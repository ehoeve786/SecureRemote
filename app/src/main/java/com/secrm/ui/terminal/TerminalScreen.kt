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
