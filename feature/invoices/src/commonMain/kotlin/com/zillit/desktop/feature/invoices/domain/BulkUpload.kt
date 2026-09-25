package com.zillit.desktop.feature.invoices.domain

/**
 * Bulk invoice upload — the web's `BulkUploadPanel` and `bulkUploadStore`.
 *
 * Files are checked, sent to storage, and each is handed to
 * `POST /invoices/bulk-upload` under one batch id the moment it lands; the
 * server extracts, finds the vendor and creates the invoice in the Inbox. The
 * client keeps its own half of the batch — what failed before the server ever
 * saw it — and reads the server's half back from
 * `GET /invoices/bulk-upload/batches`.
 */
enum class BulkFileStatus {
    /** Refused before anything left the machine: wrong type, too big, too many pages. */
    Invalid,
    Pending,
    Uploading,
    Uploaded,

    /** The storage upload failed; nothing was sent, so it is safe to try again. */
    Failed,
    Sent,

    /**
     * The hand-off failed. Never retried: the server may still have queued it,
     * and sending it again is how one invoice becomes two payables.
     */
    SendFailed,
}

/** Why a picked file is refused before it is sent. */
enum class BulkFileProblem { WrongType, TooBig, TooManyPages, Unreadable }

data class BulkFile(
    val ref: Int,
    val name: String,
    val size: Long,
    val status: BulkFileStatus = BulkFileStatus.Pending,
    val problem: BulkFileProblem? = null,
    /** Accountant uploads only: the invoice is already settled. */
    val paid: Boolean = false,
    /** The server's refusal of the hand-off, as it said it. */
    val error: String = "",
    /**
     * A storage failure already handed to a retry batch — `retried`. The
     * status stays [BulkFileStatus.Failed] so the counts do not move; only
     * the offer to retry it is withdrawn, because a second retry would send
     * the same document twice.
     */
    val retried: Boolean = false,
)

/** The client's half of one batch. */
data class BulkBatch(
    val id: String,
    val files: List<BulkFile>,
    val createdAtMs: Long,
    /** Nothing is left to hand over; only now can the server's counts be believed. */
    val postingDone: Boolean = false,
    /**
     * The server has listed this batch, or sent a progress frame for it, at
     * least once — `sawServerRow`. Without it a batch that finished and was
     * dropped cannot be told from one that never arrived.
     */
    val sawServerRow: Boolean = false,
    /**
     * The newest server snapshot of this batch — `lastServerRow`. A terminal
     * frame is kept over any later non-terminal poll: the server deletes a
     * finished batch, so this is the only place its final counts survive.
     */
    val lastServerRow: ServerBatch? = null,
    /** Why nothing reached the server, when nothing did. */
    val error: String = "",
) {
    val sentCount: Int get() = files.count { it.status == BulkFileStatus.Sent }
    val sendable: Int get() = files.count { it.status != BulkFileStatus.Invalid }
    val settled: Int
        get() = files.count {
            it.status == BulkFileStatus.Sent || it.status == BulkFileStatus.Failed ||
                it.status == BulkFileStatus.SendFailed
        }
}

/** The server's half: extraction progress, for this reader's batches and everybody else's. */
data class ServerBatch(
    val batchId: String,
    val total: Int = 0,
    val completed: Int = 0,
    val failed: Int = 0,
    val pending: Int = 0,
    val invoiceIds: List<String> = emptyList(),
    val isComplete: Boolean = false,
    val createdAtMs: Long? = null,
)

/** The numbers a batch row quotes — `mergeBatches`' `counts`. */
data class BulkCounts(val total: Int = 0, val completed: Int = 0, val failed: Int = 0, val pending: Int = 0)

/** One figure on a batch row — `batchCounters`, in the server's own vocabulary. */
enum class BulkCounterKind(val bad: Boolean = false) {
    Uploading,
    Sent,
    Completed,
    Pending,

    /** Extraction failed on the server. */
    Failed(bad = true),

    /** Never reached storage — safe to retry. */
    FailedUpload(bad = true),

    /** The hand-off errored — may still have been queued. */
    SendFailed(bad = true),

    /** Refused by the checks before anything was sent. */
    Rejected(bad = true),
}

data class BulkCounter(val kind: BulkCounterKind, val value: Int)

/** Where a batch stands, in the words the Ongoing Uploads row uses. */
enum class BulkPhase { Uploading, Processing, Done, Error }

/** One row of Ongoing Uploads: the two halves of a batch, merged — `mergeBatches.toRow`. */
data class BulkRow(
    val id: String,
    val phase: BulkPhase,
    val local: BulkBatch?,
    val server: ServerBatch?,
    val createdAtMs: Long,
    val counts: BulkCounts = BulkCounts(),
) {
    /** Somebody else's upload, or ours from before a restart — no client half to show. */
    val elsewhere: Boolean get() = local == null

    /**
     * Two stages on one bar, never running backwards: sending fills the first
     * half, extracting the second (`UploadsTab`'s `pct`).
     */
    val progress: Float
        get() {
            val local = local
            if (local != null && !local.postingDone) {
                return if (local.sendable == 0) 0f else HALF * local.settled / local.sendable
            }
            val seen = counts.completed + counts.failed
            return HALF + HALF * seen / counts.total.coerceAtLeast(1)
        }

    private companion object {
        const val HALF = 0.5f
    }
}

