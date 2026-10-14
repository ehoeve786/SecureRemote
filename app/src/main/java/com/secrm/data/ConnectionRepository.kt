package com.secrm.data

import com.secrm.core.security.EncryptedConfigStore
import com.secrm.core.ssh.tools.AuthMethod
import com.secrm.core.ssh.tools.SavedConnection
import com.secrm.core.ssh.tools.SavedConnections
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class ConnectionRepository(
    private val config: EncryptedConfigStore,
    private val keys: KeyRepository,
    private val vault: BitwardenVault
) : SavedConnections {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    override fun list(): List<SavedConnection> {
        val raw = config.sshConnectionsJson.orEmpty()
        if (raw.isNotBlank()) {
            return runCatching { json.decodeFromString<List<SavedConnection>>(raw) }
                .getOrDefault(emptyList())
        }
        val host = config.sshHost.orEmpty()
        if (host.isBlank()) return emptyList()
        val legacy = SavedConnection(
            name = host, host = host,
            port = (config.sshPort ?: "22").toIntOrNull() ?: 22,
            user = config.sshUser.orEmpty(),
            authMethod = AuthMethod.PASSWORD,
            password = config.sshPassword.orEmpty()
        )
        write(listOf(legacy))
        return listOf(legacy)
    }

    override fun find(name: String): SavedConnection? = list().firstOrNull { it.name == name }

    override fun save(connection: SavedConnection) = upsert(connection)

    fun upsert(connection: SavedConnection) {
        val current = list().toMutableList()
        val idx = current.indexOfFirst { it.name == connection.name }
        if (idx >= 0) current[idx] = connection else current.add(connection)
        write(current)
    }

    fun delete(name: String) = write(list().filterNot { it.name == name })

    private fun write(list: List<SavedConnection>) {
        config.sshConnectionsJson = json.encodeToString(list)
    }

    override fun privateKeyFor(keyId: String): String? = keys.privateKey(keyId)

    override fun keyPassphraseFor(keyId: String): String? =
        keys.find(keyId)?.passphrase

    override fun bitwardenKeyFor(itemId: String): String? = vault.privateKey(itemId)
}
