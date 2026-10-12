package com.secrm.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.secrm.SecureRemoteApp
import com.secrm.design.*
import kotlinx.coroutines.launch

@Composable
fun QuickShellScreen(app: SecureRemoteApp, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var cmd by remember { mutableStateOf("") }
    var out by remember { mutableStateOf(listOf<String>()) }

    Column(Modifier.fillMaxSize().background(Palette.Void).padding(Space.lg)) {
        Text("Quick Shell", style = SecrmType.Title, color = Palette.TextPrimary)
        Spacer(Modifier.height(Space.md))
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            items(out) { line ->
                Text(line.ifEmpty { " " },
                    style = SecrmType.MonoSmall, color = Palette.TextPrimary)
            }
        }
        Spacer(Modifier.height(Space.md))
        OutlinedTextField(cmd, { cmd = it },
            label = { Text("Command") }, singleLine = true,
            modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(Space.sm))
        Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
            PillButton("Run", PillStyle.Primary, Modifier.weight(1f)) {
                val c = cmd.trim(); if (c.isBlank()) return@PillButton
                out = out + "$ $c"
                cmd = ""
                scope.launch {
                    out = try { out + app.ssh.execOnce(c).lines() }
                    catch (t: Throwable) { out + "[error] ${t.message}" }
                }
            }
            PillButton("Back", PillStyle.Ghost, Modifier.weight(1f)) { onBack() }
        }
    }
}
