package com.secrm.plugin

import android.os.Bundle

/** One configured plugin action: which saved host, which command, and where the output goes. */
data class RunSetting(
    val connection: String,
    val command: String,
    /** MacroDroid variable for stdout (for example "%result"); the exit code goes to "<variable>_exit". */
    val variable: String,
    /** Wait for the command to finish before the macro continues. */
    val block: Boolean,
) {
    fun toBundle(): Bundle = Bundle().apply {
        putString(KEY_CONNECTION, connection)
        putString(KEY_COMMAND, command)
        putString(KEY_VARIABLE, variable)
        putBoolean(KEY_BLOCK, block)
    }

    fun blurb(): String = "$connection: ${command.lineSequence().firstOrNull().orEmpty().take(40)}"

    companion object {
        private const val KEY_CONNECTION = "connection"
        private const val KEY_COMMAND = "command"
        private const val KEY_VARIABLE = "variable"
        private const val KEY_BLOCK = "block"

        /** Variable names are "%" plus lowercase letters, digits and underscores. */
        private val VARIABLE = Regex("%[a-z][a-z0-9_]*")
        fun validVariable(name: String) = VARIABLE.matches(name)

        fun fromBundle(b: Bundle?): RunSetting? {
            if (b == null || !b.containsKey(KEY_COMMAND)) return null
            return RunSetting(
                connection = b.getString(KEY_CONNECTION).orEmpty(),
                command = b.getString(KEY_COMMAND).orEmpty(),
                variable = b.getString(KEY_VARIABLE).orEmpty(),
                block = b.getBoolean(KEY_BLOCK),
            )
        }
    }
}
