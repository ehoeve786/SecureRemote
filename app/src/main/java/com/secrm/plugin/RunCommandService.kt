package com.secrm.plugin

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.secrm.SecureRemoteApp
import com.secrm.core.ssh.SshAuth
import com.secrm.core.ssh.SshClientManager
import com.secrm.core.ssh.tools.AuthMethod
import com.secrm.core.ssh.tools.SavedConnection
import com.secrm.core.ssh.tools.connectSaved
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Runs one plugin command on its own SSH session, so the app's live terminal session is untouched.
 * Foreground, so Android doesn't stop it while the macro waits.
 */
class RunCommandService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    companion object {
        private const val CHANNEL = "secrm_plugin"
        private const val NOTIF_ID = 7302
        private const val EXTRA_SETTING = "setting"
        private const val EXTRA_FIRE = "fire"

        fun start(ctx: Context, s: RunSetting, fire: Intent) {
            val i = Intent(ctx, RunCommandService::class.java)
                .putExtra(EXTRA_SETTING, s.toBundle())
                .putExtra(EXTRA_FIRE, fire)
            ContextCompat.startForegroundService(ctx, i)
        }
    }

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, flags: Int, startId: Int): Int {
        val intent = i ?: run { stopSelf(startId); return START_NOT_STICKY }
        val setting = RunSetting.fromBundle(intent.getBundleExtra(EXTRA_SETTING))
        if (setting == null) { stopSelf(startId); return START_NOT_STICKY }
        @Suppress("DEPRECATION")
        val fire: Intent? = intent.getParcelableExtra(EXTRA_FIRE)

        startForeground(NOTIF_ID, notification(setting))
        scope.launch {
            val (code, vars) = execute(setting)
            if (setting.block && fire != null) {
                runCatching { PluginProtocol.signalFinish(this@RunCommandService, fire, code, vars) }
            }
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    /** Connects, runs the command, and returns the result code with the variables to set. */
    private suspend fun execute(s: RunSetting): Pair<Int, Bundle> {
        val app = application as SecureRemoteApp
        val conn = app.connections.find(s.connection) ?: return failure("No saved connection named ${s.connection}")
        // A separate client, so this doesn't replace or drop the app's live session.
        val ssh = SshClientManager().apply { proxy = app.ssh.proxy }
        return try {
            val auth = authFor(conn) ?: return failure("No key or vault item for ${conn.name}")
            if (!ssh.connectSaved(conn, auth, app.connections)) return failure("Could not connect to ${conn.host}")
            val r = ssh.execResult(s.command)
            PluginProtocol.RESULT_OK to Bundle().apply {
                putString(PluginProtocol.VAR_STDOUT, r.stdout)
                putString(PluginProtocol.VAR_STDERR, r.stderr)
                putString(PluginProtocol.VAR_EXIT, r.exitCode.toString())
                putString(PluginProtocol.VAR_ERR, PluginProtocol.RESULT_OK.toString())
            }
        } catch (t: Throwable) {
            failure(t.message ?: "Command failed")
        } finally {
            ssh.shutdown()
        }
    }

    private fun authFor(c: SavedConnection): SshAuth? {
        val repo = (application as SecureRemoteApp).connections
        return when (c.authMethod) {
            AuthMethod.PASSWORD -> SshAuth.Password(c.password)
            AuthMethod.SAVED_KEY -> c.keyId?.let { id ->
                repo.privateKeyFor(id)?.let { SshAuth.Key(it, repo.keyPassphraseFor(id)?.takeIf(String::isNotBlank)) }
            }
            AuthMethod.AGENT -> c.keyId?.let { repo.bitwardenKeyFor(it) }?.let { SshAuth.Key(it, null) }
        }
    }

    private fun failure(message: String): Pair<Int, Bundle> =
        PluginProtocol.RESULT_FAILED to Bundle().apply {
            putString(PluginProtocol.VAR_ERR, PluginProtocol.RESULT_FAILED.toString())
            putString(PluginProtocol.VAR_ERRMSG, message)
        }

    private fun notification(s: RunSetting): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "Plugin commands", NotificationManager.IMPORTANCE_LOW)
            )
        }
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("Running on ${s.connection}")
            .setContentText(s.command.lineSequence().firstOrNull().orEmpty().take(60))
            .setOngoing(true)
            .build()
    }
}
