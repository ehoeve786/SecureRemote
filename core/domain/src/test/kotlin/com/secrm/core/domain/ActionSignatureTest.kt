package com.secrm.core.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionSignatureTest {
    private val key = ByteArray(32) { it.toByte() }

    @Test fun verifiesOnlyTheSignedFields() {
        val sig = ActionSignature.sign(key, "host", "uptime", "true", "false")
        assertTrue(ActionSignature.verify(key, sig, "host", "uptime", "true", "false"))
        assertFalse(ActionSignature.verify(key, sig, "host", "rm -rf /", "true", "false"))
        assertFalse(ActionSignature.verify(key, sig, "host", "uptime", "false", "false"))
    }

    @Test fun rejectsAnotherKey() {
        val sig = ActionSignature.sign(key, "host", "uptime")
        assertFalse(ActionSignature.verify(ByteArray(32) { 9 }, sig, "host", "uptime"))
    }

    @Test fun rejectsMissingOrEmptySignature() {
        assertFalse(ActionSignature.verify(key, "", "host", "uptime"))
    }

    @Test fun fieldBoundariesAreNotAmbiguous() {
        assertNotEquals(
            ActionSignature.sign(key, "ab", "c"),
            ActionSignature.sign(key, "a", "bc"),
        )
    }

    @Test fun signatureIsStableHex() {
        val sig = ActionSignature.sign(key, "x")
        assertEquals(64, sig.length)
        assertEquals(sig, ActionSignature.sign(key, "x"))
    }
}
