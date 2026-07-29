package com.example.diabai.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.diabai.data.AppSettings
import com.example.diabai.data.ChatSession
import com.example.diabai.data.SettingsRepository
import com.example.diabai.data.StoredMessage
import com.example.diabai.data.StoredRole
import com.example.diabai.domain.AgentEvent
import com.example.diabai.domain.CHAT_CAPABILITY_TAG_GROUPS
import com.example.diabai.domain.CapabilityTag
import com.example.diabai.domain.CapabilityTagGroup
import com.example.diabai.domain.DiabetesAgent
import com.example.diabai.domain.analytics.DashboardState
import com.example.diabai.domain.analytics.DashboardTimeRange
import com.example.diabai.domain.analytics.DiabetesDashboardManager
import com.example.diabai.domain.analytics.TimeWindow
import com.example.diabai.domain.analytics.dashboardFailureDetail
import com.example.diabai.domain.analytics.isDashboardNetworkTimeout
import com.example.diabai.domain.analytics.isDashboardOffline
import com.example.diabai.network.McpServerPool
import com.example.diabai.network.allDataSourceServers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

class GlucoSphereViewModel(
    private val dashboardManager: DiabetesDashboardManager,
    private val agent: DiabetesAgent,
    private val settingsRepository: SettingsRepository,
    private val mcpServerPool: McpServerPool,
) : ViewModel() {

    val settings: StateFlow<AppSettings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    /** Result of the app-start MCP health check (see MainActivity) -- which configured servers
     * actually answered the connect handshake this session. Used to grey out unreachable
     * servers in the chat tab's filter chips instead of letting the user pick one that will
     * just fail on the first tool call. */
    val activeServerIds: StateFlow<Set<String>> = mcpServerPool.connectedServerIds

    // --- Übersicht (dashboard) ---

    val dashboardState: StateFlow<DashboardState> = dashboardManager.state

    private val _selectedTimeRange = MutableStateFlow(DashboardTimeRange.LAST_24H)
    val selectedTimeRange: StateFlow<DashboardTimeRange> = _selectedTimeRange.asStateFlow()

    /** null = all connected servers; otherwise the subset of server ids to query. */
    private val _selectedServerIds = MutableStateFlow<Set<String>?>(null)
    val selectedServerIds: StateFlow<Set<String>?> = _selectedServerIds.asStateFlow()

    /** Only meaningful (both non-null) while [selectedTimeRange] is [DashboardTimeRange.CUSTOM]
     * -- the "Eigener Zeitraum" date picker's current Von/Bis selection. */
    private val _customRangeStart = MutableStateFlow<Long?>(null)
    val customRangeStart: StateFlow<Long?> = _customRangeStart.asStateFlow()

    private val _customRangeEnd = MutableStateFlow<Long?>(null)
    val customRangeEnd: StateFlow<Long?> = _customRangeEnd.asStateFlow()

    /** ">5 Tage -> Single-Select": whether the Datenquellen chips currently allow only one
     * server per category active at a time. Exposed for the UI to grey out multi-select-only
     * affordances; the actual enforcement happens in [toggleServerSelected]/
     * [enforceSingleSelectIfNeeded] regardless of whether the UI reads this. */
    val isSingleSelectMode: StateFlow<Boolean> =
        combine(_selectedTimeRange, _customRangeStart, _customRangeEnd) { range, start, end -> isLongRange(range, start, end) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private fun isLongRange(range: DashboardTimeRange, customStart: Long?, customEnd: Long?): Boolean = when (range) {
        DashboardTimeRange.LAST_7_DAYS, DashboardTimeRange.LAST_30_DAYS, DashboardTimeRange.LAST_3_MONTHS -> true
        DashboardTimeRange.CUSTOM -> customStart != null && customEnd != null && (customEnd - customStart) > 5L * 24 * 60 * 60 * 1000
        else -> false
    }

    /** Collapses the current selection down to at most one server per category, keeping
     * whichever was already selected within each category (first one wins if more than one
     * was) -- called whenever the range changes to/within a >5-day window, so switching *into*
     * one doesn't leave a stale multi-select from a shorter range in place. */
    private fun enforceSingleSelectIfNeeded() {
        if (!isLongRange(_selectedTimeRange.value, _customRangeStart.value, _customRangeEnd.value)) return
        val servers = settings.value.allDataSourceServers.filter { it.enabled }
        val current = _selectedServerIds.value ?: servers.map { it.id }.toSet()
        val keepOnePerCategory = current
            .mapNotNull { id -> servers.firstOrNull { it.id == id } }
            .groupBy { it.category }
            .values
            .mapNotNull { it.firstOrNull()?.id }
            .toSet()
        if (keepOnePerCategory != current) _selectedServerIds.value = keepOnePerCategory
    }

    // --- Dynamische Tool-Registrierung (Discovery Modus, item 4) ---

    /** Every tool name Discovery Modus has ever found across all data sources (see
     * [com.example.diabai.domain.discovery.DiscoveryService]), deduplicated -- the extra,
     * individually-toggleable pill chips shown above the chat input alongside the per-server
     * ones. Loaded once at init from the same persisted cache the Discovery-Sheet reads/writes
     * (see [SettingsRepository.loadAllDiscoveryRecords]); a fresh "Erkunden" run elsewhere isn't
     * reflected here until the next app start, which is an acceptable staleness window for a
     * purely additive "which tools are known to exist" list.
     *
     * Declared (along with the rest of this section) BEFORE [init] below: [viewModelScope.launch]
     * runs on `Dispatchers.Main.immediate`, which executes synchronously (not merely "soon") when
     * already on the main thread -- exactly the case for a ViewModel being constructed here. A
     * property referenced from an `init` block's coroutine must therefore already be initialized
     * at that point in the primary constructor's own declaration order, or the reference is still
     * null; this crashed with a NullPointerException on `_discoveredToolNames.value = ...` the
     * first time this was declared further down the class body, after `init`. */
    private val _discoveredToolNames = MutableStateFlow<List<String>>(emptyList())
    val discoveredToolNames: StateFlow<List<String>> = _discoveredToolNames.asStateFlow()

    /** Tool names the user explicitly switched OFF via their pill chip -- an opt-out filter over
     * whatever [chatSelectedCapabilityTags] would otherwise include, passed straight through to
     * [DiabetesAgent.askChat]'s `excludedToolNames`. Empty by default: no discovered tool is
     * excluded until the user actively taps its chip. */
    private val _excludedToolNames = MutableStateFlow<Set<String>>(emptySet())
    val excludedToolNames: StateFlow<Set<String>> = _excludedToolNames.asStateFlow()

    fun toggleDiscoveredTool(toolName: String) {
        _excludedToolNames.update { current -> if (toolName in current) current - toolName else current + toolName }
    }

    // --- Discovery-Sheet "💡 Was du fragen kannst" -> Chat (item 3) ---

    /** Set by [DataSourcesScreen]'s example-question chips (via `onAskInChat`, wired in
     * `MainActivity`) -- [MainTabsScreen] watches this to switch to the Chat tab, and
     * [ChatSection] watches it to prefill (and clear) the input field. `null` = nothing pending. */
    private val _pendingChatPrefill = MutableStateFlow<String?>(null)
    val pendingChatPrefill: StateFlow<String?> = _pendingChatPrefill.asStateFlow()

    fun prefillChatInput(text: String) {
        _pendingChatPrefill.value = text
    }

    fun consumePendingChatPrefill() {
        _pendingChatPrefill.value = null
    }

    init {
        // Manuelle Aktualisierung (item 1): the app start / Übersicht-tab entry NO LONGER
        // triggers a network fetch (Stufe 1 Nightscout-Livewert or Stufe 2 KI-Vergleichsbericht)
        // on its own -- only whatever was cached from the last successful refresh is shown,
        // offline-first, straight from DataStore. A fresh fetch happens ONLY when the user
        // explicitly triggers [refreshDashboard] via Pull-to-Refresh or the "Aktualisieren"
        // button (see DashboardSection's onRefresh). If nothing was ever cached (a brand-new
        // install), dashboardState simply stays DashboardState.Idle -- DashboardSection renders
        // that as a friendly "Noch keine Daten" prompt with its own refresh call-to-action,
        // rather than an indefinite loading spinner that (with no auto-refresh left to resolve
        // it) would previously have spun forever.
        viewModelScope.launch {
            dashboardManager.seedFromCache()
        }
        // Discovery Modus (item 4): every tool name ever discovered, for the chat input's
        // per-tool pill-chip row -- see [discoveredToolNames]'s doc comment for the staleness
        // trade-off of only loading this once, at startup.
        viewModelScope.launch {
            _discoveredToolNames.value = settingsRepository.loadAllDiscoveryRecords()
                .values
                .flatMap { record -> record.schema.tools.map { it.name } }
                .distinct()
                .sorted()
        }
    }

    fun setTimeRange(range: DashboardTimeRange) {
        _selectedTimeRange.value = range
        if (range == DashboardTimeRange.CUSTOM) {
            // Seed sensible defaults (last 7 days) the first time, but let the user confirm via
            // "Anwenden" before spending a fetch on them -- switching *into* custom mode
            // shouldn't itself trigger a request yet.
            if (_customRangeStart.value == null) _customRangeStart.value = System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000
            if (_customRangeEnd.value == null) _customRangeEnd.value = System.currentTimeMillis()
        } else {
            enforceSingleSelectIfNeeded()
            refreshDashboard()
        }
    }

    fun setCustomRangeStart(millis: Long) {
        _customRangeStart.value = millis
    }

    fun setCustomRangeEnd(millis: Long) {
        _customRangeEnd.value = millis
    }

    /** "Anwenden" in the custom-range picker. */
    fun applyCustomRange() {
        if (_selectedTimeRange.value == DashboardTimeRange.CUSTOM) {
            enforceSingleSelectIfNeeded()
            refreshDashboard()
        }
    }

    /** Tapping the "(Vergleich zum Vorzeitraum: ...)" line: loads that period's dates straight
     * into the custom-range picker and refreshes immediately (no separate "Anwenden" needed --
     * the user already asked to see exactly this range by tapping it). */
    fun useComparisonPeriodAsCustomRange() {
        val dashboard = (dashboardState.value as? DashboardState.Loaded)?.dashboard ?: return
        _customRangeStart.value = dashboard.previousWindowStartMillis
        _customRangeEnd.value = dashboard.previousWindowEndMillis
        _selectedTimeRange.value = DashboardTimeRange.CUSTOM
        refreshDashboard()
    }

    /** In single-select mode (>5-Tage-Zeitraum), tapping a chip *replaces* whichever server was
     * active in its category rather than toggling membership -- "es darf nur genau eine
     * Datenquelle pro Thema aktiv sein". Other categories' selections (e.g. a Sport-Quelle) are
     * left untouched. */
    fun toggleServerSelected(serverId: String) {
        val enabledServers = settings.value.allDataSourceServers.filter { it.enabled }
        if (isLongRange(_selectedTimeRange.value, _customRangeStart.value, _customRangeEnd.value)) {
            val tapped = enabledServers.firstOrNull { it.id == serverId } ?: return
            val current = _selectedServerIds.value ?: enabledServers.map { it.id }.toSet()
            val keepFromOtherCategories = current.filter { id -> enabledServers.firstOrNull { it.id == id }?.category != tapped.category }
            _selectedServerIds.value = (keepFromOtherCategories + serverId).toSet()
        } else {
            val current = _selectedServerIds.value ?: enabledServers.map { it.id }.toSet()
            _selectedServerIds.value = if (serverId in current) current - serverId else current + serverId
        }
        refreshDashboard()
    }

    fun refreshDashboard() {
        val timeRange = _selectedTimeRange.value
        val servers = settings.value.allDataSourceServers.filter { it.enabled }
        val customWindow = if (timeRange == DashboardTimeRange.CUSTOM) {
            val start = _customRangeStart.value
            val end = _customRangeEnd.value
            if (start == null || end == null || start >= end) return
            val hours = ((end - start) / 3_600_000L).toInt().coerceAtLeast(1)
            TimeWindow(start, end, hours)
        } else {
            null
        }

        viewModelScope.launch {
            val result = dashboardManager.refresh(timeRange, servers, _selectedServerIds.value, customWindow, settings.value.appLanguage)
            // "Wenn eine Datenquelle keine Daten zu dem Zeitraum liefern kann, schalte sie in
            // der Übersicht aus" -- deselect it (not remove/disconnect it) so its chip shows
            // unselected but the user can still flip it back on for a different period.
            result.getOrNull()?.dataUnavailableServerIds?.takeIf { it.isNotEmpty() }?.let { unavailable ->
                val current = _selectedServerIds.value ?: servers.map { it.id }.toSet()
                _selectedServerIds.value = current - unavailable
            }
            // A failed background refresh with a cached dashboard still on screen (see
            // DiabetesDashboardManager.refresh's handleRefreshFailure, which keeps that cached
            // state instead of replacing it with DashboardState.Error) -- surface a short,
            // non-blocking hint instead of silently doing nothing. A network-layer timeout
            // specifically (see isDashboardNetworkTimeout) gets its own more precise wording per
            // "Update-Timeout – zeige letzten bekannten Stand" vs. the generic offline hint.
            if (result.isFailure && dashboardState.value is DashboardState.Loaded) {
                val isGerman = settings.value.appLanguage == com.example.diabai.data.AppLanguage.GERMAN
                val exception = result.exceptionOrNull()
                val isTimeout = exception?.isDashboardNetworkTimeout() == true
                // Only a genuine no-connectivity failure gets the "Offline" wording -- anything
                // else (wrong/expired key, retired model id, exhausted quota, ...) means the
                // server WAS reached, so labeling it "Offline" would send the user looking at
                // their WLAN instead of at the actual cause (see dashboardFailureDetail).
                val isOffline = !isTimeout && exception?.isDashboardOffline() == true
                val message = when {
                    isTimeout && isGerman -> "Update-Timeout – zeige letzten bekannten Stand"
                    isTimeout -> "Update timeout – showing last known state"
                    isOffline && isGerman -> "Offline – zeige letzten bekannten Stand"
                    isOffline -> "Offline – showing last known state"
                    isGerman -> "Update fehlgeschlagen (${exception?.dashboardFailureDetail()}) – zeige letzten bekannten Stand"
                    else -> "Update failed (${exception?.dashboardFailureDetail()}) – showing last known state"
                }
                showTransientMessage(message)
            }
        }
    }

    // --- Chat ---

    private val _chatItems = MutableStateFlow<List<ChatItem>>(emptyList())
    val chatItems: StateFlow<List<ChatItem>> = _chatItems.asStateFlow()

    private val _isAgentBusy = MutableStateFlow(false)
    val isAgentBusy: StateFlow<Boolean> = _isAgentBusy.asStateFlow()

    private val _includeMcpTools = MutableStateFlow(true)
    val includeMcpTools: StateFlow<Boolean> = _includeMcpTools.asStateFlow()

    /** Tag-basiertes Capability-Routing (item 1): null = every capability allowed (default, no
     * chip explicitly restricting) -- otherwise the union of tags from every currently-selected
     * chip in [CHAT_CAPABILITY_TAG_GROUPS], replacing the old per-brand "[x] Nightscout
     * [x] Glooko" server chips (a chip now spans every server/brand offering a matching tool,
     * regardless of which one it actually is -- see [DiabetesAgent.resolveTools]). */
    private val _chatSelectedCapabilityTags = MutableStateFlow<Set<CapabilityTag>?>(null)
    val chatSelectedCapabilityTags: StateFlow<Set<CapabilityTag>?> = _chatSelectedCapabilityTags.asStateFlow()

    /** Toggles one whole chip's tag group at once -- selected/deselected together, mirroring the
     * old per-server chip's simple on/off toggle. */
    fun toggleChatCapabilityTagGroup(group: CapabilityTagGroup) {
        val current = _chatSelectedCapabilityTags.value ?: CHAT_CAPABILITY_TAG_GROUPS.flatMap { it.tags }.toSet()
        _chatSelectedCapabilityTags.value = if (current.containsAll(group.tags)) current - group.tags else current + group.tags
    }

    val chatSessions: StateFlow<List<ChatSession>> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings()).let { flow ->
            // Derived, but keep it simple/explicit rather than another stateIn+map chain.
            MutableStateFlow(flow.value.chatSessions).also { derived ->
                viewModelScope.launch { settingsRepository.settings.collect { derived.value = it.chatSessions } }
            }
        }

    private var currentSessionId: String = UUID.randomUUID().toString()
    private var generationJob: Job? = null

    fun setIncludeMcpTools(enabled: Boolean) {
        _includeMcpTools.value = enabled
    }

    fun startNewChat() {
        generationJob?.cancel()
        agent.resetChatSession()
        currentSessionId = UUID.randomUUID().toString()
        _chatItems.value = emptyList()
        _isAgentBusy.value = false
        _aiActivityState.value = AiActivityState.Idle
    }

    /** "Chatverlauf löschen" (confirmed via dialog) -- wipes every saved session and, since the
     * currently open chat would otherwise still get persisted right back on its next message
     * (see [persistCurrentSession]), also starts a fresh one so the screen empties immediately
     * and stays empty. */
    fun clearChatHistory() {
        startNewChat()
        viewModelScope.launch { settingsRepository.clearChatSessions() }
    }

    fun loadSession(session: ChatSession) {
        generationJob?.cancel()
        // The agent's persistent conversation only ever holds *this app run's* context -- a
        // session loaded from disk has no corresponding live conversation object to resume, so
        // this starts fresh rather than (incorrectly) continuing whatever was active before.
        agent.resetChatSession()
        currentSessionId = session.id
        _chatItems.value = session.messages.map { stored ->
            when (stored.role) {
                StoredRole.USER -> ChatItem.UserMessage(UUID.randomUUID().toString(), stored.text)
                StoredRole.ASSISTANT -> ChatItem.AssistantMessage(UUID.randomUUID().toString(), stored.text, isStreaming = false)
            }
        }
        _isAgentBusy.value = false
        _aiActivityState.value = AiActivityState.Idle
    }

    /** Stops the in-progress LiteRT-LM stream immediately (autoregressive generation can't be
     * resumed mid-sentence, so this is a hard stop, not a true pause). Also ends the chat
     * session once the cancelled turn has actually wound down: a cancellation mid-exchange can
     * leave the persistent conversation with a user message but no assistant reply, which some
     * providers (Claude notably) reject outright on the next call -- starting fresh is safer
     * than risking a broken conversation for the rest of the chat. */
    fun cancelGeneration() {
        val job = generationJob
        job?.cancel()
        _aiActivityState.value = AiActivityState.Idle
        viewModelScope.launch {
            job?.join()
            agent.resetChatSession()
        }
    }

    private val _aiActivityState = MutableStateFlow<AiActivityState>(AiActivityState.Idle)
    val aiActivityState: StateFlow<AiActivityState> = _aiActivityState.asStateFlow()

    fun sendMessage(text: String) {
        if (text.isBlank() || _isAgentBusy.value) return
        addItem(ChatItem.UserMessage(newId(), text))

        generationJob = viewModelScope.launch {
            _isAgentBusy.value = true
            _aiActivityState.value = AiActivityState.Thinking
            var currentAssistantId: String? = null
            val requestStartMillis = System.currentTimeMillis()

            agent.askChat(
                text,
                includeMcpTools = _includeMcpTools.value,
                excludedToolNames = _excludedToolNames.value,
                allowedTags = _chatSelectedCapabilityTags.value,
            ).collect { event ->
                when (event) {
                    is AgentEvent.Answer -> {
                        // First streamed delta of this turn is exactly "der Token-Stream beginnt"
                        // -- every later delta re-sets the same value, which is a cheap no-op for
                        // a StateFlow (equal values don't trigger a new emission).
                        _aiActivityState.value = AiActivityState.Generating
                        val id = currentAssistantId ?: newId().also { newAssistantId ->
                            currentAssistantId = newAssistantId
                            addItem(ChatItem.AssistantMessage(newAssistantId, "", isStreaming = true))
                        }
                        updateAssistantMessage(id) { it.copy(markdown = it.markdown + event.delta) }
                    }
                    is AgentEvent.ConfirmationRequired -> {
                        _aiActivityState.value = AiActivityState.Idle
                        addItem(ChatItem.PendingConfirmation(newId(), event.request.description, event.request))
                    }
                    is AgentEvent.SourceChoiceRequired -> {
                        _aiActivityState.value = AiActivityState.Idle
                        addItem(ChatItem.PendingSourceChoice(newId(), event.options, event.request))
                    }
                    is AgentEvent.ToolCallStarted -> {
                        _aiActivityState.value = AiActivityState.CallingTool(event.toolCall.name, event.serverName)
                        addItem(ChatItem.ToolActivity(newId(), event.toolCall.name, currentStrings().toolRunning))
                    }
                    is AgentEvent.ToolCallDeclined -> {
                        _aiActivityState.value = AiActivityState.Thinking
                        addItem(ChatItem.ToolActivity(newId(), event.toolCall.name, currentStrings().toolDeclined))
                    }
                    is AgentEvent.ToolCallCompleted -> {
                        // Back to "Thinking" (not Idle): the model still has to process the tool
                        // result and produce a follow-up turn before this exchange is actually
                        // done -- the next AgentEvent.Answer flips this to Generating again.
                        _aiActivityState.value = AiActivityState.Thinking
                        addItem(ChatItem.ToolActivity(newId(), event.toolCall.name, currentStrings().toolCompleted))
                    }
                    is AgentEvent.ModelResolved ->
                        addItem(ChatItem.SystemNote(newId(), currentStrings().autoRoutedTo(event.modelId)))
                    is AgentEvent.Error -> {
                        addItem(ChatItem.ErrorMessage(newId(), event.message))
                        _snackbarMessage.value = event.message
                    }
                    AgentEvent.Complete ->
                        currentAssistantId?.let { id ->
                            val duration = System.currentTimeMillis() - requestStartMillis
                            updateAssistantMessage(id) { it.copy(isStreaming = false, durationMillis = duration) }
                        }
                }
            }

            _aiActivityState.value = AiActivityState.Idle
            _isAgentBusy.value = false
            persistCurrentSession()
        }
    }

    private suspend fun persistCurrentSession() {
        val messages = _chatItems.value.mapNotNull { item ->
            when (item) {
                is ChatItem.UserMessage -> StoredMessage(StoredRole.USER, item.text)
                is ChatItem.AssistantMessage -> StoredMessage(StoredRole.ASSISTANT, item.markdown)
                else -> null
            }
        }
        if (messages.isEmpty()) return
        val title = (_chatItems.value.firstOrNull { it is ChatItem.UserMessage } as? ChatItem.UserMessage)
            ?.text?.take(60).orEmpty()
        settingsRepository.saveChatSession(ChatSession(id = currentSessionId, title = title, messages = messages))
    }

    fun respondToConfirmation(item: ChatItem.PendingConfirmation, approved: Boolean) {
        item.request.respond(approved)
        _chatItems.update { items -> items.filterNot { it.id == item.id } }
    }

    /** [pickedServerId] null = "Alle Quellen" (see [com.example.diabai.domain.SourceChoiceRequest]). */
    fun respondToSourceChoice(item: ChatItem.PendingSourceChoice, pickedServerId: String?) {
        item.request.respond(pickedServerId)
        _chatItems.update { items -> items.filterNot { it.id == item.id } }
    }

    // --- Shared transient message surface (Snackbar), consumed once by the UI ---

    private val _snackbarMessage = MutableStateFlow<String?>(null)
    val snackbarMessage: StateFlow<String?> = _snackbarMessage.asStateFlow()

    fun consumeSnackbarMessage() {
        _snackbarMessage.value = null
    }

    /** Non-error transient feedback -- currently only "Nachricht kopiert" after long-pressing a
     * chat bubble (see ChatSection's `onCopied`). Reuses the same Snackbar surface as error
     * messages rather than adding a second one. */
    fun showTransientMessage(message: String) {
        _snackbarMessage.value = message
    }

    private fun addItem(item: ChatItem) {
        _chatItems.update { it + item }
    }

    private fun updateAssistantMessage(id: String, transform: (ChatItem.AssistantMessage) -> ChatItem.AssistantMessage) {
        _chatItems.update { items ->
            items.map { if (it.id == id && it is ChatItem.AssistantMessage) transform(it) else it }
        }
    }

    private fun newId(): String = UUID.randomUUID().toString()

    /** [LocalStrings.current] isn't reachable here (this is a ViewModel, not a Composable) --
     * same [stringsFor] lookup, just driven by the current settings snapshot instead of the
     * Compose tree. */
    private fun currentStrings(): Strings = stringsFor(settings.value.appLanguage)
}

class GlucoSphereViewModelFactory(
    private val dashboardManager: DiabetesDashboardManager,
    private val agent: DiabetesAgent,
    private val settingsRepository: SettingsRepository,
    private val mcpServerPool: McpServerPool,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return GlucoSphereViewModel(dashboardManager, agent, settingsRepository, mcpServerPool) as T
    }
}
