package com.secrm.core.server.http

class McpAuth(
    private val localPort: Int,
    private val remotePort: Int,
    private val localToken: String,
    private val remoteAllowedHosts: Set<String>,
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
            remoteAllowedHosts.any { matchesHost(host, it) }
        }
        if (!hostOk) return Decision.Deny(403, "Host not allowed")

        if (originHeader != null) {
            val originHost = runCatching {
                java.net.URI(originHeader).host?.lowercase().orEmpty()
            }.getOrDefault("")
            val originOk = if (isLocal) {
                originHost == "127.0.0.1" || originHost == "localhost"
            } else {
                remoteAllowedHosts.any { matchesHost(originHost, it) }
            }
            if (!originOk) return Decision.Deny(403, "Origin not allowed")
        }

        val token = authorization?.removePrefix("Bearer ")?.trim()
        return when {
            isLocal && token == localToken -> Decision.Allow
            isRemote && token != null && remoteToken(token) -> Decision.Allow
            else -> Decision.Deny(401, "Unauthorized")
        }
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
