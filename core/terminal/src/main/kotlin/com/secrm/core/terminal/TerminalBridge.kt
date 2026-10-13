package com.secrm.core.terminal

import android.view.ViewGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.secrm.core.ssh.SshClientManager
import org.connectbot.terminal.TerminalSession
import org.connectbot.terminal.TerminalSessionClient
import org.connectbot.terminal.TerminalView
import org.connectbot.terminal.TerminalViewClient

/**
 * Wires SSH streams into ConnectBot's termlib. The library owns the VT100
 * state machine; we only provide the byte transport.
 */
class TerminalBridge(private val ssh: SshClientManager) {

    private var session: TerminalSession? = null
    private var view: TerminalView? = null

    fun attach(v: TerminalView): Boolean {
        val inStream = ssh.shellInputStream() ?: return false
        val outStream = ssh.shellOutputStream() ?: return false

        val sessionClient = object : TerminalSessionClient {
            override fun onTextChanged(changedSession: TerminalSession) {}
            override fun onTitleChanged(changedSession: TerminalSession) {}
            override fun onSessionFinished(finishedSession: TerminalSession) {}
            override fun onCopyTextToClipboard(session: TerminalSession, text: String) {}
            override fun onPasteTextFromClipboard(session: TerminalSession?) {}
            override fun onBell(session: TerminalSession) {}
            override fun onColorsChanged(session: TerminalSession) {}
            override fun onTerminalCursorStateChange(state: Boolean) {}
        }

        val s = TerminalSession(inStream, outStream, 120, 40, 2000, sessionClient)
        session = s
        view = v

        val viewClient = object : TerminalViewClient {
            override fun onScale(scale: Float): Float = scale
            override fun onSingleTapUp(e: android.view.MotionEvent) { v.requestFocus() }
            override fun shouldBackButtonBeMappedToEscape() = false
            override fun shouldEnforceCharBasedInput() = false
            override fun shouldUseCtrlSpaceWorkaround() = false
            override fun isTerminalViewSelected() = true
            override fun copyModeChanged(copyMode: Boolean) {}
            override fun onKeyDown(keyCode: Int, e: android.view.KeyEvent, session: TerminalSession) = false
            override fun onKeyUp(keyCode: Int, e: android.view.KeyEvent) = false
            override fun onLongPress(event: android.view.MotionEvent) = false
            override fun readControlKey() = false
            override fun readAltKey() = false
            override fun readShiftKey() = false
            override fun readFnKey() = false
            override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession) = false
        }

        v.setTerminalViewClient(viewClient)
        v.attachSession(s)
        return true
    }

    /** Send text (used by extra-keys toolbar). */
    fun send(text: String) {
        ssh.shellOutputStream()?.apply {
            write(text.toByteArray(Charsets.UTF_8))
            flush()
        }
    }

    fun detach() {
        view?.attachSession(null)
        view = null
        session = null
    }
}

@Composable
fun TerminalSurface(
    bridge: TerminalBridge,
    onReady: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier
) {
    AndroidView(
        factory = { ctx ->
            TerminalView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(-1, -1)
                setTextSize(13)
                isFocusable = true
                isFocusableInTouchMode = true
                setBackgroundColor(android.graphics.Color.BLACK)
                onReady(attach(bridge).also { ok -> if (!ok) android.util.Log.e("TerminalBridge", "attach failed") }.let { true })
            }
        },
        modifier = modifier
    )
    DisposableEffect(Unit) { onDispose { bridge.detach() } }
}
