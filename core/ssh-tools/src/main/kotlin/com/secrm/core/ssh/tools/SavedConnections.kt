package com.secrm.core.ssh.tools

import com.secrm.core.ssh.SshAuth
import com.secrm.core.ssh.SshClientManager
import com.secrm.core.ssh.SshConfig
import kotlinx.serialization.Serializable

@Serializable
data class SavedConnection(
    val name: String,
    val host: String,
    val port: Int = 22,
    val user: String,
    val authMethod: AuthMethod = AuthMethod.PASSWORD,
    val password: String = "",
    val keyId: String? = null,
    /** Private network to route through ("tailscale" or "wireguard"), or null for direct. */
    val via: String? = null,
    /**
     * Tried when a direct connection (via == null) cannot reach the host: the private network to route through.
     * Lets one saved host work both on the home LAN and away from it.
     */
    val fallbackVia: String? = null,
    /** The route that last worked on each private network, keyed by its /24 (see [currentNetworkKey]); "direct" or a route name. */
    val routeByNetwork: Map<String, String> = emptyMap(),
    /** Host key (base64) trusted on first connect; also how the device is found if its IP changes. */
    val hostKey: String? = null,
    /** Opt-in: a connection is not reachable over MCP until its owner enables it. */
    val allowMcpExec: Boolean = false,
    val allowMcpRead: Boolean = false
)

interface SavedConnections {
    fun list(): List<SavedConnection>
    fun save(connection: SavedConnection)
    fun find(name: String): SavedConnection?

    /**
     * Return the private key PEM for a saved key id, or null. Implemented by
     * the app module's KeyRepository; ssh-tools only needs the string.
     */
    fun privateKeyFor(keyId: String): String?

    /** Passphrase for a saved key id, or empty. */
    fun keyPassphraseFor(keyId: String): String?
}

/**
 * Connects to a saved connection, pinned to its host key. A host with no key yet is pinned only to
 * [approvedKey], the key the owner checked in the app; any other key is refused. With [allowRediscover],
 * a failed direct connect follows the device to its new address by host key and saves it.
 */
suspend fun SshClientManager.connectSaved(
    conn: SavedConnection,
    auth: SshAuth,
    saved: SavedConnections,
    /** On for the owner's app and plugin; MCP passes false, so an agent never triggers a network probe. */
    allowRediscover: Boolean = true,
    approvedKey: String? = null,
): Boolean {
    val fallback = conn.fallbackVia?.takeIf { conn.via == null }
    val netKey = currentNetworkKey()
    val routes = orderRoutes(conn.via, fallback, onLocalNetwork(conn.host), netKey?.let { conn.routeByNetwork[it] })
    for (route in routes) {
        // Only the direct attempt may search for a moved host; a tunnel attempt never probes the network.
        if (connectOn(conn, route, auth, saved, allowRediscover && route == null, approvedKey)) {
            rememberRoute(conn, netKey, route, saved)
            return true
        }
    }
    return false
}

/** Records the route that worked on this network, read from the latest saved record so no other change is lost. */
private fun rememberRoute(conn: SavedConnection, netKey: String?, route: String?, saved: SavedConnections) {
    if (netKey == null || conn.via != null) return
    val key = route ?: DIRECT
    val latest = saved.find(conn.name) ?: return
    if (latest.routeByNetwork[netKey] == key) return
    saved.save(latest.copy(routeByNetwork = latest.routeByNetwork + (netKey to key)))
}

private suspend fun SshClientManager.connectOn(
    conn: SavedConnection,
    via: String?,
    auth: SshAuth,
    saved: SavedConnections,
    allowRediscover: Boolean,
    approvedKey: String?,
): Boolean {
    fun cfg(host: String, key: String) = SshConfig(host = host, port = conn.port, username = conn.user, auth = auth, via = via, hostKey = key, name = conn.name)
    val pinned = conn.hostKey ?: run {
        val seen = scanHostKey(conn.host, conn.port, via) ?: return false
        if (seen != approvedKey) return false
        seen
    }
    if (connect(cfg(conn.host, pinned))) {
        if (conn.hostKey == null) saved.save(conn.copy(hostKey = pinned))
        return true
    }
    val moved = (if (allowRediscover && via == null) rediscover(conn.host, conn.port, pinned) else null)
        ?: return false
    saved.save(conn.copy(host = moved))
    return connect(cfg(moved, pinned))
}
