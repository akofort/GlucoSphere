package com.example.diabai.domain.llm

import com.example.diabai.data.LlmProviderType
import com.example.diabai.network.HttpStatusException
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
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
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

private const val GEMINI_BASE = "https://generativelanguage.googleapis.com/v1beta"

/** Google Gemini via the `streamGenerateContent` REST endpoint. Function-call arguments arrive
 * as one complete JSON object per chunk (Gemini doesn't fragment them like OpenAI/Anthropic
 * do), so no cross-chunk reassembly is needed for tool calls -- only for the answer text. Gemini
 * supports parallel function calling (multiple `functionCall` parts in one response); every part
 * is collected, not just the first, and answered back in one batched `functionResponse` turn. */
class GeminiApiProvider(
    private val httpClient: HttpClient,
    private val apiKey: String,
    /** Resolved by [ModelCatalog] before this provider is constructed -- see
     * [LLMProviderManager.resolve]. Defaults to the fast/light model for callers (like
     * "Key testen") that don't care which model answers, only whether the key works. */
    private val model: String = ModelCatalog.optionsFor(LlmProviderType.GEMINI).first().id,
    private val json: Json = Json { ignoreUnknownKeys = true; explicitNulls = false; isLenient = true },
) : LLMProvider {

    override val isReady: Boolean get() = apiKey.isNotBlank()
    override val notReadyReason: String = "Kein Gemini API-Key hinterlegt"

    override fun startConversation(systemPrompt: String, tools: List<LlmToolSpec>): LlmConversation? {
        if (!isReady) return null
        return GeminiConversation(httpClient, apiKey, model, systemPrompt, tools, json)
    }

    override suspend fun testConnection(): Result<Unit> = runCatching {
        require(apiKey.isNotBlank()) { notReadyReason }
        val response = httpClient.get("$GEMINI_BASE/models") {
            header("x-goog-api-key", apiKey)
        }
        if (!response.status.isSuccess()) throw HttpStatusException(response.status.value, response.bodyAsText())
    }
}

/** MCP tool schemas are plain JSON Schema and commonly include keywords Gemini's function-calling
 * `parameters` field -- a narrow OpenAPI 3.0 subset -- doesn't recognize at all, rejecting the
 * ENTIRE request with a 400 ("Unknown name \"X\" ... Cannot find field") the moment any ONE
 * declared tool's schema contains one anywhere, including nested inside `properties`/`items`.
 * Confirmed unsupported (via live 400 responses, not guessed): `additionalProperties` (emitted by
 * most MCP servers on every object, including nested ones -- one real failure had a hit nested two
 * levels down, `function_declarations[13].parameters.properties[0].value`), `$schema` (the JSON
 * Schema meta-schema URI most schemas open with), and `exclusiveMinimum`/`exclusiveMaximum` (JSON
 * Schema's boolean/numeric form -- Gemini only understands plain `minimum`/`maximum`). Stripped
 * recursively, not just at the top level. Other providers (Claude/OpenAI) speak full JSON Schema
 * and are unaffected -- this sanitization is Gemini-only, applied right before the request is
 * built rather than at the shared [LlmToolSpec] level. */
private val GEMINI_UNSUPPORTED_SCHEMA_KEYS = setOf("additionalProperties", "\$schema", "exclusiveMinimum", "exclusiveMaximum")

private fun JsonElement.stripUnsupportedForGemini(): JsonElement = when (this) {
    is JsonObject -> JsonObject(
        entries.filterNot { it.key in GEMINI_UNSUPPORTED_SCHEMA_KEYS }.associate { it.key to it.value.stripUnsupportedForGemini() },
    )
    is JsonArray -> JsonArray(map { it.stripUnsupportedForGemini() })
    else -> this
}

