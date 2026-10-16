package com.secrm.plugin

import android.content.Context
import android.content.Intent
import android.os.Bundle

/**
 * The Tasker/MacroDroid action-plugin protocol this app speaks. Written from the public protocol
 * (Locale intents plus Tasker extras), not from Tasker's GPL sample code.
 */
object PluginProtocol {
    const val ACTION_EDIT = "com.twofortyfouram.locale.intent.action.EDIT_SETTING"
    const val ACTION_FIRE = "com.twofortyfouram.locale.intent.action.FIRE_SETTING"
    const val EXTRA_BUNDLE = "com.twofortyfouram.locale.intent.extra.BUNDLE"
    const val EXTRA_BLURB = "com.twofortyfouram.locale.intent.extra.BLURB"

    /** Result codes: OK is Activity.RESULT_OK; the rest are RESULT_FIRST_USER + n. */
    const val RESULT_OK = -1
    const val RESULT_FAILED = 2
    const val RESULT_PENDING = 3

    /** Asks the host to wait this long (ms) for the action; the host may ignore it. */
    const val REQUESTED_TIMEOUT_NEVER = 3_600_000

    /** Output variables, named as termux-tasker names them so existing macros work unchanged. */
    const val VAR_STDOUT = "%stdout"
    const val VAR_STDERR = "%stderr"
    const val VAR_EXIT = "%result"
    const val VAR_ERR = "%err"
    const val VAR_ERRMSG = "%errmsg"

    private const val EXTRAS = "net.dinglisch.android.tasker.extras."
    const val EXTRA_REQUESTED_TIMEOUT = EXTRAS + "REQUESTED_TIMEOUT"
    private const val EXTRA_COMPLETION_INTENT = EXTRAS + "COMPLETION_INTENT"
    private const val EXTRA_RESULT_CODE = EXTRAS + "RESULT_CODE"
    private const val EXTRA_VARIABLES = EXTRAS + "VARIABLES"
    private const val CALL_PACKAGE = "net.dinglisch.android.tasker.EXTRA_CALL_SERVICE_PACKAGE"
    private const val CALL_SERVICE = "net.dinglisch.android.tasker.EXTRA_CALL_SERVICE"
    private const val CALL_FOREGROUND = "net.dinglisch.android.tasker.EXTRA_CALL_SERVICE_FOREGROUND"

    /**
     * Ends a blocking action: sends [resultCode] and [vars] to the completion intent the host put in
     * the fire intent. The host must still be waiting (it was told RESULT_PENDING).
     */
    fun signalFinish(context: Context, fire: Intent, resultCode: Int, vars: Bundle?) {
        val completion = Intent.parseUri(fire.getStringExtra(EXTRA_COMPLETION_INTENT) ?: return, Intent.URI_INTENT_SCHEME)
        completion.putExtra(EXTRA_RESULT_CODE, resultCode)
        vars?.let { completion.putExtra(EXTRA_VARIABLES, it) }
        val pkg = completion.getStringExtra(CALL_PACKAGE)
        val cls = completion.getStringExtra(CALL_SERVICE)
        if (pkg != null && cls != null) {
            completion.component = android.content.ComponentName(pkg, cls)
            if (completion.getBooleanExtra(CALL_FOREGROUND, false)) context.startForegroundService(completion)
            else context.startService(completion)
        } else {
            context.sendBroadcast(completion)
        }
    }
}
