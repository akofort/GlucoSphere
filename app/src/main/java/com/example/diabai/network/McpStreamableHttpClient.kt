package com.example.diabai.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.put
import java.util.concurrent.atomic.AtomicLong

private const val MCP_SESSION_HEADER = "Mcp-Session-Id"

/**
 * MCP client over the current spec's "Streamable HTTP" transport: every JSON-RPC request/
 * notification is a single POST to the one configured endpoint URL (no separate SSE-discovered
 * endpoint like the legacy transport). The server answers either with a plain JSON body, or --
 * if it wants to stream (progress, multi-part results) -- with a `text/event-stream` body
 * carrying one or more JSON-RPC messages, of which the one matching the request's id is what we
 * return. If the `initialize` response carries an [MCP_SESSION_HEADER], it's echoed back on
 * every later request as the spec requires. See [McpSseClient] for the older transport.
 */
class McpStreamableHttpClient(
    private val json: Json = Json { ignoreUnknownKeys = true },
) : McpConnection {
    private val httpClient = HttpClient(OkHttp) {
        // requestTimeoutMillis kept at 60s deliberately, NOT tightened to the 15s the app-wide
        // "strict Netzwerk-Layer timeout" request asked for -- multi-day CGM history (7/30-Tage
        // range) can genuinely take a while for the server to query (see the original comment
        // this replaced: 30s was already known to trip on those, not just on real outages).
        // connectTimeoutMillis/socketTimeoutMillis ARE tightened as asked: connect establishment
        // has no legitimate reason to need more than 10s regardless of how slow the eventual
        // query is, and socketTimeoutMillis (per-read-chunk gap, not total duration) was
        // previously unset -- meaning OkHttp's own hardcoded 10s default silently governed it
        // instead of the intended 60s budget (identical bug to the OpenRouter socket-timeout fix
        // in LLMProviderManager -- Ktor's HttpTimeout plugin only overrides OkHttp's built-in
        // defaults when connectTimeoutMillis/socketTimeoutMillis are ALSO set explicitly).
        install(HttpTimeout) {
            requestTimeoutMillis = 60_000
            connectTimeoutMillis = 10_000
            socketTimeoutMillis = 60_000
        }
    }

    private val nextId = AtomicLong(1)
    private val requestLock = Mutex()

    private var endpointUrl: String? = null
    private var authHeaderName: String? = null
    private var authHeaderValue: String? = null
    private var sessionId: String? = null
    private var connected = false

    override var onLog: (String) -> Unit = {}

    private fun log(message: String) {
        onLog(mcpLogLine(message))
    }

    override val isConnected: Boolean get() = connected

    override suspend fun connect(
        serverUrl: String,
        authHeaderName: String?,
        authHeaderValue: String?,
        connectTimeoutMs: Long,
    ): Result<Unit> = runCatching {
        check(!connected) { "Already connected; call disconnect() first" }
        // Same defensive validation as McpSseClient -- an origin-less URL must fail loudly
        // here instead of silently degrading into a request against http://localhost/.
        serverUrl.toAbsoluteMcpOrigin()
            ?: run {
                log("Ungültige URL: \"$serverUrl\" ist nicht absolut (Schema/Host fehlt)")
                throw IllegalArgumentException("Ungültige MCP-Server-URL: \"$serverUrl\" (erwartet z. B. https://host/pfad)")
            }
        this.endpointUrl = serverUrl
        this.authHeaderName = authHeaderName
        this.authHeaderValue = authHeaderValue
        this.sessionId = null

        log("Sende initialize-Request (Streamable HTTP, POST $serverUrl)" + if (!authHeaderName.isNullOrBlank()) " mit Header \"$authHeaderName\"" else " ohne Auth-Header")
        val initParams = buildJsonObject {
            put("protocolVersion", MCP_PROTOCOL_VERSION)
            put("capabilities", buildJsonObject {})
            put("clientInfo", buildJsonObject {
                put("name", MCP_CLIENT_NAME)
                put("version", MCP_CLIENT_VERSION)
            })
        }
        try {
            withTimeout(connectTimeoutMs) { rpcCall("initialize", initParams) }
        } catch (t: Throwable) {
            log("initialize fehlgeschlagen: ${t.message ?: t::class.simpleName}")
            throw t
        }
        log("initialize-Antwort erhalten" + (sessionId?.let { " (Session-Id: $it)" } ?: " (keine Session-Id vom Server)"))
        postNotification("notifications/initialized")
        connected = true
        log("Handshake abgeschlossen.")
    }.onFailure { connected = false }

    override suspend fun listTools(): Result<List<McpTool>> = runCatching {
        val result = rpcCall("tools/list")
        json.decodeFromJsonElement<McpToolsListResult>(result).tools
    }

    /** Best-effort: a server that doesn't implement the optional `resources/list` method answers
     * with a JSON-RPC "Method not found" [McpException] (or an HTTP error), which is treated as
     * "no resources" rather than propagated as a real failure -- see the interface doc comment. */
    override suspend fun listResources(): Result<List<McpResource>> =
        runCatching {
            val result = rpcCall("resources/list")
            json.decodeFromJsonElement<McpResourcesListResult>(result).resources
        }.recoverCatching { emptyList() }

    override suspend fun callTool(name: String, arguments: JsonObject): Result<McpToolCallResult> = runCatching {
        val params = buildJsonObject {
            put("name", name)
            put("arguments", arguments)
        }
        val result = rpcCall("tools/call", params)
        json.decodeFromJsonElement<McpToolCallResult>(result)
    }

    override fun disconnect() {
        connected = false
        endpointUrl = null
        sessionId = null
        authHeaderName = null
        authHeaderValue = null
    }

    override fun close() {
        disconnect()
        httpClient.close()
    }

    /** Requests are serialized (not pipelined) -- one POST per JSON-RPC call is cheap enough
     * here, and it avoids any question of whether the target server tolerates concurrent
     * requests sharing one session id. */
    private suspend fun rpcCall(method: String, params: JsonElement? = null): JsonElement = requestLock.withLock {
        val endpoint = endpointUrl ?: error("Not connected")
        val id = nextId.getAndIncrement()
        val request = JsonRpcRequest(id = JsonPrimitive(id), method = method, params = params)
        log("POST \"$method\" (id=$id) an $endpoint …")

        val response = httpClient.post(endpoint) {
            // A manually-set `header(HttpHeaders.ContentType, ...)` string here (as opposed to
            // this builder) can end up alongside Ktor's own Content-Type derived from the body,
            // producing a duplicate/conflicting header -- some servers then fail to route the
            // body into their JSON parser at all and reply with JSON-RPC -32700 "Parse error"
            // even though the body itself is valid JSON.
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Accept, "application/json, text/event-stream")
            sessionId?.let { header(MCP_SESSION_HEADER, it) }
            applyAuthHeader()
            setBody(json.encodeToString(request))
        }

        response.headers[MCP_SESSION_HEADER]?.let { sessionId = it }

        if (!response.status.isSuccess()) {
            val body = runCatching { response.bodyAsText() }.getOrDefault("")
            log("HTTP ${response.status.value} bei \"$method\"" + if (body.isNotBlank()) ": $body" else "")
            throw HttpStatusException(response.status.value, "Fehler ${response.status.value} bei \"$method\"")
        }

        val contentTypeHeader = response.headers[HttpHeaders.ContentType].orEmpty()
        val rpcResponse: JsonRpcResponse = if (contentTypeHeader.contains("text/event-stream")) {
            log("Antwort auf \"$method\" als SSE-Stream …")
            readSseResponse(response, id)
        } else {
            json.decodeFromString(response.bodyAsText())
        }

        rpcResponse.error?.let {
            log("JSON-RPC-Fehler bei \"$method\": Code ${it.code} -- ${it.message}")
            throw McpException(it.code, it.message)
        }
        rpcResponse.result ?: JsonNull
    }

    private suspend fun postNotification(method: String, params: JsonElement? = null) {
        val endpoint = endpointUrl ?: error("Not connected")
        val notification = JsonRpcRequest(id = null, method = method, params = params)
        httpClient.post(endpoint) {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Accept, "application/json, text/event-stream")
            sessionId?.let { header(MCP_SESSION_HEADER, it) }
            applyAuthHeader()
            setBody(json.encodeToString(notification))
        }
    }

    /** Reads a single-shot SSE response body for one specific request, ignoring any other
     * (e.g. progress notification) events on the same stream until the matching id shows up. */
    private suspend fun readSseResponse(response: HttpResponse, requestId: Long): JsonRpcResponse {
        val channel = response.bodyAsChannel()
        while (!channel.isClosedForRead) {
            val line = channel.readUTF8Line() ?: break
            if (!line.startsWith("data:")) continue
            val data = line.removePrefix("data:").trim()
            if (data.isEmpty()) continue
            val parsed = runCatching { json.decodeFromString<JsonRpcResponse>(data) }.getOrNull() ?: continue
            val responseId = (parsed.id as? JsonPrimitive)?.longOrNull
            if (responseId == requestId) return parsed
        }
        error("SSE-Stream endete ohne Antwort auf Anfrage $requestId")
    }

    private fun HttpRequestBuilder.applyAuthHeader() {
        val name = authHeaderName
        val value = authHeaderValue
        if (!name.isNullOrBlank() && value != null) header(name, value)
    }
}
