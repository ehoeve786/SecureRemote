package com.secrm

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.fragment.app.FragmentActivity
import com.secrm.design.SecrmTheme
import com.secrm.ui.SecrmNavHost

/** FragmentActivity: required by BiometricPrompt. */
class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SecrmTheme { SecrmNavHost(app = application as SecureRemoteApp) }
        }
    }
}
