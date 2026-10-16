package com.secrm.ui.keys

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import android.widget.Toast
import com.secrm.SecureRemoteApp
import com.secrm.core.ssh.tools.SavedKey
import com.secrm.design.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KeyManagementScreen(app: SecureRemoteApp, onBack: (() -> Unit)? = null) {
    var keys by remember { mutableStateOf(app.keys.list()) }
    var showImport by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<SavedKey?>(null) }
    val ctx = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Column { Text("Keys", style = MaterialTheme.typography.titleLarge); Text("${keys.size} stored in the Android Keystore", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) } },
                navigationIcon = {
                    if (onBack != null) IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, "Back", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { editing = null; showImport = true },
                text = { Text("Import key") },
                icon = { Icon(Icons.Rounded.Add, null) }
            )
        }
    ) { padding ->
        if (keys.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding)) {
                com.secrm.ui.common.StateMessage(
                    SecrmIcons.Key, "No keys yet",
                    "Import an OpenSSH private key by pasting it or picking a file. Keys are stored in the Android Keystore and shared by all hosts.",
                    action = "Import key" to { editing = null; showImport = true },
                )
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(
                    start = Space.screenPadding, end = Space.screenPadding,
                    top = Space.md, bottom = 120.dp
                ),
                verticalArrangement = Arrangement.spacedBy(Space.sm)
            ) {
                items(keys, key = { it.id }) { k ->
                    KeyRow(
                        key = k,
                        onCopyPublic = {
                            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText("public key", k.publicKey))
                        },
                        onEdit = { editing = k; showImport = true },
                        onDelete = {
                            runCatching { app.keys.delete(k.id) }
                                .onFailure { Toast.makeText(ctx, "Couldn't delete key: ${it.message}", Toast.LENGTH_LONG).show() }
                            keys = app.keys.list()
                        }
                    )
                }
            }
        }
    }

    if (showImport) {
        KeyImportSheet(
            initial = editing,
            onDismiss = { showImport = false; editing = null },
            onSave = { name, pem, pub, algo, pass ->
                // A corrupt store throws from add/update; report it instead of crashing the app.
                val saved = runCatching {
                    if (editing == null) {
                        app.keys.add(name, pem, pub, algo, pass)
                    } else {
                        app.keys.updateMetadata(editing!!.copy(
                            name = name, publicKey = pub, algorithm = algo, passphrase = pass
                        ))
                    }
                }.onFailure { Toast.makeText(ctx, "Couldn't save key: ${it.message}", Toast.LENGTH_LONG).show() }.isSuccess
                if (saved) {
                    keys = app.keys.list()
                    showImport = false
                    editing = null
                }
            }
        )
    }
}

