package com.example.diabai.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.diabai.data.AuthMethod
import com.example.diabai.data.MAX_MCP_SERVERS
import com.example.diabai.data.McpDataCategory
import com.example.diabai.data.McpServerConfig
import com.example.diabai.data.McpTransport
import com.example.diabai.data.OAuth2Config
import com.example.diabai.domain.discovery.DiscoveryRecord
import com.example.diabai.network.McpTool
import com.example.diabai.network.NIGHTSCOUT_DIRECT_SERVER_ID
import com.example.diabai.network.nightscoutDirectServer

/**
 * Sub-menu: configure where health data comes from. Up to [MAX_MCP_SERVERS] independent MCP
 * servers plus an optional direct Nightscout REST API, each gated behind its own successful
 * "Verbindung testen" before it can be saved. Leaving everything empty is a normal, supported
 * state ("Pure Chat Mode"): [com.example.diabai.domain.DiabetesAgent] simply skips tool
 * listing rather than erroring when no MCP server is connected.
 *
 * MCP servers are shown as collapsible accordion cards -- collapsed, each just shows name,
 * category, connection status, and tool count; expanding one reveals the full edit form (URL,
 * transport, auth incl. OAuth2 login, per-tool auto-approve toggles, and the
 * test/save/remove actions).
 */
