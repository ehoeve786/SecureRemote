package com.secrm.core.server.http

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest
import java.util.Base64

class McpOAuthTest {
    private val verifier = "verifier-with-enough-entropy-0123456789abcdef"
    private val challenge = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
    private val redirect = "https://claude.ai/api/mcp/auth_callback"

    private class Clock(var t: Long = 1_000_000L) { fun now() = t }

    private fun newOAuth(store: Array<String?> = arrayOfNulls(1), clock: Clock = Clock()) =
        McpOAuth({ store[0] }, { store[0] = it }, clock::now)

    /** Runs the pairing step and returns the authorization code, or null. */
    private fun pair(oauth: McpOAuth, code: String, ch: String = challenge) = oauth.authorize(code, ch, redirect)

    private fun exchange(oauth: McpOAuth, code: String, v: String = verifier) =
        oauth.token(mapOf("grant_type" to "authorization_code", "code" to code, "code_verifier" to v, "redirect_uri" to redirect))

    @Test fun fullPkceFlowIssuesUsableTokens() {
        val oauth = newOAuth()
        val pairing = oauth.newPairingCode()
        val code = requireNotNull(pair(oauth, pairing))
        val (access, refresh) = requireNotNull(exchange(oauth, code))
        assertTrue(oauth.isValid(access))
        assertFalse("refresh token must not work as an access token", oauth.isValid(refresh))
    }

    @Test fun pairingCodeIsSingleUse() {
        val oauth = newOAuth()
        val pairing = oauth.newPairingCode()
        requireNotNull(pair(oauth, pairing))
        assertNull(pair(oauth, pairing))
    }

    @Test fun pairingLocksAfterFiveWrongGuesses() {
        val oauth = newOAuth()
        val pairing = oauth.newPairingCode()
        repeat(5) { assertNull(pair(oauth, "WRONG")) }
        assertNull("correct code must fail once locked", pair(oauth, pairing))
    }

    @Test fun pairingCodeIsCaseAndWhitespaceTolerant() {
        val oauth = newOAuth()
        val pairing = oauth.newPairingCode()
        requireNotNull(pair(oauth, "  ${pairing.lowercase()} "))
    }

    @Test fun pairingCodeExpiresAfterTenMinutes() {
        val clock = Clock()
        val oauth = newOAuth(clock = clock)
        val pairing = oauth.newPairingCode()
        clock.t += 10 * 60_000L + 1
        assertNull(pair(oauth, pairing))
    }

    @Test fun authorizationCodeExpiresAfterAMinute() {
        val clock = Clock()
        val oauth = newOAuth(clock = clock)
        val code = requireNotNull(pair(oauth, oauth.newPairingCode()))
        clock.t += 60_001L
        assertNull(exchange(oauth, code))
    }

    @Test fun wrongVerifierFailsAndBurnsTheCode() {
        val oauth = newOAuth()
        val code = requireNotNull(pair(oauth, oauth.newPairingCode()))
        assertNull(exchange(oauth, code, v = "not-the-verifier"))
        assertNull(exchange(oauth, code))
    }

    @Test fun redirectMismatchIsRejected() {
        val oauth = newOAuth()
        val code = requireNotNull(pair(oauth, oauth.newPairingCode()))
        assertNull(oauth.token(mapOf("grant_type" to "authorization_code", "code" to code,
            "code_verifier" to verifier, "redirect_uri" to "https://claude.com/other")))
    }

    @Test fun refreshTokenRotatesAndOldOneStopsWorking() {
        val oauth = newOAuth()
        val (_, refresh1) = requireNotNull(exchange(oauth, requireNotNull(pair(oauth, oauth.newPairingCode()))))
        val (access2, refresh2) = requireNotNull(oauth.token(mapOf("grant_type" to "refresh_token", "refresh_token" to refresh1)))
        assertTrue(oauth.isValid(access2))
        assertNull("a refresh token is single-use", oauth.token(mapOf("grant_type" to "refresh_token", "refresh_token" to refresh1)))
        requireNotNull(oauth.token(mapOf("grant_type" to "refresh_token", "refresh_token" to refresh2)))
    }

    @Test fun presentingAccessTokenAsRefreshDoesNotRevokeIt() {
        val oauth = newOAuth()
        val (access, _) = requireNotNull(exchange(oauth, requireNotNull(pair(oauth, oauth.newPairingCode()))))
        assertNull(oauth.token(mapOf("grant_type" to "refresh_token", "refresh_token" to access)))
        assertTrue("access token must survive a misused refresh request", oauth.isValid(access))
    }

    @Test fun accessTokenExpiresAfterAnHour() {
        val clock = Clock()
        val oauth = newOAuth(clock = clock)
        val (access, _) = requireNotNull(exchange(oauth, requireNotNull(pair(oauth, oauth.newPairingCode()))))
        clock.t += 60 * 60_000L + 1
        assertFalse(oauth.isValid(access))
    }

    @Test fun unknownGrantAndMissingParamsAreRejected() {
        val oauth = newOAuth()
        assertNull(oauth.token(mapOf("grant_type" to "password")))
        assertNull(oauth.token(mapOf("grant_type" to "refresh_token")))
        assertNull(oauth.token(emptyMap()))
    }

    @Test fun tokensPersistAsHashesOnly() {
        val store = arrayOfNulls<String>(1)
        val oauth = newOAuth(store)
        val (access, refresh) = requireNotNull(exchange(oauth, requireNotNull(pair(oauth, oauth.newPairingCode()))))
        val saved = requireNotNull(store[0])
        assertFalse("raw access token must not be stored", saved.contains(access))
        assertFalse("raw refresh token must not be stored", saved.contains(refresh))

        val reloaded = McpOAuth({ saved }, { store[0] = it })
        assertTrue("tokens survive a restart", reloaded.isValid(access))
    }

    @Test fun revokeAllDisconnectsEveryClient() {
        val store = arrayOfNulls<String>(1)
        val oauth = newOAuth(store)
        val (access, _) = requireNotNull(exchange(oauth, requireNotNull(pair(oauth, oauth.newPairingCode()))))
        oauth.revokeAll()
        assertFalse(oauth.isValid(access))
        assertEquals("", store[0])
    }

    @Test fun onlyClaudeRedirectsAreAllowed() {
        assertTrue(McpOAuth.allowedRedirect("https://claude.ai/api/mcp/auth_callback"))
        assertTrue(McpOAuth.allowedRedirect("https://claude.com/api/mcp/auth_callback"))
        assertFalse(McpOAuth.allowedRedirect("http://claude.ai/callback"))
        assertFalse(McpOAuth.allowedRedirect("https://claude.ai.evil.com/callback"))
        assertFalse(McpOAuth.allowedRedirect("https://evil.com/?https://claude.ai"))
        assertFalse(McpOAuth.allowedRedirect(null))
        assertFalse(McpOAuth.allowedRedirect("not a uri"))
    }
}