object BulkUploads {
    /** A sanity bound on one drop — ten near-limit files is already 100 MB. */
    const val MAX_BATCH_FILES = 10
    const val MAX_FILE_BYTES = 10L * 1024 * 1024

    /** The extractor's page cap on a PDF. */
    const val MAX_PDF_PAGES = 5
    val EXTENSIONS = setOf("pdf", "jpg", "jpeg", "png")

    /**
     * `validateInvoiceFile` with the page check: type, then size, then — for a
     * PDF — its page count ([pages] null means it could not be read).
     */
    fun problemWith(file: PickedInvoiceFile, pages: Int?): BulkFileProblem? = when {
        file.extension !in EXTENSIONS -> BulkFileProblem.WrongType
        file.bytes.size > MAX_FILE_BYTES -> BulkFileProblem.TooBig
        file.extension == "pdf" && pages == null -> BulkFileProblem.Unreadable
        file.extension == "pdf" && pages != null && pages > MAX_PDF_PAGES -> BulkFileProblem.TooManyPages
        else -> null
    }

    /** A client-owned batch id — `inv-bulk-…`, well inside the server's 64 characters. */
    fun newBatchId(nowMs: Long, random: Long): String = "inv-bulk-$nowMs-${random.toString(RADIX)}"

    /**
     * Whether a server frame is the last word on our batch — `isBatchFinal`.
     * The server can say "complete" while files are still on their way up
     * (everything sent SO FAR is done), so posting must have stopped and the
     * frame must count everything that was sent.
     */
    fun isFinal(server: ServerBatch?, sentCount: Int, postingDone: Boolean): Boolean {
        if (server == null || !server.isComplete || !postingDone) return false
        return sentCount == 0 || server.total >= sentCount
    }

    /**
     * Both halves as rows, newest first — `mergeBatches`. The server drops a
     * finished batch, so a batch of ours that it once listed and no longer
     * does is done; one it never listed is still in flight, because claiming
     * success for a batch the server may never have received cannot be taken
     * back. [seen] is an older record of listed ids, read beside
     * [BulkBatch.sawServerRow].
     */
    fun rows(local: List<BulkBatch>, server: List<ServerBatch>, seen: Set<String> = emptySet()): List<BulkRow> {
        val byId = server.associateBy { it.batchId }
        val mine = local.map { batch ->
            val frame = byId[batch.id]
            val saw = batch.sawServerRow || batch.id in seen
            val phase = when {
                !batch.postingDone -> BulkPhase.Uploading
                batch.sentCount == 0 -> BulkPhase.Error
                frame != null -> if (isFinal(frame, batch.sentCount, batch.postingDone)) {
                    BulkPhase.Done
                } else {
                    BulkPhase.Processing
                }
                saw -> BulkPhase.Done
                else -> BulkPhase.Processing
            }
            BulkRow(batch.id, phase, batch, frame, batch.createdAtMs, countsOf(batch, frame, saw))
        }
        val others = server.filter { frame -> local.none { it.id == frame.batchId } }.map { frame ->
            BulkRow(
                id = frame.batchId,
                phase = if (frame.isComplete) BulkPhase.Done else BulkPhase.Processing,
                local = null,
                server = frame,
                createdAtMs = frame.createdAtMs ?: 0L,
                counts = countsOf(null, frame, saw = false),
            )
        }
        return (mine + others).sortedByDescending { it.createdAtMs }
    }

    /**
     * `toRow`'s counts. The server's row leads; once it has gone, the kept
     * snapshot is quoted when it was the terminal one, and otherwise only
     * what a finished batch guarantees is inferred — nothing pending, and
     * completed plus failed covering the total. Our own sent count holds the
     * total up so it never shrinks under the reader mid-batch.
     */
    fun countsOf(local: BulkBatch?, server: ServerBatch?, saw: Boolean): BulkCounts {
        val vanished = server == null && local != null && saw
        val source = server ?: local?.lastServerRow?.takeIf { vanished }
        if (source == null) {
            val sendable = local?.sendable ?: 0
            return BulkCounts(total = sendable, pending = sendable)
        }
        val total = maxOf(source.total, local?.sentCount ?: 0)
        val failed = source.failed
        return if (vanished && !source.isComplete) {
            BulkCounts(total = total, completed = (total - failed).coerceAtLeast(0), failed = failed)
        } else {
            BulkCounts(total = total, completed = source.completed, failed = failed, pending = source.pending)
        }
    }

