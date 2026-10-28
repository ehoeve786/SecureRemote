package com.secrm.ui.terminal

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.secrm.SecureRemoteApp
import com.secrm.data.Snippet
import com.secrm.design.*

/** Lists the saved snippets; tapping one types it into the terminal without pressing Enter. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SnippetSheet(app: SecureRemoteApp, onInsert: (String) -> Unit, onDismiss: () -> Unit) {
    var snippets by remember { mutableStateOf(app.snippets.list()) }
    var name by remember { mutableStateOf("") }
    var command by remember { mutableStateOf("") }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        LazyColumn(
            Modifier.fillMaxWidth().padding(horizontal = Space.screenPadding),
            verticalArrangement = Arrangement.spacedBy(Space.sm),
        ) {
            item { Text("Snippets", style = MaterialTheme.typography.titleLarge) }
            if (snippets.isEmpty()) item {
                Text("No snippets yet. Add one below.", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(snippets, key = { it.id }) { s -> SnippetRow(s, onInsert = onInsert, onDelete = {
                app.snippets.delete(s.id)
                snippets = app.snippets.list()
            }) }
            item {
                HorizontalDivider()
                Text("New snippet", style = MaterialTheme.typography.labelLarge)
                OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true,
                    label = { Text("Name") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = command, onValueChange = { command = it },
                    label = { Text("Command") }, modifier = Modifier.fillMaxWidth())
                PillButton("Save snippet", PillStyle.Primary, enabled = name.isNotBlank() && command.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(), onClick = {
                        app.snippets.add(name, command)
                        snippets = app.snippets.list()
                        name = ""; command = ""
                    })
            }
            item { Spacer(Modifier.height(Space.xxl)) }
        }
    }
}

@Composable
private fun SnippetRow(s: Snippet, onInsert: (String) -> Unit, onDelete: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(vertical = 4.dp).tappable { onInsert(s.command) }) {
            Text(s.name, style = MaterialTheme.typography.bodyLarge)
            Text(s.command, style = MaterialTheme.mono, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
        TextButton(onClick = onDelete) { Text("Delete", color = MaterialTheme.colorScheme.error) }
    }
}
