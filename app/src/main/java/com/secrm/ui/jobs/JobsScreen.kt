package com.secrm.ui.jobs

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
import com.secrm.SecureRemoteApp
import com.secrm.data.Job
import com.secrm.design.*
import com.secrm.service.JobScheduling
import java.text.DateFormat
import java.util.Date

private val intervals = listOf(15L to "15 min", 60L to "1 hour", 1440L to "1 day")

/** Ready-made health checks: each exits non-zero when the condition fails. The owner can edit before saving. */
private val healthChecks = listOf(
    Triple("Disk under 90%", "test \$(df --output=pcent / | tail -1 | tr -dc 0-9) -lt 90", 60L),
    Triple("Memory above 50 MB", "test \$(awk '/MemAvailable/{print int(\$2/1024)}' /proc/meminfo) -gt 50", 60L),
    Triple("Load below 4", "test \$(cut -d. -f1 /proc/loadavg) -lt 4", 15L),
    Triple("Service running", "systemctl is-active --quiet UNIT", 15L),
)

/** Scheduled commands on saved hosts. A job that exits non-zero counts as failed, which is how health checks alert. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun JobsScreen(app: SecureRemoteApp, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var jobs by remember { mutableStateOf(app.jobs.list()) }
    var name by remember { mutableStateOf("") }
    var host by remember { mutableStateOf<String?>(null) }
    var command by remember { mutableStateOf("") }
    var every by remember { mutableStateOf(60L) }
    var notifyAlways by remember { mutableStateOf(false) }
    val hosts = app.connections.list().map { it.name }

    fun changed() {
        jobs = app.jobs.list()
        JobScheduling.sync(ctx, jobs.isNotEmpty())
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Scheduled jobs", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, "Back") }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = Space.screenPadding, vertical = Space.md),
            verticalArrangement = Arrangement.spacedBy(Space.sm),
        ) {
            if (jobs.isEmpty()) item {
                Text("No jobs yet. Add one below.", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(jobs, key = { it.id }) { job -> JobRow(job, onDelete = { app.jobs.delete(job.id); changed() }) }

            item {
                HorizontalDivider()
                Text("New job", style = MaterialTheme.typography.labelLarge)
                Text("Health check: exit non-zero when something is wrong, e.g.\\n" +
                    "test \\$(df --output=pcent / | tail -1 | tr -dc 0-9) -lt 90",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item {
                Text("Health check", style = MaterialTheme.typography.bodySmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
                    healthChecks.forEach { (label, cmd, minutes) ->
                        AssistChip(onClick = { name = label; command = cmd; every = minutes }, label = { Text(label) })
                    }
                }
            }
            item {
                OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true,
                    label = { Text("Name") }, modifier = Modifier.fillMaxWidth())
            }
            item {
                Text("Host", style = MaterialTheme.typography.bodySmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
                    hosts.forEach { h ->
                        FilterChip(selected = host == h, onClick = { host = h }, label = { Text(h) })
                    }
                }
            }
            item {
                OutlinedTextField(value = command, onValueChange = { command = it },
                    label = { Text("Command") }, modifier = Modifier.fillMaxWidth())
            }
            item {
                Text("Every", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
                    intervals.forEach { (m, label) ->
                        FilterChip(selected = every == m, onClick = { every = m }, label = { Text(label) })
                    }
                }
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Notify on every run", style = MaterialTheme.typography.bodyLarge)
                        Text("Off: only failures notify.", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(notifyAlways, { notifyAlways = it })
                }
            }
            item {
                PillButton("Add job", PillStyle.Primary,
                    enabled = name.isNotBlank() && host != null && command.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(), onClick = {
                        app.jobs.add(name, host!!, command, every, notifyAlways)
                        name = ""; command = ""
                        changed()
                    })
            }
        }
    }
}

@Composable
private fun JobRow(job: Job, onDelete: () -> Unit) {
    GlassCard(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(Space.md), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(job.name, style = MaterialTheme.typography.titleSmall)
                Text("${job.connection} · every ${job.intervalMinutes} min", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(job.command, style = MaterialTheme.mono, maxLines = 1)
                if (job.lastRun > 0) Text(
                    "${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(job.lastRun))}: ${job.lastResult}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onDelete) { Text("Delete", color = MaterialTheme.colorScheme.error) }
        }
    }
}
