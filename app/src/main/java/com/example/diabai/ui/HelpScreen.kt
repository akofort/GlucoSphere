package com.example.diabai.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/**
 * "Hilfe & MCP-Anleitungen" (item 4) -- a static reference screen, no ViewModel state needed.
 * Every linked repository below was verified reachable via web search at the time this was
 * written (2026-07-26); MCP servers in particular are a fast-moving, community-maintained
 * ecosystem with no single official registry, so treat these as a starting point, not a
 * guarantee of current maintenance status -- always check a repo's own README/last-commit date
 * before trusting it with real credentials.
 */
@Composable
fun HelpScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val strings = LocalStrings.current
    val context = LocalContext.current
    fun openUrl(url: String) {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }

    SettingsScaffold(title = strings.helpMenuTitle, onBack = onBack, modifier = modifier) {
        Column {
            Text(strings.helpGeminiKeyTitle, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                text = strings.helpGeminiKeySteps,
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(4.dp))
            TextButton(onClick = { openUrl("https://aistudio.google.com/apikey") }) {
                Text(strings.helpOpenGoogleAiStudio)
            }
        }

        HorizontalDivider()

        Column {
            Text(strings.helpMcpSuggestionsTitle, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                text = strings.helpMcpSuggestionsHint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        McpLinkSection(
            title = "Nightscout MCP",
            description = strings.helpNightscoutDesc,
            url = "https://github.com/adminpb/Nightscout-MCP",
            onOpenUrl = ::openUrl,
        )

        McpLinkSection(
            title = "Glooko MCP",
            description = strings.helpGlookoDesc,
            url = null,
            onOpenUrl = ::openUrl,
        )

        McpLinkSection(
            title = "Withings MCP",
            description = strings.helpWithingsDesc,
            url = "https://github.com/akutishevsky/withings-mcp",
            additionalUrls = listOf(
                strings.helpWithingsAlt1 to "https://github.com/Schimmilab/withings-mcp-server",
                strings.helpWithingsAlt2 to "https://github.com/davidmosiah/withings-mcp",
                strings.helpWithingsAlt3 to "https://developer.withings.com/",
            ),
            onOpenUrl = ::openUrl,
        )

        McpLinkSection(
            title = "Feelfit MCP",
            description = strings.helpFeelfitDesc,
            url = null,
            additionalUrls = listOf(
                "FeelFit MCP Server (LobeHub)" to "https://lobehub.com/mcp/tecnologicachile-mcp-feelfit",
                "FeelFit MCP Server (Conare)" to "https://conare.ai/marketplace/mcp/feelfit",
            ),
            onOpenUrl = ::openUrl,
        )

        McpLinkSection(
            title = "Google Health MCP",
            description = strings.helpGoogleHealthDesc,
            url = "https://github.com/tachibanayu24/fitbit-googlehealth-mcp",
            additionalUrls = listOf(
                strings.helpGoogleHealthAlt to "https://github.com/davidmosiah/google-health-mcp",
            ),
            onOpenUrl = ::openUrl,
        )

        McpLinkSection(
            title = strings.helpStravaTitle,
            description = strings.helpStravaDesc,
            url = "https://github.com/r-huijts/strava-mcp",
            additionalUrls = listOf(
                "eddmann/strava-mcp" to "https://github.com/eddmann/strava-mcp",
                strings.helpStravaSettingsLink to "https://www.strava.com/settings/api",
            ),
            onOpenUrl = ::openUrl,
        )
    }
}

@Composable
private fun McpLinkSection(
    title: String,
    description: String,
    url: String?,
    additionalUrls: List<Pair<String, String>> = emptyList(),
    onOpenUrl: (String) -> Unit,
) {
    val strings = LocalStrings.current
    HorizontalDivider()
    Column {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            text = description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        url?.let { TextButton(onClick = { onOpenUrl(it) }) { Text(strings.helpOpenGithubRepo) } }
        additionalUrls.forEach { (label, linkUrl) ->
            TextButton(onClick = { onOpenUrl(linkUrl) }) { Text(label) }
        }
    }
}
