package com.secrm.ui.dashboard

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import android.widget.Toast
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.secrm.SecureRemoteApp
import com.secrm.core.ssh.SshAuth
import com.secrm.core.ssh.SshConfig
import com.secrm.core.ssh.hostKeyFingerprint
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
    val hosts by app.sessions.connected.collectAsState()
    val scope = rememberCoroutineScope()
    val activity = LocalContext.current as FragmentActivity

    var connections by remember { mutableStateOf(app.connections.list()) }
    var editing by remember { mutableStateOf<SavedConnection?>(null) }
    var showForm by remember { mutableStateOf(false) }
    var connecting by remember { mutableStateOf<String?>(null) }
    var connectError by remember { mutableStateOf<String?>(null) }
    // The last failed attempt, so the owner can ask for a network search for that host.
    var failed by remember { mutableStateOf<Pair<SavedConnection, SshAuth>?>(null) }
    // A host never connected before: its key, waiting for the owner to check the fingerprint.
    var pendingTrust by remember { mutableStateOf<PendingTrust?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Hosts", style = MaterialTheme.typography.titleLarge)
                        Text(
                            "${connections.size} saved · ${if (hosts.isEmpty()) "none connected" else "${hosts.size} connected"}",
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
                                    failed?.let { (c, a) ->
                                        TextButton({
                                            scope.launch {
                                                connectError = null; failed = null; connecting = c.name
                                                val e = app.sessions.connect(c, a, rediscover = true)
                                                connections = app.connections.list()
                                                connecting = null
                                                if (e == null) onOpenShell() else { connectError = e; failed = c to a }
                                            }
                                        }, Modifier.align(Alignment.End)) { Text("Search network for ${c.name}") }
                                    }
                                    TextButton({ connectError = null; failed = null }, Modifier.align(Alignment.End)) { Text("Dismiss") }
                                }
                            }
                        }
                    }

                    items(connections, key = { it.name }) { conn ->
                        val live = hosts.firstOrNull { it.name == conn.name }
                        ConnectionCard(
                            conn = conn,
                            isConnecting = connecting == conn.name,
                            state = if (live != null) ConnectionVisual.Up else ConnectionVisual.Idle,
                            isActive = live != null,
                            serverVersion = (live?.ssh?.state?.value as? SshState.Connected)?.serverVersion,
                            onConnect = {
                                scope.launch {
                                    connectError = null; failed = null
                                    connecting = conn.name
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
                                    }
                                    if (auth == null) {
                                        connectError = connectError ?: "Missing credentials for ${conn.name}"
                                        connecting = null
                                        return@launch
                                    }
                                    if (conn.hostKey == null) {
                                        val key = app.sessions.scanKey(conn)
                                        connecting = null
                                        if (key == null) connectError = "Could not reach ${conn.host}"
                                        else pendingTrust = PendingTrust(conn, auth, key)
                                        return@launch
                                    }
                                    val err = app.sessions.connect(conn, auth)
                                    connections = app.connections.list() // host or key may have been updated
                                    connecting = null
                                    if (err == null) {
                                        failed = null
                                        onOpenShell()
                                    } else {
                                        connectError = err
                                        failed = conn to auth
                                    }
                                }
                            },
                            onShell = { app.sessions.select(conn.name); onOpenShell() },
                            onFiles = { app.sessions.select(conn.name); onOpenFiles() },
                            onDisconnect = { app.sessions.close(conn.name) },
                            onEdit = { editing = conn; showForm = true },
                            onDelete = {
                                app.connections.delete(conn.name)
                                connections = app.connections.list()
                                app.sessions.close(conn.name)
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
                // A corrupt store throws from upsert; report it instead of crashing the app.
                val old = editing
                val saved = runCatching {
                    // Saved by name: a taken name would silently replace that host and its trusted key.
                    require(old?.name == conn.name || connections.none { it.name == conn.name }) { "a host named ${conn.name} already exists" }
                    app.connections.upsert(conn)
                    if (old != null && old.name != conn.name) {
                        app.connections.delete(old.name)
                        app.sessions.close(old.name)
                    }
                }
                    .onFailure { Toast.makeText(activity, "Couldn't save host: ${it.message}", Toast.LENGTH_LONG).show() }
                    .isSuccess
                if (saved) {
                    connections = app.connections.list()
                    showForm = false
                    editing = null
                }
            }
        )
    }

    pendingTrust?.let { p ->
        AlertDialog(
            onDismissRequest = { pendingTrust = null },
            title = { Text("Trust ${p.conn.host}?") },
            text = { Text("First connection to this host. Check this fingerprint against the server before trusting it:\n\n${hostKeyFingerprint(p.key)}") },
            confirmButton = {
                TextButton({
                    pendingTrust = null
                    scope.launch {
                        connecting = p.conn.name
                        val err = app.sessions.connect(p.conn, p.auth, approvedKey = p.key)
                        connections = app.connections.list()
                        connecting = null
                        if (err == null) onOpenShell() else connectError = err
                    }
                }) { Text("Trust and connect") }
            },
            dismissButton = { TextButton({ pendingTrust = null }) { Text("Cancel") } },
        )
    }
}

