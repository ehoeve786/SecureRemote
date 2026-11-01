package com.secrm.core.server.http

import com.secrm.core.domain.CallDispatcher
import com.secrm.core.domain.Session
import com.secrm.core.domain.ToolHandler
import com.secrm.core.domain.ToolResult
import com.secrm.core.domain.ToolSpec
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.Base64

/**
 * Drives the real Ktor server over sockets, so the Host, Origin and Authorization checks, the OAuth
 * routes and the MCP handler are all exercised together, as claude.ai and the tunnel would use them.
 */
class McpServerIntegrationTest {
    private val remoteHost = "box.tail1.ts.net"
    private val claudeRedirect = "https://claude.ai/api/mcp/auth_callback"
    private lateinit var oauth: McpOAuth
    private lateinit var server: McpHttpServer
    private var localPort = 0
    private var remotePort = 0

    private class Resp(val status: Int, val headers: Map<String, String>, val body: String) {
        val json get() = Json.parseToJsonElement(body).jsonObject
    }

    private fun send(
        port: Int, method: String, path: String, host: String,
        headers: Map<String, String> = emptyMap(), body: String = "",
    ): Resp = Socket("127.0.0.1", port).use { s ->
        s.soTimeout = 5_000
        val req = buildString {
            append("$method $path HTTP/1.1\r\nHost: $host\r\nConnection: close\r\n")
            headers.forEach { (k, v) -> append("$k: $v\r\n") }
            append("Content-Length: ${body.toByteArray().size}\r\n\r\n")
            append(body)
        }
        s.getOutputStream().write(req.toByteArray())
        s.getOutputStream().flush()
        val raw = s.getInputStream().readBytes().toString(Charsets.UTF_8)
        val lines = raw.substringBefore("\r\n\r\n").split("\r\n")
        Resp(
            status = lines[0].split(" ")[1].toInt(),
            headers = lines.drop(1).associate { it.substringBefore(':').lowercase() to it.substringAfter(':').trim() },
            body = raw.substringAfter("\r\n\r\n", ""),
        )
    }

    private fun mcp(port: Int, host: String, token: String?, payload: String) = send(
        port, "POST", "/mcp", host,
        headers = buildMap {
            put("Content-Type", "application/json")
            if (token != null) put("Authorization", "Bearer $token")
        },
        body = payload,
    )

    private fun form(vararg pairs: Pair<String, String>) =
        pairs.joinToString("&") { (k, v) -> "$k=${java.net.URLEncoder.encode(v, "UTF-8")}" }

    @Before fun setUp() {
        localPort = freePort()
        remotePort = freePort()
        oauth = McpOAuth({ null }, {})
        val dispatcher = CallDispatcher().apply {
            register(
                ToolSpec("echo", "Echoes its msg argument", buildJsonObject { put("type", "object") }),
                object : ToolHandler {
                    override suspend fun call(session: Session, args: JsonObject) =
                        ToolResult.ok("pong:" + (args["msg"] as? JsonPrimitive)?.content)
                },
            )
        }
        val auth = McpAuth(localPort, remotePort, "local-token", { setOf("*.ts.net") }) {
            it == "static-remote" || oauth.isValid(it)
        }
        server = McpHttpServer(dispatcher, auth, oauth, localPort, remotePort)
        server.start()
        awaitListening(localPort); awaitListening(remotePort)
    }

    @After fun tearDown() = server.stop()

    @Test fun unauthenticatedRemoteRequestIsChallengedWithResourceMetadata() {
        val r = mcp(remotePort, remoteHost, token = null, payload = """{"jsonrpc":"2.0","id":1,"method":"ping"}""")
        assertEquals(401, r.status)
        assertEquals(
            "Bearer resource_metadata=\"https://$remoteHost/.well-known/oauth-protected-resource\"",
            r.headers["www-authenticate"],
        )
    }

    @Test fun localTokenWorksOnLoopbackAndNotRemotely() {
        val ok = mcp(localPort, "127.0.0.1:$localPort", "local-token", """{"jsonrpc":"2.0","id":1,"method":"initialize"}""")
        assertEquals(200, ok.status)
        assertTrue(ok.body.contains("protocolVersion"))

        val remoteWithLocal = mcp(remotePort, remoteHost, "local-token", """{"jsonrpc":"2.0","id":1,"method":"ping"}""")
        assertEquals(401, remoteWithLocal.status)
    }

