package com.secrm.plugin

import android.os.Bundle
import com.secrm.core.domain.ActionSignature
import com.secrm.core.security.EncryptedConfigStore
import java.security.SecureRandom

/** One configured plugin action: which saved host, which command, and whether the macro waits for it. */
data class RunSetting(
    val connection: String,
    val command: String,
    /** Wait for the command to finish before the macro continues; its output comes back as %stdout, %stderr, %result. */
    val block: Boolean,
    /** Show the output in a read-only terminal while the command runs; tap its notification to open it. */
    val live: Boolean = false,
) {
    /** Plain bundle for the app's own intents. Not trusted from outside: see [signedBundle]. */
    fun toBundle(): Bundle = Bundle().apply {
        putString(KEY_CONNECTION, connection)
        putString(KEY_COMMAND, command)
        putBoolean(KEY_BLOCK, block)
        putBoolean(KEY_LIVE, live)
    }

    /** The bundle handed back to the host macro: [toBundle] plus a signature only this app can make. */
    fun signedBundle(config: EncryptedConfigStore): Bundle = toBundle().apply {
        putString(KEY_SIGNATURE, ActionSignature.sign(signingKey(config), *fields()))
    }

    fun blurb(): String = "$connection: ${command.lineSequence().firstOrNull().orEmpty().take(40)}"

    private fun fields() = arrayOf(connection, command, block.toString(), live.toString())

    companion object {
        private const val KEY_CONNECTION = "connection"
        private const val KEY_COMMAND = "command"
        private const val KEY_BLOCK = "block"
        private const val KEY_LIVE = "live"
        private const val KEY_SIGNATURE = "signature"
        private const val KEY_SIGNING = "plugin.signing_key"

        fun fromBundle(b: Bundle?): RunSetting? {
            if (b == null || !b.containsKey(KEY_COMMAND)) return null
            return RunSetting(
                connection = b.getString(KEY_CONNECTION).orEmpty(),
                command = b.getString(KEY_COMMAND).orEmpty(),
                block = b.getBoolean(KEY_BLOCK),
                live = b.getBoolean(KEY_LIVE),
            )
        }

        /**
         * True only for a bundle this app signed, with its settings unchanged since. Any app on the
         * phone can send the fire broadcast, so this is what stops it running an arbitrary command.
         * Bundles saved before signing existed fail, so their macros need to be edited and saved again.
         */
        fun isSigned(config: EncryptedConfigStore, bundle: Bundle?, setting: RunSetting): Boolean {
            val signature = bundle?.getString(KEY_SIGNATURE) ?: return false
            val key = config.get(KEY_SIGNING)?.let(::fromHex) ?: return false
            return ActionSignature.verify(key, signature, *setting.fields())
        }

        /** A random 256-bit key, made on first use and kept in the encrypted store. */
        @Synchronized
        private fun signingKey(config: EncryptedConfigStore): ByteArray {
            config.get(KEY_SIGNING)?.let { return fromHex(it) }
            val key = ByteArray(32).also(SecureRandom()::nextBytes)
            config.put(KEY_SIGNING, key.joinToString("") { "%02x".format(it) })
            return key
        }

        private fun fromHex(hex: String) = ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
}
