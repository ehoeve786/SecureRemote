package com.secrm.core.ssh

import android.util.Log
import com.jcraft.jsch.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets

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
    val auth: SshAuth,
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

            when (val a = cfg.auth) {
                is SshAuth.Key -> {
                    // Byte-array overload — the canonical Android path.
                    // No temp file, no sandbox permission fights.
                    // Pass null for the public key: JSch derives it from the
                    // private key when only the private half is supplied.
                    jsch.addIdentity(
                        null,
                        a.pem.toByteArray(StandardCharsets.US_ASCII),
                        null,
                        a.passphrase?.toByteArray(StandardCharsets.US_ASCII)
                    )
                }
                is SshAuth.Password -> { /* handled below */ }
            }

            val s = jsch.getSession(cfg.username, cfg.host, cfg.port)
            session = s
            cfg.socksPort?.let { s.setProxy(ProxySOCKS5("127.0.0.1", it)) }
            s.setConfig(java.util.Properties().apply {
                put("StrictHostKeyChecking", "no")
                put("PreferredAuthentications",
                    when (cfg.auth) {
                        is SshAuth.Password -> "password,keyboard-interactive"
                        else -> "publickey,password,keyboard-interactive"
                    })
            })
            if (cfg.auth is SshAuth.Password) s.setPassword((cfg.auth as SshAuth.Password).password)

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

    fun resizePty(cols: Int, rows: Int, w: Int, h: Int) { runCatching { shell?.setPtySize(cols, rows, w, h) } }

    suspend fun ensureShell(): Boolean = withContext(Dispatchers.IO) {
        if (shell?.isConnected == true) return@withContext true
        val s = session ?: return@withContext false
        try {
            val ch = s.openChannel("shell") as ChannelShell
            ch.setPtyType("xterm-256color", 120, 40, 0, 0)
            ch.connect(10_000)
            shell = ch
            true
        } catch (t: Throwable) { false }
    }

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
        } finally { ch.disconnect() }
        buildString {
            append(out.toString(Charsets.UTF_8.name()))
            val e = err.toString(Charsets.UTF_8.name())
            if (e.isNotBlank()) { append("\n[stderr]\n"); append(e) }
        }
    }

    suspend fun disconnect() = withContext(Dispatchers.IO) {
        cleanup(); _state.value = SshState.Disconnected
    }

    private fun cleanup() {
        runCatching { shell?.disconnect() }; shell = null
        runCatching { session?.disconnect() }; session = null
    }

    fun shutdown() { cleanup(); scope.cancel() }
}
