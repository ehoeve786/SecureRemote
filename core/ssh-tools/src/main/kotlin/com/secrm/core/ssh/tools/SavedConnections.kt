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
    if (connectOn(conn, conn.via, auth, saved, allowRediscover, approvedKey)) return true
    // Direct failed: a host with a fallback network is tried through it, once. The saved record keeps its own route.
    val fallback = conn.fallbackVia?.takeIf { conn.via == null } ?: return false
    return connectOn(conn, fallback, auth, saved, allowRediscover = false, approvedKey = approvedKey)
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
