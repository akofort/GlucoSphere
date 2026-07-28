package com.example.diabai.network

import com.example.diabai.data.McpServerConfig
import com.example.diabai.data.resolveAuthHeader
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject

/** A tool as advertised by one specific configured MCP server. */
data class PooledTool(val serverId: String, val serverName: String, val tool: McpTool)

/**
 * Manages up to [com.example.diabai.data.MAX_MCP_SERVERS] simultaneous MCP connections (one
 * [McpConnection] per configured server, in whichever transport that server is configured for),
 * so the agent can call tools from whichever server actually offers them instead of being
 * limited to a single configured MCP endpoint.
 */
class McpServerPool {
    private val clients = mutableMapOf<String, McpConnection>()
    private val mutex = Mutex()

    private val _connectedServerIds = MutableStateFlow<Set<String>>(emptySet())

    /** Which configured servers actually answered the connect handshake, refreshed after every
     * [sync] -- this *is* the app-start health check's result (see MainActivity, which calls
     * [sync] once at launch): a server that timed out or is offline is simply absent here for
     * the rest of the session, so the chat tab's server filter chips can show it disabled/greyed
     * out instead of letting the user pick a server that will just fail on the first tool call. */
    val connectedServerIds: StateFlow<Set<String>> = _connectedServerIds.asStateFlow()

    /**
     * Connects/disconnects clients so the pool matches [configs] exactly (by id). Best-effort:
     * one server failing to connect doesn't stop the others. Already-connected servers whose
     * config didn't change are left alone rather than reconnected.
     */
    suspend fun sync(configs: List<McpServerConfig>): Map<String, Result<Unit>> {
        val results = mutableMapOf<String, Result<Unit>>()
        val configIds = configs.map { it.id }.toSet()

        mutex.withLock {
            val staleIds = clients.keys - configIds
            for (id in staleIds) clients.remove(id)?.close()
        }

        for (config in configs) {
            // A freshly-added, not-yet-filled-in server (e.g. right after "+ MCP-Server
            // hinzufügen") has a blank URL -- attempting to connect it would hand Ktor an
            // empty request URL, which silently defaults to http://localhost/ instead of
            // failing loudly. Skip it entirely rather than let that surface as a confusing
            // "connect to localhost:80" error for an unrelated, real server.
            if (config.url.isBlank()) continue

            val existing = mutex.withLock { clients[config.id] }
            if (existing != null && existing.isConnected) {
                results[config.id] = Result.success(Unit)
                continue
            }
            existing?.close()

            val client = config.transport.newMcpConnection()
            val header = config.resolveAuthHeader()
            val result = client.connect(config.url, header?.first, header?.second)
            mutex.withLock { clients[config.id] = client }
            results[config.id] = result
        }
        refreshConnectedServerIds()
        return results
    }

    /** Disconnects and forgets one server (e.g. it was just deleted from settings). */
    suspend fun remove(serverId: String) {
        mutex.withLock { clients.remove(serverId) }?.close()
        _connectedServerIds.value = _connectedServerIds.value - serverId
    }

    fun isConnected(serverId: String): Boolean = clients[serverId]?.isConnected == true

    val hasAnyConnection: Boolean get() = clients.values.any { it.isConnected }

    private suspend fun refreshConnectedServerIds() {
        _connectedServerIds.value = mutex.withLock { clients.filterValues { it.isConnected }.keys.toSet() }
    }

    /** Tools from every currently-connected server, tagged with which server offers them. */
    suspend fun listAllTools(configs: List<McpServerConfig>): List<PooledTool> {
        val names = configs.associateBy({ it.id }, { it.displayName })
        val snapshot = mutex.withLock { clients.toMap() }
        return snapshot
            .filterValues { it.isConnected }
            .flatMap { (id, client) ->
                client.listTools().getOrDefault(emptyList()).map { tool -> PooledTool(id, names[id] ?: id, tool) }
            }
    }

    /** Tools offered by one specific connected server -- used by the settings screen's
     * expandable server card to show "Einzelliste der Tools mit Auto-Approve Toggles" without
     * needing every other server's tools too. */
    suspend fun listTools(serverId: String): Result<List<McpTool>> {
        val client = mutex.withLock { clients[serverId] }
            ?: return Result.failure(IllegalStateException("MCP-Server nicht verbunden"))
        return client.listTools()
    }

    /** Resources offered by one specific connected server, if any -- Discovery Modus's
     * `resources/list` step (see [com.example.diabai.domain.discovery.DiscoveryService]). */
    suspend fun listResources(serverId: String): Result<List<McpResource>> {
        val client = mutex.withLock { clients[serverId] }
            ?: return Result.failure(IllegalStateException("MCP-Server nicht verbunden"))
        return client.listResources()
    }

    suspend fun callTool(serverId: String, name: String, arguments: JsonObject): Result<McpToolCallResult> {
        val client = mutex.withLock { clients[serverId] }
            ?: return Result.failure(IllegalStateException("MCP-Server nicht verbunden"))
        return client.callTool(name, arguments)
    }

    suspend fun closeAll() {
        mutex.withLock {
            clients.values.forEach { it.close() }
            clients.clear()
        }
        _connectedServerIds.value = emptySet()
    }
}
