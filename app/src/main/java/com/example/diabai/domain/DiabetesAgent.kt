package com.example.diabai.domain

import android.util.Log
import com.example.diabai.data.AppSettings
import com.example.diabai.data.LlmProviderType
import com.example.diabai.data.LlmRequestLogEntry
import com.example.diabai.data.McpServerConfig
import com.example.diabai.data.ONEPROVIDER_FREE_DAILY_REQUEST_LIMIT
import com.example.diabai.data.ONEPROVIDER_FREE_QUOTA_EXHAUSTED_MESSAGE
import com.example.diabai.data.SettingsRepository
import com.example.diabai.domain.llm.LLMProviderManager
import com.example.diabai.domain.llm.LlmConversation
import com.example.diabai.domain.llm.LlmPurpose
import com.example.diabai.domain.llm.LlmStreamEvent
import com.example.diabai.domain.llm.ModelCatalog
import com.example.diabai.domain.llm.LlmToolSpec
import com.example.diabai.domain.llm.RequestedToolCall
import com.example.diabai.domain.llm.ToolResultPayload
import com.example.diabai.network.HttpStatusException
import com.example.diabai.network.McpServerPool
import com.example.diabai.network.McpTool
import com.example.diabai.network.NIGHTSCOUT_DIRECT_SERVER_ID
import com.example.diabai.network.PooledTool
import com.example.diabai.network.allDataSourceServers
import com.example.diabai.network.fetchNightscoutDirectEntries
import com.example.diabai.network.longArgOrDefault
import com.example.diabai.network.nightscoutDirectServer
import com.example.diabai.network.nightscoutDirectTool
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** "adb logcat -s $LOG_TAG:W" surfaces a real LLM generation failure's full raw message (e.g. a
 * provider's own HTTP error body) immediately -- no need to reproduce it again through the UI just
 * to read a Snackbar that's truncated to a handful of words. */
private const val LOG_TAG = "GlucoSphere-LLM"

sealed interface AgentEvent {
    /** A chunk of the model's answer text, in generation order. */
    data class Answer(val delta: String) : AgentEvent

    /** The model wants to run [toolCall]; the UI must call [ConfirmationRequest.respond]. */
    data class ConfirmationRequired(val request: ConfirmationRequest) : AgentEvent

    /** "Interaktive Rückfrage" (item 4): [request] was ambiguous -- two or more enabled servers
     * of the very same [com.example.diabai.data.McpDataCategory] could equally answer it, and the
     * user didn't already pin one down via a chat server chip. [options] are those candidate
     * servers (never empty, never fewer than 2); the UI must call
     * [SourceChoiceRequest.respond] with the picked server's id, or null for "alle verwenden". */
    data class SourceChoiceRequired(val request: SourceChoiceRequest, val options: List<McpServerConfig>) : AgentEvent

    /** [serverName] is the already-resolved display name of whichever server owns this tool
     * (including the synthetic "Nightscout (Direkt-API)" server) -- resolved here in the agent
     * loop, where the [com.example.diabai.data.AppSettings.allDataSourceServers]/`serverId`
     * mapping is already in scope, rather than making the UI re-derive it from just a tool name. */
    data class ToolCallStarted(val toolCall: ToolCall, val serverName: String) : AgentEvent
    data class ToolCallDeclined(val toolCall: ToolCall) : AgentEvent
    data class ToolCallCompleted(val toolCall: ToolCall, val resultText: String) : AgentEvent
    data class Error(val message: String) : AgentEvent

    /** OpenRouter's "auto" routing resolved to a concrete underlying model this turn -- see
     * [com.example.diabai.domain.llm.LlmStreamEvent.ModelResolved]. Purely informational, shown
     * as a small "Auto-routed zu: {model}" note in chat. */
    data class ModelResolved(val modelId: String) : AgentEvent
    data object Complete : AgentEvent
}

/**
 * A pending "may I run this?" question raised mid-stream by [DiabetesAgent.ask]. The producing
 * coroutine suspends on [await] until the UI calls [respond] (e.g. from a Ja/Nein dialog).
 */
class ConfirmationRequest(val description: String) {
    private val deferred = CompletableDeferred<Boolean>()

    suspend fun await(): Boolean = deferred.await()

    fun respond(approved: Boolean) {
        deferred.complete(approved)
    }
}

/** A pending "which source did you mean?" question raised by [DiabetesAgent.askChat] before
 * [DiabetesAgent] resolveTools/tool-loop even starts (item 4's "Interaktive Rückfrage im Chat") --
 * same suspend-until-answered shape as [ConfirmationRequest], just with an N-way pick instead of
 * yes/no. `null` means "alle verwenden" (query every candidate rather than narrowing to one). */
class SourceChoiceRequest {
    private val deferred = CompletableDeferred<String?>()

    suspend fun await(): String? = deferred.await()

    fun respond(pickedServerId: String?) {
        deferred.complete(pickedServerId)
    }
}

/** LiteRT-LM's local Gemma build runs with a fixed ~4096-token context window -- once the
 * persistent chat conversation's accumulated history (system prompt + all prior turns) exceeds
 * that, generation crashes outright ("Input token ids are too long: N >= 4096") instead of
 * degrading gracefully. There's no API to trim older messages out of the native `Conversation`
 * object once they're in it, so the practical mitigation is capping how many turns are allowed
 * to accumulate before the conversation is transparently restarted -- 2 user turns is a
 * conservative stand-in for "max. 4 Dialognachrichten" (2x user + 2x assistant), since tool-result
 * messages exchanged in between add further, unpredictable bulk of their own. Cloud providers
 * replay their own history per REST call against a much larger context window, so this only
 * applies to the local provider. */
private const val MAX_LOCAL_CHAT_USER_TURNS = 2

/** Keeps the local model's system prompt (persona + full tool-schema list) well under its
 * context budget -- an MCP server can expose many tools, and each one's JSON-Schema costs real
 * tokens before the user has even said anything. Cloud providers get the full tool set. */
private const val MAX_LOCAL_TOOLS = 5

/** Above this length, a tool result fed back to the *local* model gets compressed by
 * [DiabetesAgent.pruneToolResultForLocal] instead of passed through verbatim -- see there. */
private const val MAX_LOCAL_TOOL_RESULT_CHARS = 2000

/** Below this raw-result length, [DiabetesAgent.compressHistoricalCgmResult] doesn't even attempt
 * to parse -- a same-day fetch is already small, so there's nothing worth the parsing effort or
 * risk for Kontext-Komprimierung (Turbo-Übersicht item 3). */
private const val HISTORY_COMPRESSION_MIN_CHARS = 4000

/** [DiabetesAgent.compressHistoricalCgmResult] only ever compresses once at least this many
 * confidently-parsed per-reading entries were found -- comfortably above a single day's ~288
 * five-minute Nightscout readings, so this never engages for a same-day query even if verbosely
 * formatted. */
private const val MIN_ENTRIES_FOR_HISTORY_COMPRESSION = 60

/** Shared by [DiabetesAgent.compressHistoricalCgmResult] -- `ignoreUnknownKeys` so an unrelated
 * extra field elsewhere in a real MCP server's response shape never fails the parse outright. */
private val historyCompressionJson = Json { ignoreUnknownKeys = true }

/** Matches a chat message that's plausibly asking for a real measurement -- the trigger for the
 * client-side no-data pre-check in [DiabetesAgent.askChat] (Halluzinations-Schutz item 2). Kept
 * deliberately narrow/keyword-based rather than trying to be exhaustive: a false negative here
 * just means the question goes to the LLM as normal (which itself now also refuses to invent a
 * value per the system prompt's "STRIKTE REGELN" block), while a false positive would wrongly
 * block a question that didn't actually need a data source at all -- so under-matching is the
 * safer failure mode. */
private val measurementKeywordRegex = Regex(
    """(?i)(letzte[nr]?\s+messung|letzte[nr]?\s+wert|aktuelle[nr]?\s+wert|aktuelle[nr]?\s+blutzucker|""" +
        """mein\s+gewicht|meinen?\s+blutzucker|mein\s+bz\b|\btir\b|time.?in.?range|zeit\s+im\s+zielbereich|""" +
        """wie\s+viel\s+insulin|insulin\s+an\s+bord|\biob\b)""",
)

/** Exact wording from the "Client-Side Pre-Check" requirement -- shown instantly (no LLM call at
 * all) when [measurementKeywordRegex] matches but no data source is actually reachable. */
private const val NO_DATA_SOURCE_FALLBACK_MESSAGE =
    "Um deine Werte abzurufen, muss eine Datenquelle (z. B. Nightscout oder Glooko) aktiviert sein. " +
        "Möchtest du das in den Einstellungen tun oder mir einen Wert manuell im Chat nennen?"

