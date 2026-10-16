package com.secrm.plugin

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Runs the action when MacroDroid/Tasker fires it. A blocking action returns PENDING and is finished
 * by [RunCommandService] with the output variables; a non-blocking one returns at once.
 */
class FireSettingReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != PluginProtocol.ACTION_FIRE) return
        val setting = RunSetting.fromBundle(intent.getBundleExtra(PluginProtocol.EXTRA_BUNDLE))
        if (setting == null) {
            resultCode = PluginProtocol.RESULT_FAILED
            return
        }
        RunCommandService.start(context, setting, intent)
        if (setting.block) resultCode = PluginProtocol.RESULT_PENDING
    }
}
