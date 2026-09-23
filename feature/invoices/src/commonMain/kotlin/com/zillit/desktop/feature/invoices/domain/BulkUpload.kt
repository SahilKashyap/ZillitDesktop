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
)

/** The client's half of one batch. */
data class BulkBatch(
    val id: String,
    val files: List<BulkFile>,
    val createdAtMs: Long,
    /** Nothing is left to hand over; only now can the server's counts be believed. */
    val postingDone: Boolean = false,
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

/** Where a batch stands, in the words the Ongoing Uploads row uses. */
enum class BulkPhase { Uploading, Processing, Done, Error }

/** One row of Ongoing Uploads: the two halves of a batch, merged. */
data class BulkRow(
    val id: String,
    val phase: BulkPhase,
    val local: BulkBatch?,
    val server: ServerBatch?,
    val createdAtMs: Long,
) {
    /** Somebody else's upload, or ours from before a restart — no client half to show. */
    val elsewhere: Boolean get() = local == null

    /** Two stages on one bar: sending fills the first half, extracting the second. */
    val progress: Float
        get() {
            val local = local
            if (local != null && !local.postingDone) {
                return if (local.sendable == 0) 0f else HALF * local.settled / local.sendable
            }
            val server = server ?: return if (phase == BulkPhase.Done) 1f else HALF
            val seen = server.completed + server.failed
            return HALF + HALF * seen / server.total.coerceAtLeast(1)
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
     * Both halves as rows, newest first. The server drops a finished batch,
     * so a batch of ours that has gone from its list after posting finished
     * is done; one we never saw there, with nothing sent, is an error.
     */
    fun rows(local: List<BulkBatch>, server: List<ServerBatch>, seen: Set<String>): List<BulkRow> {
        val byId = server.associateBy { it.batchId }
        val mine = local.map { batch ->
            val frame = byId[batch.id]
            val phase = when {
                !batch.postingDone -> BulkPhase.Uploading
                batch.sentCount == 0 -> BulkPhase.Error
                isFinal(frame, batch.sentCount, batch.postingDone) -> BulkPhase.Done
                frame == null && batch.id in seen -> BulkPhase.Done
                else -> BulkPhase.Processing
            }
            BulkRow(batch.id, phase, batch, frame, batch.createdAtMs)
        }
        val others = server.filter { frame -> local.none { it.id == frame.batchId } }.map { frame ->
            BulkRow(
                id = frame.batchId,
                phase = if (frame.isComplete) BulkPhase.Done else BulkPhase.Processing,
                local = null,
                server = frame,
                createdAtMs = frame.createdAtMs ?: 0L,
            )
        }
        return (mine + others).sortedByDescending { it.createdAtMs }
    }

    private const val RADIX = 36
}
