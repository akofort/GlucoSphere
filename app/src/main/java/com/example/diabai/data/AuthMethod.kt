package com.example.diabai.data

import kotlinx.serialization.Serializable

/** Shared by the MCP and Nightscout REST API data sources. [OAUTH2] is MCP-only (see
 * [McpServerConfig.oauth2]) -- Nightscout's own REST API has no OAuth2 flow, so the Nightscout
 * settings section deliberately never offers this choice (see `AuthMethodSelector`'s
 * `showOAuth2` parameter). */
@Serializable
enum class AuthMethod { NONE, BEARER_TOKEN, API_SECRET_HEADER, OAUTH2 }

/** The (name, value) HTTP header to send for this method, or null if there's nothing to send.
 * Not meaningful for [AuthMethod.OAUTH2] -- that one has no single manually-entered token, its
 * header comes from [McpServerConfig.resolveAuthHeader] instead (the OAuth2 access token). */
fun AuthMethod.buildHeader(token: String): Pair<String, String>? = when (this) {
    AuthMethod.NONE -> null
    AuthMethod.BEARER_TOKEN -> token.takeIf { it.isNotBlank() }?.let { "Authorization" to "Bearer $it" }
    AuthMethod.API_SECRET_HEADER -> token.takeIf { it.isNotBlank() }?.let { "api-secret" to it }
    AuthMethod.OAUTH2 -> null
}
