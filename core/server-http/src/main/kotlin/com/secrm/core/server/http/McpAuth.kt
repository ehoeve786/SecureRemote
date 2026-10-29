package com.secrm.core.server.http

import java.security.MessageDigest

class McpAuth(
    private val localPort: Int,
    private val remotePort: Int,
    private val localToken: String,
    /** Hosts the remote connector may be reached by, read per request (this node's public name). */
    private val remoteAllowedHosts: () -> Set<String>,
    /** Names the client a remote bearer belongs to, so audit can tell agents apart. Null for an unnamed token. */
    private val clientOf: (String) -> String? = { null },
    /** Accepts a remote bearer: the app's static token or an OAuth access token. */
    private val remoteToken: (String) -> Boolean
) {
    sealed interface Decision {
        data object Allow : Decision
        data class Deny(val status: Int, val reason: String) : Decision
    }

    fun check(
        serverPort: Int,
        hostHeader: String?,
        originHeader: String?,
        authorization: String?
    ): Decision {
        val isLocal = serverPort == localPort
        val isRemote = serverPort == remotePort
        if (!isLocal && !isRemote) return Decision.Deny(403, "Unknown port")

        val host = hostHeader?.substringBefore(':')?.lowercase().orEmpty()
        val hostOk = if (isLocal) {
            host == "127.0.0.1" || host == "localhost" || host.isEmpty()
        } else {
            remoteAllowedHosts().any { matchesHost(host, it) }
        }
        if (!hostOk) return Decision.Deny(403, "Host not allowed")

        if (originHeader != null) {
            val originHost = runCatching {
                java.net.URI(originHeader).host?.lowercase().orEmpty()
            }.getOrDefault("")
            val originOk = if (isLocal) {
                originHost == "127.0.0.1" || originHost == "localhost"
            } else {
                remoteAllowedHosts().any { matchesHost(originHost, it) }
            }
            if (!originOk) return Decision.Deny(403, "Origin not allowed")
        }

        val token = bearerOf(authorization)
        return when {
            isLocal && token != null && MessageDigest.isEqual(token.toByteArray(), localToken.toByteArray()) -> Decision.Allow
            isRemote && token != null && remoteToken(token) -> Decision.Allow
            else -> Decision.Deny(401, "Unauthorized")
        }
    }

    /** The token in an Authorization header. The scheme name is case-insensitive, so "bearer" is accepted too. */
    private fun bearerOf(authorization: String?): String? =
        authorization?.let { if (it.regionMatches(0, "Bearer ", 0, 7, ignoreCase = true)) it.substring(7) else it }?.trim()

    /** The client name for a bearer header, or null if it is not a named token. */
    fun clientLabel(authorization: String?): String? =
        bearerOf(authorization)?.let(clientOf)

    /** Whether the OAuth and well-known routes may answer this request: remote port and an allowed host. */
    fun hostAllowed(serverPort: Int, hostHeader: String?): Boolean {
        if (serverPort != remotePort) return false
        val host = hostHeader?.substringBefore(':')?.lowercase().orEmpty()
        return remoteAllowedHosts().any { matchesHost(host, it) }
    }

    private fun matchesHost(host: String, pattern: String): Boolean {
        val p = pattern.lowercase()
        if (p.startsWith("*.")) {
            val suffix = p.removePrefix("*")
            return host.endsWith(suffix) && host.length > suffix.length
        }
        return host == p
    }
}
