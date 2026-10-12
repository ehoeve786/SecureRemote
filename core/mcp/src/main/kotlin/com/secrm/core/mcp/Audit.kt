package com.secrm.core.mcp

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class AuditEntry(
    val ts: String, val tool: String, val argsRedacted: String,
    val outcome: String, val durationMs: Long
)

class McpAuditLog(context: Context) {
    private val file = File(context.filesDir, "mcp_audit.jsonl")
    private val json = Json
    private val pats = listOf(
        Regex("(?i)(pass(word)?|secret|token|api[_-]?key)\\s*[:=]\\s*\\S+"),
        Regex("(?i)bearer\\s+\\S+"),
        Regex("[A-Za-z0-9+/]{40,}={0,2}")
    )
    suspend fun record(e: AuditEntry) = withContext(Dispatchers.IO) {
        var s = e.argsRedacted; pats.forEach { s = it.replace(s, "[REDACTED]") }
        file.appendText(json.encodeToString(e.copy(argsRedacted = s)) + "\n")
    }
    suspend fun tail(limit: Int = 200): List<AuditEntry> = withContext(Dispatchers.IO) {
        if (!file.exists()) emptyList()
        else file.readLines().takeLast(limit).mapNotNull {
            runCatching { json.decodeFromString<AuditEntry>(it) }.getOrNull()
        }
    }
}
