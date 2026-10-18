package com.secrm

import com.secrm.core.ssh.SshAuth
import com.secrm.core.ssh.SshClientManager
import com.secrm.core.ssh.SshState
import com.secrm.core.ssh.tools.SavedConnection
import com.secrm.core.ssh.tools.connectSaved
import com.secrm.core.terminal.TerminalBridge
import com.secrm.ui.sftp.Clip
import com.secrm.ui.sftp.SftpController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

/** One connected host: its SSH session, shell and file browser. */
class HostSession(val name: String, val ssh: SshClientManager, app: SecureRemoteApp) {
    val files = SftpController(app, ssh, name, app.sessions)
    val target: String get() = ssh.target.orEmpty()
    private var bridge: TerminalBridge? = null
    private var bridgeGeneration = -1

    /** The shell for this host, or null when it isn't connected. Its scrollback survives tab switches. */
    @Synchronized
    fun terminal(): TerminalBridge? {
        if (ssh.state.value !is SshState.Connected) return null
        if (bridge == null || bridgeGeneration != ssh.generation) {
            bridge = TerminalBridge(ssh); bridgeGeneration = ssh.generation
        }
        return bridge
    }
}

/** The hosts connected at once, which one the screens show, and the clipboard shared between them. */
class HostSessions(private val app: SecureRemoteApp) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val all = MutableStateFlow<List<HostSession>>(emptyList())
    private val picked = MutableStateFlow<String?>(null)
    private val _clip = MutableStateFlow<Clip?>(null)
    val clip: StateFlow<Clip?> = _clip.asStateFlow()

    /** Hosts whose session is up, in the order they were opened. A host that drops leaves this list. */
    val connected: StateFlow<List<HostSession>> = all.flatMapLatest { list ->
        if (list.isEmpty()) flowOf(emptyList())
        else combine(list.map { h -> h.ssh.state.map { it is SshState.Connected } }) { up ->
            list.filterIndexed { i, _ -> up[i] }
        }
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** The host the screens show; swiping the terminal or files moves it. */
    val current: StateFlow<HostSession?> = combine(connected, picked) { list, name ->
        list.firstOrNull { it.name == name } ?: list.firstOrNull()
    }.stateIn(scope, SharingStarted.Eagerly, null)

    fun find(name: String): HostSession? = all.value.firstOrNull { it.name == name }

    fun select(name: String) { picked.value = name }

    fun setClip(c: Clip?) { _clip.value = c }

    /** Connects [conn] alongside the others (or re-connects it). Returns null on success, else why it failed. */
    suspend fun connect(conn: SavedConnection, auth: SshAuth, rediscover: Boolean = false, approvedKey: String? = null): String? {
        find(conn.name)?.takeIf { it.ssh.state.value is SshState.Connected }?.let { select(conn.name); return null }
        val ssh = app.newClient()
        if (!ssh.connectSaved(conn, auth, app.connections, allowRediscover = rediscover, approvedKey = approvedKey)) {
            val why = (ssh.state.value as? SshState.Error)?.message ?: "SSH connect failed"
            ssh.shutdown()
            return why
        }
        find(conn.name)?.ssh?.shutdown() // a dropped session under the same name
        all.update { list -> list.filter { it.name != conn.name } + HostSession(conn.name, ssh, app) }
        select(conn.name)
        return null
    }

    /** The key a host without one presents, for the owner to approve before connecting. Sends no credentials. */
    suspend fun scanKey(conn: SavedConnection): String? {
        val probe = app.newClient()
        return try { probe.scanHostKey(conn.host, conn.port, conn.via) } finally { probe.shutdown() }
    }

    fun close(name: String) {
        val h = find(name) ?: return
        all.update { it - h }
        h.ssh.shutdown()
        if (picked.value == name) picked.value = null
    }
}
