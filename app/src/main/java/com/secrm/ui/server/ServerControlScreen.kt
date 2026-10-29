package com.secrm.ui.server

import android.content.ClipData
import com.secrm.ui.sensitiveClip
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.secrm.SecureRemoteApp
import com.secrm.core.tunnel.Tunnel
import com.secrm.design.*
import com.secrm.service.McpServerService
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerControlScreen(app: SecureRemoteApp, onBack: (() -> Unit)? = null, onOpenAudit: () -> Unit = {}, onOpenJobs: () -> Unit = {}) {
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    if (onBack != null) IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, "Back", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(
                start = Space.screenPadding,
                end = Space.screenPadding,
                top = Space.md,
                bottom = Space.xxl
            ),
            verticalArrangement = Arrangement.spacedBy(Space.lg)
        ) {
            item { SectionHeader("Appearance") }
            item { AppearanceSection(app.ui) }

            item { SectionHeader("Private network") }
            item { TailscaleSection(app) }
            item { WireGuardSection(app) }

            item { SectionHeader("MCP server") }
            item { McpSection(app, onOpenAudit) }
            item { PillButton("Scheduled jobs and health checks", PillStyle.Ghost, Modifier.fillMaxWidth(), onClick = onOpenJobs) }

            item { SectionHeader("About") }
            item { AboutSection() }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppearanceSection(ui: com.secrm.data.UiPrefs) {
    GlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(Space.lg), verticalArrangement = Arrangement.spacedBy(Space.md)) {
            val modes = ThemeMode.values()
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                modes.forEachIndexed { i, m ->
                    SegmentedButton(
                        selected = ui.mode == m, onClick = { ui.chooseMode(m) },
                        shape = SegmentedButtonDefaults.itemShape(i, modes.size),
                    ) { Text(m.name) }
                }
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Match system colours", style = MaterialTheme.typography.bodyLarge)
                    Text("Use your wallpaper's palette (Material You) instead of the SecureRemote indigo.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(ui.dynamicColor, ui::chooseDynamicColor)
            }
        }
    }
}

// ── Private network ───────────────────────────────────

@Composable
private fun TailscaleSection(app: SecureRemoteApp) {
    val ts = app.tailscale
    val st by ts.state.collectAsState()
    val uri = LocalUriHandler.current
    val ctx = LocalContext.current
    var public by remember { mutableStateOf(ts.public) }
    var confirmPublic by remember { mutableStateOf(false) }
    var askSignOut by remember { mutableStateOf(false) }
    NetworkCard("Tailscale", st,
        "Joins your tailnet in-app (no VPN). SSH reaches tailnet hosts and subnet routes; " +
        "the MCP server is served at https://<this phone>/mcp."
    ) {
        // The URL comes from the control server; only https is opened.
        val authUrl = (st as? Tunnel.State.NeedsAuth)?.url?.takeIf { it.startsWith("https://") }
        if (authUrl != null)
            PillButton("Sign in to Tailscale", PillStyle.Primary, Modifier.fillMaxWidth()) {
                uri.openUri(authUrl)
            }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Public (Funnel) for claude.ai", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
            // Turning Funnel on makes the MCP server reachable from the internet, so it is confirmed first.
            // Turning it off only narrows access, so it applies at once.
            Switch(public, {
                if (it) confirmPublic = true else { public = false; ts.public = false }
            })
        }
        if (confirmPublic) AlertDialog(
            onDismissRequest = { confirmPublic = false },
            title = { Text("Make the MCP server public?") },
            text = {
                Text("Anyone on the internet who has the MCP bearer token, or a valid Claude connection, " +
                    "can run commands on your enabled SSH connections. The pairing page is also public. " +
                    "Only turn this on if you need claude.ai to reach this phone.")
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmPublic = false
                    public = true
                    ts.public = true
                    // Funnel forwards to the MCP server, so it must be running.
                    McpServerService.start(ctx)
                }) { Text("Make public") }
            },
            dismissButton = { TextButton(onClick = { confirmPublic = false }) { Text("Cancel") } },
        )
        if (public) ts.funnelProblem?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.status.warning) }
        (st as? Tunnel.State.Up)?.takeIf { public && ts.funnelProblem == null }?.let { up ->
            val url = "https://${up.name}/mcp"
            Text(url, style = MaterialTheme.mono, color = MaterialTheme.colorScheme.onSurface)
            PillButton("Copy MCP URL", PillStyle.Primary, Modifier.fillMaxWidth()) {
                (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText("MCP URL", url))
            }
        }
        // Android cuts a background app's network unless it runs a foreground service, which
        // would drop the tailnet (and Funnel) as soon as you switch apps.
        OnOff(st, { ts.start(); McpServerService.start(ctx) }, ts::stop)
        if (st !is Tunnel.State.Idle) PillButton("Sign out", PillStyle.Ghost, Modifier.fillMaxWidth()) { askSignOut = true }
        if (askSignOut) ConfirmAction(
            "Sign out of Tailscale?",
            "This phone leaves your tailnet. Signing in again opens the Tailscale login page.",
            "Sign out", onConfirm = { askSignOut = false; ts.logout() }, onDismiss = { askSignOut = false },
        )
        PillButton("Copy logs", PillStyle.Ghost, Modifier.fillMaxWidth()) {
            (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                .setPrimaryClip(sensitiveClip("tailscale logs", ts.logs()))
        }
    }
}

