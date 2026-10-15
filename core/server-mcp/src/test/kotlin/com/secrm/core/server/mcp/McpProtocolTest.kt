package com.secrm.core.server.mcp

import com.secrm.core.domain.CallDispatcher
import com.secrm.core.domain.Session
import com.secrm.core.domain.ToolHandler
import com.secrm.core.domain.ToolResult
import com.secrm.core.domain.ToolSpec
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class McpProtocolTest {
    private val session = Session("t", "test")
    private val dispatcher = CallDispatcher().apply {
        register(ToolSpec("ping.ok", "ok", buildJsonObject { put("type", "object") }, ToolSpec.Annotations(readOnlyHint = true)),
            object : ToolHandler { override suspend fun call(session: Session, args: JsonObject) = ToolResult.ok("fine") })
        register(ToolSpec("boom", "fails", buildJsonObject { put("type", "object") }),
            object : ToolHandler { override suspend fun call(session: Session, args: JsonObject): ToolResult = error("kaboom") })
    }

    private fun call(body: String): JsonObject? = runBlocking {
        McpProtocol.handle(body, session, dispatcher)?.let { Json.parseToJsonElement(it).jsonObject }
    }

    @Test fun initializeAdvertisesToolsAndProtocolVersion() {
        val r = call("""{"jsonrpc":"2.0","id":1,"method":"initialize"}""")!!["result"]!!.jsonObject
        assertEquals(McpProtocol.PROTOCOL_VERSION, r["protocolVersion"]!!.jsonPrimitive.content)
        assertTrue(r["capabilities"]!!.jsonObject.containsKey("tools"))
        assertTrue(r["instructions"]!!.jsonPrimitive.content.contains("ssh.list_connections"))
    }

    @Test fun notificationsGetNoResponse() {
        assertNull(call("""{"jsonrpc":"2.0","method":"notifications/initialized"}"""))
        assertNull("unknown notifications are ignored too", call("""{"jsonrpc":"2.0","method":"notifications/whatever"}"""))
    }

    @Test fun toolsListExposesSpecsAndAnnotations() {
        val tools = call("""{"jsonrpc":"2.0","id":2,"method":"tools/list"}""")!!["result"]!!.jsonObject["tools"]!!.jsonArray
        val ok = tools.first { it.jsonObject["name"]!!.jsonPrimitive.content == "ping.ok" }.jsonObject
        assertTrue(ok["annotations"]!!.jsonObject["readOnlyHint"]!!.jsonPrimitive.boolean)
    }

    @Test fun toolsCallReturnsTextContent() {
        val result = call("""{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"ping.ok"}}""")!!["result"]!!.jsonObject
        assertEquals("fine", result["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals(false, result["isError"]!!.jsonPrimitive.boolean)
    }

    @Test fun handlerExceptionBecomesInternalErrorResult() {
        val result = call("""{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"boom"}}""")!!["result"]!!.jsonObject
        assertEquals(true, result["isError"]!!.jsonPrimitive.boolean)
        assertEquals("internal_error", result["structuredContent"]!!.jsonObject["errorCode"]!!.jsonPrimitive.content)
    }

    @Test fun unknownToolIsAnErrorResultNotAProtocolError() {
        val result = call("""{"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"nope"}}""")!!["result"]!!.jsonObject
        assertEquals("unknown_tool", result["structuredContent"]!!.jsonObject["errorCode"]!!.jsonPrimitive.content)
    }

    @Test fun badJsonIsParseError() {
        assertEquals(-32700, call("{not json")!!["error"]!!.jsonObject["code"]!!.jsonPrimitive.int)
    }

    @Test fun methodMustBeAStringAndCallNeedsAName() {
        assertEquals(-32600, call("""{"jsonrpc":"2.0","id":6,"method":{"x":1}}""")!!["error"]!!.jsonObject["code"]!!.jsonPrimitive.int)
        assertEquals(-32602, call("""{"jsonrpc":"2.0","id":7,"method":"tools/call","params":{}}""")!!["error"]!!.jsonObject["code"]!!.jsonPrimitive.int)
    }

    @Test fun unknownMethodWithIdIsMethodNotFound() {
        assertEquals(-32601, call("""{"jsonrpc":"2.0","id":8,"method":"resources/list"}""")!!["error"]!!.jsonObject["code"]!!.jsonPrimitive.int)
    }

    @Test fun responseEchoesTheRequestId() {
        assertEquals("abc", call("""{"jsonrpc":"2.0","id":"abc","method":"ping"}""")!!["id"]!!.jsonPrimitive.content)
    }
}
