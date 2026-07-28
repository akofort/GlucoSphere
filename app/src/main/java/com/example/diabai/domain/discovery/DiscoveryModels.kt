package com.example.diabai.domain.discovery

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * "Discovery Modus" (Selbsterkundung für neue MCP-Server und APIs): everything a newly connected
 * data source was found to offer, plus (once profiled, see [DiscoveryProfile]) an LLM-written,
 * human-readable explanation of what it's good for. Persisted per data-source id -- see
 * [com.example.diabai.data.SettingsRepository.saveDiscoveryRecord] -- rather than folded into
 * [com.example.diabai.data.AppSettings] itself: like the Übersicht dashboard cache, this changes
 * on its own schedule (re-run "Erkunden", or a fresh install) and is read on demand (opening the
 * Discovery-Sheet, populating the chat's tool-chip row), not on every settings recomposition.
 *
 * No Room database here despite the request asking for one -- this app has no other persistent
 * relational data and no dependency on Room at all; a JSON blob in the same DataStore every other
 * piece of app state already lives in (see [com.example.diabai.data.ChatSession]/
 * [com.example.diabai.domain.analytics.DiabetesDashboard]'s identical cache pattern) keeps this
 * consistent with the rest of the app's persistence architecture instead of introducing a second,
 * differently-shaped storage mechanism for a single small map of records.
 */
@Serializable
data class DiscoveredParameter(
    val name: String,
    /** JSON-Schema `type` as reported by the tool (e.g. "string", "integer", "boolean", "object",
     * "array") -- kept as the raw string rather than a closed enum since MCP servers are free to
     * report anything valid JSON-Schema allows. */
    val type: String,
    val description: String? = null,
    val required: Boolean = false,
)

@Serializable
data class DiscoveredToolSchema(
    val name: String,
    val description: String? = null,
    val parameters: List<DiscoveredParameter> = emptyList(),
    /** Best-effort heuristic (see [DiscoveryService.looksWriteCapable]) -- true if the tool's own
     * name/description suggests it mutates data (e.g. "add_treatment", "create_note") rather than
     * only reading it. Feeds [DiscoveredSchema.readOnly]. */
    val writeCapable: Boolean = false,
)

/** One REST-side capability/plugin Discovery Modus recognized -- e.g. Nightscout's `careportal`,
 * `openaps`, `iob`, `cob`, `sage`, `cage` plugins from `/api/v1/status.json`'s `settings.enable`
 * list (see [com.example.diabai.network.fetchNightscoutStatus]). */
@Serializable
data class DiscoveredRestCapability(
    val id: String,
    val description: String,
    val writeCapable: Boolean = false,
)

/** Everything Discovery Modus found for one data source -- MCP `tools/list` (+ `resources/list`
 * if the server supports it) for an MCP server, or `/api/v1/status.json` plugin introspection for
 * the direct Nightscout REST API. [readOnly] is derived, not asked of anything: true unless at
 * least one discovered tool/capability looks write-capable. */
@Serializable
data class DiscoveredSchema(
    val serverId: String,
    val serverName: String,
    val discoveredAtMillis: Long,
    val tools: List<DiscoveredToolSchema> = emptyList(),
    /** MCP `resources/list` entries, as plain "name (uri)" labels -- kept simple since this app
     * doesn't otherwise consume MCP resources, only tools. */
    val resources: List<String> = emptyList(),
    val restCapabilities: List<DiscoveredRestCapability> = emptyList(),
) {
    val readOnly: Boolean
        get() = tools.none { it.writeCapable } && restCapabilities.none { it.writeCapable }
}

/** The LLM-generated "explain this API to the user" result for one [DiscoveredSchema] -- built
 * from the specialized profiling prompt in [DiscoveryService.buildProfilingPrompt] and cached
 * alongside the schema so it's not regenerated (and doesn't cost tokens again) on every visit to
 * the Discovery-Sheet, only when the user explicitly re-runs "Erkunden". */
@Serializable
data class DiscoveryProfile(
    val summary: String = "",
    val exampleQuestions: List<String> = emptyList(),
    val keyReturnValues: List<String> = emptyList(),
    val generatedAtMillis: Long = 0L,
)

@Serializable
data class DiscoveryRecord(
    val schema: DiscoveredSchema,
    val profile: DiscoveryProfile? = null,
)

private val discoveryJson = Json { ignoreUnknownKeys = true }

/** Keyed by data-source id (an [com.example.diabai.data.McpServerConfig.id], including the
 * synthetic [com.example.diabai.network.NIGHTSCOUT_DIRECT_SERVER_ID] for the direct API) -- one
 * JSON blob for the whole map, same shape [com.example.diabai.data.ChatSession]'s list already
 * uses for its own DataStore key. */
fun Map<String, DiscoveryRecord>.encodeToJson(): String = discoveryJson.encodeToString(this)

fun String.decodeDiscoveryRecords(): Map<String, DiscoveryRecord> =
    if (isBlank()) {
        emptyMap()
    } else {
        runCatching { discoveryJson.decodeFromString<Map<String, DiscoveryRecord>>(this) }.getOrElse { emptyMap() }
    }
