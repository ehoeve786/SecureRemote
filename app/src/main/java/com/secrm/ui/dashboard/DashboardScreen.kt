package com.secrm.ui.dashboard

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.fragment.app.FragmentActivity
import com.secrm.data.authenticate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.secrm.SecureRemoteApp
import com.secrm.core.ssh.SshAuth
import com.secrm.core.ssh.SshConfig
import com.secrm.core.ssh.SshState
import com.secrm.core.ssh.tools.AuthMethod
import com.secrm.core.ssh.tools.SavedConnection
import com.secrm.core.ssh.tools.connectSaved
import com.secrm.design.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    app: SecureRemoteApp,
    onOpenShell: () -> Unit,
    onOpenFiles: () -> Unit,
) {
    val sshState by app.ssh.state.collectAsState()
    val scope = rememberCoroutineScope()
    val activity = LocalContext.current as FragmentActivity

    var connections by remember { mutableStateOf(app.connections.list()) }
    var editing by remember { mutableStateOf<SavedConnection?>(null) }
    var showForm by remember { mutableStateOf(false) }
    var activeName by remember { mutableStateOf(if (app.ssh.state.value is SshState.Connected) app.ssh.connectedName else null) }
    var connecting by remember { mutableStateOf<String?>(null) }
    var connectError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(sshState) {
        activeName = if (sshState is SshState.Connected) app.ssh.connectedName else null
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Hosts", style = MaterialTheme.typography.titleLarge)
                        Text(
                            "${connections.size} saved · ${if (sshState is SshState.Connected) "1 connected" else "none connected"}",
                            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { editing = null; showForm = true },
                text = { Text("Add host") },
                icon = { Icon(Icons.Rounded.Add, null) }
            )
        }
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (connections.isEmpty()) {
                com.secrm.ui.common.StateMessage(
                    SecrmIcons.Dns, "No hosts yet",
                    "Save an SSH host to connect, open a terminal and browse its files. To reach it away from home, set up Tailscale in Settings first.",
                    action = "Add host" to { editing = null; showForm = true },
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = Space.screenPadding,
                        end = Space.screenPadding,
                        top = Space.md,
                        bottom = 120.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(Space.sm)
                ) {
                    // Connection error banner
                    connectError?.let { err ->
                        item {
                            Card(
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Column(Modifier.padding(Space.lg), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                                    Text("Couldn't connect", style = MaterialTheme.typography.titleSmall)
                                    Text(err.take(240), style = MaterialTheme.mono)
                                    TextButton({ connectError = null }, Modifier.align(Alignment.End)) { Text("Dismiss") }
                                }
                            }
                        }
                    }

                    items(connections, key = { it.name }) { conn ->
                        ConnectionCard(
                            conn = conn,
                            isConnecting = connecting == conn.name,
                            state = if (activeName == conn.name && sshState is SshState.Connected)
                                ConnectionVisual.Up
                            else if (activeName == conn.name && sshState is SshState.Connecting)
                                ConnectionVisual.Connecting
                            else if (activeName == conn.name && sshState is SshState.Error)
                                ConnectionVisual.Error
                            else ConnectionVisual.Idle,
                            isActive = activeName == conn.name && sshState is SshState.Connected,
                            serverVersion = (sshState as? SshState.Connected)?.serverVersion.takeIf { activeName == conn.name },
                            onConnect = {
                                scope.launch {
                                    connectError = null
                                    connecting = conn.name
                                    activeName = conn.name
                                    val auth: SshAuth? = when (conn.authMethod) {
                                        AuthMethod.PASSWORD -> {
                                            if (conn.password.isBlank()) null
                                            else SshAuth.Password(conn.password)
                                        }
                                        AuthMethod.SAVED_KEY -> {
                                            val kid = conn.keyId
                                            val pem = kid?.let { app.keys.privateKey(it) }
                                            if (pem == null) null
                                            else SshAuth.Key(
                                                pem = pem,
                                                passphrase = app.keys.find(kid)?.passphrase
                                                    ?.takeIf { it.isNotBlank() }
                                            )
                                        }
                                        AuthMethod.AGENT -> {
                                            runCatching { app.vault.ensureUnlocked { activity.authenticate() } }
                                                .onFailure { connectError = it.message }
                                            conn.keyId?.let(app.vault::privateKey)
                                                ?.let { SshAuth.Key(pem = it, passphrase = null) }
                                        }
                                    }
                                    if (auth == null) {
                                        connectError = connectError ?: "Missing credentials for ${conn.name}"
                                        activeName = null
                                        connecting = null
                                        return@launch
                                    }
                                    val ok = app.ssh.connectSaved(conn, auth, app.connections)
                                    connections = app.connections.list() // host or key may have been updated
                                    connecting = null
                                    if (ok) {
                                        onOpenShell()
                                    } else {
                                        val st = app.ssh.state.value
                                        connectError = when (st) {
                                            is SshState.Error -> st.message
                                            else -> "SSH connect failed"
                                        }
                                        activeName = null
                                    }
                                }
                            },
                            onShell = onOpenShell,
                            onFiles = onOpenFiles,
                            onDisconnect = { scope.launch { app.ssh.disconnect() }; activeName = null },
                            onEdit = { editing = conn; showForm = true },
                            onDelete = {
                                app.connections.delete(conn.name)
                                connections = app.connections.list()
                                if (activeName == conn.name) {
                                    scope.launch { app.ssh.disconnect() }
                                    activeName = null
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    if (showForm) {
        ConnectionFormSheet(
            app = app,
            initial = editing,
            onDismiss = { showForm = false; editing = null },
            onSave = { conn ->
                app.connections.upsert(conn)
                connections = app.connections.list()
                showForm = false
                editing = null
            }
        )
    }
}

@Composable
private fun ConnectionCard(
    conn: SavedConnection,
    isConnecting: Boolean,
    state: ConnectionVisual,
    isActive: Boolean,
    serverVersion: String?,
    onConnect: () -> Unit,
    onShell: () -> Unit,
    onFiles: () -> Unit,
    onDisconnect: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val s = MaterialTheme.colorScheme
    val visual = if (isConnecting) ConnectionVisual.Connecting else state

    ElevatedCard(
        onClick = { if (isActive) onShell() else if (!isConnecting) onConnect() },
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(containerColor = if (isActive) s.primaryContainer else s.surfaceContainerLow),
    ) {
        Column(Modifier.padding(Space.lg), verticalArrangement = Arrangement.spacedBy(Space.md)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(48.dp).background(if (isActive) s.primary else s.secondaryContainer, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(SecrmIcons.Dns, null, tint = if (isActive) s.onPrimary else s.onSecondaryContainer)
                }
                Spacer(Modifier.width(Space.md))
                Column(Modifier.weight(1f)) {
                    Text(conn.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${conn.user}@${conn.host}:${conn.port}", style = MaterialTheme.mono,
                        color = if (isActive) s.onPrimaryContainer else s.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                StatusPill(visual, when (visual) {
                    ConnectionVisual.Up -> "Connected"
                    ConnectionVisual.Connecting -> "Connecting"
                    ConnectionVisual.Error -> "Failed"
                    else -> "Offline"
                })
                Box {
                    IconButton({ showMenu = true }) { Icon(Icons.Rounded.MoreVert, "More") }
                    DropdownMenu(showMenu, { showMenu = false }) {
                        DropdownMenuItem(text = { Text("Edit") }, onClick = { showMenu = false; onEdit() })
                        DropdownMenuItem(text = { Text("Delete", color = s.error) }, onClick = { showMenu = false; confirmDelete = true })
                    }
                }
            }
            if (serverVersion != null) Text(serverVersion.take(60), style = MaterialTheme.mono, color = s.onPrimaryContainer.copy(alpha = 0.8f))
            Row(horizontalArrangement = Arrangement.spacedBy(Space.sm), verticalAlignment = Alignment.CenterVertically) {
                val tagBg = if (isActive) s.surface.copy(alpha = 0.5f) else s.surfaceContainerHighest
                Tag(when (conn.via) { "tailscale" -> "Tailscale"; "wireguard" -> "WireGuard"; else -> "Direct" }, container = tagBg)
                Tag(when (conn.authMethod) { AuthMethod.PASSWORD -> "Password"; AuthMethod.SAVED_KEY -> "Key"; AuthMethod.AGENT -> "Bitwarden" }, container = tagBg)
                Tag(when {
                    conn.allowMcpExec -> "MCP exec"
                    conn.allowMcpRead -> "MCP read-only"
                    else -> "Hidden from MCP"
                }, container = tagBg)
            }
            if (isActive) {
                Row(horizontalArrangement = Arrangement.spacedBy(Space.sm), verticalAlignment = Alignment.CenterVertically) {
                    Button(onShell) { Icon(SecrmIcons.Terminal, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Terminal") }
                    FilledTonalButton(onFiles) { Icon(SecrmIcons.Folder, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Files") }
                    Spacer(Modifier.weight(1f))
                    TextButton(onDisconnect) { Text("Disconnect", color = s.error) }
                }
            } else {
                FilledTonalButton(onConnect, enabled = !isConnecting, modifier = Modifier.fillMaxWidth()) {
                    if (isConnecting) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    else Text("Connect")
                }
            }
        }
    }
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Delete ${conn.name}?") },
        text = { Text("The saved host is removed from this phone. Its keys stay in the Keys tab.") },
        confirmButton = { TextButton({ confirmDelete = false; onDelete() }) { Text("Delete", color = s.error) } },
        dismissButton = { TextButton({ confirmDelete = false }) { Text("Cancel") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConnectionFormSheet(
    app: com.secrm.SecureRemoteApp,
    initial: SavedConnection?,
    onDismiss: () -> Unit,
    onSave: (SavedConnection) -> Unit
) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var host by remember { mutableStateOf(initial?.host ?: "") }
    var port by remember { mutableStateOf((initial?.port ?: 22).toString()) }
    var user by remember { mutableStateOf(initial?.user ?: "") }
    var authMethod by remember { mutableStateOf(initial?.authMethod ?: AuthMethod.PASSWORD) }
    var password by remember { mutableStateOf(initial?.password ?: "") }
    var keyId by remember { mutableStateOf(initial?.keyId) }
    var via by remember { mutableStateOf(initial?.via) }
    var canExec by remember { mutableStateOf(initial?.allowMcpExec ?: true) }
    var canRead by remember { mutableStateOf(initial?.allowMcpRead ?: true) }

    val savedKeys = remember { app.keys.list() }
    var bwKeys by remember { mutableStateOf(app.vault.keys) }
    val scope = rememberCoroutineScope()
    val activity = LocalContext.current as FragmentActivity
    fun pick(m: AuthMethod) { if (m != authMethod) keyId = null; authMethod = m }
    val valid = name.isNotBlank() && host.isNotBlank() && user.isNotBlank() &&
        when (authMethod) {
            AuthMethod.PASSWORD -> password.isNotBlank()
            AuthMethod.SAVED_KEY, AuthMethod.AGENT -> keyId != null
        }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        
    ) {
        Column(
            Modifier.fillMaxWidth()
                .padding(horizontal = Space.lg)
                .padding(bottom = Space.xxl)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(Space.md)
        ) {
            Text(
                if (initial == null) "New connection" else "Edit connection",
                style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface
            )
            OutlinedTextField(name, { name = it },
                label = { Text("Name") }, singleLine = true,
                modifier = Modifier.fillMaxWidth())
            OutlinedTextField(host, { host = it },
                label = { Text("Host") }, singleLine = true,
                modifier = Modifier.fillMaxWidth())
            OutlinedTextField(port, { port = it.filter(Char::isDigit) },
                label = { Text("Port") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth())
            OutlinedTextField(user, { user = it },
                label = { Text("Username") }, singleLine = true,
                modifier = Modifier.fillMaxWidth())

            // ── Auth selector ────────────────────────────────
            Text("Authentication", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            val authOptions = listOf(AuthMethod.PASSWORD to "Password", AuthMethod.SAVED_KEY to "Key", AuthMethod.AGENT to "Bitwarden")
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                authOptions.forEachIndexed { i, (m, label) ->
                    SegmentedButton(authMethod == m, { pick(m) }, SegmentedButtonDefaults.itemShape(i, authOptions.size)) { Text(label) }
                }
            }

            when (authMethod) {
                AuthMethod.PASSWORD -> {
                    OutlinedTextField(password, { password = it },
                        label = { Text("Password") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth())
                }

                AuthMethod.SAVED_KEY, AuthMethod.AGENT -> {
                    val options = if (authMethod == AuthMethod.SAVED_KEY) savedKeys.map { it.id to it.name }
                        else bwKeys?.map { it.id to it.name }.orEmpty()
                    if (options.isEmpty()) {
                        Text(
                            when {
                                authMethod == AuthMethod.SAVED_KEY -> "No saved keys. Open Settings → SSH Keys to import one."
                                !app.vault.isConfigured -> "Sign in under Settings → Bitwarden first."
                                bwKeys == null -> "Bitwarden is locked."
                                else -> "No SSH keys in your Bitwarden vault."
                            },
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.status.warning
                        )
                        if (authMethod == AuthMethod.AGENT && app.vault.isConfigured && bwKeys == null)
                            PillButton("Unlock Bitwarden", PillStyle.Primary, Modifier.fillMaxWidth()) {
                                scope.launch {
                                    runCatching { app.vault.ensureUnlocked { activity.authenticate() } }
                                    bwKeys = app.vault.keys
                                }
                            }
                    } else {
                        var expanded by remember { mutableStateOf(false) }
                        Box {
                            OutlinedButton(
                                onClick = { expanded = true },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(options.firstOrNull { it.first == keyId }?.second ?: "Select key…",
                                    color = MaterialTheme.colorScheme.onSurface)
                            }
                            DropdownMenu(
                                expanded = expanded,
                                onDismissRequest = { expanded = false },
                                containerColor = MaterialTheme.colorScheme.surfaceContainer
                            ) {
                                options.forEach { (id, label) ->
                                    DropdownMenuItem(
                                        text = { Text(label, color = MaterialTheme.colorScheme.onSurface) },
                                        onClick = { keyId = id; expanded = false }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // ── Route through (as in Tidegate) ──────────────
            Text("Route through", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            val routes = listOf<String?>(null, "tailscale", "wireguard")
            val routeNames = listOf("Direct", "Tailscale", "WireGuard")
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                routes.forEachIndexed { i, v ->
                    SegmentedButton(via == v, { via = v }, SegmentedButtonDefaults.itemShape(i, routes.size)) { Text(routeNames[i]) }
                }
            }

            Text("MCP exposure", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            ListItem(
                headlineContent = { Text("Allow ssh.exec") },
                supportingContent = { Text("Claude can run commands on this host") },
                trailingContent = { Switch(canExec, { canExec = it }) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
            ListItem(
                headlineContent = { Text("Allow ssh.read_file") },
                supportingContent = { Text("Claude can read files on this host") },
                trailingContent = { Switch(canRead, { canRead = it }) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )

            Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                PillButton("Cancel", PillStyle.Ghost, Modifier.weight(1f)) { onDismiss() }
                PillButton("Save", PillStyle.Primary, Modifier.weight(1f)) {
                    if (!valid) return@PillButton
                    onSave(SavedConnection(
                        name = name.trim(),
                        host = host.trim(),
                        port = port.toIntOrNull() ?: 22,
                        user = user.trim(),
                        authMethod = authMethod,
                        password = if (authMethod == AuthMethod.PASSWORD) password else "",
                        keyId = if (authMethod != AuthMethod.PASSWORD) keyId else null,
                        via = via,
                        hostKey = initial?.hostKey, // same device; also how it's found if its IP changes
                        allowMcpExec = canExec,
                        allowMcpRead = canRead
                    ))
                }
            }
        }
    }
}
