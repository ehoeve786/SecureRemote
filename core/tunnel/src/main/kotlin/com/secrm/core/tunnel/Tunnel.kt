package com.secrm.core.tunnel

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

interface Tunnel {
    sealed interface State {
        data object Idle : State
        data object Starting : State
        data class Up(val socksPort: Int, val selfIp: String) : State
        data class Failed(val reason: String) : State
    }
    val state: StateFlow<State>
    suspend fun start(config: TunnelConfig): State
    suspend fun stop()
    fun socksPortOrNull(): Int?
    fun shutdown()
}

data class TunnelConfig(
    val authKey: String = "",
    val hostname: String = "android-device",
    val controlUrl: String = ""
)

/** Simulated tunnel for builds without the userspace Tailscale AAR. */
class MockTunnel(
    private val ip: String = "100.64.0.1",
    private val port: Int = 1055
) : Tunnel {
    private val _state = MutableStateFlow<Tunnel.State>(Tunnel.State.Idle)
    override val state: StateFlow<Tunnel.State> = _state.asStateFlow()
    override suspend fun start(config: TunnelConfig): Tunnel.State {
        _state.value = Tunnel.State.Starting
        delay(600)
        val up = Tunnel.State.Up(port, ip)
        _state.value = up
        return up
    }
    override suspend fun stop() { _state.value = Tunnel.State.Idle }
    override fun socksPortOrNull(): Int? = (_state.value as? Tunnel.State.Up)?.socksPort
    override fun shutdown() { _state.value = Tunnel.State.Idle }
}
