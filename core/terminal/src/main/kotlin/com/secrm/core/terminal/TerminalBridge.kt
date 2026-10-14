package com.secrm.core.terminal

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.sp
import com.secrm.core.ssh.SshClientManager
import java.io.OutputStream
import org.connectbot.terminal.Terminal
import org.connectbot.terminal.TerminalEmulatorFactory
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/** Pipes an SSH shell channel into ConnectBot termlib's emulator. */
private val ANSI = Regex("\u001B(\\[[0-?]*[ -/]*[@-~]|\\][^\u0007\u001B]*(\u0007|\u001B\\\\)?|[@-Z\\\\-_])|\r")

class TerminalBridge(private val ssh: SshClientManager) {
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
        onResize = { ssh.resizePty(it.columns, it.rows, it.widthPixels, it.heightPixels) },
    ).also { e ->
        // termlib hard-codes 1000 lines of scrollback; raise it so long output can be scrolled back to.
        runCatching { e.javaClass.getDeclaredField("maxScrollbackLines").apply { isAccessible = true }.setInt(e, 10_000) }
    }

    /** Opens the shell and starts the read loop. Returns false if no shell. */
    suspend fun start(): Boolean {
        if (!ssh.ensureShell()) return false
        val input = ssh.shellInputStream() ?: return false
        out = ssh.shellOutputStream()
        thread(isDaemon = true, name = "ssh-term-read") {
            val buf = ByteArray(8192)
            val why = runCatching {
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    emulator.writeInput(buf, 0, n)
                    synchronized(log) {
                        if (log.size() > 256 * 1024) log.toByteArray().let {
                            val drop = it.size / 2
                            log.reset(); log.write(it, drop, it.size - drop); mark = maxOf(0, mark - drop)
                        }
                        log.write(buf, 0, n)
                    }
                }
            }.exceptionOrNull()
            // Say so instead of leaving a dead terminal that silently ignores typing.
            note("connection closed" + (why?.let { ": $it" } ?: ""))
            emulator.writeInput(("\u001b[90m" + ssh.recentLog().replace("\n", "\r\n") + "\u001b[0m\r\n").toByteArray())
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
    modifier: Modifier = Modifier
) {
    // The keyboard's input only reaches the terminal once it holds Compose focus. Take it on the
    // first frame, as Tidegate does, before termlib shows the IME: requesting it later (after a slow
    // tunnelled connect) re-targets focus away from the IME's live input connection.
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { withFrameNanos {}; runCatching { focus.requestFocus() } }
    LaunchedEffect(bridge) { onReady(bridge.start()) }
    Terminal(
        terminalEmulator = bridge.emulator,
        modifier = modifier,
        initialFontSize = 13.sp,
        keyboardEnabled = true,
        focusRequester = focus,
        onTerminalTap = { runCatching { focus.requestFocus() } },
    )
}
