package com.example.diabai.ui

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.example.diabai.data.DEBUG_LOG_MAX_ENTRIES
import com.example.diabai.data.DebugLogEntry
import com.example.diabai.data.LLM_REQUEST_LOG_MAX_ENTRIES
import com.example.diabai.data.LicenseTier
import com.example.diabai.data.LlmRequestLogEntry
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val logTimeFormat = DateTimeFormatter.ofPattern("dd.MM. HH:mm:ss").withZone(ZoneId.systemDefault())

/** Same idea as [buildPerformanceLogShareText], for [DebugLogEntry]'s chronological trace. */
fun buildDebugLogShareText(entries: List<DebugLogEntry>, strings: Strings): String {
    if (entries.isEmpty()) return strings.perfNoDebugEntriesShareText
    return buildString {
        appendLine(strings.perfDebugShareTextHeader(entries.size))
        appendLine()
        entries.forEach { entry ->
            appendLine("[${logTimeFormat.format(Instant.ofEpochMilli(entry.timestampMillis))}] ${entry.message}")
        }
    }.trim()
}

/** Plain-text rendering of the whole log for [android.content.Intent.ACTION_SEND] -- one line per
 * entry, newest first (matches [LlmRequestLogEntry]'s own storage order, see
 * `List<LlmRequestLogEntry>.addEntry`). */
fun buildPerformanceLogShareText(entries: List<LlmRequestLogEntry>, strings: Strings): String {
    if (entries.isEmpty()) return strings.perfNoEntriesShareText
    return buildString {
        appendLine(strings.perfShareTextHeader(entries.size))
        appendLine()
        entries.forEach { entry ->
            val time = logTimeFormat.format(Instant.ofEpochMilli(entry.timestampMillis))
            appendLine(
                "[$time] ${entry.providerType.label} · ${entry.model.ifBlank { "-" }} · ${entry.purpose} · " +
                    "${strings.perfTokensToolCalls(entry.promptTokens, entry.completionTokens, entry.toolCallCount)} · " +
                    "${"%.1f".format(entry.durationMillis / 1000.0)}s",
            )
            entry.error?.let { appendLine("    ${strings.genericErrorPrefix(it)}") }
        }
    }.trim()
}

/**
 * "Einstellungen -> Performance-Log" (Provider/Modell/Token/Anfrage-Menge/Dauer je Anfrage) --
 * lets a report like "die Übersicht dauert wieder 180s" actually be diagnosed on-device instead
 * of guessed at: every logged row shows exactly which provider/model answered, how many tokens it
 * used, how many tool calls it made, and how long it took, so a suspiciously slow row can be
 * matched back to e.g. a specific provider's outage or a wrongly-resolved model id. See
 * [com.example.diabai.data.LlmRequestLogEntry] for exactly what's recorded (never the local
 * on-device model, matching the existing token-usage tracking's own scope).
 */
@Composable
fun PerformanceLogScreen(viewModel: SettingsViewModel, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val strings = LocalStrings.current
    val entries by viewModel.performanceLog.collectAsState()
    val settings by viewModel.settings.collectAsState()
    val debugEntries by viewModel.debugLog.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        viewModel.loadPerformanceLog()
        viewModel.loadDebugLog()
    }

    SettingsScaffold(title = strings.performanceLogMenuTitle, onBack = onBack, modifier = modifier) {
        Column {
            Text(
                text = strings.perfHint(LLM_REQUEST_LOG_MAX_ENTRIES),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        val sendIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, buildPerformanceLogShareText(entries, strings))
                        }
                        context.startActivity(Intent.createChooser(sendIntent, strings.perfShareChooserTitle))
                    },
                    enabled = entries.isNotEmpty(),
                ) { Text(strings.shareAction) }
                OutlinedButton(onClick = { viewModel.clearPerformanceLog() }, enabled = entries.isNotEmpty()) {
                    Text(strings.perfClearLog)
                }
            }
        }

        if (entries.isEmpty()) {
            Text(
                text = strings.perfNoEntries,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                entries.forEach { entry -> PerformanceLogRow(entry, strings) }
            }
        }

        // Entwickler-Lizenz-only (item 1's license tiers) -- a chronological event trace is
        // considerably more verbose than the per-exchange rows above and, depending on what a
        // tool call actually returned, can end up echoing snippets of health data into it, so it's
        // both gated behind Developer and off by default rather than always recorded like the
        // Performance-Log above.
        if (settings.licenseTier == LicenseTier.DEVELOPER) {
            HorizontalDivider()
            Column {
                Text(strings.perfDebugLogTitle, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    text = strings.perfDebugLogHint(DEBUG_LOG_MAX_ENTRIES),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = settings.debugLoggingEnabled, onCheckedChange = { viewModel.saveDebugLoggingEnabled(it) })
                    Spacer(Modifier.width(8.dp))
                    Text(if (settings.debugLoggingEnabled) strings.genericEnabled else strings.genericDisabled)
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, buildDebugLogShareText(debugEntries, strings))
                            }
                            context.startActivity(Intent.createChooser(sendIntent, strings.perfShareDebugLogChooserTitle))
                        },
                        enabled = debugEntries.isNotEmpty(),
                    ) { Text(strings.shareAction) }
                    OutlinedButton(onClick = { viewModel.clearDebugLog() }, enabled = debugEntries.isNotEmpty()) {
                        Text(strings.perfClearLog)
                    }
                }
                Spacer(Modifier.height(8.dp))
                if (debugEntries.isEmpty()) {
                    Text(
                        text = strings.perfNoDebugEntries,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        debugEntries.forEach { entry ->
                            Text(
                                text = "[${logTimeFormat.format(Instant.ofEpochMilli(entry.timestampMillis))}] ${entry.message}",
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PerformanceLogRow(entry: LlmRequestLogEntry, strings: Strings) {
    Surface(shape = MaterialTheme.shapes.small, tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    text = "${entry.providerType.label} · ${entry.model.ifBlank { "-" }}",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "%.1fs".format(entry.durationMillis / 1000.0),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Text(
                text = "${logTimeFormat.format(Instant.ofEpochMilli(entry.timestampMillis))} · ${entry.purpose} · " +
                    strings.perfTokensToolCalls(entry.promptTokens, entry.completionTokens, entry.toolCallCount),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            entry.error?.let {
                Text(text = strings.genericErrorPrefix(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}
