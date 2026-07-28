package com.example.diabai.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** How many entries [List<LlmRequestLogEntry>.addEntry] keeps -- unlike [DailyLlmUsage] (bucketed
 * by day, naturally bounded), this is one row PER LLM EXCHANGE (one [com.example.diabai.domain.DiabetesAgent.ask]/
 * `askChat` call, i.e. one tool-calling round-trip conversation from user text to final answer),
 * so it needs an explicit count cap instead -- 200 comfortably covers "the last several sessions"
 * without the persisted JSON blob growing unbounded. */
const val LLM_REQUEST_LOG_MAX_ENTRIES = 200

/**
 * Performance-Log (Provider/Modell/Token/Dauer je Anfrage): one row per completed LLM exchange --
 * a single chat message turn, or one of the Übersicht tab's several per-refresh calls (current-
 * window metrics, previous-window metrics, body metrics, narrative each get their own row, which
 * is exactly the granularity needed to see e.g. "the previous-window call took 90s, the others
 * were fast" instead of only one opaque total). Recorded by [com.example.diabai.domain.DiabetesAgent.runToolLoop]
 * regardless of success or failure (a timed-out/errored call is exactly the kind of row worth
 * seeing here), never for the local on-device model (see [provider]'s doc comment).
 */
@Serializable
data class LlmRequestLogEntry(
    val timestampMillis: Long,
    /** [LlmProviderType.name] -- kept as a plain string (not a persisted @Serializable enum
     * reference) so a future rename/removal of an [LlmProviderType] entry can't fail to decode
     * old log rows; an unrecognized value just falls back to [LlmProviderType.LOCAL] on read. */
    val provider: String,
    /** Empty for [LlmProviderType.LOCAL] (no per-request model id there -- see
     * [com.example.diabai.domain.DiabetesAgent]'s own recomputation of this via
     * [com.example.diabai.domain.llm.ModelCatalog.resolve], the same logic
     * [com.example.diabai.domain.llm.LLMProviderManager.resolve] already used to pick the
     * provider instance for this exact exchange). */
    val model: String,
    /** [com.example.diabai.domain.llm.LlmPurpose.name] -- "CHAT" or "ANALYSIS", so a slow
     * Übersicht/report row can be told apart from a slow chat turn at a glance. */
    val purpose: String,
    val promptTokens: Long,
    val completionTokens: Long,
    /** How many tool calls this one exchange actually dispatched across all its rounds --
     * "Anfrage-Menge": a slow exchange with a high count points at the tool-calling loop itself
     * (many round trips), a slow exchange with 0 tool calls points at the model's own generation
     * time instead. */
    val toolCallCount: Int,
    val durationMillis: Long,
    /** Null on success. The raw failure message (e.g. a provider's own HTTP error body) when this
     * exchange ended in [com.example.diabai.domain.AgentEvent.Error] instead of a normal answer --
     * lets a failed row be told apart from a merely slow-but-successful one in the Performance-Log,
     * and gives "Teilen" something concrete to hand a developer instead of just a duration number. */
    val error: String? = null,
) {
    val providerType: LlmProviderType get() = runCatching { LlmProviderType.valueOf(provider) }.getOrDefault(LlmProviderType.LOCAL)
}

private val llmRequestLogJson = Json { ignoreUnknownKeys = true }

fun List<LlmRequestLogEntry>.encodeToJson(): String = llmRequestLogJson.encodeToString(this)

fun String.decodeLlmRequestLog(): List<LlmRequestLogEntry> =
    if (isBlank()) {
        emptyList()
    } else {
        runCatching { llmRequestLogJson.decodeFromString<List<LlmRequestLogEntry>>(this) }.getOrElse { emptyList() }
    }

/** Appends [entry], newest first, capped to [LLM_REQUEST_LOG_MAX_ENTRIES] (oldest rows silently
 * drop off once the log is full -- this is a rolling diagnostic window, not a permanent audit
 * trail). */
fun List<LlmRequestLogEntry>.addEntry(entry: LlmRequestLogEntry): List<LlmRequestLogEntry> =
    (listOf(entry) + this).take(LLM_REQUEST_LOG_MAX_ENTRIES)
