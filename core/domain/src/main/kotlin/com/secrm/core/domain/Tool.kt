package com.secrm.core.domain

import kotlinx.serialization.json.JsonObject

data class ToolSpec(
    val name: String,
    val description: String,
    val inputSchema: JsonObject,
    val annotations: Annotations = Annotations()
) {
    data class Annotations(
        val readOnlyHint: Boolean = false,
        val destructiveHint: Boolean = false,
        val idempotentHint: Boolean = false,
        val openWorldHint: Boolean = false
    )
}

data class ToolResult(
    val content: List<Content>,
    val isError: Boolean = false,
    val errorCode: String? = null,
    val recoveryHint: String? = null
) {
    sealed interface Content {
        data class Text(val text: String) : Content
    }
    companion object {
        fun ok(text: String) = ToolResult(listOf(Content.Text(text)))
        fun error(code: String, hint: String, message: String) =
            ToolResult(listOf(Content.Text("$message\nHint: $hint")), true, code, hint)
    }
}

data class Session(val id: String, val clientLabel: String)

interface ToolHandler {
    suspend fun call(session: Session, args: JsonObject): ToolResult
}

class CallDispatcher(private val audit: AuditSink = AuditSink.Noop) {
    private val handlers = linkedMapOf<String, Pair<ToolSpec, ToolHandler>>()

    fun register(spec: ToolSpec, handler: ToolHandler) {
        handlers[spec.name] = spec to handler
    }

    fun specs(): List<ToolSpec> = handlers.values.map { it.first }

    suspend fun dispatch(session: Session, name: String, args: JsonObject): ToolResult {
        val (_, handler) = handlers[name]
            ?: return ToolResult.error("unknown_tool", "Call tools/list first.", "Unknown tool: $name")
        return try {
            val result = handler.call(session, args)
            audit.record(session, name, args, result)
            result
        } catch (t: Throwable) {
            val r = ToolResult.error("internal_error", "Retry or check server logs.", t.message ?: "Tool failed")
            audit.record(session, name, args, r)
            r
        }
    }

    interface AuditSink {
        suspend fun record(session: Session, tool: String, args: JsonObject, result: ToolResult)
        object Noop : AuditSink {
            override suspend fun record(session: Session, tool: String, args: JsonObject, result: ToolResult) {}
        }
    }
}
