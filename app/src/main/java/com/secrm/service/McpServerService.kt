package com.secrm.service

import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.secrm.R
import com.secrm.SecureRemoteApp
import com.secrm.core.server.http.McpAuth
import com.secrm.core.server.http.McpHttpServer
import android.util.Log
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class McpServerService : Service() {
    companion object {
        private const val TAG = "McpServerService"
        private val _running = MutableStateFlow(false)
        /** Whether the server is up; the settings screen shows this. */
        val running: StateFlow<Boolean> = _running.asStateFlow()
        private const val CHANNEL = "secrm_mcp"
        private const val NOTIF_ID = 7301
        private const val START = "start"
        private const val STOP = "stop"
        fun start(ctx: Context) {
            val i = Intent(ctx, McpServerService::class.java).setAction(START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
            else ctx.startService(i)
        }
        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, McpServerService::class.java).setAction(STOP))
        }

        private val lastActivity = AtomicLong(System.currentTimeMillis())
        private const val IDLE_CHECK_MS = 60_000L

        /** Called on every tool call; the idle timer counts from the latest one. */
        fun noteActivity() = lastActivity.set(System.currentTimeMillis())
    }

    private var server: McpHttpServer? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var idleJob: Job? = null
    private var screenReceiverOn = false

    /** Stops the server when the screen turns off, if the owner chose that. */
    private val screenOff = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if ((application as SecureRemoteApp).mcp.lockOnScreenOff) stopAndQuit()
        }
    }

    override fun onStartCommand(i: Intent?, f: Int, s: Int): Int {
        when (i?.action) {
            START -> startServer()
            STOP -> { stopServer(); stopSelf() }
        }
        return START_STICKY
    }

    private fun startServer() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "MCP server", NotificationManager.IMPORTANCE_LOW)
            )
        }
        startForeground(NOTIF_ID, NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_secrm)
            .setContentTitle("Secure Remote Manager")
            .setContentText("MCP server and private network running")
            .setOngoing(true).build())

        if (server?.isRunning() == true) return
        val app = application as SecureRemoteApp
        val bearer = app.config.mcpBearerToken.takeIf { !it.isNullOrBlank() }
            ?: newSecret().also { app.config.mcpBearerToken = it }
        val local = app.config.mcpLocalToken.takeIf { !it.isNullOrBlank() }
            ?: newSecret().also { app.config.mcpLocalToken = it }

        // Read on every request, so a rotated token takes effect without a restart.
        val remote: (String) -> Boolean = { presented ->
            val expected = app.config.mcpBearerToken.orEmpty()
            (expected.isNotEmpty() && MessageDigest.isEqual(presented.toByteArray(), expected.toByteArray())) ||
                app.oauth.isValid(presented)
        }
        val started = McpHttpServer(
            dispatcher = app.dispatcher,
            // Only this node's own public name: the tailnet suffix would admit any other node on it.
            auth = McpAuth(7823, 7824, local, {
                (app.tailscale.state.value as? com.secrm.core.tunnel.Tunnel.State.Up)?.name?.let { setOf(it.lowercase()) }.orEmpty()
            }, remote),
            oauth = app.oauth
        )
        try {
            started.start()
        } catch (e: Exception) {
            // Typically a port already in use. Stop cleanly rather than crash the app.
            Log.e(TAG, "MCP server did not start: ${e.message}", e)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        server = started
        _running.value = true
        noteActivity()
        startIdleWatch()
        registerReceiver(screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF))
        screenReceiverOn = true
    }

    private fun startIdleWatch() {
        idleJob?.cancel()
        idleJob = scope.launch {
            while (true) {
                delay(IDLE_CHECK_MS)
                val idle = (application as SecureRemoteApp).mcp.idleMinutes
                if (idle > 0 && System.currentTimeMillis() - lastActivity.get() > idle * 60_000L) {
                    Log.i(TAG, "MCP server idle for $idle min; stopping")
                    stopAndQuit()
                    return@launch
                }
            }
        }
    }

    private fun stopAndQuit() {
        stopServer()
        stopSelf()
    }

    private fun newSecret() = ByteArray(32).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) }

    private fun stopServer() {
        idleJob?.cancel(); idleJob = null
        if (screenReceiverOn) { runCatching { unregisterReceiver(screenOff) }; screenReceiverOn = false }
        server?.stop(); server = null
        _running.value = false
    }

    override fun onDestroy() { stopServer(); scope.cancel(); super.onDestroy() }
    override fun onBind(i: Intent?): IBinder? = null
}
