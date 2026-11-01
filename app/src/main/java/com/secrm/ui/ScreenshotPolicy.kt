package com.secrm.ui

import android.view.Window
import android.view.WindowManager

/** Blocks screenshots, recordings and the recent-apps preview of this window, unless the owner allowed them. */
fun Window.applyScreenshotPolicy(allowed: Boolean) {
    if (allowed) clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    else addFlags(WindowManager.LayoutParams.FLAG_SECURE)
}
