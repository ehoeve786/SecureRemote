package com.secrm.data

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log

/**
 * Fires RUN_COMMAND intents at Termux.
 *
 * Result capture uses a file inside Termux's home (read by the app via
 * /storage/emulated/0 symlink the user creates once, or via a socket
 * relay). No ResultReceiver — that extra is unreliable across Termux
 * versions and can throw BadParcelableException at the caller.
 */
object TermuxExecutor {
    private const val TAG = "TermuxExecutor"
    private const val TERMUX_PKG = "com.termux"
    private const val RUN_COMMAND_SVC = "com.termux.app.RunCommandService"
    private const val ACTION = "com.termux.RUN_COMMAND"
    private const val TERMUX_HOME = "/data/data/com.termux/files/home"
    private const val TERMUX_SH = "/data/data/com.termux/files/usr/bin/bash"

    private const val E_PATH = "com.termux.RUN_COMMAND_PATH"
    private const val E_ARGS = "com.termux.RUN_COMMAND_ARGUMENTS"
    private const val E_WORKDIR = "com.termux.RUN_COMMAND_WORKDIR"
    private const val E_BACKGROUND = "com.termux.RUN_COMMAND_BACKGROUND"
    private const val E_SESSION_ACTION = "com.termux.RUN_COMMAND_SESSION_ACTION"
    private const val E_COMMAND_LABEL = "com.termux.RUN_COMMAND_COMMAND_LABEL"

    fun isInstalled(ctx: Context): Boolean = try {
        ctx.packageManager.getPackageInfo(TERMUX_PKG, 0); true
    } catch (_: PackageManager.NameNotFoundException) { false }

    fun hasPermission(ctx: Context): Boolean = try {
        ctx.checkSelfPermission("com.termux.permission.RUN_COMMAND") ==
                PackageManager.PERMISSION_GRANTED
    } catch (_: Throwable) { false }

    /**
     * Fire-and-forget. Never throws — all failures are logged.
     */
    fun runCommand(ctx: Context, script: String, args: List<String> = emptyList()) {
        safeStart(ctx, buildIntent(script, args, background = true))
    }

    /**
     * Opens Termux with the script in a visible session.
     */
    fun openForeground(ctx: Context, script: String, args: List<String> = emptyList()) {
        safeStart(ctx, buildIntent(script, args, background = false))
    }

    private fun safeStart(ctx: Context, intent: Intent) {
        try {
            ctx.startService(intent)
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to dispatch intent to Termux", t)
        }
    }

    private fun buildIntent(script: String, args: List<String>, background: Boolean): Intent {
        val shArgs = mutableListOf("-c", script)
        if (args.isNotEmpty()) shArgs.add("--")
        shArgs.addAll(args)

        return Intent().apply {
            component = ComponentName(TERMUX_PKG, RUN_COMMAND_SVC)
            action = ACTION
            putExtra(E_PATH, TERMUX_SH)
            putExtra(E_ARGS, shArgs.toTypedArray())
            putExtra(E_WORKDIR, TERMUX_HOME)
            putExtra(E_BACKGROUND, background)
            putExtra(E_SESSION_ACTION, if (background) "2" else "0")
            putExtra(E_COMMAND_LABEL, "secrm")
        }
    }
}
