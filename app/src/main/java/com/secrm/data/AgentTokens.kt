package com.secrm.data

import com.secrm.core.security.EncryptedConfigStore
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Named bearer tokens, one per agent, so each can be revoked alone and audit shows which one acted.
 * Only a SHA-256 of each token is stored; the plain token is shown once, when it is created.
 */
class AgentTokens(private val store: EncryptedConfigStore) {
    data class Agent(val id: String, val label: String, val created: String, val revoked: Boolean)

    private class Record(val agent: Agent, val hash: String)

    /** One record per line: id, label, hash, created, revoked; tab-separated, labels kept free of tabs and newlines. */
    private fun read(): List<Record> = store.get(KEY).orEmpty().lines().mapNotNull { line ->
        val f = line.split('\t')
        if (f.size != 5) return@mapNotNull null
        Record(Agent(f[0], f[1], f[3], f[4] == "1"), f[2])
    }

    private fun write(records: List<Record>) = store.put(KEY, records.joinToString("\n") { r ->
        listOf(r.agent.id, r.agent.label, r.hash, r.agent.created, if (r.agent.revoked) "1" else "0").joinToString("\t")
    })

    @Synchronized fun list(): List<Agent> = read().map { it.agent }

    /**
     * Creates a token for [label] and returns it. It cannot be shown again. Returns null, and creates nothing,
     * while the store cannot save: the token would be dropped and the owner would be handed a dead one.
     */
    @Synchronized fun create(label: String): String? {
        if (store.keystoreLost) return null
        val token = ByteArray(32).also(random::nextBytes).joinToString("") { "%02x".format(it) }
        val id = ByteArray(6).also(random::nextBytes).joinToString("") { "%02x".format(it) }
        val clean = label.replace(Regex("[\\t\\n\\r]"), " ").trim().take(40).ifBlank { "agent" }
        write(read() + Record(Agent(id, clean, java.time.LocalDate.now().toString(), revoked = false), hash(token)))
        return token
    }

    /** Stops [id] from working; the record stays, so the owner still sees it was there. */
    @Synchronized fun revoke(id: String) = write(read().map { r ->
        if (r.agent.id == id) Record(r.agent.copy(revoked = true), r.hash) else r
    })

    /** The label of the active agent [token] belongs to, or null. */
    @Synchronized fun match(token: String): String? {
        val h = hash(token)
        return read().firstOrNull { !it.agent.revoked && MessageDigest.isEqual(it.hash.toByteArray(), h.toByteArray()) }?.agent?.label
    }

    private fun hash(token: String) = MessageDigest.getInstance("SHA-256").digest(token.toByteArray())
        .joinToString("") { "%02x".format(it) }

    private companion object {
        const val KEY = "mcp.agent_tokens"
        val random = SecureRandom()
    }
}
