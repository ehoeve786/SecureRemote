package com.secrm.ui.sftp

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.secrm.SecureRemoteApp
import com.secrm.core.ssh.SftpClient
import com.secrm.core.ssh.SftpEntry
import com.secrm.core.ssh.SshState
import com.secrm.design.SecrmIcons
import com.secrm.design.Space
import com.secrm.ui.common.StateMessage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Files tab: the connected host's file system over SFTP. */
@Composable
fun SftpScreen(app: SecureRemoteApp, onOpenHosts: () -> Unit, onOpenTerminal: (String) -> Unit) {
    val sshState by app.ssh.state.collectAsState()
    if (sshState !is SshState.Connected) {
        StateMessage(
            SecrmIcons.Folder, "No active connection",
            "Connect to a host to browse and transfer its files.",
            action = "Choose a host" to onOpenHosts,
        )
        return
    }
    val generation = app.ssh.generation
    LaunchedEffect(generation) { app.files.sync() }
    FilesContent(app.files, app.ssh.target.orEmpty(), onOpenTerminal)
}

private sealed interface Dialog {
    data object NewFolder : Dialog
    data class Rename(val e: SftpEntry) : Dialog
    data class Delete(val e: SftpEntry) : Dialog
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun FilesContent(controller: SftpController, target: String, onOpenTerminal: (String) -> Unit) {
    val ui by controller.state.collectAsState()
    val ctx = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var sheet by remember { mutableStateOf<SftpEntry?>(null) }
    var dialog by remember { mutableStateOf<Dialog?>(null) }
    var downloading by remember { mutableStateOf<SftpEntry?>(null) }

    LaunchedEffect(Unit) {
        controller.messages.collect { m ->
            val r = snackbar.showSnackbar(m.text, actionLabel = if (m.retry != null) "Retry" else null, duration = SnackbarDuration.Long)
            if (r == SnackbarResult.ActionPerformed) m.retry?.invoke()
        }
    }
    val path = ui.path
    BackHandler(enabled = path != null && path != "/") { controller.up() }

    val pickDownload = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val e = downloading
        if (uri != null && e != null) controller.download(e, uri)
    }
    val pickUpload = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) controller.upload(uris)
    }
    val busy = ui.transfer != null || ui.working != null

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Files", style = MaterialTheme.typography.titleLarge)
                        Text(target, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                },
                actions = {
                    IconButton({ pickUpload.launch(arrayOf("*/*")) }, enabled = path != null && !busy) { Icon(SecrmIcons.Upload, "Upload files") }
                    IconButton({ dialog = Dialog.NewFolder }, enabled = path != null && !busy) { Icon(SecrmIcons.NewFolder, "New folder") }
                    IconButton({ path?.let(onOpenTerminal) }, enabled = path != null) { Icon(SecrmIcons.Terminal, "Open this folder in the terminal") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Breadcrumbs(path, onOpen = controller::open)
            Box(Modifier.height(4.dp).fillMaxWidth()) {
                if (ui.loading && ui.entries.isNotEmpty() || ui.working != null) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            Box(Modifier.weight(1f)) {
                PullToRefreshBox(isRefreshing = ui.loading, onRefresh = controller::refresh, modifier = Modifier.fillMaxSize()) {
                    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = if (ui.transfer != null) 120.dp else Space.lg)) {
                        when {
                            ui.path == null && ui.loadError == null -> item {
                                Box(Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                            }
                            ui.loadError != null && ui.entries.isEmpty() -> item {
                                Box(Modifier.fillParentMaxSize()) {
                                    StateMessage(
                                        Icons.Rounded.Refresh, "Couldn't open this folder", ui.loadError.orEmpty(), isError = true,
                                        action = "Retry" to controller::refresh,
                                        secondary = if (path != null && path != "/") "Go up" to controller::up else null,
                                    )
                                }
                            }
                            else -> {
                                if (path != null && path != "/") item(key = "..") {
                                    ListItem(
                                        headlineContent = { Text("Parent folder") },
                                        leadingContent = { EntryIcon(SecrmIcons.ArrowUp, MaterialTheme.colorScheme.surfaceContainerHighest, MaterialTheme.colorScheme.onSurfaceVariant) },
                                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                                        modifier = Modifier.combinedClickable(onClick = controller::up),
                                    )
                                }
                                items(ui.entries, key = { it.path }) { e ->
                                    EntryRow(e, onClick = { if (e.isDir) controller.open(e.path) else sheet = e }, onMore = { sheet = e })
                                }
                                if (ui.entries.isEmpty() && !ui.loading) item {
                                    Box(Modifier.fillParentMaxHeight(0.7f)) {
                                        StateMessage(
                                            SecrmIcons.Folder, "This folder is empty", "Upload files or create a folder here.",
                                            action = "Upload files" to { pickUpload.launch(arrayOf("*/*")) },
                                            secondary = "New folder" to { dialog = Dialog.NewFolder },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                ui.transfer?.let { t ->
                    TransferCard(t, onCancel = controller::cancelTransfer, modifier = Modifier.align(Alignment.BottomCenter))
                }
            }
        }
    }

    sheet?.let { e ->
        ModalBottomSheet(onDismissRequest = { sheet = null }) {
            ListItem(
                headlineContent = { Text(e.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                supportingContent = { Text(listOfNotNull(if (e.isDir) "Folder" else size(e.size), e.perms, date(e.mtime)).joinToString(" · ")) },
                leadingContent = { FileIcon(e) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
            HorizontalDivider()
            if (e.isDir) SheetAction(SecrmIcons.Terminal, "Open in terminal") { sheet = null; onOpenTerminal(e.path) }
            else SheetAction(SecrmIcons.Download, "Download", enabled = !busy) { sheet = null; downloading = e; pickDownload.launch(e.name) }
            SheetAction(Icons.Rounded.Edit, "Rename", enabled = !busy) { sheet = null; dialog = Dialog.Rename(e) }
            SheetAction(SecrmIcons.Copy, "Copy path") {
                sheet = null
                (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("path", e.path))
            }
            SheetAction(Icons.Rounded.Delete, "Delete", danger = true, enabled = !busy) { sheet = null; dialog = Dialog.Delete(e) }
            Spacer(Modifier.height(Space.lg))
        }
    }

    when (val d = dialog) {
        Dialog.NewFolder -> NameDialog("New folder", "", "Create", ui.entries, onDismiss = { dialog = null }) { dialog = null; controller.mkdir(it) }
        is Dialog.Rename -> NameDialog("Rename", d.e.name, "Rename", ui.entries.filter { it != d.e }, onDismiss = { dialog = null }) { dialog = null; controller.rename(d.e, it) }
        is Dialog.Delete -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("Delete ${d.e.name}?") },
            text = { Text(if (d.e.isDir && !d.e.isLink) "The folder and everything in it will be permanently deleted." else "This can't be undone.") },
            confirmButton = { TextButton({ dialog = null; controller.delete(d.e) }) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton({ dialog = null }) { Text("Cancel") } },
        )
        null -> {}
    }
}

@Composable
private fun Breadcrumbs(path: String?, onOpen: (String) -> Unit) {
    val scroll = rememberScrollState()
    LaunchedEffect(path) { scroll.animateScrollTo(scroll.maxValue) }
    val segments = path.orEmpty().split('/').filter { it.isNotEmpty() }
    Row(Modifier.fillMaxWidth().horizontalScroll(scroll).padding(horizontal = Space.md), verticalAlignment = Alignment.CenterVertically) {
        Crumb(onClick = { onOpen("/") }, current = path == "/") { Icon(Icons.Rounded.Home, "Root", Modifier.size(18.dp)) }
        segments.forEachIndexed { i, s ->
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Crumb(onClick = { onOpen("/" + segments.take(i + 1).joinToString("/")) }, current = i == segments.lastIndex) { Text(s) }
        }
    }
}

@Composable
private fun Crumb(onClick: () -> Unit, current: Boolean, content: @Composable () -> Unit) {
    val colors = if (current) AssistChipDefaults.assistChipColors(containerColor = MaterialTheme.colorScheme.secondaryContainer, labelColor = MaterialTheme.colorScheme.onSecondaryContainer)
    else AssistChipDefaults.assistChipColors()
    AssistChip(onClick, label = content, colors = colors, border = null, shape = MaterialTheme.shapes.medium)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EntryRow(e: SftpEntry, onClick: () -> Unit, onMore: () -> Unit) {
    ListItem(
        headlineContent = { Text(e.name + if (e.isLink) "  →" else "", maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text("${e.perms} · ${date(e.mtime)}", style = MaterialTheme.typography.labelSmall) },
        leadingContent = { FileIcon(e) },
        trailingContent = { if (!e.isDir) Text(size(e.size), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.combinedClickable(onClick = onClick, onLongClick = onMore),
    )
}

@Composable
private fun FileIcon(e: SftpEntry) {
    val s = MaterialTheme.colorScheme
    when {
        e.isDir -> EntryIcon(SecrmIcons.Folder, s.primaryContainer, s.onPrimaryContainer)
        e.isLink -> EntryIcon(SecrmIcons.Link, s.tertiaryContainer, s.onTertiaryContainer)
        else -> EntryIcon(SecrmIcons.File, s.surfaceContainerHighest, s.onSurfaceVariant)
    }
}

@Composable
private fun EntryIcon(icon: ImageVector, container: Color, content: Color) {
    Box(Modifier.size(40.dp).background(container, MaterialTheme.shapes.medium), contentAlignment = Alignment.Center) {
        Icon(icon, null, Modifier.size(22.dp), tint = content)
    }
}

@Composable
private fun SheetAction(icon: ImageVector, label: String, danger: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    ListItem(
        headlineContent = { Text(label, color = color) },
        leadingContent = { Icon(icon, null, tint = color) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.combinedClickable(enabled = enabled, onClick = onClick),
    )
}

@Composable
private fun TransferCard(t: Transfer, onCancel: () -> Unit, modifier: Modifier = Modifier) {
    ElevatedCard(modifier.fillMaxWidth().padding(Space.md)) {
        Column(Modifier.padding(horizontal = Space.lg, vertical = Space.md)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(if (t.upload) SecrmIcons.Upload else SecrmIcons.Download, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(Space.md))
                Column(Modifier.weight(1f)) {
                    val of = if (t.count > 1) " (${t.index} of ${t.count})" else ""
                    Text((if (t.upload) "Uploading" else "Downloading") + of, style = MaterialTheme.typography.labelLarge)
                    Text(t.name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                TextButton(onCancel) { Text("Cancel") }
            }
            Spacer(Modifier.height(Space.sm))
            if (t.total > 0) LinearProgressIndicator(progress = { (t.done.toFloat() / t.total).coerceIn(0f, 1f) }, Modifier.fillMaxWidth())
            else LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(if (t.total > 0) "${size(t.done)} of ${size(t.total)}" else size(t.done), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = Space.xs))
        }
    }
}

@Composable
private fun NameDialog(title: String, initial: String, confirm: String, siblings: List<SftpEntry>, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    val name = text.trim()
    val problem = when {
        name.isEmpty() -> null
        '/' in name -> "A name can't contain /"
        siblings.any { it.name == name } -> "Something with this name already exists"
        else -> null
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(text, { text = it }, singleLine = true, isError = problem != null, supportingText = { problem?.let { Text(it) } }, label = { Text("Name") }) },
        confirmButton = { TextButton({ onConfirm(name) }, enabled = name.isNotEmpty() && name != initial && problem == null) { Text(confirm) } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}

private fun size(b: Long): String = when {
    b >= 1L shl 30 -> "%.1f GB".format(b / (1L shl 30).toDouble())
    b >= 1L shl 20 -> "%.1f MB".format(b / (1L shl 20).toDouble())
    b >= 1L shl 10 -> "%.1f KB".format(b / 1024.0)
    else -> "$b B"
}

private fun date(sec: Long): String = SimpleDateFormat("MMM d, yyyy HH:mm", Locale.US).format(Date(sec * 1000))
