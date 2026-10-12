package com.secrm.core.server.mcp

import com.secrm.core.domain.CallDispatcher
import com.secrm.core.domain.Session
import com.secrm.core.domain.ToolResult
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.*
import kotlinx.serialization.json.*

object McpServerFactory {

    const val TOOL_CONTRACT_VERSION = 1

    val INSTRUCTIONS: String = """
        SecureRemoteManager exposes a controlled view of the user's saved
        SSH connections to MCP clients.

        1. Call ssh.list_connections first.
        2. Call ssh.exec with 'connection' and 'command'.
        3. Errors carry errorCode + recoveryHint — prefer fixing the cause
           over retrying.

        Contract version: $TOOL_CONTRACT_VERSION
    """.trimIndent()

    fun build(dispatcher: CallDispatcher, session: Session): Server {
        val server = Server(
            serverInfo = Implementation(name = "secrm", version = "1.0.0"),
            options = ServerOptions(
                instructions = INSTRUCTIONS,
                capabilities = ServerCapabilities(
                    tools = ServerCapabilities.Tools(listChanged = false)
                )
            )
        )

        for (spec in dispatcher.specs()) {
            server.addTool(
                name = spec.name,
                description = spec.description,
                inputSchema = spec.inputSchema,
                annotations = ToolAnnotations(
                    readOnlyHint = spec.annotations.readOnlyHint,
                    destructiveHint = spec.annotations.destructiveHint,
                    idempotentHint = spec.annotations.idempotentHint,
                    openWorldHint = spec.annotations.openWorldHint
                )
            ) { request ->
                val args = request.arguments ?: buildJsonObject {}
                toMcp(dispatcher.dispatch(session, spec.name, args))
            }
        }
        return server
    }

    private fun toMcp(r: ToolResult): CallToolResult {
        val text = r.content.joinToString("\n") {
            when (it) { is ToolResult.Content.Text -> it.text }
        }
        val structured = buildJsonObject {
            r.errorCode?.let { put("errorCode", it) }
            r.recoveryHint?.let { put("recoveryHint", it) }
        }
        return CallToolResult(
            content = listOf(TextContent(text = text)),
            structuredContent = structured,
            isError = r.isError
        )
    }
}
