package com.secrm.core.ssh

import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.SftpProgressMonitor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream

data class SftpEntry(
    val name: String,
    val path: String,
    val isDir: Boolean,
    val isLink: Boolean,
    val size: Long,
    /** Modified time, Unix seconds. */
    val mtime: Long,
    /** e.g. `rwxr-xr-x`. */
    val perms: String,
)

/** SFTP over a second channel of the SSH session already open in [ssh]; reopens it if the session is replaced. */
class SftpClient(private val ssh: SshClientManager) {
    private val lock = Mutex() // one SFTP request at a time on the channel
    private var channel: ChannelSftp? = null

    private suspend fun <T> run(block: (ChannelSftp) -> T): T = withContext(Dispatchers.IO) {
        lock.withLock {
            val ch = channel?.takeIf { it.isConnected && !it.isClosed } ?: run {
                val s = ssh.activeSession() ?: error("Not connected")
                (s.openChannel("sftp") as ChannelSftp).also { it.connect(10_000); channel = it }
            }
            try { block(ch) } catch (t: Throwable) { if (!ch.isConnected) channel = null; throw t }
        }
    }

    fun close() { runCatching { channel?.disconnect() }; channel = null }

    suspend fun home(): String = run { it.realpath(".") }

    suspend fun list(dir: String): List<SftpEntry> = run { ch ->
        ch.ls(dir).filterIsInstance<ChannelSftp.LsEntry>()
            .filter { it.filename != "." && it.filename != ".." }
            .map { e ->
                val path = join(dir, e.filename)
                val a = e.attrs
                // A symlink counts as a folder if what it points at is one.
                val dirLike = a.isDir || (a.isLink && runCatching { ch.stat(path).isDir }.getOrDefault(false))
                SftpEntry(e.filename, path, dirLike, a.isLink, a.size, a.mTime.toLong(), a.permissionsString.drop(1))
            }
            .sortedWith(compareByDescending<SftpEntry> { it.isDir }.thenBy { it.name.lowercase() })
    }

    /** [shouldCancel] is polled as data moves; when it turns true the transfer stops and throws. */
    suspend fun download(path: String, out: OutputStream, shouldCancel: () -> Boolean = { false }, onProgress: (Long) -> Unit = {}) =
        run { it.get(path, out, monitor(shouldCancel, onProgress)) }

    suspend fun upload(input: InputStream, path: String, shouldCancel: () -> Boolean = { false }, onProgress: (Long) -> Unit = {}) =
        run { it.put(input, path, monitor(shouldCancel, onProgress), ChannelSftp.OVERWRITE) }

    /** Removes a file, ignoring failure (used to clean up a cancelled upload). */
    suspend fun removeQuietly(path: String) { runCatching { run { it.rm(path) } } }

    suspend fun mkdir(path: String) = run { it.mkdir(path) }
    suspend fun rename(from: String, to: String) = run { it.rename(from, to) }

    /** Deletes a file, or a folder with everything in it. */
    suspend fun delete(e: SftpEntry) = run { rm(it, e.path, e.isDir && !e.isLink) }

    private fun rm(ch: ChannelSftp, path: String, dir: Boolean) {
        if (!dir) return ch.rm(path)
        for (c in ch.ls(path).filterIsInstance<ChannelSftp.LsEntry>()) {
            if (c.filename == "." || c.filename == "..") continue
            rm(ch, join(path, c.filename), c.attrs.isDir)
        }
        ch.rmdir(path)
    }

    private fun monitor(shouldCancel: () -> Boolean, onProgress: (Long) -> Unit) = object : SftpProgressMonitor {
        var total = 0L
        override fun init(op: Int, src: String?, dest: String?, max: Long) {}
        override fun count(n: Long): Boolean { total += n; onProgress(total); return !shouldCancel() }
        override fun end() {}
    }

    companion object {
        fun join(dir: String, name: String) = if (dir.endsWith("/")) dir + name else "$dir/$name"
        fun parent(path: String) = path.trimEnd('/').substringBeforeLast('/', "").ifEmpty { "/" }
    }
}
