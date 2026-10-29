package com.secrm.ui.sftp

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.secrm.SecureRemoteApp
import com.secrm.core.ssh.SftpEntry
import com.secrm.design.SecrmIcons
import com.secrm.design.Space
import com.secrm.ui.HostPager
import com.secrm.ui.common.StateMessage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Files tab: the connected host's file system over SFTP. */
@Composable
fun SftpScreen(app: SecureRemoteApp, onOpenHosts: () -> Unit, onOpenTerminal: (String) -> Unit) {
    val hosts by app.sessions.connected.collectAsState()
    val clip by app.sessions.clip.collectAsState()
    if (hosts.isEmpty()) {
        StateMessage(
            SecrmIcons.Folder, "No active connection",
            "Connect to a host to browse and transfer its files.",
            action = "Choose a host" to onOpenHosts,
        )
        return
    }
    // One file browser per connected host; swiping between them switches host.
    HostPager(app, hosts) { host ->
        val generation = host.ssh.generation
        LaunchedEffect(host.name, generation) { host.files.sync() }
        FilesContent(host.files, host.target, clip, onOpenTerminal)
    }
}

private sealed interface Dialog {
    data object NewFolder : Dialog
    data class Rename(val e: SftpEntry) : Dialog
    data class Delete(val items: List<SftpEntry>) : Dialog
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun FilesContent(controller: SftpController, target: String, clip: Clip?, onOpenTerminal: (String) -> Unit) {
    val ui by controller.state.collectAsState()
    val ctx = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var sheet by remember { mutableStateOf<SftpEntry?>(null) }
    var dialog by remember { mutableStateOf<Dialog?>(null) }
    var downloading by remember { mutableStateOf<List<SftpEntry>>(emptyList()) }
    var uploadMenu by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        controller.messages.collect { m ->
            val r = snackbar.showSnackbar(m.text, actionLabel = if (m.retry != null) "Retry" else null, duration = SnackbarDuration.Long)
            if (r == SnackbarResult.ActionPerformed) m.retry?.invoke()
        }
    }
    val path = ui.path
    val selecting = ui.selected.isNotEmpty()
    val picked = ui.entries.filter { it.path in ui.selected }
    val busy = ui.transfer != null || ui.working != null
    BackHandler(enabled = path != null && path != "/") { controller.up() }
    BackHandler(enabled = selecting) { controller.clearSelection() }

