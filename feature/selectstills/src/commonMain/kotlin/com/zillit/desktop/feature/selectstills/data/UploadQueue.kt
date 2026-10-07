@file:Suppress("TooManyFunctions", "MaxLineLength", "LongMethod", "CyclomaticComplexMethod", "ReturnCount", "LoopWithTooManyJumpStatements", "MagicNumber")
// One pipeline, spelled out stage by stage; see the class note. Its loops leave
// early for the four things that stop an upload — cancelled, another
// production, a stopper from the service, nothing left to send — and each exit
// is the point of the stage it is in.

package com.zillit.desktop.feature.selectstills.data

import com.zillit.desktop.core.common.ZillitLog
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.selectstills.domain.StillsPick
import com.zillit.desktop.feature.selectstills.domain.StillsRepository
import com.zillit.desktop.feature.selectstills.domain.UploadDeclaration
import com.zillit.desktop.feature.selectstills.domain.screenPicks
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Where one file has got to. */
enum class FileStatus {
    Waiting,
    Signing,
    Uploading,

    /** In storage, not yet confirmed to the service. */
    Uploaded,

    /** Confirmed: queued for processing. */
    Done,

    /** This photo is already in the production. */
    Duplicate,

    /** Not a photo the service takes. */
    Refused,
    Failed,
    Cancelled,
    ;

    /** Still on its way: what "an upload is running" means. */
    val isActive: Boolean get() = this == Waiting || this == Signing || this == Uploading

    /** In storage, whether or not the service has been told yet. */
    val hasArrived: Boolean get() = this == Uploaded || this == Done
}

/** One file in the queue. */
data class UploadItem(
    /** This row's identity on screen. */
    val ref: String,
    /**
     * The id the service keys the photo by. Asking again with the same one
     * returns the same photo, so a retried request never makes a second one.
     */
    val uniqueId: String,
    val order: Int,
    val name: String,
    val size: Long,
    val type: String,
    val path: String,
    val status: FileStatus,
    val progress: Float = 0f,
    /** A message key, or one of this screen's own reasons. */
    val reason: String = "",
    val photoId: String = "",
    val duplicateOf: String = "",
    val allowDuplicate: Boolean = false,
    val batchId: String = "",
)

/** The whole queue as the panel and the top bar read it. */
data class UploadQueueState(
    val items: List<UploadItem> = emptyList(),
    val projectId: String? = null,
    val batchId: String = "",
    val shootLabel: String = "",
    val running: Boolean = false,
    /** Why everything stopped: `cancelled`, `project`, or a refusal's message key. */
    val stopped: String = "",
    /**
     * The last attempt could not reach the service at all.
     *
     * The web reads the browser's own `navigator.onLine`; there is no such
     * switch here, so this is the honest equivalent — a request that never
     * arrived. It clears the moment one does, and the upload carries on by
     * itself either way.
     */
    val offline: Boolean = false,
)

/** Totals for the progress bar and the pill in the top bar. */
data class UploadCounts(
    val total: Int = 0,
    val active: Int = 0,
    val done: Int = 0,
    val failed: Int = 0,
    val duplicate: Int = 0,
    val refused: Int = 0,
    val cancelled: Int = 0,
    val bytes: Long = 0,
    val sent: Long = 0,
) {
    /** What the bar fills to, 0..100. */
    val percent: Int get() = if (bytes > 0) ((sent * HUNDRED) / bytes).toInt().coerceIn(0, HUNDRED) else HUNDRED

    /** Files that count towards "uploaded x of y" — the turned-away ones never will be. */
    val expected: Int get() = total - refused - duplicate

    private companion object {
        const val HUNDRED = 100
    }
}

fun countsOf(items: List<UploadItem>): UploadCounts {
    var active = 0
    var done = 0
    var failed = 0
    var duplicate = 0
    var refused = 0
    var cancelled = 0
    var bytes = 0L
    var sent = 0L
    items.forEach { item ->
        when {
            item.status.isActive -> active += 1
            item.status.hasArrived -> done += 1
            item.status == FileStatus.Failed -> failed += 1
            item.status == FileStatus.Duplicate -> duplicate += 1
            item.status == FileStatus.Refused -> refused += 1
            item.status == FileStatus.Cancelled -> cancelled += 1
            else -> Unit
        }
        if (item.status == FileStatus.Refused || item.status == FileStatus.Duplicate) return@forEach
        bytes += item.size
        sent += if (item.status.hasArrived) item.size else (item.size * item.progress).toLong()
    }
    return UploadCounts(items.size, active, done, failed, duplicate, refused, cancelled, bytes, sent)
}

