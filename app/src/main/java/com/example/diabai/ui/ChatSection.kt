package com.example.diabai.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import android.content.Intent
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.example.diabai.data.AppLanguage
import com.example.diabai.data.McpServerConfig
import com.example.diabai.domain.CHAT_CAPABILITY_TAG_GROUPS
import com.example.diabai.domain.CapabilityTag
import com.example.diabai.domain.CapabilityTagGroup
import com.example.diabai.domain.inferCapabilityTags
import com.example.diabai.domain.shareChatAnswerAsPdf
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Shared by [ChatBubble]/[AssistantBubble]'s "Teilen" menu entry -- native `ACTION_SEND` so the
 * target app (WhatsApp, Mail, ...) is picked by the user via the system chooser, same mechanism
 * as the Übersicht tab's Share icon (see [buildDashboardShareText]/[MainTabsScreen]). */
private fun shareText(context: android.content.Context, text: String, chooserTitle: String) {
    val sendIntent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(sendIntent, chooserTitle))
}

private val chatShareTimeFormat: DateTimeFormatter =
    DateTimeFormatter.ofPattern("dd.MM. HH:mm").withZone(ZoneId.systemDefault())

/**
 * Plain-text rendering of the *entire* chat transcript for [android.content.Intent.ACTION_SEND]
 * -- "einen kompletten Chatverlauf mit eingeschalteten Tools und Zeitstempeln teilen": every
 * message gets its own `[dd.MM. HH:mm]` timestamp (see [ChatItem.timestampMillis]), and every
 * tool call that actually ran during the conversation is included as its own line (not just
 * whether MCP was toggled on) -- a header line separately names which data sources were
 * enabled/selected for the conversation. Operates on the live in-memory [items] list, not the
 * persisted [com.example.diabai.data.ChatSession] -- a reloaded old session has no per-message
 * timestamps or tool-activity history to recover (see [ChatItem.timestampMillis]'s doc comment),
 * so this is only fully complete for the chat that's actually still open. A still-streaming
 * [ChatItem.AssistantMessage] or one with no visible answer text yet is skipped rather than
 * exported half-finished.
 */
fun buildChatShareText(
    items: List<ChatItem>,
    includeMcpTools: Boolean,
    /** Tag-basiertes Capability-Routing (item 1): null = every capability chip active/no
     * restriction, otherwise the union of tags from whichever chips were selected -- replaces the
     * old per-server-name label with the chip labels themselves, since a chip no longer maps to
     * one single brand/server. */
    selectedCapabilityTags: Set<CapabilityTag>?,
    strings: Strings,
): String {
    val activeSourcesLabel = if (!includeMcpTools) {
        strings.mcpDisabledLabel
    } else {
        CHAT_CAPABILITY_TAG_GROUPS
            .filter { group -> selectedCapabilityTags?.containsAll(group.tags) ?: true }
            .joinToString(", ") { it.label }
            .ifBlank { strings.mcpDisabledLabel }
    }
    return buildString {
        appendLine("GlucoSphere – ${strings.tabChat}")
        appendLine("${strings.activeToolsLabel}: $activeSourcesLabel")
        appendLine()
        items.forEach { item ->
            val time = chatShareTimeFormat.format(Instant.ofEpochMilli(item.timestampMillis))
            when (item) {
                is ChatItem.UserMessage -> appendLine("[$time] ${strings.youLabel}: ${item.text}")
                is ChatItem.AssistantMessage -> {
                    if (item.isStreaming) return@forEach
                    val answer = item.markdown.splitReasoning().answer
                    if (answer.isBlank()) return@forEach
                    val durationSuffix = formatDurationBadge(item.durationMillis)?.let { " ($it)" }.orEmpty()
                    appendLine("[$time] GlucoSphere$durationSuffix: $answer")
                }
                is ChatItem.ToolActivity -> appendLine("[$time] 🔧 ${item.toolName}: ${item.summary}")
                is ChatItem.PendingConfirmation -> appendLine("[$time] ❓ ${item.description}")
                is ChatItem.ErrorMessage -> appendLine("[$time] ⚠️ ${item.text}")
                is ChatItem.SystemNote -> appendLine("[$time] ℹ️ ${item.text}")
            }
            appendLine()
        }
    }.trim()
}

