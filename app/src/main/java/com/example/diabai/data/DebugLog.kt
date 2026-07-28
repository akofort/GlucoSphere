package com.example.diabai.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Same rolling-window reasoning as [LLM_REQUEST_LOG_MAX_ENTRIES] -- a diagnostic aid, not a
 * permanent audit trail. */
const val DEBUG_LOG_MAX_ENTRIES = 300

/**
 * "Debug-Log (Entwickler)" in Performance-Log: a chronological trace of individual LLM-agent
 * events (provider/model resolved, each tool call, every raw error) -- unlike
 * [LlmRequestLogEntry] (one row per whole exchange, always recorded), this is only ever appended
 * to while [AppSettings.debugLoggingEnabled] is on, since it can be considerably more verbose and
 * is meant for actively chasing down a specific bug, not left running by default. Gated to
 * [LicenseTier.DEVELOPER] in the UI (see PerformanceLogScreen) -- the toggle and "Teilen" export
 * are conveniences for someone debugging the app itself, not a Free/Test/User-facing feature.
 */
@Serializable
data class DebugLogEntry(val timestampMillis: Long, val message: String)

private val debugLogJson = Json { ignoreUnknownKeys = true }

fun List<DebugLogEntry>.encodeToJson(): String = debugLogJson.encodeToString(this)

fun String.decodeDebugLog(): List<DebugLogEntry> =
    if (isBlank()) {
        emptyList()
    } else {
        runCatching { debugLogJson.decodeFromString<List<DebugLogEntry>>(this) }.getOrElse { emptyList() }
    }

fun List<DebugLogEntry>.addDebugEntry(entry: DebugLogEntry): List<DebugLogEntry> =
    (listOf(entry) + this).take(DEBUG_LOG_MAX_ENTRIES)
