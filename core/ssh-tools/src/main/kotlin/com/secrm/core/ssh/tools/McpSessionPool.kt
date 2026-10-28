package com.secrm.core.ssh.tools

import com.secrm.core.ssh.SshAuth
import com.secrm.core.ssh.SshClientManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * One live SSH session per saved connection, shared by MCP calls so a burst of calls pays the
 * handshake once. A session with no calls for [idleMs] is closed. A session that has died is
 * replaced on its next use, and so is one whose connection settings changed.
 *
 * Connects run under one lock, so a slow connect delays other connects; calls on a live session do not wait.
 */
class McpSessionPool(
    private val scope: CoroutineScope,
    private val saved: SavedConnections,
    private val idleMs: Long = 60_000L,
    private val newClient: () -> SshClientManager,
) {
    private class Entry(val conn: SavedConnection, val auth: SshAuth, val client: SshClientManager) {
        var users = 0
        var reaper: Job? = null
    }

    private val lock = Mutex()
    private val entries = HashMap<String, Entry>()

    /** Runs [block] on a live session for [conn], connecting first if needed. Null if the connect fails. */
    suspend fun <T> use(conn: SavedConnection, auth: SshAuth, block: suspend (SshClientManager) -> T): T? {
        val entry = lock.withLock { acquire(conn, auth) } ?: return null
        try {
            return block(entry.client)
        } finally {
            lock.withLock {
                entry.users--
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
            evict(e)
        }
        val client = newClient()
        if (!client.connectSaved(conn, auth, saved, allowRediscover = false)) {
            client.shutdown()
            return null
        }
        return Entry(conn, auth, client).also { it.users = 1; entries[conn.name] = it }
    }

    /** Caller holds [lock]. */
    private fun scheduleReap(e: Entry) {
        e.reaper?.cancel()
        e.reaper = scope.launch {
            delay(idleMs)
            lock.withLock { if (e.users == 0 && entries[e.conn.name] === e) evict(e) }
        }
    }

    /** Caller holds [lock]. */
    private fun evict(e: Entry) {
        if (entries[e.conn.name] === e) entries.remove(e.conn.name)
        e.client.shutdown()
    }
}
