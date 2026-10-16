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
    /** Private network to route through ("tailscale" or "wireguard"), or null for direct. */
    val via: String? = null,
    /** Trusted host key (base64), or null to trust whatever the server presents (first connect). */
    val hostKey: String? = null,
    /** Name of the saved connection this is, for showing which host is live. */
    val name: String? = null,
)

private val log = ArrayDeque<String>()
private const val ALIAS = "secrm-host"

class SshClientManager {
    init {
        JSch.setLogger(object : Logger {
            override fun isEnabled(level: Int) = level >= Logger.INFO
            override fun log(level: Int, message: String) = synchronized(log) {
                log.addLast(message); if (log.size > 12) log.removeFirst()
            }
        })
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    @Volatile private var session: Session? = null
    @Volatile private var lastCfg: SshConfig? = null
    private val aux = mutableListOf<Session>()
    @Volatile private var shell: ChannelShell? = null

    /** The live session, for extra channels such as [SftpClient]. */
    fun activeSession(): Session? = session?.takeIf { it.isConnected }

    /** A second, independent session to the connected host (null if not connected). Caller disconnects it. */
    suspend fun openAuxSession(): Session? = withContext(Dispatchers.IO) { if (activeSession() == null) null else lastCfg?.let(::newSession)?.also { synchronized(aux) { aux += it } } }

    /** Disconnects an aux session from [openAuxSession] and forgets it, so the list does not grow. */
    fun releaseAux(s: Session) { runCatching { s.disconnect() }; synchronized(aux) { aux.remove(s) } }

    /** Resolves [SshConfig.via] to its tunnel's proxy; throws, in plain words, if it can't come up. */
    var proxy: (String) -> Proxy = { error("No private network named $it") }

    private val _state = MutableStateFlow<SshState>(SshState.Disconnected)
    val state: StateFlow<SshState> = _state.asStateFlow()

    /** Counts successful connects, so screens can tell a new session from the one they were built for. */
    @Volatile var generation = 0; private set

    /** Saved-connection name of the live session (set before the state turns Connected). */
    @Volatile var connectedName: String? = null; private set

    /** `user@host` of the live session, for display. */
    @Volatile var target: String? = null; private set

    /** Host key (base64) the last successful connect saw. */
    @Volatile var hostKey: String? = null; private set

    /**
     * Finds a device whose DHCP address changed, after Tidegate's HostRediscovery: sweeps [port] on
     * the phone's own private /24s (a hotspot gets a fresh random one each session) and the old
     * address's /24, and returns the single address presenting [hostKey], or null.
     */
    suspend fun rediscover(oldHost: String, port: Int, hostKey: String): String? = withContext(Dispatchers.IO) {
        val bases = (java.net.NetworkInterface.getNetworkInterfaces().asSequence()
            .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
            .flatMap { it.inetAddresses.asSequence() }
            .filterIsInstance<java.net.Inet4Address>().map { it.hostAddress.orEmpty() } + oldHost)
            .filter(::isPrivateV4).map { it.substringBeforeLast('.') }.toSet()
        coroutineScope {
            bases.flatMap { b -> (1..254).map { "$b.$it" } }.filter { it != oldHost }
                .map { ip -> async { ip.takeIf { probe(it, port) } } }.awaitAll().filterNotNull()
                .map { ip -> async { ip.takeIf { keyScan(it, port) == hostKey } } }.awaitAll().filterNotNull()
                .singleOrNull() // fail closed on ambiguity
        }
    }

    private fun probe(host: String, port: Int) = runCatching {
        java.net.Socket().use { it.connect(java.net.InetSocketAddress(host, port), 400) }
    }.isSuccess

    /** The server's host key; authentication is expected to fail, but the key exchange precedes it. */
    private fun keyScan(host: String, port: Int): String? {
        val s = JSch().getSession("probe", host, port).apply { setConfig("StrictHostKeyChecking", "no") }
        runCatching { s.connect(3000) }
        return s.hostKey?.key.also { s.disconnect() }
    }

    private fun isPrivateV4(h: String): Boolean {
        val p = h.split('.').map { it.toIntOrNull() ?: return false }
        return p.size == 4 && (p[0] == 10 || (p[0] == 192 && p[1] == 168) || (p[0] == 172 && p[1] in 16..31))
    }

    @Volatile private var shellIn: InputStream? = null
    @Volatile private var shellOut: OutputStream? = null
    fun shellInputStream(): InputStream? = shellIn
    fun shellOutputStream(): OutputStream? = shellOut

    /** Opens and authenticates a new session for [cfg]; the SFTP client uses its own so a hiccup there can't take down the terminal. */
    private fun newSession(cfg: SshConfig): Session {
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
            // A known key is checked before authenticating, so credentials never go to an impostor.
            cfg.hostKey?.let {
                s.setHostKeyAlias(ALIAS)
                jsch.hostKeyRepository.add(HostKey(ALIAS, java.util.Base64.getDecoder().decode(it)), null)
            }
            cfg.via?.let { s.setProxy(proxy(it)) }
            s.setConfig(java.util.Properties().apply {
                put("StrictHostKeyChecking", if (cfg.hostKey != null) "yes" else "no")
                // Plain CTR + HMAC-SHA2: the AEAD/ETM defaults (chacha20, GCM) corrupt packets on Android
                // once several channels share the session (servers log "message authentication code
                // incorrect" and hang up), which is what killed the session when SFTP opened.
                put("cipher.c2s", "aes128-ctr,aes192-ctr,aes256-ctr")
                put("cipher.s2c", "aes128-ctr,aes192-ctr,aes256-ctr")
                put("mac.c2s", "hmac-sha2-256,hmac-sha2-512")
                put("mac.s2c", "hmac-sha2-256,hmac-sha2-512")
                put("PreferredAuthentications",
                    when (cfg.auth) {
                        is SshAuth.Password -> "password,keyboard-interactive"
                        else -> "publickey,password,keyboard-interactive"
                    })
            })
            if (cfg.auth is SshAuth.Password) s.setPassword((cfg.auth as SshAuth.Password).password)

            try { s.connect(if (cfg.via != null) maxOf(cfg.connectTimeoutMs, 30_000) else cfg.connectTimeoutMs) } catch (t: Throwable) { s.disconnect(); throw t }
            return s
    }

    suspend fun connect(cfg: SshConfig): Boolean = withContext(Dispatchers.IO) {
        try {
            _state.value = SshState.Connecting
            val s = newSession(cfg)
            session = s
            lastCfg = cfg
            hostKey = s.hostKey?.key
            target = "${cfg.username}@${cfg.host}"
            connectedName = cfg.name
            generation++
            _state.value = SshState.Connected(s.serverVersion ?: "unknown")
            true
        } catch (t: Throwable) {
            Log.e("SSH", "connect failed", t)
            _state.value = SshState.Error(t.message ?: "SSH connect failed")
            cleanup()
            false
        }
    }

    /** JSch's recent log lines (info and above), for showing why a session ended. */
    fun recentLog(): String = synchronized(log) { log.joinToString("\n") }

    fun resizePty(cols: Int, rows: Int, w: Int, h: Int) { runCatching { shell?.setPtySize(cols, rows, w, h) } }

    suspend fun ensureShell(): Boolean = withContext(Dispatchers.IO) {
        val s = session ?: return@withContext false
        // A fresh shell per terminal screen; closing the old one ends its reader.
        runCatching { shell?.disconnect() }; shell = null
        var opened: ChannelShell? = null
        try {
            val ch = (s.openChannel("shell") as ChannelShell).also { opened = it }
            ch.setPtyType("xterm-256color", 120, 40, 0, 0)
            // Streams must exist before connect, or the banner and first prompt are dropped.
            shellIn = ch.inputStream
            shellOut = ch.outputStream
            ch.connect(10_000)
            shell = ch
            true
        } catch (t: Throwable) {
            opened?.disconnect()
            if (t is CancellationException) throw t
            false
        }
    }

    suspend fun execOnce(command: String): String = execResult(command).let { r ->
        r.stdout + if (r.stderr.isNotBlank()) "\n[stderr]\n${r.stderr}" else ""
    }

    data class ExecResult(val stdout: String, val stderr: String, val exitCode: Int)

    suspend fun execResult(command: String): ExecResult = withContext(Dispatchers.IO) {
        val s = session ?: error("No active SSH session")
        val ch = s.openChannel("exec") as com.jcraft.jsch.ChannelExec
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        coroutineScope {
            // Cancelling the caller runs this watcher, which disconnects the channel and unblocks the read below.
            val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
                try { awaitCancellation() } finally { ch.disconnect() }
            }
            try {
                ch.setCommand(command)
                ch.setErrStream(err)
                // Take the stream before connecting, or JSch drops the command's first output.
                val input = ch.inputStream
                ch.connect(10_000)
                input.copyTo(out)
            } finally { watcher.cancel(); ch.disconnect() }
        }
        ExecResult(out.toString(Charsets.UTF_8.name()), err.toString(Charsets.UTF_8.name()), ch.exitStatus)
    }

    suspend fun disconnect() = withContext(Dispatchers.IO) {
        cleanup(); _state.value = SshState.Disconnected
    }

    private fun cleanup() {
        connectedName = null
        runCatching { shell?.disconnect() }; shell = null
        runCatching { session?.disconnect() }; session = null
        synchronized(aux) { aux.forEach { runCatching { it.disconnect() } }; aux.clear() }
    }

    fun shutdown() { cleanup(); scope.cancel() }
}
