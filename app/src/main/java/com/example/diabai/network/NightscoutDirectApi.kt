package com.example.diabai.network

import com.example.diabai.data.AppSettings
import com.example.diabai.data.AuthMethod
import com.example.diabai.data.McpDataCategory
import com.example.diabai.data.McpServerConfig
import com.example.diabai.data.buildHeader
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.url
import io.ktor.client.statement.bodyAsText
import io.ktor.http.encodedPath
import io.ktor.http.isSuccess
import io.ktor.http.takeFrom
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Wires a directly-configured Nightscout REST API (no MCP server involved, see the "Nightscout
 * REST-API (optional)" section of DataSourcesScreen) into the exact same per-server tool-calling
 * machinery every real MCP server goes through ([com.example.diabai.network.McpServerPool],
 * [com.example.diabai.domain.DiabetesAgent]) -- before this, [AppSettings.nightscoutApiUrl] was
 * only ever used for the settings screen's own "Verbindung testen" probe and never actually
 * queried for dashboard/chat data at all. [NIGHTSCOUT_DIRECT_SERVER_ID] is a synthetic server id
 * that [com.example.diabai.domain.DiabetesAgent] special-cases at the two points where "which
 * server does this tool call belong to" actually matters -- tool listing and tool execution --
 * so from the Übersicht/chat filter chips' point of view it behaves exactly like any other
 * configured MCP server (own chip, own category throttling, own per-source dashboard fetch).
 */
const val NIGHTSCOUT_DIRECT_SERVER_ID = "nightscout-direct-api"
const val NIGHTSCOUT_DIRECT_TOOL_NAME = "get_glucose_entries"

/** True for the one synthetic direct-REST-API source ([NIGHTSCOUT_DIRECT_SERVER_ID]); false for
 * every real MCP server. The only REST-type source that currently exists -- used wherever the UI
 * needs to tell "direct API" and "MCP server" apart (color-coding, the "⚡ Direkt-API" hint, see
 * DataSourcesScreen/DashboardSection/ChatSection). */
val McpServerConfig.isRestApi: Boolean get() = id == NIGHTSCOUT_DIRECT_SERVER_ID

/** [AppSettings]'s direct-Nightscout-API fields, reshaped as an [McpServerConfig] so it can sit
 * in the same server list the dashboard/chat filter chips and per-category ">5 Tage" throttling
 * already operate on -- null if no direct API URL is configured. [nightscoutApiName] lets the
 * user rename this source (e.g. just "Nightscout" instead of the technical-sounding default) the
 * same way any real MCP server's name is already editable. */
fun AppSettings.nightscoutDirectServer(): McpServerConfig? {
    if (nightscoutApiUrl.isBlank()) return null
    return McpServerConfig(
        id = NIGHTSCOUT_DIRECT_SERVER_ID,
        name = nightscoutApiName.ifBlank { "Nightscout" },
        url = nightscoutApiUrl,
        authMethod = nightscoutApiAuthMethod,
        token = nightscoutApiSecret,
        category = McpDataCategory.GLUCOSE_TREATMENTS,
        enabled = nightscoutApiEnabled,
    )
}

/** Every data source the app can currently query -- real MCP servers plus, if configured, the
 * direct Nightscout REST API -- the single list every server-selection UI/heuristic (Übersicht
 * chips, chat chips, single-select-per-category throttling, contextual server routing) should
 * iterate instead of [AppSettings.mcpServers] alone. */
val AppSettings.allDataSourceServers: List<McpServerConfig>
    get() = mcpServers + listOfNotNull(nightscoutDirectServer())

/** The one synthetic tool the direct Nightscout API exposes -- deliberately just one, mirroring
 * how a real Nightscout MCP server's own glucose-history tool is used elsewhere in this app. */
val nightscoutDirectTool: McpTool = McpTool(
    name = NIGHTSCOUT_DIRECT_TOOL_NAME,
    description = "Ruft rohe Blutzucker-Sensorwerte (sgv in mg/dL, Zeitstempel, Trendrichtung) direkt " +
        "per Nightscout REST-API (/api/v1/entries.json) fuer einen Zeitraum ab.",
    inputSchema = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("fromEpochMillis") {
                put("type", "integer")
                put("description", "Start des Zeitraums als Unix-Zeitstempel in Millisekunden")
            }
            putJsonObject("toEpochMillis") {
                put("type", "integer")
                put("description", "Ende des Zeitraums als Unix-Zeitstempel in Millisekunden")
            }
        }
        putJsonArray("required") {
            add(JsonPrimitive("fromEpochMillis"))
            add(JsonPrimitive("toEpochMillis"))
        }
    },
)

