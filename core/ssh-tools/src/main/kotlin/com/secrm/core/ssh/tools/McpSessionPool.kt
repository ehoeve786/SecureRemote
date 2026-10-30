package com.secrm.core.ssh.tools

import com.secrm.core.ssh.SshAuth
import com.secrm.core.ssh.SshClientManager
import com.secrm.core.ssh.SshState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * One live SSH session per saved connection, shared by MCP calls so a burst of calls pays the
 * handshake once. A session with no calls for [idleMs] is closed. A session that has died is
 * replaced on its next use, and so is one whose connection settings changed. A call whose settings differ
 * from a session that is in use gets its own connection, closed after that call; the shared session is left alone.
 *
 * Connects run under one lock, so a slow connect delays other connects; calls on a live session do not wait.
 */
class McpSessionPool(
    private val scope: CoroutineScope,
    private val saved: SavedConnections,
    private val idleMs: Long = 60_000L,
    private val newClient: () -> SshClientManager,
) {
    /** [shared] is false for a private connection made for one call; it is never stored or reaped. */
    private class Entry(val conn: SavedConnection, val auth: SshAuth, val client: SshClientManager, val shared: Boolean = true) {
        var users = 0
        var reaper: Job? = null
    }

    private val lock = Mutex()
    private val entries = HashMap<String, Entry>()
    /** Private connections open right now (see [acquire]). Guarded by [lock]. */
    private var privateOpen = 0
    /** Connection name to whether its last connect failed because the host key changed. */
    private val keyChanged = ConcurrentHashMap<String, Boolean>()

    /** True if the last connect to [name] was refused because its host key changed. */
    fun hostKeyChanged(name: String): Boolean = keyChanged[name] == true

    /** Runs [block] on a live session for [conn], connecting first if needed. Null if the connect fails. */
    suspend fun <T> use(conn: SavedConnection, auth: SshAuth, block: suspend (SshClientManager) -> T): T? {
        val entry = lock.withLock { acquire(conn, auth) } ?: return null
        try {
            return block(entry.client)
        } finally {
            lock.withLock {
                entry.users--
                if (!entry.shared) {
                    privateOpen--
                    entry.client.shutdown()
                    return@withLock
                }
                // A session that died during the call is dropped now, not on its next use.
                if (entry.client.activeSession() == null) evict(entry) else if (entry.users == 0) scheduleReap(entry)
            }
        }
    }

    /** Caller holds [lock]. */
    private suspend fun acquire(conn: SavedConnection, auth: SshAuth): Entry? {
        entries[conn.name]?.let { e ->
            if (e.conn == conn && e.auth == auth && e.client.activeSession() != null) {
                e.reaper?.cancel()
                e.users++
                return e
            }
            // In use by other calls, and alive: leave it open for them, and connect privately for this call.
            if (e.users > 0 && e.client.activeSession() != null) {
                if (privateOpen >= MAX_PRIVATE_CONNECTIONS) return null
                val client = connectNew(conn, auth) ?: return null
                privateOpen++
                return Entry(conn, auth, client, shared = false).also { it.users = 1 }
            }
            evict(e)
        }
        val client = connectNew(conn, auth) ?: return null
        return Entry(conn, auth, client).also { it.users = 1; entries[conn.name] = it }
    }

    /** Caller holds [lock]. Connects a new client for [conn], or records why it could not. */
    private suspend fun connectNew(conn: SavedConnection, auth: SshAuth): SshClientManager? {
        val client = newClient()
        if (!client.connectSaved(conn, auth, saved, allowRediscover = false)) {
            keyChanged[conn.name] = client.state.value is SshState.HostKeyChanged
            client.shutdown()
            return null
        }
        keyChanged.remove(conn.name)
        return client
    }

    /** Caller holds [lock]. A session with port forwards open is kept: closing it would close the forwards. */
    private fun scheduleReap(e: Entry) {
        e.reaper?.cancel()
        e.reaper = scope.launch {
            delay(idleMs)
            lock.withLock {
                if (e.users != 0 || entries[e.conn.name] !== e) return@withLock
                if (e.client.forwards.value.isEmpty()) evict(e) else scheduleReap(e)
            }
        }
    }

    /** Caller holds [lock]. */
    private fun evict(e: Entry) {
        if (entries[e.conn.name] === e) entries.remove(e.conn.name)
        e.client.shutdown()
    }

    private companion object {
        /** Connections opened beside a busy shared session. More than this and the call is refused. */
        const val MAX_PRIVATE_CONNECTIONS = 2
    }
}
