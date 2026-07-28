package com.example.diabai.domain.llm

import com.example.diabai.data.DEFAULT_CLAUDE_BASE_URL
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
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

private const val ANTHROPIC_VERSION = "2023-06-01"

/** Anthropic Claude via the streaming Messages API -- also covers any Anthropic-compatible
 * gateway that mirrors this exact wire format (tool_use/tool_result content blocks, the same
 * `x-api-key`/`anthropic-version`/`content-type` headers, SSE streaming), e.g. a "OneProvider"
 * gateway pointed at via [baseUrl] instead of Anthropic directly -- see
 * [com.example.diabai.data.AppSettings.claudeBaseUrl].
 * Tool-call arguments arrive as `input_json_delta` string fragments across multiple
 * `content_block_delta` events and are only valid JSON once fully reassembled, so they're
 * buffered (keyed by each block's `index`, since Claude's own parallel tool use can stream several
 * `tool_use` blocks interleaved in one turn) and parsed at the end of the turn. */
class ClaudeApiProvider(
    private val httpClient: HttpClient,
    private val apiKey: String,
    baseUrl: String = DEFAULT_CLAUDE_BASE_URL,
    /** Resolved by [ModelCatalog] before this provider is constructed -- see
     * [LLMProviderManager.resolve]. Defaults to the fast/light model for callers (like
     * "Key testen") that don't care which model answers, only whether the key works. */
    private val model: String = ModelCatalog.optionsFor(LlmProviderType.CLAUDE).first().id,
    private val json: Json = Json { ignoreUnknownKeys = true; explicitNulls = false; isLenient = true },
) : LLMProvider {
    private val effectiveBaseUrl: String = baseUrl.trim().ifBlank { DEFAULT_CLAUDE_BASE_URL }.trimEnd('/')

    override val isReady: Boolean get() = apiKey.isNotBlank()
    override val notReadyReason: String = "Kein Claude API-Key hinterlegt"

    override fun startConversation(systemPrompt: String, tools: List<LlmToolSpec>): LlmConversation? {
        if (!isReady) return null
        return ClaudeConversation(httpClient, apiKey, effectiveBaseUrl, model, systemPrompt, tools, json)
    }

    override suspend fun testConnection(): Result<Unit> = runCatching {
        require(apiKey.isNotBlank()) { notReadyReason }
        val response = httpClient.get("$effectiveBaseUrl/models") {
            header("x-api-key", apiKey)
            header("anthropic-version", ANTHROPIC_VERSION)
        }
        if (!response.status.isSuccess()) throw HttpStatusException(response.status.value, response.bodyAsText())
    }
}

