package com.secrm.ui.server

import android.content.ClipData
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.secrm.SecureRemoteApp
import com.secrm.core.tunnel.Tunnel
import com.secrm.design.*
import com.secrm.service.McpServerService
import com.secrm.ui.agent.BitwardenScreen
import com.secrm.ui.keys.KeyManagementScreen
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerControlScreen(app: SecureRemoteApp, onBack: () -> Unit) {
    var showAgent by remember { mutableStateOf(false) }
    var showKeys by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    if (showAgent) { BitwardenScreen(app, onBack = { showAgent = false }); return }
    if (showKeys) { KeyManagementScreen(app, onBack = { showKeys = false }); return }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings", style = SecrmType.Title, color = Palette.TextPrimary) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, "Back", tint = Palette.TextSecondary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Palette.Void)
            )
        },
        containerColor = Palette.Void
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
            item { SectionLabel("PRIVATE NETWORK") }
            item { TailscaleSection(app) }
            item { WireGuardSection(app) }

            item { SectionLabel("SSH KEYS") }
            item { KeyEntryCard(onOpen = { showKeys = true }) }

            item { SectionLabel("SSH AGENT") }
            item { AgentEntryCard(onOpen = { showAgent = true }) }

            item { SectionLabel("MCP SERVER") }
            item { McpSection(app) }

            item { SectionLabel("ABOUT") }
            item { AboutSection() }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = SecrmType.Caption.copy(fontWeight = FontWeight.Medium),
        color = Palette.TextTertiary,
        modifier = Modifier.padding(top = Space.sm)
    )
}

// ── Private network ───────────────────────────────────

