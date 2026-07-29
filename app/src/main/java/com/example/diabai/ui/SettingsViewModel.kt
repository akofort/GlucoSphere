package com.example.diabai.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.diabai.data.AppSettings
import com.example.diabai.data.AuthMethod
import com.example.diabai.data.DownloadProgress
import com.example.diabai.data.LlmProviderType
import com.example.diabai.data.LlmRequestLogEntry
import com.example.diabai.data.McpServerConfig
import com.example.diabai.data.McpTransport
import com.example.diabai.data.ModelFileManager
import com.example.diabai.data.OAuth2Config
import com.example.diabai.data.SettingsRepository
import com.example.diabai.data.buildHeader
import com.example.diabai.data.decodeSettingsBackup
import com.example.diabai.data.encodeToJson
import com.example.diabai.data.resolveAuthHeader
import com.example.diabai.data.toBackup
import com.example.diabai.domain.EngineState
import com.example.diabai.domain.LiteRtInferenceEngine
import com.example.diabai.domain.OAuth2CallbackBus
import com.example.diabai.domain.PendingOAuth2Flow
import com.example.diabai.domain.SettingsBackupFile
import com.example.diabai.domain.buildAuthorizationUrl
import com.example.diabai.domain.discoverOAuth2Metadata
import com.example.diabai.domain.discovery.DiscoveryRecord
import com.example.diabai.domain.discovery.DiscoveryService
import com.example.diabai.domain.exchangeOAuth2Code
import com.example.diabai.domain.generatePkcePair
import com.example.diabai.domain.llm.LLMProviderManager
import com.example.diabai.network.HttpStatusException
import com.example.diabai.network.McpException
import com.example.diabai.network.McpServerPool
import com.example.diabai.network.McpTool
import com.example.diabai.network.newMcpConnection
import com.example.diabai.network.nightscoutUrl
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.sse.SSEClientException
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.http.isSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.ConnectException
import java.net.UnknownHostException
import java.util.UUID

sealed interface McpConnectionState {
    data object Idle : McpConnectionState
    data object Connecting : McpConnectionState
    data object Connected : McpConnectionState
    data class Error(val message: String) : McpConnectionState
}

/** Per-server state of the "Login mit Provider" OAuth2 button -- separate from
 * [McpConnectionState] since logging in and actually connecting the MCP client are two different
 * steps (a successful login still needs "Verbindung testen"/save to actually use the fresh
 * token). */
sealed interface OAuth2LoginState {
    data object Idle : OAuth2LoginState
    /** Auto-discovering the provider's OAuth2 endpoints/client ID (see [discoverOAuth2Metadata])
     * before a manually-configured [OAuth2Config] is even needed -- the "Claude-like" no-manual-
     * setup path. */
    data object Discovering : OAuth2LoginState
    data object LoggingIn : OAuth2LoginState
    data object LoggedIn : OAuth2LoginState
    data class Error(val message: String) : OAuth2LoginState
}

/** [connectionTestKey]'s "token" input for an MCP server -- the manually-entered token for every
 * [AuthMethod] except [AuthMethod.OAUTH2], where there's no such field and the OAuth2 access
 * token (once logged in) plays the same "did the credential actually change" role instead. */
internal fun mcpTestTokenFor(authMethod: AuthMethod, manualToken: String, oauth2: OAuth2Config): String =
    if (authMethod == AuthMethod.OAUTH2) oauth2.accessToken else manualToken

/**
 * Result of an explicit "Verbindung testen" tap -- used for the MCP SSE test (per server), the
 * Nightscout REST API test, and the cloud LLM provider key test, independent of the app's real
 * (persisted/connected) state. Both terminal states carry [Finished.testedTarget] -- the UI
 * matches it against a freshly-recomputed key from the current form fields to decide whether a
 * *stale* result (from before the last edit) should be hidden; without a target on [Error] too,
 * a real failure could never be distinguished from "not tested yet" and would silently vanish.
 */
sealed interface ConnectionTestState {
    data object Idle : ConnectionTestState
    data object Testing : ConnectionTestState
    sealed interface Finished : ConnectionTestState {
        val testedTarget: String
    }
    data class Success(override val testedTarget: String) : Finished
    data class Error(override val testedTarget: String, val message: String) : Finished
}

/**
 * Composite key identifying "what was actually tested" -- URL plus auth method plus token --
 * so a stale "it worked" result doesn't survive an edit to auth and not just the URL. Shared
 * between this ViewModel (which stamps [ConnectionTestState.Success] with it) and
 * DataSourcesScreen (which recomputes it from the current fields to check for a match).
 */
internal fun connectionTestKey(target: String, authMethod: AuthMethod, token: String): String =
    target + "::" + authMethod.name + "::" + token

/** Same idea as [connectionTestKey], plus the transport -- switching a server between "HTTP
 * (Streamable)" and "HTTP+SSE" changes how the connection is actually made, so a result tested
 * under one transport must not look "current" for the other. */
internal fun connectionTestKey(target: String, authMethod: AuthMethod, token: String, transport: McpTransport): String =
    connectionTestKey(target, authMethod, token) + "::" + transport.name

/** Same "what was actually tested" idea as [connectionTestKey], for the cloud LLM provider key
 * form (provider type + key + base URL, the latter only meaningful for OpenAI/OpenRouter). */
internal fun llmProviderTestKey(type: LlmProviderType, apiKey: String, baseUrl: String): String =
    type.name + "::" + apiKey + "::" + baseUrl

/** Progress of one data source's "🔍 Erkunden" (Discovery Modus) run -- schema introspection
 * (fast, local-ish) followed by best-effort LLM profiling (the slower, network-bound step). Kept
 * separate from [ConnectionTestState] since discovery is a distinct, optional action a user takes
 * on an *already*-connected/tested data source, not a prerequisite for saving/enabling it. */
