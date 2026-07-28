package com.example.diabai.domain.discovery

import com.example.diabai.data.AppSettings
import com.example.diabai.data.McpServerConfig
import com.example.diabai.data.SettingsRepository
import com.example.diabai.domain.llm.LLMProviderManager
import com.example.diabai.domain.llm.LlmPurpose
import com.example.diabai.domain.llm.LlmStreamEvent
import com.example.diabai.network.McpServerPool
import com.example.diabai.network.McpTool
import com.example.diabai.network.NIGHTSCOUT_DIRECT_TOOL_NAME
import com.example.diabai.network.fetchNightscoutStatus
import com.example.diabai.network.nightscoutDirectTool
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** A tool/plugin name/description looking like it *writes* data (as opposed to only reading it)
 * -- "add", "create", "log", "post", ... -- used to derive [DiscoveredSchema.readOnly] without
 * needing every MCP server to formally declare it (MCP's own tool schema has no such flag). */
private val writeVerbRegex = Regex("""(?i)\b(add|create|delete|remove|update|insert|write|post|log|set[^t]|save|submit|enter)""")

private fun looksWriteCapable(name: String, description: String?): Boolean =
    writeVerbRegex.containsMatchIn(name) || (description?.let { writeVerbRegex.containsMatchIn(it) } == true)

/** Nightscout `settings.enable` plugin ids Discovery Modus recognizes, mapped to a short German
 * explanation for the Discovery-Sheet's "📦 Daten-Struktur & Info" section -- best-effort; an
 * unrecognized id still shows up (as "Plugin \"xyz\"") rather than being silently dropped. */
private val nightscoutPluginDescriptions = mapOf(
    "careportal" to "Ereignisse eintragen (Mahlzeiten, Insulin-Gaben, Notizen)",
    "openaps" to "OpenAPS-Regelkreis-Daten (Prognose, Empfehlungen)",
    "iob" to "Insulin an Bord (IOB)",
    "cob" to "Kohlenhydrate an Bord (COB)",
    "sage" to "Sensor-Alter (Sage)",
    "cage" to "Kanülen-Alter (Cage)",
    "iage" to "Infusionsset-Alter (Iage)",
    "bage" to "Batterie-Alter (Bage)",
    "basal" to "Basalraten-Profil",
    "ar2" to "AR2-Trend-Prognose",
    "boluscalc" to "Bolusrechner-Historie",
    "pump" to "Pumpenstatus",
    "loop" to "Loop-Regelkreis-Status",
    "food" to "Essens-/KH-Datenbank",
)
private val writeCapableNightscoutPlugins = setOf("careportal", "food")

/**
 * Orchestrates "Discovery Modus" (item 1+2 of the feature): introspects a data source's actual
 * schema (MCP `tools/list`/`resources/list`, or Nightscout's `/api/v1/status.json`), then --
 * best-effort, since it costs a real LLM call -- asks the currently active *fast* model
 * ([LlmPurpose.CHAT], e.g. DeepSeek v4 Flash / Gemini Flash) to turn that raw schema into a short
 * German summary, example chat questions, and key return values. [SettingsRepository] caches the
 * combined result per data-source id so re-opening the Discovery-Sheet later doesn't re-run
 * either step until the user explicitly taps "Erkunden" again.
 */
class DiscoveryService(
    private val mcpServerPool: McpServerPool,
    private val providerManager: LLMProviderManager,
    private val settingsRepository: SettingsRepository,
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** Full discovery + profiling + persistence for one MCP server -- the "Test-Button gedrückt"
     * entry point from item 1. The server must already be connected in [mcpServerPool] (a normal
     * "Verbindung testen"/save already ensures this before Discovery Modus is ever reachable). */
    suspend fun runMcpDiscovery(server: McpServerConfig, settings: AppSettings): Result<DiscoveryRecord> = runCatching {
        val schema = discoverMcpServer(server.id, server.displayName).getOrThrow()
        // Best-effort: [profile] already wraps its own body in `runCatching` and never throws, so
        // a failed/unreachable LLM just means no profile -- the raw discovered schema is still
        // saved and shown either way.
        val profile = profile(schema, settings).getOrNull()
        val record = DiscoveryRecord(schema = schema, profile = profile)
        settingsRepository.saveDiscoveryRecord(server.id, record)
        record
    }

    /** Same as [runMcpDiscovery], but for the direct Nightscout REST API (no MCP connection at
     * all -- see [com.example.diabai.network.nightscoutDirectServer]). */
    suspend fun runNightscoutDiscovery(config: McpServerConfig, settings: AppSettings): Result<DiscoveryRecord> = runCatching {
        val schema = discoverNightscoutDirect(config).getOrThrow()
        val profile = profile(schema, settings).getOrNull()
        val record = DiscoveryRecord(schema = schema, profile = profile)
        settingsRepository.saveDiscoveryRecord(config.id, record)
        record
    }

    private suspend fun discoverMcpServer(serverId: String, serverName: String): Result<DiscoveredSchema> = runCatching {
        val tools = mcpServerPool.listTools(serverId).getOrThrow()
        // resources/list is optional MCP -- listResources() already reduces "not supported" to a
        // quiet empty list (see McpConnection's doc comment), so no separate error handling here.
        val resources = mcpServerPool.listResources(serverId).getOrDefault(emptyList())
        DiscoveredSchema(
            serverId = serverId,
            serverName = serverName,
            discoveredAtMillis = System.currentTimeMillis(),
            tools = tools.map { it.toDiscoveredToolSchema() },
            resources = resources.map { r -> r.name?.let { "$it (${r.uri})" } ?: r.uri },
        )
    }

    private suspend fun discoverNightscoutDirect(config: McpServerConfig): Result<DiscoveredSchema> = runCatching {
        val status = fetchNightscoutStatus(config).getOrThrow()
        val capabilities = status.enabledPlugins.map { pluginId ->
            DiscoveredRestCapability(
                id = pluginId,
                description = nightscoutPluginDescriptions[pluginId.lowercase()] ?: "Plugin \"$pluginId\"",
                writeCapable = pluginId.lowercase() in writeCapableNightscoutPlugins,
            )
        }
        DiscoveredSchema(
            serverId = config.id,
            serverName = config.displayName,
            discoveredAtMillis = System.currentTimeMillis(),
            // The direct API exposes exactly one synthetic tool (see NightscoutDirectApi.kt) --
            // still listed here so the Discovery-Sheet's "🛠️ Verfügbare Werkzeuge" section isn't
            // empty for this data source either.
            tools = listOf(nightscoutDirectTool.toDiscoveredToolSchema()),
            restCapabilities = capabilities,
        )
    }

    /** Asks the active fast model to explain [schema] in plain German -- see
     * [buildProfilingPrompt]. Runs with no MCP tools attached at all (this is a pure text-
     * generation request over an already-fetched schema, not a new tool-calling turn), and
     * deliberately outside [com.example.diabai.domain.DiabetesAgent] (no confirmation gating, no
     * chat history, no privacy-shield masking needed -- nothing here is the user's own free-form
     * text or personal data, just a schema description). */
    private suspend fun profile(schema: DiscoveredSchema, settings: AppSettings): Result<DiscoveryProfile> = runCatching {
        val provider = providerManager.resolve(settings, LlmPurpose.CHAT)
        check(provider.isReady) { provider.notReadyReason }
        val conversation = provider.startConversation(
            "Du bist ein technischer API-Analyst für eine Diabetes-App. Antworte immer ausschließlich mit " +
                "validem JSON, ohne Markdown-Codeblock und ohne Text davor oder danach.",
            emptyList(),
        ) ?: error("Modell konnte keine Unterhaltung starten")
        try {
            val text = StringBuilder()
            conversation.sendUserMessage(buildProfilingPrompt(schema)).collect { event ->
                if (event is LlmStreamEvent.TextDelta) text.append(event.delta)
            }
            parseProfile(text.toString())
        } finally {
            conversation.close()
        }
    }

    private fun buildProfilingPrompt(schema: DiscoveredSchema): String = buildString {
        appendLine(
            "Analysiere folgende API/MCP-Werkzeuge für eine Diabetes-App. Antworte in sauberem JSON mit:\n" +
                "1. Einer 2-Satz-Zusammenfassung des Nutzens auf Deutsch.\n" +
                "2. Einer Liste der 3 bis 5 besten Beispielfragen, die der Nutzer dazu im Chat stellen kann.\n" +
                "3. Den wichtigsten Rückgabewerten (z. B. Blutzucker in mg/dL, Insulin, KH).",
        )
        appendLine()
        appendLine("Datenquelle: ${schema.serverName}")
        appendLine("Werkzeuge:")
        if (schema.tools.isEmpty()) {
            appendLine("  (keine)")
        } else {
            schema.tools.forEach { tool ->
                val paramsText = tool.parameters.joinToString(", ") { p ->
                    "${p.name}: ${p.type}" + if (p.required) " (erforderlich)" else ""
                }
                append("- ${tool.name}: ${tool.description?.takeIf { it.isNotBlank() } ?: "(keine Beschreibung)"}")
                if (paramsText.isNotBlank()) append(" [Parameter: $paramsText]")
                appendLine()
            }
        }
        if (schema.restCapabilities.isNotEmpty()) {
            appendLine("Erkannte Plugins/Fähigkeiten: " + schema.restCapabilities.joinToString(", ") { it.id })
        }
        appendLine()
        appendLine(
            "Antworte AUSSCHLIESSLICH mit einem JSON-Objekt (kein Markdown-Codeblock, kein Text davor " +
                "oder danach) in exakt diesem Format: {\"summary\": \"...\", \"exampleQuestions\": " +
                "[\"...\", \"...\", \"...\"], \"keyReturnValues\": [\"...\", \"...\"]}.",
        )
    }

    private fun parseProfile(rawAnswer: String): DiscoveryProfile {
        val jsonText = extractJsonObject(rawAnswer) ?: return DiscoveryProfile(generatedAtMillis = System.currentTimeMillis())
        val obj = runCatching { json.parseToJsonElement(jsonText) as JsonObject }.getOrNull()
            ?: return DiscoveryProfile(generatedAtMillis = System.currentTimeMillis())
        return DiscoveryProfile(
            summary = obj.stringFieldOrEmpty("summary"),
            exampleQuestions = obj.stringListField("exampleQuestions", "example_questions"),
            keyReturnValues = obj.stringListField("keyReturnValues", "key_return_values"),
            generatedAtMillis = System.currentTimeMillis(),
        )
    }

    /** The model doesn't always follow "JSON only" perfectly -- takes the first `{...}` block
     * found, same tolerant approach as `DiabetesDashboardManager.extractJsonObject`. */
    private fun extractJsonObject(text: String): String? {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start == -1 || end == -1 || end < start) return null
        return text.substring(start, end + 1)
    }

    private fun JsonObject.stringFieldOrEmpty(vararg names: String): String {
        for (name in names) {
            val key = keys.firstOrNull { it.equals(name, ignoreCase = true) } ?: continue
            this[key]?.jsonPrimitive?.contentOrNull?.let { return it }
        }
        return ""
    }

    private fun JsonObject.stringListField(vararg names: String): List<String> {
        for (name in names) {
            val key = keys.firstOrNull { it.equals(name, ignoreCase = true) } ?: continue
            val element = this[key] ?: continue
            return when (element) {
                is JsonArray -> element.mapNotNull { it.jsonPrimitive.contentOrNull }
                else -> element.jsonPrimitive.contentOrNull?.let { listOf(it) } ?: emptyList()
            }
        }
        return emptyList()
    }

    private fun McpTool.toDiscoveredToolSchema(): DiscoveredToolSchema = DiscoveredToolSchema(
        name = name,
        description = description,
        parameters = parseDiscoveredParameters(inputSchema),
        writeCapable = name != NIGHTSCOUT_DIRECT_TOOL_NAME && looksWriteCapable(name, description),
    )

    private fun parseDiscoveredParameters(schema: JsonElement?): List<DiscoveredParameter> {
        val obj = schema as? JsonObject ?: return emptyList()
        val properties = obj["properties"] as? JsonObject ?: return emptyList()
        val required = (obj["required"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }?.toSet().orEmpty()
        return properties.entries.map { (name, value) ->
            val propObj = value as? JsonObject
            val type = propObj?.get("type")?.jsonPrimitive?.contentOrNull ?: "unknown"
            val description = propObj?.get("description")?.jsonPrimitive?.contentOrNull
            DiscoveredParameter(name = name, type = type, description = description, required = name in required)
        }
    }
}