@Composable
fun ChatSection(
    items: List<ChatItem>,
    isBusy: Boolean,
    includeMcpTools: Boolean,
    onIncludeMcpToolsChange: (Boolean) -> Unit,
    servers: List<McpServerConfig>,
    /** Servers that answered the app-start health check this session (see McpServerPool);
     * used to show a capability chip's 🟢/⚪ dot -- see [ChatInputBar]. */
    activeServerIds: Set<String>,
    /** Tag-basiertes Capability-Routing (item 1): null = every capability chip active (default),
     * otherwise the union of tags from whichever chips the user has explicitly selected --
     * replaces the old per-brand server chips (see [GlucoSphereViewModel.chatSelectedCapabilityTags]). */
    selectedCapabilityTags: Set<CapabilityTag>?,
    onToggleCapabilityTagGroup: (CapabilityTagGroup) -> Unit,
    /** "Dynamische Tool-Registrierung" (Discovery Modus item 4) -- every tool name discovered so
     * far, deduplicated, shown as its own row of pill chips above the server chips;
     * [excludedToolNames] are the ones the user switched off, [onToggleDiscoveredTool] flips one.
     * Empty [discoveredToolNames] simply hides the row rather than showing an empty one. */
    discoveredToolNames: List<String> = emptyList(),
    excludedToolNames: Set<String> = emptySet(),
    onToggleDiscoveredTool: (String) -> Unit = {},
    onSend: (String) -> Unit,
    onCancel: () -> Unit,
    onConfirm: (ChatItem.PendingConfirmation, Boolean) -> Unit,
    /** Long-press-to-copy feedback ("Nachricht kopiert") -- the actual clipboard write happens
     * right in [ChatBubble]/[AssistantBubble], this just surfaces the Snackbar. */
    onMessageCopied: () -> Unit,
    /** Set from the Discovery-Sheet's "💡 Was du fragen kannst" chips (see [DataSourcesScreen]) --
     * consumed once into [input] below, then cleared via [onPrefillConsumed] so it doesn't
     * re-populate the field again on the next recomposition (e.g. after the user has since
     * cleared it themselves). */
    pendingPrefill: String? = null,
    onPrefillConsumed: () -> Unit = {},
    appLanguage: AppLanguage = AppLanguage.GERMAN,
    ttsController: TtsController? = null,
    aiActivityState: AiActivityState = AiActivityState.Idle,
    modifier: Modifier = Modifier,
) {
    val strings = LocalStrings.current
    var input by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(pendingPrefill) {
        if (pendingPrefill != null) {
            input = pendingPrefill
            onPrefillConsumed()
        }
    }
    val listState = rememberLazyListState()
    // Voice-Dialogmodus (item 4): true between tapping the headset toggle and either the user
    // explicitly exiting it or the mic returning no result -- while active, a recognized
    // utterance is sent immediately (no manual "Senden" tap) and the assistant's answer is read
    // back automatically, see the LaunchedEffects below.
    var voiceModeActive by rememberSaveable { mutableStateOf(false) }
    // The id of the last AssistantMessage already read aloud in voice mode -- guards against
    // re-reading the same answer on every recomposition and against the very first
    // "speakingId became null" transition (before anything has ever been spoken) immediately
    // re-triggering the mic.
    var voiceModeLastReadId by rememberSaveable { mutableStateOf<String?>(null) }
    val (speechInputState, startSpeechInput) = rememberSpeechInputLauncher(appLanguage.languageTag, strings.micPrompt) { recognized ->
        if (voiceModeActive) {
            onSend(recognized)
        } else {
            input = if (input.isBlank()) recognized else "$input $recognized"
        }
    }
    val isListening by speechInputState.isListening
    val speakingId = ttsController?.isSpeakingId?.collectAsState()?.value

    // The typing-indicator bubble is always the last LazyColumn item (see the trailing `item {}`
    // below, index == items.size regardless of whether it's currently visible) -- scrolling to
    // that index on every activity-state change, not just on items.size, is what makes the list
    // follow "🔧 Abfrage an Nightscout …" -> "✍️ Formuliere Antwort …" as it appears/updates,
    // not only once a real new ChatItem lands.
    LaunchedEffect(items.size, aiActivityState) {
        if (items.isNotEmpty() || aiActivityState != AiActivityState.Idle) listState.animateScrollToItem(items.size)
    }

    // Voice mode, step 2 of the loop: the moment a turn finishes (isBusy flips back to false)
    // with a fresh, not-yet-read AssistantMessage as the last item, read it aloud via TTS --
    // "Sobald die Antwort des LLMs eintrifft, wird sie sofort laut vorgelesen."
    LaunchedEffect(items, isBusy, voiceModeActive) {
        if (!voiceModeActive || isBusy || ttsController == null) return@LaunchedEffect
        val lastAssistant = items.lastOrNull { it is ChatItem.AssistantMessage } as? ChatItem.AssistantMessage ?: return@LaunchedEffect
        if (lastAssistant.isStreaming || lastAssistant.id == voiceModeLastReadId) return@LaunchedEffect
        val answer = lastAssistant.markdown.splitReasoning().answer
        if (answer.isBlank()) return@LaunchedEffect
        voiceModeLastReadId = lastAssistant.id
        ttsController.speak(lastAssistant.id, answer)
    }

    // Voice mode, step 3 (the actual "Dialog" in Dialogmodus): once TTS finishes reading that
    // answer (isSpeakingId falls back to null) and we're not mid-turn, automatically reopen the
    // mic for the user's next utterance -- continues until [voiceModeActive] is turned off.
    // Guarded on voiceModeLastReadId != null so this never fires from the toggle-on transition
    // itself (speakingId is also null then, before anything has been read).
    LaunchedEffect(speakingId, voiceModeActive, isBusy, isListening) {
        if (voiceModeActive && speakingId == null && voiceModeLastReadId != null && !isBusy && !isListening) {
            startSpeechInput()
        }
    }

    // Leaving voice mode always stops any in-progress read-out immediately.
    LaunchedEffect(voiceModeActive) {
        if (!voiceModeActive) ttsController?.stop()
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = strings.includeMcpServers,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            Switch(checked = includeMcpTools, onCheckedChange = onIncludeMcpToolsChange, enabled = !isBusy)
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(items, key = { it.id }) { item -> ChatItemRow(item, onConfirm, onMessageCopied, ttsController) }
            item(key = "ai-activity-indicator") { TypingIndicatorBubble(aiActivityState) }
        }
        if (voiceModeActive) {
            VoiceModeBanner(
                isListening = isListening,
                isSpeaking = speakingId != null,
                onStopSpeaking = { ttsController?.stop() },
                onExit = { voiceModeActive = false },
                strings = strings,
            )
        } else if (isListening) {
            Text(
                text = strings.listening,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
        }
        ChatInputBar(
            input = input,
            onInputChange = { input = it },
            isBusy = isBusy,
            isListening = isListening,
            voiceModeActive = voiceModeActive,
            onMicClick = startSpeechInput,
            onToggleVoiceMode = {
                voiceModeActive = !voiceModeActive
                if (voiceModeActive) startSpeechInput()
            },
            onSend = {
                onSend(input)
                input = ""
            },
            onCancel = onCancel,
            includeMcpTools = includeMcpTools,
            servers = servers,
            activeServerIds = activeServerIds,
            selectedCapabilityTags = selectedCapabilityTags,
            onToggleCapabilityTagGroup = onToggleCapabilityTagGroup,
            discoveredToolNames = discoveredToolNames,
            excludedToolNames = excludedToolNames,
            onToggleDiscoveredTool = onToggleDiscoveredTool,
            strings = strings,
        )
    }
}

