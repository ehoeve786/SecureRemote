package com.secrm.core.ssh.tools

import com.secrm.core.domain.*
import com.secrm.core.ssh.SshAuth
import com.secrm.core.ssh.SshClientManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*

object SshTools {

    /** The read-only tools run fixed, short commands, so they get a short leash. */
    private const val READ_TIMEOUT_MS = 15_000L
    private const val MAX_READ_BYTES = 1 shl 20
    private val SERVICE_NAME = Regex("[A-Za-z0-9@._:-]{1,128}")

    /** Which owner flag a tool needs: `allowMcpExec` for running commands, `allowMcpRead` for fixed read-only queries. */
    private enum class Need(val label: String) { EXEC("exec"), READ("read") }

    /** Thrown inside [onConnection] to end a call with a specific error. */
    private class Denied(val result: ToolResult) : RuntimeException(null, null, false, false)

    fun register(
        dispatcher: CallDispatcher,
        saved: SavedConnections,
        ssh: SshClientManager,
        commands: () -> List<NamedCommand> = { emptyList() },
    ) {
        val pool = McpSessionPool(CoroutineScope(Dispatchers.IO + SupervisorJob()), saved) {
            // A separate client, so this neither replaces nor drops the app's live session.
            SshClientManager().apply { proxy = ssh.proxy }
        }

        dispatcher.register(
            ToolSpec(
                name = "ssh.list_connections",
                description = "List saved SSH connections exposed to MCP clients.",
                inputSchema = emptyObject(),
                annotations = ToolSpec.Annotations(readOnlyHint = true)
            ),
            object : ToolHandler {
                override suspend fun call(session: Session, args: JsonObject): ToolResult {
                    val visible = saved.list()
                        .filter { it.allowMcpExec || it.allowMcpRead }
                        .map {
                            buildJsonObject {
                                put("name", it.name)
                                put("host", it.host)
                                put("port", it.port)
                                put("user", it.user)
                                put("can_exec", it.allowMcpExec)
                                put("can_read", it.allowMcpRead)
                            }
                        }
                    return ToolResult.ok(
                        buildJsonObject { put("connections", JsonArray(visible)) }.toString()
                    )
                }
            }
        )

        dispatcher.register(
            ToolSpec(
                name = "ssh.exec",
                description = "Run a shell command on a saved connection. Returns stdout+stderr.",
                inputSchema = schema(
                    required = listOf("connection", "command"),
                    "connection" to field("string", CONNECTION_DOC),
                    "command" to field("string", "Shell command, run non-interactively."),
                    "timeout_ms" to field("integer", "Clamped to 1000..300000. Default 30000."),
                ),
                annotations = ToolSpec.Annotations(destructiveHint = true, openWorldHint = true)
            ),
            object : ToolHandler {
                override suspend fun call(session: Session, args: JsonObject): ToolResult {
                    val cmd = args.str("command")
                        ?: return missingArg("command")
                    // Clamped: a caller cannot hold a session open indefinitely.
                    val timeout = args.long("timeout_ms", 30_000L, 1_000L..300_000L)
                    return onConnection(saved, pool, args, Need.EXEC, timeout) { client ->
                        ToolResult.ok(client.execOnce(cmd))
                    }
                }
            }
        )

        dispatcher.register(
            readTool(
                name = "ssh.list_snippets",
                description = "Saved command snippets by name, with the variables each takes. Their text is not shown.",
                schema = schema(required = emptyList()),
            ),
            object : ToolHandler {
                override suspend fun call(session: Session, args: JsonObject): ToolResult =
                    ToolResult.ok(buildJsonObject {
                        putJsonArray("snippets") {
                            commands().forEach { c ->
                                add(buildJsonObject {
                                    put("name", c.name)
                                    putJsonArray("variables") { placeholdersOf(c.command).forEach { add(it) } }
                                })
                            }
                        }
                    }.toString())
            }
        )

        dispatcher.register(
            ToolSpec(
                name = "ssh.forward_port",
                description = "Forward a port on the phone's loopback to host:port as the server sees it. Lasts while the connection is kept alive.",
                inputSchema = schema(
                    required = listOf("connection", "local_port", "host", "port"),
                    "connection" to field("string", CONNECTION_DOC),
                    "local_port" to field("integer", "1024..65535; bound to 127.0.0.1 on the phone."),
                    "host" to field("string", "Host as the server sees it, e.g. 127.0.0.1 or a LAN address."),
                    "port" to field("integer", "Remote port, 1..65535."),
                ),
                annotations = ToolSpec.Annotations(destructiveHint = true, openWorldHint = true)
            ),
            object : ToolHandler {
                override suspend fun call(session: Session, args: JsonObject): ToolResult {
                    val local = args["local_port"]?.jsonPrimitive?.intOrNull
                        ?: return missingArg("local_port")
                    val host = args.str("host") ?: return missingArg("host")
                    val port = args["port"]?.jsonPrimitive?.intOrNull ?: return missingArg("port")
                    if (local !in 1024..65535) return ToolResult.error("invalid_argument",
                        "Use a local port from 1024 to 65535.", "Local port out of range: $local")
                    if (port !in 1..65535) return ToolResult.error("invalid_argument",
                        "Use a remote port from 1 to 65535.", "Remote port out of range: $port")
                    return onConnection(saved, pool, args, Need.EXEC, READ_TIMEOUT_MS) { client ->
                        client.addLocalForward(local, host, port)
                        ToolResult.ok(buildJsonObject {
                            put("local", "127.0.0.1:$local")
                            put("target", "$host:$port")
                        }.toString())
                    }
                }
            }
        )

        dispatcher.register(
            ToolSpec(
                name = "ssh.close_forward",
                description = "Close a forward opened by ssh.forward_port.",
                inputSchema = schema(
                    required = listOf("connection", "local_port"),
                    "connection" to field("string", CONNECTION_DOC),
                    "local_port" to field("integer", "The local port that was forwarded."),
                ),
                annotations = ToolSpec.Annotations(destructiveHint = true, openWorldHint = true)
            ),
            object : ToolHandler {
                override suspend fun call(session: Session, args: JsonObject): ToolResult {
                    val local = args["local_port"]?.jsonPrimitive?.intOrNull ?: return missingArg("local_port")
                    return onConnection(saved, pool, args, Need.EXEC, READ_TIMEOUT_MS) { client ->
                        client.removeLocalForward(local)
                        ToolResult.ok("Closed 127.0.0.1:$local")
                    }
                }
            }
        )

        dispatcher.register(
            ToolSpec(
                name = "ssh.run_snippet",
                description = "Run a saved snippet on a connection. Fill its variables with 'variables'; values are quoted, not interpreted.",
                inputSchema = schema(
                    required = listOf("connection", "snippet"),
                    "connection" to field("string", CONNECTION_DOC),
                    "snippet" to field("string", "Snippet name, from ssh.list_snippets."),
                    "variables" to buildJsonObject { put("type", "object") },
                    "timeout_ms" to field("integer", "Clamped to 1000..300000. Default 30000."),
                ),
                annotations = ToolSpec.Annotations(destructiveHint = true, openWorldHint = true)
            ),
            object : ToolHandler {
                override suspend fun call(session: Session, args: JsonObject): ToolResult {
                    val name = args.str("snippet") ?: return missingArg("snippet")
                    val snippet = commands().firstOrNull { it.name == name }
                        ?: return ToolResult.error("unknown_snippet", "Call ssh.list_snippets.", "No snippet named $name")
                    val values = (args["variables"] as? JsonObject)
                        ?.mapValues { it.value.jsonPrimitive.contentOrNull.orEmpty() }.orEmpty()
                    val missing = placeholdersOf(snippet.command) - values.keys
                    if (missing.isNotEmpty()) return ToolResult.error("missing_variable",
                        "Pass every variable listed by ssh.list_snippets.", "Missing: ${missing.joinToString()}")
                    val cmd = fillSnippet(snippet.command, values)
                    val timeout = args.long("timeout_ms", 30_000L, 1_000L..300_000L)
                    return onConnection(saved, pool, args, Need.EXEC, timeout) { client ->
                        ToolResult.ok(client.execOnce(cmd))
                    }
                }
            }
        )

        dispatcher.register(
            readTool(
                name = "ssh.read_file",
                description = "Read the start of a file on a saved connection. Truncated at max_bytes; reports whether it was.",
                schema = schema(
                    required = listOf("connection", "path"),
                    "connection" to field("string", CONNECTION_DOC),
                    "path" to field("string", "Absolute or relative path of a regular file."),
                    "max_bytes" to field("integer", "Clamped to 1..1048576. Default 65536."),
                ),
            ),
            object : ToolHandler {
                override suspend fun call(session: Session, args: JsonObject): ToolResult {
                    val path = args.str("path") ?: return missingArg("path")
                    val max = args.long("max_bytes", 65_536L, 1L..MAX_READ_BYTES.toLong()).toInt()
                    return onConnection(saved, pool, args, Need.READ, READ_TIMEOUT_MS) { client ->
                        // One byte past the cap tells us whether the file was cut short.
                        val r = client.execResult("head -c ${max + 1} -- ${shq(path)}")
                        if (r.exitCode != 0) return@onConnection commandFailed(r)
                        val bytes = r.stdout.toByteArray(Charsets.UTF_8)
                        val truncated = bytes.size > max
                        val content = if (truncated) bytes.copyOf(max).decodeToString() else r.stdout
                        ToolResult.ok(buildJsonObject {
                            put("path", path)
                            put("truncated", truncated)
                            put("content", content)
                        }.toString())
                    }
                }
            }
        )

        dispatcher.register(
            readTool(
                name = "ssh.tail_log",
                description = "Return the last lines of a log file on a saved connection.",
                schema = schema(
                    required = listOf("connection", "path"),
                    "connection" to field("string", CONNECTION_DOC),
                    "path" to field("string", "Path of the log file."),
                    "lines" to field("integer", "Clamped to 1..2000. Default 100."),
                ),
            ),
            object : ToolHandler {
                override suspend fun call(session: Session, args: JsonObject): ToolResult {
                    val path = args.str("path") ?: return missingArg("path")
                    val lines = args.long("lines", 100L, 1L..2_000L).toInt()
                    return onConnection(saved, pool, args, Need.READ, READ_TIMEOUT_MS) { client ->
                        val r = client.execResult("tail -n $lines -- ${shq(path)}")
                        if (r.exitCode != 0) return@onConnection commandFailed(r)
                        ToolResult.ok(buildJsonObject {
                            put("path", path)
                            put("lines", lines)
                            put("content", r.stdout)
                        }.toString())
                    }
                }
            }
        )

        dispatcher.register(
            readTool(
                name = "ssh.list_processes",
                description = "Top processes by CPU or memory on a saved connection, as rows.",
                schema = schema(
                    required = listOf("connection"),
                    "connection" to field("string", CONNECTION_DOC),
                    "sort" to field("string", "\"cpu\" (default) or \"mem\"."),
                    "limit" to field("integer", "Clamped to 1..200. Default 20."),
                ),
            ),
            object : ToolHandler {
                override suspend fun call(session: Session, args: JsonObject): ToolResult {
                    val sort = args.str("sort") ?: "cpu"
                    val key = when (sort) {
                        "cpu" -> "pcpu"
                        "mem" -> "pmem"
                        else -> return ToolResult.error("invalid_argument",
                            "Use \"cpu\" or \"mem\".", "Unknown sort: $sort")
                    }
                    val limit = args.long("limit", 20L, 1L..200L).toInt()
                    return onConnection(saved, pool, args, Need.READ, READ_TIMEOUT_MS) { client ->
                        val r = client.execResult(
                            "ps -eo pid,user,pcpu,pmem,etime,comm --sort=-$key | head -n ${limit + 1}"
                        )
                        val rows = parsePs(r.stdout)
                        if (rows.isEmpty() && r.stderr.isNotBlank()) return@onConnection commandFailed(r)
                        ToolResult.ok(buildJsonObject {
                            put("sort", sort)
                            put("processes", JsonArray(rows))
                        }.toString())
                    }
                }
            }
        )

        dispatcher.register(
            readTool(
                name = "ssh.service_status",
                description = "systemd state of one unit on a saved connection (load, active, sub state, PID).",
                schema = schema(
                    required = listOf("connection", "unit"),
                    "connection" to field("string", CONNECTION_DOC),
                    "unit" to field("string", "Unit name, e.g. nginx.service."),
                ),
            ),
            object : ToolHandler {
                override suspend fun call(session: Session, args: JsonObject): ToolResult {
                    val unit = args.str("unit") ?: return missingArg("unit")
                    // The name goes into the command unquoted, so only characters that are inert in a shell are allowed.
                    if (!SERVICE_NAME.matches(unit)) return ToolResult.error("invalid_argument",
                        "Use a bare unit name such as nginx.service.", "Invalid unit name: $unit")
                    return onConnection(saved, pool, args, Need.READ, READ_TIMEOUT_MS) { client ->
                        val r = client.execResult(
                            "systemctl show -p LoadState,ActiveState,SubState,MainPID,UnitFileState $unit"
                        )
                        if (r.exitCode != 0) return@onConnection commandFailed(r)
                        val props = parseShow(r.stdout)
                        ToolResult.ok(buildJsonObject {
                            put("unit", unit)
                            props.forEach { (k, v) -> put(k, v) }
                        }.toString())
                    }
                }
            }
        )
    }

