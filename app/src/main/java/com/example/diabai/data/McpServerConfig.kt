package com.example.diabai.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

const val MAX_MCP_SERVERS = 5

/** OAuth2 (Authorization Code + PKCE, via Chrome Custom Tabs -- see `domain/OAuth2.kt`) client
 * config and resulting tokens for one [McpServerConfig], only meaningful when its [AuthMethod] is
 * [AuthMethod.OAUTH2] (e.g. a Withings MCP server). Persisted the same way as every other MCP
 * credential in this app -- inline in [McpServerConfig], through the same DataStore-backed
 * [SettingsRepository.saveMcpServers] -- rather than a separate encrypted store, for consistency
 * with the manually-entered bearer tokens/API secrets already stored there unencrypted. */
@Serializable
data class OAuth2Config(
    val authorizationEndpoint: String = "",
    val tokenEndpoint: String = "",
    val clientId: String = "",
    val scope: String = "",
    val accessToken: String = "",
    val refreshToken: String = "",
    val expiresAtEpochMillis: Long = 0L,
) {
    val isLoggedIn: Boolean get() = accessToken.isNotBlank()
}

@Serializable
data class McpServerConfig(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val url: String = "",
    val authMethod: AuthMethod = AuthMethod.NONE,
    val token: String = "",
    val oauth2: OAuth2Config = OAuth2Config(),
    val category: McpDataCategory = McpDataCategory.GLUCOSE_TREATMENTS,
    val transport: McpTransport = McpTransport.STREAMABLE_HTTP,
    /** When true, tool calls routed to this server skip the interactive "Soll das Tool ...
     * ausgeführt werden?" confirmation in chat entirely and run immediately (background
     * fetches like the Übersicht tab already never ask for confirmation regardless). */
    val autoApprove: Boolean = false,
    /** Per-tool auto-approve overrides for when [autoApprove] itself is off -- set from the
     * expanded server card's tool list ("Einzelliste der Tools mit Auto-Approve Toggles"). */
    val autoApprovedTools: Set<String> = emptySet(),
    /** Whether this data source is actually queried by the dashboard/chat. Defaults to true so
     * decoding an already-persisted config saved before this field existed (no "enabled" key in
     * its stored JSON) keeps behaving exactly as before -- queried, same as always -- rather than
     * silently going dark on every existing install. A brand-new server (via "+ MCP-Server
     * hinzufügen") is explicitly constructed with `enabled = false` instead of relying on this
     * default, so it starts out inert until a connection test matching its saved fields has
     * succeeded (enforced in DataSourcesScreen, not here) -- switching it back off afterwards is
     * always allowed, no test required. Independent of the live MCP connection state: a server
     * can be "enabled" (the user wants it queried) but momentarily unreachable, or "disabled"
     * (opted out) while still fully connected. */
    val enabled: Boolean = true,
) {
    val displayName: String get() = name.ifBlank { url.ifBlank { "MCP-Server" } }
}

/** The (name, value) HTTP header to actually authenticate this server's requests with --
 * [AuthMethod.buildHeader] handles the manually-entered-token methods; [AuthMethod.OAUTH2] has
 * no such single field, its header is the stored OAuth2 access token instead. */
fun McpServerConfig.resolveAuthHeader(): Pair<String, String>? = when (authMethod) {
    AuthMethod.OAUTH2 -> oauth2.accessToken.takeIf { it.isNotBlank() }?.let { "Authorization" to "Bearer $it" }
    else -> authMethod.buildHeader(token)
}

private val serverListJson = Json { ignoreUnknownKeys = true }

fun List<McpServerConfig>.encodeToJson(): String = serverListJson.encodeToString(this)

fun String.decodeMcpServerConfigs(): List<McpServerConfig> =
    if (isBlank()) {
        emptyList()
    } else {
        runCatching { serverListJson.decodeFromString<List<McpServerConfig>>(this) }.getOrElse { emptyList() }
    }
