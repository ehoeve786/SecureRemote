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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.secrm.SecureRemoteApp
import com.secrm.core.ssh.tools.SavedKey
import com.secrm.design.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KeyManagementScreen(app: SecureRemoteApp, onBack: () -> Unit) {
    var keys by remember { mutableStateOf(app.keys.list()) }
    var showImport by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<SavedKey?>(null) }
    val ctx = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("SSH Keys", style = SecrmType.Title, color = Palette.TextPrimary) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, "Back", tint = Palette.TextSecondary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Palette.Void)
            )
        },
        containerColor = Palette.Void,
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { editing = null; showImport = true },
                containerColor = Palette.Indigo,
                contentColor = Color.White,
                text = { Text("Import key", fontWeight = FontWeight.Medium) },
                icon = { Text("+", style = SecrmType.Title) }
            )
        }
    ) { padding ->
        if (keys.isEmpty()) {
            Column(
                Modifier.fillMaxSize().padding(padding).padding(Space.xl),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("No keys yet", style = SecrmType.Title, color = Palette.TextPrimary)
                Spacer(Modifier.height(Space.sm))
                Text(
                    "Import an OpenSSH private key by pasting it or picking a file. " +
                    "Keys are stored in the Android Keystore and shared across all connections.",
                    style = SecrmType.Body, color = Palette.TextSecondary,
                    modifier = Modifier.padding(horizontal = Space.xl)
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
                            app.keys.delete(k.id)
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
                if (editing == null) {
                    app.keys.add(name, pem, pub, algo, pass)
                } else {
                    app.keys.updateMetadata(editing!!.copy(
                        name = name, publicKey = pub, algorithm = algo, passphrase = pass
                    ))
                }
                keys = app.keys.list()
                showImport = false
                editing = null
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
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(Space.cardRadius))
            .background(Palette.Surface1)
            .padding(Space.lg),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(key.name, style = SecrmType.Body.copy(fontWeight = FontWeight.Medium),
                color = Palette.TextPrimary)
            Text(key.algorithm, style = SecrmType.MonoSmall, color = Palette.TextSecondary)
            if (key.publicKey.isNotBlank()) {
                Text(
                    key.publicKey.take(60) + "…",
                    style = SecrmType.MonoSmall, color = Palette.TextTertiary,
                    maxLines = 1
                )
            }
            if (key.passphrase.isNotBlank()) {
                Text("passphrase protected", style = SecrmType.Caption, color = Palette.Warning)
            }
        }
        Box {
            Text("⋮", style = SecrmType.Title, color = Palette.TextSecondary,
                modifier = Modifier.padding(horizontal = Space.sm)
                    .clickableNoRipple { showMenu = true })
            DropdownMenu(
                expanded = showMenu,
                onDismissRequest = { showMenu = false },
                containerColor = Palette.Surface2
            ) {
                if (key.publicKey.isNotBlank()) {
                    DropdownMenuItem(
                        text = { Text("Copy public key", color = Palette.TextPrimary) },
                        onClick = { showMenu = false; onCopyPublic() }
                    )
                }
                DropdownMenuItem(
                    text = { Text("Edit", color = Palette.TextPrimary) },
                    onClick = { showMenu = false; onEdit() }
                )
                DropdownMenuItem(
                    text = { Text("Delete", color = Palette.Danger) },
                    onClick = { showMenu = false; onDelete() }
                )
            }
        }
    }
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
        containerColor = Palette.Surface1
    ) {
        Column(
            Modifier.fillMaxWidth()
                .padding(horizontal = Space.lg)
                .padding(bottom = Space.xxl),
            verticalArrangement = Arrangement.spacedBy(Space.md)
        ) {
            Text(
                if (initial == null) "Import SSH key" else "Edit key metadata",
                style = SecrmType.Title, color = Palette.TextPrimary
            )
            OutlinedTextField(name, { name = it },
                label = { Text("Name") }, singleLine = true,
                placeholder = { Text("work-ed25519") },
                modifier = Modifier.fillMaxWidth())

            if (initial == null) {
                Text("Private key", style = SecrmType.Caption, color = Palette.TextSecondary)
                OutlinedTextField(pem, { pem = it },
                    label = { Text("Paste PEM") },
                    placeholder = { Text("-----BEGIN OPENSSH PRIVATE KEY-----") },
                    minLines = 4, maxLines = 8,
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