    @Test fun staticRemoteBearerCallsTools() {
        val r = mcp(remotePort, remoteHost, "static-remote",
            """{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"echo","arguments":{"msg":"hi"}}}""")
        assertEquals(200, r.status)
        assertTrue(r.body, r.body.contains("pong:hi"))
    }

    @Test fun badHostAndOriginAreForbiddenEvenWithAValidToken() {
        assertEquals(403, mcp(remotePort, "evil.example", "static-remote", """{"jsonrpc":"2.0","id":1,"method":"ping"}""").status)
        val r = send(remotePort, "POST", "/mcp", remoteHost,
            headers = mapOf("Authorization" to "Bearer static-remote", "Origin" to "https://evil.com"),
            body = """{"jsonrpc":"2.0","id":1,"method":"ping"}""")
        assertEquals(403, r.status)
    }

    @Test fun notificationsGetAcceptedWithEmptyBody() {
        val r = mcp(remotePort, remoteHost, "static-remote", """{"jsonrpc":"2.0","method":"notifications/initialized"}""")
        assertEquals(202, r.status)
        assertEquals("", r.body)
    }

    @Test fun getOnMcpIsNotAllowed() {
        assertEquals(405, send(remotePort, "GET", "/mcp", remoteHost).status)
    }

    @Test fun malformedToolCallReturnsJsonRpcErrorNotServerError() {
        val r = mcp(remotePort, remoteHost, "static-remote",
            """{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":{"nested":true}}}""")
        assertEquals(200, r.status)
        assertEquals(-32602, r.json["error"]!!.jsonObject["code"]!!.jsonPrimitive.int)
    }

    @Test fun protectedResourceMetadataUsesThePublicOrigin() {
        val r = send(remotePort, "GET", "/.well-known/oauth-protected-resource", remoteHost)
        assertEquals("https://$remoteHost/mcp", r.json["resource"]!!.jsonPrimitive.content)
        assertEquals("https://$remoteHost", r.json["authorization_servers"]!!.jsonArray[0].jsonPrimitive.content)
    }

