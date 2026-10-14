package com.secrm.core.server.http

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.*
import java.net.URLEncoder

/** The public origin a request came in on, e.g. https://secureremote-x.tail1234.ts.net. */
internal fun ApplicationCall.base() = "https://" + request.headers[HttpHeaders.Host].orEmpty()

private suspend fun ApplicationCall.json(status: HttpStatusCode = HttpStatusCode.OK, body: JsonObjectBuilder.() -> Unit) =
    respondText(buildJsonObject(body).toString(), ContentType.Application.Json, status)

private fun esc(s: String?) = s.orEmpty().replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;")

private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

/** OAuth discovery, registration, authorization (by pairing code) and token endpoints for claude.ai. */
internal fun Route.oauthRoutes(oauth: McpOAuth, mcpPath: String) {
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
        val uris = runCatching {
            Json.parseToJsonElement(call.receiveText()).jsonObject["redirect_uris"]!!.jsonArray.map { it.jsonPrimitive.content }
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
        val p = call.receiveParameters()
        val redirect = p["redirect_uri"]
        if (!McpOAuth.allowedRedirect(redirect)) return@post call.respondText("Only Claude can connect", status = HttpStatusCode.BadRequest)
        val code = oauth.authorize(p["pairing"].orEmpty(), p["code_challenge"].orEmpty(), redirect!!)
            ?: return@post call.respondText(pairingPage(redirect, p["state"], p["code_challenge"], error = true), ContentType.Text.Html)
        call.respondRedirect("$redirect?code=${enc(code)}&state=${enc(p["state"].orEmpty())}")
    }
    post("/token") {
        val p = call.receiveParameters()
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
