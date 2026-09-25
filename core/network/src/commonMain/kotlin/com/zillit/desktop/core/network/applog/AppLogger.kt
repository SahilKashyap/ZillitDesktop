package com.zillit.desktop.core.network.applog

import com.zillit.desktop.core.common.ZillitLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** One queued record: the envelope already built, as JSON text. */
data class QueuedLog(val uniqueId: String, val data: String)

/** Where events wait for the server — the phones keep the same table (iOS Realm `ApiErrorPendingEventTaskModel`). */
interface LogQueue {
    suspend fun save(log: QueuedLog)
    suspend fun oldest(limit: Int): List<QueuedLog>
    suspend fun count(): Long
    suspend fun delete(ids: List<String>)

    /** Keeps the newest [keep] rows, so a log endpoint that is down for days cannot fill the disk. */
    suspend fun trim(keep: Int)
}

/** Posts one batch; answers the ids the server took, or null when the call failed. */
fun interface LogSender {
    suspend fun send(records: List<QueuedLog>): List<String>?
}

/**
 * The desktop's `location/log` — a port of iOS `AppAnalyticsEngine`.
 *
 * ## On the spot, with a batching fallback
 *
 * The backend asks for every error to be sent the moment it happens. When the
 * log endpoint itself fails, events are kept and only retried once
 * [CHUNK] of them have accumulated; a successful send switches back to
 * immediate mode. In memory on purpose: a fresh launch tries immediate again.
 *
 * Every event is written to [queue] before it is sent, and a send takes the
 * oldest [CHUNK], so any backlog rides along with the newest event.
 *
 * ## Never logs itself
 *
 * A failure of the log endpoint is never recorded. Without that, immediate
 * mode is an infinite loop (send → fail → log the failure → send …), and even
 * the batching fallback amplifies itself.
 */
class AppLogger(
    private val queue: LogQueue,
    private val sender: LogSender,
    private val identity: LogIdentity,
    private val scope: CoroutineScope,
    private val newId: () -> String,
    private val nowMillis: () -> Long,
    private val isOnline: () -> Boolean = { true },
) {

    @kotlin.concurrent.Volatile
    private var immediate = true

    /** Single flight: two overlapping reads of the same oldest batch would post it twice. */
    private val uploading = Mutex()

    fun log(event: LogEvent) {
        if (event.isAboutTheLogEndpoint()) return
        val record = QueuedLog(
            // A fresh id per event, never derived from the payload: the server
            // upserts on it, so a repeated id silently overwrites the earlier event.
            uniqueId = newId(),
            data = json.encodeToString(JsonObject.serializer(), event.envelope(identity, nowMillis())),
        )
        scope.launch {
            runCatching {
                queue.save(record)
                queue.trim(MAX_QUEUED)
            }.onFailure { ZillitLog.w(TAG) { "could not queue a log event: ${it.message}" } }
            if (!isOnline()) return@launch
            if (immediate || queue.count() >= CHUNK) upload()
        }
    }

    /** Sends whatever is waiting — at start, or when the network comes back. */
    fun flush() {
        scope.launch { if (isOnline()) upload() }
    }

    private suspend fun upload() {
        if (!uploading.tryLock()) return
        try {
            while (true) {
                val batch = runCatching { queue.oldest(CHUNK) }.getOrDefault(emptyList())
                if (batch.isEmpty()) return
                val taken = sender.send(batch)
                if (taken == null) {
                    // The log endpoint is failing: keep storing, and retry only
                    // once a batch has accumulated.
                    immediate = false
                    return
                }
                immediate = true
                val sent = batch.mapTo(HashSet()) { it.uniqueId }
                val acknowledged = taken.filter { it in sent }
                runCatching { queue.delete(acknowledged) }
                // Nothing of this batch acknowledged would re-read the same rows forever.
                if (acknowledged.isEmpty()) return
            }
        } finally {
            uploading.unlock()
        }
    }

    private fun LogEvent.isAboutTheLogEndpoint(): Boolean =
        name.contains(LOG_PATH) || api?.url?.contains(LOG_PATH) == true

    companion object {
        /** The endpoint's own path, matched host-agnostically (dev, QA and prod lcwapi). */
        const val LOG_PATH = "/location/log"

        private const val TAG = "AppLog"
        private const val CHUNK = 10
        private const val MAX_QUEUED = 500
        private val json = Json
    }
}
