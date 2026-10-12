package com.secrm.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.secrm.SecureRemoteApp
import com.secrm.core.server.http.McpAuth
import com.secrm.core.server.http.McpHttpServer

class McpServerService : Service() {
    companion object {
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
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("Secure Remote Manager")
            .setContentText("MCP server on 127.0.0.1:7823")
            .setOngoing(true).build())

        if (server?.isRunning() == true) return
        val app = application as SecureRemoteApp
        val bearer = app.config.mcpBearerToken.takeIf { !it.isNullOrBlank() }
            ?: java.util.UUID.randomUUID().toString().replace("-", "")
                .also { app.config.mcpBearerToken = it }
        val local = "local-" + bearer.take(8)

        server = McpHttpServer(
            dispatcher = app.dispatcher,
            auth = McpAuth(7823, 7824, local,
                setOf("*.ts.net", "*.trycloudflare.com", "*.ngrok-free.app"), bearer)
        ).also { it.start() }
    }

    private fun stopServer() { server?.stop(); server = null }
    override fun onDestroy() { stopServer(); super.onDestroy() }
    override fun onBind(i: Intent?): IBinder? = null
}