/** How many were queued, and how many were turned away here. */
data class QueueOutcome(val added: Int, val refused: Int)

/** Timings and sizes, in one place so tests can shrink them. */
data class UploadTuning(
    val signChunk: Int = 10,
    val lanes: Int = 3,
    val putAttempts: Int = 3,
    val confirmEvery: Int = 5,
    val retryMs: List<Long> = listOf(1000, 3000),
    val signRetryMs: List<Long> = listOf(2000, 5000, 15_000),
)

/**
 * Photo uploads in flight.
 *
 * ## Why this outlives every screen
 *
 * A card of stills is hundreds of large files and takes a while. The upload
 * must keep going while the photographer looks at the gallery, names faces or
 * leaves the tool for another part of the app. So one of these is built per
 * production graph and handed to the view model, rather than held in a screen
 * — the web keeps it in a module-scope store for exactly the same reason.
 *
 * ## The pipeline, per file
 *
 *   Waiting → Signing → Uploading → Uploaded → Done
 *
 * Links are asked for a few files at a time, just before they are used: a link
 * lasts 15 minutes, and a queue of 500 files takes longer than that. Three
 * files travel at once. A file that arrived is confirmed to the service every
 * few files — not at the end — so closing the app after 300 of 500 loses
 * nothing: those 300 are already queued for processing. (And if the confirm
 * never gets sent, the service picks an arrived file up by itself.)
 *
 * ## Pinned to its production
 *
 * Every call carries the production the upload started in, and the queue stops
 * for good the moment the app is in another one: a photo must never land in
 * the production next door.
 *
 * ## What is retried
 *
 * The PUT is retried: same link, same bytes, nothing to break. A 403 from
 * storage means the link ran out, so a new one is asked for — the file's
 * unique id gets the same photo back, never a second one. Asking for links is
 * retried only when the request never reached the service.
 */