/** The pulsing "🎙️/🔊 active" banner shown above the input bar for as long as
 * [voiceModeActive][ChatSection] is on -- tapping anywhere on it stops an in-progress read-out
 * (per "Der Nutzer kann die Sprachausgabe jederzeit durch Tap ... unterbrechen"), which also
 * naturally reopens the mic right after (see the `speakingId == null` LaunchedEffect in
 * [ChatSection]) rather than needing a separate "interrupt" code path. */
@Composable
private fun VoiceModeBanner(
    isListening: Boolean,
    isSpeaking: Boolean,
    onStopSpeaking: () -> Unit,
    onExit: () -> Unit,
    strings: Strings,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.primaryContainer)
            .clickable(enabled = isSpeaking, onClick = onStopSpeaking)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        VoicePulseIndicator(active = isListening || isSpeaking)
        Text(
            text = when {
                isListening -> strings.voiceModeListening
                isSpeaking -> strings.voiceModeSpeaking
                else -> strings.voiceModeIdle
            },
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onExit) { Text(strings.voiceModeExit) }
    }
}

/** A softly pulsing dot -- [active] (listening or speaking) pulses continuously via
 * [rememberInfiniteTransition]; otherwise it sits at a fixed size, same visual language as
 * [TypingDots] elsewhere in this file. */
