package com.example.diabai.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.example.diabai.network.allDataSourceServers
import com.example.diabai.network.nightscoutDirectServer

@Composable
fun ChatScreen(viewModel: GlucoSphereViewModel, ttsController: TtsController? = null, modifier: Modifier = Modifier) {
    val chatItems by viewModel.chatItems.collectAsState()
    val isBusy by viewModel.isAgentBusy.collectAsState()
    val includeMcpTools by viewModel.includeMcpTools.collectAsState()
    val settings by viewModel.settings.collectAsState()
    val chatSelectedCapabilityTags by viewModel.chatSelectedCapabilityTags.collectAsState()
    val mcpActiveServerIds by viewModel.activeServerIds.collectAsState()
    // The direct Nightscout REST API has no persistent MCP connection to check -- treat it as
    // "active" whenever it's configured (and enabled) at all, rather than always showing it
    // greyed-out "(offline)" for lacking something it was never going to have.
    val activeServerIds = mcpActiveServerIds + listOfNotNull(settings.nightscoutDirectServer()?.id)
    val aiActivityState by viewModel.aiActivityState.collectAsState()
    val discoveredToolNames by viewModel.discoveredToolNames.collectAsState()
    val excludedToolNames by viewModel.excludedToolNames.collectAsState()
    val pendingChatPrefill by viewModel.pendingChatPrefill.collectAsState()
    val strings = LocalStrings.current

    ChatSection(
        items = chatItems,
        isBusy = isBusy,
        includeMcpTools = includeMcpTools,
        onIncludeMcpToolsChange = viewModel::setIncludeMcpTools,
        servers = settings.allDataSourceServers.filter { it.enabled },
        activeServerIds = activeServerIds,
        selectedCapabilityTags = chatSelectedCapabilityTags,
        onToggleCapabilityTagGroup = viewModel::toggleChatCapabilityTagGroup,
        discoveredToolNames = discoveredToolNames,
        excludedToolNames = excludedToolNames,
        onToggleDiscoveredTool = viewModel::toggleDiscoveredTool,
        pendingPrefill = pendingChatPrefill,
        onPrefillConsumed = viewModel::consumePendingChatPrefill,
        onSend = viewModel::sendMessage,
        onCancel = viewModel::cancelGeneration,
        onConfirm = viewModel::respondToConfirmation,
        onMessageCopied = { viewModel.showTransientMessage(strings.messageCopied) },
        appLanguage = settings.appLanguage,
        ttsController = ttsController,
        aiActivityState = aiActivityState,
        modifier = modifier.fillMaxSize(),
    )
}
