package com.example.diabai.data

import kotlinx.serialization.Serializable

/**
 * Which MCP wire protocol a server speaks. Real-world servers are split between the two --
 * defaulting new entries to [STREAMABLE_HTTP] since it's the current MCP spec transport (a
 * single POST endpoint, optionally answering with a one-shot SSE stream instead of a plain JSON
 * body) while [SSE] (the older "open a GET SSE stream, get an `endpoint` event, POST JSON-RPC
 * there" transport) stays selectable for servers that haven't moved on yet.
 */
@Serializable
enum class McpTransport(val label: String) {
    STREAMABLE_HTTP("HTTP (Streamable, empfohlen)"),
    SSE("HTTP+SSE (klassisch)"),
}
