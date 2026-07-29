package com.example.diabai.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.diabai.data.AppColorTheme
import com.example.diabai.data.AppLanguage
import com.example.diabai.data.DEFAULT_SYSTEM_PROMPT
import com.example.diabai.data.LlmProviderType
import com.example.diabai.data.ONEPROVIDER_FREE_DAILY_REQUEST_LIMIT
import com.example.diabai.domain.EngineState
import com.example.diabai.ui.theme.swatchColorFor
import java.io.File

/** Top-level settings menu: navigates into the three sub-screens. */
@Composable
fun SettingsOverviewScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
    onOpenProfile: () -> Unit,
    onOpenLlmConfig: () -> Unit,
    onOpenDataSources: () -> Unit,
    onOpenSystemPrompt: () -> Unit,
    onOpenAbout: () -> Unit,
    onOpenBackup: () -> Unit,
    onOpenPerformanceLog: () -> Unit,
    onOpenHelp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val settings by viewModel.settings.collectAsState()
    val engineState by viewModel.engineState.collectAsState()
    val downloadProgress by viewModel.downloadProgress.collectAsState()
    val mcpConnectionStates by viewModel.mcpConnectionStates.collectAsState()
    val strings = LocalStrings.current

    // Was only ever reading the local-engine state, so a fully configured cloud provider
    // (a valid API key, no local model needed at all) still showed "Kein Modell konfiguriert".
    // Branch on the actually-active provider first, and only fall through to that string when
    // *it* has nothing configured either.
    val llmSubtitle = when (settings.llmProviderType) {
        LlmProviderType.LOCAL -> when {
            downloadProgress != null -> strings.settingsDownloadRunning
            engineState is EngineState.Ready -> strings.settingsLocalModelActive(File(settings.modelFilePath).name)
            engineState is EngineState.Error -> strings.settingsLoadError
            settings.modelFilePath.isNotBlank() -> strings.settingsLoadingModel
            else -> strings.settingsNoModelConfigured
        }
        LlmProviderType.GEMINI ->
            if (settings.geminiApiKey.isNotBlank()) strings.settingsApiActive(strings.llmProviderGemini) else strings.settingsNoModelConfigured
        LlmProviderType.CLAUDE ->
            if (settings.claudeApiKey.isNotBlank()) strings.settingsApiActive(strings.llmProviderClaude) else strings.settingsNoModelConfigured
        LlmProviderType.OPENAI ->
            if (settings.openAiApiKey.isNotBlank()) strings.settingsApiActive(strings.llmProviderOpenAi) else strings.settingsNoModelConfigured
        LlmProviderType.DEEPSEEK ->
            if (settings.deepseekApiKey.isNotBlank()) strings.settingsApiActive(strings.llmProviderDeepSeek) else strings.settingsNoModelConfigured
        LlmProviderType.ONEPROVIDER_FREE -> {
            val remaining = (ONEPROVIDER_FREE_DAILY_REQUEST_LIMIT - settings.oneProviderFreeRequestsToday).coerceAtLeast(0)
            strings.settingsOneProviderFreeActive(remaining, ONEPROVIDER_FREE_DAILY_REQUEST_LIMIT)
        }
    }

    val connectedServerCount = mcpConnectionStates.values.count { it == McpConnectionState.Connected }
    val dataSourcesSubtitle = when {
        settings.isPureChatMode -> strings.settingsPureChatModeSubtitle
        connectedServerCount > 0 -> strings.settingsMcpConnected(connectedServerCount, settings.mcpServers.size)
        else -> strings.settingsConfiguredNotConnected
    }

    val systemPromptSubtitle = if (settings.systemPrompt == DEFAULT_SYSTEM_PROMPT) strings.settingsPromptStandard else strings.settingsPromptCustomized

    val profileRoleSubtitle = when (settings.userRole) {
        com.example.diabai.data.UserRole.DIABETIKER -> strings.roleDiabetikerLabel
        com.example.diabai.data.UserRole.FACHPERSONAL -> strings.roleFachpersonalLabel
        com.example.diabai.data.UserRole.ANGEHOERIGE -> strings.roleAngehorigeLabel
    }

    SettingsScaffold(title = strings.settingsTitle, onBack = onBack, modifier = modifier) {
        Column {
            Text(strings.languageLabel, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AppLanguage.entries.forEach { language ->
                    FilterChip(
                        selected = settings.appLanguage == language,
                        onClick = { viewModel.saveAppLanguage(language) },
                        label = { Text(language.displayName) },
                    )
                }
            }
            Text(
                text = strings.languageHint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Column {
            Text(strings.appearanceSectionTitle, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            ColorThemePicker(selected = settings.colorTheme, onSelect = viewModel::saveColorTheme)
        }

        Column {
            SettingsMenuRow(
                title = strings.profileTitle,
                subtitle = profileRoleSubtitle,
                onClick = onOpenProfile,
            )
            HorizontalDivider()
            SettingsMenuRow(
                title = strings.llmConfigMenuTitle,
                subtitle = llmSubtitle,
                onClick = onOpenLlmConfig,
            )
            HorizontalDivider()
            SettingsMenuRow(
                title = strings.dataSourcesMenuTitle,
                subtitle = dataSourcesSubtitle,
                onClick = onOpenDataSources,
            )
            HorizontalDivider()
            SettingsMenuRow(
                title = strings.systemPromptMenuTitle,
                subtitle = systemPromptSubtitle,
                onClick = onOpenSystemPrompt,
            )
            HorizontalDivider()
            SettingsMenuRow(
                title = strings.backupMenuTitle,
                subtitle = strings.backupMenuSubtitle,
                onClick = onOpenBackup,
            )
            HorizontalDivider()
            SettingsMenuRow(
                title = strings.performanceLogMenuTitle,
                subtitle = strings.performanceLogMenuSubtitle,
                onClick = onOpenPerformanceLog,
            )
            HorizontalDivider()
            SettingsMenuRow(
                title = strings.helpMenuTitle,
                subtitle = strings.helpMenuSubtitle,
                onClick = onOpenHelp,
            )
            HorizontalDivider()
            // "Über GlucoSphere" steht bewusst als letzter Eintrag -- Versions-/Lizenz-/Datenschutz-
            // Infos gehören ans Ende des Menüs, nicht dazwischen.
            SettingsMenuRow(
                title = strings.aboutMenuTitle,
                subtitle = strings.aboutMenuSubtitle,
                onClick = onOpenAbout,
            )
            HorizontalDivider()
        }
    }
}

/** "Einstellungen -> Erscheinungsbild": one tappable color swatch per [AppColorTheme], each
 * previewing that theme's own primary color (see [swatchColorFor]) rather than a plain text
 * label/chip -- the whole point of a color picker is to see the color, not read its name. The
 * selected swatch gets a ring in the CURRENTLY ACTIVE theme's primary color (not the swatch's own
 * color), so the ring stays visible/legible regardless of which swatch it's marking. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColorThemePicker(selected: AppColorTheme, onSelect: (AppColorTheme) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AppColorTheme.entries.forEach { theme ->
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(72.dp)) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(swatchColorFor(theme))
                        .border(
                            width = if (theme == selected) 3.dp else 0.dp,
                            color = MaterialTheme.colorScheme.primary,
                            shape = CircleShape,
                        )
                        .selectable(selected = theme == selected, onClick = { onSelect(theme) }),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = theme.label,
                    style = MaterialTheme.typography.labelSmall,
                    textAlign = TextAlign.Center,
                    color = if (theme == selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SettingsMenuRow(title: String, subtitle: String, onClick: () -> Unit) {
    Surface(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("›", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
