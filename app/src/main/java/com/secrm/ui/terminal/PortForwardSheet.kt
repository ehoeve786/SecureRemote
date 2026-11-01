package com.secrm.ui.terminal

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.secrm.core.ssh.SshClientManager
import com.secrm.design.*
import kotlinx.coroutines.launch

/**
 * Local port forwards on the connected host. Each listens on this phone's loopback only, so other devices
 * on the network cannot use it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PortForwardSheet(ssh: SshClientManager, onDismiss: () -> Unit) {
    val forwards by ssh.forwards.collectAsState()
    val scope = rememberCoroutineScope()
    var localPort by remember { mutableStateOf("") }
    var remoteHost by remember { mutableStateOf("127.0.0.1") }
    var remotePort by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        LazyColumn(
            Modifier.fillMaxWidth().padding(horizontal = Space.screenPadding),
            verticalArrangement = Arrangement.spacedBy(Space.sm),
        ) {
            item { Text("Port forwards", style = MaterialTheme.typography.titleLarge) }
            item {
                Text("Reach a service on the server's side at 127.0.0.1:<local port> on this phone.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (forwards.isEmpty()) item {
                Text("No forwards open.", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(forwards.entries.toList(), key = { it.key }) { (port, target) ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("localhost:$port", style = MaterialTheme.mono)
                        Text("→ $target", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = { scope.launch { ssh.removeLocalForward(port) } }) {
                        Text("Close", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            item {
                HorizontalDivider()
                Text("New forward", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                    OutlinedTextField(value = localPort, onValueChange = { localPort = it.filter(Char::isDigit).take(5) },
                        label = { Text("Local port") }, singleLine = true, keyboardOptions = portKeys,
                        modifier = Modifier.weight(1f))
                    OutlinedTextField(value = remotePort, onValueChange = { remotePort = it.filter(Char::isDigit).take(5) },
                        label = { Text("Remote port") }, singleLine = true, keyboardOptions = portKeys,
                        modifier = Modifier.weight(1f))
                }
                OutlinedTextField(value = remoteHost, onValueChange = { remoteHost = it.trim() },
                    label = { Text("Remote host, as the server sees it") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth())
                error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                PillButton("Open forward", PillStyle.Primary,
                    enabled = localPort.isNotEmpty() && remotePort.isNotEmpty() && remoteHost.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth(), onClick = {
                        val lp = localPort.toIntOrNull()
                        val rp = remotePort.toIntOrNull()
                        when {
                            lp == null || lp !in 1024..65535 -> error = "Local port must be 1024–65535 (no root on Android)."
                            rp == null || rp !in 1..65535 -> error = "Remote port must be 1–65535."
                            else -> scope.launch {
                                runCatching { ssh.addLocalForward(lp, remoteHost, rp) }
                                    .onSuccess { error = null; localPort = ""; remotePort = "" }
                                    .onFailure { error = "Could not open: ${it.message ?: "port in use"}" }
                            }
                        }
                    })
            }
            item { Spacer(Modifier.height(Space.xxl)) }
        }
    }
}

private val portKeys = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Number)
