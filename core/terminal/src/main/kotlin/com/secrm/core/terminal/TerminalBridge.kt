package com.secrm.core.terminal

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.sp
import com.secrm.core.ssh.SshClientManager
import java.io.OutputStream
import org.connectbot.terminal.Terminal
import org.connectbot.terminal.TerminalEmulatorFactory
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/** Pipes an SSH shell channel into ConnectBot termlib's emulator. */
class TerminalBridge(private val ssh: SshClientManager) {
    private val writer = Executors.newSingleThreadExecutor()
    @Volatile private var out: OutputStream? = null

    val emulator = TerminalEmulatorFactory.create(
        initialRows = 40,
        initialCols = 120,
        onKeyboardInput = ::send,
        onResize = { ssh.resizePty(it.columns, it.rows, it.widthPixels, it.heightPixels) },
    )

    /** Opens the shell and starts the read loop. Returns false if no shell. */
    suspend fun start(): Boolean {
        if (!ssh.ensureShell()) return false
        val input = ssh.shellInputStream() ?: return false
        out = ssh.shellOutputStream()
        thread(isDaemon = true, name = "ssh-term-read") {
            val buf = ByteArray(8192)
            runCatching {
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    emulator.writeInput(buf, 0, n)
                }
            }
        }
        return true
    }

    fun send(text: String) = send(text.toByteArray())

    fun send(data: ByteArray) {
        writer.execute { runCatching { out?.run { write(data); flush() } } }
    }
}

@Composable
fun TerminalSurface(
    bridge: TerminalBridge,
    onReady: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier
) {
    LaunchedEffect(bridge) { onReady(bridge.start()) }
    Terminal(
        terminalEmulator = bridge.emulator,
        modifier = modifier,
        initialFontSize = 13.sp,
        keyboardEnabled = true,
    )
}
