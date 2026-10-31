package com.secrm.core.ssh

import android.util.Log
import com.jcraft.jsch.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean
import java.nio.charset.StandardCharsets

sealed interface SshState {
    data object Disconnected : SshState
    data object Connecting : SshState
    data class Connected(val serverVersion: String) : SshState
    data class Error(val message: String) : SshState
    /** The server presented a key other than the one pinned. Nothing was sent to it. */
    data class HostKeyChanged(val pinned: String, val presented: String) : SshState
}

/** Thrown by [SshClientManager] when the server's key no longer matches the pinned one; both keys are base64. */
class HostKeyChangedException(val pinned: String, val presented: String, cause: Throwable) :
    Exception("Host key has changed", cause)

data class SshConfig(
    val host: String,
    val port: Int = 22,
    val username: String,
    val auth: SshAuth,
    val connectTimeoutMs: Int = 10_000,
    /** Private network to route through ("tailscale" or "wireguard"), or null for direct. */
    val via: String? = null,
    /** Host key (base64) the owner approved. Required: a connect without one is refused. */
    val hostKey: String? = null,
    /** Name of the saved connection this is, for showing which host is live. */
    val name: String? = null,
)

private val log = ArrayDeque<String>()
private const val ALIAS = "secrm-host"

/** SHA-256 fingerprint of a base64 host key, in the form OpenSSH prints. */
fun hostKeyFingerprint(hostKey: String): String =
    "SHA256:" + java.util.Base64.getEncoder().withoutPadding()
        .encodeToString(java.security.MessageDigest.getInstance("SHA-256").digest(java.util.Base64.getDecoder().decode(hostKey)))

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

    /** Local forwards open on the live session: local port to "host:port". Ends with the session. */
    private val _forwards = MutableStateFlow<Map<Int, String>>(emptyMap())
    val forwards: StateFlow<Map<Int, String>> = _forwards.asStateFlow()

    /** A forward's listener on the phone, and the job that accepts its clients. */
    private class Listener(val server: ServerSocket, val job: Job)

    private companion object {
        const val MAX_FORWARD_CLIENTS = 16
    }
    private val listeners = HashMap<Int, Listener>()
    private val random = SecureRandom()

    /**
     * Forwards [localPort] on this phone's loopback to [host]:[port] beyond the server. Throws if the port is taken.
     * Each client must send the returned token as its first line; a client that does not is dropped before any bytes are relayed.
     */
    suspend fun addLocalForward(localPort: Int, host: String, port: Int): String = withContext(Dispatchers.IO) {
        val s = activeSession() ?: error("Not connected")
        val token = ByteArray(16).also(random::nextBytes).joinToString("") { "%02x".format(it) }
        val server = ServerSocket()
        try {
            server.reuseAddress = true
            server.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), localPort))
        } catch (e: IOException) {
            server.close()
            throw e
        }
        // Each client holds a thread for up to its preamble timeout, so the number at once is capped.
        val clients = java.util.concurrent.atomic.AtomicInteger()
        val job = scope.launch {
            while (isActive) {
                val client = try { server.accept() } catch (e: IOException) { break }
                if (clients.incrementAndGet() > MAX_FORWARD_CLIENTS) {
                    clients.decrementAndGet()
                    client.close()
                    continue
                }
                launch(Dispatchers.IO) {
                    try {
                        relay(s, client, host, port, token)
                    } catch (e: Exception) {
                        // A refused or unreachable target must not reach the app's uncaught handler.
                        Log.w("SSH", "forward to $host:$port failed: ${e.javaClass.simpleName}")
                    } finally {
                        client.close()
                        clients.decrementAndGet()
                    }
                }
            }
        }
        synchronized(listeners) { listeners[localPort] = Listener(server, job) }
        _forwards.update { it + (localPort to "$host:$port") }
        token
    }

    /** Serves one client: checks the token line, then copies bytes both ways through a direct-tcpip channel. */
    private fun relay(s: Session, client: Socket, host: String, port: Int, token: String) {
        client.use { c ->
            c.soTimeout = 10_000
            val given = readLine(c.getInputStream(), token.length + 2)
            if (!MessageDigest.isEqual(given.toByteArray(), token.toByteArray())) return
            c.soTimeout = 0
            val ch = s.openChannel("direct-tcpip") as ChannelDirectTCPIP
            ch.setHost(host)
            ch.setPort(port)
            ch.setOrgIPAddress("127.0.0.1")
            ch.setOrgPort(c.localPort)
            ch.connect(10_000)
            try {
                val up = Thread { runCatching { c.getInputStream().copyTo(ch.outputStream) } }.apply { isDaemon = true; start() }
                runCatching { ch.inputStream.copyTo(c.getOutputStream()) }
                ch.disconnect()
                c.close()
                up.join(1_000)
            } finally {
                ch.disconnect()
            }
        }
    }

    /** One line of at most [max] bytes, without its line ending. */
    private fun readLine(input: InputStream, max: Int): String {
        val sb = StringBuilder()
        while (sb.length < max) {
            val b = input.read()
            if (b < 0 || b == '\n'.code) break
            sb.append(b.toChar())
        }
        return sb.toString().trimEnd('\r')
    }

    /** Closes a forward made by [addLocalForward]. */
    suspend fun removeLocalForward(localPort: Int) = withContext(Dispatchers.IO) {
        synchronized(listeners) { listeners.remove(localPort) }?.let { closeListener(it) }
        _forwards.update { it - localPort }
    }

    private fun closeListener(l: Listener) {
        l.job.cancel()
        runCatching { l.server.close() }
    }

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

    /**
     * The server's host key, for the owner to approve before anything is sent. Sends no credentials:
     * the key exchange happens, then the probe user is refused.
     */
    suspend fun scanHostKey(host: String, port: Int, via: String? = null): String? = withContext(Dispatchers.IO) {
        // Null, not a guess, when the route is down: a key from a direct probe would not be the tunnel's host.
        runCatching {
            val s = JSch().getSession("probe", host, port)
            s.setConfig("StrictHostKeyChecking", "no")
            if (via != null) s.setProxy(proxy(via))
            runCatching { s.connect(if (via != null) 30_000 else 3_000) }
            s.hostKey?.key.also { s.disconnect() }
        }.getOrNull()
    }

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
                        a.passphrase?.toByteArray(StandardCharsets.UTF_8)
                    )
                }
                is SshAuth.Password -> { /* handled below */ }
            }

            val s = jsch.getSession(cfg.username, cfg.host, cfg.port)
            // Only a pinned key is accepted, and it is checked before authenticating, so credentials never go to an impostor.
            val pinned = requireNotNull(cfg.hostKey) { "Host key not confirmed for ${cfg.host}" }
            s.setHostKeyAlias(ALIAS)
            jsch.hostKeyRepository.add(HostKey(ALIAS, java.util.Base64.getDecoder().decode(pinned)), null)
            cfg.via?.let { s.setProxy(proxy(it)) }
            s.setConfig(java.util.Properties().apply {
                put("StrictHostKeyChecking", "yes")
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
            // Probes the link every 15 s; three missed replies end the session, so a drop is noticed.
            s.setServerAliveInterval(15_000)
            s.setServerAliveCountMax(3)

            try { s.connect(if (cfg.via != null) maxOf(cfg.connectTimeoutMs, 30_000) else cfg.connectTimeoutMs) } catch (t: Throwable) {
                val presented = s.hostKey?.key
                s.disconnect()
                if (presented != null && presented != pinned && t is JSchException &&
                    t.message?.contains("has been changed", ignoreCase = true) == true
                ) throw HostKeyChangedException(pinned, presented, t)
                throw t
            }
            return s
    }

    suspend fun connect(cfg: SshConfig): Boolean = withContext(Dispatchers.IO) {
        try {
            _state.value = SshState.Connecting
            val s = newSession(cfg)
            // A replaced session must not stay open: its shell and channels would leak.
            cleanup()
            session = s
            lastCfg = cfg
            hostKey = s.hostKey?.key
            target = "${cfg.username}@${cfg.host}"
            connectedName = cfg.name
            generation++
            _state.value = SshState.Connected(s.serverVersion ?: "unknown")
            watchSession(s)
            true
        } catch (t: Throwable) {
            Log.e("SSH", "connect failed", t)
            _state.value = if (t is HostKeyChangedException) SshState.HostKeyChanged(t.pinned, t.presented)
                else SshState.Error(t.message ?: "SSH connect failed")
            cleanup()
            false
        }
    }

    /** JSch's recent log lines (info and above), for showing why a session ended. */
    fun recentLog(): String = synchronized(log) { log.joinToString("\n") }

    /** The last size sent to the server for the shell; a keyboard or sheet animation resizes many times over. */
    @Volatile private var sentPtySize: Pair<Int, Int>? = null

    /** Sends a window-change only when the character grid changed, and never a non-positive size. */
    fun resizePty(cols: Int, rows: Int, w: Int, h: Int) {
        if (cols < 1 || rows < 1) return
        if (sentPtySize == cols to rows) return
        sentPtySize = cols to rows
        runCatching { shell?.setPtySize(cols, rows, w, h) }
    }

    suspend fun ensureShell(): Boolean = withContext(Dispatchers.IO) {
        val s = session ?: return@withContext false
        // A fresh shell per terminal screen; closing the old one ends its reader.
        runCatching { shell?.disconnect() }; shell = null
        sentPtySize = null
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

    /** Keeps the first [limit] bytes and marks the rest as dropped, so a runaway command cannot exhaust memory. */
    private class CappedBuffer(private val limit: Int) : OutputStream() {
        private val buf = ByteArrayOutputStream()
        private var dropped = false

        override fun write(b: Int) { if (buf.size() < limit) buf.write(b) else dropped = true }
        override fun write(b: ByteArray, off: Int, len: Int) {
            val room = limit - buf.size()
            if (len > room) dropped = true
            if (room > 0) buf.write(b, off, minOf(len, room))
        }

        fun text(): String = buf.toString(Charsets.UTF_8.name()) +
            if (dropped) "\n[output truncated at $limit bytes]" else ""
    }

    data class ExecResult(val stdout: String, val stderr: String, val exitCode: Int)

    /** Runs [command]; [watchOut] and [watchErr], if given, also get its output as it arrives. */
    suspend fun execResult(
        command: String,
        watchOut: OutputStream? = null,
        watchErr: OutputStream? = null,
        maxBytes: Int = 1 shl 20,
        pty: Boolean = false,
    ): ExecResult = withContext(Dispatchers.IO) {
        val s = session ?: error("No active SSH session")
        val ch = s.openChannel("exec") as com.jcraft.jsch.ChannelExec
        val out = CappedBuffer(maxBytes)
        val err = CappedBuffer(maxBytes)
        coroutineScope {
            // Cancelling the caller runs this watcher, which disconnects the channel and unblocks the read below.
            // With a pty, an interrupted command is sent ^C first so it stops, not only its connection.
            val done = AtomicBoolean(false)
            val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
                try { awaitCancellation() } finally {
                    if (pty && !done.get()) {
                        Thread { runCatching { ch.outputStream.write(0x03); ch.outputStream.flush() } }
                            .apply { isDaemon = true; start() }.join(500)
                    }
                    ch.disconnect()
                }
            }
            try {
                ch.setPty(pty)
                ch.setCommand(command)
                ch.setErrStream(tee(err, watchErr))
                // Take the stream before connecting, or JSch drops the command's first output.
                val input = ch.inputStream
                ch.connect(10_000)
                input.copyTo(tee(out, watchOut))
            } finally { done.set(true); watcher.cancel(); ch.disconnect() }
        }
        ExecResult(out.text(), err.text(), ch.exitStatus)
    }

    /** [a], also written to [b] when it is given. */
    private fun tee(a: OutputStream, b: OutputStream?): OutputStream = if (b == null) a else object : OutputStream() {
        override fun write(x: Int) { a.write(x); b.write(x) }
        override fun write(x: ByteArray, off: Int, len: Int) { a.write(x, off, len); b.write(x, off, len) }
        override fun flush() { a.flush(); b.flush() }
    }

    suspend fun disconnect() = withContext(Dispatchers.IO) {
        cleanup(); _state.value = SshState.Disconnected
    }

    private var watchdog: Job? = null

    /** JSch never reports a dead link on its own; poll it so the UI stops showing the host as connected. */
    private fun watchSession(s: Session) {
        watchdog?.cancel()
        watchdog = scope.launch {
            while (true) {
                delay(5_000)
                if (session !== s) return@launch
                if (!s.isConnected) {
                    cleanup()
                    _state.value = SshState.Error("Connection lost")
                    return@launch
                }
            }
        }
    }

    private fun cleanup() {
        watchdog?.cancel(); watchdog = null
        connectedName = null
        _forwards.value = emptyMap()
        synchronized(listeners) { listeners.values.forEach { closeListener(it) }; listeners.clear() }
        runCatching { shell?.disconnect() }; shell = null
        runCatching { session?.disconnect() }; session = null
        synchronized(aux) { aux.forEach { runCatching { it.disconnect() } }; aux.clear() }
    }

    fun shutdown() { cleanup(); scope.cancel() }
}
