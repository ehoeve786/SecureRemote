package com.secrm.core.tunnel

import android.content.Context
import com.secrm.gonet.gen.srnet.Srnet
import com.secrm.gonet.gen.srnet.Tunnel as GoTunnel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Userspace WireGuard on its own network stack in the app, after PocketPilot's WireGuardProvider:
 * no VpnService, so the rest of the phone's traffic is untouched. The owner imports a wg-quick
 * config (e.g. exported by their router); SSH reaches the peers' AllowedIPs through it.
 *
 * [loadConfig]/[saveConfig] keep the config, which holds the private key, in encrypted storage.
 */
class WireGuardTunnel(
    context: Context,
    scope: CoroutineScope,
    private val loadConfig: () -> String?,
    private val saveConfig: (String?) -> Unit,
) : Tunnel(context, scope, "wireguard") {
    private val appContext = context.applicationContext
    @Volatile private var tunnel: GoTunnel? = null

    /** Cached so the UI does not decrypt the private key just to ask whether one is saved. */
    @Volatile private var hasConfig = false
    val configured get() = hasConfig

    /** The phone's public key, for the server's peer list, or null without a config. */
    @Volatile var publicKey: String? = null; private set

    init {
        // Deriving the key loads the Go library, so it stays off the thread that builds this.
        scope.launch(Dispatchers.IO) {
            loadConfig()?.let { c ->
                val key = runCatching { lib(); Srnet.publicKeyOfConfig(c) }.getOrNull()
                // Publish under the same lock as configure(), and only if no newer config arrived meanwhile.
                synchronized(this@WireGuardTunnel) {
                    if (loadConfig() == c) { hasConfig = true; publicKey = key }
                }
            }
        }
    }

    /** Checks and saves a wg-quick config; returns the problem in plain words, or null. Restarts a running tunnel. */
    @Synchronized
    fun configure(config: String): String? {
        val key = runCatching { lib(); Srnet.publicKeyOfConfig(config) }
        key.exceptionOrNull()?.let { return it.message ?: it.toString() }
        saveConfig(config)
        hasConfig = true
        publicKey = key.getOrNull()
        if (enabled) { stop(); start() }
        return null
    }

    /** Forgets the config and its private key. */
    @Synchronized
    fun forget() {
        stop()
        saveConfig(null)
        hasConfig = false
        publicKey = null
    }

    override fun startNative() {
        val config = loadConfig() ?: error("Import a WireGuard config first")
        lib()
        (tunnel ?: Srnet.newTunnel().also { tunnel = it }).start(config)
    }

    override fun stopNative() { tunnel?.stop() }
    override fun dial(host: String, port: Int, timeoutMs: Int) =
        (tunnel ?: error("WireGuard is not running")).dial(host, port.toLong(), timeoutMs.toLong())
    override fun status(): String? = tunnel?.status()
    override fun logs(): String = runCatching { tunnel?.logs() }.getOrNull().orEmpty()

    override fun stateOf(status: JSONObject): State {
        val error = status.optString("error").takeIf(String::isNotEmpty)
        return when {
            error != null -> State.Failed(error)
            !status.optBoolean("running") -> State.Idle
            // No handshake yet: the server has not answered.
            status.optLong("lastHandshake") == 0L -> State.Starting
            else -> status.optJSONArray("addresses")?.optString(0).orEmpty().let { State.Up(it, it) }
        }
    }

    /** Points the Go library at the app before its first call. */
    private fun lib() = go.Seq.setContext(appContext)
}
