package com.secrm.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.secrm.R
import com.secrm.SecureRemoteApp
import com.secrm.core.server.http.McpAuth
import com.secrm.core.server.http.McpHttpServer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class McpServerService : Service() {
    companion object {
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
    }

    private var server: McpHttpServer? = null

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
            ?: java.util.UUID.randomUUID().toString().replace("-", "")
                .also { app.config.mcpBearerToken = it }
        val local = app.config.mcpLocalToken.takeIf { !it.isNullOrBlank() }
            ?: ByteArray(32).also(java.security.SecureRandom()::nextBytes)
                .joinToString("") { "%02x".format(it) }
                .also { app.config.mcpLocalToken = it }

        server = McpHttpServer(
            dispatcher = app.dispatcher,
            auth = McpAuth(7823, 7824, local,
                setOf("*.ts.net")) { it == bearer || app.oauth.isValid(it) },
            oauth = app.oauth
        ).also { it.start() }
        _running.value = true
    }

    private fun stopServer() { server?.stop(); server = null; _running.value = false }
    override fun onDestroy() { stopServer(); super.onDestroy() }
    override fun onBind(i: Intent?): IBinder? = null
}
