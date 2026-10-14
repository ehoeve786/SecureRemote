package com.secrm

import android.app.Application
import com.secrm.core.domain.CallDispatcher
import com.secrm.core.domain.Session
import com.secrm.core.domain.ToolResult
import com.secrm.core.mcp.AuditEntry
import com.secrm.core.mcp.McpAuditLog
import com.secrm.core.security.EncryptedConfigStore
import com.secrm.core.ssh.SshClientManager
import com.secrm.core.ssh.tools.SshTools
import com.secrm.core.tunnel.TailscaleTunnel
import com.secrm.core.tunnel.WireGuardTunnel
import kotlinx.coroutines.MainScope
import com.secrm.data.BitwardenVault
import com.secrm.data.ConnectionRepository
import com.secrm.data.KeyRepository
import kotlinx.serialization.json.JsonObject

class SecureRemoteApp : Application() {
    lateinit var config: EncryptedConfigStore; private set
    lateinit var tailscale: TailscaleTunnel; private set
    lateinit var wireguard: WireGuardTunnel; private set
    lateinit var ssh: SshClientManager; private set
    lateinit var audit: McpAuditLog; private set
    lateinit var dispatcher: CallDispatcher; private set
    lateinit var connections: ConnectionRepository; private set
    lateinit var keys: KeyRepository; private set
    lateinit var vault: BitwardenVault; private set

    override fun onCreate() {
        super.onCreate()
        config = EncryptedConfigStore(this)
        val scope = MainScope()
        tailscale = TailscaleTunnel(this, scope, targetPort = 7824).apply { resume() }
        wireguard = WireGuardTunnel(this, scope, { config.get("wg.config") },
            { it?.let { c -> config.put("wg.config", c) } ?: config.remove("wg.config") }).apply { resume() }
        ssh = SshClientManager()
        // SSH goes through whichever tunnel is up; each sends only its own network's hosts through it.
        ssh.proxy = {
            (tailscale.socks ?: wireguard.socks)?.let {
                com.jcraft.jsch.ProxySOCKS5("127.0.0.1", it.port).apply { setUserPasswd(it.user, it.pass) }
            }
        }
        audit = McpAuditLog(this)
        keys = KeyRepository(config)
        vault = BitwardenVault(config)
        connections = ConnectionRepository(config, keys, vault)
        dispatcher = CallDispatcher(object : CallDispatcher.AuditSink {
            override suspend fun record(
                s: Session, tool: String, args: JsonObject, result: ToolResult
            ) {
                audit.record(AuditEntry(
                    ts = java.time.Instant.now().toString(),
                    tool = tool,
                    argsRedacted = args.toString(),
                    outcome = if (result.isError) "error" else "ok",
                    durationMs = 0
                ))
            }
        })
        SshTools.register(dispatcher, connections, ssh)
    }
}