sealed interface DiscoveryUiState {
    data object Idle : DiscoveryUiState
    data object Running : DiscoveryUiState
    data class Success(val record: DiscoveryRecord) : DiscoveryUiState
    data class Error(val message: String) : DiscoveryUiState
}

/** Backup & Konfiguration (item 2) -- drives [SettingsViewModel.importSettings]'s UI: whether the
 * elegant password dialog needs to show, and the terminal outcome of an import attempt. Export has
 * no equivalent state machine -- it's a single request/response, surfaced instead via
 * [SettingsViewModel.backupExportError]. */
sealed interface BackupImportState {
    data object Idle : BackupImportState
    data object Importing : BackupImportState

    /** The read file is [SettingsBackupFile.ReadResult.PasswordRequired] -- its raw bytes are held
     * in [SettingsViewModel]'s own state until [SettingsViewModel.importSettingsWithPassword] (or
     * [SettingsViewModel.consumeBackupImportState] on cancel) is called. */
    data object PasswordRequired : BackupImportState
    data object WrongPassword : BackupImportState
    data object Success : BackupImportState
    data class Error(val message: String) : BackupImportState
}

/** Same idea again, for "Benutzerdefiniertes Modell eingeben" -- keyed additionally by the typed
 * model id, so switching *just* the custom id (key/URL unchanged) still invalidates a stale
 * "Testen" result rather than looking current. */
internal fun customModelTestKey(type: LlmProviderType, apiKey: String, baseUrl: String, modelId: String): String =
    llmProviderTestKey(type, apiKey, baseUrl) + "::" + modelId