@Composable
private fun TailscaleSection(app: SecureRemoteApp) {
    val ts = app.tailscale
    val st by ts.state.collectAsState()
    val uri = LocalUriHandler.current
    var public by remember { mutableStateOf(ts.public) }
    NetworkCard("Tailscale", st,
        "Joins your tailnet in-app (no VPN). SSH reaches tailnet hosts and subnet routes; " +
        "the MCP server is served at https://<this phone>/mcp."
    ) {
        if (st is Tunnel.State.NeedsAuth)
            PillButton("Sign in to Tailscale", PillStyle.Primary, Modifier.fillMaxWidth()) {
                uri.openUri((st as Tunnel.State.NeedsAuth).url)
            }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Public (Funnel) for claude.ai", style = SecrmType.Body,
                color = Palette.TextPrimary, modifier = Modifier.weight(1f))
            val ctx = LocalContext.current
            // Funnel forwards to the MCP server, so it must be running.
            Switch(public, { public = it; ts.public = it; if (it) McpServerService.start(ctx) })
        }
        if (public) ts.funnelProblem?.let { Text(it, style = SecrmType.Caption, color = Palette.Warning) }
        (st as? Tunnel.State.Up)?.takeIf { public && ts.funnelProblem == null }?.let { up ->
            val url = "https://${up.name}/mcp"
            val ctx = LocalContext.current
            Text(url, style = SecrmType.MonoSmall, color = Palette.TextPrimary)
            PillButton("Copy MCP URL", PillStyle.Primary, Modifier.fillMaxWidth()) {
                (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText("MCP URL", url))
            }
        }
        OnOff(st, ts::start, ts::stop)
        if (st !is Tunnel.State.Idle) PillButton("Sign out", PillStyle.Ghost, Modifier.fillMaxWidth()) { ts.logout() }
        val ctx = LocalContext.current
        PillButton("Copy logs", PillStyle.Ghost, Modifier.fillMaxWidth()) {
            (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                .setPrimaryClip(ClipData.newPlainText("tailscale logs", ts.logs()))
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
    NetworkCard("WireGuard", st,
        "Userspace tunnel from a wg-quick config (e.g. exported by your router). " +
        "SSH reaches the peer's AllowedIPs through it; other apps are unaffected."
    ) {
        if (configured) {
            wg.publicKey?.let { key ->
                Text("Phone public key", style = SecrmType.Caption, color = Palette.TextSecondary)
                Text(key, style = SecrmType.MonoSmall, color = Palette.TextPrimary)
                PillButton("Copy public key", PillStyle.Ghost, Modifier.fillMaxWidth()) {
                    (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                        .setPrimaryClip(ClipData.newPlainText("wg public key", key))
                }
            }
            OnOff(st, wg::start, wg::stop)
            PillButton("Remove config", PillStyle.Ghost, Modifier.fillMaxWidth()) { wg.forget(); configured = false }
        } else {
            OutlinedTextField(config, { config = it }, label = { Text("Paste wg-quick config") },
                minLines = 4, visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth())
            PillButton("Save", PillStyle.Primary, Modifier.fillMaxWidth(), config.isNotBlank()) {
                problem = wg.configure(config)
                if (problem == null) { configured = true; config = "" }
            }
            problem?.let { Text(it, style = SecrmType.Caption, color = Palette.Danger) }
        }
    }
}

@Composable
private fun NetworkCard(title: String, st: Tunnel.State, blurb: String, content: @Composable ColumnScope.() -> Unit) {
    GlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(Space.lg), verticalArrangement = Arrangement.spacedBy(Space.sm)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = SecrmType.Body.copy(fontWeight = FontWeight.Medium),
                    color = Palette.TextPrimary, modifier = Modifier.weight(1f))
                StatusDot(when (st) {
                    is Tunnel.State.Up -> ConnectionVisual.Up
                    is Tunnel.State.Starting -> ConnectionVisual.Connecting
                    is Tunnel.State.NeedsAuth -> ConnectionVisual.Warning
                    is Tunnel.State.Failed -> ConnectionVisual.Error
                    Tunnel.State.Idle -> ConnectionVisual.Idle
                })
                Spacer(Modifier.width(Space.sm))
                Text(when (st) {
                    is Tunnel.State.Up -> "Connected"
                    is Tunnel.State.Starting -> "Connecting…"
                    is Tunnel.State.NeedsAuth -> "Sign-in needed"
                    is Tunnel.State.Failed -> "Error"
                    Tunnel.State.Idle -> "Off"
                }, style = SecrmType.Label, color = Palette.TextSecondary)
            }
            Text(blurb, style = SecrmType.Caption, color = Palette.TextSecondary)
            if (st is Tunnel.State.Up) Text("${st.name} · ${st.address}", style = SecrmType.MonoSmall, color = Palette.TextSecondary)
            if (st is Tunnel.State.Failed) Text(st.reason, style = SecrmType.MonoSmall, color = Palette.Danger)
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
private fun McpSection(app: SecureRemoteApp) {
    val ctx = LocalContext.current
    var running by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }
    val bearer = app.config.mcpBearerToken.orEmpty()

    GlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(Space.lg)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("MCP Server", style = SecrmType.Body.copy(fontWeight = FontWeight.Medium),
                    color = Palette.TextPrimary)
                Spacer(Modifier.weight(1f))
                StatusDot(if (running) ConnectionVisual.Up else ConnectionVisual.Idle)
                Spacer(Modifier.width(Space.sm))
                Text(if (running) "Running" else "Stopped",
                    style = SecrmType.Label, color = Palette.TextSecondary)
            }
            Spacer(Modifier.height(Space.xs))
            Text(
                "Expose your saved SSH connections to Claude and other MCP clients.",
                style = SecrmType.Body, color = Palette.TextSecondary
            )

            Spacer(Modifier.height(Space.md))
            if (running) {
                PillButton("Stop server", PillStyle.Danger, Modifier.fillMaxWidth()) {
                    McpServerService.stop(ctx); running = false
                }
            } else {
                PillButton("Start server", PillStyle.Primary, Modifier.fillMaxWidth()) {
                    McpServerService.start(ctx); running = true
                }
            }

            // claude.ai connector (OAuth via pairing code)
            val ts by app.tailscale.state.collectAsState()
            (ts as? com.secrm.core.tunnel.Tunnel.State.Up)?.takeIf { app.tailscale.public }?.let {
                Spacer(Modifier.height(Space.md))
                Text("Claude connector URL", style = SecrmType.Caption, color = Palette.TextSecondary)
                Text("https://${it.name}/mcp", style = SecrmType.MonoSmall, color = Palette.TextPrimary)
            }
            var pairing by remember { mutableStateOf<String?>(null) }
            Spacer(Modifier.height(Space.md))
            Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                PillButton("Pair Claude", PillStyle.Primary, Modifier.weight(1f)) { pairing = app.oauth.newPairingCode() }
                PillButton("Disconnect Claude", PillStyle.Ghost, Modifier.weight(1f)) { app.oauth.revokeAll(); pairing = null }
            }
            pairing?.let {
                Text("Pairing code (10 min, single use)", style = SecrmType.Caption, color = Palette.TextSecondary)
                Text(it, style = SecrmType.Title, color = Palette.TextPrimary)
            }

            Spacer(Modifier.height(Space.md))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Setup instructions", style = SecrmType.Caption, color = Palette.TextSecondary)
                TextButton(onClick = { expanded = !expanded }) {
                    Text(if (expanded) "Hide" else "Show", color = Palette.Indigo)
                }
            }

            if (expanded) {
                Spacer(Modifier.height(Space.sm))
                Text("Endpoints", style = SecrmType.Label,
                    color = Palette.TextPrimary, fontWeight = FontWeight.Medium)
                Text(
                    "Local    http://127.0.0.1:7823/mcp\n" +
                    "Remote   http://127.0.0.1:7824/mcp   (forward this one)",
                    style = SecrmType.MonoSmall, color = Palette.TextPrimary
                )

                Spacer(Modifier.height(Space.md))
                Text("Bearer token", style = SecrmType.Label,
                    color = Palette.TextPrimary, fontWeight = FontWeight.Medium)
                Text(bearer.ifBlank { "(generated on first start)" },
                    style = SecrmType.MonoSmall, color = Palette.TextPrimary)
                if (bearer.isNotBlank()) {
                    Spacer(Modifier.height(Space.xs))
                    PillButton("Copy token", PillStyle.Ghost, Modifier.fillMaxWidth()) {
                        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("mcp bearer", bearer))
                    }
                }

                Spacer(Modifier.height(Space.md))
                Card(
                    colors = CardDefaults.cardColors(containerColor = Palette.Surface2),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(Space.md), verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                        Text("Connect from Claude",
                            style = SecrmType.Label, color = Palette.TextPrimary,
                            fontWeight = FontWeight.Medium)
                        Text(
                            "1.  Start the server, connect Tailscale and turn on Public (Funnel).\n" +
                            "2.  claude.ai → Settings → Connectors → Add custom connector,\n" +
                            "    URL: the Claude connector URL above.\n" +
                            "3.  Tap Connect. When asked, tap Pair Claude here and enter the code.\n\n" +
                            "Claude's servers reach the phone from the internet, so Funnel is\n" +
                            "needed; your servers stay private and are reached through each\n" +
                            "connection's Route through setting.",
                            style = SecrmType.Body, color = Palette.TextPrimary
                        )
                    }
                }

                Spacer(Modifier.height(Space.sm))
                Text(
                    "Tip: gate each connection’s MCP exposure in its edit sheet. " +
                    "Read-only boxes should have ssh.exec turned off so agents cannot change state.",
                    style = SecrmType.Caption, color = Palette.TextTertiary
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
                style = SecrmType.Body.copy(fontWeight = FontWeight.Medium),
                color = Palette.TextPrimary)
            Text("Version 1.0.0 · debug build",
                style = SecrmType.Caption, color = Palette.TextSecondary)
            Spacer(Modifier.height(Space.sm))
            Text(
                "Local-first. No telemetry. Credentials stored in Android Keystore.",
                style = SecrmType.Caption, color = Palette.TextTertiary
            )
        }
    }
}

