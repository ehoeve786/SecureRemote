package com.secrm.plugin

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import java.security.MessageDigest
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

    /**
     * Macro hosts this plugin talks to, each with the SHA-256 digests (lowercase hex) of the signing
     * certificates it must be signed with. Only these may receive results or open the editor.
     */
    val HOST_SIGNERS: Map<String, Set<String>> = mapOf(
        "net.dinglisch.android.taskerm" to setOf("973fe25b9be28fb7436d49582b04277767c852539be31783d134a55621b6636d"), // Tasker
    )

    /** True only if [pkg] is an installed host signed by one of its pinned certificates. The name alone is not enough. */
    fun isGenuineHost(context: Context, pkg: String?): Boolean {
        val pins = HOST_SIGNERS[pkg ?: return false] ?: return false
        return runCatching {
            val info = context.packageManager.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES)
            info.signingInfo?.apkContentsSigners.orEmpty().any { sig ->
                MessageDigest.getInstance("SHA-256").digest(sig.toByteArray()).joinToString("") { "%02x".format(it) } in pins
            }
        }.getOrDefault(false)
    }

    /** Output variables, named as termux-tasker names them so existing macros work unchanged. */
    const val VAR_STDOUT = "%stdout"
    const val VAR_STDERR = "%stderr"
    const val VAR_EXIT = "%result"
    const val VAR_ERR = "%err"
    const val VAR_ERRMSG = "%errmsg"

    private const val EXTRAS = "net.dinglisch.android.tasker.extras."
    const val EXTRA_REQUESTED_TIMEOUT = EXTRAS + "REQUESTED_TIMEOUT"
    private const val EXTRA_HOST_CAPABILITIES = EXTRAS + "HOST_CAPABILITIES"
    private const val CAPABILITY_RELEVANT_VARIABLES = 16
    private const val BUNDLE_RELEVANT_VARIABLES = "net.dinglisch.android.tasker.RELEVANT_VARIABLES"

    /** Whether the host asked for the variable list, shown as its "Variables" dialog. */
    fun hostSupportsRelevantVariables(hostExtras: Bundle?): Boolean =
        ((hostExtras?.getInt(EXTRA_HOST_CAPABILITIES, 0) ?: 0) and CAPABILITY_RELEVANT_VARIABLES) != 0

    /**
     * Declares the output variables for the host's dialog. Each entry is "name", a label line and a
     * description line, which may use simple HTML.
     */
    fun addRelevantVariables(intent: Intent, variables: Array<String>) {
        intent.putExtra(BUNDLE_RELEVANT_VARIABLES, variables)
    }
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
        // The completion intent is text from the sender, so it may only reach the host's own packages.
        // A selector or a target outside them would turn this into an intent redirect or a leak of the output.
        val pkg = completion.getStringExtra(CALL_PACKAGE) ?: completion.component?.packageName ?: completion.`package`
        if (completion.selector != null || !isGenuineHost(context, pkg)) return
        completion.putExtra(EXTRA_RESULT_CODE, resultCode)
        vars?.let { completion.putExtra(EXTRA_VARIABLES, it) }
        val cls = completion.getStringExtra(CALL_SERVICE)
        if (cls != null) {
            completion.component = android.content.ComponentName(pkg, cls)
            if (completion.getBooleanExtra(CALL_FOREGROUND, false)) context.startForegroundService(completion)
            else context.startService(completion)
        } else {
            completion.`package` = pkg
            context.sendBroadcast(completion)
        }
    }
}
