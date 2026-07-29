package com.example.diabai.ui.theme

import androidx.compose.ui.graphics.Color
import com.example.diabai.data.McpServerConfig
import com.example.diabai.network.isRestApi

/** Fixed (not [colorSchemeFor]-derived) accent colors distinguishing a direct REST API from an
 * MCP server -- deliberately independent of the active [com.example.diabai.data.AppColorTheme],
 * same reasoning as the existing 🟢/🟡/🔴 connection-status colors in DataSourcesScreen: this is
 * about telling two *kinds of source* apart at a glance, not about the app's own color theme, so
 * it needs to stay recognizable no matter which of the 6 palettes is active. Blue for the direct
 * REST API, violet for MCP servers, per the "Blau für REST-APIs, Violett/Türkis für MCP-Server"
 * request. */
val RestApiAccentColor = Color(0xFF1976D2)
val McpServerAccentColor = Color(0xFF7B1FA2)

/** [RestApiAccentColor] or [McpServerAccentColor], whichever applies to [this]. */
val McpServerConfig.sourceTypeAccentColor: Color get() = if (isRestApi) RestApiAccentColor else McpServerAccentColor
