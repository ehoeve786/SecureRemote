package com.secrm.plugin

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.secrm.R
import com.secrm.SecureRemoteApp
import com.secrm.core.ssh.SshAuth
import com.secrm.core.ssh.SshClientManager
import com.secrm.core.ssh.tools.AuthMethod
import com.secrm.core.ssh.tools.SavedConnection
import com.secrm.core.ssh.tools.connectSaved
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/**
 * Runs one plugin command on its own SSH session, so the app's live terminal session is untouched.
 * Foreground, so Android doesn't stop it while the macro waits. With [RunSetting.live], its output
 * is also offered to a read-only terminal, opened from the notification.
 */
class RunCommandService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    companion object {
        private const val CHANNEL = "secrm_plugin"
        private const val NOTIF_ID = 7302
        private const val UNLOCK_CHANNEL = "secrm_unlock"
        private const val UNLOCK_NOTIF_ID = 7303
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

        val liveId = if (setting.live) UUID.randomUUID().toString() else null
        val live = liveId?.let { id -> LiveOutput().also { LiveCommands.add(id, it) } }
        startForeground(NOTIF_ID, notification(setting, liveId))
        scope.launch {
            try {
                val (code, vars) = execute(setting, live)
                if (live != null && code != PluginProtocol.RESULT_OK) {
                    live.sink(red = true).write(vars.getString(PluginProtocol.VAR_ERRMSG).orEmpty().toByteArray())
                }
                if (setting.block && fire != null) {
                    runCatching { PluginProtocol.signalFinish(this@RunCommandService, fire, code, vars) }
                }
            } finally {
                // Ends the terminal's output, so it closes once the command has exited.
                live?.finish()
                liveId?.let(LiveCommands::remove)
                stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    /** Connects, runs the command, and returns the result code with the variables to set. */
    private suspend fun execute(s: RunSetting, live: LiveOutput?): Pair<Int, Bundle> {
        val app = application as SecureRemoteApp
        val conn = app.connections.find(s.connection) ?: return failure("No saved connection named ${s.connection}")
        // A separate client, so this doesn't replace or drop the app's live session.
        val ssh = SshClientManager().apply { proxy = app.ssh.proxy }
        return try {
            if (conn.authMethod == AuthMethod.AGENT && !unlockVault(app)) return failure("Bitwarden is locked. Unlock it and run again.")
            val auth = authFor(conn) ?: return failure("No key or vault item for ${conn.name}")
            if (!ssh.connectSaved(conn, auth, app.connections)) return failure("Could not connect to ${conn.host}")
            val r = ssh.execResult(s.command, live?.sink(red = false), live?.sink(red = true))
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

    /**
     * Unlocks the Bitwarden vault if it is locked, via the fingerprint prompt in [UnlockActivity].
     * A background service can't show that prompt itself, so a full-screen notification opens it.
     */
    private suspend fun unlockVault(app: SecureRemoteApp): Boolean {
        if (app.vault.keys != null) return true
        if (!app.vault.isConfigured) return false
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(NotificationChannel(UNLOCK_CHANNEL, "Bitwarden unlock", NotificationManager.IMPORTANCE_HIGH))
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, UnlockActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val done = CompletableDeferred<Boolean>()
        UnlockActivity.waiting = done
        nm.notify(UNLOCK_NOTIF_ID, NotificationCompat.Builder(this, UNLOCK_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_secrm)
            .setContentTitle("Unlock Bitwarden to run the command")
            .setContentText("Tap to unlock with your fingerprint")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(open)
            .setFullScreenIntent(open, true)
            .build())
        return try {
            withTimeoutOrNull(120_000) { done.await() } == true
        } finally {
            nm.cancel(UNLOCK_NOTIF_ID)
            UnlockActivity.waiting = null
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

    private fun notification(s: RunSetting, liveId: String?): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "Plugin commands", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val firstLine = s.command.lineSequence().firstOrNull().orEmpty().take(60)
        val builder = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_secrm)
            .setContentTitle("Running on ${s.connection}")
            .setContentText(if (liveId != null) "Tap to watch: $firstLine" else firstLine)
            .setOngoing(true)
        if (liveId != null) {
            builder.setContentIntent(
                PendingIntent.getActivity(
                    this, liveId.hashCode(),
                    LiveTerminalActivity.intent(this, liveId, s.connection),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            )
        }
        return builder.build()
    }
}
