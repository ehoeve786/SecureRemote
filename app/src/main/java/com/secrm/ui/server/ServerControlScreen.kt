package com.secrm.ui.server

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.secrm.SecureRemoteApp
import com.secrm.core.tunnel.Tunnel
import com.secrm.core.tunnel.TunnelConfig
import com.secrm.design.*
import com.secrm.service.McpServerService
import com.secrm.ui.agent.AgentSetupScreen
import com.secrm.ui.keys.KeyManagementScreen
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerControlScreen(app: SecureRemoteApp, onBack: () -> Unit) {
    val ts by app.tunnel.state.collectAsState()
    var showAgent by remember { mutableStateOf(false) }
    var showKeys by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    if (showAgent) { AgentSetupScreen(onBack = { showAgent = false }); return }
    if (showKeys) { KeyManagementScreen(app, onBack = { showKeys = false }); return }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings", style = SecrmType.Title, color = Palette.TextPrimary) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Rounded.ChevronLeft, "Back", tint = Palette.TextSecondary)
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
            item { SectionLabel("TAILSCALE TUNNEL") }
            item { TailscaleSection(app, ts) }

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

// ── Tailscale ─────────────────────────────────────────────

@Composable
private fun TailscaleSection(app: SecureRemoteApp, ts: Tunnel.State) {
    val scope = rememberCoroutineScope()
    var authKey by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf(ts !is Tunnel.State.Up) }

    GlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(Space.lg)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Tailscale", style = SecrmType.Body.copy(fontWeight = FontWeight.Medium),
                    color = Palette.TextPrimary)
                Spacer(Modifier.weight(1f))
                StatusDot(when (ts) {
                    is Tunnel.State.Up -> ConnectionVisual.Up
                    is Tunnel.State.Starting -> ConnectionVisual.Connecting
                    is Tunnel.State.Failed -> ConnectionVisual.Error
                    else -> ConnectionVisual.Idle
                })
                Spacer(Modifier.width(Space.sm))
                Text(
                    when (val s = ts) {
                        is Tunnel.State.Up -> "Connected"
                        is Tunnel.State.Starting -> "Connecting…"
                        is Tunnel.State.Failed -> "Error"
                        else -> "Not connected"
                    },
                    style = SecrmType.Label, color = Palette.TextSecondary
                )
            }

            if (ts is Tunnel.State.Up) {
                Spacer(Modifier.height(Space.sm))
                Text("${ts.selfIp} · SOCKS5 :${ts.socksPort}",
                    style = SecrmType.MonoSmall, color = Palette.TextSecondary)
            }
            if (ts is Tunnel.State.Failed) {
                Spacer(Modifier.height(Space.sm))
                Text(ts.reason, style = SecrmType.MonoSmall, color = Palette.Danger)
            }

            Spacer(Modifier.height(Space.md))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Configuration", style = SecrmType.Caption, color = Palette.TextSecondary)
                TextButton(onClick = { expanded = !expanded }) {
                    Text(if (expanded) "Hide" else "Show", color = Palette.Indigo)
                }
            }

            if (expanded) {
                Spacer(Modifier.height(Space.sm))

                // Instructions
                Card(
                    colors = CardDefaults.cardColors(containerColor = Palette.Surface2),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(Space.md), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                        Text("How to get an auth key", style = SecrmType.Label,
                            color = Palette.TextPrimary, fontWeight = FontWeight.Medium)
                        Text(
                            "1. Sign in at login.tailscale.com/admin/settings/keys\n" +
                            "2. Click “Generate auth key…”\n" +
                            "3. Set Reusable → Yes, Ephemeral → No\n" +
                            "4. Copy the tskey-auth-… value and paste it below",
                            style = SecrmType.Body, color = Palette.TextSecondary
                        )
                    }
                }

                Spacer(Modifier.height(Space.md))
                OutlinedTextField(
                    value = authKey,
                    onValueChange = { authKey = it },
                    label = { Text("Auth Key (tskey-auth-…)") },
                    placeholder = { Text("tskey-auth-xxxxxxxxxxxx") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(Space.md))
                if (ts is Tunnel.State.Up) {
                    PillButton("Disconnect", PillStyle.Danger, Modifier.fillMaxWidth()) {
                        scope.launch { app.tunnel.stop() }
                    }
                } else {
                    PillButton(
                        "Connect",
                        PillStyle.Primary,
                        Modifier.fillMaxWidth(),
                        enabled = authKey.isNotBlank() || app.config.mcpBearerToken != null
                    ) {
                        app.config.sshConnectionsJson // touch
                        scope.launch {
                            app.tunnel.start(TunnelConfig(authKey = authKey))
                        }
                    }
                }

                Spacer(Modifier.height(Space.sm))
                Text(
                    "Note: the current build uses a mock tunnel — the connection will report success without actually joining a tailnet. " +
                    "Real Tailscale wiring ships once the userspace AAR is added.",
                    style = SecrmType.Caption, color = Palette.TextTertiary
                )
            }
        }
    }
}

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
                            "1.  Expose port 7824 to the public internet.\n" +
                            "    Easiest: install Tailscale on this phone and run\n" +
                            "        tailscale funnel 7824\n" +
                            "    Alternatives: ngrok http 7824, Cloudflare Tunnel,\n" +
                            "    or a small VPS that dials back over SSH.\n\n" +
                            "2.  Note the public URL, e.g.\n" +
                            "        https://your-phone.ts.net\n\n" +
                            "3.  On claude.ai → Settings → Connectors → Add custom connector.\n" +
                            "        URL:  https://your-phone.ts.net/mcp\n" +
                            "        Auth: Bearer <token above>\n\n" +
                            "4.  Save. The connector syncs to Claude Mobile automatically.\n\n" +
                            "Why the cloud dials in: Anthropic’s servers reach your\n" +
                            "endpoint from outside your network. A Tailscale 100.x IP\n" +
                            "is not reachable from their side — you need a public URL.",
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
            Text("Bitwarden SSH agent",
                style = SecrmType.Body.copy(fontWeight = FontWeight.Medium),
                color = Palette.TextPrimary)
            Spacer(Modifier.height(Space.xs))
            Text(
                "Sign SSH challenges without exposing private keys to this app. " +
                "Requires Bitwarden + Termux on the same device.",
                style = SecrmType.Body, color = Palette.TextSecondary
            )
            Spacer(Modifier.height(Space.md))
            PillButton("Set up agent", PillStyle.Primary, Modifier.fillMaxWidth()) { onOpen() }
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
