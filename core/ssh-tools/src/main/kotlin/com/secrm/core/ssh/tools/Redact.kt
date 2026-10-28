package com.secrm.core.ssh.tools

import com.secrm.core.ssh.SshAuth

/**
 * Removes credentials from text an agent is about to read: the connection's own secrets wherever they
 * appear, PEM private key blocks, and `password=`/`token=`-style assignments.
 */
object Redact {
    private const val MARK = "[REDACTED]"
    private const val MIN_SECRET = 4

    private val pem = Regex("-----BEGIN [A-Z0-9 ]*PRIVATE KEY-----[\\s\\S]*?-----END [A-Z0-9 ]*PRIVATE KEY-----")
    private val assignment = Regex("(?i)\\b(pass(word)?|passphrase|secret|token|api[_-]?key)(\\s*[:=]\\s*)\\S+")
    private val bearer = Regex("(?i)bearer\\s+[A-Za-z0-9._~+/-]+=*")

    /** Every secret the connection carries, for [text] to be scrubbed of. */
    fun secretsOf(auth: SshAuth): List<String> = when (auth) {
        is SshAuth.Password -> listOf(auth.password)
        is SshAuth.Key -> listOfNotNull(auth.pem, auth.passphrase)
    }

    fun text(text: String, secrets: Collection<String>): String {
        var s = pem.replace(text, MARK)
        // Longest first, so a secret that contains a shorter one is removed whole.
        secrets.filter { it.length >= MIN_SECRET }.sortedByDescending { it.length }
            .forEach { s = s.replace(it, MARK) }
        s = assignment.replace(s) { "${it.groupValues[1]}${it.groupValues[3]}$MARK" }
        return bearer.replace(s, "Bearer $MARK")
    }
}
