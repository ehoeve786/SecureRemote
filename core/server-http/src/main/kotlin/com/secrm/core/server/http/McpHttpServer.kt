package com.secrm.core.server.http

import android.util.Log
import com.secrm.core.domain.CallDispatcher
import com.secrm.core.domain.Session
import com.secrm.core.server.mcp.McpServerFactory
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.modelcontextprotocol.kotlin.sdk.server.mcpStreamableHttp

class McpHttpServer(
    private val dispatcher: CallDispatcher,
    private val auth: McpAuth,
    private val localPort: Int = 7823,
    private val remotePort: Int = 7824,
    private val mcpPath: String = "/mcp"
) {
    companion object { private const val TAG = "McpHttpServer" }
    private var engine: EmbeddedServer<*, *>? = null

    fun start() {
        if (engine != null) return
        engine = embeddedServer(CIO, configure = {
            connector { host = "127.0.0.1"; port = localPort }
            connector { host = "127.0.0.1"; port = remotePort }
        }) {
            install(StatusPages) {
                exception<Throwable> { call, cause ->
                    Log.e(TAG, "Unhandled", cause)
                    call.respondText("internal error", status = HttpStatusCode.InternalServerError)
                }
            }
            intercept(ApplicationCallPipeline.Plugins) {
                val path = call.request.path()
                if (!path.startsWith(mcpPath)) return@intercept
                val decision = auth.check(
                    serverPort = call.request.local.port,
                    hostHeader = call.request.headers[HttpHeaders.Host],
                    originHeader = call.request.headers[HttpHeaders.Origin],
                    authorization = call.request.headers[HttpHeaders.Authorization]
                )
                when (decision) {
                    is McpAuth.Decision.Allow -> {}
                    is McpAuth.Decision.Deny -> {
                        if (decision.status == 401) {
                            call.response.headers.append(
                                HttpHeaders.WWWAuthenticate,
                                "Bearer realm=\"secrm\""
                            )
                        }
                        call.respondText(decision.reason, status = HttpStatusCode.fromValue(decision.status))
                        finish()
                    }
                }
            }
            mcpStreamableHttp(path = mcpPath, enableDnsRebindingProtection = false) {
                McpServerFactory.build(dispatcher, Session(id = "http", clientLabel = "mcp"))
            }
        }.start(wait = false)
        Log.i(TAG, "Listening on 127.0.0.1:$localPort and :$remotePort$mcpPath")
    }

    fun stop() { engine?.stop(500, 1500); engine = null }
    fun isRunning(): Boolean = engine != null
}
