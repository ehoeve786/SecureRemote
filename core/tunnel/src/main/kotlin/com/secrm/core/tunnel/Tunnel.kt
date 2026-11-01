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

/**
 * A private network (Tailscale or WireGuard) running in-process through the Go library, after
 * PocketPilot's NetworkProvider. The SSH client reaches it through [dial].
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

    /** Native calls run one at a time, in order, so a quick stop-then-start cannot interleave. */
    protected val native = Dispatchers.IO.limitedParallelism(1)
    private var poller: Job? = null

    protected abstract fun startNative()
    protected abstract fun stopNative()
    protected abstract fun status(): String?
    protected abstract fun stateOf(status: JSONObject): State
    abstract fun logs(): String

    /** Opens a TCP connection through the tunnel (Tidegate's approach: no local proxy hop). */
    abstract fun dial(host: String, port: Int, timeoutMs: Int): com.secrm.gonet.gen.srnet.Conn

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
                // Errors too: a missing native library is an UnsatisfiedLinkError. Skipped after stop(), which cancels this.
                if (isActive) mutableState.value = State.Failed(e.message ?: e.toString(), e.stackTraceToString())
                return@launch
            }
            // Polls fast after a change and backs off while nothing changes, so an idle or waiting tunnel
            // does not wake the device every second.
            var wait = 1_000L
            while (isActive) {
                val next = status()?.let { runCatching { JSONObject(it) }.getOrNull() }?.let { stateOf(it) }
                if (next != null && next != mutableState.value) {
                    mutableState.value = next
                    wait = 1_000L
                } else {
                    wait = minOf(wait * 2, if (mutableState.value is State.Up) 5_000L else 30_000L)
                }
                delay(wait)
            }
        }
    }

    /**
     * Brings an enabled tunnel back up after the network under it changed: a tunnel can read as Up on a
     * network it no longer routes through. Does nothing if the owner turned the tunnel off.
     */
    @Synchronized
    fun restart() {
        if (!enabled) return
        poller?.cancel()
        poller = null
        // Queued on the same serial dispatcher as the start that follows, so the stop runs first.
        scope.launch(native) { runCatching { stopNative() } }
        start()
    }

    /** Stops but keeps the login or config. */
    @Synchronized
    fun stop() {
        prefs.edit().putBoolean("enabled", false).apply()
        poller?.cancel()
        poller = null
        scope.launch(native) { runCatching { stopNative() } }
        mutableState.value = State.Idle
    }
}