// requestTimeoutMillis alone silently did nothing useful here -- Ktor's OkHttp engine only
// overrides OkHttp's own hardcoded 10s connect/read/write defaults when connectTimeoutMillis/
// socketTimeoutMillis are ALSO set explicitly (see LLMProviderManager's identical fix for the
// OpenRouter socket-timeout bug). Left as just requestTimeoutMillis, a genuinely slow-but-alive
// Nightscout instance could still silently trip OkHttp's 10s read default well before the
// intended 15s budget -- indistinguishable from a real timeout, but for the wrong reason.
private val directHttpClient by lazy {
    HttpClient(OkHttp) {
        install(HttpTimeout) {
            requestTimeoutMillis = 15_000
            connectTimeoutMillis = 10_000
            socketTimeoutMillis = 15_000
        }
    }
}
private val entryTimeFormat = DateTimeFormatter.ofPattern("dd.MM. HH:mm").withZone(ZoneId.systemDefault())

/**
 * Points this request at `[path]` on the Nightscout instance identified by [baseUrl], while
 * preserving any query string [baseUrl] already carries -- Nightscout's common
 * "https://host/?token=xyz" share-link format embeds the auth token directly in the base URL's
 * own query, e.g. `token=xyz`. Naive string concatenation (`"$baseUrl$path"`) breaks on exactly
 * this shape: everything after the existing `?` is already the query, so the appended path text
 * gets silently absorbed into that query's *value* instead of becoming part of the URL's actual
 * path -- every such request then hits the instance's root page (a 200 OK HTML response, not the
 * intended JSON endpoint) instead of the real API, without erroring anywhere. [takeFrom] parses
 * [baseUrl] properly first (path *and* query both preserved), then [path] is appended to the
 * parsed path -- not the raw string -- so the token survives as a real query parameter.
 */
fun HttpRequestBuilder.nightscoutUrl(baseUrl: String, path: String) {
    url {
        takeFrom(baseUrl)
        encodedPath = encodedPath.trimEnd('/') + path
    }
}

/** One raw Nightscout `/api/v1/entries/sgv.json` reading -- [direction] is Nightscout's own CGM
 * trend word ("Flat", "SingleUp", "FortyFiveDown", ...), kept as-is here and mapped to a display
 * arrow at the UI/snapshot layer (see `DiabetesDashboardManager.trendArrowFor`), not here, so this
 * stays a plain, presentation-agnostic parse of the API response. */
data class NightscoutEntry(val sgvMgDl: Double, val dateMillis: Long, val direction: String)

/** The shared, single-shape parse both [fetchNightscoutDirectEntries] (plain-text tool result,
 * for the LLM) and the Übersicht tab's instant "Stufe 1" live snapshot (see
 * `DiabetesDashboardManager.fetchLiveSnapshot`, deterministic Kotlin-side TIR/hypo/hyper/etc,
 * no LLM round trip) build on. Safe to compute metrics from directly -- unlike a heterogeneous
 * MCP tool's free-form text result (see the doc comment on the now-reverted client-side metrics
 * attempt in DiabetesDashboardManager), this is *our own* request against one well-known,
 * single, fixed JSON shape (Nightscout's own `entries/sgv.json`), not third-party tool output
 * whose field names vary per server. */