@Composable
fun DataSourcesScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
    /** "Ein Klick darauf übernimmt die Frage direkt in den Chat-Tab und wechselt dorthin" -- the
     * Discovery-Sheet's example-question chips (item 3). Wired all the way up to `MainActivity`'s
     * `GlucoSphereViewModel`, which owns both the chat input prefill and the tab selection. */
    onAskInChat: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val strings = LocalStrings.current
    val settings by viewModel.settings.collectAsState()
    val mcpConnectionStates by viewModel.mcpConnectionStates.collectAsState()
    val mcpTestStates by viewModel.mcpTestStates.collectAsState()
    val mcpTestLogs by viewModel.mcpTestLogs.collectAsState()
    val mcpServerTools by viewModel.mcpServerTools.collectAsState()
    val oauth2LoginStates by viewModel.oauth2LoginStates.collectAsState()
    val nightscoutApiTestState by viewModel.nightscoutApiTestState.collectAsState()
    val discoveryRecords by viewModel.discoveryRecords.collectAsState()
    val discoveryUiStates by viewModel.discoveryUiStates.collectAsState()
    val context = LocalContext.current

    // Which data source's Discovery-Sheet is currently open, if any -- one shared sheet instance
    // for the whole screen rather than one per card, since only one can be visible at a time.
    var discoverySheetTarget by remember { mutableStateOf<DiscoveryTarget?>(null) }

    var nightscoutApiUrl by remember(settings.nightscoutApiUrl) { mutableStateOf(settings.nightscoutApiUrl) }
    var nightscoutApiSecret by remember(settings.nightscoutApiSecret) { mutableStateOf(settings.nightscoutApiSecret) }
    var nightscoutApiAuthMethod by remember(settings.nightscoutApiAuthMethod) { mutableStateOf(settings.nightscoutApiAuthMethod) }
    var nightscoutApiEnabled by remember(settings.nightscoutApiEnabled) { mutableStateOf(settings.nightscoutApiEnabled) }

    // Eagerly fetches tool lists for already-connected servers so the accordion's collapsed
    // header can show a real tool count without requiring the user to expand each card first.
    LaunchedEffect(settings.mcpServers, mcpConnectionStates) {
        settings.mcpServers.forEach { server ->
            if (mcpConnectionStates[server.id] == McpConnectionState.Connected) {
                viewModel.loadServerTools(server.id)
            }
        }
    }

    // "Automatischer Live-Status Check": fires once when this screen opens, pinging every
    // configured data source (MCP servers + the direct Nightscout API) in the background so the
    // 🟢/🟡/🔴 badge on each collapsed card reflects live reachability without the user having to
    // expand each one and tap "Verbindung testen" by hand first.
    LaunchedEffect(Unit) {
        settings.mcpServers.forEach { server -> if (server.url.isNotBlank()) viewModel.testMcpServer(server) }
        if (settings.nightscoutApiUrl.isNotBlank()) {
            viewModel.testNightscoutApi(settings.nightscoutApiUrl, settings.nightscoutApiAuthMethod, settings.nightscoutApiSecret)
        }
    }

    SettingsScaffold(title = strings.dataSourcesMenuTitle, onBack = onBack, modifier = modifier) {
        if (settings.isPureChatMode) {
            Text(
                text = strings.dsPureChatModeHint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Column {
            Text(strings.dsMcpServersTitle(MAX_MCP_SERVERS), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            settings.mcpServers.forEach { config ->
                McpServerAccordionCard(
                    config = config,
                    connectionState = mcpConnectionStates[config.id] ?: McpConnectionState.Idle,
                    testState = mcpTestStates[config.id] ?: ConnectionTestState.Idle,
                    log = mcpTestLogs[config.id].orEmpty(),
                    tools = mcpServerTools[config.id],
                    oauth2LoginState = oauth2LoginStates[config.id] ?: OAuth2LoginState.Idle,
                    discoveryState = discoveryUiStates[config.id] ?: DiscoveryUiState.Idle,
                    onExpand = { viewModel.loadServerTools(config.id) },
                    onTest = viewModel::testMcpServer,
                    onSave = { edited ->
                        viewModel.saveMcpServers(settings.mcpServers.map { if (it.id == edited.id) edited else it })
                    },
                    onDelete = { viewModel.removeMcpServer(config.id) },
                    onOAuth2Login = { edited -> viewModel.startOAuth2Login(context, edited) },
                    onDiscover = { edited ->
                        viewModel.runDiscovery(edited)
                        discoverySheetTarget = DiscoveryTarget(edited.id, edited.displayName)
                    },
                    onOpenDiscoverySheet = { discoverySheetTarget = DiscoveryTarget(config.id, config.displayName) },
                )
                Spacer(Modifier.height(12.dp))
            }
            if (settings.mcpServers.size < MAX_MCP_SERVERS) {
                OutlinedButton(
                    onClick = { viewModel.saveMcpServers(settings.mcpServers + McpServerConfig(enabled = false)) },
                ) {
                    Text(strings.dsAddMcpServer)
                }
            }
        }

        HorizontalDivider()

        NightscoutApiSection(
            url = nightscoutApiUrl,
            secret = nightscoutApiSecret,
            authMethod = nightscoutApiAuthMethod,
            enabled = nightscoutApiEnabled,
            configured = settings.nightscoutApiUrl.isNotBlank(),
            testState = nightscoutApiTestState,
            discoveryState = discoveryUiStates[NIGHTSCOUT_DIRECT_SERVER_ID] ?: DiscoveryUiState.Idle,
            onUrlChange = { nightscoutApiUrl = it },
            onSecretChange = { nightscoutApiSecret = it },
            onAuthMethodChange = { nightscoutApiAuthMethod = it },
            onEnabledChange = { checked ->
                nightscoutApiEnabled = checked
                viewModel.saveNightscoutApi(nightscoutApiUrl.trim(), nightscoutApiAuthMethod, nightscoutApiSecret.trim(), checked)
            },
            onTest = {
                viewModel.testNightscoutApi(nightscoutApiUrl.trim(), nightscoutApiAuthMethod, nightscoutApiSecret.trim())
            },
            onSave = {
                viewModel.saveNightscoutApi(nightscoutApiUrl.trim(), nightscoutApiAuthMethod, nightscoutApiSecret.trim(), nightscoutApiEnabled)
            },
            onDiscover = {
                val config = settings.nightscoutDirectServer() ?: return@NightscoutApiSection
                viewModel.runNightscoutDiscovery(config)
                discoverySheetTarget = DiscoveryTarget(NIGHTSCOUT_DIRECT_SERVER_ID, strings.dsNightscoutRestApiName)
            },
            onOpenDiscoverySheet = {
                discoverySheetTarget = DiscoveryTarget(NIGHTSCOUT_DIRECT_SERVER_ID, strings.dsNightscoutRestApiName)
            },
        )
    }

    discoverySheetTarget?.let { target ->
        DiscoveryBottomSheet(
            title = target.name,
            record = discoveryRecords[target.id],
            state = discoveryUiStates[target.id] ?: DiscoveryUiState.Idle,
            onDismiss = { discoverySheetTarget = null },
            onRediscover = {
                if (target.id == NIGHTSCOUT_DIRECT_SERVER_ID) {
                    settings.nightscoutDirectServer()?.let { viewModel.runNightscoutDiscovery(it) }
                } else {
                    settings.mcpServers.firstOrNull { it.id == target.id }?.let { viewModel.runDiscovery(it) }
                }
            },
            onAskInChat = { question ->
                onAskInChat(question)
                discoverySheetTarget = null
            },
        )
    }
}

private data class DiscoveryTarget(val id: String, val name: String)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun AuthMethodSelector(
    selected: AuthMethod,
    onSelect: (AuthMethod) -> Unit,
    enabled: Boolean,
    showOAuth2: Boolean = false,
) {
    val strings = LocalStrings.current
    Text(strings.dsAuthTypeLabel, style = MaterialTheme.typography.titleSmall)
    Spacer(Modifier.height(4.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = selected == AuthMethod.NONE,
            onClick = { onSelect(AuthMethod.NONE) },
            enabled = enabled,
            label = { Text(strings.authNone) },
        )
        FilterChip(
            selected = selected == AuthMethod.BEARER_TOKEN,
            onClick = { onSelect(AuthMethod.BEARER_TOKEN) },
            enabled = enabled,
            label = { Text(strings.authBearer) },
        )
        FilterChip(
            selected = selected == AuthMethod.API_SECRET_HEADER,
            onClick = { onSelect(AuthMethod.API_SECRET_HEADER) },
            enabled = enabled,
            label = { Text(strings.authCustomHeader) },
        )
        if (showOAuth2) {
            FilterChip(
                selected = selected == AuthMethod.OAUTH2,
                onClick = { onSelect(AuthMethod.OAUTH2) },
                enabled = enabled,
                label = { Text(strings.authOAuth2) },
            )
        }
    }
}

@Composable
private fun TransportSelector(selected: McpTransport, onSelect: (McpTransport) -> Unit, enabled: Boolean) {
    val strings = LocalStrings.current
    Text(strings.dsTransportLabel, style = MaterialTheme.typography.titleSmall)
    Spacer(Modifier.height(4.dp))
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        McpTransport.entries.forEach { transport ->
            FilterChip(
                selected = selected == transport,
                onClick = { onSelect(transport) },
                enabled = enabled,
                label = { Text(transport.label) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun DataCategorySelector(selected: McpDataCategory, onSelect: (McpDataCategory) -> Unit, enabled: Boolean) {
    val strings = LocalStrings.current
    Text(strings.dsDataCategoryLabel, style = MaterialTheme.typography.titleSmall)
    Spacer(Modifier.height(4.dp))
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        McpDataCategory.entries.forEach { category ->
            FilterChip(
                selected = selected == category,
                onClick = { onSelect(category) },
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(category.label) },
            )
        }
    }
}

/** 🟢/🟡/🔴 live-status badge for one data source's collapsed header, driven by its most recent
 * (auto-triggered on screen open, or manual "Verbindung testen") [ConnectionTestState] --
 * distinct from [StatusDot], which reflects the app's *persisted* MCP pool connection instead.
 * [ConnectionTestState.Idle] ("noch nicht getestet") and [ConnectionTestState.Testing] both read
 * as yellow per the spec; only [ConnectionTestState.Testing] pulses, as the "dezent pulsierendes
 * Lade-Icon" while a check is actually in flight. */
@Composable
private fun TrafficLightBadge(testState: ConnectionTestState) {
    val color = when (testState) {
        is ConnectionTestState.Success -> Color(0xFF2E7D32)
        is ConnectionTestState.Error -> Color(0xFFC62828)
        ConnectionTestState.Testing, ConnectionTestState.Idle -> Color(0xFFF9A825)
    }
    val alpha = if (testState is ConnectionTestState.Testing) {
        val infiniteTransition = rememberInfiniteTransition(label = "liveStatusPing")
        val animatedAlpha by infiniteTransition.animateFloat(
            initialValue = 0.35f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(animation = tween(600), repeatMode = RepeatMode.Reverse),
            label = "liveStatusPingAlpha",
        )
        animatedAlpha
    } else {
        1f
    }
    Box(modifier = Modifier.size(10.dp).background(color = color.copy(alpha = alpha), shape = CircleShape))
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun McpServerAccordionCard(
    config: McpServerConfig,
    connectionState: McpConnectionState,
    testState: ConnectionTestState,
    log: List<String>,
    /** null = not fetched yet (shown as "–"/"wird geladen"), distinct from an empty list. */
    tools: List<McpTool>?,
    oauth2LoginState: OAuth2LoginState,
    discoveryState: DiscoveryUiState,
    onExpand: () -> Unit,
    onTest: (McpServerConfig) -> Unit,
    onSave: (McpServerConfig) -> Unit,
    onDelete: () -> Unit,
    onOAuth2Login: (McpServerConfig) -> Unit,
    /** Kicks off a fresh Discovery-Modus run for the current (possibly just-edited) form state. */
    onDiscover: (McpServerConfig) -> Unit,
    /** Just opens the sheet on whatever was already discovered before, without re-running
     * discovery -- the header's "🔍" affordance, distinct from the expanded card's "Erkunden"
     * button (which does both, see [onDiscover]'s call site in [DataSourcesScreen]). */
    onOpenDiscoverySheet: () -> Unit,
) {
    val strings = LocalStrings.current
    var expanded by rememberSaveable(config.id) { mutableStateOf(false) }
    var name by remember(config) { mutableStateOf(config.name) }
    var url by remember(config) { mutableStateOf(config.url) }
    var authMethod by remember(config) { mutableStateOf(config.authMethod) }
    var token by remember(config) { mutableStateOf(config.token) }
    var oauth2 by remember(config) { mutableStateOf(config.oauth2) }
    var category by remember(config) { mutableStateOf(config.category) }
    var transport by remember(config) { mutableStateOf(config.transport) }
    var autoApprove by remember(config) { mutableStateOf(config.autoApprove) }
    var autoApprovedTools by remember(config) { mutableStateOf(config.autoApprovedTools) }
    var enabled by remember(config) { mutableStateOf(config.enabled) }
    var showLog by rememberSaveable(config.id) { mutableStateOf(false) }

    // The OAuth2 login flow saves fresh tokens straight to settings (see
    // SettingsViewModel.handleOAuth2Callback) independently of this card's own "Speichern" --
    // pick that up here so a successful login is reflected without the user having to re-expand
    // the card.
    LaunchedEffect(config.oauth2) { oauth2 = config.oauth2 }

    val trimmedUrl = url.trim()
    val isTesting = testState is ConnectionTestState.Testing
    val currentKey = connectionTestKey(trimmedUrl, authMethod, mcpTestTokenFor(authMethod, token.trim(), oauth2), transport)
    val testMatchesCurrent = (testState as? ConnectionTestState.Finished)?.testedTarget == currentKey
    val testPassed = testMatchesCurrent && testState is ConnectionTestState.Success
    // "Erlaube das Speichern einer neuen MCP-Konfiguration AUCH VOR dem Verbindungstest" -- only
    // an in-flight test blocks Speichern now, not the absence/failure of one. Whether the source
    // is actually *queried* is governed separately by the "Aktiviert" switch below, which -- unlike
    // Speichern -- does still require [testPassed] to be switched on.
    val canSave = !isTesting

    fun edited() = config.copy(
        name = name.trim(),
        url = trimmedUrl,
        authMethod = authMethod,
        token = token.trim(),
        oauth2 = oauth2,
        category = category,
        transport = transport,
        autoApprove = autoApprove,
        autoApprovedTools = autoApprovedTools,
        enabled = enabled,
    )

    Surface(shape = MaterialTheme.shapes.medium, tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        expanded = !expanded
                        if (expanded) onExpand()
                    }
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TrafficLightBadge(testState)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = config.displayName,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = true),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = config.category.shortLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = tools?.size?.let { strings.dsToolsCount(it) } ?: "–",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
                if (!enabled) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = strings.genericDisabled,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 1,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "🔍",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.clickable(onClick = onOpenDiscoverySheet),
                )
                Spacer(Modifier.width(4.dp))
                Text(if (expanded) "▲" else "▼", style = MaterialTheme.typography.labelSmall)
            }

            if (expanded) {
                Column(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp)) {
                    HorizontalDivider()
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(strings.dsNameLabel) },
                        placeholder = { Text(strings.dsNamePlaceholder) },
                        singleLine = true,
                        enabled = !isTesting,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(strings.dsUrlLabel) },
                        placeholder = { Text("https://192.168.1.100:8080/sse") },
                        singleLine = true,
                        enabled = !isTesting,
                    )
                    Spacer(Modifier.height(12.dp))
                    TransportSelector(selected = transport, onSelect = { transport = it }, enabled = !isTesting)
                    Spacer(Modifier.height(12.dp))
                    DataCategorySelector(selected = category, onSelect = { category = it }, enabled = !isTesting)
                    Spacer(Modifier.height(12.dp))
                    AuthMethodSelector(selected = authMethod, onSelect = { authMethod = it }, enabled = !isTesting, showOAuth2 = true)
                    when (authMethod) {
                        AuthMethod.OAUTH2 -> {
                            Spacer(Modifier.height(8.dp))
                            OAuth2Fields(
                                serverUrl = trimmedUrl,
                                oauth2 = oauth2,
                                onChange = { oauth2 = it },
                                loginState = oauth2LoginState,
                                enabled = !isTesting,
                                onLogin = { onOAuth2Login(edited().copy(authMethod = AuthMethod.OAUTH2)) },
                            )
                        }
                        AuthMethod.NONE -> Unit
                        else -> {
                            Spacer(Modifier.height(8.dp))
                            PasswordField(value = token, onValueChange = { token = it }, label = strings.dsTokenSecretLabel, enabled = !isTesting)
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.weight(1f)) {
                            Text(strings.dsDataSourceEnabled, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                text = if (testPassed) strings.dsQueriedByDashboardChat else strings.dsEnableAfterTestHint,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = enabled,
                            onCheckedChange = { checked ->
                                if (!checked || testPassed) {
                                    enabled = checked
                                    onSave(edited().copy(enabled = checked))
                                }
                            },
                            enabled = !isTesting,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.weight(1f)) {
                            Text(strings.dsDiscoveryTitle, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                text = strings.dsDiscoveryHint,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        OutlinedButton(
                            onClick = { onDiscover(edited()) },
                            // [connectionState] alone would wrongly stay disabled forever for a
                            // server that was already connected by MainActivity's app-start sync
                            // (the common case) rather than (re)connected from within this screen
                            // -- SettingsViewModel's own connection-state map is only ever written
                            // by saveMcpServers(), never backfilled from the real McpServerPool's
                            // already-live connections. A non-null/non-empty [tools] list (already
                            // successfully fetched against the real pool, e.g. via onExpand) is
                            // just as reliable a "this server is actually reachable" signal.
                            enabled = (connectionState == McpConnectionState.Connected || !tools.isNullOrEmpty()) &&
                                discoveryState != DiscoveryUiState.Running,
                        ) {
                            Text(strings.dsExploreButton)
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.weight(1f)) {
                            Text(strings.dsAutoRunToolsTitle, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                text = strings.dsAutoRunToolsHint,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(checked = autoApprove, onCheckedChange = { autoApprove = it }, enabled = !isTesting)
                    }

                    if (!tools.isNullOrEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        Text(strings.dsToolsTitle(tools.size), style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = strings.dsToolsAutoApproveHint,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(4.dp))
                        Column {
                            tools.forEach { tool ->
                                McpToolRow(
                                    tool = tool,
                                    autoApproved = autoApprove || tool.name in autoApprovedTools,
                                    lockedOn = autoApprove,
                                    onToggle = { checked ->
                                        autoApprovedTools = if (checked) {
                                            autoApprovedTools + tool.name
                                        } else {
                                            autoApprovedTools - tool.name
                                        }
                                    },
                                )
                            }
                        }
                    } else if (connectionState == McpConnectionState.Connected) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = strings.dsToolsLoading,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    Spacer(Modifier.height(12.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { onTest(edited()) }, enabled = !isTesting && trimmedUrl.isNotBlank()) {
                            Text(strings.dsTestConnection)
                        }
                        Button(onClick = { onSave(edited()) }, enabled = canSave) {
                            Text(strings.genericSave)
                        }
                        OutlinedButton(
                            onClick = onDelete,
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        ) {
                            Text(strings.genericRemove)
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    StatusLine(
                        text = when {
                            isTesting -> strings.dsTestingConnection
                            testState is ConnectionTestState.Success && testMatchesCurrent -> strings.dsConnectionSuccessful
                            testState is ConnectionTestState.Error && testMatchesCurrent -> strings.genericErrorPrefix(testState.message)
                            else -> strings.dsNotTestedBeforeEnable
                        },
                        isBusy = isTesting,
                        tone = when {
                            testState is ConnectionTestState.Success && testMatchesCurrent -> StatusTone.SUCCESS
                            testState is ConnectionTestState.Error && testMatchesCurrent -> StatusTone.ERROR
                            else -> StatusTone.NEUTRAL
                        },
                    )
                    StatusLine(
                        text = when (val state = connectionState) {
                            McpConnectionState.Idle -> strings.dsSavedNotConnected
                            McpConnectionState.Connecting -> strings.dsConnecting
                            McpConnectionState.Connected -> strings.dsConnected
                            is McpConnectionState.Error -> strings.dsConnectionFailed(state.message)
                        },
                        isBusy = connectionState == McpConnectionState.Connecting,
                        tone = when (connectionState) {
                            McpConnectionState.Connected -> StatusTone.SUCCESS
                            is McpConnectionState.Error -> StatusTone.ERROR
                            else -> StatusTone.NEUTRAL
                        },
                    )

                    if (log.isNotEmpty()) {
                        val clipboard = LocalClipboardManager.current
                        Spacer(Modifier.height(4.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(onClick = { showLog = !showLog }) {
                                Text(if (showLog) strings.dsHideConnectionLog else strings.dsShowConnectionLog)
                            }
                            if (showLog) {
                                TextButton(onClick = { clipboard.setText(AnnotatedString(log.joinToString("\n"))) }) {
                                    Text(strings.dsCopyLog)
                                }
                            }
                        }
                        if (showLog) {
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                shape = MaterialTheme.shapes.small,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                SelectionContainer {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .heightIn(max = 220.dp)
                                            .verticalScroll(rememberScrollState())
                                            .padding(8.dp),
                                    ) {
                                        log.forEach { line ->
                                            Text(text = line, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun McpToolRow(tool: McpTool, autoApproved: Boolean, lockedOn: Boolean, onToggle: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(
            text = tool.name,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Checkbox(checked = autoApproved, onCheckedChange = onToggle, enabled = !lockedOn)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OAuth2Fields(
    serverUrl: String,
    oauth2: OAuth2Config,
    onChange: (OAuth2Config) -> Unit,
    loginState: OAuth2LoginState,
    enabled: Boolean,
    onLogin: () -> Unit,
) {
    val strings = LocalStrings.current
    var showManualFields by rememberSaveable { mutableStateOf(oauth2.authorizationEndpoint.isNotBlank()) }

    Column {
        Text(
            text = strings.dsOAuth2Hint,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = onLogin,
                enabled = enabled && (oauth2.authorizationEndpoint.isNotBlank() || serverUrl.isNotBlank()) &&
                    loginState !is OAuth2LoginState.LoggingIn && loginState !is OAuth2LoginState.Discovering,
            ) {
                Text(if (oauth2.isLoggedIn) strings.dsLoginAgain else strings.dsLoginWithProvider)
            }
        }
        Spacer(Modifier.height(4.dp))
        StatusLine(
            text = when {
                loginState is OAuth2LoginState.Discovering -> strings.dsOAuth2Discovering
                loginState is OAuth2LoginState.LoggingIn -> strings.dsOAuth2LoggingIn
                loginState is OAuth2LoginState.Error -> strings.genericErrorPrefix(loginState.message)
                oauth2.isLoggedIn -> strings.dsLoggedIn
                else -> strings.dsNotLoggedIn
            },
            isBusy = loginState is OAuth2LoginState.Discovering || loginState is OAuth2LoginState.LoggingIn,
            tone = when {
                loginState is OAuth2LoginState.Error -> StatusTone.ERROR
                oauth2.isLoggedIn -> StatusTone.SUCCESS
                else -> StatusTone.NEUTRAL
            },
        )
        Spacer(Modifier.height(4.dp))
        TextButton(onClick = { showManualFields = !showManualFields }) {
            Text(if (showManualFields) strings.dsHideManualEndpoints else strings.dsShowManualEndpoints)
        }
        if (showManualFields) {
            OutlinedTextField(
                value = oauth2.authorizationEndpoint,
                onValueChange = { onChange(oauth2.copy(authorizationEndpoint = it)) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(strings.dsAuthEndpointLabel) },
                placeholder = { Text("https://account.withings.com/oauth2_user/authorize2") },
                singleLine = true,
                enabled = enabled,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = oauth2.tokenEndpoint,
                onValueChange = { onChange(oauth2.copy(tokenEndpoint = it)) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(strings.dsTokenEndpointLabel) },
                placeholder = { Text("https://wbsapi.withings.net/v2/oauth2") },
                singleLine = true,
                enabled = enabled,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = oauth2.clientId,
                onValueChange = { onChange(oauth2.copy(clientId = it)) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(strings.dsClientIdLabel) },
                singleLine = true,
                enabled = enabled,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = oauth2.scope,
                onValueChange = { onChange(oauth2.copy(scope = it)) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(strings.dsScopeOptionalLabel) },
                placeholder = { Text("user.metrics") },
                singleLine = true,
                enabled = enabled,
            )
        }
    }
}

@Composable
private fun NightscoutApiSection(
    url: String,
    secret: String,
    authMethod: AuthMethod,
    enabled: Boolean,
    /** Whether the *persisted* settings already have a non-blank URL -- used only for the
     * collapsed-state summary/status dot, independent of whatever's currently being edited but
     * not yet saved in this card. */
    configured: Boolean,
    testState: ConnectionTestState,
    discoveryState: DiscoveryUiState,
    onUrlChange: (String) -> Unit,
    onSecretChange: (String) -> Unit,
    onAuthMethodChange: (AuthMethod) -> Unit,
    onEnabledChange: (Boolean) -> Unit,
    onTest: () -> Unit,
    onSave: () -> Unit,
    onDiscover: () -> Unit,
    onOpenDiscoverySheet: () -> Unit,
) {
    val strings = LocalStrings.current
    // Collapsed by default only once something's actually configured -- an empty, never-used
    // Nightscout section should stay visible/inviting rather than hiding behind a tap.
    var expanded by rememberSaveable(configured) { mutableStateOf(!configured) }
    val trimmedUrl = url.trim()
    val isTesting = testState is ConnectionTestState.Testing
    val currentKey = connectionTestKey(trimmedUrl, authMethod, secret.trim())
    val testMatchesCurrent = (testState as? ConnectionTestState.Finished)?.testedTarget == currentKey
    val testPassed = testMatchesCurrent && testState is ConnectionTestState.Success
    // "Erlaube das Speichern AUCH VOR dem Verbindungstest" -- same relaxed gating as the MCP
    // server cards; only "Aktiviert" (below) still requires a passing test.
    val canSave = !isTesting

    Surface(shape = MaterialTheme.shapes.medium, tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TrafficLightBadge(testState)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = strings.dsNightscoutTitle,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f, fill = true),
                )
                if (configured && !enabled) {
                    Text(strings.genericDisabled, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.width(8.dp))
                }
                if (configured) {
                    Text(
                        text = "🔍",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.clickable(onClick = onOpenDiscoverySheet),
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(if (expanded) "▲" else "▼", style = MaterialTheme.typography.labelSmall)
            }

            if (expanded) {
                Column(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp)) {
                    HorizontalDivider()
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = strings.dsNightscoutHint,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))

                    OutlinedTextField(
                        value = url,
                        onValueChange = onUrlChange,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(strings.dsNightscoutUrlLabel) },
                        placeholder = { Text("https://meine-instanz.herokuapp.com") },
                        singleLine = true,
                        enabled = !isTesting,
                    )
                    Spacer(Modifier.height(12.dp))
                    AuthMethodSelector(selected = authMethod, onSelect = onAuthMethodChange, enabled = !isTesting)
                    if (authMethod != AuthMethod.NONE) {
                        Spacer(Modifier.height(8.dp))
                        PasswordField(value = secret, onValueChange = onSecretChange, label = strings.dsTokenSecretLabel, enabled = !isTesting)
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.weight(1f)) {
                            Text(strings.dsDataSourceEnabled, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                text = if (testPassed) strings.dsQueriedByDashboardChat else strings.dsEnableAfterTestHint,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = enabled,
                            onCheckedChange = { checked -> if (!checked || testPassed) onEnabledChange(checked) },
                            enabled = !isTesting,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.weight(1f)) {
                            Text(strings.dsDiscoveryTitle, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                text = strings.dsNightscoutDiscoveryHint,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        OutlinedButton(onClick = onDiscover, enabled = testPassed && discoveryState != DiscoveryUiState.Running) {
                            Text(strings.dsExploreButton)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = onTest, enabled = !isTesting && trimmedUrl.isNotBlank()) {
                        Text(strings.dsTestConnection)
                    }
                    Spacer(Modifier.height(4.dp))
                    StatusLine(
                        text = when {
                            isTesting -> strings.dsTestingConnection
                            testState is ConnectionTestState.Success && testMatchesCurrent -> strings.dsConnectionSuccessful
                            testState is ConnectionTestState.Error && testMatchesCurrent -> strings.genericErrorPrefix(testState.message)
                            else -> strings.dsNotTestedBeforeEnable
                        },
                        isBusy = isTesting,
                        tone = when {
                            testState is ConnectionTestState.Success && testMatchesCurrent -> StatusTone.SUCCESS
                            testState is ConnectionTestState.Error && testMatchesCurrent -> StatusTone.ERROR
                            else -> StatusTone.NEUTRAL
                        },
                    )

                    Spacer(Modifier.height(8.dp))
                    Button(onClick = onSave, enabled = canSave) {
                        Text(strings.genericSave)
                    }
                }
            }
        }
    }
}

/**
 * "Discovery Modus" item 3: the interactive detail sheet opened from either a data source's "🔍"
 * header icon (shows whatever was last discovered) or its "Erkunden" button (triggers a fresh run
 * first). Three sections, in order: 🛠️ the discovered tools/plugins themselves, 💡 LLM-generated
 * example questions as tappable [AssistChip]s (tapping one hands the question straight to
 * [onAskInChat], which -- see [DataSourcesScreen]'s caller in `MainActivity` -- both prefills the
 * chat input and switches to the Chat tab), and 📦 a compact read/write + data-structure summary.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun DiscoveryBottomSheet(
    title: String,
    record: DiscoveryRecord?,
    state: DiscoveryUiState,
    onDismiss: () -> Unit,
    onRediscover: () -> Unit,
    onAskInChat: (String) -> Unit,
) {
    val strings = LocalStrings.current
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
        ) {
            Text("🔍 $title", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))

            when (state) {
                DiscoveryUiState.Running -> {
                    StatusLine(text = strings.dsDiscoverySheetExploring, isBusy = true, tone = StatusTone.NEUTRAL)
                    Spacer(Modifier.height(12.dp))
                }
                is DiscoveryUiState.Error -> {
                    StatusLine(text = strings.genericErrorPrefix(state.message), isBusy = false, tone = StatusTone.ERROR)
                    Spacer(Modifier.height(12.dp))
                }
                else -> Unit
            }

            if (record == null) {
                Text(
                    text = strings.dsDiscoveryNotExploredYet,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                record.profile?.summary?.takeIf { it.isNotBlank() }?.let { summary ->
                    Text(summary, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(16.dp))
                }

                Text(strings.dsAvailableToolsTitle, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                if (record.schema.tools.isEmpty()) {
                    Text(
                        text = strings.dsNoToolsFound,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    record.schema.tools.forEach { tool ->
                        Column(Modifier.padding(vertical = 4.dp)) {
                            Text(tool.name, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
                            tool.description?.takeIf { it.isNotBlank() }?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }

                record.profile?.exampleQuestions?.takeIf { it.isNotEmpty() }?.let { questions ->
                    Spacer(Modifier.height(16.dp))
                    Text(strings.dsWhatYouCanAskTitle, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        questions.forEach { question ->
                            AssistChip(onClick = { onAskInChat(question) }, label = { Text(question) })
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))
                Text(strings.dsDataStructureTitle, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                Text(
                    text = if (record.schema.readOnly) strings.dsReadOnly else strings.dsWriteCapable,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (record.schema.readOnly) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                )
                if (record.schema.restCapabilities.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    record.schema.restCapabilities.forEach { capability ->
                        Text(
                            text = "• ${capability.description}" + if (capability.writeCapable) strings.dsWriteCapableSuffix else "",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                record.profile?.keyReturnValues?.takeIf { it.isNotEmpty() }?.let { values ->
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = strings.dsKeyReturnValues(values.joinToString(", ")),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (record.schema.resources.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = strings.dsResources(record.schema.resources.joinToString(", ")),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(20.dp))
            OutlinedButton(onClick = onRediscover, enabled = state != DiscoveryUiState.Running) {
                Text(if (record == null) strings.dsExploreNow else strings.dsExploreAgain)
            }
        }
    }
}
