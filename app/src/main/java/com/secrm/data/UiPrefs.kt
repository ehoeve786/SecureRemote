package com.secrm.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.secrm.design.ThemeMode

/** Appearance settings. Compose state, so changing one recolours the app immediately. */
class UiPrefs(context: Context) {
    private val p = context.getSharedPreferences("ui", Context.MODE_PRIVATE)

    var mode by mutableStateOf(runCatching { ThemeMode.valueOf(p.getString("mode", null)!!) }.getOrDefault(ThemeMode.System))
        private set
    var dynamicColor by mutableStateOf(p.getBoolean("dynamic", false))
        private set

    /** Off by default: screenshots, recordings and the recent-apps preview show nothing from the app. */
    var allowScreenshots by mutableStateOf(p.getBoolean("screenshots", false))
        private set

    fun chooseAllowScreenshots(on: Boolean) { allowScreenshots = on; p.edit().putBoolean("screenshots", on).apply() }
    fun chooseMode(m: ThemeMode) { mode = m; p.edit().putString("mode", m.name).apply() }
    fun chooseDynamicColor(on: Boolean) { dynamicColor = on; p.edit().putBoolean("dynamic", on).apply() }
}
