package com.example.diabai.network

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/** JSON-RPC 2.0 envelope types used by both MCP transports. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class JsonRpcRequest(
    // Without @EncodeDefault, kotlinx.serialization omits this field entirely from the
    // encoded JSON because it still equals its default -- silently sending every request
    // without "jsonrpc" at all, which a strict (e.g. pydantic-validated) server rejects
    // outright as "Field required" rather than as a jsonrpc *version* mismatch. `id`/`params`
    // deliberately keep the default Json behavior (omitted when null): a JSON-RPC
    // notification is a request with the "id" member absent, not present-and-null.
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) val jsonrpc: String = "2.0",
    val id: JsonElement? = null,
    val method: String,
    val params: JsonElement? = null,
)

@Serializable
data class JsonRpcResponse(
    val jsonrpc: String = "2.0",
    val id: JsonElement? = null,
    val result: JsonElement? = null,
    val error: JsonRpcError? = null,
)

@Serializable
data class JsonRpcError(
    val code: Int,
    val message: String,
    val data: JsonElement? = null,
)

class McpException(val code: Int, message: String) : Exception(message)

/** A tool advertised by an MCP server (e.g. get_cgm_history). */
@Serializable
data class McpTool(
    val name: String,
    val description: String? = null,
    val inputSchema: JsonElement? = null,
)

@Serializable
data class McpToolsListResult(
    val tools: List<McpTool> = emptyList(),
    val nextCursor: String? = null,
)

/** A resource advertised by an MCP server via `resources/list` -- optional MCP capability, only
 * some servers implement it (see [McpConnection.listResources]'s doc comment). Used by the
 * Discovery Modus (item 1 of the "Discovery Modus" feature) to enrich [com.example.diabai.domain.discovery.DiscoveredSchema]
 * beyond just tools, when a server happens to expose it. */
@Serializable
data class McpResource(
    val uri: String,
    val name: String? = null,
    val description: String? = null,
    val mimeType: String? = null,
)

@Serializable
data class McpResourcesListResult(
    val resources: List<McpResource> = emptyList(),
    val nextCursor: String? = null,
)

@Serializable
data class McpContent(
    val type: String,
    val text: String? = null,
    val data: String? = null,
    val mimeType: String? = null,
)

@Serializable
data class McpToolCallResult(
    val content: List<McpContent> = emptyList(),
    val isError: Boolean = false,
)

@Serializable
data class McpServerInfo(
    val name: String,
    val version: String,
)

@Serializable
data class McpInitializeResult(
    val protocolVersion: String,
    @SerialName("serverInfo") val serverInfo: McpServerInfo? = null,
)
