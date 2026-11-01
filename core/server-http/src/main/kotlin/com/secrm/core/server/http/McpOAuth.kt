package com.secrm.core.server.http

import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Minimal OAuth 2.1 server so claude.ai can add the MCP endpoint as a custom connector (it cannot
 * send a static bearer token). Dynamic client registration, authorization code with PKCE (S256
 * only) and rotating refresh tokens, after PocketPilot's AuthorizationServer. Approval is the
 * pairing code the app shows: only someone holding the phone can connect. Only Claude's redirect
 * URIs are accepted. Tokens are kept as SHA-256 hashes through [load]/[save].
 *
 * Every token belongs to a family. Replaying a rotated refresh token revokes its whole family,
 * so a stolen refresh token ends the session instead of quietly coexisting with the live one.
 */
class McpOAuth(
    load: () -> String?,
    private val save: (String) -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val random = SecureRandom()

    /** kind: 'a' access, 'r' live refresh, 'c' consumed refresh (kept to detect reuse). */
    private data class Entry(val kind: Char, val expires: Long, val family: String)

    /** Token hash → entry. Persisted as "hash kind expires family" lines. */
    private val tokens = HashMap<String, Entry>().apply {
        // A malformed line is skipped rather than failing construction, which runs at app start.
        load().orEmpty().lines().forEach { l ->
            runCatching {
                val f = l.split(' ')
                when (f.size) {
                    4 -> put(f[0], Entry(f[1].single(), f[2].toLong(), f[3]))
                    3 -> put(f[0], Entry(f[1].single(), f[2].toLong(), f[0])) // older format: no family
                }
            }
        }
    }

    private class Code(val challenge: String, val redirect: String, val expires: Long)
    private val codes = HashMap<String, Code>()

    private var pairing: String? = null
    private var pairingExpires = 0L

    /**
     * A new single-use pairing code, valid for 10 minutes. Guessing is bounded by the route's rate limit
     * (about 100 tries in that time against 31^8 codes), not a wrong-try lockout, which anyone who has
     * the public URL could trip to cancel the owner's code.
     */
    @Synchronized
    fun newPairingCode(): String =
        (1..8).map { ALPHABET[random.nextInt(ALPHABET.length)] }.joinToString("")
            .also { pairing = it; pairingExpires = now() + 10 * MINUTE }

    @Synchronized
    fun isValid(token: String) = tokens[sha(token)]?.let { it.kind == 'a' && it.expires > now() } == true

    /** Disconnects every client. */
    @Synchronized
    fun revokeAll() { tokens.clear(); codes.clear(); persist() }

    /** Exchanges a correct pairing code for an authorization code, or returns null. */
    @Synchronized
    fun authorize(code: String, challenge: String, redirect: String): String? {
        codes.values.removeAll { it.expires < now() }
        val p = pairing
        if (p == null || now() > pairingExpires || !MessageDigest.isEqual(code.trim().uppercase().toByteArray(), p.toByteArray())) return null
        pairing = null
        return token().also { codes[it] = Code(challenge, redirect, now() + MINUTE) }
    }

    /** The token endpoint. Returns (access, refresh), or null for invalid_grant. */
    @Synchronized
    fun token(params: Map<String, String?>): Pair<String, String>? {
        var family = token()
        var refreshedFrom: String? = null
        when (params["grant_type"]) {
            "authorization_code" -> {
                val c = params["code"]?.let(codes::remove) ?: return null
                val verifier = params["code_verifier"] ?: return null
                // RFC 7636 4.1: 43 to 128 characters.
                if (verifier.length !in 43..128) return null
                // RFC 6749 4.1.3: redirect_uri is required here when the authorize request sent one.
                if (c.expires < now() || params["redirect_uri"] != c.redirect) return null
                if (!MessageDigest.isEqual(b64(sha256(verifier.toByteArray())).toByteArray(), c.challenge.toByteArray())) return null
            }
            "refresh_token" -> {
                // Rotating: each refresh token works once. A consumed one coming back means it leaked,
                // so the whole family is revoked. Access tokens are not refresh tokens and never match here.
                val key = sha(params["refresh_token"] ?: return null)
                val r = tokens[key] ?: return null
                if (r.kind == 'c') {
                    // A retry just after a successful refresh (its response was lost) gets the next pair; a later reuse is a theft.
                    val consumed = consumedAt[key]
                    if (consumed == null || now() - consumed > REUSE_GRACE_MS) {
                        tokens.values.removeAll { it.family == r.family }
                        persist()
                        return null
                    }
                    // Only one pair may stay live: the one this retry gets. The first pair's access token goes, and its
                    // refresh token is marked used, so if both copies were real (one stolen) the other's next refresh
                    // revokes the family.
                    reissued.remove(key)?.let { (a, rf) ->
                        tokens.remove(a)
                        tokens[rf]?.let { tokens[rf] = it.copy(kind = 'c'); consumedAt.remove(rf) }
                    }
                } else {
                    if (r.kind != 'r' || r.expires < now()) return null
                    tokens[key] = r.copy(kind = 'c')
                    consumedAt[key] = now()
                }
                family = r.family
                refreshedFrom = key
            }
            else -> return null
        }
        val access = token()
        val refresh = token()
        tokens.values.removeAll { it.expires < now() }
        consumedAt.values.removeAll { now() - it > REUSE_GRACE_MS }
        reissued.keys.retainAll(consumedAt.keys)
        tokens[sha(access)] = Entry('a', now() + HOUR, family)
        tokens[sha(refresh)] = Entry('r', now() + 30 * DAY, family)
        refreshedFrom?.let { reissued[it] = sha(access) to sha(refresh) }
        persist()
        return access to refresh
    }

    /** When each consumed refresh token was used. Not persisted: after a restart a reuse is treated as theft. */
    private val consumedAt = HashMap<String, Long>()

    /** Consumed refresh token hash to the (access, refresh) hashes its use issued, while a retry is still allowed. */
    private val reissued = HashMap<String, Pair<String, String>>()

    private fun persist() = save(tokens.entries.joinToString("\n") { "${it.key} ${it.value.kind} ${it.value.expires} ${it.value.family}" })
    private fun token() = b64(ByteArray(32).also(random::nextBytes))
    private fun sha(s: String) = sha256(s.toByteArray()).joinToString("") { "%02x".format(it) }

    companion object {
        /** How long after a refresh a retry of the same refresh token is treated as a lost response, not a theft. */
        const val REUSE_GRACE_MS = 30_000L
        const val ACCESS_SECONDS = 3600
        private const val MINUTE = 60_000L
        private const val HOUR = 60 * MINUTE
        private const val DAY = 24 * HOUR
        private const val ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
        private fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b)
        private fun b64(b: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(b)

        /** Claude's OAuth callbacks only. */
        fun allowedRedirect(uri: String?) = runCatching { URI(uri) }.getOrNull()
            ?.let { it.scheme == "https" && it.host?.lowercase() in setOf("claude.ai", "claude.com") && it.path == "/api/mcp/auth_callback" } == true
    }
}
