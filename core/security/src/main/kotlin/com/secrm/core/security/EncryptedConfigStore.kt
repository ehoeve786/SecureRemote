package com.secrm.core.security

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class EncryptedConfigStore(context: Context) {
    private val key = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
    private val prefs = EncryptedSharedPreferences.create(
        context, "secrm_secure_prefs", key,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )
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
    var mcpBearerToken: String?
        get() = get("mcp.tok"); set(v) = put("mcp.tok", v ?: "")
}