    val pickSave = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val items = downloading
        if (uri != null && items.size == 1) controller.download(items[0], uri)
    }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null && downloading.isNotEmpty()) controller.downloadInto(downloading, uri)
    }
    val pickUpload = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) controller.upload(uris)
    }
    val pickUploadFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) controller.uploadFolder(uri)
    }

    // One file saves straight to a chosen name; several files or any folder go into a chosen folder.
    fun download(items: List<SftpEntry>) {
        downloading = items
        controller.clearSelection()
        val single = items.singleOrNull()
        if (single != null && !single.isDir) pickSave.launch(single.name) else pickFolder.launch(null)
    }

    Scaffold(
        topBar = {
            if (selecting) {
                TopAppBar(
                    navigationIcon = { IconButton({ controller.clearSelection() }) { Icon(SecrmIcons.Close, "Clear selection") } },
                    title = { Text("${picked.size} selected", style = MaterialTheme.typography.titleLarge) },
                    actions = { TextButton({ controller.selectAll() }) { Text("All") } },
                )
            } else {
                TopAppBar(
                    title = {
                        Column {
                            Text("Files", style = MaterialTheme.typography.titleLarge)
                            Text(target, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    },
                    actions = {
                        Box {
                            IconButton({ uploadMenu = true }, enabled = path != null && !busy) { Icon(SecrmIcons.Upload, "Upload") }
                            DropdownMenu(expanded = uploadMenu, onDismissRequest = { uploadMenu = false }) {
                                DropdownMenuItem(text = { Text("Files") }, onClick = { uploadMenu = false; pickUpload.launch(arrayOf("*/*")) })
                                DropdownMenuItem(text = { Text("Folder") }, onClick = { uploadMenu = false; pickUploadFolder.launch(null) })
                            }
                        }
                        IconButton({ dialog = Dialog.NewFolder }, enabled = path != null && !busy) { Icon(SecrmIcons.NewFolder, "New folder") }
                        IconButton({ path?.let(onOpenTerminal) }, enabled = path != null) { Icon(SecrmIcons.Terminal, "Open this folder in the terminal") }
                    },
                )
            }
        },
        bottomBar = {
            if (selecting) {
                SelectionBar(
                    enabled = !busy,
                    onCopy = { controller.copy(picked, cut = false) },
                    onCut = { controller.copy(picked, cut = true) },
                    onDownload = { download(picked) },
                    onDelete = { dialog = Dialog.Delete(picked) },
                )
            } else {
                clip?.let { c ->
                    PasteBar(c, enabled = path != null && !busy, onCancel = controller::clearClip, onPaste = controller::paste)
                }
            }
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
                                    EntryRow(
                                        e,
                                        selected = e.path in ui.selected,
                                        onClick = {
                                            when {
                                                selecting -> controller.toggle(e)
                                                e.isDir -> controller.open(e.path)
                                                else -> sheet = e
                                            }
                                        },
                                        onLongClick = { controller.toggle(e) },
                                    )
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
                    if (ui.queued > 0) Text(
                        "${ui.queued} more queued",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 96.dp),
                    )
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
            SheetAction(SecrmIcons.Download, "Download", enabled = !busy) { sheet = null; download(listOf(e)) }
            SheetAction(SecrmIcons.Copy, "Copy", enabled = !busy) { sheet = null; controller.copy(listOf(e), cut = false) }
            SheetAction(SecrmIcons.Cut, "Cut", enabled = !busy) { sheet = null; controller.copy(listOf(e), cut = true) }
            SheetAction(Icons.Rounded.Edit, "Rename", enabled = !busy) { sheet = null; dialog = Dialog.Rename(e) }
            SheetAction(SecrmIcons.Check, "Select") { sheet = null; controller.toggle(e) }
            SheetAction(SecrmIcons.Link, "Copy path") {
                sheet = null
                (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("path", e.path))
            }
            SheetAction(Icons.Rounded.Delete, "Delete", danger = true, enabled = !busy) { sheet = null; dialog = Dialog.Delete(listOf(e)) }
            Spacer(Modifier.height(Space.lg))
        }
    }

    when (val d = dialog) {
        Dialog.NewFolder -> NameDialog("New folder", "", "Create", ui.entries, onDismiss = { dialog = null }) { dialog = null; controller.mkdir(it) }
        is Dialog.Rename -> NameDialog("Rename", d.e.name, "Rename", ui.entries.filter { it != d.e }, onDismiss = { dialog = null }) { dialog = null; controller.rename(d.e, it) }
        is Dialog.Delete -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text(if (d.items.size == 1) "Delete ${d.items[0].name}?" else "Delete ${d.items.size} items?") },
            text = { Text(if (d.items.any { it.isDir && !it.isLink }) "Folders and everything in them will be permanently deleted." else "This can't be undone.") },
            confirmButton = { TextButton({ dialog = null; controller.delete(d.items); controller.clearSelection() }) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton({ dialog = null }) { Text("Cancel") } },
        )
        null -> {}
    }
}

@Composable
private fun SelectionBar(enabled: Boolean, onCopy: () -> Unit, onCut: () -> Unit, onDownload: () -> Unit, onDelete: () -> Unit) {
    Surface(tonalElevation = 3.dp, color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = Space.sm, vertical = Space.sm),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            BarAction(SecrmIcons.Copy, "Copy", enabled, onClick = onCopy)
            BarAction(SecrmIcons.Cut, "Cut", enabled, onClick = onCut)
            BarAction(SecrmIcons.Download, "Download", enabled, onClick = onDownload)
            BarAction(Icons.Rounded.Delete, "Delete", enabled, danger = true, onClick = onDelete)
        }
    }
}

@Composable
private fun BarAction(icon: ImageVector, label: String, enabled: Boolean, danger: Boolean = false, onClick: () -> Unit) {
    val color = when {
        !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        danger -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurface
    }
    Column(
        Modifier.clip(MaterialTheme.shapes.medium).clickable(enabled = enabled, onClick = onClick).padding(horizontal = Space.md, vertical = Space.sm),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, null, tint = color)
        Text(label, style = MaterialTheme.typography.labelSmall, color = color)
    }
}

@Composable
private fun PasteBar(clip: Clip, enabled: Boolean, onCancel: () -> Unit, onPaste: () -> Unit) {
    Surface(tonalElevation = 3.dp, color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(Space.md), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${clip.entries.size} from ${clip.host} to ${if (clip.cut) "move" else "copy"} here?",
                style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f),
            )
            TextButton(onCancel) { Text("Clear") }
            Button(onPaste, enabled = enabled) {
                Icon(SecrmIcons.Paste, null, Modifier.size(18.dp))
                Spacer(Modifier.width(Space.xs))
                Text("Paste")
            }
        }
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
private fun EntryRow(e: SftpEntry, selected: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(e.name + if (e.isLink) "  →" else "", maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text("${e.perms} · ${date(e.mtime)}", style = MaterialTheme.typography.labelSmall) },
        leadingContent = { FileIcon(e) },
        trailingContent = { if (!e.isDir) Text(size(e.size), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) },
        colors = ListItemDefaults.colors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f) else Color.Transparent,
        ),
        modifier = Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick),
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
        name == "." || name == ".." -> "That name isn't allowed"
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
