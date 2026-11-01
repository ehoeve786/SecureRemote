package com.secrm.core.mcp

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class AuditEntry(
    val ts: String, val tool: String, val argsRedacted: String,
    val outcome: String, val durationMs: Long,
    /** Which client made the call: a named agent token, "claude.ai" or "mcp". Empty in entries from before this field. */
    val client: String = "",
)

class McpAuditLog(context: Context) {
    private val file = File(context.filesDir, "mcp_audit.jsonl")
    private val json = Json
    private val pats = listOf(
        Regex("(?i)(pass(word)?|secret|token|api[_-]?key)\\s*[:=]\\s*\\S+"),
        Regex("(?i)bearer\\s+\\S+"),
        Regex("[A-Za-z0-9+/]{40,}={0,2}")
    )
    private val rotated = File(file.path + ".1")
    private val lock = Mutex()

    suspend fun record(e: AuditEntry) = withContext(Dispatchers.IO) {
        var s = e.argsRedacted; pats.forEach { s = it.replace(s, "[REDACTED]") }
        val line = json.encodeToString(e.copy(argsRedacted = s)) + "\n"
        lock.withLock {
            // Keep the log bounded: the current file rotates at MAX_BYTES (1 MB) into one previous file, so at most 2 MB is kept.
            if (file.length() > MAX_BYTES) file.renameTo(rotated)
            file.appendText(line)
        }
    }
    suspend fun tail(limit: Int = 200): List<AuditEntry> = withContext(Dispatchers.IO) {
        lock.withLock {
            (rotated.takeIf { it.exists() }?.readLines().orEmpty() + file.takeIf { it.exists() }?.readLines().orEmpty())
                .takeLast(limit).mapNotNull { runCatching { json.decodeFromString<AuditEntry>(it) }.getOrNull() }
        }
    }

    private companion object { const val MAX_BYTES = 1_000_000L }
}
