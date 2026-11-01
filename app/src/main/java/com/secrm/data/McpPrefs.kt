package com.secrm.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** When the MCP server stops by itself. Compose state, so the settings screen updates as the values change. */
class McpPrefs(context: Context) {
    private val p = context.getSharedPreferences("mcp", Context.MODE_PRIVATE)

    /** Minutes with no tool call before the server stops; 0 means never. */
    var idleMinutes by mutableStateOf(p.getInt("idle_minutes", 30))
        private set
    /** Stop the server as soon as the screen turns off. */
    var lockOnScreenOff by mutableStateOf(p.getBoolean("lock_on_screen_off", false))
        private set

    fun chooseIdleMinutes(m: Int) { idleMinutes = m; p.edit().putInt("idle_minutes", m).apply() }
    fun chooseLockOnScreenOff(on: Boolean) { lockOnScreenOff = on; p.edit().putBoolean("lock_on_screen_off", on).apply() }
}