@Composable
private fun AgentEntryCard(onOpen: () -> Unit) {
    GlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(Space.lg)) {
            Text("Bitwarden",
                style = SecrmType.Body.copy(fontWeight = FontWeight.Medium),
                color = Palette.TextPrimary)
            Spacer(Modifier.height(Space.xs))
            Text(
                "Use SSH keys stored in your Bitwarden vault. Unlocks with " +
                "fingerprint; keys stay in memory only.",
                style = SecrmType.Body, color = Palette.TextSecondary
            )
            Spacer(Modifier.height(Space.md))
            PillButton("Open", PillStyle.Primary, Modifier.fillMaxWidth()) { onOpen() }
        }
    }
}


@Composable
private fun KeyEntryCard(onOpen: () -> Unit) {
    GlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(Space.lg)) {
            Text("SSH Keys",
                style = SecrmType.Body.copy(fontWeight = FontWeight.Medium),
                color = Palette.TextPrimary)
            Spacer(Modifier.height(Space.xs))
            Text(
                "Import private keys for passwordless authentication. Keys are " +
                "stored in the Android Keystore and never leave the device.",
                style = SecrmType.Body, color = Palette.TextSecondary
            )
            Spacer(Modifier.height(Space.md))
            PillButton("Manage keys", PillStyle.Primary, Modifier.fillMaxWidth()) { onOpen() }
        }
    }
}
