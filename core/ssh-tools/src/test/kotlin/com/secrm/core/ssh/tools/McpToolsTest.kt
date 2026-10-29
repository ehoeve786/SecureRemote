package com.secrm.core.ssh.tools

import com.secrm.core.domain.CallDispatcher
import com.secrm.core.domain.Session
import com.secrm.core.domain.ToolResult
import com.secrm.core.ssh.SshClientManager
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The refusals and argument checks of the snippet and port-forward tools; each returns before any connection is made. */
class McpToolsTest {
    private class FakeSaved(private val conns: List<SavedConnection>) : SavedConnections {
        override fun list() = conns
        override fun save(connection: SavedConnection) {}
        override fun find(name: String) = conns.firstOrNull { it.name == name }
        override fun privateKeyFor(keyId: String): String? = null
        override fun keyPassphraseFor(keyId: String): String? = null
    }

    private val snippets = listOf(NamedCommand("logs", "journalctl -u {unit} -n {lines}"))

    private fun box(exec: Boolean) = SavedConnection(
        name = "box", host = "10.0.0.2", user = "me", hostKey = "1A2B", allowMcpExec = exec,
    )

    private fun call(vararg conns: SavedConnection, tool: String, args: JsonObject): ToolResult {
        val dispatcher = CallDispatcher().also {
            SshTools.register(it, FakeSaved(conns.toList()), SshClientManager()) { snippets }
        }
        return runBlocking { dispatcher.dispatch(Session("t", "test"), tool, args) }
    }

    private fun text(r: ToolResult) = (r.content.single() as ToolResult.Content.Text).text

    @Test fun listSnippetsNamesTheVariablesButNotTheCommand() {
        val r = call(box(exec = false), tool = "ssh.list_snippets", args = JsonObject(emptyMap()))
        assertFalse(r.isError)
        assertTrue(text(r).contains("\"logs\""))
        assertTrue(text(r).contains("\"unit\""))
        assertTrue(text(r).contains("\"lines\""))
        assertFalse(text(r).contains("journalctl"))
    }

    @Test fun runSnippetRejectsAnUnknownName() {
        val r = call(box(exec = true), tool = "ssh.run_snippet", args = buildJsonObject {
            put("connection", "box"); put("snippet", "nope")
        })
        assertEquals("unknown_snippet", r.errorCode)
    }

    @Test fun runSnippetNeedsEveryVariable() {
        val r = call(box(exec = true), tool = "ssh.run_snippet", args = buildJsonObject {
            put("connection", "box"); put("snippet", "logs")
            putJsonObject("variables") { put("unit", "nginx") }
        })
        assertEquals("missing_variable", r.errorCode)
        assertTrue(text(r).contains("lines"))
    }

    @Test fun runSnippetNeedsTheExecPermission() {
        val r = call(box(exec = false), tool = "ssh.run_snippet", args = buildJsonObject {
            put("connection", "box"); put("snippet", "logs")
            putJsonObject("variables") { put("unit", "nginx"); put("lines", "50") }
        })
        assertEquals("not_permitted", r.errorCode)
    }

    @Test fun forwardPortRejectsPrivilegedAndOutOfRangePorts() {
        for (local in listOf(80, 70000)) {
            val r = call(box(exec = true), tool = "ssh.forward_port", args = buildJsonObject {
                put("connection", "box"); put("local_port", local); put("host", "127.0.0.1"); put("port", 22)
            })
            assertEquals("invalid_argument", r.errorCode)
        }
        val badRemote = call(box(exec = true), tool = "ssh.forward_port", args = buildJsonObject {
            put("connection", "box"); put("local_port", 8080); put("host", "127.0.0.1"); put("port", 0)
        })
        assertEquals("invalid_argument", badRemote.errorCode)
    }

    @Test fun forwardPortNeedsTheHostAndPort() {
        val r = call(box(exec = true), tool = "ssh.forward_port", args = buildJsonObject {
            put("connection", "box"); put("local_port", 8080); put("port", 22)
        })
        assertEquals("missing_argument", r.errorCode)
    }

    @Test fun forwardPortNeedsTheReadPermission() {
        val noRead = SavedConnection(name = "box", host = "10.0.0.2", user = "me", hostKey = "1A2B", allowMcpExec = true, allowMcpRead = false)
        val r = call(noRead, tool = "ssh.forward_port", args = buildJsonObject {
            put("connection", "box"); put("local_port", 8080); put("host", "127.0.0.1"); put("port", 22)
        })
        assertEquals("not_permitted", r.errorCode)
    }

    @Test fun closeForwardNeedsTheExecPermission() {
        val r = call(box(exec = false), tool = "ssh.close_forward", args = buildJsonObject {
            put("connection", "box"); put("local_port", 8080)
        })
        assertEquals("not_permitted", r.errorCode)
    }
}