@Composable
private fun VoicePulseIndicator(active: Boolean) {
    val transition = rememberInfiniteTransition(label = "voice-pulse")
    val scale by transition.animateFloat(
        initialValue = if (active) 0.85f else 1f,
        targetValue = if (active) 1.25f else 1f,
        animationSpec = infiniteRepeatable(animation = tween(700, easing = LinearEasing), repeatMode = RepeatMode.Reverse),
        label = "pulse-scale",
    )
    Box(
        modifier = Modifier
            .size(14.dp)
            .scale(if (active) scale else 1f)
            .background(MaterialTheme.colorScheme.primary, CircleShape),
    )
}

/**
 * The Gemini/ChatGPT-style floating input surface: rounded, elevated [Surface] holding an
 * optional horizontal pill-chip data-source selector directly above a multiline, auto-growing
 * text field, with mic/voice-mode/send controls alongside it -- replaces the previous plain,
 * fixed-height [OutlinedTextField] + button row.
 */
@Composable
private fun ChatInputBar(
    input: String,
    onInputChange: (String) -> Unit,
    isBusy: Boolean,
    isListening: Boolean,
    voiceModeActive: Boolean,
    onMicClick: () -> Unit,
    onToggleVoiceMode: () -> Unit,
    onSend: () -> Unit,
    onCancel: () -> Unit,
    includeMcpTools: Boolean,
    servers: List<McpServerConfig>,
    activeServerIds: Set<String>,
    selectedCapabilityTags: Set<CapabilityTag>?,
    onToggleCapabilityTagGroup: (CapabilityTagGroup) -> Unit,
    discoveredToolNames: List<String>,
    excludedToolNames: Set<String>,
    onToggleDiscoveredTool: (String) -> Unit,
    strings: Strings,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(28.dp),
        shadowElevation = 6.dp,
        tonalElevation = 3.dp,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Column(Modifier.padding(top = 8.dp, bottom = 4.dp)) {
            // Tag-basiertes Capability-Routing (item 1): compact pill chips per CAPABILITY --
            // "BZ & Insulin" / "Aktivität & Sport" / "Körperdaten" -- instead of one chip per
            // brand/server name. Tapping one toggles that whole tag group for the *next* message
            // (same "toggle, dim while deselected" shape the old per-server row used); the 🟢/⚪
            // dot reflects whether ANY currently-active data source plausibly offers a tool for
            // this capability (via the server's own [McpDataCategory], the same broad prior
            // [inferCapabilityTags] falls back to for a tool with no clear keyword match) -- the
            // real, per-tool tag filtering itself happens later in
            // [com.example.diabai.domain.DiabetesAgent.resolveTools], once the actual tool list is
            // known.
            if (includeMcpTools && servers.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    CHAT_CAPABILITY_TAG_GROUPS.forEach { group ->
                        val isSelected = selectedCapabilityTags?.containsAll(group.tags) ?: true
                        val isActive = servers.any { server ->
                            server.id in activeServerIds && inferCapabilityTags(server.displayName, null, server.category).any { it in group.tags }
                        }
                        ToolPillChip(
                            label = "${if (isActive) "🟢" else "⚪"} ${group.emoji} ${group.label}",
                            selected = isSelected && isActive,
                            enabled = !isBusy && isActive,
                            onClick = { onToggleCapabilityTagGroup(group) },
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
            }
            // Dynamische Tool-Registrierung (Discovery Modus item 4): every tool Discovery Modus
            // has ever found, one chip each -- independent of (and layered on top of) the
            // server-level chips above, so a single noisy/unwanted tool can be switched off
            // without deselecting its whole server. Selected (not excluded) by default.
            if (includeMcpTools && discoveredToolNames.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    discoveredToolNames.forEach { toolName ->
                        ToolPillChip(
                            label = "🔍 $toolName",
                            selected = toolName !in excludedToolNames,
                            enabled = !isBusy,
                            onClick = { onToggleDiscoveredTool(toolName) },
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
            }
            Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp)) {
                TextField(
                    value = input,
                    onValueChange = onInputChange,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text(strings.chatPlaceholder) },
                    enabled = !isBusy,
                    maxLines = 6,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        disabledContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        disabledIndicatorColor = Color.Transparent,
                    ),
                )
                // Mic (STT) -- same emoji-glyph approach as the TTS speaker icon below, see
                // InfoCard in DashboardSection.kt for why (no material-icons-extended dependency).
                TextButton(onClick = onMicClick, enabled = !isBusy && !isListening) { Text(if (isListening) "🔴" else "🎤") }
                // Voice-Dialogmodus toggle (item 4) -- a prominent headset glyph next to Senden,
                // highlighted while active.
                TextButton(onClick = onToggleVoiceMode, enabled = !isBusy) {
                    Text(
                        "🎧",
                        color = if (voiceModeActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (isBusy) {
                    IconButton(onClick = onCancel) { Text("⏹️") }
                } else {
                    IconButton(onClick = onSend, enabled = input.isNotBlank()) {
                        Text("➤", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}

/** A pill-shaped, dimmable filter chip for the input bar's data-source selector -- plain
 * [Surface] + [Text] rather than Material3's [FilterChip] so the shape/dimming exactly match
 * "kompakte Pill-Chips ... deaktivierte Chips werden visuell gedimmt dargestellt" instead of
 * FilterChip's default look. */
@Composable
private fun ToolPillChip(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(50),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        contentColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.alpha(if (selected) 1f else 0.5f),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun ChatItemRow(
    item: ChatItem,
    onConfirm: (ChatItem.PendingConfirmation, Boolean) -> Unit,
    onMessageCopied: () -> Unit,
    ttsController: TtsController?,
) {
    when (item) {
        is ChatItem.UserMessage -> ChatBubble(text = item.text, isUser = true, onCopied = onMessageCopied)
        is ChatItem.AssistantMessage -> AssistantBubble(item, onCopied = onMessageCopied, ttsController = ttsController)
        is ChatItem.ToolActivity -> ToolActivityRow(item)
        is ChatItem.PendingConfirmation -> ConfirmationCard(item, onConfirm)
        is ChatItem.ErrorMessage -> Text(
            text = item.text,
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
        )
        is ChatItem.SystemNote -> Text(
            text = item.text,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

/** The "💭 KI denkt nach …" / "🔧 Abfrage an {Server} …" / "✍️ Formuliere Antwort …" bubble --
 * always the LazyColumn's last item (see [ChatSection]'s trailing `item {}`), faded in/out via
 * [AnimatedVisibility] rather than added/removed as a real [ChatItem] so it never gets persisted
 * into chat history or briefly flashes as a "real" message. [lastLabel] keeps rendering the most
 * recent non-null label through the fade-OUT animation itself -- [AnimatedVisibility]'s content
 * lambda keeps composing while exiting, and the state has usually already flipped back to
 * [AiActivityState.Idle] (label == null) by the time that plays out, which would otherwise render
 * an empty bubble mid-fade instead of the label it's actually fading away from. */
@Composable
private fun TypingIndicatorBubble(state: AiActivityState) {
    val strings = LocalStrings.current
    val label = when (state) {
        AiActivityState.Idle -> null
        AiActivityState.Thinking -> strings.aiThinking
        is AiActivityState.CallingTool -> strings.aiCallingTool(state.serverName)
        AiActivityState.Generating -> strings.aiGenerating
    }
    var lastLabel by remember { mutableStateOf("") }
    if (label != null) lastLabel = label

    AnimatedVisibility(visible = label != null, enter = fadeIn(), exit = fadeOut()) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = MaterialTheme.shapes.medium,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(10.dp)) {
                    Text(
                        text = lastLabel,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    Spacer(Modifier.width(8.dp))
                    TypingDots()
                }
            }
        }
    }
}

/** Three dots, phase-shifted so they pulse in sequence rather than in unison -- the classic
 * "typing…" indicator shape, built from [rememberInfiniteTransition] rather than a GIF/Lottie
 * asset since this project has no animation-asset dependency to begin with. */
@Composable
private fun TypingDots() {
    val transition = rememberInfiniteTransition(label = "typing-dots")
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        repeat(3) { index ->
            val alpha by transition.animateFloat(
                initialValue = 0.3f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 600, easing = LinearEasing),
                    repeatMode = RepeatMode.Reverse,
                    initialStartOffset = StartOffset(index * 150),
                ),
                label = "dot-$index",
            )
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .alpha(alpha)
                    .background(MaterialTheme.colorScheme.onSecondaryContainer, CircleShape),
            )
        }
    }
}

/** Long-press (via [combinedClickable]'s `onLongClick`) copies the bubble's raw text to the
 * clipboard and asks [onCopied] to surface a "Nachricht kopiert" Snackbar -- the actual clipboard
 * write happens here since it needs [LocalClipboardManager], not up in [ChatSection]. A plain
 * tap is a no-op (`onClick = {}`), just enough to give the long-press its required sibling. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChatBubble(text: String, isUser: Boolean, onCopied: () -> Unit) {
    val strings = LocalStrings.current
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    var showMenu by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Box {
            Surface(
                color = if (isUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.combinedClickable(
                    onClick = {},
                    onLongClick = { showMenu = true },
                ),
            ) {
                Text(text, modifier = Modifier.padding(10.dp))
            }
            DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                DropdownMenuItem(
                    text = { Text(strings.copyAction) },
                    onClick = {
                        showMenu = false
                        clipboard.setText(AnnotatedString(text))
                        onCopied()
                    },
                )
                DropdownMenuItem(
                    text = { Text(strings.shareAction) },
                    onClick = {
                        showMenu = false
                        shareText(context, text, strings.shareAction)
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AssistantBubble(message: ChatItem.AssistantMessage, onCopied: () -> Unit, ttsController: TtsController? = null) {
    val strings = LocalStrings.current
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val parsed = remember(message.markdown) { message.markdown.splitReasoning() }
    var showReasoning by rememberSaveable(message.id) { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth()) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Box {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.combinedClickable(
                onClick = {},
                onLongClick = { showMenu = true },
            ),
        ) {
            Column(Modifier.padding(10.dp)) {
                if (ttsController != null && !message.isStreaming) {
                    val speakingId by ttsController.isSpeakingId.collectAsState()
                    val isSpeaking = speakingId == message.id
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        Text(
                            text = if (isSpeaking) "⏹️" else "🔊",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.combinedClickable(
                                onClick = { ttsController.speak(message.id, parsed.answer) },
                                onLongClick = {},
                            ),
                        )
                    }
                }
                if (parsed.reasoning != null) {
                    TextButton(onClick = { showReasoning = !showReasoning }) {
                        Text(if (showReasoning) strings.hideReasoning else strings.showReasoning(parsed.reasoningTokenEstimate))
                    }
                    if (showReasoning) {
                        Surface(
                            color = MaterialTheme.colorScheme.surface,
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                text = parsed.reasoning,
                                modifier = Modifier.padding(8.dp),
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                }
                MarkdownText(parsed.answer)
                if (message.isStreaming) {
                    Spacer(Modifier.height(4.dp))
                    Text("…", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
            DropdownMenuItem(
                text = { Text(strings.copyAction) },
                onClick = {
                    showMenu = false
                    clipboard.setText(AnnotatedString(message.markdown))
                    onCopied()
                },
            )
            DropdownMenuItem(
                text = { Text(strings.shareAction) },
                onClick = {
                    showMenu = false
                    shareText(context, message.markdown, strings.shareAction)
                },
            )
            DropdownMenuItem(
                text = { Text(strings.sharePdfAction) },
                onClick = {
                    showMenu = false
                    shareChatAnswerAsPdf(context, strings.pdfAnswerTitle, message.markdown, strings.sharePdfAction)
                },
            )
        }
        }
    }
    // Small status line under the bubble -- response duration, only once fully received (see
    // AgentEvent.Complete in GlucoSphereViewModel.sendMessage).
    formatDurationBadge(message.durationMillis)?.let { badge ->
        Text(
            text = badge,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, top = 2.dp),
        )
    }
    }
}

@Composable
private fun ToolActivityRow(item: ChatItem.ToolActivity) {
    Text(
        text = "⚙ ${item.toolName}: ${item.summary}",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun ConfirmationCard(item: ChatItem.PendingConfirmation, onConfirm: (ChatItem.PendingConfirmation, Boolean) -> Unit) {
    val strings = LocalStrings.current
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(item.description, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
            Row {
                Button(onClick = { onConfirm(item, true) }) { Text(strings.genericYes) }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = { onConfirm(item, false) }) { Text(strings.genericNo) }
            }
        }
    }
}
