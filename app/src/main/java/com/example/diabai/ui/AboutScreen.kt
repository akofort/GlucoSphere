package com.example.diabai.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.example.diabai.BuildConfig
import com.example.diabai.R
import com.example.diabai.data.estimatedCostEuros
import com.example.diabai.data.todayUsageDateKey

/** Bottom-of-settings "Über GlucoSphere" screen: version/build metadata, the currently active LLM,
 * the mandatory medical disclaimer, and dialogs for licenses/privacy -- none of this changes at
 * runtime except the active-LLM line, so it's cheap to read straight from [BuildConfig]/settings
 * rather than needing its own ViewModel state. */
@Composable
fun AboutScreen(viewModel: SettingsViewModel, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val strings = LocalStrings.current
    val settings by viewModel.settings.collectAsState()
    var showLicenses by remember { mutableStateOf(false) }
    var showPrivacy by remember { mutableStateOf(false) }
    var showDisclaimer by remember { mutableStateOf(false) }

    val context = LocalContext.current
    // The launcher icon is an adaptive-icon XML (mipmap-anydpi-v26/ic_launcher.xml, layered
    // foreground+background), not a plain VectorDrawable or raster PNG/WEBP -- Compose's
    // painterResource() only supports the latter two and crashes on the former
    // ("Only VectorDrawables and rasterized asset types are supported"). Resolving it via
    // PackageManager.getApplicationIcon() + Drawable.toBitmap() flattens the adaptive icon down
    // to a plain bitmap first, the same way the launcher itself renders it.
    val appIconBitmap = remember {
        runCatching { context.packageManager.getApplicationIcon(context.packageName).toBitmap() }.getOrNull()
    }

    // Lizenzmodell (item 1): the key field itself is always editable (a fresh install has no key
    // at all, so gating the FIELD behind a tier it doesn't have yet would make it impossible to
    // ever enter one) -- only the RESULTING tier/behavior elsewhere in the app is gated.
    var licenseKeyDraft by remember(settings.licenseKey) { mutableStateOf(settings.licenseKey) }
    val isLicenseKeyDirty = licenseKeyDraft != settings.licenseKey

    SettingsScaffold(title = strings.aboutMenuTitle, onBack = onBack, modifier = modifier) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
            if (appIconBitmap != null) {
                Surface(shape = RoundedCornerShape(20.dp), modifier = Modifier.size(88.dp)) {
                    Image(
                        bitmap = appIconBitmap.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.clip(RoundedCornerShape(20.dp)),
                    )
                }
            }
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(top = 12.dp),
            )
            Text(
                text = "Version ${BuildConfig.VERSION_NAME} (Build ${BuildConfig.VERSION_CODE})",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = strings.aboutBuildLine(if (BuildConfig.DEBUG) "Debug" else "Release", BuildConfig.BUILD_TIME),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        HorizontalDivider()

        Column {
            Text(strings.aboutActiveSystemLlm, style = MaterialTheme.typography.titleSmall)
            Text(
                text = settings.activeLlmLabel,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        HorizontalDivider()

        // Lizenzmodell (item 1): current tier + remaining daily time (Test/Free only -- User/
        // Entwickler have no cap, see AppSettings.remainingDailyUsageMillis) + the key-entry field.
        Column {
            Text(strings.aboutLicenseTitle, style = MaterialTheme.typography.titleSmall)
            Text(
                text = settings.licenseTier.label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            settings.remainingDailyUsageMillis?.let { remainingMillis ->
                val remainingMinutes = (remainingMillis / 60_000L).coerceAtLeast(0L)
                Text(
                    text = if (settings.isDailyUsageLimitReached) {
                        strings.aboutDailyLimitReached
                    } else {
                        strings.aboutMinutesRemaining(remainingMinutes)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (settings.isDailyUsageLimitReached) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = licenseKeyDraft,
                onValueChange = { licenseKeyDraft = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(strings.aboutLicenseKeyLabel) },
                placeholder = { Text(strings.aboutLicenseKeyPlaceholder) },
                singleLine = true,
            )
            Spacer(Modifier.height(4.dp))
            TextButton(
                onClick = { viewModel.saveLicenseKey(licenseKeyDraft) },
                enabled = isLicenseKeyDirty,
            ) { Text(strings.aboutSaveLicenseKey) }
        }

        HorizontalDivider()

        Column {
            Text(strings.aboutUsageTitle, style = MaterialTheme.typography.titleSmall)
            Text(
                text = strings.aboutUsageHint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp, bottom = 6.dp),
            )
            val todayUsage = settings.llmUsage.firstOrNull { it.dateKey == todayUsageDateKey() }
            val todayTokens = (todayUsage?.promptTokens ?: 0) + (todayUsage?.completionTokens ?: 0)
            val totalTokens30d = settings.llmUsage.sumOf { it.promptTokens + it.completionTokens }
            val estimatedCost = settings.llmUsage.estimatedCostEuros()
            Text(strings.aboutTodayTokens(todayTokens), style = MaterialTheme.typography.bodyMedium)
            Text(strings.aboutLast30DaysTokens(totalTokens30d), style = MaterialTheme.typography.bodyMedium)
            Text(
                text = strings.aboutEstimatedCost("%.3f".format(estimatedCost)),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = strings.aboutCostDisclaimer,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            TextButton(onClick = { viewModel.resetLlmUsage() }) { Text(strings.aboutResetUsage) }
        }

        HorizontalDivider()

        Column {
            Text(strings.aboutDisclaimerTitle, style = MaterialTheme.typography.titleSmall)
            Text(
                text = strings.aboutDisclaimerText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        HorizontalDivider()

        Column {
            TextButton(onClick = { showLicenses = true }) { Text(strings.aboutOpenSourceLicenses) }
            TextButton(onClick = { showPrivacy = true }) { Text(strings.aboutPrivacyNotice) }
        }

        HorizontalDivider()

        // Copyright-Hinweis & Lizenz-Anzeige (item 3): the very bottom of the screen -- a plain,
        // unobtrusive footer rather than another expandable card, since neither line needs more
        // than a glance. The disclaimer line here is the exact short wording from the
        // requirement; "Haftungsausschluss anzeigen" re-opens the full DISCLAIMER_TEXT already
        // shown further up this screen (see the "Medizinischer Haftungsausschluss" card) via the
        // same InfoDialog, rather than duplicating that text a second time.
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
            Text(
                text = strings.aboutCopyright,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = strings.aboutFooterDisclaimer,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = { showDisclaimer = true }) { Text(strings.aboutShowDisclaimer) }
        }
    }

    if (showLicenses) {
        InfoDialog(title = strings.aboutOpenSourceLicenses, text = strings.aboutLicensesText, onDismiss = { showLicenses = false })
    }
    if (showPrivacy) {
        InfoDialog(title = strings.aboutPrivacyDialogTitle, text = strings.aboutPrivacyText, onDismiss = { showPrivacy = false })
    }
    if (showDisclaimer) {
        InfoDialog(title = strings.aboutDisclaimerTitle, text = strings.aboutDisclaimerText, onDismiss = { showDisclaimer = false })
    }
}

@Composable
private fun InfoDialog(title: String, text: String, onDismiss: () -> Unit) {
    val strings = LocalStrings.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(text, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(strings.genericClose) } },
    )
}