suspend fun fetchNightscoutDirectRawEntries(config: McpServerConfig, fromMillis: Long, toMillis: Long): Result<List<NightscoutEntry>> = runCatching {
    val header = config.authMethod.buildHeader(config.token)
    val response = directHttpClient.get {
        nightscoutUrl(config.url, "/api/v1/entries/sgv.json")
        parameter("find[date][\$gte]", fromMillis)
        parameter("find[date][\$lte]", toMillis)
        parameter("count", 100_000)
        if (header != null) header(header.first, header.second)
    }
    if (!response.status.isSuccess()) error("Nightscout-API-Fehler: HTTP ${response.status.value}")
    val entries = Json.parseToJsonElement(response.bodyAsText()) as? JsonArray ?: JsonArray(emptyList())
    entries.mapNotNull { element ->
        val obj = element as? JsonObject ?: return@mapNotNull null
        val sgv = obj["sgv"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
        val dateMillis = obj["date"]?.jsonPrimitive?.longOrNull ?: obj["date"]?.jsonPrimitive?.doubleOrNull?.toLong() ?: return@mapNotNull null
        val direction = obj["direction"]?.jsonPrimitive?.contentOrNull ?: ""
        NightscoutEntry(sgv, dateMillis, direction)
    }
}

/** Calls Nightscout's `/api/v1/entries/sgv.json` directly (not through an MCP server) for the
 * given window and formats the result as compact "time: value mg/dL (trend)" lines -- the same
 * shape of plain-text tool result [com.example.diabai.domain.DiabetesAgent.runToolLoop] feeds
 * back to the model after any real MCP tool call, so the model computes TIR/hypo/hyper/etc from
 * it exactly the same way regardless of which kind of source answered. */
suspend fun fetchNightscoutDirectEntries(config: McpServerConfig, fromMillis: Long, toMillis: Long): Result<String> =
    fetchNightscoutDirectRawEntries(config, fromMillis, toMillis).map { entries ->
        if (entries.isEmpty()) return@map "Keine Blutzucker-Werte im angefragten Zeitraum gefunden."
        entries.joinToString("\n") { e ->
            "${entryTimeFormat.format(Instant.ofEpochMilli(e.dateMillis))}: ${e.sgvMgDl} mg/dL (${e.direction})"
        }
    }

/** Nightscout's own `/api/v1/status.json` introspection response, reduced to what Discovery Modus
 * (see [com.example.diabai.domain.discovery.DiscoveryService]) actually needs -- which optional
 * plugins (`settings.enable`, e.g. "careportal", "openaps", "iob", "cob", "sage", "cage") this
 * particular instance has turned on, and whether its write API (`apiEnabled`) is reachable at
 * all. Deliberately not a full 1:1 mapping of Nightscout's (large, loosely-specified) status
 * response -- only the fields this app's discovery step actually reads. */
@Serializable
data class NightscoutStatusInfo(
    val enabledPlugins: List<String> = emptyList(),
    val apiEnabled: Boolean = true,
    val units: String? = null,
)

/** Calls Nightscout's `/api/v1/status.json` directly -- the lightweight introspection endpoint
 * Discovery Modus uses to recognize which plugins/data structures (Careportal, OpenAPS, IOB, COB,
 * Sage, Cage, ...) a given instance actually exposes, instead of assuming every Nightscout
 * instance offers the same fixed set. No auth required by Nightscout for this endpoint on most
 * instances, but the configured header is still sent in case the instance requires it. */
suspend fun fetchNightscoutStatus(config: McpServerConfig): Result<NightscoutStatusInfo> = runCatching {
    val header = config.authMethod.buildHeader(config.token)
    val response = directHttpClient.get {
        nightscoutUrl(config.url, "/api/v1/status.json")
        if (header != null) header(header.first, header.second)
    }
    if (!response.status.isSuccess()) error("Nightscout-API-Fehler: HTTP ${response.status.value}")
    val root = Json.parseToJsonElement(response.bodyAsText()) as? JsonObject ?: JsonObject(emptyMap())
    val settingsObj = root["settings"] as? JsonObject
    val enabledPlugins = (settingsObj?.get("enable") as? JsonArray)
        ?.mapNotNull { it.jsonPrimitive.contentOrNull }
        .orEmpty()
    val apiEnabled = root["apiEnabled"]?.jsonPrimitive?.booleanOrNull ?: true
    val units = settingsObj?.get("units")?.jsonPrimitive?.contentOrNull
    NightscoutStatusInfo(enabledPlugins = enabledPlugins, apiEnabled = apiEnabled, units = units)
}

/** Parses the `fromEpochMillis`/`toEpochMillis` arguments the model is instructed to pass to
 * [NIGHTSCOUT_DIRECT_TOOL_NAME] -- falls back to the full requested window if the model omitted
 * or malformed either one, rather than failing the call outright. */
fun JsonObject.longArgOrDefault(name: String, default: Long): Long =
    this[name]?.jsonPrimitive?.let { it.longOrNull ?: it.contentOrNull?.toLongOrNull() } ?: default
