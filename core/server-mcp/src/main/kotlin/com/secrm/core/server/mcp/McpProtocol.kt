package com.secrm.core.server.mcp

import com.secrm.core.domain.CallDispatcher
import com.secrm.core.domain.Session
import com.secrm.core.domain.ToolResult
import kotlinx.serialization.json.*

/**
 * Minimal MCP server, hand-rolled against the 2024-11-05 spec.
 *
 * Handles only what a remote client needs:
 *   initialize, notifications/initialized, ping, tools/list, tools/call.
 *
 * Every call routes through CallDispatcher so the audit path is shared
 * with the in-app agent (once that exists).
 */
object McpProtocol {

    const val PROTOCOL_VERSION = "2024-11-05"
    const val SERVER_NAME = "secrm"
    const val SERVER_VERSION = "1.0.0"
    const val TOOL_CONTRACT_VERSION = 1

    val INSTRUCTIONS: String = """
        SecureRemoteManager exposes a controlled view of the user's saved
        SSH connections to MCP clients.

        Order of operations:
          1. Call ssh.list_connections first. Only connections explicitly
             enabled for MCP appear here.
          2. Call ssh.exec with the connection name and a shell command.
             Commands run in a non-interactive shell; combine steps with &&.

        Errors carry a stable errorCode and a recoveryHint. Prefer fixing
        the cause (unknown_connection -> list first; not_permitted -> ask
        the user to enable MCP on that connection) over retrying blindly.

        Contract version: $TOOL_CONTRACT_VERSION
    """.trimIndent()

    /**
     * Handle a JSON-RPC request body.
     * Returns the response JSON, or null if the message was a notification
     * (which must be answered with 202 Accepted and an empty body).
     */
    suspend fun handle(
        body: String,
        session: Session,
        dispatcher: CallDispatcher
    ): String? {
        val parsed = runCatching { Json.parseToJsonElement(body) }.getOrNull()
            ?: return errorResponse(JsonNull, -32700, "Parse error")
        // Batch arrays are valid JSON-RPC but not supported here: reject them as an invalid request.
        val root = parsed as? JsonObject ?: return errorResponse(JsonNull, -32600, "Batch requests are not supported")
        val id = root["id"]
        val method = (root["method"] as? JsonPrimitive)?.contentOrNull
            ?: return errorResponse(id, -32600, "Invalid request")
        val params = root["params"] as? JsonObject

        return when (method) {
            "initialize" -> handleInitialize(id)
            "notifications/initialized" -> null
            "ping" -> ok(id, buildJsonObject {})
            "tools/list" -> handleToolsList(id, dispatcher)
            "tools/call" -> handleToolsCall(id, params, session, dispatcher)
            else -> if (id == null) null else errorResponse(id, -32601, "Method not found: $method")
        }
    }

    private fun handleInitialize(id: JsonElement?): String {
        val result = buildJsonObject {
            put("protocolVersion", PROTOCOL_VERSION)
            putJsonObject("capabilities") {
                putJsonObject("tools") { put("listChanged", false) }
            }
            putJsonObject("serverInfo") {
                put("name", SERVER_NAME)
                put("version", SERVER_VERSION)
            }
            put("instructions", INSTRUCTIONS)
        }
        return ok(id, result)
    }

    private fun handleToolsList(id: JsonElement?, dispatcher: CallDispatcher): String {
        val tools = dispatcher.specs().map { spec ->
            buildJsonObject {
                put("name", spec.name)
                put("description", spec.description)
                put("inputSchema", spec.inputSchema)
                putJsonObject("annotations") {
                    put("readOnlyHint", spec.annotations.readOnlyHint)
                    put("destructiveHint", spec.annotations.destructiveHint)
                    put("idempotentHint", spec.annotations.idempotentHint)
                    put("openWorldHint", spec.annotations.openWorldHint)
                }
            }
        }
        return ok(id, buildJsonObject { put("tools", JsonArray(tools)) })
    }

    private suspend fun handleToolsCall(
        id: JsonElement?,
        params: JsonObject?,
        session: Session,
        dispatcher: CallDispatcher
    ): String {
        val name = (params?.get("name") as? JsonPrimitive)?.contentOrNull
            ?: return errorResponse(id, -32602, "Missing tool name")
        val args = params["arguments"] as? JsonObject ?: buildJsonObject {}
        // A throw here would reach the HTTP layer as plain text, not a JSON-RPC error with this id.
        val result = runCatching { dispatcher.dispatch(session, name, args) }
            .getOrElse { return errorResponse(id, -32603, "Internal error") }
        return ok(id, toolResultToJson(result))
    }

    private fun toolResultToJson(r: ToolResult): JsonObject = buildJsonObject {
        putJsonArray("content") {
            r.content.forEach { c ->
                when (c) {
                    is ToolResult.Content.Text -> addJsonObject {
                        put("type", "text")
                        put("text", c.text)
                    }
                }
            }
        }
        put("isError", r.isError)
        if (r.errorCode != null || r.recoveryHint != null) {
            putJsonObject("structuredContent") {
                r.errorCode?.let { put("errorCode", it) }
                r.recoveryHint?.let { put("recoveryHint", it) }
            }
        }
    }

    private fun ok(id: JsonElement?, result: JsonObject): String =
        buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id ?: JsonNull)
            put("result", result)
        }.toString()

    private fun errorResponse(id: JsonElement?, code: Int, message: String): String =
        buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id ?: JsonNull)
            putJsonObject("error") {
                put("code", code)
                put("message", message)
            }
        }.toString()
}
