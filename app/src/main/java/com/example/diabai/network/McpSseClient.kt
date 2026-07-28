package com.example.diabai.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.sse.ClientSSESession
import io.ktor.client.plugins.sse.SSE
import io.ktor.client.plugins.sse.sse
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
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
import java.net.URI
import java.util.concurrent.atomic.AtomicLong

/**
 * MCP client over the legacy HTTP+SSE transport: an SSE stream (server -> client) delivers
 * an `endpoint` event with the URL the client must POST JSON-RPC requests to, and
 * subsequent JSON-RPC responses/notifications arrive as further SSE `message` events
 * on that same stream. See [McpStreamableHttpClient] for the current MCP spec transport.
 */
class McpSseClient(
    private val json: Json = Json { ignoreUnknownKeys = true },
) : McpConnection {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val httpClient = HttpClient(OkHttp) {
        install(SSE)
        install(ContentNegotiation) { json(json) }
        // connectTimeoutMillis only -- bounds establishing the initial SSE connection (no
        // legitimate reason that should ever take more than 10s). Deliberately NOT setting
        // requestTimeoutMillis/socketTimeoutMillis: this stream is meant to stay open and idle
        // between server-sent events for the whole MCP session, so a flat request timeout would
        // kill a perfectly healthy, still-listening connection rather than an actually-stuck one
        // -- individual JSON-RPC request/response round trips already get their own bounded
        // `withTimeout` below (see `sendRequest`), which is the correct place to bound those.
        install(HttpTimeout) { connectTimeoutMillis = 10_000 }
    }

    private val nextId = AtomicLong(1)
    private val pendingRequests = mutableMapOf<Long, CompletableDeferred<JsonRpcResponse>>()
    private val pendingLock = Mutex()

    private var sseJob: Job? = null
    private var messageEndpointUrl: String? = null
    private var authHeaderName: String? = null
    private var authHeaderValue: String? = null

    override var onLog: (String) -> Unit = {}

    private fun log(message: String) {
        onLog(mcpLogLine(message))
    }

    override val isConnected: Boolean
        get() = sseJob?.isActive == true && messageEndpointUrl != null

    /**
     * Opens the SSE stream at [serverUrl], resolves the POST endpoint and performs the MCP
     * handshake. When set, [authHeaderName]/[authHeaderValue] (e.g. `Authorization` /
     * `Bearer <token>`, or `api-secret` / `<secret>`) are sent on the SSE request and on
     * every subsequent JSON-RPC POST for the lifetime of this connection.
     */
    override suspend fun connect(
        serverUrl: String,
        authHeaderName: String?,
        authHeaderValue: String?,
        connectTimeoutMs: Long,
    ): Result<Unit> = runCatching {
        check(sseJob == null) { "Already connected; call disconnect() first" }
        // A blank, scheme-less, or host-less URL never fails loudly here -- Ktor/OkHttp would
        // otherwise silently default the request to http://localhost/, which then surfaces
        // downstream as a baffling "Failed to connect to localhost:80" instead of a clear
        // "invalid URL" error pointing at the actual misconfiguration.
        val origin = serverUrl.toAbsoluteMcpOrigin()
            ?: run {
                log("Ungültige URL: \"$serverUrl\" ist nicht absolut (Schema/Host fehlt)")
                throw IllegalArgumentException("Ungültige MCP-Server-URL: \"$serverUrl\" (erwartet z. B. https://host/pfad)")
            }
        this.authHeaderName = authHeaderName
        this.authHeaderValue = authHeaderValue

        log("Öffne SSE-Stream (GET $serverUrl)" + if (!authHeaderName.isNullOrBlank()) " mit Header \"$authHeaderName\"" else " ohne Auth-Header")
        val endpointReady = CompletableDeferred<String>()
        sseJob = scope.launch {
            // This coroutine is a sibling of connect()'s, not a child of its call stack --
            // an uncaught exception here (bad host, malformed URL, refused connection, ...)
            // would otherwise escape runCatching entirely and crash the process. Route it
            // through endpointReady instead so it surfaces as a normal connect() failure.
            try {
                // The URL is set explicitly inside the request builder (rather than relying
                // on an overload's own URL-string parsing) so it's always the exact, validated
                // origin -- never left to an engine default.
                httpClient.sse(request = {
                    url(serverUrl)
                    applyAuthHeader()
                }) {
                    log("SSE-Stream geöffnet, warte auf \"endpoint\"-Event …")
                    listenForEvents(this, origin, endpointReady)
                }
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                log("SSE-Verbindung fehlgeschlagen: ${t.message ?: t::class.simpleName}")
                if (!endpointReady.isCompleted) endpointReady.completeExceptionally(t)
            }
        }

        val endpointUrl = try {
            withTimeout(connectTimeoutMs) { endpointReady.await() }
        } catch (t: Throwable) {
            if (t is TimeoutCancellationException) {
                log("Zeitüberschreitung: kein \"endpoint\"-Event innerhalb von ${connectTimeoutMs}ms erhalten " +
                    "(Server nutzt evtl. Streamable HTTP statt HTTP+SSE -- \"HTTP (Streamable)\" als Transport versuchen)")
            }
            disconnect()
            throw t
        }
        messageEndpointUrl = endpointUrl
        log("Endpoint-URL für JSON-RPC-Aufrufe: $endpointUrl")

        val initParams = buildJsonObject {
            put("protocolVersion", MCP_PROTOCOL_VERSION)
            put("capabilities", buildJsonObject {})
            put("clientInfo", buildJsonObject {
                put("name", MCP_CLIENT_NAME)
                put("version", MCP_CLIENT_VERSION)
            })
        }
        log("Sende initialize-Request …")
        rpcCall("initialize", initParams)
        log("initialize-Antwort erhalten, sende notifications/initialized …")
        postNotification("notifications/initialized")
        log("Handshake abgeschlossen.")
    }

    override suspend fun listTools(): Result<List<McpTool>> = runCatching {
        val result = rpcCall("tools/list")
        json.decodeFromJsonElement<McpToolsListResult>(result).tools
    }

    /** Same best-effort contract as [McpStreamableHttpClient.listResources] -- see there. */
    override suspend fun listResources(): Result<List<McpResource>> =
        runCatching {
            val result = rpcCall("resources/list")
            json.decodeFromJsonElement<McpResourcesListResult>(result).resources
        }.recoverCatching { emptyList() }

    override suspend fun callTool(name: String, arguments: JsonObject): Result<McpToolCallResult> =
        runCatching {
            val params = buildJsonObject {
                put("name", name)
                put("arguments", arguments)
            }
            val result = rpcCall("tools/call", params)
            json.decodeFromJsonElement<McpToolCallResult>(result)
        }

    override fun disconnect() {
        sseJob?.cancel()
        sseJob = null
        messageEndpointUrl = null
        authHeaderName = null
        authHeaderValue = null
        scope.launch {
            pendingLock.withLock {
                pendingRequests.values.forEach { it.cancel() }
                pendingRequests.clear()
            }
        }
    }

    override fun close() {
        disconnect()
        scope.cancel()
        httpClient.close()
    }

    private suspend fun listenForEvents(
        session: ClientSSESession,
        originUrl: String,
        endpointReady: CompletableDeferred<String>,
    ) {
        session.incoming.collect { event ->
            when (event.event) {
                "endpoint" -> {
                    val data = event.data?.trim()
                    log("\"endpoint\"-Event empfangen: \"${data.orEmpty()}\"")
                    if (!data.isNullOrEmpty() && !endpointReady.isCompleted) {
                        val resolved = resolveUrl(originUrl, data)
                        log("Aufgelöst gegen Basis-URL \"$originUrl\" -> \"$resolved\"")
                        endpointReady.complete(resolved)
                    }
                }
                else -> {
                    log("SSE-Event \"${event.event ?: "message"}\" empfangen")
                    event.data?.let { handleIncomingMessage(it) }
                }
            }
        }
    }

    /** Resolves the server's (possibly relative, e.g. `/messages`) `endpoint` event payload
     * against [baseUrl], always keeping the original scheme/host/port -- an origin-less or
     * unresolvable result is a hard error rather than something that could silently degrade
     * into a request against the wrong (or no) host. */
    private fun resolveUrl(baseUrl: String, maybeRelative: String): String {
        val resolved = runCatching { URI(baseUrl).resolve(maybeRelative) }.getOrNull()
        check(resolved != null && resolved.isAbsolute && !resolved.host.isNullOrBlank()) {
            "MCP-Endpoint-URL konnte nicht aufgelöst werden: \"$maybeRelative\" (Basis: \"$baseUrl\")"
        }
        return resolved.toString()
    }

    private suspend fun handleIncomingMessage(raw: String) {
        val response = try {
            json.decodeFromString<JsonRpcResponse>(raw)
        } catch (_: Exception) {
            return
        }
        val id = (response.id as? JsonPrimitive)?.longOrNull ?: return
        val deferred = pendingLock.withLock { pendingRequests.remove(id) }
        deferred?.complete(response)
    }

    private suspend fun rpcCall(
        method: String,
        params: JsonElement? = null,
        // Multi-day CGM history (7/30-Tage range) can genuinely take a while for the server to
        // query -- 15s was tight enough to trip on those, not just on real outages.
        timeoutMs: Long = 60_000,
    ): JsonElement {
        val endpoint = messageEndpointUrl ?: error("Not connected")
        val id = nextId.getAndIncrement()
        val deferred = CompletableDeferred<JsonRpcResponse>()
        pendingLock.withLock { pendingRequests[id] = deferred }

        val request = JsonRpcRequest(id = JsonPrimitive(id), method = method, params = params)
        httpClient.post(endpoint) {
            contentType(ContentType.Application.Json)
            applyAuthHeader()
            setBody(json.encodeToString(request))
        }

        val response = try {
            withTimeout(timeoutMs) { deferred.await() }
        } finally {
            pendingLock.withLock { pendingRequests.remove(id) }
        }

        response.error?.let { throw McpException(it.code, it.message) }
        return response.result ?: JsonNull
    }

    private suspend fun postNotification(method: String, params: JsonElement? = null) {
        val endpoint = messageEndpointUrl ?: error("Not connected")
        val notification = JsonRpcRequest(id = null, method = method, params = params)
        httpClient.post(endpoint) {
            contentType(ContentType.Application.Json)
            applyAuthHeader()
            setBody(json.encodeToString(notification))
        }
    }

    private fun HttpRequestBuilder.applyAuthHeader() {
        val name = authHeaderName
        val value = authHeaderValue
        if (!name.isNullOrBlank() && value != null) header(name, value)
    }
}
