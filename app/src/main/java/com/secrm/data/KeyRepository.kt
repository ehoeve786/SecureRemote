package com.secrm.data

import com.secrm.core.security.EncryptedConfigStore
import com.secrm.core.ssh.tools.SavedKey
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * Global store for SSH keys. Metadata (id, name, algorithm, public key,
 * passphrase) lives in a single JSON blob; the private key material for each
 * entry lives in its own encrypted field keyed "ssh.key.<id>.pem" so the list
 * view can render without touching secrets.
 */
class KeyRepository(private val config: EncryptedConfigStore) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val listKey = "ssh.keys"
    private fun pemKey(id: String) = "ssh.key.$id.pem"

    fun list(): List<SavedKey> {
        val raw = config.get(listKey).orEmpty()
        if (raw.isBlank()) return emptyList()
        return runCatching { json.decodeFromString<List<SavedKey>>(raw) }
            .getOrDefault(emptyList())
    }

    fun find(id: String): SavedKey? = list().firstOrNull { it.id == id }

    fun privateKey(id: String): String? = config.get(pemKey(id))

    /** Insert a new key. Returns the generated id. */
    @Synchronized
    fun add(
        name: String,
        privatePem: String,
        publicKey: String = "",
        algorithm: String = "ssh-ed25519",
        passphrase: String = ""
    ): String {
        val id = UUID.randomUUID().toString()
        val updated = list() + SavedKey(
            id = id,
            name = name.trim(),
            algorithm = algorithm,
            publicKey = publicKey.trim(),
            passphrase = passphrase
        )
        config.put(listKey, json.encodeToString(updated))
        config.put(pemKey(id), privatePem)
        return id
    }

    @Synchronized
    fun updateMetadata(key: SavedKey) {
        val updated = list().map { if (it.id == key.id) key else it }
        config.put(listKey, json.encodeToString(updated))
    }

    @Synchronized
    fun delete(id: String) {
        val updated = list().filterNot { it.id == id }
        config.put(listKey, json.encodeToString(updated))
        config.remove(pemKey(id))
    }
}
