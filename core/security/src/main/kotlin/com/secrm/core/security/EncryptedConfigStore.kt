package com.secrm.core.security

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.GeneralSecurityException

class EncryptedConfigStore(context: Context) {
    private val prefs: SharedPreferences = try {
        open(context)
    } catch (e: GeneralSecurityException) {
        // The Keystore key is unusable, so the stored values can never be decrypted. Start empty
        // (the owner re-enters credentials) instead of crashing on every launch.
        context.deleteSharedPreferences(FILE)
        open(context)
    }

    private fun open(context: Context): SharedPreferences {
        val key = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        return EncryptedSharedPreferences.create(
            context, FILE, key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    private companion object { const val FILE = "secrm_secure_prefs" }

    fun put(k: String, v: String) { prefs.edit().putString(k, v).apply() }
    fun get(k: String): String? = prefs.getString(k, null)
    fun remove(k: String) { prefs.edit().remove(k).apply() }

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
}