/** Lizenzmodell (item 1): exact wording from the requirement, shown instantly (no LLM call at
 * all, same "client-side pre-check" shape as [NO_DATA_SOURCE_FALLBACK_MESSAGE]) once
 * [AppSettings.isDailyUsageLimitReached] is true -- only ever reachable for [LicenseTier.TEST]/
 * [LicenseTier.FREE], since [LicenseTier.USER]/[LicenseTier.DEVELOPER] have no daily cap at all. */
private const val DAILY_USAGE_LIMIT_REACHED_MESSAGE =
    "Tageslimit für Test/Free-Lizenz erreicht. Upgrade auf User-/Entwickler-Lizenz erforderlich."

/** A model writing its tool call out as literal text/XML instead of using the provider's native
 * function-calling API ("Text-Cosplay") -- these never actually run (no [RequestedToolCall] is
 * ever produced for them), so a user seeing this raw tag in the chat bubble would be misleading
 * ("did that run or not?"). Matched by [DiabetesAgent.detectToolCosplay]. Deliberately matches
 * only a small, specific set of tag names (not a bare `<`) -- markdown/HTML-ish text containing
 * an unrelated `<` (e.g. "Werte < 180 mg/dL") must never be flagged. */
private val toolCosplayOpenTagRegex = Regex(
    """<\s*(tool|tool_call|tool_use|function_call|invoke|use_tool)\b[^>]*>""",
    RegexOption.IGNORE_CASE,
)

/**
 * Ties whichever [com.example.diabai.domain.llm.LLMProvider] is currently active (resolved per
 * request via [LLMProviderManager] from the user's saved settings -- local Gemma via LiteRT-LM,
 * or a Gemini/Claude/OpenAI cloud API) to [McpServerPool] (which actually executes MCP tool
 * calls against up to five configured servers): take a free-form user request for any time
 * range (e.g. "Wie lief mein Sport gestern?", "Analysiere die letzten 14 Tage"), let the model
 * call MCP tools as needed -- gated behind an explicit user confirmation per call -- feed each
 * tool result back in, and produce the final answer/report. Fully provider-agnostic: swapping
 * the active provider in settings changes nothing about this loop.
 *
 * Two entry points, deliberately different lifecycles:
 *  - [ask] starts a brand-new, throwaway conversation every call and discards it afterwards --
 *    correct for one-off background analyses (Übersicht tab fetches, [generateReport]) that must
 *    never see, or leak into, chat history.
 *  - [askChat] reuses one conversation across the whole chat session (see [chatConversation]) so
 *    the model actually remembers earlier turns -- what interactive chat needs, and what [ask]
 *    deliberately does *not* provide.
 */
