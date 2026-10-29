package com.secrm.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.secrm.R
import com.secrm.SecureRemoteApp
import com.secrm.core.ssh.SshAuth
import com.secrm.core.ssh.tools.AuthMethod
import com.secrm.core.ssh.tools.Redact
import com.secrm.core.ssh.tools.SavedConnection
import com.secrm.core.ssh.tools.connectSaved
import com.secrm.data.Job
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.withTimeout

/** Runs the due scheduled jobs and notifies the owner. Scheduled by [JobScheduling]. */
class JobWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as SecureRemoteApp
        val now = System.currentTimeMillis()
        for (job in app.jobs.list()) {
            if (now - job.lastRun < job.intervalMinutes * 60_000L) continue
            val (ok, summary) = runJob(app, job)
            app.jobs.recordRun(job.id, now, summary)
            if (!ok || job.notifyAlways) notify(job, ok, summary)
        }
        return Result.success()
    }

    /** Connects like the owner's app does (pinned host key, no network search) and runs the command once. */
    private suspend fun runJob(app: SecureRemoteApp, job: Job): Pair<Boolean, String> {
        val conn: SavedConnection = app.connections.find(job.connection)
            ?: return false to "Connection '${job.connection}' no longer exists"
        val auth = authFor(app, conn) ?: return false to "Credentials are missing for ${conn.name}"
        val client = app.newClient()
        return try {
            if (!client.connectSaved(conn, auth, app.connections, allowRediscover = false)) {
                return false to "Could not connect to ${conn.host}"
            }
            val r = withTimeout(JOB_TIMEOUT_MS) { client.execResult(job.command) }
            val output = Redact.text(r.stdout + r.stderr, Redact.secretsOf(auth)).trim()
            (r.exitCode == 0) to "exit ${r.exitCode}" + if (output.isEmpty()) "" else ": ${output.take(200)}"
        } catch (t: Exception) {
            false to Redact.text(t.message ?: "job failed", Redact.secretsOf(auth))
        } finally {
            client.shutdown()
        }
    }

    private fun authFor(app: SecureRemoteApp, conn: SavedConnection): SshAuth? = when (conn.authMethod) {
        AuthMethod.PASSWORD -> SshAuth.Password(conn.password)
        AuthMethod.SAVED_KEY -> {
            val keyId = conn.keyId ?: return null
            val pem = app.connections.privateKeyFor(keyId) ?: return null
            SshAuth.Key(pem, app.connections.keyPassphraseFor(keyId)?.takeIf { it.isNotBlank() })
        }
    }

    private fun notify(job: Job, ok: Boolean, summary: String) {
        val ctx = applicationContext
        ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Scheduled jobs", NotificationManager.IMPORTANCE_DEFAULT)
        )
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_secrm)
            .setContentTitle(if (ok) "${job.name}: ok" else "${job.name}: failed")
            .setContentText("${job.connection}: $summary")
            .setStyle(NotificationCompat.BigTextStyle().bigText("${job.connection}: $summary"))
            .setAutoCancel(true)
            .build()
        // Without the notification permission the result is still recorded in the job list.
        runCatching { NotificationManagerCompat.from(ctx).notify(job.id.hashCode(), n) }
    }

    companion object {
        const val CHANNEL = "secrm_jobs"
        private const val JOB_TIMEOUT_MS = 120_000L
    }
}

/** Keeps one periodic worker queued while any job exists, and cancels it when none does. */
object JobScheduling {
    private const val NAME = "secrm_jobs"

    fun sync(ctx: Context, anyJobs: Boolean) {
        val wm = WorkManager.getInstance(ctx)
        if (!anyJobs) { wm.cancelUniqueWork(NAME); return }
        val request = PeriodicWorkRequestBuilder<JobWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        wm.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }
}
