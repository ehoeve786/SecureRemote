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
import androidx.compose.material.icons.rounded.Settings
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
import com.secrm.design.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    app: SecureRemoteApp,
    onOpenSettings: () -> Unit,
    onOpenShell: () -> Unit
) {
    val sshState by app.ssh.state.collectAsState()
    val scope = rememberCoroutineScope()
    val activity = LocalContext.current as FragmentActivity

    var connections by remember { mutableStateOf(app.connections.list()) }
    var editing by remember { mutableStateOf<SavedConnection?>(null) }
    var showForm by remember { mutableStateOf(false) }
    var activeName by remember { mutableStateOf<String?>(null) }
    var connecting by remember { mutableStateOf<String?>(null) }
    var connectError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(sshState) {
        if (sshState !is SshState.Connected) activeName = null
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Connections", style = SecrmType.Title, color = Palette.TextPrimary)
                        Text(
                            "${connections.size} saved · ${if (sshState is SshState.Connected) "1 active" else "idle"}",
                            style = SecrmType.Caption, color = Palette.TextSecondary
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Rounded.Settings, "Settings", tint = Palette.TextSecondary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Palette.Void)
            )
        },
        containerColor = Palette.Void,
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { editing = null; showForm = true },
                containerColor = Palette.Indigo,
                contentColor = Color.White,
                text = { Text("New connection", fontWeight = FontWeight.Medium) },
                icon = { Text("+", style = SecrmType.Title) }
            )
        }
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .background(Palette.Void)
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(320.dp)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(Palette.IndigoGlow.copy(alpha = 0.12f), Color.Transparent),
                            radius = 700f
                        )
                    )
            )

            if (connections.isEmpty()) {
                EmptyState(onAdd = { editing = null; showForm = true })
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
                            Row(
                                Modifier.fillMaxWidth()
                                    .clip(RoundedCornerShape(Space.cardRadius))
                                    .background(Palette.Danger.copy(alpha = 0.15f))
                                    .padding(Space.lg),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text("Connection failed",
                                        style = SecrmType.Body.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Medium),
                                        color = Palette.Danger)
                                    Text(err.take(200),
                                        style = SecrmType.MonoSmall,
                                        color = Palette.TextSecondary)
                                }
                                androidx.compose.material3.TextButton(onClick = { connectError = null }) {
                                    Text("Dismiss", color = Palette.TextSecondary)
                                }
                            }
                        }
                        item { Spacer(Modifier.height(Space.sm)) }
                    }

                    // Active session banner
                    if (sshState is SshState.Connected && activeName != null) {
                        item { ActiveBanner(
                            name = activeName ?: "",
                            serverVersion = (sshState as SshState.Connected).serverVersion,
                            onShell = onOpenShell,
                            onDisconnect = {
                                scope.launch { app.ssh.disconnect() }
                                activeName = null
                            }
                        ) }
                        item { Spacer(Modifier.height(Space.sm)) }
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
                                    val ok = app.ssh.connect(SshConfig(
                                        host = conn.host,
                                        port = conn.port,
                                        username = conn.user,
                                        auth = auth,
                                        socksPort = app.tunnel.socksPortOrNull()
                                    ))
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
private fun EmptyState(onAdd: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(Space.xl),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("No connections yet", style = SecrmType.Title, color = Palette.TextPrimary)
        Spacer(Modifier.height(Space.sm))
        Text(
            "Add an SSH connection to get started. If you're using Tailscale, configure it first in Settings.",
            style = SecrmType.Body, color = Palette.TextSecondary,
            modifier = Modifier.padding(horizontal = Space.xl)
        )
    }
}

@Composable
private fun ActiveBanner(
    name: String,
    serverVersion: String,
    onShell: () -> Unit,
    onDisconnect: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(Space.cardRadius))
            .background(Palette.Indigo.copy(alpha = 0.12f))
            .padding(Space.lg),
        verticalAlignment = Alignment.CenterVertically
    ) {
        StatusDot(ConnectionVisual.Up)
        Spacer(Modifier.width(Space.md))
        Column(Modifier.weight(1f)) {
            Text("Connected to $name", style = SecrmType.Body.copy(fontWeight = FontWeight.Medium),
                color = Palette.TextPrimary)
            Text(serverVersion.take(40), style = SecrmType.MonoSmall, color = Palette.TextSecondary)
        }
        TextButton(onClick = onShell) {
            Text("Shell", color = Palette.Indigo, fontWeight = FontWeight.Medium)
        }
        TextButton(onClick = onDisconnect) {
            Text("Disconnect", color = Palette.Danger)
        }
    }
}

