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
import com.secrm.SecureRemoteApp
import com.secrm.design.SecrmTheme
import com.secrm.design.Space

/** The action's configuration screen in MacroDroid/Tasker: host, command, wait. */
class EditSettingActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
        putExtra(PluginProtocol.EXTRA_BUNDLE, s.toBundle())
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

@Composable
private fun EditScreen(hosts: List<String>, initial: RunSetting?, onCancel: () -> Unit, onSave: (RunSetting) -> Unit) {
    var host by remember { mutableStateOf(initial?.connection ?: hosts.firstOrNull().orEmpty()) }
    var command by remember { mutableStateOf(initial?.command.orEmpty()) }
    var block by remember { mutableStateOf(initial?.block ?: true) }
    var picking by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Space.md),
        verticalArrangement = Arrangement.spacedBy(Space.md),
    ) {
        Box {
            OutlinedButton(onClick = { picking = true }, enabled = hosts.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
                Text("Host: " + host.ifBlank { if (hosts.isEmpty()) "no saved hosts" else "choose" })
            }
            DropdownMenu(expanded = picking, onDismissRequest = { picking = false }) {
                hosts.forEach { h -> DropdownMenuItem(text = { Text(h) }, onClick = { host = h; picking = false }) }
            }
        }
        OutlinedTextField(
            value = command, onValueChange = { command = it },
            label = { Text("Command") }, minLines = 2, modifier = Modifier.fillMaxWidth(),
        )
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text("Wait for command to finish")
                Text("Off: start it and continue at once", style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked = block, onCheckedChange = { block = it })
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onCancel) { Text("Cancel") }
            Button(
                onClick = { onSave(RunSetting(host, command.trim(), block)) },
                enabled = host.isNotBlank() && command.isNotBlank(),
            ) { Text("Save") }
        }
    }
}
