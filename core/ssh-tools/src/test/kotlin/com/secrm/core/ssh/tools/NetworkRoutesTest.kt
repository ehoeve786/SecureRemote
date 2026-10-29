package com.secrm.core.ssh.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkRoutesTest {
    @Test fun aFixedRouteIsTheOnlyOne() {
        assertEquals(listOf("tailscale"), orderRoutes("tailscale", null, onHostNetwork = false, remembered = null))
    }

    @Test fun offTheHostNetworkTheFallbackComesFirst() {
        assertEquals(listOf("wireguard", null), orderRoutes(null, "wireguard", onHostNetwork = false, remembered = null))
    }

    @Test fun onTheHostNetworkDirectComesFirst() {
        assertEquals(listOf(null, "wireguard"), orderRoutes(null, "wireguard", onHostNetwork = true, remembered = null))
    }

    @Test fun aRememberedRouteMovesToTheFront() {
        assertEquals(listOf("wireguard", null), orderRoutes(null, "wireguard", onHostNetwork = true, remembered = "wireguard"))
        assertEquals(listOf(null, "wireguard"), orderRoutes(null, "wireguard", onHostNetwork = false, remembered = DIRECT))
    }

    @Test fun aRememberedRouteNoLongerOfferedIsIgnored() {
        assertEquals(listOf(null, "wireguard"), orderRoutes(null, "wireguard", onHostNetwork = true, remembered = "tailscale"))
    }

    @Test fun withoutAFallbackOnlyDirectIsTried() {
        assertEquals(listOf<String?>(null), orderRoutes(null, null, onHostNetwork = true, remembered = null))
    }

    @Test fun privateAddressesAreRecognised() {
        assertTrue(isPrivateIpv4("192.168.8.1"))
        assertTrue(isPrivateIpv4("172.20.0.5"))
        assertFalse(isPrivateIpv4("8.8.8.8"))
        assertFalse(isPrivateIpv4("100.64.1.2"))
    }
}
