package com.zillit.desktop.feature.dealmemo.ui.builder

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.dealmemo.domain.SavedRecord
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject

/** What the autosave engine needs from its page. */
internal interface AutosaveHost {

    /** The master gate: still loading, a cancelled deal, or no page at all turn it off. */
    val autosaveEnabled: Boolean

    /** The whole payload as it stands, or null when there is nothing to save. */
    fun autosavePayload(): JsonObject?

    /** The server-confirmed id — never a minted one, or the first tick PATCHes a 404. */
    fun autosaveServerId(): String?

    /** Creates the record; `Success(null)` means the page declined (the draft was discarded). */
    suspend fun autosaveCreate(payload: JsonObject): ZillitResult<SavedRecord?>

    suspend fun autosaveUpdate(payload: JsonObject): ZillitResult<Unit>

    fun onAutosave(ui: AutosaveUi)

    fun launch(block: suspend CoroutineScope.() -> Unit): Job

    fun now(): Long
}

/**
 * `useDealAutosave`: 5 s after the last edit, or 30 s into continuous typing,
 * the page saves itself — skipping a payload identical to the last one the
 * server confirmed, creating once, and letting a newer update supersede an
 * older one still in flight.
 */
internal class BuilderAutosave(private val host: AutosaveHost) {

    private var baseline: String? = null
    private var idleJob: Job? = null
    private var maxJob: Job? = null
    private var inFlight: Job? = null
    private var creating = false
    private var disarmed = false
    private var created: CompletableDeferred<SavedRecord?>? = null
    private var ui = AutosaveUi()

    /** A user edit: restart the idle timer, and start — never restart — the ceiling. */
    fun arm() {
        if (!host.autosaveEnabled || disarmed) return
        if (ui.status != AutosaveStatus.Saving) publish(ui.copy(status = AutosaveStatus.Pending))
        idleJob?.cancel()
        idleJob = host.launch {
            delay(IDLE_MILLIS)
            host.launch { flush() }
        }
        if (maxJob == null) {
            maxJob = host.launch {
                delay(MAX_WAIT_MILLIS)
                host.launch { flush() }
            }
        }
    }

    /** The save itself. Answers whether the payload is now on the server. */
    suspend fun flush(): Boolean {
        if (!host.autosaveEnabled || disarmed) return false
        val payload = host.autosavePayload() ?: return false
        val serialized = payload.toString()
        if (serialized == baseline) {
            clearTimers()
            if (ui.status == AutosaveStatus.Pending) publish(ui.copy(status = AutosaveStatus.Idle))
            return true
        }
        return if (host.autosaveServerId() == null) create(payload, serialized) else update(payload, serialized)
    }

    private suspend fun create(payload: JsonObject, serialized: String): Boolean {
        if (creating) return false
        creating = true
        clearTimers()
        publish(ui.copy(status = AutosaveStatus.Saving))
        val settled = CompletableDeferred<SavedRecord?>()
        created = settled
        return try {
            when (val result = host.autosaveCreate(payload)) {
                is ZillitResult.Success -> {
                    settled.complete(result.data)
                    baseline = serialized
                    publish(AutosaveUi(AutosaveStatus.Saved, host.now()))
                    true
                }
                is ZillitResult.Failure -> {
                    settled.complete(null)
                    publish(ui.copy(status = AutosaveStatus.Error))
                    false
                }
            }
        } finally {
            if (!settled.isCompleted) settled.complete(null)
            creating = false
        }
    }

    /** A newer update cancels the one in flight; the superseded save leaves the status to its successor. */
    private suspend fun update(payload: JsonObject, serialized: String): Boolean {
        inFlight?.cancel()
        clearTimers()
        publish(ui.copy(status = AutosaveStatus.Saving))
        var saved = false
        val job = host.launch {
            when (host.autosaveUpdate(payload)) {
                is ZillitResult.Success -> {
                    baseline = serialized
                    saved = true
                    publish(AutosaveUi(AutosaveStatus.Saved, host.now()))
                }
                is ZillitResult.Failure -> publish(ui.copy(status = AutosaveStatus.Error))
            }
        }
        inFlight = job
        job.join()
        if (inFlight === job) inFlight = null
        return saved
    }

    /** After a load or an explicit save: this payload is what the server has, so an unchanged page never saves. */
    fun markSaved(payload: JsonObject?) {
        baseline = payload?.toString()
        clearTimers()
        publish(ui.copy(status = AutosaveStatus.Idle))
    }

    /** A permanent stop, for a page deleting the record it has been saving — no final flush either. */
    fun disarm() {
        disarmed = true
        clearTimers()
    }

    /** The create on the wire when this is called, once it lands — null when none ran or it failed. */
    suspend fun settleCreate(): SavedRecord? = created?.await()

    /** Leaving the page: one last save when enabled, not awaited. */
    fun flushOnExit() {
        clearTimers()
        if (host.autosaveEnabled && !disarmed) host.launch { flush() }
    }

    /** A fresh page: nothing saved, nothing pending. */
    fun reset() {
        clearTimers()
        baseline = null
        inFlight = null
        creating = false
        disarmed = false
        created = null
        ui = AutosaveUi()
    }

    private fun clearTimers() {
        idleJob?.cancel()
        maxJob?.cancel()
        idleJob = null
        maxJob = null
    }

    private fun publish(next: AutosaveUi) {
        ui = next
        host.onAutosave(next)
    }

    companion object {
        const val IDLE_MILLIS = 5_000L
        const val MAX_WAIT_MILLIS = 30_000L
    }
}
