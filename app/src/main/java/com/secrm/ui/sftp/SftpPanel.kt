package com.secrm.ui.sftp

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.secrm.core.ssh.SftpClient
import com.secrm.core.ssh.SftpEntry
import com.secrm.design.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private sealed interface Prompt {
    data object NewFolder : Prompt
    data class Rename(val e: SftpEntry) : Prompt
    data class Delete(val e: SftpEntry) : Prompt
}

/** File browser for the connected host over SFTP: browse, upload, download, rename, delete, new folder. */
@Composable
fun SftpPanel(client: SftpClient, onCdInTerminal: (String) -> Unit, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var path by remember { mutableStateOf<String?>(null) }
    var entries by remember { mutableStateOf<List<SftpEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableLongStateOf(0L) }
    var error by remember { mutableStateOf<String?>(null) }
    var actions by remember { mutableStateOf<SftpEntry?>(null) }
    var prompt by remember { mutableStateOf<Prompt?>(null) }
    var downloading by remember { mutableStateOf<SftpEntry?>(null) }

    fun fail(t: Throwable) { error = t.message ?: t.toString() }
    fun load(dir: String) = scope.launch {
        loading = true
        runCatching { client.list(dir) }.onSuccess { entries = it; path = dir; error = null }.onFailure(::fail)
        loading = false
    }
    /** Runs a change, then reloads the folder. */
    fun change(label: String, block: suspend () -> Unit) = scope.launch {
        busy = label; progress = 0
        runCatching { block() }.onSuccess { error = null }.onFailure(::fail)
        busy = null
        path?.let { load(it) }
    }
    LaunchedEffect(Unit) { runCatching { client.home() }.onSuccess { load(it) }.onFailure { fail(it); loading = false } }
    DisposableEffect(Unit) { onDispose { client.close() } }

    val download = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val e = downloading ?: return@rememberLauncherForActivityResult
        uri ?: return@rememberLauncherForActivityResult
        change("Downloading ${e.name}") {
            withContext(Dispatchers.IO) {
                ctx.contentResolver.openOutputStream(uri)!!.use { client.download(e.path, it) { n -> progress = n } }
            }
        }
    }
    val upload = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val dir = path ?: return@rememberLauncherForActivityResult
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        change("Uploading") {
            for (u in uris) withContext(Dispatchers.IO) {
                busy = "Uploading ${displayName(ctx, u)}"; progress = 0
                ctx.contentResolver.openInputStream(u)!!.use { client.upload(it, SftpClient.join(dir, displayName(ctx, u))) { n -> progress = n } }
            }
        }
    }

    // The pointerInput stops touches reaching the terminal underneath.
    Column(modifier.background(Palette.Void).pointerInput(Unit) { detectTapGestures {} }) {
        Row(Modifier.fillMaxWidth().padding(horizontal = Space.md), verticalAlignment = Alignment.CenterVertically) {
            Link("↑ Up", enabled = path != null && path != "/") { path?.let { load(SftpClient.parent(it)) } }
            Spacer(Modifier.width(Space.sm))
            Text(path ?: "…", style = SecrmType.MonoSmall, color = Palette.TextPrimary, maxLines = 1,
                overflow = TextOverflow.StartEllipsis, modifier = Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = Space.md), horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
            Link("Upload", path != null) { upload.launch(arrayOf("*/*")) }
            Link("New folder", path != null) { prompt = Prompt.NewFolder }
            Link("cd in terminal", path != null) { path?.let(onCdInTerminal) }
            Spacer(Modifier.weight(1f))
            Link("Refresh", path != null) { path?.let { load(it) } }
        }
        (busy?.let { "$it · ${size(progress)}" } ?: error)?.let {
            Text(it, style = SecrmType.MonoSmall, color = if (busy != null) Palette.Indigo else Palette.Danger,
                modifier = Modifier.padding(horizontal = Space.md, vertical = Space.xs))
        }
        if (busy != null || loading) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Palette.Indigo)
        LazyColumn(Modifier.weight(1f)) {
            items(entries, key = { it.path }) { e ->
                Row(
                    Modifier.fillMaxWidth()
                        .pointerInput(e) { detectTapGestures(onLongPress = { actions = e }, onTap = { if (e.isDir) load(e.path) else actions = e }) }
                        .padding(horizontal = Space.md, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(if (e.isDir) "▸" else "·", color = if (e.isDir) Palette.Indigo else Palette.TextTertiary, style = SecrmType.Body)
                    Spacer(Modifier.width(Space.sm))
                    Column(Modifier.weight(1f)) {
                        Text(e.name + if (e.isLink) " →" else "", style = SecrmType.Body, color = Palette.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${e.perms}  ${date(e.mtime)}", style = SecrmType.MonoSmall, color = Palette.TextTertiary)
                    }
                    if (!e.isDir) Text(size(e.size), style = SecrmType.MonoSmall, color = Palette.TextSecondary)
                }
            }
            if (!loading && entries.isEmpty() && path != null) item {
                Text("Empty folder", style = SecrmType.Body, color = Palette.TextTertiary, modifier = Modifier.padding(Space.md))
            }
        }
    }

    actions?.let { e ->
        AlertDialog(
            onDismissRequest = { actions = null },
            title = { Text(e.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            text = {
                Column {
                    if (!e.isDir) Action("Download") { actions = null; downloading = e; download.launch(e.name) }
                    if (e.isDir) Action("cd in terminal") { actions = null; onCdInTerminal(e.path) }
                    Action("Rename") { actions = null; prompt = Prompt.Rename(e) }
                    Action("Copy path") {
                        actions = null
                        (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("path", e.path))
                    }
                    Action("Delete", Palette.Danger) { actions = null; prompt = Prompt.Delete(e) }
                }
            },
            confirmButton = { TextButton({ actions = null }) { Text("Close") } },
        )
    }

    prompt?.let { p ->
        var text by remember(p) { mutableStateOf((p as? Prompt.Rename)?.e?.name ?: "") }
        val dir = path ?: return@let
        AlertDialog(
            onDismissRequest = { prompt = null },
            title = { Text(when (p) { Prompt.NewFolder -> "New folder"; is Prompt.Rename -> "Rename"; is Prompt.Delete -> "Delete ${p.e.name}?" }) },
            text = {
                when (p) {
                    is Prompt.Delete -> Text(if (p.e.isDir) "The folder and everything in it will be deleted." else "This can't be undone.")
                    else -> OutlinedTextField(text, { text = it }, singleLine = true)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    prompt = null
                    when (p) {
                        Prompt.NewFolder -> if (text.isNotBlank()) change("Creating folder") { client.mkdir(SftpClient.join(dir, text.trim())) }
                        is Prompt.Rename -> if (text.isNotBlank()) change("Renaming") { client.rename(p.e.path, SftpClient.join(dir, text.trim())) }
                        is Prompt.Delete -> change("Deleting ${p.e.name}") { client.delete(p.e) }
                    }
                }) { Text(if (p is Prompt.Delete) "Delete" else "OK", color = if (p is Prompt.Delete) Palette.Danger else Palette.Indigo) }
            },
            dismissButton = { TextButton({ prompt = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Link(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Box(Modifier.clickableNoRipple { if (enabled) onClick() }.padding(vertical = Space.sm)) {
        Text(label, style = SecrmType.MonoSmall, color = if (enabled) Palette.Indigo else Palette.TextTertiary)
    }
}

@Composable
private fun Action(label: String, color: androidx.compose.ui.graphics.Color = Palette.TextPrimary, onClick: () -> Unit) {
    Text(label, color = color, style = SecrmType.Body, modifier = Modifier.fillMaxWidth().clickableNoRipple(onClick).padding(vertical = Space.md))
}

private fun displayName(ctx: Context, uri: Uri): String =
    ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }
        ?: uri.lastPathSegment?.substringAfterLast('/') ?: "upload"

private fun size(b: Long): String = when {
    b >= 1L shl 30 -> "%.1f GB".format(b / (1L shl 30).toDouble())
    b >= 1L shl 20 -> "%.1f MB".format(b / (1L shl 20).toDouble())
    b >= 1L shl 10 -> "%.1f KB".format(b / 1024.0)
    else -> "$b B"
}

private fun date(sec: Long): String = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(sec * 1000))
