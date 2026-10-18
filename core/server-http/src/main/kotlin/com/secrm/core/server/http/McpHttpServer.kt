package com.secrm.core.server.http

import android.util.Log
import com.secrm.core.domain.CallDispatcher
import com.secrm.core.domain.Session
import com.secrm.core.server.mcp.McpProtocol
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.readAvailable

/**
 * Ktor CIO host bound to loopback. Two connectors:
 *   - localPort  (7823): on-phone callers, accepts the local token
 *   - remotePort (7824): forwarded by a tunnel, accepts the remote bearer
 *
 * Both bind to 127.0.0.1. External reachability is the tunnel's job.
 */
class McpHttpServer(
    private val dispatcher: CallDispatcher,
    private val auth: McpAuth,
    private val oauth: McpOAuth,
    private val localPort: Int = 7823,
    private val remotePort: Int = 7824,
    private val mcpPath: String = "/mcp"
) {
    companion object {
        private const val TAG = "McpHttpServer"
        private const val MAX_BODY_BYTES = 1 shl 20
    }
    private var engine: EmbeddedServer<*, *>? = null

    @Synchronized
    fun start() {
        if (engine != null) return
        engine = embeddedServer(CIO, configure = {
            connector { host = "127.0.0.1"; port = localPort }
            connector { host = "127.0.0.1"; port = remotePort }
        }) {
            install(StatusPages) {
                exception<Throwable> { call, cause ->
                    Log.e(TAG, "Unhandled: ${cause.message}", cause)
                    call.respondText("internal error", status = HttpStatusCode.InternalServerError)
                }
            }

            routing {
                post(mcpPath) {
                    val decision = auth.check(
                        serverPort = call.request.local.localPort,
                        hostHeader = call.request.headers[HttpHeaders.Host],
                        originHeader = call.request.headers[HttpHeaders.Origin],
                        authorization = call.request.headers[HttpHeaders.Authorization]
                    )
                    when (decision) {
                        is McpAuth.Decision.Deny -> {
                            if (decision.status == 401) {
                                // Points claude.ai at the OAuth metadata (RFC 9728).
                                call.response.headers.append(
                                    HttpHeaders.WWWAuthenticate,
                                    "Bearer resource_metadata=\"${call.base()}/.well-known/oauth-protected-resource\""
                                )
                            }
                            call.respondText(
                                decision.reason,
                                status = HttpStatusCode.fromValue(decision.status)
                            )
                            return@post
                        }
                        is McpAuth.Decision.Allow -> Unit
                    }

                    // Streamed with a cap, so chunked bodies are bounded too (Content-Length alone is not).
                    val out = java.io.ByteArrayOutputStream()
                    val channel = call.receiveChannel()
                    val chunk = ByteArray(8192)
                    while (true) {
                        val n = channel.readAvailable(chunk, 0, chunk.size)
                        if (n < 0) break
                        if (out.size() + n > MAX_BODY_BYTES) return@post call.respond(HttpStatusCode.PayloadTooLarge)
                        out.write(chunk, 0, n)
                    }
                    val body = out.toString(Charsets.UTF_8.name())
                    // Per client, from a hash of its bearer token: audit can tell clients apart without storing the token.
                    val who = java.security.MessageDigest.getInstance("SHA-256")
                        .digest(call.request.headers[HttpHeaders.Authorization].orEmpty().toByteArray())
                        .take(6).joinToString("") { "%02x".format(it) }
                    val session = Session(id = "mcp-$who", clientLabel = "mcp")
                    val response = McpProtocol.handle(body, session, dispatcher)

                    if (response == null) {
                        call.respond(HttpStatusCode.Accepted)
                    } else {
                        call.respondText(
                            text = response,
                            contentType = ContentType.Application.Json,
                            status = HttpStatusCode.OK
                        )
                    }
                }

                // No server-sent event stream.
                get(mcpPath) { call.respond(HttpStatusCode.MethodNotAllowed) }

                // OAuth and well-known routes answer only to this node's public name on the remote port.
                route("") {
                    intercept(ApplicationCallPipeline.Plugins) {
                        if (!auth.hostAllowed(call.request.local.localPort, call.request.headers[HttpHeaders.Host])) {
                            call.respondText("Host not allowed", status = HttpStatusCode.Forbidden)
                            finish()
                        }
                    }
                    oauthRoutes(oauth, mcpPath)
                }
            }
        }.start(wait = false)
        Log.i(TAG, "MCP listening on 127.0.0.1:$localPort and :$remotePort$mcpPath")
    }

    @Synchronized
    fun stop() { engine?.stop(500, 1500); engine = null }

    @Synchronized
    fun isRunning(): Boolean = engine != null
}