private class GeminiConversation(
    private val httpClient: HttpClient,
    private val apiKey: String,
    private val model: String,
    private val systemPrompt: String,
    private val tools: List<LlmToolSpec>,
    private val json: Json,
) : LlmConversation {
    private val contents = mutableListOf<JsonObject>()

    override fun sendUserMessage(text: String): Flow<LlmStreamEvent> {
        contents += buildJsonObject {
            put("role", "user")
            putJsonArray("parts") { addJsonObject { put("text", text) } }
        }
        return streamTurn()
    }

    /** Gemini has no separate call-id concept -- a `functionResponse` is matched back to its
     * `functionCall` purely by [ToolResultPayload.toolName], and multiple parallel calls are
     * answered together as multiple `functionResponse` parts in ONE user turn (not one turn per
     * call), matching how [streamTurn] below also collects every parallel `functionCall`. */
    override fun sendToolResults(results: List<ToolResultPayload>): Flow<LlmStreamEvent> {
        contents += buildJsonObject {
            put("role", "user")
            putJsonArray("parts") {
                results.forEach { result ->
                    addJsonObject {
                        putJsonObject("functionResponse") {
                            put("name", result.toolName)
                            putJsonObject("response") { put("content", result.resultText) }
                        }
                    }
                }
            }
        }
        return streamTurn()
    }

    override fun close() = Unit

    private fun streamTurn(): Flow<LlmStreamEvent> = flow {
        val toolsPayload: JsonArray? = if (tools.isEmpty()) null else buildJsonArray {
            addJsonObject {
                putJsonArray("functionDeclarations") {
                    tools.forEach { tool ->
                        addJsonObject {
                            put("name", tool.name)
                            tool.description?.let { put("description", it) }
                            put("parameters", tool.parametersSchema.stripUnsupportedForGemini())
                        }
                    }
                }
            }
        }
        val requestBody = buildJsonObject {
            putJsonObject("system_instruction") {
                putJsonArray("parts") { addJsonObject { put("text", systemPrompt) } }
            }
            put("contents", JsonArray(contents))
            toolsPayload?.let { put("tools", it) }
            putJsonObject("generationConfig") {
                put("temperature", LLM_TEMPERATURE)
                put("maxOutputTokens", LLM_MAX_TOKENS)
            }
        }

        val answerText = StringBuilder()
        val functionCalls = mutableListOf<GeminiFunctionCall>()
        var promptTokens = 0
        var completionTokens = 0

        httpClient.preparePost("$GEMINI_BASE/models/$model:streamGenerateContent") {
            parameter("alt", "sse")
            header("x-goog-api-key", apiKey)
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
                val chunk = runCatching { json.parseToJsonElement(data).jsonObjectOrNull }.getOrNull() ?: continue
                // Captured before the `parts ?: continue` below -- a final usage-only chunk can
                // arrive with no candidates/parts of its own, and would otherwise be skipped
                // before ever reading its usageMetadata. Null-safe accessors throughout (see
                // JsonSafety.kt) -- a present-but-JSON-null field (e.g. no usage yet) must not
                // crash the throwing `.jsonObject`/`.jsonArray` getters.
                chunk["usageMetadata"]?.jsonObjectOrNull?.let { usage ->
                    usage["promptTokenCount"]?.jsonPrimitive?.intOrNull?.let { promptTokens = it }
                    usage["candidatesTokenCount"]?.jsonPrimitive?.intOrNull?.let { completionTokens = it }
                }
                val parts = chunk["candidates"]?.jsonArrayOrNull?.firstOrNull()?.jsonObjectOrNull
                    ?.get("content")?.jsonObjectOrNull?.get("parts")?.jsonArrayOrNull ?: continue
                parts.forEach { part ->
                    val partObj = part.jsonObjectOrNull ?: return@forEach
                    partObj["text"]?.jsonPrimitive?.content?.let { text ->
                        if (text.isNotEmpty()) {
                            answerText.append(text)
                            emit(LlmStreamEvent.TextDelta(text))
                        }
                    }
                    partObj["functionCall"]?.jsonObjectOrNull?.let { call ->
                        val callName = call["name"]?.jsonPrimitive?.content
                        if (callName != null) {
                            // thoughtSignature is a SIBLING of functionCall within the same part,
                            // not nested inside it -- see the rebuild below for why it has to be
                            // captured here and replayed verbatim.
                            functionCalls += GeminiFunctionCall(
                                name = callName,
                                args = call["args"]?.jsonObjectOrNull ?: JsonObject(emptyMap()),
                                thoughtSignature = partObj["thoughtSignature"]?.jsonPrimitive?.content,
                            )
                        }
                    }
                }
            }
        }

        if (promptTokens > 0 || completionTokens > 0) {
            emit(LlmStreamEvent.Usage(promptTokens, completionTokens))
        }

        contents += buildJsonObject {
            put("role", "model")
            putJsonArray("parts") {
                if (answerText.isNotEmpty()) addJsonObject { put("text", answerText.toString()) }
                functionCalls.forEach { call ->
                    addJsonObject {
                        putJsonObject("functionCall") {
                            put("name", call.name)
                            put("args", call.args)
                        }
                        // Required by Gemini 3.x for multi-round tool calling -- omitting it on the
                        // next turn's replayed history fails the whole request with "Function call
                        // is missing a thought_signature in functionCall parts", not just a warning.
                        call.thoughtSignature?.let { put("thoughtSignature", it) }
                    }
                }
            }
        }

        if (functionCalls.isNotEmpty()) {
            emit(LlmStreamEvent.ToolCallRequested(functionCalls.map { RequestedToolCall(id = it.name, name = it.name, arguments = it.args) }))
        }
    }
}

/** [thoughtSignature] is null for any Gemini model/response that doesn't use interleaved thinking
 * (older models, or a turn where the model didn't think before calling) -- see the two use sites
 * above for why it has to survive from the response all the way back into the next request. */
private data class GeminiFunctionCall(val name: String, val args: JsonObject, val thoughtSignature: String?)