class DiabetesAgent(
    private val providerManager: LLMProviderManager,
    private val mcpServerPool: McpServerPool,
    private val settingsRepository: SettingsRepository,
    private val maxToolRounds: Int = 4,
) {
    /**
     * Streams every step (answer text, tool calls, errors) as it happens. When
     * [requireConfirmation] is true (the default -- this is the interactive chat path), a
     * [AgentEvent.ConfirmationRequired] is emitted and awaited before each tool call actually
     * runs -- *unless* the tool's server has [com.example.diabai.data.McpServerConfig.autoApprove]
     * set, in which case that one call skips confirmation regardless of [requireConfirmation].
     * [includeMcpTools] is the per-message "MCP-Server einbeziehen" chat toggle -- when false,
     * this turn is answered as pure chat even if servers are configured/connected.
     * [selectedServerIds] is the chat tab's per-server filter (null = every connected server);
     * ignored when [includeMcpTools] is false.
     *
     * "Pure Chat Mode": if no MCP server is connected, tool listing is skipped entirely rather
     * than attempted-and-failed -- an unconfigured data source is a normal, expected state
     * (e.g. a fresh install), not an error to surface to the user.
     *
     * Starts and closes its own conversation every call -- see the class doc for why that's
     * intentional here and wrong for [askChat].
     */
    fun ask(
        request: String,
        requireConfirmation: Boolean = true,
        includeMcpTools: Boolean = true,
        selectedServerIds: Set<String>? = null,
        /** [LlmPurpose.ANALYSIS] by default -- correct for every real caller (Übersicht-tab
         * fetches, [generateReport]), which all run unattended and benefit more from a stronger
         * model than a faster one. */
        purpose: LlmPurpose = LlmPurpose.ANALYSIS,
        /** Turbo-Übersicht item 2's strict tool lock: when non-null, only tools whose inferred
         * [CapabilityTag]s (see [inferCapabilityTags]) intersect this set are ever offered to the
         * model this call, regardless of [selectedServerIds] -- e.g. [DASHBOARD_ALLOWED_TAGS] for
         * the Übersicht tab's Stage-2 metrics fetch, so an activity/body-metrics tool is never
         * even listed for that call. Null (the default) means no tag-based restriction at all. */
        allowedTags: Set<CapabilityTag>? = null,
    ): Flow<AgentEvent> = flow {
        val settings = settingsRepository.settings.first()

        quotaBlockMessage(settings)?.let { message ->
            emit(AgentEvent.Error(message))
            emit(AgentEvent.Complete)
            return@flow
        }

        val provider = providerManager.resolve(settings, purpose)

        if (!provider.isReady) {
            emit(AgentEvent.Error(provider.notReadyReason))
            emit(AgentEvent.Complete)
            return@flow
        }

        if (settings.llmProviderType == LlmProviderType.ONEPROVIDER_FREE && !settings.isOneProviderOwnKey) {
            settingsRepository.recordOneProviderFreeRequest()
        }

        val resolved = resolveTools(settings, includeMcpTools, selectedServerIds, request, allowedTags = allowedTags)
        val personaPrompt = personaPromptFor(settings) + buildToolRegistryPrompt(resolved.pooledTools, settings.allDataSourceServers)
        val conversation = provider.startConversation(personaPrompt, resolved.llmTools)
        if (conversation == null) {
            emit(AgentEvent.Error("Modell konnte keine Unterhaltung starten"))
            emit(AgentEvent.Complete)
            return@flow
        }

        val sanitizedRequest = prepareOutgoingRequest(request, settings)
        try {
            runToolLoop(conversation, NextInput.UserText(sanitizedRequest), requireConfirmation, resolved.toolServerByName, settings, purpose)
        } finally {
            conversation.close()
        }
    }

    /** The chat tab's persistent conversation -- created lazily on first use, reused for every
     * subsequent [askChat] call until [resetChatSession] is called (new chat, a different loaded
     * session, or a cancelled turn -- see there). [chatConversationKey] captures everything that
     * would make an existing conversation unsafe/wrong to keep using (a different provider, a
     * changed tool set -- including one that changed because [resolveTools]' contextual
     * server-selection picked a different server for a new topic): if it no longer matches, the
     * stale conversation is closed and replaced rather than reused, at the cost of losing history
     * for that one transition. [chatLocalUserTurns] separately forces this same reset once the
     * local model's context budget is at risk -- see [MAX_LOCAL_CHAT_USER_TURNS]. */
    private var chatConversation: LlmConversation? = null
    private var chatConversationKey: String? = null
    private var chatLocalUserTurns = 0

    /** Same contract as [ask], but keeps the conversation alive across calls instead of starting
     * fresh each time -- this is what actually gives chat its memory of earlier turns. */
    fun askChat(
        request: String,
        requireConfirmation: Boolean = true,
        includeMcpTools: Boolean = true,
        selectedServerIds: Set<String>? = null,
        /** "Dynamische Tool-Registrierung": tool names the user explicitly switched off via a
         * per-tool pill-chip in the chat input (see [ChatSection]'s discovered-tool chip row,
         * fed from Discovery Modus -- [com.example.diabai.domain.discovery.DiscoveryService]).
         * Empty by default (no behavior change): this is an opt-out filter layered on top of
         * [selectedServerIds]'s server-level selection, not a replacement for it -- a tool stays
         * available whenever its server is selected UNLESS its own name is in this set. */
        excludedToolNames: Set<String> = emptySet(),
        /** Tag-basiertes Capability-Routing (item 1): the capability chip(s) the user has active
         * in the chat input (see [CHAT_CAPABILITY_TAG_GROUPS]/[ChatSection]) -- null (the default,
         * no chip explicitly restricting) means every tag is allowed, same as before this feature
         * existed. Layered on top of [selectedServerIds]/[excludedToolNames] exactly like
         * [ask]'s own `allowedTags`. */
        allowedTags: Set<CapabilityTag>? = null,
    ): Flow<AgentEvent> = flow {
        val settings = settingsRepository.settings.first()

        // Client-Side Pre-Check (Halluzinations-Schutz item 2): a measurement question the app
        // already knows can't be answered -- literally zero active data sources right now, MCP
        // or direct API -- is answered locally, deterministically, and for free. This never
        // touches the LLM at all: no tokens spent, no latency, and no chance of the model
        // guessing/hallucinating a value it was never going to have access to anyway.
        if (measurementKeywordRegex.containsMatchIn(request) && !hasAnyActiveDataSource(settings)) {
            emit(AgentEvent.Answer(NO_DATA_SOURCE_FALLBACK_MESSAGE))
            emit(AgentEvent.Complete)
            return@flow
        }

        quotaBlockMessage(settings)?.let { message ->
            emit(AgentEvent.Error(message))
            emit(AgentEvent.Complete)
            return@flow
        }

        val provider = providerManager.resolve(settings, LlmPurpose.CHAT)

        if (!provider.isReady) {
            emit(AgentEvent.Error(provider.notReadyReason))
            emit(AgentEvent.Complete)
            return@flow
        }

        if (settings.llmProviderType == LlmProviderType.ONEPROVIDER_FREE && !settings.isOneProviderOwnKey) {
            settingsRepository.recordOneProviderFreeRequest()
        }

        val isLocal = settings.llmProviderType == LlmProviderType.LOCAL

        // Item 4's "Interaktive Rückfrage": ask instead of silently querying every ambiguous
        // source (or silently guessing one) whenever 2+ same-category servers could equally
        // answer this and the user hasn't already pinned one down via a chat server chip.
        var effectiveSelectedServerIds = selectedServerIds
        if (includeMcpTools) {
            val enabledServers = settings.allDataSourceServers.filter { it.enabled }
            val ambiguousGroup = detectSourceAmbiguity(enabledServers, selectedServerIds, request)
            if (ambiguousGroup != null) {
                val choiceRequest = SourceChoiceRequest()
                emit(AgentEvent.SourceChoiceRequired(choiceRequest, ambiguousGroup))
                val pickedServerId = choiceRequest.await()
                effectiveSelectedServerIds = pickedServerId?.let { setOf(it) } ?: ambiguousGroup.map { it.id }.toSet()
            }
        }

        val resolved = resolveTools(
            settings, includeMcpTools, effectiveSelectedServerIds, request,
            applyContextualSelection = true, excludedToolNames = excludedToolNames, allowedTags = allowedTags,
        )
        val personaPrompt = personaPromptFor(settings) + buildToolRegistryPrompt(resolved.pooledTools, settings.allDataSourceServers)
        val key = "${settings.llmProviderType}|$includeMcpTools|${selectedServerIds?.sorted()}|${resolved.llmTools.map { it.name }.sorted()}"

        // Force a fresh conversation once the local model's context budget is at risk, exactly
        // as if the tool set/key had changed below -- see MAX_LOCAL_CHAT_USER_TURNS.
        if (isLocal && chatLocalUserTurns >= MAX_LOCAL_CHAT_USER_TURNS) {
            chatConversation?.close()
            chatConversation = null
            chatConversationKey = null
        }

        if (chatConversation == null || chatConversationKey != key) {
            chatConversation?.close()
            chatConversation = provider.startConversation(personaPrompt, resolved.llmTools)
            chatConversationKey = key
            chatLocalUserTurns = 0
        }
        val conversation = chatConversation
        if (conversation == null) {
            emit(AgentEvent.Error("Modell konnte keine Unterhaltung starten"))
            emit(AgentEvent.Complete)
            return@flow
        }

        if (isLocal) chatLocalUserTurns++

        val sanitizedRequest = prepareOutgoingRequest(request, settings)
        // No close() here, deliberately -- stays open for the next askChat() call.
        runToolLoop(conversation, NextInput.UserText(sanitizedRequest), requireConfirmation, resolved.toolServerByName, settings, LlmPurpose.CHAT)
    }

    /** Ends the current chat conversation (if any) so the next [askChat] call starts fresh --
     * call this when the user starts a new chat, switches to a different saved session, or
     * cancels an in-flight turn (a cancelled turn can leave the conversation mid-exchange, e.g.
     * a user message with no assistant reply yet appended -- some providers, Claude notably,
     * reject the next call outright if roles don't strictly alternate, so resetting is the safe
     * default rather than risking a broken conversation that then errors on every later message). */
    fun resetChatSession() {
        chatConversation?.close()
        chatConversation = null
        chatConversationKey = null
        chatLocalUserTurns = 0
    }

    /** Cloud providers get the name-anonymized prompt (see [PrivacyShield]); only the local,
     * on-device model -- which never sends anything off the phone -- gets the real name for a
     * proper personal address. */
    private fun personaPromptFor(settings: AppSettings): String {
        val basePrompt = if (settings.llmProviderType == LlmProviderType.LOCAL) {
            settings.personalizedSystemPrompt
        } else {
            settings.anonymizedSystemPrompt
        }
        // The model otherwise has no way to know "today" -- neither for interpreting relative
        // phrasing in chat ("wie war mein Zucker heute?") nor for filling in date/time tool
        // parameters correctly, so every conversation gets the device's actual current
        // date/time appended, not just the persona/behavior prompt.
        return basePrompt + "\n\n" + currentDateContext()
    }

    /** Prepares [request] for actually being sent to the model: strips the user's own name/
     * email/dates/phone/address for a cloud provider (see [PrivacyShield] -- a no-op for the
     * local model, which never leaves the device), then prepends a fresh `[SYSTEM STATE]` line
     * (Dynamische Zustands-Injektion, item 2) naming which data sources are actually active
     * right now, the glucose unit, and the current local time/zone -- recomputed on EVERY call,
     * not just once when a conversation starts: [personaPromptFor]'s date/persona context is set
     * once per [chatConversation] and can go stale over a long-running chat, while this is fresh
     * on every single outgoing message regardless of how long the conversation has been open. */
    private fun prepareOutgoingRequest(request: String, settings: AppSettings): String {
        val sanitized = if (settings.llmProviderType == LlmProviderType.LOCAL) {
            request
        } else {
            PrivacyShield.sanitize(request, settings.userName)
        }
        return systemStateBlock(settings) + "\n\n" + sanitized
    }

    /** Every data source that's both enabled in settings AND actually reachable right now -- an
     * MCP server the pool's live handshake succeeded for ([McpServerPool.connectedServerIds]),
     * or the direct Nightscout REST API whenever it's configured+enabled (it has no persistent
     * "connected" state to check, being a plain per-call REST client rather than a live session).
     * Feeds both [systemStateBlock] (item 2's "Aktive Quellen") and the client-side no-data
     * pre-check in [askChat] -- a server merely being *saved* in settings but unreachable this
     * session must not count as "active" for either purpose. */
    private fun activeDataSourceNames(settings: AppSettings): List<String> {
        val connectedMcpIds = mcpServerPool.connectedServerIds.value
        return settings.allDataSourceServers
            .filter { it.enabled && (it.id == NIGHTSCOUT_DIRECT_SERVER_ID || it.id in connectedMcpIds) }
            .map { it.displayName }
    }

    private fun hasAnyActiveDataSource(settings: AppSettings): Boolean = activeDataSourceNames(settings).isNotEmpty()

    /** Client-side pre-checks for [ask]/[askChat], same "no LLM call at all" shape as
     * [hasAnyActiveDataSource]'s own gate -- checked BEFORE `providerManager.resolve` even runs,
     * so a blocked request never reaches the network at all (no wasted latency, and for the
     * OneProvider-Freikontingent case, no risk of a request slipping through and silently NOT
     * being counted against the local cap). Null means "not blocked, proceed normally". */
    private fun quotaBlockMessage(settings: AppSettings): String? {
        if (settings.isDailyUsageLimitReached) return DAILY_USAGE_LIMIT_REACHED_MESSAGE
        // An own OneProvider key (settings.isOneProviderOwnKey) is unlimited, exactly like every
        // other cloud provider's own key -- only the app-embedded free-tier key is capped.
        if (settings.llmProviderType == LlmProviderType.ONEPROVIDER_FREE && !settings.isOneProviderOwnKey &&
            settings.oneProviderFreeRequestsToday >= ONEPROVIDER_FREE_DAILY_REQUEST_LIMIT
        ) {
            return ONEPROVIDER_FREE_QUOTA_EXHAUSTED_MESSAGE
        }
        return null
    }

    /** The `[SYSTEM STATE]` line prepended to every outgoing message (see
     * [prepareOutgoingRequest]) -- tells the model exactly what it can and can't actually reach
     * right now, instead of leaving it to infer that from the tool list alone or (worse) assume
     * from training data / prior turns. Glucose unit is currently always "mg/dL" -- this app has
     * no per-user unit *setting* yet (see [AppSettings]), just the one fixed display unit already
     * used everywhere else in the UI (Übersicht metrics, dashboard cache, ...). */
    private fun systemStateBlock(settings: AppSettings): String {
        val activeNames = activeDataSourceNames(settings)
        val sourcesText = if (activeNames.isEmpty()) "KEINE" else activeNames.joinToString(", ")
        val now = ZonedDateTime.now()
        val timeText = now.format(DateTimeFormatter.ofPattern("HH:mm 'Uhr', dd.MM.yyyy", Locale.GERMAN))
        return "[SYSTEM STATE] Aktive Quellen: $sourcesText. Maßeinheit: mg/dL. Lokale Uhrzeit: $timeText (Zeitzone ${now.zone})."
    }

    /**
     * [applyContextualSelection] (only set from [askChat]) layers two heuristics on top of the
     * caller's own [selectedServerIds] filter, both from the "Kontextbezogene MCP-Server-Auswahl"
     * requirement:
     *  - If the user hasn't manually restricted servers, [inferQueryCategory] narrows to
     *    whichever category the request text is actually about (e.g. a sport question no longer
     *    also lists Nightscout/Glooko tools).
     *  - If the request implies a multi-day range (> 5 days), [restrictToOnePerCategory] caps
     *    the server set to at most one server per category regardless of manual selection, so a
     *    long-range question never doubles up on two servers covering the same data.
     *  - If the request asks for the value *right now* ([isCurrentValueQuery]),
     *    [preferNightscoutForCurrentValue] narrows a multi-source glucose selection down to
     *    Nightscout alone, since it reports the freshest sensor reading of any source here.
     * Left off for [ask]/[generateReport] (background/report generation, including every
     * Übersicht-tab fetch) -- those already pass their own precise, already-tuned server
     * selection and shouldn't have it second-guessed by a keyword heuristic over their internal
     * instruction text.
     */
    /** The narrowing chain shared by [resolveTools] and [detectSourceAmbiguity] -- pulled out so
     * both apply the exact same heuristics in the exact same order and can never silently drift
     * apart (e.g. the ambiguity check "resolving" something [resolveTools] would have narrowed
     * differently). See [resolveTools]'s own doc comment for what each step does. */
    private fun computeEffectiveServerIds(
        enabledServers: List<McpServerConfig>,
        selectedServerIds: Set<String>?,
        request: String,
        applyContextualSelection: Boolean,
    ): Set<String> {
        val explicitSelection = selectedServerIds != null
        var effectiveIds = selectedServerIds ?: enabledServers.map { it.id }.toSet()

        if (applyContextualSelection) {
            if (!explicitSelection) {
                inferQueryCategory(request)?.let { category ->
                    val matching = enabledServers.filter { it.category == category && it.id in effectiveIds }
                    if (matching.isNotEmpty()) effectiveIds = matching.map { it.id }.toSet()
                }
            }
            if ((inferSpanDays(request) ?: 0) > 5) {
                effectiveIds = restrictToOnePerCategory(enabledServers, effectiveIds)
            }
            if (isCurrentValueQuery(request)) {
                effectiveIds = preferNightscoutForCurrentValue(enabledServers, effectiveIds)
            }
        }
        return effectiveIds
    }

    /** Item 4's "Interaktive Rückfrage": true ambiguity is when the user gave no explicit
     * selection (no chat server chip active) AND, after every heuristic in
     * [computeEffectiveServerIds] has already run, 2+ enabled servers of the very same
     * [com.example.diabai.data.McpDataCategory] are still both in scope -- e.g. glucose data
     * sitting in both Nightscout and Glooko with nothing (chip, contextual category match, >5-day
     * throttling, "aktueller Wert" Nightscout-preference) strong enough to pick just one. Returns
     * null when there's nothing to ask about, including whenever [selectedServerIds] already
     * pins the answer down explicitly. */
    private fun detectSourceAmbiguity(
        enabledServers: List<McpServerConfig>, selectedServerIds: Set<String>?, request: String,
    ): List<McpServerConfig>? {
        if (selectedServerIds != null) return null
        val effectiveIds = computeEffectiveServerIds(enabledServers, selectedServerIds, request, applyContextualSelection = true)
        val candidates = enabledServers.filter { it.id in effectiveIds }
        return candidates.groupBy { it.category }.values.firstOrNull { it.size > 1 }
    }

    private suspend fun resolveTools(
        settings: AppSettings,
        includeMcpTools: Boolean,
        selectedServerIds: Set<String>?,
        request: String,
        applyContextualSelection: Boolean = false,
        /** See [askChat]'s doc comment -- ignored by [ask]/[generateReport] (background/report
         * generation callers don't have a chat-side tool-chip row to source this from). */
        excludedToolNames: Set<String> = emptySet(),
        /** See [ask]'s doc comment -- applied last, after server selection/contextual narrowing/
         * [excludedToolNames], and operates on individual tools (via [inferCapabilityTags]) rather
         * than whole servers, which is exactly what lets one chat capability chip span several
         * different brands/servers at once (Tag-basiertes Capability-Routing, item 1). */
        allowedTags: Set<CapabilityTag>? = null,
    ): ResolvedTools {
        // A direct Nightscout REST API (no MCP server at all) counts as "there's something to
        // query" on its own -- previously this gate only ever looked at the MCP pool, so a user
        // with *only* the direct API configured (no MCP server) silently got zero tools ever.
        val nightscoutDirect = settings.nightscoutDirectServer()
        if (!includeMcpTools || (!mcpServerPool.hasAnyConnection && nightscoutDirect == null)) {
            return ResolvedTools(emptyMap(), emptyList(), emptyList())
        }

        val enabledServers = settings.allDataSourceServers.filter { it.enabled }
        val effectiveIds = computeEffectiveServerIds(enabledServers, selectedServerIds, request, applyContextualSelection)

        val mcpPooledTools = mcpServerPool.listAllTools(settings.mcpServers.filter { it.enabled })
            .filter { it.serverId in effectiveIds }
        val directPooledTool = nightscoutDirect
            ?.takeIf { it.enabled && it.id in effectiveIds }
            ?.let { PooledTool(NIGHTSCOUT_DIRECT_SERVER_ID, it.displayName, nightscoutDirectTool) }
        val excludedFilteredTools = (mcpPooledTools + listOfNotNull(directPooledTool))
            .filter { it.tool.name !in excludedToolNames }

        // Tag-basiertes Capability-Routing (item 1) / Turbo-Übersicht's strict tool lock (item 2):
        // a tool survives here only if at least one of its inferred capability tags is in
        // [allowedTags] -- see [inferCapabilityTags]. Operates per-tool, not per-server, so a
        // single chat capability chip (or the dashboard's fixed [DASHBOARD_ALLOWED_TAGS]) can span
        // every brand/server that happens to expose a matching tool.
        val categoryByServerId = settings.allDataSourceServers.associate { it.id to it.category }
        val pooledTools = if (allowedTags == null) {
            excludedFilteredTools
        } else {
            excludedFilteredTools.filter { pooled ->
                inferCapabilityTags(pooled.tool.name, pooled.tool.description, categoryByServerId[pooled.serverId])
                    .any { it in allowedTags }
            }
        }

        // Cap to the essential core tools for the local model -- see MAX_LOCAL_TOOLS.
        val relevantPooled = if (settings.llmProviderType == LlmProviderType.LOCAL && pooledTools.size > MAX_LOCAL_TOOLS) {
            pooledTools.take(MAX_LOCAL_TOOLS)
        } else {
            pooledTools
        }

        // A tool name is only ambiguous if two connected servers happen to expose the same
        // name; last one registered wins the routing, which is an acceptable edge case here.
        val toolServerByName = relevantPooled.associate { it.tool.name to it.serverId }
        val llmTools = relevantPooled.map { it.tool.toLlmToolSpec() }
        return ResolvedTools(toolServerByName, llmTools, relevantPooled)
    }

    private data class ResolvedTools(
        val toolServerByName: Map<String, String>,
        val llmTools: List<LlmToolSpec>,
        val pooledTools: List<PooledTool>,
    )

    /**
     * A short, curated "which tool for what" cheat-sheet appended to the system prompt (on top
     * of the tool schemas already passed through the provider's own function-calling API) -- the
     * MCP tools' own descriptions can be terse or inconsistent between servers, so this gives the
     * model an explicit, purpose-labelled index to reason from *before* it even considers calling
     * anything, rather than relying purely on schema matching. Empty string (nothing appended)
     * when no tools are available this turn.
     */
    private fun buildToolRegistryPrompt(pooledTools: List<PooledTool>, servers: List<McpServerConfig>): String {
        if (pooledTools.isEmpty()) return ""
        val categoryByServerId = servers.associate { it.id to it.category.shortLabel }
        val lines = pooledTools.joinToString("\n") { pooled ->
            val category = categoryByServerId[pooled.serverId] ?: "?"
            val description = pooled.tool.description?.takeIf { it.isNotBlank() }?.let { ": $it" } ?: ""
            "- ${pooled.serverName} ($category) -> `${pooled.tool.name}`$description"
        }
        return "\n\n# VERFÜGBARE MCP-TOOLS\n$lines\n\nWähle vor jedem Tool-Aufruf gezielt genau das " +
            "Tool aus dieser Liste, dessen Zweck am besten zur aktuellen Frage passt -- rate nicht, " +
            "wenn keine Beschreibung eindeutig passt, sondern frage stattdessen kurz nach."
    }

    /** One requested tool call plus everything [runToolLoop] already knows about it once
     * ([toolServerByName]/[AppSettings.allDataSourceServers] are resolved -- kept together so a
     * parallel batch can be resolved once, confirmed once, and dispatched together. */
    private data class ResolvedCall(
        val id: String,
        val toolCall: ToolCall,
        val serverId: String?,
        val serverName: String,
        val autoApprove: Boolean,
    )

    private fun resolveCall(requested: RequestedToolCall, toolServerByName: Map<String, String>, settings: AppSettings): ResolvedCall {
        val toolCall = ToolCall(requested.name, requested.arguments)
        val serverId = toolServerByName[toolCall.name]
        val server = serverId?.let { id -> settings.mcpServers.firstOrNull { it.id == id } }
        val autoApprove = server != null && (server.autoApprove || toolCall.name in server.autoApprovedTools)
        val serverName = serverId?.let { id -> settings.allDataSourceServers.firstOrNull { it.id == id }?.displayName } ?: "?"
        return ResolvedCall(requested.id, toolCall, serverId, serverName, autoApprove)
    }

    /** Actually runs one already-approved [ResolvedCall] against its MCP/Nightscout server --
     * pulled out of [runToolLoop] so a parallel batch can dispatch several of these concurrently
     * via `async` instead of one network round trip after another. */
    private suspend fun dispatchCall(resolved: ResolvedCall, settings: AppSettings): String {
        val toolCall = resolved.toolCall
        val serverId = resolved.serverId
        val resultText = when {
            serverId == null -> "error calling ${toolCall.name}: no connected server offers this tool"
            serverId == NIGHTSCOUT_DIRECT_SERVER_ID -> {
                val directConfig = settings.nightscoutDirectServer()
                if (directConfig == null) {
                    "error calling ${toolCall.name}: Nightscout-Direct-API nicht konfiguriert"
                } else {
                    val now = System.currentTimeMillis()
                    val from = toolCall.arguments.longArgOrDefault("fromEpochMillis", now - 24 * 60 * 60 * 1000L)
                    val to = toolCall.arguments.longArgOrDefault("toEpochMillis", now)
                    fetchNightscoutDirectEntries(directConfig, from, to).fold(
                        onSuccess = { it },
                        onFailure = { "error calling ${toolCall.name}: ${it.message}" },
                    )
                }
            }
            else -> mcpServerPool.callTool(serverId, toolCall.name, toolCall.arguments).fold(
                onSuccess = { result -> result.content.joinToString("\n") { it.text.orEmpty() } },
                onFailure = { "error calling ${toolCall.name}: ${it.message}" },
            )
        }
        // The local model runs with a small, fixed context window (see MAX_LOCAL_CHAT_USER_TURNS)
        // -- a raw multi-day CGM dump (e.g. 288 five-minute readings) can single-handedly blow
        // that budget, so it keeps its own existing (more aggressive) pruning unchanged. Cloud
        // providers instead get the new, more conservative Kontext-Komprimierung (item 3): a
        // multi-day result is compressed to daily aggregates ONLY when [compressHistoricalCgmResult]
        // is confident it correctly identified every reading -- see its own doc comment for why
        // this is safe where the earlier, reverted raw-text-regex approach wasn't. Falls back to
        // the untouched raw text whenever that confidence bar isn't met.
        return if (settings.llmProviderType == LlmProviderType.LOCAL) {
            pruneToolResultForLocal(resultText)
        } else if (resultText.length > HISTORY_COMPRESSION_MIN_CHARS) {
            compressHistoricalCgmResult(resultText)
        } else {
            resultText
        }
    }

    /** The actual multi-round tool-calling loop, shared by [ask] and [askChat] -- they differ
     * only in whether [conversation] is thrown away afterwards or kept alive. A single round can
     * request several tools at once (parallel tool calling); those are resolved/confirmed as one
     * batch and then dispatched concurrently via `async`/`awaitAll` -- see [dispatchCall] -- so
     * e.g. a Nightscout AND a Withings call in the same round run at the same time instead of one
     * network round trip after another. */
    private suspend fun FlowCollector<AgentEvent>.runToolLoop(
        conversation: LlmConversation,
        initialInput: NextInput,
        requireConfirmation: Boolean,
        toolServerByName: Map<String, String>,
        settings: AppSettings,
        /** Performance-Log: which [LlmPurpose] this exchange was resolved for -- see
         * [logRequestPerformance]. Always the same value [ask]/[askChat] already resolved their
         * [com.example.diabai.domain.llm.LLMProviderManager.resolve] call with. */
        purpose: LlmPurpose,
    ) {
        // Performance-Log (Provider/Modell/Token/Anfrage-Menge/Dauer je Anfrage): accumulated
        // across every round of this one exchange, written once via logRequestPerformance in the
        // `finally` below regardless of how the loop actually ends (normal completion, a caught
        // AgentGenerationError, or an uncaught CancellationException from the user tapping
        // "Abbrechen" -- `finally` still runs before a cancellation continues to propagate, so
        // even a cancelled request leaves a row showing how long it ran before that happened).
        val requestStartMillis = System.currentTimeMillis()
        var totalPromptTokens = 0L
        var totalCompletionTokens = 0L
        var toolCallCount = 0
        // Set only in the `catch` below -- null means this exchange completed without an
        // AgentGenerationError (a normal answer, or a soft AgentEvent.Error emitted earlier before
        // the tool loop even started, e.g. a quota block -- neither of those reaches this catch).
        var requestErrorMessage: String? = null
        // Bidirektionales Privacy-Shield: cloud providers echo the anonymized system prompt's
        // literal `[USER]` tag back in their own answers (see AppSettings.anonymizedSystemPrompt),
        // so every answer chunk on the way back OUT to the UI needs the reverse substitution --
        // see PrivacyShield.unmask. A no-op for the local model, which was never given a masked
        // prompt/name in the first place (personaPromptFor already branches on this).
        val isCloudProvider = settings.llmProviderType != LlmProviderType.LOCAL
        // Holds back a possibly-incomplete `[...]` tag split across two streamed deltas (e.g. one
        // chunk ending in "Hallo [US" and the next starting with "ER], wie...") until either its
        // closing `]` arrives or the round ends -- see unmaskStreamChunk. Reset per round: a
        // dangling '[' can never legitimately span a tool-call boundary.
        val unmaskTail = StringBuilder()
        // Same held-back-tail technique, for a possibly-incomplete `<tag...>` (Text-Cosplay
        // detection, see detectToolCosplay) -- operates on the ALREADY-unmasked text, i.e. this
        // is the second stage of a two-stage per-delta pipeline (unmask, then cosplay-filter).
        val cosplayTail = StringBuilder()

        try {
            var pendingInput = initialInput
            var round = 0

            while (true) {
                var sawError = false
                var requestedCalls: List<RequestedToolCall> = emptyList()
                var toolCosplayDetected = false
                unmaskTail.clear()
                cosplayTail.clear()

                logDebug(
                    settings,
                    "Runde $round (${settings.llmProviderType}, ${purpose.name}): " +
                        if (pendingInput is NextInput.ToolResults) "Tool-Ergebnisse gesendet" else "Anfrage gesendet",
                )
                val events = when (val input = pendingInput) {
                    is NextInput.UserText -> conversation.sendUserMessage(input.text)
                    is NextInput.ToolResults -> conversation.sendToolResults(input.results)
                }

                events
                    .catch { e ->
                        sawError = true
                        // OneProvider-Freikontingent (item 3): the local 3-req/day counter is the
                        // PRIMARY, always-reliable gate (checked before this call was ever made --
                        // see quotaBlockMessage) -- this is the secondary, best-effort case where
                        // the embedded key itself has run dry server-side (402/429) before the
                        // local counter caught up, surfaced with the same friendly wording instead
                        // of a raw HTTP error the user has no way to act on.
                        val message = if (settings.llmProviderType == LlmProviderType.ONEPROVIDER_FREE && !settings.isOneProviderOwnKey &&
                            e is HttpStatusException && e.statusCode in setOf(402, 429)
                        ) {
                            ONEPROVIDER_FREE_QUOTA_EXHAUSTED_MESSAGE
                        } else {
                            e.message ?: "Generation failed"
                        }
                        throw AgentGenerationError(message, e)
                    }
                    .collect { event ->
                        when (event) {
                            is LlmStreamEvent.TextDelta -> {
                                if (toolCosplayDetected) {
                                    // Already caught this round text-cosplaying a tool call --
                                    // suppress everything else it says for the rest of this
                                    // round (see detectToolCosplay's doc comment for why not
                                    // hunting for a matching closing tag instead); the corrective
                                    // system nudge below handles it once the round ends.
                                    return@collect
                                }
                                val unmasked = if (isCloudProvider) {
                                    unmaskStreamChunk(unmaskTail, event.delta, settings.userName)
                                } else {
                                    event.delta
                                }
                                val (safeText, cosplayFound) = detectToolCosplay(cosplayTail, unmasked)
                                if (cosplayFound) toolCosplayDetected = true
                                if (safeText.isNotEmpty()) emit(AgentEvent.Answer(safeText))
                            }
                            is LlmStreamEvent.ToolCallRequested -> {
                                requestedCalls = event.calls
                                logDebug(settings, "Tool-Aufruf angefordert: ${event.calls.joinToString(", ") { it.name }}")
                            }
                            is LlmStreamEvent.Usage -> {
                                settingsRepository.recordLlmUsage(event.promptTokens, event.completionTokens)
                                totalPromptTokens += event.promptTokens
                                totalCompletionTokens += event.completionTokens
                            }
                            is LlmStreamEvent.ModelResolved -> emit(AgentEvent.ModelResolved(event.modelId))
                        }
                    }

                // The stream ended (this round) with text still held back in one or both stages
                // -- either it wasn't actually a placeholder/cosplay tag at all (e.g. a markdown
                // link fragment, or "Werte < 180 mg/dL"), or the provider truncated mid-tag;
                // either way there's no more input coming for this round, so flush it now rather
                // than silently dropping it. Skipped entirely once cosplay was already detected:
                // nothing further from this round is meant to reach the UI.
                if (!toolCosplayDetected) {
                    var trailing = ""
                    if (unmaskTail.isNotEmpty()) {
                        trailing = PrivacyShield.unmask(unmaskTail.toString(), settings.userName)
                        unmaskTail.clear()
                    }
                    val (safeTrailing, cosplayFoundAtEnd) = detectToolCosplay(cosplayTail, trailing)
                    if (cosplayFoundAtEnd) toolCosplayDetected = true
                    if (safeTrailing.isNotEmpty()) emit(AgentEvent.Answer(safeTrailing))
                    if (!toolCosplayDetected && cosplayTail.isNotEmpty()) {
                        emit(AgentEvent.Answer(cosplayTail.toString()))
                        cosplayTail.clear()
                    }
                }

                if (sawError) {
                    emit(AgentEvent.Complete)
                    return
                }

                // Text-Cosplay-Schutz (Halluzinations-Schutz item 1): the model wrote its "tool
                // call" as plain text/XML instead of a real native function call, so no
                // RequestedToolCall was ever produced for it -- nothing to actually run. Nudge it
                // back onto the native API with a corrective system message and retry, exactly
                // like the existing "declined tool" retry below, instead of silently completing
                // with a response that quietly never did what it claimed to.
                if (toolCosplayDetected && requestedCalls.isEmpty()) {
                    if (round + 1 >= maxToolRounds) {
                        emit(AgentEvent.Error(
                            "Das Modell hat wiederholt versucht, Tools als Text statt über die " +
                                "native API aufzurufen, statt sie tatsächlich auszuführen.",
                        ))
                        emit(AgentEvent.Complete)
                        return
                    }
                    pendingInput = NextInput.UserText(
                        "SYSTEM-HINWEIS: Deine letzte Antwort enthielt einen Tool-Aufruf als Text " +
                            "oder XML-Tag statt eines echten Function-Calls über die native Tool-API. " +
                            "Das wurde NICHT ausgeführt. Rufe das gewünschte Tool jetzt ausschließlich " +
                            "über die bereitgestellte native Function-Calling-Schnittstelle auf -- " +
                            "gib niemals Tool-Aufrufe als sichtbaren Text oder XML-Tags aus.",
                    )
                    round++
                    continue
                }

                if (requestedCalls.isEmpty() || round >= maxToolRounds) {
                    emit(AgentEvent.Complete)
                    return
                }

                val resolvedCalls = requestedCalls.map { resolveCall(it, toolServerByName, settings) }

                if (requireConfirmation && resolvedCalls.any { !it.autoApprove }) {
                    val description = if (resolvedCalls.size == 1) {
                        describeToolCall(resolvedCalls[0].toolCall)
                    } else {
                        "Sollen folgende Tools ausgeführt werden?\n" +
                            resolvedCalls.joinToString("\n") { "- ${it.toolCall.name} (${it.serverName})" }
                    }
                    val confirmation = ConfirmationRequest(description)
                    emit(AgentEvent.ConfirmationRequired(confirmation))
                    if (!confirmation.await()) {
                        resolvedCalls.forEach { emit(AgentEvent.ToolCallDeclined(it.toolCall)) }
                        pendingInput = NextInput.UserText(
                            "The user declined to run tool(s): ${resolvedCalls.joinToString(", ") { it.toolCall.name }}. " +
                                "Continue without this data if possible, and mention that this information is missing.",
                        )
                        round++
                        continue
                    }
                }

                toolCallCount += resolvedCalls.size
                resolvedCalls.forEach { emit(AgentEvent.ToolCallStarted(it.toolCall, it.serverName)) }

                // The actual parallel dispatch (item 1): every call in this round's batch runs
                // concurrently instead of sequentially -- `dispatchCall` is a plain suspend
                // function with no Flow emission of its own, so it's safe to run N of them at
                // once inside `async` and only touch the (non-thread-safe) FlowCollector
                // afterwards, back on this coroutine.
                val effectiveResults = coroutineScope {
                    resolvedCalls.map { resolved -> resolved to async { dispatchCall(resolved, settings) } }
                        .map { (resolved, deferred) -> resolved to deferred.await() }
                }

                val toolResultPayloads = effectiveResults.map { (resolved, effectiveResultText) ->
                    emit(AgentEvent.ToolCallCompleted(resolved.toolCall, effectiveResultText))
                    logDebug(settings, "Tool-Ergebnis ${resolved.toolCall.name}: ${effectiveResultText.length} Zeichen")
                    ToolResultPayload(resolved.id, resolved.toolCall.name, effectiveResultText)
                }

                pendingInput = NextInput.ToolResults(toolResultPayloads)
                round++
            }
        } catch (e: AgentGenerationError) {
            // Logged here (not at the inner throw site) because this is the first point with both
            // the FULL original message (e.g. a provider's raw HTTP error body -- see e.cause) and
            // the request context (provider/model) -- "adb logcat -s $LOG_TAG:W" now shows exactly
            // what failed instead of needing a UI round trip to read a truncated Snackbar.
            Log.w(LOG_TAG, "LLM-Generierung fehlgeschlagen (${settings.llmProviderType}, ${purpose.name}): ${e.message}", e.cause)
            requestErrorMessage = e.message
            logDebug(settings, "FEHLER (${settings.llmProviderType}): ${e.message}")
            // A native/JNI-level failure inside the local model (e.g. a memory allocation
            // failure building too large a KV-cache) surfaces here the same way any other
            // generation error does -- but for LOCAL specifically, the persistent chat
            // conversation is also force-reset (see resetChatSession), since re-using a
            // conversation object whose native state may be corrupted after such a failure would
            // otherwise just fail the exact same way on every subsequent message.
            val message = if (settings.llmProviderType == LlmProviderType.LOCAL) {
                resetChatSession()
                "Lokaler Speicherüberlauf -- Kontext zurückgesetzt. Bitte stelle deine Frage erneut, ggf. kürzer gefasst."
            } else {
                e.message ?: "Generation failed"
            }
            emit(AgentEvent.Error(message))
            emit(AgentEvent.Complete)
        } finally {
            logRequestPerformance(
                settings, purpose, totalPromptTokens, totalCompletionTokens, toolCallCount, requestStartMillis, requestErrorMessage,
            )
        }
    }

    /** "Debug-Log (Entwickler)" in Performance-Log -- a no-op unless [AppSettings.debugLoggingEnabled]
     * is on, so every call site above can log unconditionally instead of repeating that check. */
    private suspend fun logDebug(settings: AppSettings, message: String) {
        if (!settings.debugLoggingEnabled) return
        settingsRepository.appendDebugLog(message)
    }

    /** Writes one [com.example.diabai.data.LlmRequestLogEntry] for the exchange [runToolLoop] just
     * finished (Performance-Log) -- never for [LlmProviderType.LOCAL], matching
     * [com.example.diabai.data.SettingsRepository.recordLlmUsage]'s own local-model exclusion (no
     * per-request model id concept there, and it costs nothing/never leaves the device, so it
     * isn't part of the cloud-latency/cost picture this log exists to show). [model] is
     * recomputed via the exact same [ModelCatalog.resolve] call
     * [com.example.diabai.domain.llm.LLMProviderManager.resolve] already used to pick the
     * provider instance for this call, rather than threading a resolved-model-id value through
     * [LLMProvider] itself. */
    private suspend fun logRequestPerformance(
        settings: AppSettings,
        purpose: LlmPurpose,
        promptTokens: Long,
        completionTokens: Long,
        toolCallCount: Int,
        requestStartMillis: Long,
        errorMessage: String? = null,
    ) {
        if (settings.llmProviderType == LlmProviderType.LOCAL) return
        val model = when (settings.llmProviderType) {
            LlmProviderType.GEMINI -> ModelCatalog.resolve(LlmProviderType.GEMINI, settings.geminiModel, purpose)
            LlmProviderType.CLAUDE -> ModelCatalog.resolve(LlmProviderType.CLAUDE, settings.claudeModel, purpose)
            LlmProviderType.OPENAI -> ModelCatalog.resolve(LlmProviderType.OPENAI, settings.openAiModel, purpose)
            LlmProviderType.DEEPSEEK -> ModelCatalog.resolve(LlmProviderType.DEEPSEEK, settings.deepseekModel, purpose)
            LlmProviderType.ONEPROVIDER_FREE -> ModelCatalog.resolve(LlmProviderType.ONEPROVIDER_FREE, settings.oneProviderModel, purpose)
            LlmProviderType.LOCAL -> return
        }
        settingsRepository.recordLlmRequestLog(
            LlmRequestLogEntry(
                timestampMillis = System.currentTimeMillis(),
                provider = settings.llmProviderType.name,
                model = model,
                purpose = purpose.name,
                promptTokens = promptTokens,
                completionTokens = completionTokens,
                toolCallCount = toolCallCount,
                durationMillis = System.currentTimeMillis() - requestStartMillis,
                error = errorMessage,
            ),
        )
    }

    /** Streaming-safe wrapper around [PrivacyShield.unmask]: a placeholder tag like `[USER]` can
     * arrive split across two separate [LlmStreamEvent.TextDelta] chunks, and unmasking each
     * chunk independently would then miss it (half a tag looks like nothing to the regex).
     * Appends [delta] to [tail], then holds back everything from the last *unmatched* `[`
     * onward (an opening bracket with no closing `]` after it yet, i.e. a tag that might still
     * be mid-stream) as the new [tail], and returns the unmasked, safe-to-emit prefix before it. */
    private fun unmaskStreamChunk(tail: StringBuilder, delta: String, userName: String): String {
        tail.append(delta)
        val combined = tail.toString()
        val lastOpen = combined.lastIndexOf('[')
        val lastClose = combined.lastIndexOf(']')
        val splitIndex = if (lastOpen > lastClose) lastOpen else combined.length
        val safePart = combined.substring(0, splitIndex)
        tail.clear()
        tail.append(combined.substring(splitIndex))
        return PrivacyShield.unmask(safePart, userName)
    }

    /** Streaming-safe detector for "Text-Cosplay" -- a model writing its tool call out as literal
     * text/XML (e.g. `<tool>get_cgm_history</tool>`) instead of using the provider's native
     * function-calling API, see [toolCosplayOpenTagRegex]. Same held-back-tail technique as
     * [unmaskStreamChunk] so a tag split across two deltas is still recognized -- but unlike
     * [unmaskStreamChunk] (which always eventually resolves a matched `[...]` placeholder), once
     * a recognized cosplay tag's *opening* tag is fully seen, this returns `found = true`
     * immediately rather than also trying to locate a matching closing tag: a model that starts
     * cosplaying a tool call essentially never produces other, separately-worth-showing text for
     * the rest of that turn, so [runToolLoop] simply stops forwarding anything else this round
     * instead of adding the complexity of tracking open/close tag pairs mid-stream. Returns the
     * safe-to-emit text before the tag (if any) alongside whether a cosplay tag was found. */
    private fun detectToolCosplay(tail: StringBuilder, delta: String): Pair<String, Boolean> {
        tail.append(delta)
        val combined = tail.toString()
        val match = toolCosplayOpenTagRegex.find(combined)
        if (match != null) {
            tail.clear()
            return combined.substring(0, match.range.first) to true
        }
        val lastOpen = combined.lastIndexOf('<')
        val splitIndex = if (lastOpen >= 0 && !combined.substring(lastOpen).contains('>')) lastOpen else combined.length
        val safePart = combined.substring(0, splitIndex)
        tail.clear()
        tail.append(combined.substring(splitIndex))
        return safePart to false
    }

    /** Compresses a large raw tool result (e.g. hundreds of individual CGM readings) down to a
     * compact statistical summary -- average, min/max, and Time-in-Range -- before it's fed back
     * to the *local* model specifically (see [runToolLoop]'s call site; cloud providers keep the
     * full result, they have much larger context budgets). Falls back to plain truncation if the
     * result doesn't look like a numeric glucose series at all, rather than guessing. Left
     * unchanged if it's already short enough that pruning wouldn't help. */
    private fun pruneToolResultForLocal(resultText: String): String {
        if (resultText.length <= MAX_LOCAL_TOOL_RESULT_CHARS) return resultText
        val values = Regex("""\b(\d{2,3})\s*(?:mg/dL|mg/dl)?\b""").findAll(resultText)
            .mapNotNull { it.groupValues[1].toIntOrNull() }
            .filter { it in 20..600 } // plausible glucose range -- filters out unrelated numbers
            .toList()
        if (values.size < 5) {
            return resultText.take(MAX_LOCAL_TOOL_RESULT_CHARS) + "\n… (gekürzt, ${resultText.length} Zeichen gesamt)"
        }
        val average = values.average()
        val timeInRangeCount = values.count { it in 70..180 }
        val timeInRangePercent = 100.0 * timeInRangeCount / values.size
        return "Zusammenfassung (${values.size} Messwerte, für lokales Modell komprimiert): " +
            "Ø %.0f mg/dL, Min %d mg/dL, Max %d mg/dL, Time in Range (70-180) %.0f%%."
                .format(average, values.min(), values.max(), timeInRangePercent)
    }

    /**
     * Kontext-Komprimierung für den historischen Zeitraum (Turbo-Übersicht item 3): a multi-day
     * CGM tool result -- Nightscout-style APIs return one JSON object PER five-minute reading, so
     * a 7/14-day comparison window is easily 2000-4000 entries -- is compressed here into one
     * aggregate row PER CALENDAR DAY (avg/min/max/TIR%/hypo count) before it reaches the model,
     * cutting the JSON token count by roughly two orders of magnitude for a multi-day fetch.
     *
     * Deliberately NOT the same shape as the reverted client-side dashboard-metrics attempt that
     * once produced a false 42.6% severe-hypoglycemia reading by regex-matching *any* plausible
     * 2-3 digit number in raw tool-result text (timestamps, entry counts, and other unrelated
     * fields all matched just as easily as a real glucose value). This instead requires:
     *  - the result to actually parse as JSON (see [extractReadingEntries]) -- free-form text
     *    that merely happens to contain numbers is never touched;
     *  - EVERY counted reading to come from an object with both an explicitly-named glucose-value
     *    key AND an explicitly-named, parseable timestamp key (see [parseGlucoseReading]) -- an
     *    unrelated numeric field is never mistaken for one just because it's "a plausible number";
     *  - at least [MIN_ENTRIES_FOR_HISTORY_COMPRESSION] entries to confidently parse this way, AND
     *    at least 80% of all entries found in the result to parse -- if the field-naming
     *    convention wasn't actually recognized (a differently-shaped or unfamiliar MCP server),
     *    this bails out and returns [resultText] completely unmodified rather than compressing a
     *    small, unrepresentative, possibly-misleading subset.
     * The model still receives and reasons over the (compressed) numbers itself for its own final
     * report, exactly as it already does for the uncompressed case -- this never substitutes its
     * own computed value for a number shown directly to the user, unlike the reverted attempt.
     */
    private fun compressHistoricalCgmResult(resultText: String): String {
        val root = runCatching { historyCompressionJson.parseToJsonElement(resultText) }.getOrNull() ?: return resultText
        val entries = extractReadingEntries(root) ?: return resultText
        if (entries.size < MIN_ENTRIES_FOR_HISTORY_COMPRESSION) return resultText

        val zone = ZoneId.systemDefault()
        val readings = entries.mapNotNull { entry -> parseGlucoseReading(entry, zone) }
        if (readings.size < entries.size * 0.8) return resultText

        val byDay = readings.groupBy({ it.first }, { it.second })
        if (byDay.size < 2) return resultText // a single day's worth -- nothing to gain by compressing

        val lines = byDay.toSortedMap().map { (day, values) ->
            val avg = values.average()
            val tir = 100.0 * values.count { it in 70.0..180.0 } / values.size
            val hypoCount = values.count { it < 70.0 }
            "$day: Ø %.0f mg/dL, Min %.0f, Max %.0f, TIR %.0f%%, Hypo-Messungen %d, n=%d"
                .format(avg, values.min(), values.max(), tir, hypoCount, values.size)
        }
        return "Tages-Zusammenfassung (Kotlin-vorkomprimiert aus ${entries.size} Einzelmesswerten, " +
            "da Zeitraum > 24h): jeweils Datum, Durchschnitt, Min/Max, Time-in-Range (70-180 mg/dL), " +
            "Anzahl Unterzuckerungen (<70 mg/dL), Anzahl Messwerte:\n" + lines.joinToString("\n")
    }

    /** The list of per-reading JSON objects inside [root], or null if that shape isn't
     * recognizable at all -- either [root] itself is a JSON array of objects, or (the far more
     * common real-world shape) a JSON object with exactly one array-valued field holding them
     * (e.g. `{"entries": [...]}`, `{"data": [...]}`, `{"result": [...]}` -- picks the LARGEST
     * array field present, on the assumption that the actual readings list is never the smallest
     * array in the payload). Returns null (not an empty list) when any array element isn't itself
     * an object, since that means this guess about the shape was wrong. */
    private fun extractReadingEntries(root: JsonElement): List<JsonObject>? = when (root) {
        is JsonArray -> root.map { it as? JsonObject ?: return null }
        is JsonObject -> {
            val arrayField = root.values.filterIsInstance<JsonArray>().maxByOrNull { it.size } ?: return null
            arrayField.map { it as? JsonObject ?: return null }
        }
        else -> null
    }

    private val glucoseValueKeys = setOf(
        "sgv", "glucose", "value", "mgdl", "mg_dl", "bg", "bloodglucose", "blood_glucose", "glucosevalue", "glucose_value",
    )
    private val timestampKeys = setOf(
        "date", "datemillis", "dateepochmillis", "timestamp", "time", "epoch", "datestring", "created_at", "createdat",
    )

    /** One reading's (calendar day, mg/dL value), or null unless BOTH an explicitly-named glucose
     * key (from [glucoseValueKeys], case-insensitive, value plausibility-checked to 20-600 mg/dL
     * as a sanity backstop, not as the primary signal) AND an explicitly-named, parseable
     * timestamp key (from [timestampKeys]; accepts epoch millis, epoch seconds, or the date
     * portion of an ISO-8601 string) are present on [entry] -- see [compressHistoricalCgmResult]'s
     * doc comment for why both are required rather than either alone. */
    private fun parseGlucoseReading(entry: JsonObject, zone: ZoneId): Pair<String, Double>? {
        val valueKey = entry.keys.firstOrNull { it.lowercase() in glucoseValueKeys } ?: return null
        val mgDl = entry[valueKey]?.jsonPrimitive?.doubleOrNull?.takeIf { it in 20.0..600.0 } ?: return null
        val tsKey = entry.keys.firstOrNull { it.lowercase() in timestampKeys } ?: return null
        val tsPrimitive = entry[tsKey]?.jsonPrimitive ?: return null
        val dayKey = tsPrimitive.longOrNull?.let { raw ->
            val millis = if (raw < 10_000_000_000L) raw * 1000 else raw // accept epoch seconds too
            Instant.ofEpochMilli(millis).atZone(zone).toLocalDate().toString()
        } ?: tsPrimitive.contentOrNull
            ?.take(10)
            ?.takeIf { it.length == 10 && it[4] == '-' && it[7] == '-' } // "yyyy-MM-dd" prefix of an ISO-8601 string
        return dayKey?.let { it to mgDl }
    }

    /** What to send next: either free-form user text, or the results of the whole tool-call
     * batch the model just requested (one entry for a single call, several for parallel ones). */
    private sealed interface NextInput {
        data class UserText(val text: String) : NextInput
        data class ToolResults(val results: List<ToolResultPayload>) : NextInput
    }

    /** Carries a Flow failure out of the `catch` operator (whose `emit` binds to the *upstream*
     * `Flow<LlmStreamEvent>`, not this function's `Flow<AgentEvent>`) so it can be turned into a
     * proper [AgentEvent.Error] at the outer level. [cause] keeps the original exception (e.g.
     * [HttpStatusException], [java.net.UnknownHostException]) reachable via `Throwable.cause` for
     * anyone further up the call chain classifying the failure (see
     * [com.example.diabai.domain.analytics.isDashboardNetworkTimeout]/`isDashboardOffline`) --
     * losing it here would make a genuine offline error and a same-message API error
     * indistinguishable by the time they reach the UI. */
    private class AgentGenerationError(message: String, cause: Throwable?) : Exception(message, cause)

    /**
     * Convenience wrapper around [ask] for callers that just want the finished report text
     * (e.g. "Erstelle einen Wochenbericht") rather than the step-by-step event stream. Runs
     * with confirmation disabled -- there's no UI attached to answer it -- so only use this
     * for non-interactive/background report generation. Any text the model produced in a
     * round that ended in a tool call (its reasoning/preamble before deciding to call a tool)
     * is discarded -- only the text from the final, tool-call-free round counts as the report.
     * [includeMcpTools] set to false skips tool listing/calling entirely -- for callers (like a
     * narrative-only dashboard step over already-computed numbers) that want a fast, plain text
     * generation with no MCP round trip at all.
     */
    suspend fun generateReport(
        request: String,
        selectedServerIds: Set<String>? = null,
        includeMcpTools: Boolean = true,
        /** See [ask]'s own doc comment -- e.g. [DASHBOARD_ALLOWED_TAGS] for the Übersicht tab's
         * strict cgm/insulin_carbs-only Stage-2 metrics fetch (Turbo-Übersicht item 2). Null (the
         * default) leaves every caller that doesn't opt in completely unaffected. */
        allowedTags: Set<CapabilityTag>? = null,
    ): Result<String> {
        var reportText = StringBuilder()
        var error: String? = null

        ask(
            request,
            requireConfirmation = false,
            includeMcpTools = includeMcpTools,
            selectedServerIds = selectedServerIds,
            allowedTags = allowedTags,
        ).collect { event ->
            when (event) {
                is AgentEvent.Answer -> reportText.append(event.delta)
                is AgentEvent.ToolCallStarted -> reportText = StringBuilder()
                is AgentEvent.Error -> error = event.message
                is AgentEvent.ToolCallCompleted,
                is AgentEvent.ToolCallDeclined,
                is AgentEvent.ConfirmationRequired,
                // generateReport() always calls ask(), never askChat() -- the only producer of
                // SourceChoiceRequired -- so this branch is unreachable here, just required for
                // exhaustiveness.
                is AgentEvent.SourceChoiceRequired,
                is AgentEvent.ModelResolved,
                AgentEvent.Complete,
                -> Unit
            }
        }

        return error?.let { Result.failure(IllegalStateException(it)) } ?: Result.success(reportText.toString())
    }

    /** German, human-readable, always the real device clock -- deliberately not just a date, so
     * "heute Abend"/"gerade eben"-style phrasing in chat also has something concrete to anchor to. */
    private fun currentDateContext(): String {
        val now = ZonedDateTime.now()
        val formatted = now.format(DateTimeFormatter.ofPattern("EEEE, d. MMMM yyyy, HH:mm 'Uhr'", Locale.GERMAN))
        return "# AKTUELLES DATUM\nHeute ist $formatted (Zeitzone: ${now.zone}). Nutze dieses Datum verbindlich " +
            "als Referenz für relative Zeitangaben (heute, gestern, letzte Woche, ...) und für Datums-/" +
            "Zeit-Parameter bei Tool-Aufrufen -- rate niemals ein anderes Datum."
    }

    private fun McpTool.toLlmToolSpec(): LlmToolSpec {
        val schema = (inputSchema as? JsonObject) ?: buildJsonObject { put("type", "object") }
        return LlmToolSpec(name = name, description = description, parametersSchema = schema)
    }

    private fun describeToolCall(toolCall: ToolCall): String {
        val argsText = toolCall.arguments.entries.joinToString(", ") { (key, value) -> "$key=$value" }
        return "Soll das Tool '${toolCall.name}($argsText)' ausgeführt werden?"
    }
}
