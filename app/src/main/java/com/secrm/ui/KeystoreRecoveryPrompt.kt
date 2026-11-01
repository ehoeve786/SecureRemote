package com.secrm.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.secrm.SecureRemoteApp

/**
 * Shown when the Keystore key that protects saved data is unusable. The owner chooses whether to
 * reset: nothing is wiped without their confirmation, and saves are dropped until they do.
 */
@Composable
fun KeystoreRecoveryPrompt(app: SecureRemoteApp) {
    var open by remember { mutableStateOf(app.config.keystoreLost) }
    if (!open) return
    AlertDialog(
        onDismissRequest = { open = false },
        title = { Text("Saved data can't be opened") },
        text = {
            Text(
                "The key that protects your saved SSH hosts, keys and MCP settings is no longer usable. " +
                    "Reset to continue; you'll need to re-enter them. Until then, nothing new is saved."
            )
        },
        confirmButton = {
            TextButton({
                // Reset ends every grant too: claude.ai connections are not kept.
                app.oauth.revokeAll()
                app.config.resetStorage()
                // The tunnel's cached "has config" flag and key must go too; the saved config it refers to is gone.
                app.wireguard.forget()
                open = false
            }) { Text("Reset") }
        },
        dismissButton = { TextButton({ open = false }) { Text("Not now") } },
    )
}
