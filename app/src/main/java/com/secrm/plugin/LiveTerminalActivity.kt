package com.secrm.plugin

import com.secrm.SecureRemoteApp
import com.secrm.ui.applyScreenshotPolicy
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.secrm.core.terminal.TerminalBridge
import com.secrm.core.terminal.TerminalSurface
import com.secrm.design.SecrmTheme
import com.secrm.design.Space

/** Read-only view of a plugin command's output while it runs; closes when the command exits. */
class LiveTerminalActivity : ComponentActivity() {
    companion object {
        private const val EXTRA_ID = "live_id"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_OVERLAY = "overlay"

        /** [overlay]: show as the top half of the screen, over other apps; otherwise full screen. */
        fun intent(ctx: Context, id: String, title: String, overlay: Boolean = false): Intent =
            Intent(ctx, LiveTerminalActivity::class.java)
                .putExtra(EXTRA_ID, id)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_OVERLAY, overlay)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Command output is shown here, so it stays out of screenshots and the recent-apps preview unless allowed in Settings.
        window.applyScreenshotPolicy((application as SecureRemoteApp).ui.allowScreenshots)
        // Gone once taken: a command that already finished, or a screen already open, has nothing to show.
        val output = LiveCommands.take(intent.getStringExtra(EXTRA_ID).orEmpty()) ?: run { finish(); return }
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        val overlay = intent.getBooleanExtra(EXTRA_OVERLAY, false)
        window.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            if (overlay) resources.displayMetrics.heightPixels / 2 else ViewGroup.LayoutParams.MATCH_PARENT,
        )
        window.setGravity(if (overlay) Gravity.TOP else Gravity.CENTER)
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        val bridge = TerminalBridge.watching(output).apply { onEnd = { runOnUiThread { finish() } } }
        setContent {
            SecrmTheme {
                Column(Modifier.fillMaxSize().background(Color(0xFF111111)).windowInsetsPadding(WindowInsets.safeDrawing)) {
                    Text(
                        "Running on $title",
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                        modifier = Modifier.padding(Space.md),
                    )
                    // Read-only: the soft keyboard stays hidden.
                    TerminalSurface(bridge, modifier = Modifier.weight(1f).fillMaxWidth(), keyboard = false)
                }
            }
        }
    }
}