@Composable
private fun WireGuardSection(app: SecureRemoteApp) {
    val wg = app.wireguard
    val st by wg.state.collectAsState()
    val ctx = LocalContext.current
    var configured by remember { mutableStateOf(wg.configured) }
    var config by remember { mutableStateOf("") }
    var problem by remember { mutableStateOf<String?>(null) }
    var askRemove by remember { mutableStateOf(false) }
    NetworkCard("WireGuard", st,
        "Userspace tunnel from a wg-quick config (e.g. exported by your router). " +
        "SSH reaches the peer's AllowedIPs through it; other apps are unaffected."
    ) {
        if (configured) {
            wg.publicKey?.let { key ->
                Text("Phone public key", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(key, style = MaterialTheme.mono, color = MaterialTheme.colorScheme.onSurface)
                PillButton("Copy public key", PillStyle.Ghost, Modifier.fillMaxWidth()) {
                    (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                        .setPrimaryClip(ClipData.newPlainText("wg public key", key))
                }
            }
            OnOff(st, wg::start, wg::stop)
            PillButton("Remove config", PillStyle.Ghost, Modifier.fillMaxWidth()) { askRemove = true }
            if (askRemove) ConfirmAction(
                "Remove the WireGuard config?",
                "This deletes the saved private key. Reconnecting needs the config file again.",
                "Remove", onConfirm = { askRemove = false; wg.forget(); configured = false }, onDismiss = { askRemove = false },
            )
        } else {
            OutlinedTextField(config, { config = it }, label = { Text("Paste wg-quick config") },
                minLines = 4, visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(autoCorrect = false),
                modifier = Modifier.fillMaxWidth())
            PillButton("Save", PillStyle.Primary, Modifier.fillMaxWidth(), config.isNotBlank()) {
                problem = wg.configure(config)
                if (problem == null) { configured = true; config = "" }
            }
            problem?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable
private fun NetworkCard(title: String, st: Tunnel.State, blurb: String, content: @Composable ColumnScope.() -> Unit) {
    GlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(Space.lg), verticalArrangement = Arrangement.spacedBy(Space.sm)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                StatusPill(
                    when (st) {
                        is Tunnel.State.Up -> ConnectionVisual.Up
                        is Tunnel.State.Starting -> ConnectionVisual.Connecting
                        is Tunnel.State.NeedsAuth -> ConnectionVisual.Warning
                        is Tunnel.State.Failed -> ConnectionVisual.Error
                        Tunnel.State.Idle -> ConnectionVisual.Idle
                    },
                    when (st) {
                        is Tunnel.State.Up -> "Connected"
                        is Tunnel.State.Starting -> "Connecting…"
                        is Tunnel.State.NeedsAuth -> "Sign-in needed"
                        is Tunnel.State.Failed -> "Error"
                        Tunnel.State.Idle -> "Off"
                    },
                )
            }
            Text(blurb, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (st is Tunnel.State.Up) Text("${st.name} · ${st.address}", style = MaterialTheme.mono, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (st is Tunnel.State.Failed) Text(st.reason, style = MaterialTheme.mono, color = MaterialTheme.colorScheme.error)
            content()
        }
    }
}

@Composable
private fun OnOff(st: Tunnel.State, on: () -> Unit, off: () -> Unit) =
    if (st is Tunnel.State.Idle || st is Tunnel.State.Failed)
        PillButton("Connect", PillStyle.Primary, Modifier.fillMaxWidth()) { on() }
    else PillButton("Disconnect", PillStyle.Danger, Modifier.fillMaxWidth()) { off() }

// ── MCP Server ────────────────────────────────────────────

@Composable
private fun McpSection(app: SecureRemoteApp, onOpenAudit: () -> Unit) {
    val ctx = LocalContext.current
    val running by McpServerService.running.collectAsState()
    var expanded by remember { mutableStateOf(false) }
    var bearer by remember { mutableStateOf(app.config.mcpBearerToken.orEmpty()) }
    var askRotate by remember { mutableStateOf(false) }
    val localToken = app.config.mcpLocalToken.orEmpty()

    GlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(Space.lg)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("MCP server", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                StatusPill(if (running) ConnectionVisual.Up else ConnectionVisual.Idle, if (running) "Running" else "Stopped")
            }
            Spacer(Modifier.height(Space.xs))
            Text(
                "Expose your saved SSH connections to Claude and other MCP clients.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(Space.md))
            if (running) {
                PillButton("Stop server", PillStyle.Danger, Modifier.fillMaxWidth()) {
                    McpServerService.stop(ctx)
                }
            } else {
                PillButton("Start server", PillStyle.Primary, Modifier.fillMaxWidth()) {
                    McpServerService.start(ctx)
                }
            }

            Spacer(Modifier.height(Space.md))
            Text("Stop after no tool calls for", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(Space.xs))
            val idleChoices = listOf(0 to "Never", 15 to "15 min", 30 to "30 min", 60 to "1 h")
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                idleChoices.forEachIndexed { i, (minutes, label) ->
                    SegmentedButton(
                        selected = app.mcp.idleMinutes == minutes,
                        onClick = { app.mcp.chooseIdleMinutes(minutes) },
                        shape = SegmentedButtonDefaults.itemShape(i, idleChoices.size),
                    ) { Text(label) }
                }
            }
            Spacer(Modifier.height(Space.sm))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Stop when the screen turns off", style = MaterialTheme.typography.bodyLarge)
                    Text("Agents lose access until you start the server again.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(app.mcp.lockOnScreenOff, app.mcp::chooseLockOnScreenOff)
            }

            Spacer(Modifier.height(Space.sm))
            PillButton("View audit log", PillStyle.Ghost, Modifier.fillMaxWidth(), onClick = onOpenAudit)

            AgentTokensSection(app)

            // claude.ai connector (OAuth via pairing code)
            val ts by app.tailscale.state.collectAsState()
            (ts as? com.secrm.core.tunnel.Tunnel.State.Up)?.takeIf { app.tailscale.public }?.let {
                Spacer(Modifier.height(Space.md))
                Text("Claude connector URL", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("https://${it.name}/mcp", style = MaterialTheme.mono, color = MaterialTheme.colorScheme.onSurface)
            }
            var pairing by remember { mutableStateOf<String?>(null) }
            Spacer(Modifier.height(Space.md))
            Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                PillButton("Pair Claude", PillStyle.Primary, Modifier.weight(1f)) { pairing = app.oauth.newPairingCode() }
                PillButton("Disconnect Claude", PillStyle.Ghost, Modifier.weight(1f)) { app.oauth.revokeAll(); pairing = null }
            }
            pairing?.let {
                Text("Pairing code (10 min, single use)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                SecretOrPlaceholder(it)
            }

            Spacer(Modifier.height(Space.md))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Setup instructions", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { expanded = !expanded }) {
                    Text(if (expanded) "Hide" else "Show", color = MaterialTheme.colorScheme.primary)
                }
            }

            if (expanded) {
                Spacer(Modifier.height(Space.sm))
                Text("Endpoints", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Medium)
                Text(
                    "Local    http://127.0.0.1:7823/mcp\n" +
                    "Remote   http://127.0.0.1:7824/mcp   (forward this one)",
                    style = MaterialTheme.mono, color = MaterialTheme.colorScheme.onSurface
                )

                Spacer(Modifier.height(Space.md))
                Text("Local token (on-phone callers only)", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Medium)
                SecretOrPlaceholder(localToken)

                Spacer(Modifier.height(Space.md))
                Text("Bearer token", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Medium)
                SecretOrPlaceholder(bearer)
                if (bearer.isNotBlank()) {
                    Spacer(Modifier.height(Space.xs))
                    PillButton("Copy token", PillStyle.Ghost, Modifier.fillMaxWidth()) {
                        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(sensitiveClip("mcp bearer", bearer))
                    }
                    Spacer(Modifier.height(Space.xs))
                    // Old token stops working at once; MCP clients using it must be given the new one.
                    PillButton("Rotate token", PillStyle.Danger, Modifier.fillMaxWidth()) { askRotate = true }
                    if (askRotate) ConfirmAction(
                        "Rotate the MCP token?",
                        "Every client using the current token stops working until it gets the new one.",
                        "Rotate", onConfirm = {
                            askRotate = false
                            bearer = ByteArray(32).also(java.security.SecureRandom()::nextBytes)
                                .joinToString("") { "%02x".format(it) }
                                .also { app.config.mcpBearerToken = it }
                        }, onDismiss = { askRotate = false },
                    )
                }

                Spacer(Modifier.height(Space.md))
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(Space.md), verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                        Text("Connect from Claude",
                            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.Medium)
                        Text(
                            "1.  Start the server, connect Tailscale and turn on Public (Funnel).\n" +
                            "2.  claude.ai → Settings → Connectors → Add custom connector,\n" +
                            "    URL: the Claude connector URL above.\n" +
                            "3.  Tap Connect. When asked, tap Pair Claude here and enter the code.\n\n" +
                            "Claude's servers reach the phone from the internet, so Funnel is\n" +
                            "needed; your servers stay private and are reached through each\n" +
                            "connection's Route through setting.",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }

                Spacer(Modifier.height(Space.sm))
                Text(
                    "Tip: gate each connection’s MCP exposure in its edit sheet. " +
                    "Read-only boxes should have ssh.exec turned off so agents cannot change state.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }
        }
    }
}

// ── About ─────────────────────────────────────────────────

@Composable
private fun AboutSection() {
    GlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(Space.lg)) {
            Text("Secure Remote Manager",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface)
            Text("Version 1.0.0",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(Space.sm))
            Text(
                "Local-first. No telemetry. Credentials stored in Android Keystore.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
            Spacer(Modifier.height(Space.sm))
            // Accessibility services that are switched on can read the whole terminal screen, secrets included.
            Text(
                "Enabled accessibility services can read everything the terminal shows, passwords included. " +
                    "Turn them off, or keep secrets out of the terminal, if that matters.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
        }
    }
}

/** A confirmation before an action that cannot be undone from the app. */
@Composable
private fun ConfirmAction(title: String, text: String, action: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(action, color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Shows a token masked until the user asks to see it; blank shows the placeholder. */
@Composable
private fun SecretOrPlaceholder(value: String) {
    if (value.isBlank()) {
        Text("(generated on first start)", style = MaterialTheme.mono, color = MaterialTheme.colorScheme.onSurface)
    } else {
        var shown by remember { mutableStateOf(false) }
        Text(if (shown) value else "•".repeat(32), style = MaterialTheme.mono, color = MaterialTheme.colorScheme.onSurface)
        PillButton(if (shown) "Hide" else "Show", PillStyle.Ghost, Modifier.fillMaxWidth()) { shown = !shown }
    }
}

@Composable
private fun AgentTokensSection(app: SecureRemoteApp) {
    var label by remember { mutableStateOf("") }
    var issued by remember { mutableStateOf<String?>(null) }
    var agents by remember { mutableStateOf(app.agents.list()) }

    Spacer(Modifier.height(Space.md))
    Text("Agent tokens", style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Medium)
    Text("One token per agent. Revoking one leaves the others working.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(Space.xs))
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
        OutlinedTextField(
            value = label, onValueChange = { label = it }, singleLine = true,
            label = { Text("Agent name") }, modifier = Modifier.weight(1f),
        )
        PillButton("Create", PillStyle.Primary, enabled = label.isNotBlank() && !app.config.keystoreLost, onClick = {
            issued = app.agents.create(label)
            label = ""
            agents = app.agents.list()
        })
    }
    issued?.let {
        Spacer(Modifier.height(Space.xs))
        Text("Token for the new agent (shown once)", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        SecretOrPlaceholder(it)
    }
    agents.forEach { a ->
        Spacer(Modifier.height(Space.xs))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(a.label, style = MaterialTheme.typography.bodyLarge)
                Text(if (a.revoked) "Revoked" else "Created ${a.created}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (!a.revoked) TextButton(onClick = {
                app.agents.revoke(a.id)
                agents = app.agents.list()
            }) { Text("Revoke", color = MaterialTheme.colorScheme.error) }
        }
    }
}
