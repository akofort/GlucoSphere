package com.example.diabai.domain.llm

import com.example.diabai.data.DEFAULT_OPENAI_BASE_URL
import com.example.diabai.data.LlmProviderType
import com.example.diabai.network.HttpStatusException
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** OpenAI-compatible Chat Completions API -- also covers OpenRouter and any other host that
 * mirrors this wire format, hence the configurable [baseUrl]. Tool-call arguments stream as
 * `arguments` string fragments per `tool_calls[].index` -- ALL indices are tracked (not just 0),
 * so a model requesting several tools in parallel in one turn is fully captured; see
 * [DiabetesAgent.runToolLoop][com.example.diabai.domain.DiabetesAgent] for where those get
 * dispatched concurrently instead of one network round trip per call. */
class OpenAiApiProvider(
    private val httpClient: HttpClient,
    private val apiKey: String,
    baseUrl: String = DEFAULT_OPENAI_BASE_URL,
    /** Resolved by [ModelCatalog] before this provider is constructed -- see
     * [LLMProviderManager.resolve]. Defaults to the fast/light model for callers (like
     * "Key testen") that don't care which model answers, only whether the key works. */
    private val model: String = ModelCatalog.optionsFor(LlmProviderType.OPENAI).first().id,
    private val json: Json = Json { ignoreUnknownKeys = true; explicitNulls = false; isLenient = true },
) : LLMProvider {
    private val effectiveBaseUrl: String = baseUrl.trim().ifBlank { DEFAULT_OPENAI_BASE_URL }.trimEnd('/')

    override val isReady: Boolean get() = apiKey.isNotBlank()
    override val notReadyReason: String = "Kein OpenAI/OpenRouter API-Key hinterlegt"

    override fun startConversation(systemPrompt: String, tools: List<LlmToolSpec>): LlmConversation? {
        if (!isReady) return null
        return OpenAiConversation(httpClient, apiKey, effectiveBaseUrl, model, systemPrompt, tools, json)
    }

    override suspend fun testConnection(): Result<Unit> = runCatching {
        require(apiKey.isNotBlank()) { notReadyReason }
        val response = httpClient.get("$effectiveBaseUrl/models") {
            header("Authorization", "Bearer $apiKey")
        }
        if (!response.status.isSuccess()) throw HttpStatusException(response.status.value, response.bodyAsText())
    }
}

