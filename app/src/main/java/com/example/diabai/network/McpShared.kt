package com.example.diabai.network

import com.example.diabai.data.McpTransport
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal const val MCP_PROTOCOL_VERSION = "2024-11-05"
internal const val MCP_CLIENT_NAME = "GlucoSphere"
internal const val MCP_CLIENT_VERSION = "1.0"

private val mcpLogTimeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.GERMANY)

internal fun mcpLogLine(message: String): String = "[${mcpLogTimeFormat.format(Date())}] $message"

/** Null unless this string is a well-formed absolute URL with a real host (e.g.
 * `https://mcp-glooko.kofort.de/sse`) -- used to reject blank/relative/malformed server
 * addresses before they can fall back to an engine default like `http://localhost/`. */
internal fun String.toAbsoluteMcpOrigin(): String? {
    val uri = runCatching { URI(this) }.getOrNull() ?: return null
    return if (uri.isAbsolute && !uri.host.isNullOrBlank()) this else null
}

internal val JsonPrimitive.longOrNull: Long?
    get() = content.toLongOrNull()

/**
 * One live (or connectable) MCP server session, independent of which wire transport it speaks.
 * [McpSseClient] implements the legacy "GET SSE stream + endpoint event + POST JSON-RPC"
 * transport; [McpStreamableHttpClient] implements the current MCP spec's single-POST-endpoint
 * "Streamable HTTP" transport (server may answer a request with plain JSON or a one-shot SSE
 * stream). [McpServerPool] and the settings "Verbindung testen" flow both talk to whichever one
 * a given [McpServerConfig][com.example.diabai.data.McpServerConfig] is configured for through
 * this common surface.
 */
interface McpConnection {
    val isConnected: Boolean

    /** Line-by-line trace of the handshake, for the "Verbindungs-Log anzeigen" diagnostic view
     * -- silent by default (no-op) so normal (non-test) connections pay nothing for it. */
    var onLog: (String) -> Unit

    suspend fun connect(
        serverUrl: String,
        authHeaderName: String? = null,
        authHeaderValue: String? = null,
        connectTimeoutMs: Long = 10_000,
    ): Result<Unit>

    /** Queries the MCP server for its available tools (e.g. get_cgm_history). */
    suspend fun listTools(): Result<List<McpTool>>

    /** Queries the MCP server for its advertised resources (`resources/list`) -- unlike
     * [listTools], this is an *optional* MCP capability many servers never implement at all, so
     * the default here is a quiet empty success rather than every existing caller needing to
     * handle "not supported" as a special kind of failure. Discovery Modus (see
     * [com.example.diabai.domain.discovery.DiscoveryService]) is the only caller that cares about
     * this; overridden by both real transports to actually attempt the call. */
    suspend fun listResources(): Result<List<McpResource>> = Result.success(emptyList())

    /** Invokes a tool by name with the given JSON arguments and returns its result content. */
    suspend fun callTool(name: String, arguments: JsonObject = JsonObject(emptyMap())): Result<McpToolCallResult>

    fun disconnect()
    fun close()
}

/** Builds the right [McpConnection] implementation for this transport choice. */
fun McpTransport.newMcpConnection(): McpConnection = when (this) {
    McpTransport.SSE -> McpSseClient()
    McpTransport.STREAMABLE_HTTP -> McpStreamableHttpClient()
}
