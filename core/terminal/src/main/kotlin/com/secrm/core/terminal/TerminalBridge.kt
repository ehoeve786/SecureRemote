package com.secrm.core.terminal

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.sp
import com.secrm.core.ssh.SshClientManager
import java.io.InputStream
import java.io.OutputStream
import org.connectbot.terminal.Terminal
import org.connectbot.terminal.TerminalEmulatorFactory
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.Semaphore
import kotlin.concurrent.thread

/** Pipes an SSH shell channel into ConnectBot termlib's emulator. */
private val ANSI = Regex("\u001B(\\[[0-?]*[ -/]*[@-~]|\\][^\u0007\u001B]*(\u0007|\u001B\\\\)?|[@-Z\\\\-_])|\r")

class TerminalBridge private constructor(
    /** The SSH session whose shell this drives; null for a watch-only terminal. */
    private val ssh: SshClientManager?,
    /** Output to show read-only, from a command rather than a shell; null for a shell. */
    private val feed: InputStream?,
) {
    constructor(ssh: SshClientManager) : this(ssh, null)

    companion object {
        /** A read-only terminal showing [feed]; keyboard input goes nowhere. */
        fun watching(feed: InputStream) = TerminalBridge(null, feed)

        /** Output read from the network but not yet drawn. Past this the reader waits for the screen to catch up. */
        private const val MAX_QUEUED_BYTES = 8 shl 20
        /** Marks the end of the output in the queue. */
        private val END = ByteArray(0)
    }

    /** Called once the output ends (the shell or command closed), from the drawing thread. */
    @Volatile var onEnd: (() -> Unit)? = null

    private val writer = Executors.newSingleThreadExecutor()
    @Volatile private var out: OutputStream? = null

    // Raw session output (last ~256 KB) and where it stood when Enter was last pressed, for copying
    // a long command's output without drag-selecting.
    private val log = java.io.ByteArrayOutputStream()
    private var mark = 0

    /** Output of the last command run, as plain text: its echo line and the new prompt left out. */
    fun lastOutput(): String = plain(synchronized(log) { String(log.toByteArray(), mark, log.size() - mark, Charsets.UTF_8) })
        .substringAfter('\n', "").substringBeforeLast('\n', "")

    /** The whole session (up to the last ~256KB) as plain text, for the native text selector. */
    fun transcript(): String = plain(synchronized(log) { log.toString(Charsets.UTF_8) })

    /** Drops escape sequences and carriage returns, and applies backspaces. */
    private fun plain(raw: String): String {
        val s = raw.replace(ANSI, "")
        val sb = StringBuilder(s.length)
        for (c in s) when (c) {
            '\b' -> if (sb.isNotEmpty() && sb.last() != '\n') sb.setLength(sb.length - 1)
            '\u0007' -> {}
            else -> sb.append(c)
        }
        return sb.toString()
    }

    val emulator = TerminalEmulatorFactory.create(
        initialRows = 40,
        initialCols = 120,
        onKeyboardInput = ::send,
        onResize = { ssh?.resizePty(it.columns, it.rows, it.widthPixels, it.heightPixels) },
    )

    private var started: Boolean? = null

    /** Opens the shell (or starts the feed) and the read loop, once; later calls return the first result. */
    suspend fun start(): Boolean = started ?: begin().also { started = it }

    private suspend fun begin(): Boolean {
        val input: InputStream
        val s = ssh
        if (s == null) {
            input = feed ?: return false
        } else {
            if (!s.ensureShell()) return false
            input = s.shellInputStream() ?: return false
            out = s.shellOutputStream()
        }
        // Two threads: one only reads the network, one draws. The emulator's lock is also taken by resizes and
        // snapshots, so a keyboard or sheet animation can hold it for a while. If the read waited on it, the SSH
        // library's buffer would fill and it would stop answering the server's keepalives, and a server that
        // probes often drops the session. The queue lets the read go on while the screen is busy.
        val queue = LinkedBlockingQueue<ByteArray>()
        val room = Semaphore(MAX_QUEUED_BYTES)
        var why: Throwable? = null
        thread(isDaemon = true, name = "ssh-term-read") {
            val buf = ByteArray(8192)
            why = runCatching {
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    room.acquire(n)
                    queue.put(buf.copyOf(n))
                }
            }.exceptionOrNull()
            queue.put(END)
        }
        thread(isDaemon = true, name = "ssh-term-draw") {
            while (true) {
                val chunk = queue.take()
                if (chunk === END) break
                emulator.writeInput(chunk, 0, chunk.size)
                room.release(chunk.size)
                synchronized(log) {
                    if (log.size() > 256 * 1024) log.toByteArray().let {
                        val drop = it.size / 2
                        log.reset(); log.write(it, drop, it.size - drop); mark = maxOf(0, mark - drop)
                    }
                    log.write(chunk, 0, chunk.size)
                }
            }
            if (s != null) {
                // Say so instead of leaving a dead terminal that silently ignores typing.
                note("connection closed" + (why?.let { ": $it" } ?: ""))
                emulator.writeInput(("\u001b[90m" + s.recentLog().replace("\n", "\r\n") + "\u001b[0m\r\n").toByteArray())
            }
            // The session's output is not kept after the connection ends.
            synchronized(log) { log.reset(); mark = 0 }
            onEnd?.invoke()
        }
        return true
    }

    fun send(text: String) = send(text.toByteArray())

    fun send(data: ByteArray) {
        if ('\r'.code.toByte() in data) synchronized(log) { mark = log.size() }
        writer.execute {
            runCatching { out?.run { write(data); flush() } }.onFailure { note("send failed: $it") }
        }
    }

    private fun note(msg: String) {
        android.util.Log.w("secrm-term", msg)
        emulator.writeInput("\r\n\u001b[33m[$msg]\u001b[0m\r\n".toByteArray())
    }
}

@Composable
fun TerminalSurface(
    bridge: TerminalBridge,
    onReady: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier,
    /** False for a read-only view: no soft keyboard, and taps don't take focus. */
    keyboard: Boolean = true,
) {
    // The keyboard's input only reaches the terminal once it holds Compose focus. Take it on the
    // first frame, as Tidegate does, before termlib shows the IME: requesting it later (after a slow
    // tunnelled connect) re-targets focus away from the IME's live input connection.
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { if (keyboard) { withFrameNanos {}; runCatching { focus.requestFocus() } } }
    LaunchedEffect(bridge) { onReady(bridge.start()) }
    Terminal(
        terminalEmulator = bridge.emulator,
        modifier = modifier,
        initialFontSize = 13.sp,
        keyboardEnabled = keyboard,
        focusRequester = focus,
        onTerminalTap = { if (keyboard) runCatching { focus.requestFocus() } },
    )
}
