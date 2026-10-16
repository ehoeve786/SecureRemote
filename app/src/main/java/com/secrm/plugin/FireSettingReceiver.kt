package com.secrm.plugin

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.secrm.SecureRemoteApp

/**
 * Runs the action when MacroDroid/Tasker fires it. Only an ordered broadcast can wait: a blocking
 * action there returns PENDING and [RunCommandService] finishes it with the output variables;
 * otherwise it returns at once. The receiver is exported (the host sends to it), so it runs only
 * settings this app signed when they were saved.
 */
class FireSettingReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != PluginProtocol.ACTION_FIRE) return
        val bundle = intent.getBundleExtra(PluginProtocol.EXTRA_BUNDLE)
        val setting = RunSetting.fromBundle(bundle)
        val config = (context.applicationContext as SecureRemoteApp).config
        if (setting == null || !RunSetting.isSigned(config, bundle, setting)) {
            resultCode = PluginProtocol.RESULT_FAILED
            return
        }
        val waits = setting.block && isOrderedBroadcast
        RunCommandService.start(context, setting.copy(block = waits), intent)
        if (waits) resultCode = PluginProtocol.RESULT_PENDING
    }
}
