package com.secrm.core.server.http

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RateLimitTest {
    @Test fun allowsMaxCallsPerWindowThenRefuses() {
        var t = 0L
        val limit = RateLimit(max = 3, windowMs = 1_000) { t }
        assertTrue(limit.allow()); assertTrue(limit.allow()); assertTrue(limit.allow())
        assertFalse(limit.allow())
        t = 999
        assertFalse("still inside the window", limit.allow())
    }

    @Test fun windowResetsAfterItElapses() {
        var t = 0L
        val limit = RateLimit(max = 1, windowMs = 1_000) { t }
        assertTrue(limit.allow())
        assertFalse(limit.allow())
        t = 1_000
        assertTrue(limit.allow())
        assertEquals(false, limit.allow())
    }
}