    private const val CONNECTION_DOC = "Saved connection name, from ssh.list_connections."

    private fun readTool(name: String, description: String, schema: JsonObject) = ToolSpec(
        name = name,
        description = description,
        inputSchema = schema,
        annotations = ToolSpec.Annotations(readOnlyHint = true, openWorldHint = true),
    )

    /**
     * Checks the caller may use [args]' connection for [need], resolves its credentials, then runs [block]
     * on the pooled session with a [timeoutMs] limit. Every refusal is returned as a ToolResult, not thrown.
     */
    private suspend fun onConnection(
        saved: SavedConnections,
        pool: McpSessionPool,
        args: JsonObject,
        need: Need,
        timeoutMs: Long,
        block: suspend (SshClientManager) -> ToolResult,
    ): ToolResult = try {
        val name = args.str("connection") ?: return missingArg("connection")
        val conn = saved.find(name)
            ?: return ToolResult.error("unknown_connection", "Call ssh.list_connections.", "No such connection: $name")
        val allowed = if (need == Need.EXEC) conn.allowMcpExec else conn.allowMcpRead
        if (!allowed) return ToolResult.error("not_permitted",
            "Enable MCP ${need.label} for this connection in the app.",
            "Connection '$name' is not exposed for ${need.label}.")

        // The host key must already be trusted. Trusting a first-seen key is the owner's job, in the app.
        if (conn.hostKey == null) return ToolResult.error("host_not_pinned",
            "Connect to it once in the SecureRemote app to trust its host key.",
            "Connection '$name' has no trusted host key yet.")

        val auth = authFor(conn, saved)
        val secrets = Redact.secretsOf(auth)
        pool.use(conn, auth) { client ->
            try {
                withTimeout(timeoutMs) { block(client) }.let { r ->
                    // Output goes to the agent, so it is scrubbed here, whichever tool produced it.
                    r.copy(content = r.content.map { c -> if (c is ToolResult.Content.Text) ToolResult.Content.Text(Redact.text(c.text, secrets)) else c })
                }
            } catch (t: TimeoutCancellationException) {
                ToolResult.error("exec_failed", "Retry with a larger timeout_ms or a smaller request.",
                    "Timed out after $timeoutMs ms")
            } catch (t: CancellationException) {
                throw t
            } catch (t: Exception) {
                ToolResult.error("exec_failed", "Retry or shorten command.", Redact.text(t.message ?: "exec failed", secrets))
            }
        } ?: if (pool.hostKeyChanged(conn.name)) ToolResult.error("host_key_changed",
            "The server's host key changed. The owner must trust the new key in the app before MCP can connect.",
            "Host key for '${conn.name}' has changed.")
        else ToolResult.error("connect_failed", "Check host reachability and credentials.",
            "SSH connect failed to ${conn.host}:${conn.port}")
    } catch (d: Denied) {
        d.result
    }

