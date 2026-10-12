package com.secrm

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.secrm.design.SecrmTheme
import com.secrm.ui.SecrmNavHost

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SecrmTheme { SecrmNavHost(app = application as SecureRemoteApp) }
        }
    }
}
