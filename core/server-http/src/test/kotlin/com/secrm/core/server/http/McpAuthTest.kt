package com.secrm.core.server.http

import org.junit.Assert.assertEquals
import org.junit.Test

class McpAuthTest {
    private val remoteTokens = setOf("remote-token")
    private val auth = McpAuth(7823, 7824, "local-token", setOf("*.ts.net", "example.org")) { it in remoteTokens }

    private fun check(port: Int, host: String?, origin: String? = null, authorization: String?) =
        auth.check(port, host, origin, authorization)

    private fun McpAuth.Decision.code() = when (this) {
        McpAuth.Decision.Allow -> 200
        is McpAuth.Decision.Deny -> status
    }

    @Test fun localPortAcceptsLocalTokenFromLoopback() {
        assertEquals(200, check(7823, "127.0.0.1:7823", authorization = "Bearer local-token").code())
        assertEquals(200, check(7823, "localhost", authorization = "Bearer local-token").code())
    }

    @Test fun localPortRejectsRemoteTokenAndWrongHost() {
        assertEquals(401, check(7823, "127.0.0.1", authorization = "Bearer remote-token").code())
        assertEquals(403, check(7823, "evil.example", authorization = "Bearer local-token").code())
    }

    @Test fun remotePortAcceptsStaticAndOAuthTokens() {
        assertEquals(200, check(7824, "box.tail1.ts.net", authorization = "Bearer remote-token").code())
    }

    @Test fun remotePortRejectsMissingOrUnknownTokens() {
        assertEquals(401, check(7824, "box.tail1.ts.net", authorization = null).code())
        assertEquals(401, check(7824, "box.tail1.ts.net", authorization = "Bearer nope").code())
        assertEquals(401, check(7824, "box.tail1.ts.net", authorization = "local-token").code())
    }

    @Test fun remoteHostMustMatchAllowlist() {
        assertEquals(403, check(7824, "ts.net", authorization = "Bearer remote-token").code())
        assertEquals(403, check(7824, "ts.net.evil.com", authorization = "Bearer remote-token").code())
        assertEquals(403, check(7824, "evilts.net", authorization = "Bearer remote-token").code())
        assertEquals(200, check(7824, "example.org:7824", authorization = "Bearer remote-token").code())
    }

    @Test fun originMustBeAllowedWhenPresent() {
        assertEquals(403, check(7824, "box.ts.net", origin = "https://evil.com", authorization = "Bearer remote-token").code())
        assertEquals(200, check(7824, "box.ts.net", origin = "https://app.box.ts.net", authorization = "Bearer remote-token").code())
        assertEquals(403, check(7824, "box.ts.net", origin = "null", authorization = "Bearer remote-token").code())
    }

    @Test fun unknownPortIsForbidden() {
        assertEquals(403, check(9999, "127.0.0.1", authorization = "Bearer local-token").code())
    }
}
