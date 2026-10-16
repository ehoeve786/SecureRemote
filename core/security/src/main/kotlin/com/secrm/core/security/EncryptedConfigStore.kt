package com.secrm.core.security

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.GeneralSecurityException

class EncryptedConfigStore(private val context: Context) {
    private var prefs: SharedPreferences? = openOrNull()

    /**
     * True when the Keystore key can no longer decrypt the store. Reads come back empty and writes
     * are dropped until the owner agrees to [resetStorage], so the app should prompt before saving.
     */
    val keystoreLost get() = prefs == null

    /** Deletes the unreadable store (credentials must be re-entered), then opens a fresh one. */
    @Synchronized
    fun resetStorage() {
        context.deleteSharedPreferences(FILE)
        prefs = openOrNull()
    }

    private fun openOrNull(): SharedPreferences? = try {
        val key = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(
            context, FILE, key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: GeneralSecurityException) {
        null
    }

    private companion object { const val FILE = "secrm_secure_prefs" }

    fun put(k: String, v: String) { prefs?.edit()?.putString(k, v)?.apply() }
    fun get(k: String): String? = prefs?.getString(k, null)
    fun remove(k: String) { prefs?.edit()?.remove(k)?.apply() }

    var sshHost: String?
        get() = get("ssh.host"); set(v) = put("ssh.host", v ?: "")
    var sshPort: String?
        get() = get("ssh.port"); set(v) = put("ssh.port", v ?: "22")
    var sshUser: String?
        get() = get("ssh.user"); set(v) = put("ssh.user", v ?: "")
    var sshPassword: String?
        get() = get("ssh.pass"); set(v) = put("ssh.pass", v ?: "")
    var sshConnectionsJson: String?
        get() = get("ssh.connections"); set(v) = put("ssh.connections", v ?: "")
    var mcpBearerToken: String?
        get() = get("mcp.tok"); set(v) = put("mcp.tok", v ?: "")
    /** Token for the on-phone port. Its own secret, not derived from the remote bearer. */
    var mcpLocalToken: String?
        get() = get("mcp.local"); set(v) = put("mcp.local", v ?: "")
}
