package com.secrm.ui.audit

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.secrm.SecureRemoteApp
import com.secrm.core.mcp.AuditEntry
import com.secrm.core.mcp.McpAuditLog
import com.secrm.design.*
import kotlinx.coroutines.launch

/** Recent MCP tool calls, newest first. Arguments are already redacted when they are written. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuditScreen(app: SecureRemoteApp, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var entries by remember { mutableStateOf<List<AuditEntry>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    var errorsOnly by remember { mutableStateOf(false) }

    fun reload() { scope.launch { entries = app.audit.tail(LIMIT) } }
    LaunchedEffect(Unit) { reload() }

    val shown = entries.asReversed().filter { e ->
        (!errorsOnly || e.outcome != "ok") &&
            (query.isBlank() || listOf(e.tool, e.outcome, e.argsRedacted).any { it.contains(query, ignoreCase = true) })
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Audit log", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, "Back")
                    }
                },
                actions = {
                    TextButton(onClick = { ctx.startActivity(Intent.createChooser(shareIntent(shown), "Share audit log")) }) {
                        Text("Share")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = Space.screenPadding, vertical = Space.md),
            verticalArrangement = Arrangement.spacedBy(Space.sm),
        ) {
            item {
                OutlinedTextField(
                    value = query, onValueChange = { query = it }, singleLine = true,
                    label = { Text("Search tool, outcome or arguments") }, modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(selected = errorsOnly, onClick = { errorsOnly = !errorsOnly }, label = { Text("Errors only") })
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = ::reload) { Text("Refresh") }
                }
            }
            if (shown.isEmpty()) item {
                Text(
                    if (entries.isEmpty()) "No tool calls yet." else "Nothing matches.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            items(shown) { e ->
                GlassCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(Space.md)) {
                        Text(e.tool, style = MaterialTheme.typography.titleSmall)
                        Text(
                            "${e.ts.take(19).replace('T', ' ')} · ${e.outcome} · ${e.durationMs} ms",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (e.outcome == "ok") MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                        )
                        if (e.argsRedacted.isNotBlank() && e.argsRedacted != "{}") {
                            Text(e.argsRedacted, style = MaterialTheme.mono, color = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
            }
        }
    }
}

private const val LIMIT = 500

private fun shareIntent(entries: List<AuditEntry>): Intent = Intent(Intent.ACTION_SEND)
    .setType("text/plain")
    .putExtra(Intent.EXTRA_SUBJECT, "SecureRemote MCP audit log")
    .putExtra(
        Intent.EXTRA_TEXT,
        entries.joinToString("\n") { "${it.ts} ${it.tool} ${it.outcome} ${it.durationMs}ms ${it.argsRedacted}" },
    )
