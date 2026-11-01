package com.secrm.core.ssh.tools

/** A saved command an agent can run by name. [command] may hold `{name}` placeholders for variables. */
data class NamedCommand(val name: String, val command: String)

/** `{name}` placeholders, except shell parameter expansions such as `${HOME}`. */
private val PLACEHOLDER = Regex("(?<!\\$)\\{([A-Za-z_][A-Za-z0-9_]*)\\}")

/** The variable names in [command], in order of first use. */
fun placeholdersOf(command: String): List<String> = PLACEHOLDER.findAll(command).map { it.groupValues[1] }.distinct().toList()

/** [command] with each placeholder replaced by its value, single-quoted so a value cannot add commands. */
fun fillSnippet(command: String, values: Map<String, String>): String =
    PLACEHOLDER.replace(command) { m -> shellQuote(values[m.groupValues[1]].orEmpty()) }

/** Single-quotes [s] for a POSIX shell. */
fun shellQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"
