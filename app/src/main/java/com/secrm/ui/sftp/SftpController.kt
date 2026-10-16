package com.secrm.ui.sftp

import android.app.Application
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import com.secrm.core.ssh.SftpClient
import com.secrm.core.ssh.SftpEntry
import com.secrm.core.ssh.SshClientManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class Transfer(val upload: Boolean, val name: String, val done: Long, val total: Long, val index: Int, val count: Int)

/** Items copied or cut, waiting to be pasted into a folder. */
data class Clip(val entries: List<SftpEntry>, val cut: Boolean)

data class SftpUiState(
    val path: String? = null,
    val entries: List<SftpEntry> = emptyList(),
    /** A listing is in flight. */
    val loading: Boolean = false,
    /** Why the last listing failed; the list is then empty or stale. */
    val loadError: String? = null,
    /** A quick change (create, rename, delete, paste) is in flight. */
    val working: String? = null,
    val transfer: Transfer? = null,
    /** Paths picked in multi-select; empty when not selecting. */
    val selected: Set<String> = emptySet(),
    val clip: Clip? = null,
)

/** A one-off message for the snackbar, with an optional retry. */
data class SftpMessage(val text: String, val retry: (() -> Unit)? = null)

/**
 * Holds the file browser's state for the SSH session in [ssh]: screens render [state] and call the
 * methods below (unidirectional flow). It lives in the app, so path and listing survive tab changes;
 * [sync] resets it when a new SSH session replaces the one it was browsing.
 */
