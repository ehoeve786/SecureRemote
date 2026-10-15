package com.secrm.core.domain

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CallDispatcherTest {
    private val spec = ToolSpec("t", "test", buildJsonObject { })
    private val session = Session("s", "client")

    private class Recorder : CallDispatcher.AuditSink {
        val seen = mutableListOf<Triple<String, Boolean, String?>>()
        override suspend fun record(session: Session, tool: String, args: JsonObject, result: ToolResult) {
            seen += Triple(tool, result.isError, result.errorCode)
        }
    }

    @Test fun everyCallIsAuditedWithItsOutcome() = runBlocking {
        val audit = Recorder()
        val d = CallDispatcher(audit).apply {
            register(spec, object : ToolHandler { override suspend fun call(session: Session, args: JsonObject) = ToolResult.ok("x") })
        }
        d.dispatch(session, "t", buildJsonObject { })
        d.dispatch(session, "missing", buildJsonObject { })
        assertEquals(2, audit.seen.size)
        assertEquals(Triple("t", false, null), audit.seen[0])
    }

    @Test fun throwingHandlerIsContainedAndAudited() = runBlocking {
        val audit = Recorder()
        val d = CallDispatcher(audit).apply {
            register(spec, object : ToolHandler { override suspend fun call(session: Session, args: JsonObject): ToolResult = throw IllegalStateException("bad") })
        }
        val r = d.dispatch(session, "t", buildJsonObject { })
        assertTrue(r.isError)
        assertEquals("internal_error", r.errorCode)
        assertEquals(Triple("t", true, "internal_error"), audit.seen.single())
    }

    @Test fun reRegisteringReplacesTheHandler() = runBlocking {
        val d = CallDispatcher()
        d.register(spec, object : ToolHandler { override suspend fun call(session: Session, args: JsonObject) = ToolResult.ok("old") })
        d.register(spec, object : ToolHandler { override suspend fun call(session: Session, args: JsonObject) = ToolResult.ok("new") })
        assertEquals("new", (d.dispatch(session, "t", buildJsonObject { }).content.single() as ToolResult.Content.Text).text)
        assertEquals(1, d.specs().size)
    }
}
