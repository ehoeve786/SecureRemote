package com.secrm.ui.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.secrm.SecureRemoteApp
import com.secrm.core.terminal.TerminalBridge
import com.secrm.core.terminal.TerminalSurface
import com.secrm.design.*

@Composable
fun TerminalScreen(app: SecureRemoteApp, onBack: () -> Unit) {
    val bridge = remember { TerminalBridge(app.ssh) }
    var ready by remember { mutableStateOf<Boolean?>(null) }
    var selecting by remember { mutableStateOf<String?>(null) }
    selecting?.let { TranscriptSelector(it) { selecting = null } }

    Column(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black).safeDrawingPadding()) {
        Row(
            Modifier.fillMaxWidth().height(40.dp).padding(horizontal = Space.sm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, "Back", tint = Palette.TextTertiary)
            }
            Spacer(Modifier.weight(1f))
            val ctx = androidx.compose.ui.platform.LocalContext.current
            Box(Modifier.clickableNoRipple { selecting = bridge.transcript() }.padding(Space.sm)) {
                Text("Select text", style = SecrmType.MonoSmall, color = Palette.Indigo)
            }
            Box(Modifier.clickableNoRipple {
                (ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                    .setPrimaryClip(android.content.ClipData.newPlainText("command output", bridge.lastOutput()))
            }.padding(Space.sm)) {
                Text("Copy output", style = SecrmType.MonoSmall, color = Palette.Indigo)
            }
        }
        Box(Modifier.weight(1f)) {
            TerminalSurface(bridge = bridge, onReady = { ready = it }, modifier = Modifier.fillMaxSize())
            if (ready == false) Text("Could not open a shell. Go back and reconnect.",
                style = SecrmType.Body, color = Palette.Danger, modifier = Modifier.padding(Space.md))
        }
        // Extra-keys toolbar
        Row(
            Modifier.fillMaxWidth()
                .padding(horizontal = Space.md, vertical = Space.sm)
                .background(Palette.Surface2.copy(alpha = 0.9f), RoundedCornerShape(Space.pillRadius))
                .padding(horizontal = Space.sm, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Key("Esc") { bridge.send("\u001B") }
            Key("^C") { bridge.send("\u0003") }
            Key("Tab") { bridge.send("\t") }
            Spacer(Modifier.weight(1f))
            Key("◀") { bridge.send("\u001B[D") }
            Key("▲") { bridge.send("\u001B[A") }
            Key("▼") { bridge.send("\u001B[B") }
            Key("▶") { bridge.send("\u001B[C") }
            Spacer(Modifier.weight(1f))
            Key("⏎") { bridge.send("\r") }
        }
    }
}

@Composable
private fun Key(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier.size(width = 40.dp, height = 36.dp).clickableNoRipple(onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(label, style = SecrmType.MonoSmall, color = Palette.TextPrimary)
    }
}

/**
 * The session as plain text in a native, read-only text view. Android's own selection handles
 * scroll the view when dragged to its top or bottom edge, so a selection can span any length.
 */
@Composable
private fun TranscriptSelector(text: String, onClose: () -> Unit) {
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onClose,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black).safeDrawingPadding()) {
            var view by remember { mutableStateOf<android.widget.EditText?>(null) }
            Row(
                Modifier.fillMaxWidth().height(40.dp).padding(horizontal = Space.sm),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onClose) {
                    Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, "Back", tint = Palette.TextTertiary)
                }
                Text("Long-press, then drag a handle to the edge to scroll", style = SecrmType.MonoSmall,
                    color = Palette.TextTertiary, modifier = Modifier.weight(1f))
                Box(Modifier.clickableNoRipple { view?.let { it.requestFocus(); it.selectAll() } }.padding(Space.sm)) {
                    Text("All", style = SecrmType.MonoSmall, color = Palette.Indigo)
                }
            }
            androidx.compose.ui.viewinterop.AndroidView(
                modifier = Modifier.fillMaxSize().padding(horizontal = Space.sm),
                factory = { ctx ->
                    android.widget.EditText(ctx).apply {
                        setText(text)
                        // Selectable but not editable, and no keyboard.
                        keyListener = null
                        showSoftInputOnFocus = false
                        setTextIsSelectable(true)
                        isVerticalScrollBarEnabled = true
                        gravity = android.view.Gravity.TOP or android.view.Gravity.START
                        typeface = android.graphics.Typeface.MONOSPACE
                        textSize = 12f
                        setTextColor(android.graphics.Color.WHITE)
                        setHighlightColor(0x665B6CFF)
                        background = null
                        setPadding(0, 0, 0, 0)
                        post { setSelection(this.text.length) } // open at the latest output
                        view = this
                    }
                },
            )
        }
    }
}
