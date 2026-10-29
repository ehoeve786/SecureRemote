package com.secrm.core.server.http

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.readAvailable
import kotlinx.serialization.json.*
import java.io.ByteArrayOutputStream
import java.net.URLEncoder

/** The public origin a request came in on, e.g. https://secureremote-x.tail1234.ts.net. */
internal fun ApplicationCall.base() = "https://" + request.headers[HttpHeaders.Host].orEmpty()

private suspend fun ApplicationCall.json(status: HttpStatusCode = HttpStatusCode.OK, body: JsonObjectBuilder.() -> Unit) =
    respondText(buildJsonObject(body).toString(), ContentType.Application.Json, status)

private fun esc(s: String?) = s.orEmpty().replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;")

private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

/** Largest OAuth request body. Every legitimate request here is a few hundred bytes. */
private const val MAX_OAUTH_BODY = 64 * 1024

/**
 * The request body as text, or null if it is over [limit] bytes. Read in chunks with a running count,
 * so a chunked body with no Content-Length is capped too. These routes are reachable from the internet.
 * An oversized body is drained, not abandoned: closing with unread input resets the connection before
 * the client can read its 413. Nothing past the limit is kept.
 */
private suspend fun ApplicationCall.boundedText(limit: Int = MAX_OAUTH_BODY): String? {
    val out = ByteArrayOutputStream()
    val channel = receiveChannel()
    val chunk = ByteArray(4096)
    var tooLarge = false
    while (true) {
        val n = channel.readAvailable(chunk, 0, chunk.size)
        if (n < 0) break
        if (!tooLarge && out.size() + n > limit) { tooLarge = true; out.reset() }
        if (!tooLarge) out.write(chunk, 0, n)
    }
    return if (tooLarge) null else out.toString(Charsets.UTF_8.name())
}

/** A fixed window of [max] calls per [windowMs], shared by all callers of one route. */
internal class RateLimit(
    private val max: Int,
    private val windowMs: Long = 60_000,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private var windowStart = 0L
    private var count = 0

    @Synchronized
    fun allow(): Boolean {
        val t = now()
        if (t - windowStart >= windowMs) { windowStart = t; count = 0 }
        return ++count <= max
    }
}

