package com.secrm.ui.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.secrm.SecureRemoteApp
import com.secrm.core.ssh.SshConfig
import com.secrm.core.ssh.SshState
import com.secrm.core.tunnel.Tunnel
import com.secrm.core.tunnel.TunnelConfig
import com.secrm.design.*
import kotlinx.coroutines.launch

@Composable
fun DashboardScreen(app: SecureRemoteApp, onOpenServer: () -> Unit, onOpenShell: () -> Unit) {
    val ts by app.tunnel.state.collectAsState()
    val sshState by app.ssh.state.collectAsState()
    val scope = rememberCoroutineScope()

    var authKey by remember { mutableStateOf("") }
    var host by remember { mutableStateOf(app.config.sshHost ?: "") }
    var user by remember { mutableStateOf(app.config.sshUser ?: "") }
    var pass by remember { mutableStateOf(app.config.sshPassword ?: "") }

    Box(Modifier.fillMaxSize().background(Palette.Void)) {
        Box(Modifier.fillMaxWidth().height(400.dp).background(
            Brush.radialGradient(
                colors = listOf(Palette.IndigoGlow.copy(alpha = 0.15f), Color.Transparent),
                radius = 800f
            )
        ))
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = Space.screenPadding, vertical = Space.xxl),
            verticalArrangement = Arrangement.spacedBy(Space.md)
        ) {
            Text("Secure", style = SecrmType.Display, color = Palette.TextPrimary)
            Text("Remote", style = SecrmType.Display, color = Palette.TextSecondary)
            Spacer(Modifier.height(Space.lg))

            GlassCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(Space.lg)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("TAILSCALE", style = SecrmType.Caption, color = Palette.TextSecondary)
                        Spacer(Modifier.weight(1f))
                        StatusDot(when (ts) {
                            is Tunnel.State.Up -> ConnectionVisual.Up
                            is Tunnel.State.Starting -> ConnectionVisual.Connecting
                            is Tunnel.State.Failed -> ConnectionVisual.Error
                            else -> ConnectionVisual.Idle
                        })
                    }
                    Spacer(Modifier.height(Space.md))
                    val d = when (val s = ts) {
                        is Tunnel.State.Up -> "${s.selfIp} · SOCKS5 :${s.socksPort}"
                        is Tunnel.State.Failed -> s.reason
                        is Tunnel.State.Starting -> "Establishing…"
                        else -> "Not connected"
                    }
                    Text(d, style = SecrmType.MonoSmall, color = Palette.TextSecondary)
                    Spacer(Modifier.height(Space.md))
                    OutlinedTextField(
                        value = authKey, onValueChange = { authKey = it },
                        label = { Text("Auth Key") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(Space.md))
                    if (ts is Tunnel.State.Up) {
                        PillButton("Disconnect", PillStyle.Ghost, Modifier.fillMaxWidth()) {
                            scope.launch { app.tunnel.stop() }
                        }
                    } else {
                        PillButton("Connect", PillStyle.Primary, Modifier.fillMaxWidth()) {
                            scope.launch { app.tunnel.start(TunnelConfig(authKey = authKey)) }
                        }
                    }
                }
            }

            GlassCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(Space.lg)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("SSH", style = SecrmType.Caption, color = Palette.TextSecondary)
                        Spacer(Modifier.weight(1f))
                        StatusDot(when (sshState) {
                            is SshState.Connected -> ConnectionVisual.Up
                            is SshState.Connecting -> ConnectionVisual.Connecting
                            is SshState.Error -> ConnectionVisual.Error
                            else -> ConnectionVisual.Idle
                        })
                    }
                    Spacer(Modifier.height(Space.md))
                    OutlinedTextField(host, { host = it },
                        label = { Text("Host") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(Space.sm))
                    OutlinedTextField(user, { user = it },
                        label = { Text("Username") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(Space.sm))
                    OutlinedTextField(pass, { pass = it },
                        label = { Text("Password") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(Space.md))
                    if (sshState is SshState.Connected) {
                        Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                            PillButton("Shell", PillStyle.Primary, Modifier.weight(1f)) { onOpenShell() }
                            PillButton("Disconnect", PillStyle.Ghost, Modifier.weight(1f)) {
                                scope.launch { app.ssh.disconnect() }
                            }
                        }
                    } else {
                        PillButton("Connect", PillStyle.Primary, Modifier.fillMaxWidth()) {
                            app.config.sshHost = host
                            app.config.sshUser = user
                            app.config.sshPassword = pass
                            scope.launch {
                                app.ssh.connect(SshConfig(
                                    host = host, username = user, password = pass,
                                    socksPort = app.tunnel.socksPortOrNull()
                                ))
                            }
                        }
                    }
                }
            }

            GlassCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(Space.lg)) {
                    Text("MCP SERVER", style = SecrmType.Caption, color = Palette.TextSecondary)
                    Spacer(Modifier.height(Space.sm))
                    Text("Expose SSH connections to Claude and other AI clients.",
                        style = SecrmType.Body, color = Palette.TextPrimary)
                    Spacer(Modifier.height(Space.md))
                    PillButton("Open Server Control", PillStyle.Primary, Modifier.fillMaxWidth()) {
                        onOpenServer()
                    }
                }
            }
            Spacer(Modifier.height(Space.xxl))
        }
    }
}
