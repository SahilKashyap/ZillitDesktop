package com.zillit.desktop.feature.drive.domain

/**
 * How one file is cut into parts for the S3 multipart upload.
 *
 * ## Why the client computes this at all
 *
 * The server decides the chunk size and hands back one presigned URL per part,
 * so this is a *prediction*, not a decision — and it has to match, because the
 * client reads the file in these slices and a slice that is not the size the
 * presigned URL was signed for is rejected by S3 with a signature error rather
 * than a size error. Pinning the arithmetic here, against the server's rule
 * (FR-02.2), is what turns "uploads mysteriously fail above 1 GB" into a test.
 *
 * Pure and separately testable for exactly that reason.
 */
data class UploadPlan(
    val fileSizeBytes: Long,
    val chunkSizeBytes: Long,
    val totalParts: Int,
) {

    /** The byte range of part [partNumber], which is **1-based** as S3 counts. */
    fun rangeOf(partNumber: Int): LongRange {
        require(partNumber in 1..totalParts) { "part $partNumber is outside 1..$totalParts" }
        val start = (partNumber - 1).toLong() * chunkSizeBytes
        // The last part is short. Sizing it as a full chunk is what produces a
        // truncated object that S3 assembles without complaint.
        val end = minOf(start + chunkSizeBytes, fileSizeBytes) - 1
        return start..end
    }

    fun sizeOf(partNumber: Int): Long = rangeOf(partNumber).let { it.last - it.first + 1 }

    /** Fraction complete given the parts that have been acknowledged. */
    fun progress(uploadedParts: Int): Float =
        if (totalParts <= 0) 0f else (uploadedParts.toFloat() / totalParts).coerceIn(0f, 1f)

    companion object {

        // Declared before what reads them: a `const val` in a companion may
        // only refer to one already declared above it.
        private const val MAX_FILE_GIGABYTES = 10L
        private const val MB = 1024L * 1024
        private const val GB = 1024L * MB

        /** 10 GB — the server's own single-file ceiling (FR-01.18). */
        const val MAX_FILE_BYTES = MAX_FILE_GIGABYTES * GB

        /** Six parts in flight per file, matching the web (FR-02.7). */
        const val PARALLEL_PARTS = 6

        // The four bands of the server's adaptive sizing (FR-02.2), named so
        // the thresholds and the sizes they choose read as one table.
        private const val SMALL_FILE = 100 * MB
        private const val MEDIUM_FILE = GB
        private const val LARGE_FILE = 5 * GB

        private const val SMALL_CHUNK = 8 * MB
        private const val MEDIUM_CHUNK = 32 * MB
        private const val LARGE_CHUNK = 64 * MB
        private const val HUGE_CHUNK = 128 * MB

        /**
         * The server's adaptive chunk size (FR-02.2).
         *
         * The steps are not arbitrary: S3 caps a multipart upload at 10,000
         * parts, so an 8 MB chunk stops being viable at 80 GB, and the real
         * constraint below that is round trips — a 5 GB file at 8 MB is 640
         * requests, which is where a slow connection starts timing parts out.
         *
         * The comparisons are `<=`, matching the server's. A boundary file
         * belongs to the *lower* band, and getting that backwards signs every
         * part of a 100 MB file for the wrong size.
         */
        fun chunkSizeFor(fileSizeBytes: Long): Long = when {
            fileSizeBytes <= SMALL_FILE -> SMALL_CHUNK
            fileSizeBytes <= MEDIUM_FILE -> MEDIUM_CHUNK
            fileSizeBytes <= LARGE_FILE -> LARGE_CHUNK
            else -> HUGE_CHUNK
        }

        /**
         * The plan for a file of [fileSizeBytes].
         *
         * A zero-byte file still gets **one** part: S3 has no concept of a
         * multipart upload with no parts, and completing one with an empty part
         * list fails rather than creating an empty object.
         */
        fun forFile(fileSizeBytes: Long): UploadPlan {
            val size = fileSizeBytes.coerceAtLeast(0)
            val chunk = chunkSizeFor(size)
            val parts = if (size == 0L) 1 else ((size + chunk - 1) / chunk).toInt()
            return UploadPlan(
                fileSizeBytes = size,
                chunkSizeBytes = chunk,
                totalParts = parts,
            )
        }

        /**
         * Why this file cannot be uploaded, or null.
         *
         * Checked before the session is opened, so an oversized file is
         * refused in the picker rather than after the first chunk has been
         * pushed and the server has rejected the completion.
         */
        fun rejectionReason(fileName: String, fileSizeBytes: Long): String? = when {
            fileName.isBlank() -> "That file has no name."
            fileSizeBytes > MAX_FILE_BYTES ->
                "\"$fileName\" is larger than the 10 GB limit for a single file."

            else -> null
        }
    }
}

/**
 * A part the server has handed a presigned URL for.
 *
 * [etag] is filled in by the client after the PUT: S3 returns it in the
 * response header, and the completion call must send every part's back or the
 * assembled object is rejected.
 */
data class UploadPart(
    val partNumber: Int,
    val url: String,
    val etag: String? = null,
)

/** An upload session the server has opened, as `POST /drive/uploads` answers it. */
data class UploadSession(
    val uploadId: String,
    val fileName: String,
    val plan: UploadPlan,
    val parts: List<UploadPart>,
    val expiresAt: Long? = null,
) {
    /** Parts still to send — what a resume asks the server for. */
    val outstanding: List<UploadPart> get() = parts.filter { it.etag == null }

    val isComplete: Boolean get() = outstanding.isEmpty()
}

/** Where one queued upload has got to. */
sealed interface UploadState {
    data object Queued : UploadState
    data class InProgress(val uploadedParts: Int, val totalParts: Int) : UploadState {
        val fraction: Float
            get() = if (totalParts <= 0) 0f else uploadedParts.toFloat() / totalParts
    }

    data object Completing : UploadState
    data class Done(val fileId: String) : UploadState
    data class Failed(val reason: String) : UploadState
}

/** One file the user asked to upload, and how far it has got. */
data class QueuedUpload(
    val id: String,
    val fileName: String,
    val sizeBytes: Long,
    val destinationFolderId: String?,
    val state: UploadState = UploadState.Queued,
) {
    val isSettled: Boolean
        get() = state is UploadState.Done || state is UploadState.Failed

    val fraction: Float
        get() = when (val current = state) {
            is UploadState.InProgress -> current.fraction
            UploadState.Completing -> 1f
            is UploadState.Done -> 1f
            else -> 0f
        }
}