class SftpController(private val app: Application, private val ssh: SshClientManager) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var client = SftpClient(ssh)
    private var generation = -1
    private var listing: Job? = null
    private var home: String? = null
    @Volatile private var cancelRequested = false

    private val _state = MutableStateFlow(SftpUiState())
    val state: StateFlow<SftpUiState> = _state.asStateFlow()
    private val _messages = MutableSharedFlow<SftpMessage>(extraBufferCapacity = 8)
    val messages: SharedFlow<SftpMessage> = _messages.asSharedFlow()

    /** Call when the screen shows: starts at the home folder for a new session, otherwise keeps what it had. */
    fun sync() {
        if (generation == ssh.generation) return
        generation = ssh.generation
        client.close(); client = SftpClient(ssh); home = null
        _state.value = SftpUiState()
        goHome()
    }

    fun goHome() { scope.launch {
        val h = home ?: runCatching { client.home() }.getOrElse { return@launch fail(it) { goHome() } }.also { home = it }
        open(h)
    } }
    fun up() { _state.value.path?.let { open(SftpClient.parent(it)) } }
    fun refresh() { _state.value.path?.let(::open) ?: goHome() }

    fun open(dir: String) {
        listing?.cancel()
        listing = scope.launch {
            _state.update { it.copy(loading = true, selected = if (dir == it.path) it.selected else emptySet()) }
            runCatching { client.list(dir) }
                .onSuccess { list -> _state.update { s ->
                    s.copy(path = dir, entries = list, loading = false, loadError = null,
                        selected = s.selected.filterTo(HashSet()) { p -> list.any { it.path == p } })
                } }
                .onFailure { t -> _state.update { it.copy(loading = false, loadError = t.message ?: t.toString()) } }
        }
    }

    fun mkdir(name: String) = change("Creating folder") { client.mkdir(SftpClient.join(dir(), name)) }
    fun rename(e: SftpEntry, name: String) = change("Renaming") { client.rename(e.path, SftpClient.join(dir(), name)) }
    fun delete(items: List<SftpEntry>) = change(if (items.size == 1) "Deleting ${items[0].name}" else "Deleting ${items.size} items") {
        items.forEach { client.delete(it) }
    }
    fun cancelTransfer() { cancelRequested = true }

    // Selection

    fun toggle(e: SftpEntry) = _state.update { s -> s.copy(selected = if (e.path in s.selected) s.selected - e.path else s.selected + e.path) }
    fun selectAll() = _state.update { s -> s.copy(selected = s.entries.mapTo(HashSet()) { it.path }) }
    fun clearSelection() = _state.update { it.copy(selected = emptySet()) }
    private fun picked(): List<SftpEntry> = _state.value.let { s -> s.entries.filter { it.path in s.selected } }

    /** Copies (or cuts) [items]; paste them into any folder with [paste]. */
    fun copy(items: List<SftpEntry>, cut: Boolean) {
        if (items.isEmpty()) return
        _state.update { it.copy(clip = Clip(items, cut), selected = emptySet()) }
        _messages.tryEmit(SftpMessage("${items.size} ${if (cut) "cut" else "copied"}. Open the folder and tap Paste."))
    }

    fun clearClip() = _state.update { it.copy(clip = null) }

    /** Pastes the clipboard into the open folder: a move for cut, a copy on the host for copied items. */
    fun paste() {
        val clip = _state.value.clip ?: return
        val dir = _state.value.path ?: return
        change(if (clip.cut) "Moving" else "Copying") {
            val taken = _state.value.entries.mapTo(HashSet()) { it.name }
            for (e in clip.entries) {
                if (clip.cut && SftpClient.parent(e.path) == dir) continue // already in this folder
                val name = uniqueName(e.name, taken).also { taken += it }
                val to = SftpClient.join(dir, name)
                if (clip.cut) client.rename(e.path, to) else client.copy(e.path, to)
            }
            _state.update { it.copy(clip = null) }
        }
    }

    // Transfers

    fun download(e: SftpEntry, dest: Uri) {
        transfer(Transfer(false, e.name, 0, e.size, 1, 1)) { setProgress ->
            val ok = withContext(Dispatchers.IO) {
                app.contentResolver.openOutputStream(dest)!!.use { client.download(e.path, it, { cancelRequested }, setProgress) }
            }
            if (cancelRequested) runCatching { DocumentsContract.deleteDocument(app.contentResolver, dest) }
            ok
        }
    }

    /** Saves files and folders (folders recursively) into the folder the user picked. */
    fun downloadInto(items: List<SftpEntry>, folder: Uri) {
        val root = DocumentFile.fromTreeUri(app, folder) ?: return fail(IllegalStateException("Can't write to that folder"))
        val label = if (items.size == 1) items[0].name else "${items.size} items"
        transfer(Transfer(false, label, 0, 0, 1, 1)) { setProgress ->
            withContext(Dispatchers.IO) { items.forEach { if (!cancelRequested) saveTo(it, root, setProgress) } }
        }
        clearSelection()
    }

    private suspend fun saveTo(e: SftpEntry, parent: DocumentFile, setProgress: (Long) -> Unit) {
        if (e.isDir) {
            val d = parent.findFile(e.name)?.takeIf { it.isDirectory } ?: parent.createDirectory(e.name)
                ?: error("Couldn't create ${e.name}")
            for (c in client.list(e.path)) { if (cancelRequested) return; saveTo(c, d, setProgress) }
        } else {
            val f = parent.createFile("application/octet-stream", e.name) ?: error("Couldn't create ${e.name}")
            app.contentResolver.openOutputStream(f.uri)!!.use { client.download(e.path, it, { cancelRequested }, setProgress) }
        }
    }

    fun upload(uris: List<Uri>) {
        val dir = _state.value.path ?: return
        transfer(null) { _ ->
            uris.forEachIndexed { i, u ->
                if (cancelRequested) return@forEachIndexed
                val (name, size) = describe(u)
                val remote = SftpClient.join(dir, name)
                _state.update { it.copy(transfer = Transfer(true, name, 0, size, i + 1, uris.size)) }
                withContext(Dispatchers.IO) {
                    runCatching {
                        app.contentResolver.openInputStream(u)!!.use { client.upload(it, remote, { cancelRequested }) { n -> progress(n) } }
                    }.onFailure { if (cancelRequested) client.removeQuietly(remote) else throw it }
                }
            }
        }
    }

    /** Uploads a whole folder the user picked, into the open folder. */
    fun uploadFolder(tree: Uri) {
        val dir = _state.value.path ?: return
        val root = DocumentFile.fromTreeUri(app, tree) ?: return fail(IllegalStateException("Can't read that folder"))
        transfer(Transfer(true, root.name.orEmpty(), 0, 0, 1, 1)) { _ ->
            withContext(Dispatchers.IO) { putTree(root, dir) }
        }
    }

    private suspend fun putTree(src: DocumentFile, dir: String) {
        val name = src.name ?: return
        val remote = SftpClient.join(dir, name)
        if (src.isDirectory) {
            runCatching { client.mkdir(remote) } // already there is fine
            for (c in src.listFiles()) { if (cancelRequested) return; putTree(c, remote) }
        } else {
            _state.update { it.copy(transfer = it.transfer?.copy(name = name, done = 0, total = src.length())) }
            app.contentResolver.openInputStream(src.uri)!!.use { client.upload(it, remote, { cancelRequested }) { n -> progress(n) } }
        }
    }

    private fun progress(done: Long) = _state.update { s -> s.copy(transfer = s.transfer?.copy(done = done)) }

    private fun transfer(first: Transfer?, block: suspend ((Long) -> Unit) -> Unit) {
        if (_state.value.transfer != null) return
        cancelRequested = false
        scope.launch {
            _state.update { it.copy(transfer = first ?: Transfer(true, "", 0, 0, 1, 1)) }
            val result = runCatching { block(::progress) }
            _state.update { it.copy(transfer = null) }
            val t = result.exceptionOrNull()
            when {
                cancelRequested -> _messages.tryEmit(SftpMessage("Transfer cancelled"))
                t != null -> fail(t)
                else -> _messages.tryEmit(SftpMessage("Transfer complete"))
            }
            refresh()
        }
    }

    private fun change(label: String, block: suspend () -> Unit) {
        scope.launch {
            _state.update { it.copy(working = label) }
            runCatching { block() }.onFailure { fail(it) }
            _state.update { it.copy(working = null) }
            refresh()
        }
    }

    private fun dir() = _state.value.path ?: error("No folder open")

    private fun fail(t: Throwable, retry: (() -> Unit)? = null) {
        _state.update { it.copy(loadError = if (it.path == null) t.message ?: t.toString() else it.loadError) }
        _messages.tryEmit(SftpMessage(t.message ?: t.toString(), retry))
    }

    /** "name", then "name copy", "name copy 2", … keeping the extension, so nothing is overwritten. */
    private fun uniqueName(name: String, taken: Set<String>): String {
        if (name !in taken) return name
        val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
        val stem = name.substring(0, dot)
        val ext = name.substring(dot)
        var n = 1
        var candidate = "$stem copy$ext"
        while (candidate in taken) { n++; candidate = "$stem copy $n$ext" }
        return candidate
    }

    private fun describe(u: Uri): Pair<String, Long> {
        app.contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use {
            if (it.moveToFirst()) return (it.getString(0) ?: "upload") to (if (it.isNull(1)) 0L else it.getLong(1))
        }
        return (u.lastPathSegment?.substringAfterLast('/') ?: "upload") to 0L
    }
}
