package com.example.diabai.domain.llm

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonObject

/** Fixed, low generation temperature for EVERY provider (local and cloud) -- medical/glycemic
 * figures need factual precision over creative variety, so this is not user-configurable.
 * [LLM_MAX_TOKENS] is likewise shared by every cloud provider's request payload. */
const val LLM_TEMPERATURE = 0.2
const val LLM_MAX_TOKENS = 4096

/** Provider-agnostic tool declaration, derived from an MCP tool's JSON-Schema description. */
data class LlmToolSpec(val name: String, val description: String?, val parametersSchema: JsonObject)

/** One tool call out of a (possibly parallel) batch the model requested in a single turn. [id]
 * correlates it to the matching result sent back via [LlmConversation.sendToolResults] --
 * OpenAI/Claude use their own real `id`/`tool_use_id`; Gemini and the local LiteRT-LM model have
 * no such concept, so their providers use the tool [name] itself as a synthetic id (both require
 * only the name to match a result back to a call anyway). */
data class RequestedToolCall(val id: String, val name: String, val arguments: JsonObject)

/** One tool's result, ready to send back via [LlmConversation.sendToolResults] -- [toolCallId]
 * must match the [RequestedToolCall.id] it answers; [toolName] is carried alongside for providers
 * (Gemini, local) whose wire format keys results by name rather than id. */
data class ToolResultPayload(val toolCallId: String, val toolName: String, val resultText: String)

sealed interface LlmStreamEvent {
    data class TextDelta(val delta: String) : LlmStreamEvent

    /** [calls] holds every tool call requested THIS turn -- one entry for the common single-call
     * case, more than one when the model requested several in parallel (see
     * [com.example.diabai.domain.DiabetesAgent.runToolLoop], which now dispatches the whole batch
     * concurrently via `async`/`awaitAll` instead of one network round trip per call). */
    data class ToolCallRequested(val calls: List<RequestedToolCall>) : LlmStreamEvent

    /** Emitted at most once per turn, only by cloud providers that reported token usage for this
     * exact turn (see each provider's `streamTurn`) -- the local [LiteRTProvider] never emits
     * this, since on-device inference costs nothing and never leaves the phone. Consumed by
     * [com.example.diabai.domain.DiabetesAgent] to feed
     * [com.example.diabai.data.SettingsRepository.recordLlmUsage]. */
    data class Usage(val promptTokens: Int, val completionTokens: Int) : LlmStreamEvent

    /** OpenRouter's `openrouter/auto-beta` model id dynamically routes each request to whichever
     * underlying model it picks -- this surfaces which one actually answered (from the response's
     * own `model` field) so the UI can show "Auto-routed zu: {actual_model}" instead of just the
     * opaque "auto" id the user picked. Only [OpenAiApiProvider] ever emits this, and only when
     * the response's `model` differs from what was requested. */
    data class ModelResolved(val modelId: String) : LlmStreamEvent
}

/**
 * One provider-agnostic multi-turn conversation. Mirrors LiteRT-LM's own stateful `Conversation`
 * shape so [LiteRTProvider] can wrap it directly with no overhead; the cloud providers implement
 * this by accumulating the message history themselves and replaying it on every REST call (each
 * is a stateless API).
 *
 * Each returned flow emits zero or more [LlmStreamEvent.TextDelta] as the answer streams in,
 * then at most one [LlmStreamEvent.ToolCallRequested] once every requested call's argument JSON
 * is fully assembled (some providers stream tool-call arguments as string fragments that aren't
 * valid JSON until the last one arrives), then completes normally. A network/parse failure throws
 * out of the flow.
 */
interface LlmConversation {
    fun sendUserMessage(text: String): Flow<LlmStreamEvent>

    /** Answers every call from the most recent [LlmStreamEvent.ToolCallRequested] batch in ONE
     * follow-up turn -- each provider's wire format requires all of a turn's tool results to be
     * replayed together (a "next round" with only some of them answered is invalid), so this
     * takes the whole batch rather than being called once per result. */
    fun sendToolResults(results: List<ToolResultPayload>): Flow<LlmStreamEvent>
    fun close()
}

interface LLMProvider {
    /** Whether this provider has what it needs to run right now (model loaded / API key set). */
    val isReady: Boolean

    /** Human-readable reason [isReady] is false, surfaced as a chat error when it's used anyway. */
    val notReadyReason: String

    /** Starts a fresh conversation, or null if [isReady] is false. */
    fun startConversation(systemPrompt: String, tools: List<LlmToolSpec>): LlmConversation?

    /** Cheap, side-effect-free round trip used by "Key testen" -- proves the key/endpoint is
     * accepted without spending generation tokens. */
    suspend fun testConnection(): Result<Unit>
}
