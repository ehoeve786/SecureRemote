package com.secrm.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class BitwardenStatus(
    val status: String = "unauthenticated",   // "unauthenticated" | "locked" | "unlocked"
    @SerialName("userEmail") val userEmail: String? = null,
    @SerialName("userId") val userId: String? = null,
    @SerialName("serverUrl") val serverUrl: String? = null,
    @SerialName("lastSync") val lastSync: String? = null
) {
    val isAuthenticated: Boolean get() = status == "locked" || status == "unlocked"
    val isUnlocked: Boolean get() = status == "unlocked"
    val isInstalled: Boolean get() = status != "missing"
}

object BitwardenStatusParser {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * Parse the output of `bw status`. The CLI prints a single JSON object
     * on stdout. If the CLI isn't installed, callers should synthesize a
     * "missing" status instead of calling this.
     */
    fun parse(stdout: String): BitwardenStatus? {
        val trimmed = stdout.trim()
        if (trimmed.isEmpty()) return null
        // Some builds prefix or suffix warnings; grab the first {...} block.
        val start = trimmed.indexOf('{')
        val end = trimmed.lastIndexOf('}')
        if (start < 0 || end < start) return null
        val jsonSlice = trimmed.substring(start, end + 1)
        return runCatching { json.decodeFromString<BitwardenStatus>(jsonSlice) }.getOrNull()
    }

    val Missing = BitwardenStatus(status = "missing")
}
