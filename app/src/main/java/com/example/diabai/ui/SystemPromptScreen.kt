package com.example.diabai.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.diabai.data.DEFAULT_SYSTEM_PROMPT
import com.example.diabai.data.LicenseTier

/** Sub-menu: personal name for direct address, and the persona/behavior system prompt template.
 *
 * System-Prompt-Schutzschicht (item 2): the protected base [com.example.diabai.data.AppSettings.systemPrompt]
 * is only shown/editable at all under [LicenseTier.DEVELOPER] -- every other tier only ever sees
 * the "Zusätzliche Instruktionen" field, which dynamically appends onto whatever the (hidden) base
 * prompt currently is (see [com.example.diabai.data.AppSettings.personalizedSystemPrompt]). This
 * is the one screen in the app where [SettingsViewModel.settings]'s [LicenseTier] genuinely gates
 * a whole UI section, not just a daily-usage number. */
@Composable
fun SystemPromptScreen(viewModel: SettingsViewModel, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val strings = LocalStrings.current
    val settings by viewModel.settings.collectAsState()
    var name by remember(settings.userName) { mutableStateOf(settings.userName) }
    var draft by remember(settings.systemPrompt) { mutableStateOf(settings.systemPrompt) }
    val isNameDirty = name != settings.userName
    val isPromptDirty = draft != settings.systemPrompt
    val isDefault = draft == DEFAULT_SYSTEM_PROMPT
    val isDeveloper = settings.licenseTier == LicenseTier.DEVELOPER

    var additionalDraft by remember(settings.additionalInstructions) { mutableStateOf(settings.additionalInstructions) }
    val isAdditionalDirty = additionalDraft != settings.additionalInstructions
    var additionalExpanded by rememberSaveable { mutableStateOf(true) }

    SettingsScaffold(title = strings.systemPromptMenuTitle, onBack = onBack, modifier = modifier) {
        Text(strings.spPersonalAddressTitle, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(strings.spNameLabel) },
            placeholder = { Text(strings.spNamePlaceholder) },
            singleLine = true,
        )
        Text(
            text = strings.spNameHint,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        Button(onClick = { viewModel.saveUserName(name.trim()) }, enabled = isNameDirty) {
            Text(strings.spSaveName)
        }

        HorizontalDivider()

        if (isDeveloper) {
            Text(
                text = strings.spBasePromptDevHint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier.fillMaxWidth().heightIn(min = 320.dp),
                label = { Text(strings.spSystemPromptLabel) },
                singleLine = false,
            )

            if (isDefault) {
                Text(strings.spMatchesDefault, style = MaterialTheme.typography.bodySmall)
            } else if (isPromptDirty) {
                Text(strings.spUnsavedChanges, style = MaterialTheme.typography.bodySmall)
            }

            Row {
                Button(onClick = { viewModel.saveSystemPrompt(draft) }, enabled = isPromptDirty) {
                    Text(strings.genericSave)
                }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = { draft = DEFAULT_SYSTEM_PROMPT }, enabled = !isDefault) {
                    Text(strings.spResetToDefault)
                }
            }

            HorizontalDivider()
        }

        // Zusätzliche Instruktionen (item 2): the one prompt-customization surface every tier can
        // use -- for non-Entwickler tiers, this is ALL of the System-Prompt screen they see below
        // the persönliche-Ansprache section above; for Entwickler it's an additional, appended
        // layer on top of the base prompt they can already edit directly above.
        Text(
            text = if (isDeveloper) strings.spAdditionalInstructionsTitle else strings.spInstructionsTitle,
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = if (isDeveloper) strings.spAdditionalDevHint else strings.spAdditionalNonDevHint,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        if (!isDeveloper) {
            // Collapsible per the requirement ("einklappbares Textfeld") -- for Entwickler this
            // field is short/secondary enough to just always show, so only User/Test/Free get the
            // toggle at all.
            TextButton(onClick = { additionalExpanded = !additionalExpanded }) {
                Text(if (additionalExpanded) strings.spCollapseInstructions else strings.spEditInstructions)
            }
        }
        if (isDeveloper || additionalExpanded) {
            OutlinedTextField(
                value = additionalDraft,
                onValueChange = { additionalDraft = it },
                modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
                label = { Text(strings.spAdditionalInstructionsTitle) },
                placeholder = { Text(strings.spAdditionalInstructionsPlaceholder) },
                singleLine = false,
            )
            Spacer(Modifier.height(4.dp))
            Button(onClick = { viewModel.saveAdditionalInstructions(additionalDraft) }, enabled = isAdditionalDirty) {
                Text(strings.genericSave)
            }
        }
    }
}