    private fun authFor(conn: SavedConnection, saved: SavedConnections): SshAuth = when (conn.authMethod) {
        AuthMethod.PASSWORD -> SshAuth.Password(conn.password)
        AuthMethod.SAVED_KEY -> {
            val keyId = conn.keyId ?: throw Denied(ToolResult.error("no_key",
                "Assign a key to this connection.", "Connection '${conn.name}' has no key assigned."))
            val pem = saved.privateKeyFor(keyId) ?: throw Denied(ToolResult.error("missing_key",
                "Re-import the key in Settings → SSH Keys.", "Private key for '$keyId' not found."))
            SshAuth.Key(pem = pem, passphrase = saved.keyPassphraseFor(keyId)?.takeIf { it.isNotBlank() })
        }
    }

    private fun missingArg(name: String) = ToolResult.error("missing_argument",
        "Provide '$name'.", "Missing $name")

    private fun commandFailed(r: SshClientManager.ExecResult) = ToolResult.error("command_failed",
        "Check the path or name and that the connection's user can read it.",
        r.stderr.ifBlank { "Command exited with ${r.exitCode}" }.trim())

    private fun JsonObject.str(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() }

    private fun JsonObject.long(key: String, default: Long, range: LongRange): Long =
        this[key]?.jsonPrimitive?.longOrNull?.coerceIn(range) ?: default

