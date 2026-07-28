package com.example.diabai.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.diabai.data.UserRole

/**
 * "Einstellungen -> Profil / Benutzer": who's actually using the app -- drives the system
 * prompt's tone/focus (see [com.example.diabai.data.AppSettings.personalizedSystemPrompt]'s
 * `withUserRole`) so the same underlying data (Blutzuckerwerte, TIR, ...) is explained
 * differently to a diabetic managing their own values, a clinician, or a family member without
 * medical background. Persisted immediately on tap (no separate "Speichern" gate) -- same
 * "Deutsch/English" pattern as the language toggle in Einstellungen, since there's no credential
 * to test first.
 */
@Composable
fun ProfileScreen(viewModel: SettingsViewModel, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val strings = LocalStrings.current
    val settings by viewModel.settings.collectAsState()

    SettingsScaffold(title = strings.profileTitle, onBack = onBack, modifier = modifier) {
        Column {
            Text(strings.profileUserTypeTitle, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                text = strings.profileHint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            UserRole.entries.forEach { role ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    RadioButton(
                        selected = settings.userRole == role,
                        onClick = { viewModel.saveUserRole(role) },
                    )
                    Column {
                        Text(localizedRoleLabel(role, strings), style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = localizedRoleDescription(role, strings),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
            }
        }
    }
}

/** UI-only localization for [UserRole] -- does NOT touch [UserRole.label]/[UserRole.description]
 * themselves, which stay German (used to build the German-only role-specific system-prompt block,
 * see [com.example.diabai.data.rolePromptFor]). */
private fun localizedRoleLabel(role: UserRole, strings: Strings): String = when (role) {
    UserRole.DIABETIKER -> strings.roleDiabetikerLabel
    UserRole.FACHPERSONAL -> strings.roleFachpersonalLabel
    UserRole.ANGEHOERIGE -> strings.roleAngehorigeLabel
}

private fun localizedRoleDescription(role: UserRole, strings: Strings): String = when (role) {
    UserRole.DIABETIKER -> strings.roleDiabetikerDesc
    UserRole.FACHPERSONAL -> strings.roleFachpersonalDesc
    UserRole.ANGEHOERIGE -> strings.roleAngehorigeDesc
}
