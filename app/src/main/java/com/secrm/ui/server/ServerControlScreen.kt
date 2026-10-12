package com.secrm.ui.server

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.secrm.SecureRemoteApp
import com.secrm.design.*
import com.secrm.service.McpServerService

@Composable
fun ServerControlScreen(app: SecureRemoteApp, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var running by remember { mutableStateOf(false) }
    val bearer by remember { mutableStateOf(app.config.mcpBearerToken.orEmpty()) }

    Column(
        Modifier.fillMaxSize().background(Palette.Void)
            .verticalScroll(rememberScrollState())
            .padding(Space.screenPadding),
        verticalArrangement = Arrangement.spacedBy(Space.lg)
    ) {
        Spacer(Modifier.height(Space.xl))
        Text("MCP Server", style = SecrmType.Display, color = Palette.TextPrimary)
        Text("Expose your saved SSH connections to AI clients.",
            style = SecrmType.Body, color = Palette.TextSecondary)

        GlassCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(Space.lg), verticalArrangement = Arrangement.spacedBy(Space.md)) {
                Text("Status", style = SecrmType.Caption, color = Palette.TextSecondary)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(if (running) ConnectionVisual.Up else ConnectionVisual.Idle)
                    Spacer(Modifier.width(Space.sm))
                    Text(
                        if (running) "127.0.0.1:7823 · 7824" else "Stopped",
                        style = SecrmType.MonoSmall, color = Palette.TextPrimary
                    )
                }
                if (running) {
                    PillButton("Stop server", PillStyle.Danger, Modifier.fillMaxWidth()) {
                        McpServerService.stop(ctx); running = false
                    }
                } else {
                    PillButton("Start server", PillStyle.Primary, Modifier.fillMaxWidth()) {
                        McpServerService.start(ctx); running = true
                    }
                }
            }
        }

        GlassCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(Space.lg), verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                Text("Bearer token", style = SecrmType.Caption, color = Palette.TextSecondary)
                Text(bearer.ifBlank { "(generated on first start)" },
                    style = SecrmType.MonoSmall, color = Palette.TextPrimary)
                if (bearer.isNotBlank()) {
                    PillButton("Copy", PillStyle.Ghost, Modifier.fillMaxWidth()) {
                        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("mcp bearer", bearer))
                    }
                }
            }
        }

        GlassCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(Space.lg), verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                Text("Connect Claude", style = SecrmType.Caption, color = Palette.TextSecondary)
                Text(
                    "1. Forward 7824 to the public internet (Tailscale Funnel / ngrok / VPS relay).\n\n" +
                    "2. claude.ai → Settings → Connectors → Add Custom Connector.\n" +
                    "   URL: https://<your-public-host>/mcp\n" +
                    "   Auth: Bearer <token above>\n\n" +
                    "3. Connector syncs to Claude Mobile.\n\n" +
                    "Anthropic's cloud dials from outside — a 100.x Tailscale IP will not reach it.",
                    style = SecrmType.Body, color = Palette.TextPrimary
                )
            }
        }
        Spacer(Modifier.height(Space.xxl))
    }
}
