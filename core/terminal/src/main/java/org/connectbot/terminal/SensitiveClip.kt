package org.connectbot.terminal

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.PersistableBundle

/**
 * Copies [text] to the system clipboard with the sensitive flag, so clipboard previews do not show it.
 * Terminal output can hold secrets, so every copy from here goes through this.
 */
internal fun copySensitive(context: Context, label: String, text: String) {
    val clip = ClipData.newPlainText(label, text).apply {
        description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
    }
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
}