/** OAuth discovery, registration, authorization (by pairing code) and token endpoints for claude.ai. */
internal fun Route.oauthRoutes(oauth: McpOAuth, mcpPath: String) {
    // Shared across callers, so a flood can briefly delay a refresh but cannot exhaust memory or CPU.
    val registerLimit = RateLimit(max = 20)
    val tokenLimit = RateLimit(max = 60)
    get("/.well-known/oauth-protected-resource/{rest...}") {
        call.json {
            put("resource", call.base() + mcpPath)
            putJsonArray("authorization_servers") { add(call.base()) }
        }
    }
    get("/.well-known/oauth-authorization-server/{rest...}") {
        val b = call.base()
        call.json {
            put("issuer", b)
            put("authorization_endpoint", "$b/authorize")
            put("token_endpoint", "$b/token")
            put("registration_endpoint", "$b/register")
            putJsonArray("response_types_supported") { add("code") }
            putJsonArray("grant_types_supported") { add("authorization_code"); add("refresh_token") }
            putJsonArray("code_challenge_methods_supported") { add("S256") }
            putJsonArray("token_endpoint_auth_methods_supported") { add("none") }
        }
    }
    // Public clients only; nothing is stored, since authorize checks the redirect URI itself.
    post("/register") {
        // Body first, so a refused request is drained too (see boundedText).
        val body = call.boundedText() ?: return@post call.respond(HttpStatusCode.PayloadTooLarge)
        if (!registerLimit.allow()) return@post call.respond(HttpStatusCode.TooManyRequests)
        val uris = runCatching {
            Json.parseToJsonElement(body).jsonObject["redirect_uris"]!!.jsonArray.map { it.jsonPrimitive.content }
        }.getOrDefault(emptyList())
        if (uris.isEmpty() || !uris.all(McpOAuth::allowedRedirect)) {
            return@post call.json(HttpStatusCode.BadRequest) {
                put("error", "invalid_redirect_uri"); put("error_description", "Only Claude can connect")
            }
        }
        call.json(HttpStatusCode.Created) {
            put("client_id", "claude-" + System.nanoTime())
            putJsonArray("redirect_uris") { uris.forEach { add(it) } }
            put("token_endpoint_auth_method", "none")
        }
    }
    get("/authorize") {
        val p = call.request.queryParameters
        if (!McpOAuth.allowedRedirect(p["redirect_uri"])) return@get call.respondText("Only Claude can connect", status = HttpStatusCode.BadRequest)
        if (p["response_type"] != "code" || p["code_challenge_method"] != "S256" || p["code_challenge"].isNullOrEmpty()) {
            return@get call.respondRedirect(p["redirect_uri"] + "?error=invalid_request&state=" + enc(p["state"].orEmpty()))
        }
        call.respondText(pairingPage(p["redirect_uri"], p["state"], p["code_challenge"], error = false), ContentType.Text.Html)
    }
    post("/authorize") {
        val p = call.boundedText()?.let { parseQueryString(it) } ?: return@post call.respond(HttpStatusCode.PayloadTooLarge)
        val redirect = p["redirect_uri"]
        if (!McpOAuth.allowedRedirect(redirect)) return@post call.respondText("Only Claude can connect", status = HttpStatusCode.BadRequest)
        // PKCE is mandatory: without a challenge the code could never be redeemed.
        val challenge = p["code_challenge"]
        if (challenge.isNullOrEmpty()) return@post call.respondText("Missing code challenge", status = HttpStatusCode.BadRequest)
        val code = oauth.authorize(p["pairing"].orEmpty(), challenge, redirect!!)
            ?: return@post call.respondText(pairingPage(redirect, p["state"], p["code_challenge"], error = true), ContentType.Text.Html)
        call.respondRedirect("$redirect?code=${enc(code)}&state=${enc(p["state"].orEmpty())}")
    }
    post("/token") {
        // RFC 6749 5.1: token responses, errors included, must not be cached.
        call.response.header(HttpHeaders.CacheControl, "no-store")
        val p = call.boundedText()?.let { parseQueryString(it) } ?: return@post call.respond(HttpStatusCode.PayloadTooLarge)
        if (!tokenLimit.allow()) return@post call.respond(HttpStatusCode.TooManyRequests)
        val (access, refresh) = oauth.token(p.names().associateWith { p[it] })
            ?: return@post call.json(HttpStatusCode.BadRequest) { put("error", "invalid_grant") }
        call.json {
            put("access_token", access)
            put("token_type", "Bearer")
            put("expires_in", McpOAuth.ACCESS_SECONDS)
            put("refresh_token", refresh)
        }
    }
}

private fun pairingPage(redirect: String?, state: String?, challenge: String?, error: Boolean) = """
<!doctype html><meta name=viewport content="width=device-width,initial-scale=1">
<title>Connect to SecureRemote</title>
<body style="font-family:sans-serif;max-width:420px;margin:40px auto;padding:0 16px">
<h2>Connect Claude to SecureRemote</h2>
<p>Open SecureRemote → Settings → MCP server, tap <b>Pair Claude</b> and enter the code.</p>
${if (error) "<p style=color:#c00>That code is wrong or expired. Get a new one in the app.</p>" else ""}
<form method=post action=/authorize>
<input type=hidden name=redirect_uri value="${esc(redirect)}">
<input type=hidden name=state value="${esc(state)}">
<input type=hidden name=code_challenge value="${esc(challenge)}">
<input name=pairing autocomplete=off autocapitalize=characters placeholder="Pairing code" style="font-size:20px;width:100%;padding:8px">
<button style="font-size:18px;margin-top:12px;padding:8px 16px">Connect</button>
</form>
"""