class StillsUploadQueue(
    private val repository: StillsRepository,
    private val uploader: StillsUploader,
    private val files: StillsFileReader,
    /** The production that is open right now. */
    private val openProject: () -> String?,
    private val newId: () -> String,
    private val tuning: UploadTuning = UploadTuning(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob()),
) {

    private val _state = MutableStateFlow(UploadQueueState())
    val state: StateFlow<UploadQueueState> = _state.asStateFlow()

    /** Not on screen, so not in the state. */
    private var runner: Job? = null
    private var cancelled = false
    private val unconfirmed = mutableListOf<String>()
    private val confirmLock = Mutex()

    private val items get() = _state.value.items

    /**
     * Queue files for upload and start.
     *
     * @param types the types the service takes, from `GET /me` → upload
     * @param maxBytes the largest file it takes
     */
    fun add(picks: List<StillsPick>, shootLabel: String, types: List<String>, maxBytes: Long): QueueOutcome {
        val project = openProject()
        var existing = items
        var batch = _state.value.batchId
        // Another production now: the old list is not this one's to show.
        if (_state.value.projectId != project) {
            cancelAll("project", quiet = true)
            existing = emptyList()
            batch = ""
            unconfirmed.clear()
        }
        if (batch.isBlank() || existing.none { it.status.isActive }) batch = newId()

        val screened = screenPicks(picks, types, maxBytes)
        val start = existing.count { it.batchId == batch && it.uniqueId.isNotBlank() }
        val fresh = screened.ok.mapIndexed { index, pick ->
            UploadItem(
                ref = newId(),
                uniqueId = newId(),
                order = start + index,
                name = pick.name,
                size = pick.size,
                type = pick.type,
                path = pick.path,
                status = FileStatus.Waiting,
                batchId = batch,
            )
        } + screened.refused.map { bad ->
            UploadItem(
                ref = newId(),
                uniqueId = "",
                order = 0,
                name = bad.name,
                size = bad.size,
                type = "",
                path = "",
                status = FileStatus.Refused,
                reason = bad.reason.wire,
                batchId = batch,
            )
        }

        val label = shootLabel.replace(WHITESPACE, " ").trim().take(MAX_LABEL)
        _state.update {
            it.copy(
                items = existing + fresh,
                batchId = batch,
                projectId = project,
                shootLabel = label,
                stopped = "",
            )
        }
        if (screened.ok.isNotEmpty()) run()
        return QueueOutcome(screened.ok.size, screened.refused.size)
    }

    /** Try again: one file, or with no [ref] every file that failed or was stopped. */
    fun retry(ref: String? = null) {
        _state.update { state ->
            state.copy(
                stopped = "",
                items = state.items.map { item ->
                    val again = (item.status == FileStatus.Failed || item.status == FileStatus.Cancelled) &&
                        item.path.isNotBlank() && (ref == null || item.ref == ref)
                    if (again) item.copy(status = FileStatus.Waiting, reason = "", progress = 0f) else item
                },
            )
        }
        run()
    }

    /** A file the service said is already in the production: upload it all the same. */
    fun uploadAnyway(ref: String) {
        _state.update { state ->
            state.copy(
                stopped = "",
                items = state.items.map { item ->
                    if (item.ref == ref && item.status == FileStatus.Duplicate && item.path.isNotBlank()) {
                        item.copy(status = FileStatus.Waiting, allowDuplicate = true, duplicateOf = "", progress = 0f)
                    } else {
                        item
                    }
                },
            )
        }
        run()
    }

    /** Stop the files still on their way. What already arrived stays. */
    fun cancel() = cancelAll("cancelled", quiet = false)

    /** Take everything that is not on its way off the list. */
    fun clear() {
        _state.update { state -> state.copy(items = state.items.filter { it.status.isActive }) }
    }

    /** Tests only. */
    fun reset() {
        cancelAll("cancelled", quiet = true)
        unconfirmed.clear()
        _state.value = UploadQueueState()
    }

    // -- the run ---------------------------------------------------------------------

    private fun run() {
        if (runner?.isActive == true) return
        cancelled = false
        runner = scope.launch {
            _state.update { it.copy(running = true, stopped = "") }
            try {
                loop()
            } finally {
                _state.update { it.copy(running = false) }
            }
            // Files added while the last ones were finishing (or after a stop).
            if (_state.value.stopped.isBlank() && items.any { it.status == FileStatus.Waiting }) run()
        }
    }

    private suspend fun loop() {
        while (true) {
            if (cancelled || _state.value.stopped.isNotBlank()) break
            if (!sameProject()) {
                cancelAll("project", quiet = false)
                break
            }
            val waiting = items.filter { it.status == FileStatus.Waiting }
            if (waiting.isEmpty()) break
            // "Upload anyway" files go in a request of their own: the switch is per request.
            val flag = waiting.first().allowDuplicate
            val group = waiting.filter { it.allowDuplicate == flag }.take(tuning.signChunk)
            sendGroup(group)
        }
        // What arrived but was not confirmed yet. If this cannot be sent
        // either, the service finds those files by itself within a few minutes.
        repeat(tuning.retryMs.size + 1) { attempt ->
            if (unconfirmed.isEmpty()) return@repeat
            if (attempt > 0) delay(tuning.retryMs.getOrElse(attempt - 1) { LAST_WAIT })
            if (confirm(force = true)) return@repeat
        }
        unconfirmed.clear()
    }

    private suspend fun sendGroup(group: List<UploadItem>) {
        patchMany(group.map { it.ref }) { it.copy(status = FileStatus.Signing) }

        var answer: ZillitResult<List<UploadLinkRow>>? = null
        for (attempt in 0..tuning.signRetryMs.size) {
            answer = sign(group)
            // Only a request that never reached the service is asked again.
            val unreachable = answer.isUnreachable()
            _state.update { it.copy(offline = unreachable) }
            if (answer is ZillitResult.Success || !unreachable) break
            if (attempt == tuning.signRetryMs.size) break
            delay(tuning.signRetryMs[attempt])
            if (cancelled) return
        }
        if (cancelled) return

        val links = when (val result = answer) {
            is ZillitResult.Success -> result.data
            else -> {
                refuseGroup(group, result)
                return
            }
        }

        val byUnique = links.associateBy { it.uniqueId }
        val toSend = mutableListOf<UploadLinkRow>()
        group.forEach { item ->
            val link = byUnique[item.uniqueId]
            when {
                link == null -> patch(item.ref) { it.copy(status = FileStatus.Failed, reason = LOCAL_NETWORK) }
                link.refused.isNotBlank() ->
                    patch(item.ref) { it.copy(status = FileStatus.Refused, reason = link.refused, path = "") }
                link.duplicateOf.isNotBlank() ->
                    patch(item.ref) { it.copy(status = FileStatus.Duplicate, duplicateOf = link.duplicateOf) }
                link.uploaded -> {
                    patch(item.ref) { it.copy(photoId = link.photoId) }
                    arrived(item.ref, link.photoId)
                }
                else -> {
                    patch(item.ref) { it.copy(photoId = link.photoId) }
                    toSend += link
                }
            }
        }

        inLanes(toSend, tuning.lanes) { sendOne(it) }
        confirm(force = false)
    }

    /** Every refusal that is not about one file: a stopper, or a failure to ask at all. */
    private fun refuseGroup(group: List<UploadItem>, answer: ZillitResult<List<UploadLinkRow>>?) {
        val key = (answer as? ZillitResult.Failure)?.error?.stillsMessageKey?.takeIf { it.isMessageKey() }
        if (key != null && key in STOPPERS) {
            // About the production, not the file: nothing more can be uploaded
            // until somebody acts (acknowledges the notice, for one).
            _state.update { state ->
                state.copy(
                    stopped = key,
                    items = state.items.map { item ->
                        if (item.status == FileStatus.Waiting || item.status == FileStatus.Signing) {
                            item.copy(status = FileStatus.Failed, reason = key)
                        } else {
                            item
                        }
                    },
                )
            }
            return
        }
        val reason = if (answer.isUnreachable() || key == null) LOCAL_NETWORK else key
        patchMany(group.map { it.ref }) { it.copy(status = FileStatus.Failed, reason = reason) }
    }

    private suspend fun sendOne(start: UploadLinkRow) {
        var link = start
        for (attempt in 1..tuning.putAttempts) {
            val item = itemOf(link.ref) ?: return
            if (cancelled || item.status == FileStatus.Cancelled) return
            if (!sameProject()) {
                cancelAll("project", quiet = false)
                return
            }
            if (!files.exists(item.path)) {
                patch(item.ref) { it.copy(status = FileStatus.Failed, reason = LOCAL_MISSING, progress = 0f) }
                return
            }
            patch(item.ref) { it.copy(status = FileStatus.Uploading, progress = 0f) }

            var shown = 0f
            val outcome = uploader.put(
                url = link.url,
                headers = link.headers,
                path = item.path,
                contentType = item.type,
                size = item.size,
            ) { fraction ->
                // Progress arrives many times a second; redraw every 2%.
                if (fraction - shown >= PROGRESS_STEP || fraction >= 1f) {
                    shown = fraction
                    patch(item.ref) { it.copy(progress = fraction) }
                }
            }

            when (outcome) {
                PutOutcome.Ok -> {
                    arrived(item.ref, item.photoId)
                    confirm(force = false)
                    return
                }
                PutOutcome.Cancelled -> {
                    patch(item.ref) { row ->
                        if (row.status == FileStatus.Cancelled) {
                            row
                        } else {
                            row.copy(status = FileStatus.Cancelled, reason = _state.value.stopped.ifBlank { "cancelled" }, progress = 0f)
                        }
                    }
                    return
                }
                PutOutcome.Expired -> {
                    if (attempt == tuning.putAttempts) break
                    // The link ran out (a long queue, a laptop that slept). The
                    // same id gets the same photo back with a new link.
                    val again = sign(listOf(item))
                    val row = (again as? ZillitResult.Success)?.data?.firstOrNull()
                    if (row?.uploaded == true) {
                        arrived(item.ref, item.photoId)
                        return
                    }
                    if (row != null && row.url.isNotBlank()) link = row.copy(ref = item.ref)
                }
                is PutOutcome.Failed -> {
                    if (attempt == tuning.putAttempts) break
                    delay(tuning.retryMs.getOrElse(attempt - 1) { LAST_WAIT })
                }
            }
        }
        patch(link.ref) { it.copy(status = FileStatus.Failed, reason = LOCAL_UPLOAD, progress = 0f) }
    }

    /** One link, with the queue row it belongs to. */
    private data class UploadLinkRow(
        val ref: String,
        val uniqueId: String,
        val photoId: String,
        val url: String,
        val headers: Map<String, String>,
        val refused: String,
        val duplicateOf: String,
        val uploaded: Boolean,
    )

    private suspend fun sign(group: List<UploadItem>): ZillitResult<List<UploadLinkRow>> {
        val state = _state.value
        val declarations = group.map { item ->
            UploadDeclaration(
                uniqueId = item.uniqueId,
                name = item.name,
                type = item.type,
                size = item.size,
                fingerprint = files.fingerprint(item.path, item.size),
            )
        }
        val answer = repository.presignUploads(
            files = declarations,
            shootLabel = state.shootLabel,
            batchId = state.batchId,
            batchOffset = group.firstOrNull()?.order ?: 0,
            allowDuplicates = group.firstOrNull()?.allowDuplicate == true,
            projectId = state.projectId,
        )
        return when (answer) {
            is ZillitResult.Failure -> ZillitResult.Failure(answer.error)
            is ZillitResult.Success -> {
                val byUnique = group.associateBy { it.uniqueId }
                ZillitResult.Success(
                    answer.data.mapNotNull { link ->
                        val item = byUnique[link.uniqueId] ?: return@mapNotNull null
                        UploadLinkRow(
                            ref = item.ref,
                            uniqueId = link.uniqueId,
                            photoId = link.photoId,
                            url = link.url,
                            headers = link.headers,
                            refused = link.refused,
                            duplicateOf = link.duplicateOf,
                            uploaded = link.uploaded,
                        )
                    },
                )
            }
        }
    }

    private fun arrived(ref: String, photoId: String) {
        patch(ref) { it.copy(status = FileStatus.Uploaded, progress = 1f) }
        if (photoId.isNotBlank()) unconfirmed += photoId
    }

    /**
     * Tell the service which files arrived. Sent every few files; [force] sends
     * whatever is waiting. False when the call did not get through — the ids
     * are kept and go with the next one.
     */
    private suspend fun confirm(force: Boolean): Boolean = confirmLock.withLock {
        if (unconfirmed.isEmpty() || (!force && unconfirmed.size < tuning.confirmEvery)) return true
        val ids = unconfirmed.toList()
        unconfirmed.clear()
        val answer = repository.completeUploads(ids, _state.value.projectId)
        if (answer !is ZillitResult.Success) {
            unconfirmed.addAll(0, ids)
            return false
        }
        val accepted = answer.data.accepted
        _state.update { state ->
            state.copy(
                items = state.items.map { item ->
                    if (item.status != FileStatus.Uploaded || item.photoId !in ids) {
                        item
                    } else if (item.photoId in accepted) {
                        item.copy(status = FileStatus.Done)
                    } else {
                        // Not there after all (or not the file that was declared): send it again.
                        item.copy(status = FileStatus.Failed, reason = LOCAL_MISSING, progress = 0f)
                    }
                },
            )
        }
        return true
    }

    private fun cancelAll(why: String, quiet: Boolean) {
        cancelled = true
        runner?.cancel()
        runner = null
        if (quiet) return
        _state.update { state ->
            state.copy(
                stopped = why,
                items = state.items.map { item ->
                    if (item.status.isActive) item.copy(status = FileStatus.Cancelled, reason = why, progress = 0f) else item
                },
            )
        }
        ZillitLog.i(TAG) { "upload stopped: $why" }
    }

    private fun sameProject(): Boolean = openProject() == _state.value.projectId

    private fun itemOf(ref: String): UploadItem? = items.firstOrNull { it.ref == ref }

    private fun patch(ref: String, change: (UploadItem) -> UploadItem) {
        _state.update { state -> state.copy(items = state.items.map { if (it.ref == ref) change(it) else it }) }
    }

    private fun patchMany(refs: List<String>, change: (UploadItem) -> UploadItem) {
        val wanted = refs.toSet()
        _state.update { state -> state.copy(items = state.items.map { if (it.ref in wanted) change(it) else it }) }
    }

    /** Run [worker] over [jobs], [width] at a time. */
    private suspend fun <T> inLanes(jobs: List<T>, width: Int, worker: suspend (T) -> Unit) {
        if (jobs.isEmpty()) return
        val queue = ArrayDeque(jobs)
        val gate = Mutex()
        coroutineScope {
            List(minOf(width, jobs.size)) {
                async {
                    while (true) {
                        val job = gate.withLock { queue.removeFirstOrNull() } ?: break
                        worker(job)
                    }
                }
            }.awaitAll()
        }
    }

    private companion object {
        const val TAG = "Stills"
        const val MAX_LABEL = 80
        const val PROGRESS_STEP = 0.02f
        const val LAST_WAIT = 3000L
        val WHITESPACE = Regex("""\s+""")

        /** Reasons this screen owns, rather than the service's message keys. */
        const val LOCAL_NETWORK = "network"
        const val LOCAL_UPLOAD = "upload"
        const val LOCAL_MISSING = "missing"

        /**
         * Refusals about the production, not the file: nothing more can be
         * uploaded until somebody acts.
         */
        val STOPPERS = setOf(
            "still_kills_not_attested",
            "still_kills_storage_not_supported",
            "still_kills_region_not_supported",
        )
    }
}

/** A message key is a lowercase snake-case word, not a sentence or a body. */
internal fun String.isMessageKey(): Boolean = isNotBlank() && all { it.isLowerCase() || it.isDigit() || it == '_' }

/** Whether a failure never reached the service, so asking again is worth it. */
internal fun ZillitResult<*>?.isUnreachable(): Boolean {
    val error = (this as? ZillitResult.Failure)?.error ?: return false
    return error is com.zillit.desktop.core.common.ZillitError.NoConnection ||
        error is com.zillit.desktop.core.common.ZillitError.Timeout
}
