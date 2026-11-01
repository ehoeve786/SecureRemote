package com.secrm.plugin

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.secrm.SecureRemoteApp
import com.secrm.design.SecrmTheme
import com.secrm.design.SectionHeader
import com.secrm.design.Space
import com.secrm.design.mono

/** The action's configuration screen in MacroDroid/Tasker: host, command, when it runs. */
class EditSettingActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Only a macro host that opened this for a result may configure an action. Anything else could
        // collect a signed setting, and so a command the user never meant to approve.
        val caller = callingPackage
        if (!PluginProtocol.isGenuineHost(this, caller)) {
            setResult(Activity.RESULT_CANCELED); finish(); return
        }
        val initial = RunSetting.fromBundle(intent.getBundleExtra(PluginProtocol.EXTRA_BUNDLE))
        val hosts = (application as SecureRemoteApp).connections.list().map { it.name }
        setContent {
            SecrmTheme {
                EditScreen(
                    hosts = hosts,
                    initial = initial,
                    onCancel = { setResult(Activity.RESULT_CANCELED); finish() },
                    onSave = { s -> setResult(Activity.RESULT_OK, resultIntent(s)); finish() },
                )
            }
        }
    }

    private fun resultIntent(s: RunSetting): Intent = Intent().apply {
        putExtra(PluginProtocol.EXTRA_BUNDLE, s.signedBundle((application as SecureRemoteApp).config))
        putExtra(PluginProtocol.EXTRA_BLURB, s.blurb())
        if (s.block) {
            putExtra(PluginProtocol.EXTRA_REQUESTED_TIMEOUT, PluginProtocol.REQUESTED_TIMEOUT_NEVER)
            if (PluginProtocol.hostSupportsRelevantVariables(intent.extras)) PluginProtocol.addRelevantVariables(this, arrayOf(
                "%stdout\nStandard Output\nThe <b>stdout</b> of the command.",
                "%stderr\nStandard Error\nThe <b>stderr</b> of the command.",
                "%result\nExit Code\nThe <b>exit code</b> of the command. 0 often means success.",
                "%errmsg\nError Message\nWhy the command could not run, if it failed.",
            ))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditScreen(hosts: List<String>, initial: RunSetting?, onCancel: () -> Unit, onSave: (RunSetting) -> Unit) {
    var host by remember { mutableStateOf(initial?.connection ?: hosts.firstOrNull().orEmpty()) }
    var command by remember { mutableStateOf(initial?.command.orEmpty()) }
    var block by remember { mutableStateOf(initial?.block ?: true) }
    var live by remember { mutableStateOf(initial?.live ?: false) }
    var picking by remember { mutableStateOf(false) }
    val canSave = host.isNotBlank() && command.isNotBlank()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Run SSH command", style = MaterialTheme.typography.titleLarge)
                        Text(
                            "Runs on a saved host",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
            )
        },
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 3.dp) {
                Row(
                    Modifier.fillMaxWidth().padding(Space.lg),
                    horizontalArrangement = Arrangement.spacedBy(Space.sm),
                ) {
                    OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) { Text("Cancel") }
                    Button(
                        onClick = { onSave(RunSetting(host, command.trim(), block, live)) },
                        enabled = canSave,
                        modifier = Modifier.weight(1f),
                    ) { Text("Save") }
                }
            }
        },
    ) { inner ->
        Column(
            Modifier.fillMaxSize().padding(inner).verticalScroll(rememberScrollState()).padding(horizontal = Space.screenPadding),
            verticalArrangement = Arrangement.spacedBy(Space.sm),
        ) {
            SectionHeader("Host")
            Box {
                ElevatedCard(
                    onClick = { picking = true },
                    enabled = hosts.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                ) {
                    Row(Modifier.padding(Space.lg), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                host.ifBlank { if (hosts.isEmpty()) "No saved hosts" else "Choose a host" },
                                style = MaterialTheme.mono,
                                color = if (host.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                if (hosts.isEmpty()) "Save a host in SecureRemote first" else "Tap to change",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                DropdownMenu(expanded = picking, onDismissRequest = { picking = false }) {
                    hosts.forEach { h -> DropdownMenuItem(text = { Text(h, style = MaterialTheme.mono) }, onClick = { host = h; picking = false }) }
                }
            }

            SectionHeader("Command")
            OutlinedTextField(
                value = command,
                onValueChange = { command = it },
                placeholder = { Text("e.g. df -h /", style = MaterialTheme.mono) },
                textStyle = MaterialTheme.mono,
                minLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )

            SectionHeader("When it runs")
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            ) {
                SettingRow("Wait for it to finish", "The macro waits and receives the output", block) { block = it }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SettingRow("Watch live", "Show the output in a terminal. Tap the notification to open it.", live) { live = it }
            }
        }
    }
}

@Composable
private fun SettingRow(title: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = Space.lg, vertical = Space.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
