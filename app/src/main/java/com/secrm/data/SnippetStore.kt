package com.secrm.data

import com.secrm.core.security.EncryptedConfigStore
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.util.UUID

@Serializable
data class Snippet(val id: String, val name: String, val command: String)

/**
 * Reusable commands, kept in the encrypted config. In the terminal they are inserted, not run: the owner presses
 * Enter. MCP agents with exec access to a host can also run them there by name (ssh.run_snippet).
 */
class SnippetStore(private val store: EncryptedConfigStore) {
    @Synchronized
    fun list(): List<Snippet> = store.get(KEY)?.let {
        runCatching { json.decodeFromString(ListSerializer(Snippet.serializer()), it) }.getOrNull()
    }.orEmpty()

    @Synchronized
    fun add(name: String, command: String) {
        val s = Snippet(UUID.randomUUID().toString(), name.trim().take(60), command)
        write(list() + s)
    }

    @Synchronized
    fun delete(id: String) = write(list().filterNot { it.id == id })

    private fun write(items: List<Snippet>) = store.put(KEY, json.encodeToString(ListSerializer(Snippet.serializer()), items))

    private companion object {
        const val KEY = "snippets"
        val json = Json { ignoreUnknownKeys = true }
    }
}
