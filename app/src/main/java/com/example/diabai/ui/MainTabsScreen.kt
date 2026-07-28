package com.example.diabai.ui

import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.example.diabai.domain.analytics.DashboardState
import com.example.diabai.network.allDataSourceServers
import kotlinx.coroutines.launch

private enum class MainTab(val glyph: String) {
    OVERVIEW("▤"),
    CHAT("💬"),
}

private fun MainTab.label(strings: Strings): String = when (this) {
    MainTab.OVERVIEW -> strings.tabOverview
    MainTab.CHAT -> strings.tabChat
}

/**
 * No forced fullscreen/immersive mode: the app draws edge-to-edge (see
 * MainActivity.enableEdgeToEdge) but Scaffold + systemBarsPadding() keep content clear of
 * the status/navigation bars instead of drawing underneath them.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainTabsScreen(viewModel: GlucoSphereViewModel, onOpenSettings: () -> Unit, modifier: Modifier = Modifier) {
    val strings = LocalStrings.current
    var selectedTab by remember { mutableStateOf(MainTab.OVERVIEW) }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var showClearHistoryDialog by remember { mutableStateOf(false) }

    val chatSessions by viewModel.chatSessions.collectAsState()
    val snackbarMessage by viewModel.snackbarMessage.collectAsState()
    val settings by viewModel.settings.collectAsState()
    val dashboardState by viewModel.dashboardState.collectAsState()
    val pendingChatPrefill by viewModel.pendingChatPrefill.collectAsState()

    LaunchedEffect(snackbarMessage) {
        val message = snackbarMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        viewModel.consumeSnackbarMessage()
    }

    // Discovery-Sheet "💡 Was du fragen kannst" (item 3): a tapped example question sets
    // [GlucoSphereViewModel.pendingChatPrefill] from the Settings/Datenquellen screen, potentially
    // while a completely different tab (or the Settings area entirely) is showing -- this is what
    // actually switches back to the Chat tab; [ChatSection] itself picks the text up into its
    // input field via the same StateFlow.
    LaunchedEffect(pendingChatPrefill) {
        if (pendingChatPrefill != null) selectedTab = MainTab.CHAT
    }

    // One shared TTS engine for both tabs (see TtsController's own doc comment) -- disposed here
    // since this composable owns its whole lifecycle; stopped on every tab switch so leaving
    // either screen "stoppt sofort" instead of continuing to read in the background, and kept in
    // sync with the language setting whenever it changes.
    val context = LocalContext.current
    val ttsController = remember { TtsController(context) }
    DisposableEffect(Unit) {
        onDispose { ttsController.shutdown() }
    }
    LaunchedEffect(selectedTab) {
        ttsController.stop()
    }
    LaunchedEffect(settings.appLanguage) {
        ttsController.setLanguage(settings.appLanguage)
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                ChatHistoryDrawerContent(
                    sessions = chatSessions,
                    onNewChat = {
                        viewModel.startNewChat()
                        selectedTab = MainTab.CHAT
                        scope.launch { drawerState.close() }
                    },
                    onSelectSession = { session ->
                        viewModel.loadSession(session)
                        selectedTab = MainTab.CHAT
                        scope.launch { drawerState.close() }
                    },
                )
            }
        },
    ) {
        Scaffold(
            modifier = modifier,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            snackbarHost = {
                SnackbarHost(snackbarHostState) { data ->
                    Snackbar(snackbarData = data)
                }
            },
            topBar = {
                TopAppBar(
                    // TopAppBar's own default `windowInsets` param already includes the status
                    // bar, but it's zeroed out here and re-applied explicitly as an outer
                    // Modifier instead -- both achieve the same padding, this just avoids ever
                    // silently double-applying it if that default changes.
                    windowInsets = WindowInsets(0, 0, 0, 0),
                    modifier = Modifier.windowInsetsPadding(WindowInsets.statusBars),
                    title = {
                        Column {
                            Text("GlucoSphere")
                            Text(
                                text = settings.activeLlmLabel,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    actions = {
                        if (selectedTab == MainTab.CHAT) {
                            val chatItems by viewModel.chatItems.collectAsState()
                            val includeMcpTools by viewModel.includeMcpTools.collectAsState()
                            val chatSelectedCapabilityTags by viewModel.chatSelectedCapabilityTags.collectAsState()
                            IconButton(
                                onClick = {
                                    val shareText = buildChatShareText(
                                        items = chatItems,
                                        includeMcpTools = includeMcpTools,
                                        selectedCapabilityTags = chatSelectedCapabilityTags,
                                        strings = strings,
                                    )
                                    val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(Intent.EXTRA_TEXT, shareText)
                                    }
                                    context.startActivity(Intent.createChooser(sendIntent, strings.shareChatContentDescription))
                                },
                                enabled = chatItems.isNotEmpty(),
                            ) {
                                Icon(Icons.Default.Share, contentDescription = strings.shareChatContentDescription)
                            }
                            IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                Text("🕒")
                            }
                            IconButton(onClick = { showClearHistoryDialog = true }) {
                                Icon(Icons.Default.Delete, contentDescription = strings.deleteHistoryContentDescription)
                            }
                        }
                        if (selectedTab == MainTab.OVERVIEW) {
                            val loadedDashboard = (dashboardState as? DashboardState.Loaded)?.dashboard
                            IconButton(
                                onClick = {
                                    val dashboard = loadedDashboard ?: return@IconButton
                                    val shareText = buildDashboardShareText(dashboard, strings, settings.appLanguage)
                                    val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(Intent.EXTRA_TEXT, shareText)
                                    }
                                    context.startActivity(Intent.createChooser(sendIntent, strings.shareAction))
                                },
                                enabled = loadedDashboard != null,
                            ) {
                                Icon(Icons.Default.Share, contentDescription = strings.shareContentDescription)
                            }
                        }
                        IconButton(onClick = onOpenSettings) {
                            Icon(Icons.Default.Settings, contentDescription = strings.settingsContentDescription)
                        }
                    },
                )
            },
            bottomBar = {
                NavigationBar {
                    MainTab.entries.forEach { tab ->
                        NavigationBarItem(
                            selected = selectedTab == tab,
                            onClick = { selectedTab = tab },
                            icon = { Text(tab.glyph) },
                            label = { Text(tab.label(strings)) },
                        )
                    }
                }
            },
        ) { innerPadding ->
            // No additional .systemBarsPadding() here: TopAppBar and NavigationBar above/below
            // already claim the status/navigation bar insets themselves (see their own
            // modifiers/defaults), so innerPadding alone is the correct content inset --
            // stacking systemBarsPadding() on top double-applied the status bar inset and left
            // a large empty gap under the TopAppBar.
            val contentModifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()

            when (selectedTab) {
                MainTab.OVERVIEW -> OverviewScreen(viewModel = viewModel, ttsController = ttsController, modifier = contentModifier)
                MainTab.CHAT -> ChatScreen(viewModel = viewModel, ttsController = ttsController, modifier = contentModifier)
            }
        }
    }

    if (showClearHistoryDialog) {
        AlertDialog(
            onDismissRequest = { showClearHistoryDialog = false },
            title = { Text(strings.deleteHistoryTitle) },
            text = { Text(strings.deleteHistoryText) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.clearChatHistory()
                    showClearHistoryDialog = false
                }) { Text(strings.deleteHistoryConfirm) }
            },
            dismissButton = {
                TextButton(onClick = { showClearHistoryDialog = false }) { Text(strings.deleteHistoryDismiss) }
            },
        )
    }
}
