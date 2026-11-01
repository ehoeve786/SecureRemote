package com.secrm.data

import com.secrm.core.security.EncryptedConfigStore
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * A command the app runs on a saved host on a schedule. A health check is a job whose command exits non-zero
 * when something is wrong; the job notifies on a failure, or on every run if [notifyAlways].
 */
@Serializable
data class Job(
    val id: String,
    val name: String,
    val connection: String,
    val command: String,
    val intervalMinutes: Long,
    val notifyAlways: Boolean = false,
    val lastRun: Long = 0,
    val lastResult: String = "",
)

class JobStore(private val store: EncryptedConfigStore) {
    @Synchronized
    fun list(): List<Job> = store.get(KEY)?.let {
        runCatching { json.decodeFromString(ListSerializer(Job.serializer()), it) }.getOrNull()
    }.orEmpty()

    @Synchronized
    fun add(name: String, connection: String, command: String, intervalMinutes: Long, notifyAlways: Boolean) =
        write(list() + Job(UUID.randomUUID().toString(), name.trim().take(60), connection, command,
            intervalMinutes.coerceAtLeast(15), notifyAlways))

    @Synchronized
    fun delete(id: String) = write(list().filterNot { it.id == id })

    /** Jobs name their host, so a renamed host takes its jobs with it. */
    @Synchronized
    fun renameConnection(from: String, to: String) = write(list().map { if (it.connection == from) it.copy(connection = to) else it })

    @Synchronized
    fun recordRun(id: String, at: Long, result: String) = write(list().map {
        if (it.id == id) it.copy(lastRun = at, lastResult = result.take(300)) else it
    })

    private fun write(items: List<Job>) = store.put(KEY, json.encodeToString(ListSerializer(Job.serializer()), items))

    private companion object {
        const val KEY = "jobs"
        val json = Json { ignoreUnknownKeys = true }
    }
}