    /**
     * `batchCounters`: what is moving, what landed and what needs the reader.
     * Zeros are left out so a clean batch reads short — except Completed once
     * posting is over, because "0 completed so far" is news. The two failure
     * kinds stay apart: a storage failure never left the machine and is safe
     * to retry; an extraction failure happened on the server.
     */
    fun counters(row: BulkRow): List<BulkCounter> {
        val files = row.local?.files.orEmpty()
        val count = { status: BulkFileStatus -> files.count { it.status == status } }
        val posting = row.local != null && !row.local.postingDone
        val all = listOfNotNull(
            BulkCounter(BulkCounterKind.Uploading, count(BulkFileStatus.Uploading)).takeIf { posting },
            BulkCounter(BulkCounterKind.Sent, row.local?.sentCount ?: 0),
            BulkCounter(BulkCounterKind.Completed, row.counts.completed),
            BulkCounter(BulkCounterKind.Pending, row.counts.pending),
            BulkCounter(BulkCounterKind.Failed, row.counts.failed),
            BulkCounter(BulkCounterKind.FailedUpload, count(BulkFileStatus.Failed)),
            BulkCounter(BulkCounterKind.SendFailed, count(BulkFileStatus.SendFailed)),
            BulkCounter(BulkCounterKind.Rejected, count(BulkFileStatus.Invalid)),
        )
        return all.filter { it.value > 0 || (it.kind == BulkCounterKind.Completed && !posting) }
    }

    /**
     * The files it is safe to send again — `retryableFiles`: storage
     * failures only, never a hand-off that errored (it may have been queued),
     * and never one already handed to a retry batch.
     */
    fun retryable(batch: BulkBatch?): List<BulkFile> =
        batch?.files.orEmpty().filter { it.status == BulkFileStatus.Failed && !it.retried }

    /**
     * Whether a finished batch still owes the reader something —
     * `batchNeedsAttention`: a retry to offer, or a file that names itself
     * as refused or unsent. Extraction failures do not hold a row open: the
     * server reports them as counts, so there is nothing on the row to act on.
     */
    fun needsAttention(batch: BulkBatch?): Boolean {
        if (batch == null) return false
        if (retryable(batch).isNotEmpty()) return true
        return batch.files.any { it.status == BulkFileStatus.SendFailed || it.status == BulkFileStatus.Invalid }
    }

    /**
     * A progress frame recorded as the newest word on one of our batches —
     * `recordProgressFrame`. Somebody else's batch has no client half and is
     * left to the list.
     */
    fun recordFrame(batches: List<BulkBatch>, frame: ServerBatch): List<BulkBatch> = batches.map { batch ->
        if (batch.id != frame.batchId) batch else batch.copy(sawServerRow = true, lastServerRow = frame)
    }

    /**
     * The list's rows folded into our batches — `fetchBatches`: each listed
     * batch is marked seen and its snapshot kept, but a terminal snapshot is
     * never replaced by a non-terminal one (a poll sent before the last frame
     * can land after it).
     */
    fun applyListed(batches: List<BulkBatch>, listed: List<ServerBatch>): List<BulkBatch> {
        val byId = listed.associateBy { it.batchId }
        return batches.map { batch ->
            val row = byId[batch.id] ?: return@map batch
            val keepTerminal = batch.lastServerRow?.isComplete == true && !row.isComplete
            batch.copy(sawServerRow = true, lastServerRow = if (keepTerminal) batch.lastServerRow else row)
        }
    }

    /**
     * Our batches that have finished and left the server's list — the ones
     * whose countdown to clearing themselves may start (`expireWhenIdle`),
     * bar any that still need the reader.
     */
    fun expired(batches: List<BulkBatch>, listed: List<ServerBatch>): List<String> {
        val ids = listed.map { it.batchId }.toSet()
        return batches.filter { it.postingDone && it.sawServerRow && it.id !in ids && !needsAttention(it) }
            .map { it.id }
    }

    /** Whether any row is still moving — the Ongoing Uploads poll runs only then. */
    fun busy(rows: List<BulkRow>): Boolean =
        rows.any { it.phase == BulkPhase.Uploading || it.phase == BulkPhase.Processing }

    /** Same name and size twice in one pick is a double drop, not two invoices. */
    fun sameFile(a: BulkFile, name: String, size: Long): Boolean = a.name == name && a.size == size

    /** How long a clean finished batch stays on the tab — `AUTO_DISMISS_MS`. */
    const val AUTO_DISMISS_MS = 6_000L

    /** The backup poll while a batch is moving — `UploadsTab`'s `POLL_MS`. */
    const val POLL_MS = 8_000L

    /** Upload→post lanes run at once — `S3_CONCURRENCY`. */
    const val LANES = 3

    /** Tries at the storage upload, and the backoff step between them. */
    const val UPLOAD_ATTEMPTS = 3
    const val RETRY_BASE_MS = 120L

    private const val RADIX = 36
}
