package com.secrm.ui.terminal

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import com.secrm.HostSession
import com.secrm.SecureRemoteApp
import com.secrm.ui.HostPager
import com.secrm.core.terminal.TerminalBridge
import com.secrm.core.terminal.TerminalSurface
import com.secrm.design.*

/** Terminal tab: a live SSH shell per connected host, swipe between them. Each shell survives tab switches. */
@Composable
fun TerminalScreen(app: SecureRemoteApp, onOpenHosts: () -> Unit, onOpenFiles: () -> Unit) {
    val hosts by app.sessions.connected.collectAsState()
    if (hosts.isEmpty()) {
        com.secrm.ui.common.StateMessage(
            SecrmIcons.Terminal, "No active connection",
            "Connect to a host to open a shell.",
            action = "Choose a host" to onOpenHosts,
        )
        return
    }
    HostPager(app, hosts) { host -> TerminalPage(host, onOpenFiles) }
}

@Composable
private fun TerminalPage(host: HostSession, onOpenFiles: () -> Unit) {
    val bridge = host.terminal() ?: return
    var ready by remember(bridge) { mutableStateOf<Boolean?>(null) }
    var selecting by remember { mutableStateOf<String?>(null) }
    selecting?.let { TranscriptSelector(it) { selecting = null } }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val imeVisible = WindowInsets.ime.getBottom(androidx.compose.ui.platform.LocalDensity.current) > 0

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding()) {
        // Hidden while the keyboard is up, so the terminal gets the room.
        AnimatedVisibility(visible = !imeVisible) { Row(
            Modifier.fillMaxWidth().height(48.dp).padding(horizontal = Space.md),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ModeSwitch(onFiles = onOpenFiles)
            Text(host.target, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(horizontal = Space.md))
            TextButton({ selecting = bridge.transcript() }) { Text("Select") }
            TextButton({
                (ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                    .setPrimaryClip(android.content.ClipData.newPlainText("command output", bridge.lastOutput()))
            }) { Text("Copy output") }
        } }
        // The terminal itself is always dark, whatever the app theme.
        Box(Modifier.weight(1f).background(androidx.compose.ui.graphics.Color.Black)) {
            TerminalSurface(bridge = bridge, onReady = { ready = it }, modifier = Modifier.fillMaxSize())
            if (ready == false) Text("Could not open a shell. Go back and reconnect.",
                style = MaterialTheme.typography.bodyMedium, color = androidx.compose.ui.graphics.Color(0xFFFF8A80), modifier = Modifier.padding(Space.md))
        }
        // Extra-keys toolbar
        Row(
            Modifier.fillMaxWidth()
                .padding(horizontal = Space.md, vertical = 4.dp)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.shapes.extraLarge)
                .padding(horizontal = Space.sm, vertical = 2.dp),
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
        modifier = Modifier.size(width = 40.dp, height = 32.dp).tappable(onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(label, style = MaterialTheme.mono, color = MaterialTheme.colorScheme.onSurface)
    }
}

/**
 * The session as lines in a fast-scrolling list. Long-press a line and drag to select; holding the
 * finger near (or past) the top or bottom edge scrolls, faster the further in. Tap a line to move
 * the selection's end there, so a selection can also be made by scrolling and tapping.
 */
@Composable
private fun TranscriptSelector(text: String, onClose: () -> Unit) {
    val lines = remember(text) { text.lines() }
    val list = rememberLazyListState(initialFirstVisibleItemIndex = lines.lastIndex)
    var anchor by remember { mutableIntStateOf(-1) }
    var end by remember { mutableIntStateOf(-1) }
    var dragY by remember { mutableStateOf<Float?>(null) }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    fun lineAt(y: Float) = list.layoutInfo.visibleItemsInfo.let { v -> (v.firstOrNull { y < it.offset + it.size } ?: v.lastOrNull())?.index }

    // Edge auto-scroll while dragging: px/second from the finger's depth into the edge zone (time-based,
    // so the same on 60 and 120 Hz screens): ~40 lines/s at the zone's start, ~300 lines/s past the edge.
    LaunchedEffect(dragY != null) {
        var last = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            val dt = (now - last) / 1e9f; last = now
            val y = dragY ?: break
            val h = list.layoutInfo.viewportSize.height
            val zone = h * 0.2f
            val depth = when { y < zone -> y - zone; y > h - zone -> y - (h - zone); else -> 0f } / zone // -1..1 (beyond when past the edge)
            if (depth != 0f) {
                val f = kotlin.math.abs(depth).coerceAtMost(2f)
                list.scrollBy(kotlin.math.sign(depth) * (1600f + 4500f * f * f) * dt)
                lineAt(y)?.let { end = it }
            }
        }
    }

    androidx.compose.ui.window.Dialog(
        onDismissRequest = onClose,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding()) {
            Row(
                Modifier.fillMaxWidth().height(40.dp).padding(horizontal = Space.sm),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onClose) {
                    Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, "Back", tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
                }
                Text(if (anchor < 0) "Long-press a line, then drag" else "${kotlin.math.abs(end - anchor) + 1} lines",
                    style = MaterialTheme.mono, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f), modifier = Modifier.weight(1f))
                Box(Modifier.tappable { anchor = 0; end = lines.lastIndex }.padding(Space.sm)) {
                    Text("All", style = MaterialTheme.mono, color = MaterialTheme.colorScheme.primary)
                }
                Box(Modifier.tappable {
                    if (anchor < 0) return@tappable
                    (ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                        .setPrimaryClip(android.content.ClipData.newPlainText("terminal", lines.subList(minOf(anchor, end), maxOf(anchor, end) + 1).joinToString("\n")))
                    onClose()
                }.padding(Space.sm)) {
                    Text("Copy", style = MaterialTheme.mono, color = if (anchor < 0) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f) else MaterialTheme.colorScheme.primary)
                }
            }
            LazyColumn(
                state = list,
                modifier = Modifier.fillMaxSize().padding(horizontal = Space.sm)
                    .pointerInput(lines) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = { o -> lineAt(o.y)?.let { anchor = it; end = it }; dragY = o.y },
                            onDrag = { c, _ -> dragY = c.position.y; lineAt(c.position.y)?.let { end = it } },
                            onDragEnd = { dragY = null },
                            onDragCancel = { dragY = null },
                        )
                    }
                    .pointerInput(lines) { detectTapGestures { o -> if (anchor >= 0) lineAt(o.y)?.let { end = it } } },
            ) {
                items(lines.size) { i ->
                    val selected = anchor >= 0 && i in minOf(anchor, end)..maxOf(anchor, end)
                    Text(lines[i], color = MaterialTheme.colorScheme.onSurface,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, fontSize = 12.sp,
                        modifier = Modifier.fillMaxWidth().background(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.4f) else androidx.compose.ui.graphics.Color.Transparent))
                }
            }
        }
    }
}

/** SSH | SFTP switch for the connected host: SSH is this screen, SFTP opens the file browser. */
@Composable
private fun ModeSwitch(onFiles: () -> Unit) {
    SingleChoiceSegmentedButtonRow {
        SegmentedButton(selected = true, onClick = {}, shape = SegmentedButtonDefaults.itemShape(0, 2), icon = {}) { Text("SSH") }
        SegmentedButton(selected = false, onClick = onFiles, shape = SegmentedButtonDefaults.itemShape(1, 2), icon = {}) { Text("SFTP") }
    }
}