/** A host waiting for its first-connect key to be approved. */
private data class PendingTrust(val conn: SavedConnection, val auth: SshAuth, val key: String)

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
                Tag(when (conn.authMethod) { AuthMethod.PASSWORD -> "Password"; AuthMethod.SAVED_KEY -> "Key" }, container = tagBg)
                Tag(when {
                    conn.allowMcpExec -> "MCP exec"
                    conn.allowMcpRead -> "MCP read-only"
                    else -> "Hidden from MCP"
                }, container = tagBg)
            }
            if (isActive) {
                Row(horizontalArrangement = Arrangement.spacedBy(Space.sm), verticalAlignment = Alignment.CenterVertically) {
                    val pill = PaddingValues(horizontal = 12.dp)
                    Button(onShell, contentPadding = pill) { Icon(SecrmIcons.Terminal, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("Terminal", maxLines = 1, softWrap = false) }
                    FilledTonalButton(onFiles, contentPadding = pill) { Icon(SecrmIcons.Folder, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("Files", maxLines = 1, softWrap = false) }
                    Spacer(Modifier.weight(1f))
                    TextButton(onDisconnect, contentPadding = pill) { Text("Disconnect", color = s.error, maxLines = 1, softWrap = false) }
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
    var fallback by remember { mutableStateOf(initial?.fallbackVia) }
    var canExec by remember { mutableStateOf(initial?.allowMcpExec ?: false) }
    var canRead by remember { mutableStateOf(initial?.allowMcpRead ?: false) }
    var forgetKey by remember { mutableStateOf(false) }

    val savedKeys = remember { app.keys.list() }
    val scope = rememberCoroutineScope()
    val activity = LocalContext.current as FragmentActivity
    fun pick(m: AuthMethod) { if (m != authMethod) keyId = null; authMethod = m }
    val portNum = if (port.isEmpty()) 22 else port.toIntOrNull()?.takeIf { it in 1..65535 }
    val valid = portNum != null && name.isNotBlank() && host.isNotBlank() && user.isNotBlank() &&
        when (authMethod) {
            AuthMethod.PASSWORD -> password.isNotBlank()
            AuthMethod.SAVED_KEY -> keyId != null
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
                isError = portNum == null,
                supportingText = { if (portNum == null) Text("Port must be 1–65535") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth())
            OutlinedTextField(user, { user = it },
                label = { Text("Username") }, singleLine = true,
                modifier = Modifier.fillMaxWidth())

            // ── Auth selector ────────────────────────────────
            Text("Authentication", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            val authOptions = listOf(AuthMethod.PASSWORD to "Password", AuthMethod.SAVED_KEY to "Key")
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                authOptions.forEachIndexed { i, (m, label) ->
                    SegmentedButton(authMethod == m, { pick(m) }, SegmentedButtonDefaults.itemShape(i, authOptions.size)) { Text(label) }
                }
            }

            when (authMethod) {
                AuthMethod.PASSWORD -> {
                    OutlinedTextField(password, { password = it },
                        label = { Text("Password") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrect = false),
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth())
                }

                AuthMethod.SAVED_KEY -> {
                    val options = savedKeys.map { it.id to it.name }
                    if (options.isEmpty()) {
                        Text(
                            "No saved keys. Open Settings → SSH Keys to import one.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.status.warning
                        )
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
            if (via == null) {
                Text("If direct fails, use", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val fallbacks = listOf<String?>(null, "tailscale", "wireguard")
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    fallbacks.forEachIndexed { i, v ->
                        SegmentedButton(fallback == v, { fallback = v }, SegmentedButtonDefaults.itemShape(i, fallbacks.size)) { Text(routeNames[i]) }
                    }
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

            if (initial?.hostKey != null) {
                PillButton(
                    if (forgetKey) "Host key will be asked again" else "Forget host key",
                    PillStyle.Ghost, Modifier.fillMaxWidth(),
                ) { forgetKey = true }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                PillButton("Cancel", PillStyle.Ghost, Modifier.weight(1f)) { onDismiss() }
                PillButton("Save", PillStyle.Primary, Modifier.weight(1f)) {
                    if (!valid) return@PillButton
                    // Learned fields come from the latest saved record: the sheet may have been open while a connect learned them.
                    val latest = initial?.let { app.connections.find(it.name) } ?: initial
                    onSave(SavedConnection(
                        name = name.trim(),
                        host = host.trim(),
                        port = portNum ?: 22,
                        user = user.trim(),
                        authMethod = authMethod,
                        password = if (authMethod == AuthMethod.PASSWORD) password else "",
                        keyId = if (authMethod != AuthMethod.PASSWORD) keyId else null,
                        via = via,
                        fallbackVia = if (via == null) fallback else null,
                        // Learned by connecting, not edited here, so an edit keeps them.
                        routeByNetwork = latest?.routeByNetwork.orEmpty(),
                        // Kept only for the same address: a new address is a new host and needs its key approved again.
                        hostKey = if (forgetKey || latest == null || latest.host != host.trim() || latest.port != (portNum ?: 22) || latest.via != via) null else latest.hostKey,
                        allowMcpExec = canExec,
                        allowMcpRead = canRead
                    ))
                }
            }
        }
    }
}
