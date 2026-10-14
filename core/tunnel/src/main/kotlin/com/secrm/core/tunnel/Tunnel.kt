package com.secrm.core.tunnel

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject

/** Loopback SOCKS5 proxy into a tunnel's private network. */
data class Socks(val port: Int, val user: String, val pass: String)

/**
 * A private network (Tailscale or WireGuard) running in-process through the Go library, after
 * PocketPilot's NetworkProvider. The SSH client reaches it through [socks].
 */
abstract class Tunnel(context: Context, private val scope: CoroutineScope, key: String) {
    sealed interface State {
        data object Idle : State
        data object Starting : State
        /** Waiting for the owner to sign in at [url]. */
        data class NeedsAuth(val url: String) : State
        data class Up(val address: String, val name: String) : State
        data class Failed(val reason: String, val details: String? = null) : State
    }

    protected val prefs = context.getSharedPreferences("tunnel_$key", Context.MODE_PRIVATE)
    protected val mutableState = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = mutableState.asStateFlow()

    /** Whether the owner turned it on; survives restarts so [resume] can bring it back. */
    val enabled get() = prefs.getBoolean("enabled", false)

    @Volatile var socks: Socks? = null; private set

    /** Native calls run one at a time, in order, so a quick stop-then-start cannot interleave. */
    protected val native = Dispatchers.IO.limitedParallelism(1)
    private var poller: Job? = null

    protected abstract fun startNative()
    protected abstract fun stopNative()
    protected abstract fun status(): String?
    protected abstract fun stateOf(status: JSONObject): State
    abstract fun logs(): String

    fun resume() { if (enabled) start() }

    @Synchronized
    fun start() {
        prefs.edit().putBoolean("enabled", true).apply()
        if (poller?.isActive == true) return
        mutableState.value = State.Starting
        poller = scope.launch(native) {
            try {
                startNative()
            } catch (e: Throwable) {
                // Errors too: a missing native library is an UnsatisfiedLinkError.
                mutableState.value = State.Failed(e.message ?: e.toString(), e.stackTraceToString())
                return@launch
            }
            while (isActive) {
                status()?.let { runCatching { JSONObject(it) }.getOrNull() }?.let { st ->
                    socks = st.optInt("socksPort").takeIf { it > 0 }
                        ?.let { Socks(it, st.optString("socksUser"), st.optString("socksPass")) }
                    mutableState.value = stateOf(st)
                }
                delay(if (state.value is State.Up) 5_000 else 1_000)
            }
        }
    }

    /** Stops but keeps the login or config. */
    @Synchronized
    fun stop() {
        prefs.edit().putBoolean("enabled", false).apply()
        poller?.cancel()
        poller = null
        socks = null
        scope.launch(native) { runCatching { stopNative() } }
        mutableState.value = State.Idle
    }
}
