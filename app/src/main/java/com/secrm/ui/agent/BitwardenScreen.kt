package com.secrm.ui.agent

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.fragment.app.FragmentActivity
import com.secrm.SecureRemoteApp
import com.secrm.data.authenticate
import com.secrm.design.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BitwardenScreen(app: SecureRemoteApp, onBack: () -> Unit) {
    val vault = app.vault
    val activity = LocalContext.current as FragmentActivity
    val scope = rememberCoroutineScope()
    var configured by remember { mutableStateOf(vault.isConfigured) }
    var count by remember { mutableStateOf(vault.keys?.size) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var server by remember { mutableStateOf("https://vault.bitwarden.com") }
    var clientId by remember { mutableStateOf("") }
    var secret by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    fun act(block: suspend () -> Unit) = scope.launch {
        busy = true
        error = runCatching { block() }.exceptionOrNull()?.let { it.message ?: it.javaClass.simpleName }
        configured = vault.isConfigured; count = vault.keys?.size; busy = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Bitwarden", style = SecrmType.Title, color = Palette.TextPrimary) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, "Back", tint = Palette.TextSecondary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Palette.Void)
            )
        },
        containerColor = Palette.Void
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).padding(Space.screenPadding)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(Space.md)
        ) {
            if (configured) {
                Text(count?.let { "Unlocked · $it SSH key${if (it == 1) "" else "s"}" } ?: "Locked",
                    style = SecrmType.Body, color = Palette.TextPrimary)
                if (count == null) PillButton("Unlock", PillStyle.Primary, Modifier.fillMaxWidth(), !busy) {
                    act { vault.ensureUnlocked { activity.authenticate() } }
                } else PillButton("Lock", PillStyle.Ghost, Modifier.fillMaxWidth()) {
                    vault.lock(); count = null
                }
                PillButton("Sign out", PillStyle.Danger, Modifier.fillMaxWidth()) {
                    vault.signOut(); configured = false; count = null
                }
            } else {
                Text(
                    "Sign in with your personal API key (web vault → Settings → Security → " +
                    "Keys → View API key). The master password is used once; after that, " +
                    "fingerprint or device PIN unlocks your SSH keys.",
                    style = SecrmType.Caption, color = Palette.TextSecondary
                )
                Field(server, { server = it }, "Server URL")
                Field(clientId, { clientId = it }, "client_id")
                Field(secret, { secret = it }, "client_secret", hidden = true)
                Field(password, { password = it }, "Master password", hidden = true)
                PillButton("Sign in", PillStyle.Primary, Modifier.fillMaxWidth(),
                    !busy && listOf(server, clientId, secret, password).all(String::isNotBlank)) {
                    act { vault.login(server, clientId, secret, password) { activity.authenticate() }; password = "" }
                }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            error?.let { Text(it, style = SecrmType.Caption, color = Palette.Danger) }
        }
    }
}

@Composable
private fun Field(value: String, onChange: (String) -> Unit, label: String, hidden: Boolean = false) =
    OutlinedTextField(value, onChange, label = { Text(label) }, singleLine = true,
        visualTransformation = if (hidden) PasswordVisualTransformation() else VisualTransformation.None,
        modifier = Modifier.fillMaxWidth())