    @Test fun authorizationServerMetadataAdvertisesPkceS256Only() {
        val r = send(remotePort, "GET", "/.well-known/oauth-authorization-server", remoteHost)
        assertEquals("https://$remoteHost/token", r.json["token_endpoint"]!!.jsonPrimitive.content)
        assertEquals(listOf("S256"), r.json["code_challenge_methods_supported"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    @Test fun registrationAcceptsOnlyClaudeRedirects() {
        val ok = send(remotePort, "POST", "/register", remoteHost, body =
            """{"redirect_uris":["$claudeRedirect"]}""")
        assertEquals(201, ok.status)
        requireNotNull(ok.json["client_id"])

        val bad = send(remotePort, "POST", "/register", remoteHost, body =
            """{"redirect_uris":["https://evil.com/cb"]}""")
        assertEquals(400, bad.status)
        assertTrue(bad.body.contains("invalid_redirect_uri"))
    }

    @Test fun fullOAuthFlowThenToolCallAndRefresh() {
        val verifier = "a-verifier-long-enough-for-pkce-0123456789abc"
        val challenge = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))

        // claude.ai shows the consent page...
        val page = send(remotePort, "GET", "/authorize", remoteHost) // no params: must be refused
        assertEquals(400, page.status)
        val consent = send(remotePort, "GET", "/authorize", remoteHost).let {
            val q = "response_type=code&redirect_uri=${java.net.URLEncoder.encode(claudeRedirect, "UTF-8")}" +
                "&code_challenge=$challenge&code_challenge_method=S256&state=xyz"
            send(remotePort, "GET", "/authorize?$q", remoteHost)
        }
        assertEquals(200, consent.status)
        assertTrue(consent.body.contains("Pairing code"))

        // ...the user types the code shown in the app...
        val pairing = oauth.newPairingCode()
        val approve = send(remotePort, "POST", "/authorize", remoteHost,
            headers = mapOf("Content-Type" to "application/x-www-form-urlencoded"),
            body = form("redirect_uri" to claudeRedirect, "state" to "xyz", "code_challenge" to challenge, "pairing" to pairing))
        assertEquals(302, approve.status)
        val location = requireNotNull(approve.headers["location"])
        assertTrue(location, location.startsWith("$claudeRedirect?code="))
        assertTrue(location, location.endsWith("&state=xyz"))
        val code = location.substringAfter("code=").substringBefore('&')

        // ...and claude.ai exchanges the code with the PKCE verifier.
        val token = send(remotePort, "POST", "/token", remoteHost,
            headers = mapOf("Content-Type" to "application/x-www-form-urlencoded"),
            body = form("grant_type" to "authorization_code", "code" to code, "code_verifier" to verifier, "redirect_uri" to claudeRedirect))
        assertEquals(200, token.status)
        val access = token.json["access_token"]!!.jsonPrimitive.content
        val refresh = token.json["refresh_token"]!!.jsonPrimitive.content
        assertEquals("Bearer", token.json["token_type"]!!.jsonPrimitive.content)

        val listed = mcp(remotePort, remoteHost, access, """{"jsonrpc":"2.0","id":1,"method":"tools/list"}""")
        assertTrue(listed.body, listed.body.contains("\"echo\""))

        // The code is single-use.
        val replay = send(remotePort, "POST", "/token", remoteHost,
            headers = mapOf("Content-Type" to "application/x-www-form-urlencoded"),
            body = form("grant_type" to "authorization_code", "code" to code, "code_verifier" to verifier, "redirect_uri" to claudeRedirect))
        assertEquals(400, replay.status)

        // Refresh rotates: the new access token works and the old refresh token is spent.
        val refreshed = send(remotePort, "POST", "/token", remoteHost,
            headers = mapOf("Content-Type" to "application/x-www-form-urlencoded"),
            body = form("grant_type" to "refresh_token", "refresh_token" to refresh))
        assertEquals(200, refreshed.status)
        val access2 = refreshed.json["access_token"]!!.jsonPrimitive.content
        assertEquals(200, mcp(remotePort, remoteHost, access2, """{"jsonrpc":"2.0","id":1,"method":"ping"}""").status)
        // A retry right after the refresh is inside the grace window, so it gets the next pair. Reuse after the window is covered in McpOAuthTest.
        val retry = send(remotePort, "POST", "/token", remoteHost,
            headers = mapOf("Content-Type" to "application/x-www-form-urlencoded"),
            body = form("grant_type" to "refresh_token", "refresh_token" to refresh))
        assertEquals(200, retry.status)
    }

    @Test fun wrongPairingCodeShowsErrorAndIssuesNoCode() {
        oauth.newPairingCode()
        val r = send(remotePort, "POST", "/authorize", remoteHost,
            headers = mapOf("Content-Type" to "application/x-www-form-urlencoded"),
            body = form("redirect_uri" to claudeRedirect, "state" to "s", "code_challenge" to "c", "pairing" to "WRONG1"))
        assertEquals(200, r.status)
        assertTrue(r.body.contains("wrong or expired"))
        assertEquals(null, r.headers["location"])
    }

    @Test fun oauthAuthorizeRejectsNonClaudeRedirect() {
        val r = send(remotePort, "POST", "/authorize", remoteHost,
            headers = mapOf("Content-Type" to "application/x-www-form-urlencoded"),
            body = form("redirect_uri" to "https://evil.com/cb", "pairing" to "X", "code_challenge" to "c"))
        assertEquals(400, r.status)
    }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private fun awaitListening(port: Int) {
        val deadline = System.currentTimeMillis() + 5_000
        while (true) {
            try { Socket("127.0.0.1", port).close(); return } catch (e: Exception) {
                if (System.currentTimeMillis() > deadline) throw e
                Thread.sleep(50)
            }
        }
    }

    @Test fun oversizedOAuthBodyIsRefusedBeforeBeingBuffered() {
        val big = "x".repeat(200 * 1024)
        val r = send(remotePort, "POST", "/token", remoteHost,
            headers = mapOf("Content-Type" to "application/x-www-form-urlencoded"), body = big)
        assertEquals(413, r.status)
    }

    @Test fun authorizeWithoutPkceChallengeIsRefused() {
        val pairing = oauth.newPairingCode()
        val r = send(remotePort, "POST", "/authorize", remoteHost,
            headers = mapOf("Content-Type" to "application/x-www-form-urlencoded"),
            body = form("redirect_uri" to claudeRedirect, "pairing" to pairing))
        assertEquals(400, r.status)
    }
}
