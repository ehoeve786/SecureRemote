package com.secrm.plugin

import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import com.secrm.SecureRemoteApp
import com.secrm.data.authenticate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

/**
 * Shows the fingerprint prompt for a plugin command that needs a Bitwarden key while the vault is
 * locked. [RunCommandService] opens it from a notification and waits on [waiting] for the result.
 */
class UnlockActivity : FragmentActivity() {
    companion object {
        @Volatile var waiting: CompletableDeferred<Boolean>? = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as SecureRemoteApp
        MainScope().launch {
            val ok = runCatching { app.vault.ensureUnlocked { authenticate() } }.getOrDefault(false)
            waiting?.complete(ok)
            finish()
        }
    }

    override fun onDestroy() {
        waiting?.complete(false) // no-op when the result is already in
        super.onDestroy()
    }
}
