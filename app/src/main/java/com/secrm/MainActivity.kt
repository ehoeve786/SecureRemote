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
        // Keep the MCP server up while Funnel publishes it.
        if ((application as SecureRemoteApp).tailscale.run { enabled && public }) com.secrm.service.McpServerService.start(this)
        setContent {
            SecrmTheme { SecrmNavHost(app = application as SecureRemoteApp) }
        }
    }
}
