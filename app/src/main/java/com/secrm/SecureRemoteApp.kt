package com.secrm

import android.app.Application
import com.secrm.core.domain.CallDispatcher
import com.secrm.core.domain.Session
import com.secrm.core.domain.ToolResult
import com.secrm.core.mcp.AuditEntry
import com.secrm.core.mcp.McpAuditLog
import com.secrm.core.security.EncryptedConfigStore
import com.secrm.core.ssh.SshClientManager
import com.secrm.core.ssh.tools.SavedConnections
import com.secrm.core.ssh.tools.SshTools
import com.secrm.core.tunnel.MockTunnel
import com.secrm.core.tunnel.Tunnel
import kotlinx.serialization.json.JsonObject

class SecureRemoteApp : Application() {
    lateinit var config: EncryptedConfigStore; private set
    lateinit var tunnel: Tunnel; private set
    lateinit var ssh: SshClientManager; private set
    lateinit var audit: McpAuditLog; private set
    lateinit var dispatcher: CallDispatcher; private set

    override fun onCreate() {
        super.onCreate()
        config = EncryptedConfigStore(this)
        tunnel = MockTunnel()
        ssh = SshClientManager()
        audit = McpAuditLog(this)
        dispatcher = CallDispatcher(object : CallDispatcher.AuditSink {
            override suspend fun record(s: Session, tool: String, args: JsonObject, result: ToolResult) {
                audit.record(AuditEntry(
                    ts = java.time.Instant.now().toString(),
                    tool = tool,
                    argsRedacted = args.toString(),
                    outcome = if (result.isError) "error" else "ok",
                    durationMs = 0
                ))
            }
        })
        SshTools.register(dispatcher, PrefsSaved(config), ssh)
    }
}

private class PrefsSaved(private val c: EncryptedConfigStore) : SavedConnections {
    override fun list(): List<SavedConnections.Connection> {
        val host = c.sshHost.orEmpty()
        if (host.isBlank()) return emptyList()
        return listOf(SavedConnections.Connection(
            name = host,
            host = host,
            port = (c.sshPort ?: "22").toIntOrNull() ?: 22,
            user = c.sshUser.orEmpty(),
            password = c.sshPassword.orEmpty()
        ))
    }
    override fun find(name: String) = list().firstOrNull { it.name == name }
}
