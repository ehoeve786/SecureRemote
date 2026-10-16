package com.secrm.core.ssh.tools

import com.secrm.core.domain.CallDispatcher
import com.secrm.core.domain.Session
import com.secrm.core.domain.ToolResult
import com.secrm.core.ssh.SshClientManager
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SshToolsTest {
    private class FakeSaved(private val conns: List<SavedConnection>) : SavedConnections {
        override fun list() = conns
        override fun save(connection: SavedConnection) {}
        override fun find(name: String) = conns.firstOrNull { it.name == name }
        override fun privateKeyFor(keyId: String): String? = null
        override fun keyPassphraseFor(keyId: String): String? = null
        override fun bitwardenKeyFor(itemId: String): String? = null
    }

    private fun exec(vararg conns: SavedConnection, connection: String, command: String = "uptime"): ToolResult {
        val dispatcher = CallDispatcher().also { SshTools.register(it, FakeSaved(conns.toList()), SshClientManager()) }
        val args: JsonObject = buildJsonObject {
            put("connection", connection)
            put("command", command)
        }
        return runBlocking { dispatcher.dispatch(Session("t", "test"), "ssh.exec", args) }
    }

    private val pinned = "1A2B"

    @Test fun exposureIsOffUnlessTheOwnerEnablesIt() {
        // A connection saved without the flag must not run commands over MCP.
        val r = exec(SavedConnection(name = "box", host = "10.0.0.2", user = "me", hostKey = pinned), connection = "box")
        assertTrue(r.isError)
        assertEquals("not_permitted", r.errorCode)
    }

    @Test fun unpinnedHostIsRefusedBeforeAnyConnection() {
        val r = exec(
            SavedConnection(name = "box", host = "10.0.0.2", user = "me", allowMcpExec = true, hostKey = null),
            connection = "box",
        )
        assertTrue(r.isError)
        assertEquals("host_not_pinned", r.errorCode)
    }

    @Test fun unknownConnectionIsReported() {
        val r = exec(connection = "missing")
        assertEquals("unknown_connection", r.errorCode)
    }
}
