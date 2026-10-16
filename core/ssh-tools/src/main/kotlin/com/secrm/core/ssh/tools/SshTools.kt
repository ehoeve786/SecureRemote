package com.secrm.core.ssh.tools

import com.secrm.core.domain.*
import com.secrm.core.ssh.SshAuth
import com.secrm.core.ssh.SshClientManager
import com.secrm.core.ssh.SshConfig
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*

object SshTools {

    fun register(dispatcher: CallDispatcher, saved: SavedConnections, ssh: SshClientManager) {

        dispatcher.register(
            ToolSpec(
                name = "ssh.list_connections",
                description = "List saved SSH connections exposed to MCP clients.",
                inputSchema = emptyObject(),
                annotations = ToolSpec.Annotations(readOnlyHint = true)
            ),
            object : ToolHandler {
                override suspend fun call(session: Session, args: JsonObject): ToolResult {
                    val visible = saved.list()
                        .filter { it.allowMcpExec || it.allowMcpRead }
                        .map {
                            buildJsonObject {
                                put("name", it.name)
                                put("host", it.host)
                                put("port", it.port)
                                put("user", it.user)
                                put("can_exec", it.allowMcpExec)
                                put("can_read", it.allowMcpRead)
                            }
                        }
                    return ToolResult.ok(
                        buildJsonObject { put("connections", JsonArray(visible)) }.toString()
                    )
                }
            }
        )

        dispatcher.register(
            ToolSpec(
                name = "ssh.exec",
                description = "Run a shell command on a saved connection. Returns stdout+stderr.",
                inputSchema = buildJsonObject {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("connection") { put("type", "string") }
                        putJsonObject("command") { put("type", "string") }
                        putJsonObject("timeout_ms") { put("type", "integer") }
                    }
                    putJsonArray("required") { add("connection"); add("command") }
                },
                annotations = ToolSpec.Annotations(destructiveHint = true, openWorldHint = true)
            ),
            object : ToolHandler {
                override suspend fun call(session: Session, args: JsonObject): ToolResult {
                    val name = args["connection"]?.jsonPrimitive?.contentOrNull
                        ?: return ToolResult.error("missing_argument", "Provide 'connection'.", "Missing connection")
                    val cmd = args["command"]?.jsonPrimitive?.contentOrNull
                        ?: return ToolResult.error("missing_argument", "Provide 'command'.", "Missing command")
                    // Clamped: a caller cannot hold a session open indefinitely.
                    val timeout = (args["timeout_ms"]?.jsonPrimitive?.longOrNull ?: 30_000L).coerceIn(1_000L, 300_000L)

                    val conn = saved.find(name)
                        ?: return ToolResult.error("unknown_connection",
                            "Call ssh.list_connections.", "No such connection: $name")
                    if (!conn.allowMcpExec) return ToolResult.error("not_permitted",
                        "Enable MCP exec for this connection in the app.",
                        "Connection '$name' is not exposed for exec.")

                    // The host key must already be trusted. Trusting a first-seen key is the owner's job, in the app.
                    if (conn.hostKey == null) return ToolResult.error("host_not_pinned",
                        "Connect to it once in the SecureRemote app to trust its host key.",
                        "Connection '$name' has no trusted host key yet.")

                    // Resolve auth from the connection.
                    val auth: SshAuth = when (conn.authMethod) {
                        AuthMethod.PASSWORD -> SshAuth.Password(conn.password)
                        AuthMethod.SAVED_KEY -> {
                            val keyId = conn.keyId
                                ?: return ToolResult.error("no_key",
                                    "Assign a key to this connection.",
                                    "Connection '$name' has no key assigned.")
                            val pem = saved.privateKeyFor(keyId)
                                ?: return ToolResult.error("missing_key",
                                    "Re-import the key in Settings → SSH Keys.",
                                    "Private key for '$keyId' not found.")
                            val pass = saved.keyPassphraseFor(keyId)?.takeIf { it.isNotBlank() }
                            SshAuth.Key(pem = pem, passphrase = pass)
                        }
                        AuthMethod.AGENT -> SshAuth.Key(
                            pem = conn.keyId?.let(saved::bitwardenKeyFor)
                                ?: return ToolResult.error("vault_locked",
                                    "Unlock Bitwarden in the SecureRemote app.",
                                    "Bitwarden key for '$name' is unavailable (vault locked)."),
                            passphrase = null)
                    }

                    // A separate client, so this neither replaces nor drops the app's live session.
                    val client = SshClientManager().apply { proxy = ssh.proxy }
                    if (!client.connectSaved(conn, auth, saved, allowRediscover = false)) return ToolResult.error("connect_failed",
                        "Check host reachability and credentials.",
                        "SSH connect failed to ${conn.host}:${conn.port}")

                    return try {
                        ToolResult.ok(withTimeout(timeout) { client.execOnce(cmd) })
                    } catch (t: Throwable) {
                        ToolResult.error("exec_failed", "Retry or shorten command.",
                            t.message ?: "exec failed")
                    } finally {
                        client.shutdown()
                    }
                }
            }
        )
    }

    private fun emptyObject() = buildJsonObject {
        put("type", "object"); putJsonObject("properties") {}
    }
}
