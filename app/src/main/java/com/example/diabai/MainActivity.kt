package com.example.diabai

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.diabai.data.ModelFileManager
import com.example.diabai.data.SettingsRepository
import com.example.diabai.domain.DiabetesAgent
import com.example.diabai.domain.LiteRtInferenceEngine
import com.example.diabai.domain.OAuth2CallbackBus
import com.example.diabai.domain.analytics.DiabetesDashboardManager
import com.example.diabai.domain.discovery.DiscoveryService
import com.example.diabai.domain.llm.LLMProviderManager
import com.example.diabai.network.McpServerPool
import com.example.diabai.ui.AboutScreen
import com.example.diabai.ui.BackupScreen
import com.example.diabai.ui.DataSourcesScreen
import com.example.diabai.ui.GlucoSphereViewModel
import com.example.diabai.ui.GlucoSphereViewModelFactory
import com.example.diabai.ui.HelpScreen
import com.example.diabai.ui.LlmConfigScreen
import com.example.diabai.ui.LocalStrings
import com.example.diabai.ui.MainTabsScreen
import com.example.diabai.ui.PerformanceLogScreen
import com.example.diabai.ui.ProfileScreen
import com.example.diabai.ui.SettingsOverviewScreen
import com.example.diabai.ui.SettingsViewModel
import com.example.diabai.ui.SettingsViewModelFactory
import com.example.diabai.ui.SystemPromptScreen
import com.example.diabai.ui.stringsFor
import com.example.diabai.ui.theme.GlucoSphereTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : ComponentActivity() {

    private val mcpServerPool by lazy { McpServerPool() }
    private val llmEngine by lazy { LiteRtInferenceEngine(this) }
    private val providerManager by lazy { LLMProviderManager(llmEngine) }
    private val settingsRepository by lazy { SettingsRepository(this) }
    private val agent by lazy { DiabetesAgent(providerManager, mcpServerPool, settingsRepository) }
    private val dashboardManager by lazy { DiabetesDashboardManager(agent, settingsRepository, mcpServerPool) }
    private val modelFileManager by lazy { ModelFileManager(this) }
    private val discoveryService by lazy { DiscoveryService(mcpServerPool, providerManager, settingsRepository) }

    /** Lizenzmodell (item 1) -- "aktive Nutzungszeit": this app has exactly one Activity, so its
     * own onResume/onPause already IS whole-app foreground/background for practical purposes
     * here, no need for a separate ProcessLifecycleOwner dependency just for this. Null whenever
     * not currently resumed (including right after a flush in [onPause]), so [onPause] can't
     * double-count if it somehow ran twice without an intervening [onResume]. */
    private var resumedAtMillis: Long? = null

    override fun onResume() {
        super.onResume()
        resumedAtMillis = System.currentTimeMillis()
    }

    override fun onPause() {
        super.onPause()
        val start = resumedAtMillis ?: return
        resumedAtMillis = null
        val elapsedMillis = System.currentTimeMillis() - start
        // lifecycleScope, not viewModelScope -- there's no ViewModel here, and this specific
        // write should survive independently of whatever screen happens to be showing.
        lifecycleScope.launch { settingsRepository.addActiveUsageMillis(elapsedMillis) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleOAuth2Intent(intent)

        // Apply whatever was saved last session, once, at startup -- explicit "Speichern" in
        // the settings screen is what applies *changes* live (see SettingsViewModel).
        lifecycleScope.launch {
            val saved = settingsRepository.settings.first()
            if (saved.mcpServers.isNotEmpty()) {
                mcpServerPool.sync(saved.mcpServers)
            }
            if (saved.modelFilePath.isNotBlank()) {
                val file = File(saved.modelFilePath)
                if (file.exists()) llmEngine.loadModel(file)
            }
        }

        setContent {
            GlucoSphereTheme {
                GlucoSphereApp(
                    dashboardManager = dashboardManager,
                    agent = agent,
                    settingsRepository = settingsRepository,
                    modelFileManager = modelFileManager,
                    llmEngine = llmEngine,
                    mcpServerPool = mcpServerPool,
                    providerManager = providerManager,
                    discoveryService = discoveryService,
                )
            }
        }
    }

    /** `singleTask` (see AndroidManifest) means the OAuth2 redirect (`diabai://oauth-callback`)
     * reuses this same running instance instead of creating a new one -- it arrives here, not in
     * a fresh [onCreate]. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleOAuth2Intent(intent)
    }

    private fun handleOAuth2Intent(intent: Intent?) {
        val uri = intent?.data ?: return
        if (uri.scheme == "diabai" && uri.host == "oauth-callback") {
            OAuth2CallbackBus.emit(uri)
        }
    }
}

private enum class AppRoute {
    MAIN, SETTINGS_OVERVIEW, SETTINGS_PROFILE, SETTINGS_LLM, SETTINGS_DATA_SOURCES, SETTINGS_SYSTEM_PROMPT,
    SETTINGS_ABOUT, SETTINGS_BACKUP, SETTINGS_PERFORMANCE_LOG, SETTINGS_HELP,
}

@Composable
fun GlucoSphereApp(
    dashboardManager: DiabetesDashboardManager,
    agent: DiabetesAgent,
    settingsRepository: SettingsRepository,
    modelFileManager: ModelFileManager,
    llmEngine: LiteRtInferenceEngine,
    mcpServerPool: McpServerPool,
    providerManager: LLMProviderManager,
    discoveryService: DiscoveryService,
) {
    var route by rememberSaveable { mutableStateOf(AppRoute.MAIN) }
    BackHandler(enabled = route != AppRoute.MAIN) {
        route = when (route) {
            AppRoute.SETTINGS_OVERVIEW -> AppRoute.MAIN
            AppRoute.SETTINGS_PROFILE, AppRoute.SETTINGS_LLM, AppRoute.SETTINGS_DATA_SOURCES, AppRoute.SETTINGS_SYSTEM_PROMPT,
            AppRoute.SETTINGS_ABOUT, AppRoute.SETTINGS_BACKUP, AppRoute.SETTINGS_PERFORMANCE_LOG, AppRoute.SETTINGS_HELP,
            -> AppRoute.SETTINGS_OVERVIEW
            AppRoute.MAIN -> AppRoute.MAIN
        }
    }

    // One shared instance for the whole settings area -- every sub-screen reads/writes the
    // same underlying SettingsRepository state, so a single ViewModel avoids duplicating
    // that StateFlow plumbing four times over.
    val settingsViewModel: SettingsViewModel = viewModel(
        factory = SettingsViewModelFactory(settingsRepository, modelFileManager, llmEngine, mcpServerPool, providerManager, discoveryService),
    )

    // Hoisted above the `when (route)` below (rather than created inside AppRoute.MAIN only) so
    // Discovery Modus's "💡 Was du fragen kannst" chips -- reachable from AppRoute.SETTINGS_DATA_SOURCES,
    // a *different* route -- can still reach the same instance to prefill the chat input and have
    // MainTabsScreen (only actually composed for AppRoute.MAIN) pick it up once the user is routed
    // back there. Backed by the Activity's own ViewModelStore either way, so this doesn't change
    // when the instance itself is created/destroyed, only when the `viewModel()` call site first runs.
    val diabAiViewModel: GlucoSphereViewModel = viewModel(
        factory = GlucoSphereViewModelFactory(dashboardManager, agent, settingsRepository, mcpServerPool),
    )

    // Per-app language: a plain Compose CompositionLocal, not Android's system LocaleManager --
    // see Strings.kt's doc comment for why (LocaleManager.applicationLocales triggered an Activity
    // recreation that was the actual cause of the "switch language -> crashes later" bug). STT/TTS
    // still read AppSettings.appLanguage directly for their own locale tag, unaffected by this.
    val settings by settingsViewModel.settings.collectAsState()

    CompositionLocalProvider(LocalStrings provides stringsFor(settings.appLanguage)) {
        when (route) {
            AppRoute.MAIN ->
                MainTabsScreen(viewModel = diabAiViewModel, onOpenSettings = { route = AppRoute.SETTINGS_OVERVIEW })
            AppRoute.SETTINGS_OVERVIEW -> SettingsOverviewScreen(
                viewModel = settingsViewModel,
                onBack = { route = AppRoute.MAIN },
                onOpenProfile = { route = AppRoute.SETTINGS_PROFILE },
                onOpenLlmConfig = { route = AppRoute.SETTINGS_LLM },
                onOpenDataSources = { route = AppRoute.SETTINGS_DATA_SOURCES },
                onOpenSystemPrompt = { route = AppRoute.SETTINGS_SYSTEM_PROMPT },
                onOpenAbout = { route = AppRoute.SETTINGS_ABOUT },
                onOpenBackup = { route = AppRoute.SETTINGS_BACKUP },
                onOpenPerformanceLog = { route = AppRoute.SETTINGS_PERFORMANCE_LOG },
                onOpenHelp = { route = AppRoute.SETTINGS_HELP },
            )
            AppRoute.SETTINGS_PROFILE -> ProfileScreen(
                viewModel = settingsViewModel,
                onBack = { route = AppRoute.SETTINGS_OVERVIEW },
            )
            AppRoute.SETTINGS_LLM -> LlmConfigScreen(
                viewModel = settingsViewModel,
                onBack = { route = AppRoute.SETTINGS_OVERVIEW },
            )
            AppRoute.SETTINGS_DATA_SOURCES -> DataSourcesScreen(
                viewModel = settingsViewModel,
                onBack = { route = AppRoute.SETTINGS_OVERVIEW },
                onAskInChat = { question ->
                    diabAiViewModel.prefillChatInput(question)
                    route = AppRoute.MAIN
                },
            )
            AppRoute.SETTINGS_SYSTEM_PROMPT -> SystemPromptScreen(
                viewModel = settingsViewModel,
                onBack = { route = AppRoute.SETTINGS_OVERVIEW },
            )
            AppRoute.SETTINGS_ABOUT -> AboutScreen(
                viewModel = settingsViewModel,
                onBack = { route = AppRoute.SETTINGS_OVERVIEW },
            )
            AppRoute.SETTINGS_BACKUP -> BackupScreen(
                viewModel = settingsViewModel,
                onBack = { route = AppRoute.SETTINGS_OVERVIEW },
            )
            AppRoute.SETTINGS_PERFORMANCE_LOG -> PerformanceLogScreen(
                viewModel = settingsViewModel,
                onBack = { route = AppRoute.SETTINGS_OVERVIEW },
            )
            AppRoute.SETTINGS_HELP -> HelpScreen(
                onBack = { route = AppRoute.SETTINGS_OVERVIEW },
            )
        }
    }
}