private class OpenAiConversation(
    private val httpClient: HttpClient,
    private val apiKey: String,
    private val baseUrl: String,
    private val model: String,
    systemPrompt: String,
    private val tools: List<LlmToolSpec>,
    private val json: Json,
) : LlmConversation {
    private val messages = mutableListOf(
        buildJsonObject { put("role", "system"); put("content", systemPrompt) },
    )

    override fun sendUserMessage(text: String): Flow<LlmStreamEvent> {
        messages += buildJsonObject { put("role", "user"); put("content", text) }
        return streamTurn()
    }

    /** All of a turn's tool results MUST be replayed together as separate `role: "tool"` messages
     * before the next request -- OpenAI-compatible APIs reject a follow-up that only answers some
     * of the previous turn's `tool_calls`. */
    override fun sendToolResults(results: List<ToolResultPayload>): Flow<LlmStreamEvent> {
        results.forEach { result ->
            messages += buildJsonObject {
                put("role", "tool")
                put("tool_call_id", result.toolCallId)
                put("content", result.resultText)
            }
        }
        return streamTurn()
    }

    override fun close() = Unit

    /** Accumulates one `tool_calls[]` entry's streamed fragments, keyed by its `index` (see
     * [streamTurn]) -- OpenAI-compatible parallel tool calling streams each call's `arguments` as
     * fragments tagged with its own array index, all interleaved across the same SSE chunks. */
    private class ToolCallAccumulator {
        var id: String? = null
        var name: String? = null
        val args: StringBuilder = StringBuilder()
    }

    private fun streamTurn(): Flow<LlmStreamEvent> = flow {
        val requestBody = buildJsonObject {
            put("model", model)
            put("messages", JsonArray(messages))
            put("stream", true)
            put("temperature", LLM_TEMPERATURE)
            put("max_tokens", LLM_MAX_TOKENS)
            // Without this, OpenAI-compatible SSE streams omit "usage" entirely -- it's what
            // makes the final chunk (empty "choices", populated "usage") arrive at all.
            putJsonObject("stream_options") { put("include_usage", true) }
            if (tools.isNotEmpty()) {
                putJsonArray("tools") {
                    tools.forEach { tool ->
                        addJsonObject {
                            put("type", "function")
                            putJsonObject("function") {
                                put("name", tool.name)
                                tool.description?.let { put("description", it) }
                                put("parameters", tool.parametersSchema)
                            }
                        }
                    }
                }
            }
        }

        val answerText = StringBuilder()
        // DeepSeek's "thinking mode" (deepseek-reasoner and similar reasoning models routed
        // through OpenRouter) streams its chain-of-thought as a SEPARATE `reasoning_content`
        // delta field, alongside (not inside) the normal `content` field -- distinct from this
        // app's own `<think>...</think>`-tag convention for the local model. DeepSeek's API
        // rejects the next request in a tool-calling round with "The reasoning_content in the
        // thinking mode must be passed back to the API" unless that exact reasoning_content is
        // replayed back on the assistant message it belongs to (see the `messages +=` below) --
        // dropping it (as this code previously did, since nothing here even looked at the field)
        // broke every DeepSeek-reasoner tool call outright.
        val reasoningText = StringBuilder()
        val toolCallsByIndex = sortedMapOf<Int, ToolCallAccumulator>()
        var promptTokens = 0
        var completionTokens = 0
        var resolvedModel: String? = null

        httpClient.preparePost("$baseUrl/chat/completions") {
            header("Authorization", "Bearer $apiKey")
            contentType(ContentType.Application.Json)
            setBody(requestBody.toString())
        }.execute { response ->
            if (!response.status.isSuccess()) {
                throw HttpStatusException(response.status.value, response.bodyAsText())
            }
            val channel = response.bodyAsChannel()
            while (!channel.isClosedForRead) {
                val line = channel.readUTF8Line() ?: break
                if (!line.startsWith("data:")) continue
                val data = line.removePrefix("data:").trim()
                if (data.isEmpty() || data == "[DONE]") continue
                val chunk = runCatching { json.parseToJsonElement(data).jsonObjectOrNull }.getOrNull() ?: continue
                // Both captured before the `delta ?: continue` below -- the final usage-only
                // chunk (from "stream_options.include_usage") has an empty "choices" array and no
                // delta at all, and would otherwise never be inspected. "usage" is `null` (not
                // absent) on every non-final chunk once stream_options.include_usage is set --
                // documented OpenAI-compatible behavior, not malformed data -- so this MUST use
                // the null-safe accessor, not the throwing `.jsonObject` getter (see JsonSafety.kt).
                chunk["usage"]?.jsonObjectOrNull?.let { usage ->
                    usage["prompt_tokens"]?.jsonPrimitive?.intOrNull?.let { promptTokens = it }
                    usage["completion_tokens"]?.jsonPrimitive?.intOrNull?.let { completionTokens = it }
                }
                chunk["model"]?.jsonPrimitive?.contentOrNull?.let { resolvedModel = it }
                val delta = chunk["choices"]?.jsonArrayOrNull?.firstOrNull()?.jsonObjectOrNull?.get("delta")?.jsonObjectOrNull ?: continue
                delta["content"]?.jsonPrimitive?.contentOrNull?.let { text ->
                    if (text.isNotEmpty()) {
                        answerText.append(text)
                        emit(LlmStreamEvent.TextDelta(text))
                    }
                }
                // Not surfaced to the UI (that's the unrelated `<think>` local-model tag path) --
                // captured purely so it can be replayed back on this exact assistant message below.
                delta["reasoning_content"]?.jsonPrimitive?.contentOrNull?.let { text ->
                    if (text.isNotEmpty()) reasoningText.append(text)
                }
                // Every entry in this array (not just the first) is a fragment of one of
                // potentially several parallel tool calls -- "index" is the stable key that
                // reassembles each call's fragments across chunks (a single call's own
                // "arguments" fragments always share the same index, interleaved with any other
                // parallel calls' fragments in between).
                delta["tool_calls"]?.jsonArrayOrNull?.forEach { callElement ->
                    val call = callElement.jsonObjectOrNull ?: return@forEach
                    val index = call["index"]?.jsonPrimitive?.intOrNull ?: 0
                    val acc = toolCallsByIndex.getOrPut(index) { ToolCallAccumulator() }
                    call["id"]?.jsonPrimitive?.contentOrNull?.let { acc.id = it }
                    val function = call["function"]?.jsonObjectOrNull
                    function?.get("name")?.jsonPrimitive?.contentOrNull?.let { acc.name = it }
                    function?.get("arguments")?.jsonPrimitive?.contentOrNull?.let { acc.args.append(it) }
                }
            }
        }

        if (promptTokens > 0 || completionTokens > 0) {
            emit(LlmStreamEvent.Usage(promptTokens, completionTokens))
        }
        // Only meaningful (and only ever different from the requested id) for the
        // "openrouter/auto-beta" routing model -- a pinned model's response just echoes the same
        // id back, which would be redundant noise to surface in the UI.
        resolvedModel?.takeIf { it != model }?.let { emit(LlmStreamEvent.ModelResolved(it)) }

        // Only complete accumulators (both id and name actually arrived) become real requested
        // calls -- an index with only e.g. a stray "arguments" fragment and nothing else is not
        // a valid call to replay back to the API.
        val requestedCalls = toolCallsByIndex.values.mapNotNull { acc ->
            val id = acc.id ?: return@mapNotNull null
            val name = acc.name ?: return@mapNotNull null
            val argsElement = (runCatching { json.parseToJsonElement(acc.args.toString().ifBlank { "{}" }) }.getOrNull() as? JsonObject)
                ?: JsonObject(emptyMap())
            RequestedToolCall(id, name, argsElement)
        }

        messages += buildJsonObject {
            put("role", "assistant")
            put("content", answerText.toString())
            // Only added when the model actually streamed reasoning for this turn (non-thinking
            // models/turns never populate `reasoning_content` at all) -- omitted rather than
            // forced to `""` on every assistant message across every OpenAI-compatible provider
            // this class also serves (OpenAI, OpenRouter), which never asked for this field.
            if (reasoningText.isNotEmpty()) {
                put("reasoning_content", reasoningText.toString())
            }
            if (requestedCalls.isNotEmpty()) {
                putJsonArray("tool_calls") {
                    requestedCalls.forEach { rc ->
                        addJsonObject {
                            put("id", rc.id)
                            put("type", "function")
                            putJsonObject("function") {
                                put("name", rc.name)
                                put("arguments", rc.arguments.toString())
                            }
                        }
                    }
                }
            }
        }

        if (requestedCalls.isNotEmpty()) {
            emit(LlmStreamEvent.ToolCallRequested(requestedCalls))
        }
    }
}
