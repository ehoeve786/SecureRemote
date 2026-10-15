package com.secrm

import android.os.Bundle
import androidx.activity.compose.setContent
import android.graphics.Color
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.DisposableEffect
import androidx.fragment.app.FragmentActivity
import com.secrm.design.SecrmTheme
import com.secrm.design.isDark
import com.secrm.ui.KeystoreRecoveryPrompt
import com.secrm.ui.SecrmNavHost

/** FragmentActivity: required by BiometricPrompt. */
class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A foreground service keeps the tailnet's network alive in the background (and serves Funnel).
        if ((application as SecureRemoteApp).tailscale.enabled) com.secrm.service.McpServerService.start(this)
        val app = application as SecureRemoteApp
        setContent {
            val dark = isDark(app.ui.mode)
            // Transparent system bars, with icons that stay readable on the chosen theme.
            DisposableEffect(dark) {
                val bars = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark }
                enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
                onDispose {}
            }
            SecrmTheme(app.ui.mode, app.ui.dynamicColor) {
                SecrmNavHost(app)
                KeystoreRecoveryPrompt(app)
            }
        }
    }
}
