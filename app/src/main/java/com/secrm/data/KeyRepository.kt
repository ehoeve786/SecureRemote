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

    fun list(): List<SavedKey> = runCatching { readStrict() }.getOrDefault(emptyList())

    /** Throws if the stored list is unreadable. Writes use this, so they never replace it with a partial list. */
    private fun readStrict(): List<SavedKey> {
        val raw = config.get(listKey).orEmpty()
        if (raw.isBlank()) return emptyList()
        return json.decodeFromString<List<SavedKey>>(raw)
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
        val updated = readStrict() + SavedKey(
            id = id,
            name = name.trim(),
            algorithm = algorithm,
            publicKey = publicKey.trim(),
            passphrase = passphrase
        )
        // The PEM goes first: a failure in between leaves an unused PEM, never a listed key with no PEM.
        config.put(pemKey(id), privatePem)
        config.put(listKey, json.encodeToString(updated))
        return id
    }

    @Synchronized
    fun updateMetadata(key: SavedKey) {
        val updated = readStrict().map { if (it.id == key.id) key else it }
        config.put(listKey, json.encodeToString(updated))
    }

    @Synchronized
    fun delete(id: String) {
        val updated = readStrict().filterNot { it.id == id }
        config.put(listKey, json.encodeToString(updated))
        config.remove(pemKey(id))
    }
}
