package com.secrm.ui.agent

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
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
                title = { Text("Bitwarden", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, "Back") }
                },
            )
        },
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).padding(horizontal = Space.screenPadding)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(Space.md)
        ) {
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (configured) {
                val unlocked = count != null
                GlassCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(Space.lg), verticalArrangement = Arrangement.spacedBy(Space.md)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Vault", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    if (unlocked) "$count SSH key${if (count == 1) "" else "s"} available" else "Unlock with your fingerprint to use your keys",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            StatusPill(if (unlocked) ConnectionVisual.Up else ConnectionVisual.Idle, if (unlocked) "Unlocked" else "Locked")
                        }
                        if (!unlocked) PillButton("Unlock", PillStyle.Primary, Modifier.fillMaxWidth(), !busy) {
                            act { vault.ensureUnlocked { activity.authenticate() } }
                        } else PillButton("Lock", PillStyle.Ghost, Modifier.fillMaxWidth()) {
                            vault.lock(); count = null
                        }
                    }
                }
                OutlinedButton(
                    onClick = { vault.signOut(); configured = false; count = null },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("Sign out of Bitwarden") }
            } else {
                GlassCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(Space.lg), verticalArrangement = Arrangement.spacedBy(Space.md)) {
                        Text("Sign in", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Use your personal API key (web vault → Settings → Security → Keys → View API key). " +
                            "The master password is used once; after that, your fingerprint or device PIN unlocks your SSH keys.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
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
                }
            }
            error?.let {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer), modifier = Modifier.fillMaxWidth()) {
                    Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(Space.lg))
                }
            }
            Spacer(Modifier.height(Space.lg))
        }
    }
}

@Composable
private fun Field(value: String, onChange: (String) -> Unit, label: String, hidden: Boolean = false) =
    OutlinedTextField(value, onChange, label = { Text(label) }, singleLine = true,
        // Secrets: a password keyboard keeps the IME from learning or suggesting them.
        keyboardOptions = if (hidden) KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrect = false) else KeyboardOptions.Default,
        visualTransformation = if (hidden) PasswordVisualTransformation() else VisualTransformation.None,
        modifier = Modifier.fillMaxWidth())
