package com.secrm.plugin

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Runs the action when MacroDroid/Tasker fires it. Only an ordered broadcast can wait: a blocking
 * action there returns PENDING and [RunCommandService] finishes it with the output variables;
 * otherwise it returns at once.
 */
class FireSettingReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != PluginProtocol.ACTION_FIRE) return
        val setting = RunSetting.fromBundle(intent.getBundleExtra(PluginProtocol.EXTRA_BUNDLE))
        if (setting == null) {
            resultCode = PluginProtocol.RESULT_FAILED
            return
        }
        val waits = setting.block && isOrderedBroadcast
        RunCommandService.start(context, setting.copy(block = waits), intent)
        if (waits) resultCode = PluginProtocol.RESULT_PENDING
    }
}
