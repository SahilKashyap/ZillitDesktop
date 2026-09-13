package com.zillit.desktop.feature.esignature.ui.flows

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.esignature.domain.BulkJob
import com.zillit.desktop.feature.esignature.domain.CsvParse
import com.zillit.desktop.feature.esignature.domain.EnvelopeTemplate
import com.zillit.desktop.feature.esignature.ui.BULK_ROW_CAP
import com.zillit.desktop.feature.esignature.ui.BulkSendState
import com.zillit.desktop.feature.esignature.ui.EsignStore
import com.zillit.desktop.feature.esignature.ui.EsignSurface
import com.zillit.desktop.feature.esignature.ui.ParsedCsv
import com.zillit.desktop.feature.esignature.ui.PickPurpose
import com.zillit.desktop.feature.esignature.ui.orFail
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * Bulk send by CSV — the web's `BulkSendModal` and `BulkJobsView`: a
 * template, one row per recipient, one envelope per row; the dashboard
 * polls a running job every two seconds until it settles.
 */
internal class BulkFlow(private val store: EsignStore) {

    private var poller: Job? = null

    fun load() {
        store.update { copy(bulk = bulk.copy(loading = true)) }
        store.runTask {
            when (val jobs = store.repository.bulkJobs()) {
                is ZillitResult.Failure -> {
                    store.update { copy(bulk = bulk.copy(loading = false, loaded = true)) }
                    store.failed(jobs.error.userMessage)
                }
                is ZillitResult.Success -> {
                    store.update {
                        val sorted = jobs.data.sortedByDescending { it.created ?: 0 }
                        copy(bulk = bulk.copy(jobs = sorted, loading = false, loaded = true))
                    }
                    if (jobs.data.any { it.isRunning }) pollWhileRunning()
                }
            }
        }
    }

    /** Refetches while any job is running; stops on its own when none is. */
    private fun pollWhileRunning() {
        if (poller?.isActive == true) return
        poller = store.runTask {
            while (isActive && store.current.surface == EsignSurface.Bulk &&
                store.current.bulk.jobs.any { it.isRunning }
            ) {
                delay(POLL_MS)
                val jobs = store.repository.bulkJobs().getOrNull() ?: continue
                store.update { copy(bulk = bulk.copy(jobs = jobs.sortedByDescending { it.created ?: 0 })) }
                store.current.bulk.open?.let { open -> refreshOpen(open.id) }
            }
        }
    }

    fun openJob(job: BulkJob?) {
        store.update { copy(bulk = bulk.copy(open = job, openLoading = job != null)) }
        job?.let { refreshOpen(it.id) }
    }

    private fun refreshOpen(jobId: String) {
        store.runTask {
            val full = store.repository.bulkJob(jobId).getOrNull()
            store.update {
                val open = if (bulk.open?.id == jobId) full ?: bulk.open else bulk.open
                copy(bulk = bulk.copy(open = open, openLoading = false))
            }
        }
    }

    fun retryFailed(jobId: String) {
        if (store.refusesPost()) return
        store.update { copy(bulk = bulk.copy(busyJobId = jobId)) }
        store.runTask {
            val ok = store.orFail { store.repository.retryFailedRows(jobId) }
            store.update { copy(bulk = bulk.copy(busyJobId = null)) }
            if (ok != null) {
                store.notice("Failed rows re-queued.")
                load()
            }
        }
    }

    fun remindOutstanding(jobId: String) {
        if (store.refusesPost()) return
        store.update { copy(bulk = bulk.copy(busyJobId = jobId)) }
        store.runTask {
            val reminded = store.orFail { store.repository.remindOutstanding(jobId) }
            store.update { copy(bulk = bulk.copy(busyJobId = null)) }
            if (reminded != null) {
                store.notice(if (reminded > 0) "Reminded $reminded recipient(s)." else "Nobody outstanding to remind.")
            }
        }
    }

    // ---------------------------------------------------------------- the send

    fun start(template: EnvelopeTemplate) {
        if (store.refusesPost()) return
        store.update { copy(bulk = bulk.copy(send = BulkSendState(template = template))) }
    }

    fun cancel() = store.update { copy(bulk = bulk.copy(send = null)) }

    fun pickCsv() = store.requestPick(PickPurpose.Csv)

    fun csvPicked(name: String, bytes: ByteArray) {
        val text = bytes.decodeToString()
        val parsed = CsvParse.parse(text)
        val send = store.current.bulk.send ?: return
        val csv = ParsedCsv(parsed.headers, parsed.rows, parsed.delimiter)
        store.update {
            copy(bulk = bulk.copy(send = send.copy(fileName = name, csvText = text, parsed = csv, step = 2)))
        }
        if (!csv.hasRequiredColumns) store.failed("The CSV needs a “name” column and an “email” column.")
    }

    fun step(step: Int) = store.update {
        copy(bulk = bulk.copy(send = bulk.send?.copy(step = step.coerceIn(1, LAST_STEP))))
    }

    fun editBatchName(name: String) = store.update {
        copy(bulk = bulk.copy(send = bulk.send?.copy(batchName = name.take(BATCH_NAME_MAX))))
    }

    /** Ships only the rows the sender approved — invalid ones are dropped, not sent to fail. */
    @Suppress("ReturnCount") // One early return per refusal.
    fun confirm() {
        val send = store.current.bulk.send ?: return
        val parsed = send.parsed ?: return
        if (!parsed.hasRequiredColumns) {
            store.failed("The CSV needs a “name” column and an “email” column.")
            return
        }
        val rows = parsed.validRows()
        if (rows.isEmpty()) {
            store.failed("No sendable rows — every row is missing a valid name or email.")
            return
        }
        if (rows.size > BULK_ROW_CAP) {
            store.failed("CSV exceeds the $BULK_ROW_CAP-row cap. Split into smaller batches.")
            return
        }
        store.update { copy(bulk = bulk.copy(send = send.copy(submitting = true))) }
        store.runTask {
            val clean = CsvParse.serialise(parsed.headers, rows)
            when (val job = store.repository.startBulkSend(send.template.id, clean, send.batchName)) {
                is ZillitResult.Failure -> {
                    store.update { copy(bulk = bulk.copy(send = bulk.send?.copy(submitting = false))) }
                    store.failed(job.error.userMessage)
                }
                is ZillitResult.Success -> {
                    store.update { copy(bulk = bulk.copy(send = null), surface = EsignSurface.Bulk) }
                    val queued = job.data.totalRows.takeIf { it > 0 } ?: rows.size
                    store.notice("Bulk send started — $queued envelopes queued.")
                    load()
                }
            }
        }
    }

    private companion object {
        const val POLL_MS = 2_000L
        const val BATCH_NAME_MAX = 120
        const val LAST_STEP = 3
    }
}
