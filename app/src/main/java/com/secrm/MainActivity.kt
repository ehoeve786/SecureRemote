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
        // A foreground service keeps the tailnet's network alive in the background (and serves Funnel).
        if ((application as SecureRemoteApp).tailscale.enabled) com.secrm.service.McpServerService.start(this)
        setContent {
            SecrmTheme { SecrmNavHost(app = application as SecureRemoteApp) }
        }
    }
}
