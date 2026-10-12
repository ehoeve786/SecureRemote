package com.secrm.core.ssh

import android.util.Log
import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.JSch
import com.jcraft.jsch.ProxySOCKS5
import com.jcraft.jsch.Session
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream

sealed interface SshState {
    data object Disconnected : SshState
    data object Connecting : SshState
    data class Connected(val serverVersion: String) : SshState
    data class Error(val message: String) : SshState
}

data class SshConfig(
    val host: String,
    val port: Int = 22,
    val username: String,
    val password: String,
    val connectTimeoutMs: Int = 10_000,
    val socksPort: Int? = null
)

class SshClientManager {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var session: Session? = null
    private var shell: ChannelShell? = null

    private val _state = MutableStateFlow<SshState>(SshState.Disconnected)
    val state: StateFlow<SshState> = _state.asStateFlow()

    fun shellInputStream(): InputStream? = runCatching { shell?.inputStream }.getOrNull()
    fun shellOutputStream(): OutputStream? = runCatching { shell?.outputStream }.getOrNull()

    suspend fun connect(cfg: SshConfig): Boolean = withContext(Dispatchers.IO) {
        try {
            _state.value = SshState.Connecting
            val jsch = JSch()
            val s = jsch.getSession(cfg.username, cfg.host, cfg.port)
            session = s
            cfg.socksPort?.let { s.setProxy(ProxySOCKS5("127.0.0.1", it, null)) }
            s.setConfig(java.util.Properties().apply {
                put("StrictHostKeyChecking", "no")
                put("PreferredAuthentications", "password,keyboard-interactive")
            })
            s.setPassword(cfg.password)
            s.connect(cfg.connectTimeoutMs)
            _state.value = SshState.Connected(s.serverVersion ?: "unknown")
            true
        } catch (t: Throwable) {
            Log.e("SSH", "connect failed", t)
            _state.value = SshState.Error(t.message ?: "SSH connect failed")
            cleanup()
            false
        }
    }

    /**
     * Open a shell channel if none is open. Used by the quick-shell UI.
     */
    suspend fun ensureShell(): Boolean = withContext(Dispatchers.IO) {
        if (shell?.isConnected == true) return@withContext true
        val s = session ?: return@withContext false
        try {
            val ch = s.openChannel("shell") as ChannelShell
            ch.setPtyType("xterm-256color", 120, 40, 0, 0)
            ch.connect(10_000)
            shell = ch
            true
        } catch (t: Throwable) {
            Log.e("SSH", "shell open failed", t)
            false
        }
    }

    /**
     * Run a single command in its own exec channel and return combined
     * stdout+stderr. Used by the MCP server path (ssh.exec tool).
     */
    suspend fun execOnce(command: String): String = withContext(Dispatchers.IO) {
        val s = session ?: error("No active SSH session")
        val ch = s.openChannel("exec") as com.jcraft.jsch.ChannelExec
        ch.setCommand(command)
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        ch.setErrStream(err)
        ch.connect(10_000)
        try {
            val input = ch.inputStream
            val buf = ByteArray(4096)
            while (true) {
                val n = input.read(buf)
                if (n <= 0 && ch.isClosed) break
                if (n > 0) out.write(buf, 0, n)
            }
        } finally {
            ch.disconnect()
        }
        val stdout = out.toString(Charsets.UTF_8.name())
        val stderr = err.toString(Charsets.UTF_8.name())
        buildString {
            append(stdout)
            if (stderr.isNotBlank()) { append("\n[stderr]\n"); append(stderr) }
        }
    }

    suspend fun disconnect() = withContext(Dispatchers.IO) {
        cleanup()
        _state.value = SshState.Disconnected
    }

    private fun cleanup() {
        runCatching { shell?.disconnect() }; shell = null
        runCatching { session?.disconnect() }; session = null
    }

    fun shutdown() { cleanup(); scope.cancel() }
}