private class ClaudeConversation(
    private val httpClient: HttpClient,
    private val apiKey: String,
    private val baseUrl: String,
    private val model: String,
    private val systemPrompt: String,
    private val tools: List<LlmToolSpec>,
    private val json: Json,
) : LlmConversation {
    private val messages = mutableListOf<JsonObject>()

    override fun sendUserMessage(text: String): Flow<LlmStreamEvent> {
        messages += buildJsonObject {
            put("role", "user")
            putJsonArray("content") { addJsonObject { put("type", "text"); put("text", text) } }
        }
        return streamTurn()
    }

    /** All of a turn's `tool_use` blocks must be answered together as multiple `tool_result`
     * blocks in ONE user message -- Claude rejects a follow-up that only answers some of them. */
    override fun sendToolResults(results: List<ToolResultPayload>): Flow<LlmStreamEvent> {
        messages += buildJsonObject {
            put("role", "user")
            putJsonArray("content") {
                results.forEach { result ->
                    addJsonObject {
                        put("type", "tool_result")
                        put("tool_use_id", result.toolCallId)
                        put("content", result.resultText)
                    }
                }
            }
        }
        return streamTurn()
    }

    override fun close() = Unit

    private class ToolUseAccumulator(val id: String, val name: String) {
        val args: StringBuilder = StringBuilder()
    }

    private fun streamTurn(): Flow<LlmStreamEvent> = flow {
        val requestBody = buildJsonObject {
            put("model", model)
            put("max_tokens", LLM_MAX_TOKENS)
            put("temperature", LLM_TEMPERATURE)
            put("system", systemPrompt)
            put("messages", JsonArray(messages))
            put("stream", true)
            if (tools.isNotEmpty()) {
                putJsonArray("tools") {
                    tools.forEach { tool ->
                        addJsonObject {
                            put("name", tool.name)
                            tool.description?.let { put("description", it) }
                            put("input_schema", tool.parametersSchema)
                        }
                    }
                }
            }
        }

        val answerText = StringBuilder()
        val toolUseByIndex = sortedMapOf<Int, ToolUseAccumulator>()
        var promptTokens = 0
        var completionTokens = 0

        httpClient.preparePost("$baseUrl/messages") {
            header("x-api-key", apiKey)
            header("anthropic-version", ANTHROPIC_VERSION)
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
                if (data.isEmpty()) continue
                val event = runCatching { json.parseToJsonElement(data).jsonObjectOrNull }.getOrNull() ?: continue
                when (event["type"]?.jsonPrimitive?.content) {
                    "message_start" -> {
                        event["message"]?.jsonObjectOrNull?.get("usage")?.jsonObjectOrNull?.get("input_tokens")
                            ?.jsonPrimitive?.intOrNull?.let { promptTokens = it }
                    }
                    "message_delta" -> {
                        // Cumulative for the whole turn, not incremental -- this later value
                        // (arriving once, near the end of the stream) simply overwrites, no sum.
                        event["usage"]?.jsonObjectOrNull?.get("output_tokens")?.jsonPrimitive?.intOrNull
                            ?.let { completionTokens = it }
                    }
                    "content_block_start" -> {
                        val index = event["index"]?.jsonPrimitive?.intOrNull ?: 0
                        val block = event["content_block"]?.jsonObjectOrNull
                        if (block != null && block["type"]?.jsonPrimitive?.content == "tool_use") {
                            val id = block["id"]?.jsonPrimitive?.content
                            val name = block["name"]?.jsonPrimitive?.content
                            if (id != null && name != null) toolUseByIndex[index] = ToolUseAccumulator(id, name)
                        }
                    }
                    "content_block_delta" -> {
                        val index = event["index"]?.jsonPrimitive?.intOrNull ?: 0
                        val delta = event["delta"]?.jsonObjectOrNull
                        if (delta != null) {
                            when (delta["type"]?.jsonPrimitive?.content) {
                                "text_delta" -> delta["text"]?.jsonPrimitive?.content?.let { text ->
                                    answerText.append(text)
                                    emit(LlmStreamEvent.TextDelta(text))
                                }
                                "input_json_delta" -> delta["partial_json"]?.jsonPrimitive?.content?.let {
                                    toolUseByIndex[index]?.args?.append(it)
                                }
                            }
                        }
                    }
                }
            }
        }

        val requestedCalls = toolUseByIndex.values.map { acc ->
            val argsElement = (runCatching { json.parseToJsonElement(acc.args.toString().ifBlank { "{}" }) }.getOrNull() as? JsonObject)
                ?: JsonObject(emptyMap())
            RequestedToolCall(acc.id, acc.name, argsElement)
        }

        if (promptTokens > 0 || completionTokens > 0) {
            emit(LlmStreamEvent.Usage(promptTokens, completionTokens))
        }

        messages += buildJsonObject {
            put("role", "assistant")
            putJsonArray("content") {
                if (answerText.isNotEmpty()) addJsonObject { put("type", "text"); put("text", answerText.toString()) }
                requestedCalls.forEach { rc ->
                    addJsonObject {
                        put("type", "tool_use")
                        put("id", rc.id)
                        put("name", rc.name)
                        put("input", rc.arguments)
                    }
                }
            }
        }

        if (requestedCalls.isNotEmpty()) {
            emit(LlmStreamEvent.ToolCallRequested(requestedCalls))
        }
    }
}
