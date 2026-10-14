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
import com.secrm.core.tunnel.Tunnel
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
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
    lateinit var oauth: com.secrm.core.server.http.McpOAuth; private set

    override fun onCreate() {
        super.onCreate()
        config = EncryptedConfigStore(this)
        val scope = MainScope()
        tailscale = TailscaleTunnel(this, scope, targetPort = 7824).apply { resume() }
        wireguard = WireGuardTunnel(this, scope, { config.get("wg.config") },
            { it?.let { c -> config.put("wg.config", c) } ?: config.remove("wg.config") }).apply { resume() }
        ssh = SshClientManager()
        // A connection routed through a private network starts it if needed and waits (up to 30 s) for it.
        ssh.proxy = { via ->
            val (t, name) = if (via == "wireguard") wireguard to "WireGuard" else tailscale to "Tailscale"
            t.start() // no-op if already running
            val s = runBlocking { withTimeoutOrNull(30_000) { t.state.first { it is Tunnel.State.Up }; t.socks } }
                ?: error("$name isn't connected. Check Settings → Private network.")
            com.jcraft.jsch.ProxySOCKS5("127.0.0.1", s.port).apply { setUserPasswd(s.user, s.pass) }
        }
        audit = McpAuditLog(this)
        keys = KeyRepository(config)
        vault = BitwardenVault(config)
        oauth = com.secrm.core.server.http.McpOAuth({ config.get("mcp.oauth") }, { config.put("mcp.oauth", it) })
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