@Composable
private fun KeyRow(
    key: SavedKey,
    onCopyPublic: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val s = MaterialTheme.colorScheme
    ElevatedCard(Modifier.fillMaxWidth(), colors = CardDefaults.elevatedCardColors(containerColor = s.surfaceContainerLow)) {
        Row(Modifier.padding(Space.lg), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).background(s.tertiaryContainer, CircleShape), contentAlignment = Alignment.Center) {
                Icon(SecrmIcons.Key, null, tint = s.onTertiaryContainer)
            }
            Spacer(Modifier.width(Space.md))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                Text(key.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                    Tag(key.algorithm)
                    if (key.passphrase.isNotBlank()) Tag("Passphrase", container = MaterialTheme.status.warning.copy(alpha = 0.16f), content = MaterialTheme.status.warning)
                }
                if (key.publicKey.isNotBlank()) Text(key.publicKey.take(48) + "…", style = MaterialTheme.mono, color = s.onSurfaceVariant, maxLines = 1)
            }
            Box {
                IconButton({ showMenu = true }) { Icon(Icons.Rounded.MoreVert, "More") }
                DropdownMenu(showMenu, { showMenu = false }) {
                    if (key.publicKey.isNotBlank()) DropdownMenuItem(text = { Text("Copy public key") }, onClick = { showMenu = false; onCopyPublic() })
                    DropdownMenuItem(text = { Text("Edit") }, onClick = { showMenu = false; onEdit() })
                    DropdownMenuItem(text = { Text("Delete", color = s.error) }, onClick = { showMenu = false; confirmDelete = true })
                }
            }
        }
    }
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Delete ${key.name}?") },
        text = { Text("Hosts using this key will need another way to sign in.") },
        confirmButton = { TextButton({ confirmDelete = false; onDelete() }) { Text("Delete", color = s.error) } },
        dismissButton = { TextButton({ confirmDelete = false }) { Text("Cancel") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun KeyImportSheet(
    initial: SavedKey?,
    onDismiss: () -> Unit,
    onSave: (name: String, pem: String, pub: String, algo: String, pass: String) -> Unit
) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var pem by remember { mutableStateOf("") }
    var pub by remember { mutableStateOf(initial?.publicKey ?: "") }
    var algo by remember { mutableStateOf(initial?.algorithm ?: "ssh-ed25519") }
    var pass by remember { mutableStateOf(initial?.passphrase ?: "") }

    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    // SAF file picker — reads the selected file into `pem` on the IO dispatcher.
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching {
                    ctx.contentResolver.openInputStream(uri)?.use {
                        it.readBytes().toString(Charsets.UTF_8)
                    }.orEmpty()
                }.getOrDefault("")
            }
            if (text.isNotBlank()) {
                pem = text
                // Auto-detect algorithm from the PEM header
                algo = when {
                    text.contains("BEGIN OPENSSH PRIVATE KEY") -> "openssh"
                    text.contains("BEGIN RSA PRIVATE KEY") -> "ssh-rsa"
                    text.contains("BEGIN EC PRIVATE KEY") -> "ecdsa-sha2-nistp256"
                    else -> algo
                }
            }
        }
    }

    val valid = name.isNotBlank() && (initial != null || pem.isNotBlank())
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        
    ) {
        Column(
            Modifier.fillMaxWidth()
                .padding(horizontal = Space.lg)
                .padding(bottom = Space.xxl),
            verticalArrangement = Arrangement.spacedBy(Space.md)
        ) {
            Text(
                if (initial == null) "Import SSH key" else "Edit key metadata",
                style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface
            )
            OutlinedTextField(name, { name = it },
                label = { Text("Name") }, singleLine = true,
                placeholder = { Text("work-ed25519") },
                modifier = Modifier.fillMaxWidth())

            if (initial == null) {
                Text("Private key", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(pem, { pem = it },
                    label = { Text("Paste PEM") },
                    placeholder = { Text("-----BEGIN OPENSSH PRIVATE KEY-----") },
                    minLines = 4, maxLines = 8,
                    // The key is secret material: keep it out of the IME and out of plain view.
                    keyboardOptions = KeyboardOptions(autoCorrect = false),
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth())
                PillButton("Pick file…", PillStyle.Ghost, Modifier.fillMaxWidth()) {
                    picker.launch(arrayOf("*/*"))
                }
            }

            OutlinedTextField(pub, { pub = it },
                label = { Text("Public key (optional)") },
                placeholder = { Text("ssh-ed25519 AAAA… user@host") },
                minLines = 2, maxLines = 4,
                modifier = Modifier.fillMaxWidth())

            OutlinedTextField(algo, { algo = it },
                label = { Text("Algorithm") }, singleLine = true,
                modifier = Modifier.fillMaxWidth())

            OutlinedTextField(pass, { pass = it },
                label = { Text("Passphrase (optional)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrect = false),
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth())

            Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                PillButton("Cancel", PillStyle.Ghost, Modifier.weight(1f)) { onDismiss() }
                PillButton("Save", PillStyle.Primary, Modifier.weight(1f)) {
                    if (!valid) return@PillButton
                    onSave(name.trim(), pem, pub.trim(), algo.trim(), pass)
                }
            }
        }
    }
}
