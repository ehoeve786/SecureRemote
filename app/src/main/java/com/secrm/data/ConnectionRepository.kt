package com.secrm.data

import com.secrm.core.security.EncryptedConfigStore
import com.secrm.core.ssh.tools.AuthMethod
import com.secrm.core.ssh.tools.SavedConnection
import com.secrm.core.ssh.tools.SavedConnections
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class ConnectionRepository(
    private val config: EncryptedConfigStore,
    private val keys: KeyRepository,
) : SavedConnections {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    init {
        // Read access used to default on, so every saved host was listed to paired agents. Reset once.
        if (!config.mcpReadDefaultMigrated) {
            runCatching { write(readStrict().map { it.copy(allowMcpRead = false) }) }
                .onSuccess { config.mcpReadDefaultMigrated = true }
        }
    }

    override fun list(): List<SavedConnection> = runCatching { readStrict() }.getOrDefault(emptyList())

    /** Throws if the stored list is unreadable. Writes use this, so they never replace it with a partial list. */
    private fun readStrict(): List<SavedConnection> {
        val raw = config.sshConnectionsJson.orEmpty()
        if (raw.isNotBlank()) return json.decodeFromString(
            JsonArray(json.parseToJsonElement(raw).jsonArray.map(::fromBitwarden)).toString()
        )
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

    /** Bitwarden auth was removed: a host saved with it becomes a key host, to be given its key again. */
    private fun fromBitwarden(el: JsonElement): JsonElement {
        val o = el.jsonObject
        return if (o["authMethod"]?.jsonPrimitive?.content == "AGENT")
            JsonObject(o + ("authMethod" to JsonPrimitive("SAVED_KEY"))) else el
    }

    override fun find(name: String): SavedConnection? = list().firstOrNull { it.name == name }

    override fun save(connection: SavedConnection) = upsert(connection)

    /** Synchronized: the MCP path and the app both save, and an unlocked read-modify-write loses updates. */
    @Synchronized
    override fun update(name: String, change: (SavedConnection) -> SavedConnection) {
        val current = readStrict().toMutableList()
        val idx = current.indexOfFirst { it.name == name }
        if (idx < 0) return
        current[idx] = change(current[idx])
        write(current)
    }

    /** Synchronized: the MCP path and the app both save, and an unlocked read-modify-write loses updates. */
    @Synchronized
    fun upsert(connection: SavedConnection) {
        val current = readStrict().toMutableList()
        val idx = current.indexOfFirst { it.name == connection.name }
        if (idx >= 0) current[idx] = connection else current.add(connection)
        write(current)
    }

    @Synchronized
    fun delete(name: String) = write(readStrict().filterNot { it.name == name })

    private fun write(list: List<SavedConnection>) {
        config.sshConnectionsJson = json.encodeToString(list)
    }

    override fun privateKeyFor(keyId: String): String? = keys.privateKey(keyId)

    override fun keyPassphraseFor(keyId: String): String? =
        keys.find(keyId)?.passphrase
}
