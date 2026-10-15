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
    lateinit var ui: com.secrm.data.UiPrefs; private set
    /** File browser state for the connected host; survives tab switches. */
    val files by lazy { com.secrm.ui.sftp.SftpController(this, ssh) }

    private var bridge: com.secrm.core.terminal.TerminalBridge? = null
    private var bridgeGeneration = -1

    /**
     * The terminal for the live SSH session, or null when not connected. One per session, so the
     * shell and its scrollback survive switching tabs; a new connection gets a new one.
     */
    @Synchronized
    fun terminal(): com.secrm.core.terminal.TerminalBridge? {
        if (ssh.state.value !is com.secrm.core.ssh.SshState.Connected) return null
        if (bridge == null || bridgeGeneration != ssh.generation) {
            bridge = com.secrm.core.terminal.TerminalBridge(ssh); bridgeGeneration = ssh.generation
        }
        return bridge
    }

    override fun onCreate() {
        super.onCreate()
        config = EncryptedConfigStore(this)
        ui = com.secrm.data.UiPrefs(this)
        val scope = MainScope()
        tailscale = TailscaleTunnel(this, scope, targetPort = 7824).apply { resume() }
        wireguard = WireGuardTunnel(this, scope, { config.get("wg.config") },
            { it?.let { c -> config.put("wg.config", c) } ?: config.remove("wg.config") }).apply { resume() }
        ssh = SshClientManager()
        // A connection routed through a private network starts it if needed and waits (up to 30 s) for it.
        ssh.proxy = { via ->
            val (t, name) = if (via == "wireguard") wireguard to "WireGuard" else tailscale to "Tailscale"
            t.start() // no-op if already running
            runBlocking { withTimeoutOrNull(30_000) { t.state.first { it is Tunnel.State.Up } } }
                ?: error("$name isn't connected. Check Settings → Private network.")
            com.secrm.data.TunnelProxy { host, port -> t.dial(host, port, 30_000) }
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
