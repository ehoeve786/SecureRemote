package com.secrm.core.ssh.tools

import org.junit.Assert.assertEquals
import org.junit.Test

class RedactTest {
    @Test fun scrubsTheConnectionsOwnSecrets() {
        assertEquals("login [REDACTED] ok", Redact.text("login hunter2hunter2 ok", listOf("hunter2hunter2")))
    }

    @Test fun shortSecretsAreScrubbedToo() {
        assertEquals("[REDACTED]c", Redact.text("abc", listOf("ab")))
    }

    @Test fun emptySecretIsIgnored() {
        assertEquals("abc", Redact.text("abc", listOf("")))
    }

    @Test fun removesPemBlocks() {
        val pem = "x\n-----BEGIN OPENSSH PRIVATE KEY-----\nAAAA\n-----END OPENSSH PRIVATE KEY-----\ny"
        assertEquals("x\n[REDACTED]\ny", Redact.text(pem, emptyList()))
    }

    @Test fun removesAssignmentsAndBearerTokens() {
        assertEquals("password=[REDACTED] and token: [REDACTED]",
            Redact.text("password=s3cret and token: abc123", emptyList()))
        assertEquals("Authorization: Bearer [REDACTED]", Redact.text("Authorization: Bearer abc.def", emptyList()))
    }
}
