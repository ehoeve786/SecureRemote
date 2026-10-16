package com.secrm.plugin

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import com.secrm.core.terminal.TerminalBridge
import com.secrm.core.terminal.TerminalSurface
import com.secrm.design.SecrmTheme
import com.secrm.design.Space

/** Read-only view of a plugin command's output while it runs; closes when the command exits. */
class LiveTerminalActivity : ComponentActivity() {
    companion object {
        private const val EXTRA_ID = "live_id"
        private const val EXTRA_TITLE = "title"

        fun intent(ctx: Context, id: String, title: String): Intent =
            Intent(ctx, LiveTerminalActivity::class.java)
                .putExtra(EXTRA_ID, id)
                .putExtra(EXTRA_TITLE, title)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Gone once taken: a command that already finished, or a screen already open, has nothing to show.
        val output = LiveCommands.take(intent.getStringExtra(EXTRA_ID).orEmpty()) ?: run { finish(); return }
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        val bridge = TerminalBridge.watching(output).apply { onEnd = { runOnUiThread { finish() } } }
        setContent {
            SecrmTheme {
                Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                    Text(
                        "Running on $title",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(Space.md),
                    )
                    TerminalSurface(bridge, modifier = Modifier.weight(1f).fillMaxWidth())
                }
            }
        }
    }
}
