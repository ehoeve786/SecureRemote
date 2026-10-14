package com.secrm.core.tunnel

import android.content.Context
import android.os.Build
import com.secrm.gonet.gen.srnet.Node
import com.secrm.gonet.gen.srnet.Srnet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File

/**
 * Tailscale through an embedded tsnet node, after PocketPilot's TailscaleProvider. The node joins
 * the owner's tailnet as `secureremote-<model>` (sign-in by link, no auth key), gives SSH a route to
 * tailnet hosts and subnet routes, and serves the MCP server's remote port at https://<node>/mcp.
 * With [public] on, that URL is published through Tailscale Funnel.
 */
class TailscaleTunnel(
    context: Context,
    private val scope: CoroutineScope,
    private val targetPort: Int,
) : Tunnel(context, scope, "tailscale") {
    private val appContext = context.applicationContext
    private val stateDir = File(context.noBackupFilesDir, "tailscale")
    @Volatile private var node: Node? = null

    var public: Boolean
        get() = prefs.getBoolean("public", false)
        set(on) {
            prefs.edit().putBoolean("public", on).apply()
            scope.launch(native) { runCatching { node?.setPublic(on) } }
        }

    /** Set when public is on but Funnel cannot work yet, in the tailnet's own words. */
    @Volatile var funnelProblem: String? = null; private set

    init { previousCrash()?.let { mutableState.value = it } }

    override fun startNative() {
        val n = node ?: run {
            go.Seq.setContext(appContext)
            Srnet.newNode(stateDir.absolutePath, hostname(), targetPort.toLong()).also { node = it }
        }
        n.setPublic(public)
        n.start("")
    }

    override fun stopNative() { node?.stop() }
    override fun status(): String? = node?.status()
    override fun logs(): String = runCatching { node?.logs() }.getOrNull().orEmpty()

    /** Signs out of the tailnet; the next start needs a new sign-in. */
    fun logout() {
        scope.launch(native) { runCatching { node?.logout() } }
        stop()
    }

    override fun stateOf(status: JSONObject): State {
        val backend = status.optString("backendState")
        val dnsName = status.optString("dnsName")
        val error = status.optString("error").takeIf(String::isNotEmpty)
        funnelProblem = status.optString("funnelProblem").takeIf { public && it.isNotEmpty() }
        return when {
            !status.optBoolean("running") -> State.Idle
            backend == "NeedsLogin" && status.optString("authUrl").isNotEmpty() -> State.NeedsAuth(status.optString("authUrl"))
            backend == "NeedsMachineAuth" -> State.Failed("Approve this phone in the Tailscale admin console, under Machines")
            backend == "Running" && dnsName.isNotEmpty() -> State.Up(status.optJSONArray("ips")?.optString(0).orEmpty(), dnsName)
            error != null -> State.Failed(error)
            else -> State.Starting
        }
    }

    /**
     * If the Go runtime died last run it left a report. Shows it once and leaves Tailscale off, so
     * [resume] does not crash the app on every launch.
     */
    private fun previousCrash(): State? {
        val crash = runCatching { File(stateDir, "crash.txt").readText() }.getOrNull()
        if (crash.isNullOrBlank()) return null
        val log = runCatching { File(stateDir, "node.log").readLines().takeLast(80) }.getOrDefault(emptyList())
        runCatching { File(stateDir, "crash.txt").writeText("") }
        prefs.edit().putBoolean("enabled", false).apply()
        return State.Failed("Tailscale stopped unexpectedly last time", crash.trim() + "\n\n--- Tailscale log ---\n" + log.joinToString("\n"))
    }

    /** `secureremote-sm-s918w`: Tailscale host names allow letters, digits and hyphens. */
    private fun hostname() = "secureremote-" + Build.MODEL.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(40)
}
