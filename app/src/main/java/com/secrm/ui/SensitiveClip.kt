package com.secrm.ui

import android.content.ClipData
import android.os.PersistableBundle

/** A clip the system keeps out of clipboard previews (Android 13+ reads the flag; older versions ignore it). */
fun sensitiveClip(label: String, text: String): ClipData =
    ClipData.newPlainText(label, text).apply {
        description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
    }
