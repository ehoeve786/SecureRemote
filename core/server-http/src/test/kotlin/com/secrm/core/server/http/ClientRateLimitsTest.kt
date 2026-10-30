package com.secrm.core.server.http

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClientRateLimitsTest {
    @Test fun rotatingClientIdsCannotBypassTheOverallCeiling() {
        val limits = ClientRateLimits(max = 1)
        // The overall ceiling is four times the per-client limit. Each new id has its own bucket, so only the ceiling stops it.
        repeat(4) { assertTrue(limits.allow("client-$it")) }
        assertFalse("a fifth fresh client_id is refused", limits.allow("client-5"))
    }

    @Test fun oneClientIsLimitedToItsOwnBucket() {
        val limits = ClientRateLimits(max = 2)
        assertTrue(limits.allow("a"))
        assertTrue(limits.allow("a"))
        assertFalse(limits.allow("a"))
        assertTrue("another client is not slowed by it", limits.allow("b"))
    }
}