    /** Single-quotes [s] for a POSIX shell, so no path can add a command. */
    internal fun shq(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    /** Rows from `ps -eo pid,user,pcpu,pmem,etime,comm`; the header and malformed lines are skipped. */
    internal fun parsePs(out: String): List<JsonObject> = out.lineSequence().drop(1).mapNotNull { line ->
        val f = line.trim().split(Regex("\\s+"), limit = 6)
        if (f.size < 6) return@mapNotNull null
        val pid = f[0].toIntOrNull() ?: return@mapNotNull null
        val cpu = f[2].toDoubleOrNull() ?: return@mapNotNull null
        val mem = f[3].toDoubleOrNull() ?: return@mapNotNull null
        buildJsonObject {
            put("pid", pid)
            put("user", f[1])
            put("cpu_percent", cpu)
            put("mem_percent", mem)
            put("elapsed", f[4])
            put("command", f[5])
        }
    }.toList()

    /** `Key=Value` lines from `systemctl show`, in the order given. */
    internal fun parseShow(out: String): List<Pair<String, String>> = out.lineSequence()
        .mapNotNull { line -> line.indexOf('=').takeIf { it > 0 }?.let { line.substring(0, it) to line.substring(it + 1) } }
        .toList()

    private fun schema(required: List<String>, vararg props: Pair<String, JsonObject>) = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") { props.forEach { (k, v) -> put(k, v) } }
        putJsonArray("required") { required.forEach { add(it) } }
    }

    private fun field(type: String, description: String) = buildJsonObject {
        put("type", type)
        put("description", description)
    }

    private fun emptyObject() = buildJsonObject {
        put("type", "object"); putJsonObject("properties") {}
    }
}
