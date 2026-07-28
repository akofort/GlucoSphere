package com.example.diabai.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import java.time.LocalDate

/**
 * "Einstellungen -> Backup & Konfiguration" (item 2): full export/import of the app's
 * configuration -- see [com.example.diabai.data.SettingsBackup] for exactly what's included, and
 * [com.example.diabai.domain.SettingsBackupFile]/[com.example.diabai.domain.BackupCrypto] for the
 * optional AES-256-GCM encryption. Uses the platform's own document picker
 * (`ActivityResultContracts.CreateDocument`/`OpenDocument`) rather than a hand-rolled file
 * chooser, so the exported file can be dropped straight into any location the system picker
 * offers -- local storage or a cloud-storage provider the user already has installed.
 */
@Composable
fun BackupScreen(viewModel: SettingsViewModel, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val strings = LocalStrings.current
    val context = LocalContext.current
    val backupExportError by viewModel.backupExportError.collectAsState()
    val backupImportState by viewModel.backupImportState.collectAsState()

    var encryptExport by rememberSaveable { mutableStateOf(false) }
    var exportPassword by rememberSaveable { mutableStateOf("") }
    var importPasswordInput by rememberSaveable { mutableStateOf("") }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri: Uri? ->
        if (uri != null) viewModel.exportSettings(context, uri, if (encryptExport) exportPassword else null)
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) viewModel.importSettings(context, uri)
    }
    // A fresh suggested filename every time this screen recomposes is unnecessary churn -- just
    // once, when the screen is first shown, is enough for "heutiges Datum im Dateinamen".
    val suggestedFileName = remember { "diabai-backup-${LocalDate.now()}.diabai" }

    SettingsScaffold(title = strings.backupMenuTitle, onBack = onBack, modifier = modifier) {
        Column {
            Text(strings.backupExportTitle, style = MaterialTheme.typography.titleSmall)
            Text(
                text = strings.backupExportHint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
            )
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Checkbox(checked = encryptExport, onCheckedChange = { encryptExport = it })
                Text(strings.backupEncryptCheckbox, style = MaterialTheme.typography.bodyMedium)
            }
            if (encryptExport) {
                OutlinedTextField(
                    value = exportPassword,
                    onValueChange = { exportPassword = it },
                    label = { Text(strings.backupPasswordLabel) },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                )
            }
            Button(
                onClick = { exportLauncher.launch(suggestedFileName) },
                enabled = !encryptExport || exportPassword.isNotBlank(),
            ) { Text(strings.backupExportTitle) }
            backupExportError?.let { message ->
                Text(
                    text = message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }

        HorizontalDivider()

        Column {
            Text(strings.backupImportTitle, style = MaterialTheme.typography.titleSmall)
            Text(
                text = strings.backupImportHint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
            )
            OutlinedButton(
                onClick = { importLauncher.launch(arrayOf("*/*")) },
                enabled = backupImportState !is BackupImportState.Importing,
            ) { Text(strings.backupImportTitle) }
            Spacer(Modifier.height(4.dp))
            when (val state = backupImportState) {
                BackupImportState.Importing -> Text(strings.backupImporting, style = MaterialTheme.typography.bodySmall)
                BackupImportState.Success -> Text(
                    text = strings.backupImportSuccess,
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodySmall,
                )
                BackupImportState.WrongPassword -> Text(
                    text = strings.backupWrongPassword,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
                is BackupImportState.Error -> Text(
                    text = state.message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
                BackupImportState.Idle, BackupImportState.PasswordRequired -> Unit
            }
        }
    }

    // Shown for BOTH PasswordRequired (first attempt) and WrongPassword (retry) -- a wrong
    // password doesn't drop SettingsViewModel's held pendingImportBytes, so the exact same dialog
    // can just be re-shown with a hint appended, rather than needing the user to re-pick the file.
    if (backupImportState == BackupImportState.PasswordRequired || backupImportState == BackupImportState.WrongPassword) {
        val isRetry = backupImportState == BackupImportState.WrongPassword
        AlertDialog(
            onDismissRequest = { viewModel.consumeBackupImportState(); importPasswordInput = "" },
            title = { Text(strings.backupPasswordRequiredTitle) },
            text = {
                Column {
                    Text(
                        text = if (isRetry) strings.backupWrongPasswordRetry else strings.backupEncryptedFileHint,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (isRetry) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = importPasswordInput,
                        onValueChange = { importPasswordInput = it },
                        label = { Text(strings.backupPasswordLabel) },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.importSettingsWithPassword(importPasswordInput)
                        importPasswordInput = ""
                    },
                    enabled = importPasswordInput.isNotBlank(),
                ) { Text(strings.backupDecrypt) }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.consumeBackupImportState(); importPasswordInput = "" }) { Text(strings.deleteHistoryDismiss) }
            },
        )
    }
}