class SettingsViewModel(
    private val settingsRepository: SettingsRepository,
    private val modelFileManager: ModelFileManager,
    private val llmEngine: LiteRtInferenceEngine,
    private val mcpServerPool: McpServerPool,
    private val providerManager: LLMProviderManager,
    private val discoveryService: DiscoveryService,
) : ViewModel() {

    val settings: StateFlow<AppSettings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    /** Model load status is owned by the engine itself -- reused here rather than duplicated. */
    val engineState = llmEngine.state

    private val _isImporting = MutableStateFlow(false)
    val isImporting: StateFlow<Boolean> = _isImporting.asStateFlow()

    private val _importError = MutableStateFlow<String?>(null)
    val importError: StateFlow<String?> = _importError.asStateFlow()

    private val _downloadProgress = MutableStateFlow<DownloadProgress?>(null)
    val downloadProgress: StateFlow<DownloadProgress?> = _downloadProgress.asStateFlow()

    /** Keyed by [McpServerConfig.id]. */
    private val _mcpConnectionStates = MutableStateFlow<Map<String, McpConnectionState>>(emptyMap())
    val mcpConnectionStates: StateFlow<Map<String, McpConnectionState>> = _mcpConnectionStates.asStateFlow()

    private val _mcpTestStates = MutableStateFlow<Map<String, ConnectionTestState>>(emptyMap())
    val mcpTestStates: StateFlow<Map<String, ConnectionTestState>> = _mcpTestStates.asStateFlow()

    private val _nightscoutApiTestState = MutableStateFlow<ConnectionTestState>(ConnectionTestState.Idle)
    val nightscoutApiTestState: StateFlow<ConnectionTestState> = _nightscoutApiTestState.asStateFlow()

    private val _llmProviderTestState = MutableStateFlow<ConnectionTestState>(ConnectionTestState.Idle)
    val llmProviderTestState: StateFlow<ConnectionTestState> = _llmProviderTestState.asStateFlow()

    private val _customModelTestState = MutableStateFlow<ConnectionTestState>(ConnectionTestState.Idle)
    val customModelTestState: StateFlow<ConnectionTestState> = _customModelTestState.asStateFlow()

    /** Keyed by [McpServerConfig.id]. */
    private val _oauth2LoginStates = MutableStateFlow<Map<String, OAuth2LoginState>>(emptyMap())
    val oauth2LoginStates: StateFlow<Map<String, OAuth2LoginState>> = _oauth2LoginStates.asStateFlow()

    /** Keyed by [McpServerConfig.id] -- populated on demand (expanding a server card, or the
     * eager fetch DataSourcesScreen does for already-connected servers so the collapsed header's
     * tool count doesn't need an expand first). Absent = not fetched yet, not "zero tools". */
    private val _mcpServerTools = MutableStateFlow<Map<String, List<McpTool>>>(emptyMap())
    val mcpServerTools: StateFlow<Map<String, List<McpTool>>> = _mcpServerTools.asStateFlow()

    /** "Discovery Modus": keyed by data-source id ([McpServerConfig.id], including the synthetic
     * direct-Nightscout-API id) -- [discoveryRecords] is the persisted result (schema + LLM
     * profile, survives app restarts, seeded from [SettingsRepository] at init below);
     * [discoveryUiStates] is purely this session's in-flight/last-run progress for the
     * Discovery-Sheet's own status line. */
    private val _discoveryRecords = MutableStateFlow<Map<String, DiscoveryRecord>>(emptyMap())
    val discoveryRecords: StateFlow<Map<String, DiscoveryRecord>> = _discoveryRecords.asStateFlow()

    private val _discoveryUiStates = MutableStateFlow<Map<String, DiscoveryUiState>>(emptyMap())
    val discoveryUiStates: StateFlow<Map<String, DiscoveryUiState>> = _discoveryUiStates.asStateFlow()

    init {
        viewModelScope.launch {
            _discoveryRecords.value = settingsRepository.loadAllDiscoveryRecords()
        }
        // Picks up the OAuth2 redirect (diabai://oauth-callback) MainActivity forwards here from
        // onNewIntent/onCreate -- see handleOAuth2Callback.
        viewModelScope.launch {
            OAuth2CallbackBus.events.collect { uri -> handleOAuth2Callback(uri) }
        }
        // Downloads survive the app being closed (DownloadManager runs at the OS level) --
        // if one was still in flight last time we ran, keep reflecting its progress.
        viewModelScope.launch {
            settingsRepository.settings.first().activeDownloadId?.let { pollDownloadProgress(it) }
        }
        // Loads (or reloads) the model whenever the saved path changes, whether that's from
        // picking a file, a finished direct download, or the completion BroadcastReceiver
        // updating it while this screen happens to be open.
        viewModelScope.launch {
            settingsRepository.settings.collect { current ->
                if (current.modelFilePath.isBlank()) return@collect
                val alreadyLoaded = (llmEngine.state.value as? EngineState.Ready)?.modelPath == current.modelFilePath
                if (alreadyLoaded) return@collect
                val file = File(current.modelFilePath)
                if (file.exists()) llmEngine.loadModel(file)
            }
        }
    }

    fun saveUserName(name: String) {
        viewModelScope.launch { settingsRepository.saveUserName(name) }
    }

    /** "Deutsch / English" toggle in Einstellungen. */
    fun saveAppLanguage(language: com.example.diabai.data.AppLanguage) {
        viewModelScope.launch { settingsRepository.saveAppLanguage(language) }
    }

    /** "Einstellungen -> Profil / Benutzer" (see [com.example.diabai.data.UserRole]). */
    fun saveUserRole(role: com.example.diabai.data.UserRole) {
        viewModelScope.launch { settingsRepository.saveUserRole(role) }
    }

    /** "Einstellungen -> Erscheinungsbild" (see [com.example.diabai.data.AppColorTheme]). */
    fun saveColorTheme(theme: com.example.diabai.data.AppColorTheme) {
        viewModelScope.launch { settingsRepository.saveColorTheme(theme) }
    }

    fun importModel(uri: Uri) {
        viewModelScope.launch {
            _isImporting.value = true
            _importError.value = null
            modelFileManager.importModel(uri)
                .onSuccess { file ->
                    settingsRepository.saveModelFilePath(file.absolutePath)
                    _isImporting.value = false
                }
                .onFailure { e ->
                    _isImporting.value = false
                    _importError.value = e.message ?: "Import fehlgeschlagen"
                }
        }
    }

    /**
     * Hands the URL to Android's own DownloadManager: it runs in the OS, survives the app
     * being closed, shows its own progress notification, and retries/resumes automatically
     * on transient network loss -- all things a hand-rolled in-process download can't do
     * reliably. [ModelDownloadReceiver] picks up the completion broadcast and moves the
     * finished file into app-private storage; [pollDownloadProgress] just mirrors its status
     * into this screen while it runs.
     */
    fun downloadModel(url: String) {
        viewModelScope.launch {
            _importError.value = null
            settingsRepository.saveModelDownloadUrl(url)
            val id = try {
                modelFileManager.enqueueDownload(url)
            } catch (e: Exception) {
                _importError.value = e.message ?: "Download konnte nicht gestartet werden"
                return@launch
            }
            settingsRepository.saveActiveDownloadId(id)
            pollDownloadProgress(id)
        }
    }

    private fun pollDownloadProgress(downloadId: Long) {
        viewModelScope.launch {
            _downloadProgress.value = DownloadProgress(0, null)
            while (isActive) {
                val status = modelFileManager.queryDownloadStatus(downloadId)
                if (status == null) {
                    // No longer known to DownloadManager -- ModelDownloadReceiver already
                    // handled completion (or the user/system cleared it).
                    _downloadProgress.value = null
                    return@launch
                }
                when (status.state) {
                    ModelFileManager.DownloadState.RUNNING, ModelFileManager.DownloadState.PENDING ->
                        _downloadProgress.value = DownloadProgress(status.bytesDownloaded, status.totalBytes)
                    ModelFileManager.DownloadState.SUCCESSFUL -> {
                        _downloadProgress.value = null
                        return@launch
                    }
                    ModelFileManager.DownloadState.FAILED -> {
                        _downloadProgress.value = null
                        _importError.value = "Download fehlgeschlagen: ${status.reason ?: "unbekannter Fehler"}"
                        settingsRepository.saveActiveDownloadId(null)
                        return@launch
                    }
                }
                delay(1000)
            }
        }
    }

    fun clearModel() {
        viewModelScope.launch {
            settingsRepository.saveModelFilePath("")
            llmEngine.unloadModel()
        }
    }

    /** Keyed by [McpServerConfig.id] -- the line-by-line trace of the most recent (or
     * in-progress) "Verbindung testen" attempt for that server, shown via "Verbindungs-Log
     * anzeigen" so a failure that isn't obvious from the one-line status can still be diagnosed
     * (e.g. did the SSE stream even open? did an `endpoint` event ever arrive?). */
    private val _mcpTestLogs = MutableStateFlow<Map<String, List<String>>>(emptyMap())
    val mcpTestLogs: StateFlow<Map<String, List<String>>> = _mcpTestLogs.asStateFlow()

    /** Runs a throwaway connection attempt without touching the app's real MCP pool. */
    fun testMcpServer(config: McpServerConfig) {
        viewModelScope.launch {
            _mcpTestStates.value = _mcpTestStates.value + (config.id to ConnectionTestState.Testing)
            _mcpTestLogs.value = _mcpTestLogs.value + (config.id to emptyList())
            val onLog: (String) -> Unit = { line ->
                _mcpTestLogs.value = _mcpTestLogs.value +
                    (config.id to (_mcpTestLogs.value[config.id].orEmpty() + line))
            }
            val result = withContext(Dispatchers.IO) { runMcpConnectionTest(config, onLog) }
            _mcpTestStates.value = _mcpTestStates.value + (config.id to result)
        }
    }

    private suspend fun runMcpConnectionTest(config: McpServerConfig, onLog: (String) -> Unit): ConnectionTestState {
        val key = connectionTestKey(
            config.url, config.authMethod, mcpTestTokenFor(config.authMethod, config.token, config.oauth2), config.transport,
        )
        if (config.url.isBlank()) return ConnectionTestState.Error(key, "Bitte eine Adresse eingeben")
        if (config.authMethod == AuthMethod.OAUTH2 && !config.oauth2.isLoggedIn) {
            return ConnectionTestState.Error(key, "Bitte zuerst über \"Login\" anmelden")
        }
        val header = config.resolveAuthHeader()
        val testClient = config.transport.newMcpConnection()
        testClient.onLog = onLog
        onLog("Starte Verbindungstest zu \"${config.url}\" (Transport: ${config.transport.label}) …")
        return try {
            testClient.connect(config.url, header?.first, header?.second, connectTimeoutMs = 8_000).fold(
                onSuccess = {
                    onLog("Verbindung erfolgreich.")
                    ConnectionTestState.Success(key)
                },
                onFailure = { e ->
                    val message = describeConnectionError(e)
                    onLog("Fehlgeschlagen: $message")
                    ConnectionTestState.Error(key, message)
                },
            )
        } catch (e: Exception) {
            val message = describeConnectionError(e)
            onLog("Fehlgeschlagen: $message")
            ConnectionTestState.Error(key, message)
        } finally {
            testClient.close()
        }
    }

    /** Tools offered by [serverId] -- fetched on demand (expanding its accordion card) or
     * eagerly for already-connected servers, see DataSourcesScreen. */
    fun loadServerTools(serverId: String) {
        viewModelScope.launch {
            mcpServerPool.listTools(serverId).onSuccess { tools ->
                _mcpServerTools.value = _mcpServerTools.value + (serverId to tools)
            }
        }
    }

    /** "🔍 Erkunden" on an MCP server's accordion card -- item 1+2 of Discovery Modus: introspects
     * `tools/list`/`resources/list` on the already-connected server, then best-effort asks the
     * active fast LLM to profile the result (see [DiscoveryService]), and persists both. The
     * server must already be connected in [mcpServerPool] (same precondition the tool list itself
     * already has) -- a not-yet-connected server fails with a clear message rather than silently
     * doing nothing. */
    fun runDiscovery(server: McpServerConfig) {
        viewModelScope.launch {
            _discoveryUiStates.value = _discoveryUiStates.value + (server.id to DiscoveryUiState.Running)
            discoveryService.runMcpDiscovery(server, settings.value).fold(
                onSuccess = { record ->
                    _discoveryRecords.value = _discoveryRecords.value + (server.id to record)
                    _discoveryUiStates.value = _discoveryUiStates.value + (server.id to DiscoveryUiState.Success(record))
                },
                onFailure = { e ->
                    _discoveryUiStates.value = _discoveryUiStates.value +
                        (server.id to DiscoveryUiState.Error(describeConnectionError(e)))
                },
            )
        }
    }

    /** Same as [runDiscovery], for the direct Nightscout REST API (`/api/v1/status.json`
     * introspection instead of an MCP `tools/list` call -- see
     * [DiscoveryService.runNightscoutDiscovery]). */
    fun runNightscoutDiscovery(config: McpServerConfig) {
        viewModelScope.launch {
            _discoveryUiStates.value = _discoveryUiStates.value + (config.id to DiscoveryUiState.Running)
            discoveryService.runNightscoutDiscovery(config, settings.value).fold(
                onSuccess = { record ->
                    _discoveryRecords.value = _discoveryRecords.value + (config.id to record)
                    _discoveryUiStates.value = _discoveryUiStates.value + (config.id to DiscoveryUiState.Success(record))
                },
                onFailure = { e ->
                    _discoveryUiStates.value = _discoveryUiStates.value +
                        (config.id to DiscoveryUiState.Error(describeConnectionError(e)))
                },
            )
        }
    }

    /**
     * Kicks off the OAuth2 Authorization Code + PKCE flow for [config] in a Chrome Custom Tab --
     * the same "get redirected, authenticate, done" experience Claude's own MCP connector gives
     * for OAuth2-protected servers. If [config]'s [McpServerConfig.oauth2] doesn't already have
     * an authorization endpoint configured (the common case -- nobody should have to hand-copy
     * OAuth2 endpoints and a client ID first), this auto-discovers them via
     * [discoverOAuth2Metadata] (RFC 8414 metadata + RFC 7591 dynamic client registration) before
     * proceeding. Manually-filled endpoint fields are respected as-is and skip discovery
     * entirely, for servers that don't support it.
     *
     * [config] is the card's *full currently-edited* form state (name/url/authMethod/category/
     * transport/...), not just the url+oauth2 sub-object -- it's persisted here immediately,
     * before discovery/login even starts. This fixes two related bugs: (1) a still-unsaved edit
     * (most commonly having just picked "OAuth2" as the Auth-Typ, but not yet pressed
     * "Speichern") used to get silently discarded the moment login-triggered saves overwrote the
     * server with a stale, still-`AuthMethod.NONE` copy -- visually indistinguishable from "the
     * app reset my Auth-Typ back to Keine Auth"; and (2) it lets a brand-new server be captured
     * (and reachable again on process death, e.g. after being sent to the OAuth2 provider's own
     * login page) *before* any connection test has ever succeeded, which a plain "Speichern"
     * still can't do for other auth types -- see [DataSourcesScreen]'s "Speichern" gating.
     */
    fun startOAuth2Login(context: Context, config: McpServerConfig) {
        viewModelScope.launch {
            val baseline = if (settings.value.mcpServers.any { it.id == config.id }) {
                settings.value.mcpServers.map { if (it.id == config.id) config else it }
            } else {
                settings.value.mcpServers + config
            }
            saveMcpServers(baseline)

            val serverId = config.id
            val serverUrl = config.url
            var effectiveOauth2 = config.oauth2
            if (effectiveOauth2.authorizationEndpoint.isBlank()) {
                if (serverUrl.isBlank()) {
                    _oauth2LoginStates.value = _oauth2LoginStates.value +
                        (serverId to OAuth2LoginState.Error("Bitte zuerst die Server-URL eintragen"))
                    return@launch
                }
                _oauth2LoginStates.value = _oauth2LoginStates.value + (serverId to OAuth2LoginState.Discovering)
                appendOAuth2Log(serverId, "Starte OAuth2-Discovery für \"$serverUrl\" …")
                val discovered = discoverOAuth2Metadata(serverUrl).getOrElse { e ->
                    appendOAuth2Log(serverId, "Discovery fehlgeschlagen: ${e.message}")
                    _oauth2LoginStates.value = _oauth2LoginStates.value +
                        (serverId to OAuth2LoginState.Error(e.message ?: "OAuth2-Discovery fehlgeschlagen"))
                    return@launch
                }
                appendOAuth2Log(
                    serverId,
                    "Discovery erfolgreich: authorization_endpoint=${discovered.authorizationEndpoint}, " +
                        "token_endpoint=${discovered.tokenEndpoint}, " +
                        "client_id=${discovered.clientId.ifBlank { "(keine -- dynamische Registrierung fehlgeschlagen)" }}",
                )
                effectiveOauth2 = discovered
                // Persist the discovered endpoints right away (even before login finishes) so a
                // retry doesn't re-discover from scratch.
                saveMcpServers(
                    settings.value.mcpServers.map {
                        if (it.id == serverId) it.copy(oauth2 = discovered) else it
                    },
                )
            }
            if (effectiveOauth2.authorizationEndpoint.isBlank() || effectiveOauth2.clientId.isBlank()) {
                _oauth2LoginStates.value = _oauth2LoginStates.value +
                    (serverId to OAuth2LoginState.Error("Autorisierungs-Endpoint und Client-ID erforderlich"))
                return@launch
            }

            val pkce = generatePkcePair()
            val state = UUID.randomUUID().toString()
            val authorizationUrl = buildAuthorizationUrl(effectiveOauth2, state, pkce.challenge)
            try {
                // No browser/Custom Tabs host installed (seen on a bare emulator image) throws
                // ActivityNotFoundException synchronously from launchUrl -- surface it the same
                // way as any other login failure instead of letting it crash the whole app.
                CustomTabsIntent.Builder().build().launchUrl(context, authorizationUrl)
            } catch (e: ActivityNotFoundException) {
                _oauth2LoginStates.value = _oauth2LoginStates.value +
                    (serverId to OAuth2LoginState.Error("Kein Browser für die Anmeldung gefunden"))
                return@launch
            }
            PendingOAuth2Flow.start(PendingOAuth2Flow.Pending(serverId, pkce.verifier, state, effectiveOauth2))
            _oauth2LoginStates.value = _oauth2LoginStates.value + (serverId to OAuth2LoginState.LoggingIn)
        }
    }

    /** Appends one line to [serverId]'s connection-test log -- reused (not a separate log/UI) for
     * OAuth2 discovery/login/token-exchange feedback so "Verbindungs-Log anzeigen" in
     * DataSourcesScreen shows the exact server response for OAuth2 the same way it already does
     * for a plain "Verbindung testen". */
    private fun appendOAuth2Log(serverId: String, line: String) {
        _mcpTestLogs.value = _mcpTestLogs.value + (serverId to (_mcpTestLogs.value[serverId].orEmpty() + line))
    }

    /** Finishes an in-flight [startOAuth2Login] once MainActivity forwards the redirect here --
     * exchanges the authorization code for tokens and saves them onto the matching server (which
     * also reconnects the real MCP pool with the fresh token, via [saveMcpServers]). A no-op if
     * no login is actually pending (e.g. a stray/replayed deep link). */
    private fun handleOAuth2Callback(uri: Uri) {
        val pending = PendingOAuth2Flow.consume() ?: return
        val providerError = uri.getQueryParameter("error")
        if (providerError != null) {
            _oauth2LoginStates.value = _oauth2LoginStates.value + (pending.serverId to OAuth2LoginState.Error(providerError))
            return
        }
        val code = uri.getQueryParameter("code")
        val returnedState = uri.getQueryParameter("state")
        if (code == null || returnedState != pending.state) {
            _oauth2LoginStates.value = _oauth2LoginStates.value +
                (pending.serverId to OAuth2LoginState.Error("Ungültige Antwort vom Provider"))
            return
        }
        viewModelScope.launch {
            if (settings.value.mcpServers.none { it.id == pending.serverId }) {
                _oauth2LoginStates.value = _oauth2LoginStates.value +
                    (pending.serverId to OAuth2LoginState.Error("Server wurde inzwischen entfernt"))
                return@launch
            }
            appendOAuth2Log(pending.serverId, "Autorisierungscode erhalten, tausche gegen Access Token …")
            exchangeOAuth2Code(pending.oauth2, code, pending.verifier).fold(
                onSuccess = { updatedOAuth2 ->
                    appendOAuth2Log(pending.serverId, "Token-Austausch erfolgreich -- Access Token erhalten.")
                    // Only the fresh tokens are overwritten here -- every other field (name, url,
                    // authMethod, category, ...) was already correctly captured by
                    // startOAuth2Login's own save right when the login started, so this can't
                    // regress them back to their pre-edit state.
                    val updatedServers = settings.value.mcpServers.map {
                        if (it.id == pending.serverId) it.copy(oauth2 = updatedOAuth2) else it
                    }
                    saveMcpServers(updatedServers)
                    _oauth2LoginStates.value = _oauth2LoginStates.value + (pending.serverId to OAuth2LoginState.LoggedIn)
                },
                onFailure = { e ->
                    // The raw exception message (for an HttpStatusException, the server's actual
                    // response body) rather than the friendlier describeConnectionError() text --
                    // "zeige die exakte Antwort des MCP-Servers".
                    appendOAuth2Log(pending.serverId, "Token-Austausch fehlgeschlagen: ${e.message ?: e::class.simpleName}")
                    _oauth2LoginStates.value = _oauth2LoginStates.value +
                        (pending.serverId to OAuth2LoginState.Error(describeConnectionError(e)))
                },
            )
        }
    }

    /** Persists the full (<=5) server list and (re)connects the app's real pool to match it. */
    fun saveMcpServers(servers: List<McpServerConfig>) {
        viewModelScope.launch {
            settingsRepository.saveMcpServers(servers)
            _mcpConnectionStates.value = servers.associate { it.id to McpConnectionState.Connecting }
            val results = mcpServerPool.sync(servers)
            _mcpConnectionStates.value = servers.associate { config ->
                val result = results[config.id]
                val state = when {
                    result == null -> McpConnectionState.Idle
                    result.isSuccess -> McpConnectionState.Connected
                    else -> McpConnectionState.Error(describeConnectionError(result.exceptionOrNull() ?: Exception()))
                }
                config.id to state
            }
        }
    }

    fun removeMcpServer(serverId: String) {
        viewModelScope.launch {
            mcpServerPool.remove(serverId)
            val updated = settings.value.mcpServers.filterNot { it.id == serverId }
            settingsRepository.saveMcpServers(updated)
            settingsRepository.removeDiscoveryRecord(serverId)
            _mcpConnectionStates.value = _mcpConnectionStates.value - serverId
            _mcpTestStates.value = _mcpTestStates.value - serverId
            _mcpTestLogs.value = _mcpTestLogs.value - serverId
            _mcpServerTools.value = _mcpServerTools.value - serverId
            _oauth2LoginStates.value = _oauth2LoginStates.value - serverId
            _discoveryRecords.value = _discoveryRecords.value - serverId
            _discoveryUiStates.value = _discoveryUiStates.value - serverId
        }
    }

    /**
     * Runs a throwaway REST call against the Nightscout instance: unauthenticated
     * `/api/v1/status.json` confirms the host is actually a reachable Nightscout server, and
     * -- if a secret/token was entered -- an authenticated `/api/v1/entries.json` call with
     * the chosen [authMethod]'s header confirms it's accepted.
     */
    fun testNightscoutApi(url: String, authMethod: AuthMethod, secret: String) {
        viewModelScope.launch {
            _nightscoutApiTestState.value = ConnectionTestState.Testing
            _nightscoutApiTestState.value = withContext(Dispatchers.IO) { runNightscoutApiTest(url, authMethod, secret) }
        }
    }

    private suspend fun runNightscoutApiTest(url: String, authMethod: AuthMethod, secret: String): ConnectionTestState {
        val key = connectionTestKey(url, authMethod, secret)
        if (url.isBlank()) return ConnectionTestState.Error(key, "Bitte eine Adresse eingeben")
        val header = authMethod.buildHeader(secret)
        val client = HttpClient(OkHttp) {
            install(HttpTimeout) { requestTimeoutMillis = 8_000 }
        }
        return try {
            val statusResponse = client.get { nightscoutUrl(url, "/api/v1/status.json") }
            if (!statusResponse.status.isSuccess()) {
                return ConnectionTestState.Error(key, describeHttpStatus(statusResponse.status.value))
            }
            // Always probes the actual entries endpoint -- not just when a manually-entered
            // token/secret exists -- since a Nightscout share link with the token embedded
            // directly in the URL (e.g. "https://host/?token=xyz", Auth-Typ "Keine" here) is a
            // common, fully valid setup where [header] alone would otherwise be null and this
            // whole check would silently skip the endpoint the real per-window data fetch
            // (fetchNightscoutDirectEntries) actually depends on -- "Verbindung erfolgreich"
            // must mean the entries endpoint really works, not just that the root page loaded.
            val entriesResponse = client.get {
                nightscoutUrl(url, "/api/v1/entries.json")
                parameter("count", 1)
                if (header != null) header(header.first, header.second)
            }
            if (!entriesResponse.status.isSuccess()) {
                return ConnectionTestState.Error(key, describeHttpStatus(entriesResponse.status.value))
            }
            ConnectionTestState.Success(key)
        } catch (e: Exception) {
            ConnectionTestState.Error(key, describeConnectionError(e))
        } finally {
            client.close()
        }
    }

    fun saveNightscoutApi(url: String, authMethod: AuthMethod, secret: String, enabled: Boolean, name: String = "") {
        viewModelScope.launch { settingsRepository.saveNightscoutApi(url, secret, authMethod, enabled, name) }
    }

    fun saveSystemPrompt(prompt: String) {
        viewModelScope.launch { settingsRepository.saveSystemPrompt(prompt) }
    }

    /** Item 2's "Zusätzliche Instruktionen" -- editable regardless of [com.example.diabai.data.LicenseTier]. */
    fun saveAdditionalInstructions(text: String) {
        viewModelScope.launch { settingsRepository.saveAdditionalInstructions(text) }
    }

    /** "Über GlucoSphere"'s Lizenzschlüssel field (item 1). */
    fun saveLicenseKey(key: String) {
        viewModelScope.launch { settingsRepository.saveLicenseKey(key) }
    }

    /** Switches straight to the local model -- no credentials to test/gate. */
    fun selectLocalProvider() {
        viewModelScope.launch { settingsRepository.saveLlmProviderType(LlmProviderType.LOCAL) }
    }

    /** Just switches the radio selection, same as [selectLocalProvider] -- whether an own key is
     * required to actually save/use it is [LlmConfigScreen]'s `ProviderKeyForm(keyOptional = true)`
     * concern, not this call's; tapping the radio alone is enough to fall back to the app-embedded
     * free-tier key with no further action. */
    fun selectOneProviderFree() {
        viewModelScope.launch { settingsRepository.saveLlmProviderType(LlmProviderType.ONEPROVIDER_FREE) }
    }

    /** Throwaway key/endpoint check for the "Key testen" button -- doesn't touch the app's
     * real active provider until [saveLlmProvider] is called. */
    fun testLlmProviderKey(type: LlmProviderType, apiKey: String, baseUrl: String) {
        viewModelScope.launch {
            _llmProviderTestState.value = ConnectionTestState.Testing
            val key = llmProviderTestKey(type, apiKey, baseUrl)
            _llmProviderTestState.value = withContext(Dispatchers.IO) {
                providerManager.testKey(type, apiKey, baseUrl).fold(
                    onSuccess = { ConnectionTestState.Success(key) },
                    onFailure = { e -> ConnectionTestState.Error(key, describeConnectionError(e)) },
                )
            }
        }
    }

    /** Persists the cloud provider's credentials and switches the app to it in one step --
     * gated in the UI behind a matching successful [testLlmProviderKey] run. */
    fun saveLlmProvider(type: LlmProviderType, apiKey: String, baseUrl: String) {
        viewModelScope.launch { settingsRepository.saveLlmProviderConfig(type, apiKey, baseUrl) }
    }

    /** Independent of [saveLlmProvider] -- switching "Automatisch" vs. a pinned model shouldn't
     * require re-entering/re-testing the API key. */
    fun saveLlmModel(type: LlmProviderType, modelId: String) {
        viewModelScope.launch { settingsRepository.saveLlmModel(type, modelId) }
    }

    /** "Testen" for "Benutzerdefiniertes Modell eingeben" -- see
     * [com.example.diabai.domain.llm.LLMProviderManager.testCustomModel]. Gates the chip that
     * actually selects the custom id in LlmConfigScreen, the same "test, then use" pattern as
     * every other connection check in this screen. */
    fun testCustomModel(type: LlmProviderType, apiKey: String, baseUrl: String, modelId: String) {
        viewModelScope.launch {
            _customModelTestState.value = ConnectionTestState.Testing
            val key = customModelTestKey(type, apiKey, baseUrl, modelId)
            _customModelTestState.value = withContext(Dispatchers.IO) {
                providerManager.testCustomModel(type, apiKey, baseUrl, modelId).fold(
                    onSuccess = { ConnectionTestState.Success(key) },
                    onFailure = { e -> ConnectionTestState.Error(key, describeConnectionError(e)) },
                )
            }
        }
    }

    /** "Verbrauchsstatistik zurücksetzen" in Über GlucoSphere. */
    fun resetLlmUsage() {
        viewModelScope.launch { settingsRepository.resetLlmUsage() }
    }

    // --- Backup & Konfiguration (item 2): verschlüsselte Export-/Importfunktion ---

    private val _backupExportError = MutableStateFlow<String?>(null)
    val backupExportError: StateFlow<String?> = _backupExportError.asStateFlow()

    private val _backupImportState = MutableStateFlow<BackupImportState>(BackupImportState.Idle)
    val backupImportState: StateFlow<BackupImportState> = _backupImportState.asStateFlow()

    /** Set by [importSettings] whenever [SettingsBackupFile.read] reports
     * [SettingsBackupFile.ReadResult.PasswordRequired] -- the file's raw bytes, held here so
     * [importSettingsWithPassword] doesn't need to re-read the source URI a second time (its
     * `ActivityResultContracts.OpenDocument` grant may not even still be valid by then). Cleared
     * on [consumeBackupImportState] or a successful import. */
    private var pendingImportBytes: ByteArray? = null

    /** "Einstellungen exportieren" (item 2): writes the current [AppSettings] -- reduced to
     * exactly [com.example.diabai.data.SettingsBackup]'s fields -- to [uri] (handed back by
     * `ActivityResultContracts.CreateDocument`, see [BackupScreen]), optionally AES-256-GCM-
     * encrypted under [password] (null/blank = plain JSON) via [SettingsBackupFile.write]. */
    fun exportSettings(context: Context, uri: Uri, password: String?) {
        viewModelScope.launch {
            _backupExportError.value = null
            try {
                val json = settings.value.toBackup().encodeToJson()
                val bytes = SettingsBackupFile.write(json, password?.takeIf { it.isNotBlank() })
                context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                    ?: error("Datei konnte nicht zum Schreiben geöffnet werden")
            } catch (e: Exception) {
                _backupExportError.value = e.message ?: "Export fehlgeschlagen"
            }
        }
    }

    /** "Einstellungen importieren", step 1: reads [uri]'s bytes and attempts them without a
     * password first (most imports are unencrypted, and this is also how an encrypted file is
     * first *recognized* as such -- see [SettingsBackupFile.read]'s header check, which runs
     * before any decryption is attempted). */
    fun importSettings(context: Context, uri: Uri) {
        viewModelScope.launch {
            _backupImportState.value = BackupImportState.Importing
            val bytes = try {
                context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: error("Datei konnte nicht gelesen werden")
            } catch (e: Exception) {
                _backupImportState.value = BackupImportState.Error(e.message ?: "Datei konnte nicht gelesen werden")
                return@launch
            }
            applyImportResult(SettingsBackupFile.read(bytes, password = null), bytes)
        }
    }

    /** Step 2, only reached after [importSettings] reported [BackupImportState.PasswordRequired]
     * -- retries the already-read [pendingImportBytes] with the password the user just entered in
     * [BackupScreen]'s dialog. */
    fun importSettingsWithPassword(password: String) {
        val bytes = pendingImportBytes ?: return
        viewModelScope.launch {
            _backupImportState.value = BackupImportState.Importing
            applyImportResult(SettingsBackupFile.read(bytes, password), bytes)
        }
    }

    private suspend fun applyImportResult(result: SettingsBackupFile.ReadResult, rawBytes: ByteArray) {
        when (result) {
            is SettingsBackupFile.ReadResult.Plain -> {
                pendingImportBytes = null
                val backup = try {
                    result.json.decodeSettingsBackup()
                } catch (e: Exception) {
                    _backupImportState.value = BackupImportState.Error("Ungültiges Backup-Format: ${e.message}")
                    return
                }
                settingsRepository.restoreFromBackup(backup)
                // Mirrors saveMcpServers' own "persist, then reconnect the real pool" step -- an
                // imported server list would otherwise sit there configured but never actually
                // connected until some unrelated future save happened to trigger a sync.
                mcpServerPool.sync(backup.mcpServers)
                _backupImportState.value = BackupImportState.Success
            }
            SettingsBackupFile.ReadResult.PasswordRequired -> {
                pendingImportBytes = rawBytes
                _backupImportState.value = BackupImportState.PasswordRequired
            }
            SettingsBackupFile.ReadResult.WrongPassword -> _backupImportState.value = BackupImportState.WrongPassword
            is SettingsBackupFile.ReadResult.InvalidFile -> _backupImportState.value = BackupImportState.Error(result.message)
        }
    }

    /** Dismisses the password dialog / clears a terminal (success or error) import state once the
     * user has seen it -- also drops [pendingImportBytes], so a cancelled password prompt can't be
     * silently resumed by some later, unrelated call to [importSettingsWithPassword]. */
    fun consumeBackupImportState() {
        _backupImportState.value = BackupImportState.Idle
        pendingImportBytes = null
    }

    // --- Performance-Log ---

    private val _performanceLog = MutableStateFlow<List<LlmRequestLogEntry>>(emptyList())
    val performanceLog: StateFlow<List<LlmRequestLogEntry>> = _performanceLog.asStateFlow()

    /** Loaded on demand (see [PerformanceLogScreen]'s `LaunchedEffect`) rather than eagerly at
     * ViewModel construction -- same one-shot-suspend-read reasoning as
     * [SettingsRepository.loadLlmRequestLog] itself; this screen isn't opened every session. */
    fun loadPerformanceLog() {
        viewModelScope.launch { _performanceLog.value = settingsRepository.loadLlmRequestLog() }
    }

    fun clearPerformanceLog() {
        viewModelScope.launch {
            settingsRepository.clearLlmRequestLog()
            _performanceLog.value = emptyList()
        }
    }

    // --- Debug-Log (Entwickler) ---

    private val _debugLog = MutableStateFlow<List<com.example.diabai.data.DebugLogEntry>>(emptyList())
    val debugLog: StateFlow<List<com.example.diabai.data.DebugLogEntry>> = _debugLog.asStateFlow()

    fun saveDebugLoggingEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.saveDebugLoggingEnabled(enabled) }
    }

    /** Same on-demand load as [loadPerformanceLog] -- see [PerformanceLogScreen]'s `LaunchedEffect`. */
    fun loadDebugLog() {
        viewModelScope.launch { _debugLog.value = settingsRepository.loadDebugLog() }
    }

    fun clearDebugLog() {
        viewModelScope.launch {
            settingsRepository.clearDebugLog()
            _debugLog.value = emptyList()
        }
    }

    private fun describeConnectionError(e: Throwable): String = when (e) {
        is SSEClientException -> {
            val code = e.response?.status?.value
            if (code != null) describeHttpStatus(code) else e.message ?: "Verbindung fehlgeschlagen"
        }
        is HttpStatusException -> describeHttpStatus(e.statusCode)
        is McpException -> "MCP-Fehler ${e.code}: ${e.message}" + describeMcpErrorCode(e.code)
        is UnknownHostException -> "Host nicht gefunden – Adresse prüfen"
        is ConnectException -> "Verbindung abgelehnt – ist der Server erreichbar?"
        is HttpRequestTimeoutException, is TimeoutCancellationException -> "Zeitüberschreitung – Server antwortet nicht"
        is IllegalArgumentException -> "Ungültige Adresse"
        else -> e.message ?: e::class.simpleName ?: "Unbekannter Fehler"
    }

    /** Short parenthetical hint for the standard JSON-RPC 2.0 error codes MCP inherits, so
     * e.g. "-32700" isn't just a bare number the user has to look up themselves. */
    private fun describeMcpErrorCode(code: Int): String = when (code) {
        -32700 -> " (Parse error – der Server konnte die gesendete Anfrage nicht als JSON lesen)"
        -32600 -> " (Invalid Request – die Anfrage entspricht nicht dem erwarteten Format)"
        -32601 -> " (Method not found)"
        -32602 -> " (Invalid params)"
        -32603 -> " (Internal error auf dem Server)"
        else -> ""
    }

    private fun describeHttpStatus(code: Int): String = when (code) {
        401 -> "Fehler 401: Token/Secret ungültig"
        403 -> "Fehler 403: Zugriff verweigert"
        404 -> "Fehler 404: Adresse/Pfad nicht gefunden"
        in 500..599 -> "Fehler $code: Server-seitiges Problem"
        else -> "Fehler $code"
    }
}

class SettingsViewModelFactory(
    private val settingsRepository: SettingsRepository,
    private val modelFileManager: ModelFileManager,
    private val llmEngine: LiteRtInferenceEngine,
    private val mcpServerPool: McpServerPool,
    private val providerManager: LLMProviderManager,
    private val discoveryService: DiscoveryService,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return SettingsViewModel(settingsRepository, modelFileManager, llmEngine, mcpServerPool, providerManager, discoveryService) as T
    }
}
