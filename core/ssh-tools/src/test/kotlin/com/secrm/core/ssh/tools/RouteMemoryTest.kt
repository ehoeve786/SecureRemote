package com.secrm.core.ssh.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteMemoryTest {
    /** Holds one record and counts saves, so a test can see whether a route was written. */
    private class Store(var stored: SavedConnection?) : SavedConnections {
        var saves = 0
        override fun list() = listOfNotNull(stored)
        override fun save(connection: SavedConnection) { stored = connection; saves++ }
        override fun find(name: String) = stored?.takeIf { it.name == name }
        override fun privateKeyFor(keyId: String): String? = null
        override fun keyPassphraseFor(keyId: String): String? = null
    }

    private fun host(via: String? = null, routes: Map<String, String> = emptyMap()) = SavedConnection(
        name = "router", host = "192.168.8.1", user = "root", hostKey = "KEY", via = via,
        fallbackVia = "wireguard", routeByNetwork = routes,
    )

    @Test fun aWorkingRouteIsRememberedForTheNetwork() {
        val store = Store(host())
        rememberRoute(host(), "192.168.8", "wireguard", store)
        assertEquals(mapOf("192.168.8" to "wireguard"), store.stored!!.routeByNetwork)
        assertEquals(1, store.saves)
    }

    @Test fun directIsStoredAsDirect() {
        val store = Store(host())
        rememberRoute(host(), "192.168.8", null, store)
        assertEquals(mapOf("192.168.8" to DIRECT), store.stored!!.routeByNetwork)
    }

    @Test fun otherNetworksKeepTheirRoutes() {
        val store = Store(host(routes = mapOf("10.0.0" to DIRECT)))
        rememberRoute(host(), "192.168.8", "wireguard", store)
        assertEquals(mapOf("10.0.0" to DIRECT, "192.168.8" to "wireguard"), store.stored!!.routeByNetwork)
    }

    @Test fun anUnchangedRouteIsNotWrittenAgain() {
        val store = Store(host(routes = mapOf("192.168.8" to "wireguard")))
        rememberRoute(host(), "192.168.8", "wireguard", store)
        assertEquals(0, store.saves)
    }

    @Test fun noNetworkMeansNothingIsRemembered() {
        val store = Store(host())
        rememberRoute(host(), null, "wireguard", store)
        assertEquals(0, store.saves)
    }

    @Test fun aFixedRouteIsNotRemembered() {
        val store = Store(host(via = "tailscale"))
        rememberRoute(host(via = "tailscale"), "192.168.8", "tailscale", store)
        assertEquals(0, store.saves)
    }

    @Test fun theLatestRecordIsUpdatedSoOtherChangesSurvive() {
        // A host key pinned during the connect is in the store but not in the copy passed in.
        val store = Store(host().copy(hostKey = "PINNED"))
        rememberRoute(host().copy(hostKey = null), "192.168.8", "wireguard", store)
        assertEquals("PINNED", store.stored!!.hostKey)
        assertTrue(store.stored!!.routeByNetwork.containsKey("192.168.8"))
    }
}
