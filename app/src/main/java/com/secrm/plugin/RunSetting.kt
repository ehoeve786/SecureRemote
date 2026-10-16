package com.secrm.plugin

import android.os.Bundle

/** One configured plugin action: which saved host, which command, and whether the macro waits for it. */
data class RunSetting(
    val connection: String,
    val command: String,
    /** Wait for the command to finish before the macro continues; its output comes back as %stdout, %stderr, %result. */
    val block: Boolean,
) {
    fun toBundle(): Bundle = Bundle().apply {
        putString(KEY_CONNECTION, connection)
        putString(KEY_COMMAND, command)
        putBoolean(KEY_BLOCK, block)
    }

    fun blurb(): String = "$connection: ${command.lineSequence().firstOrNull().orEmpty().take(40)}"

    companion object {
        private const val KEY_CONNECTION = "connection"
        private const val KEY_COMMAND = "command"
        private const val KEY_BLOCK = "block"

        fun fromBundle(b: Bundle?): RunSetting? {
            if (b == null || !b.containsKey(KEY_COMMAND)) return null
            return RunSetting(
                connection = b.getString(KEY_CONNECTION).orEmpty(),
                command = b.getString(KEY_COMMAND).orEmpty(),
                block = b.getBoolean(KEY_BLOCK),
            )
        }
    }
}