@Composable
private fun ConnectionCard(
    conn: SavedConnection,
    isConnecting: Boolean = false,
    state: ConnectionVisual,
    isActive: Boolean,
    onConnect: () -> Unit,
    onShell: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }

    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(Space.cardRadius))
            .background(Palette.Surface1)
            .clickableNoRipple { if (isActive) onShell() else onConnect() }
            .padding(Space.lg),
        verticalAlignment = Alignment.CenterVertically
    ) {
        StatusDot(if (isConnecting) ConnectionVisual.Connecting else state)
        Spacer(Modifier.width(Space.md))
        Column(Modifier.weight(1f)) {
            Text(conn.name, style = SecrmType.Body.copy(fontWeight = FontWeight.Medium),
                color = Palette.TextPrimary)
            Text("${conn.user}@${conn.host}:${conn.port}",
                style = SecrmType.MonoSmall, color = Palette.TextSecondary)
            if (!conn.allowMcpExec && !conn.allowMcpRead) {
                Text("hidden from MCP", style = SecrmType.Caption, color = Palette.TextTertiary)
            } else if (conn.allowMcpExec) {
                Text("MCP: exec", style = SecrmType.Caption, color = Palette.Indigo)
            } else if (conn.allowMcpRead) {
                Text("MCP: read-only", style = SecrmType.Caption, color = Palette.Indigo)
            }
        }
        Box {
            Text(
                "⋮",
                style = SecrmType.Title,
                color = Palette.TextSecondary,
                modifier = Modifier
                    .padding(horizontal = Space.sm)
                    .clickableNoRipple { showMenu = true }
            )
            DropdownMenu(
                expanded = showMenu,
                onDismissRequest = { showMenu = false },
                containerColor = Palette.Surface2
            ) {
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
        containerColor = Palette.Surface1
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
                style = SecrmType.Title, color = Palette.TextPrimary
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
            Text("Authentication", style = SecrmType.Caption, color = Palette.TextSecondary)
            Row(
                Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(Space.pillRadius))
                    .background(Palette.Surface2)
                    .padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                AuthTab("Password", authMethod == AuthMethod.PASSWORD, Modifier.weight(1f)) {
                    pick(AuthMethod.PASSWORD)
                }
                AuthTab("Key", authMethod == AuthMethod.SAVED_KEY, Modifier.weight(1f)) {
                    pick(AuthMethod.SAVED_KEY)
                }
                AuthTab("Bitwarden", authMethod == AuthMethod.AGENT, Modifier.weight(1f)) {
                    pick(AuthMethod.AGENT)
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
                            style = SecrmType.Caption, color = Palette.Warning
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
                                    color = Palette.TextPrimary)
                            }
                            DropdownMenu(
                                expanded = expanded,
                                onDismissRequest = { expanded = false },
                                containerColor = Palette.Surface2
                            ) {
                                options.forEach { (id, label) ->
                                    DropdownMenuItem(
                                        text = { Text(label, color = Palette.TextPrimary) },
                                        onClick = { keyId = id; expanded = false }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Text("MCP exposure", style = SecrmType.Caption, color = Palette.TextSecondary)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(canExec, { canExec = it })
                Spacer(Modifier.width(Space.sm))
                Text("Allow ssh.exec", style = SecrmType.Body, color = Palette.TextPrimary)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(canRead, { canRead = it })
                Spacer(Modifier.width(Space.sm))
                Text("Allow ssh.read_file", style = SecrmType.Body, color = Palette.TextPrimary)
            }

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
                        allowMcpExec = canExec,
                        allowMcpRead = canRead
                    ))
                }
            }
        }
    }
}

@Composable
private fun AuthTab(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .clip(RoundedCornerShape(Space.pillRadius))
            .background(if (selected) Palette.Indigo else Color.Transparent)
            .clickableNoRipple(onClick)
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, style = SecrmType.Label,
            color = if (selected) Color.White else Palette.TextSecondary)
    }
}
