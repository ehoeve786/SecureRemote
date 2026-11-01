package com.secrm.core.ssh.tools

/** A saved command an agent can run by name. [command] may hold `{name}` placeholders for variables. */
data class NamedCommand(val name: String, val command: String)

/** `{name}` placeholders, except shell parameter expansions such as `${HOME}`. */
private val PLACEHOLDER = Regex("(?<!\\$)\\{([A-Za-z_][A-Za-z0-9_]*)\\}")

/** The variable names in [command], in order of first use. */
fun placeholdersOf(command: String): List<String> = PLACEHOLDER.findAll(command).map { it.groupValues[1] }.distinct().toList()

private val NAME = Regex("[A-Za-z_][A-Za-z0-9_]*")

/**
 * [command] with each placeholder replaced by its value, quoted for where the placeholder stands, so a value
 * cannot add commands: single-quoted outside quotes, escaped inside '…' or "…". Plain single-quoting would
 * end an enclosing '…' (leaving the value unquoted) or leave `$(…)` live inside "…".
 */
fun fillSnippet(command: String, values: Map<String, String>): String {
    val out = StringBuilder()
    var quote: Char? = null // the quote the text is inside, if any
    var i = 0
    while (i < command.length) {
        val c = command[i]
        if (c == '{' && (i == 0 || command[i - 1] != '$')) {
            val end = command.indexOf('}', i)
            val name = if (end > i) command.substring(i + 1, end) else ""
            if (NAME.matches(name)) {
                val v = values[name].orEmpty()
                out.append(when (quote) {
                    '\'' -> v.replace("'", "'\\''")
                    '"' -> v.replace(Regex("[\\\\\"$`]")) { "\\" + it.value }
                    else -> shellQuote(v)
                })
                i = end + 1
                continue
            }
        }
        when {
            // A backslash outside '…' escapes the next character, which then opens or closes nothing.
            c == '\\' && quote != '\'' && i + 1 < command.length -> { out.append(c).append(command[i + 1]); i += 2; continue }
            c == '\'' && quote != '"' -> quote = if (quote == '\'') null else '\''
            c == '"' && quote != '\'' -> quote = if (quote == '"') null else '"'
        }
        out.append(c)
        i++
    }
    return out.toString()
}

/** Single-quotes [s] for a POSIX shell. */
fun shellQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"
