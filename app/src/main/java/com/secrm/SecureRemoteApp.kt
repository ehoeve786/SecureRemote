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
import com.secrm.data.ConnectionRepository
import com.secrm.data.KeyRepository
import com.secrm.data.AgentTokens
import com.secrm.data.McpPrefs
import com.secrm.service.McpServerService
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class SecureRemoteApp : Application() {
    lateinit var config: EncryptedConfigStore; private set
    lateinit var tailscale: TailscaleTunnel; private set
    lateinit var wireguard: WireGuardTunnel; private set
    lateinit var audit: McpAuditLog; private set
    lateinit var dispatcher: CallDispatcher; private set
    lateinit var connections: ConnectionRepository; private set
    lateinit var keys: KeyRepository; private set
    lateinit var oauth: com.secrm.core.server.http.McpOAuth; private set
    lateinit var ui: com.secrm.data.UiPrefs; private set
    lateinit var mcp: McpPrefs; private set
    lateinit var agents: AgentTokens; private set
    /** The connected hosts, each with its own shell and file browser. */
    val sessions by lazy { HostSessions(this) }

    /** A new SSH client that goes through the private network when a connection asks for it. */
    fun newClient(): SshClientManager = SshClientManager().apply {
        // A connection routed through a private network starts it if needed and waits (up to 30 s) for it.
        proxy = { via ->
            val (t, name) = if (via == "wireguard") wireguard to "WireGuard" else tailscale to "Tailscale"
            t.start() // no-op if already running
            runBlocking { withTimeoutOrNull(30_000) { t.state.first { it is Tunnel.State.Up } } }
                ?: error("$name isn't connected. Check Settings → Private network.")
            com.secrm.data.TunnelProxy { host, port -> t.dial(host, port, 30_000) }
        }
    }

    override fun onCreate() {
        super.onCreate()
        config = EncryptedConfigStore(this)
        ui = com.secrm.data.UiPrefs(this)
        mcp = McpPrefs(this)
        agents = AgentTokens(config)
        // Bitwarden was removed: drop its stored login and wrapped key.
        listOf("bw.server", "bw.client_id", "bw.client_secret", "bw.user_key", "bw.device").forEach { config.remove(it) }
        runCatching { java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry("secrm_bitwarden") }
        val scope = MainScope()
        tailscale = TailscaleTunnel(this, scope, targetPort = 7824).apply { resume() }
        wireguard = WireGuardTunnel(this, scope, { config.get("wg.config") },
            { it?.let { c -> config.put("wg.config", c) } ?: config.remove("wg.config") }).apply { resume() }
        audit = McpAuditLog(this)
        keys = KeyRepository(config)
        oauth = com.secrm.core.server.http.McpOAuth({ config.get("mcp.oauth") }, { config.put("mcp.oauth", it) })
        connections = ConnectionRepository(config, keys)
        dispatcher = CallDispatcher(object : CallDispatcher.AuditSink {
            override suspend fun record(
                s: Session, tool: String, args: JsonObject, result: ToolResult, durationNanos: Long
            ) {
                audit.record(AuditEntry(
                    ts = java.time.Instant.now().toString(),
                    tool = tool,
                    argsRedacted = redacted(args).toString(),
                    outcome = if (result.isError) "error" else "ok",
                    durationMs = durationNanos / 1_000_000,
                    client = s.clientLabel,
                ))
                McpServerService.noteActivity()
            }
        })
        SshTools.register(dispatcher, connections, newClient())
    }

    /** Commands and secrets never reach the audit log; their length does, so the entry is still useful. */
    private fun redacted(args: JsonObject) = JsonObject(args.mapValues { (k, v) ->
        if (k in SECRET_ARGS) JsonPrimitive("[redacted ${v.toString().length} chars]") else v
    })

    private companion object {
        val SECRET_ARGS = setOf("command", "password", "secret", "token", "passphrase")
    }
}
