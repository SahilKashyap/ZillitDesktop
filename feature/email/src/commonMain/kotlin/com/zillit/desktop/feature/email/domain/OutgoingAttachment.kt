package com.zillit.desktop.feature.email.domain

import com.zillit.desktop.core.common.ZillitResult

/** Where a file has got to on its way out. */
sealed interface UploadState {
    data object Pending : UploadState
    data class InProgress(val percent: Int) : UploadState

    /** Stored, and addressable by the mail server. */
    data class Uploaded(val stored: StoredFile) : UploadState
    data class Failed(val reason: String) : UploadState
}

/**
 * A file in object storage, as the mail API describes it.
 *
 * The send payload does not carry bytes — it carries this, and the server
 * fetches from the bucket. So an attachment is only really attached once its
 * upload has finished.
 */
data class StoredFile(
    /** The object key. Called `media` on the wire, for historical reasons. */
    val media: String,
    val bucket: String,
    val region: String,
    val fileName: String,
    val contentType: String,
    val sizeBytes: Long,
)

/** A file the user picked, and how far it has got. */
data class OutgoingAttachment(
    val id: String,
    val fileName: String,
    val sizeBytes: Long,
    val contentType: String,
    val state: UploadState = UploadState.Pending,
) {
    val isUploaded: Boolean get() = state is UploadState.Uploaded

    val stored: StoredFile? get() = (state as? UploadState.Uploaded)?.stored

    /** "1.2 MB" — sized for a chip, not a table. */
    val readableSize: String
        get() = when {
            sizeBytes <= 0 -> ""
            sizeBytes < KB -> "$sizeBytes B"
            sizeBytes < MB -> "${sizeBytes / KB} KB"
            else -> "${(sizeBytes * 10 / MB) / 10.0} MB"
        }

    private companion object {
        const val KB = 1024L
        const val MB = 1024L * 1024L
    }
}

/**
 * Whether a message is ready to go.
 *
 * A send while a file is still uploading would arrive without it — the payload
 * names objects that do not exist yet, and the recipient gets a message
 * referring to an attachment that never appears. Blocking the button is the
 * only honest option, since the send itself cannot wait.
 */
val List<OutgoingAttachment>.areSettled: Boolean
    get() = none { it.state is UploadState.Pending || it.state is UploadState.InProgress }

/** Files that failed and are still in the list, which the user should be told about. */
val List<OutgoingAttachment>.failed: List<OutgoingAttachment>
    get() = filter { it.state is UploadState.Failed }

/**
 * Puts a file into object storage.
 *
 * A port because the destination varies: productions are on AWS or on Box, and
 * only the first is implemented here.
 */
interface AttachmentUploader {

    /**
     * Uploads [bytes] and returns where it landed.
     *
     * [onProgress] receives 0..100. Called from the uploading coroutine, so
     * whatever it touches must be safe to touch there.
     */
    suspend fun upload(
        fileName: String,
        contentType: String,
        bytes: ByteArray,
        onProgress: (Int) -> Unit = {},
    ): ZillitResult<StoredFile>
}

/**
 * Where uploads go, from `suitable-region`.
 *
 * Fetched rather than configured: the bucket is chosen per user by the server,
 * to keep a production's files near the people working on it.
 */
data class StorageTarget(val region: String, val bucket: String) {
    val isUsable: Boolean get() = region.isNotBlank() && bucket.isNotBlank()
}

interface StorageTargetSource {
    suspend fun target(): ZillitResult<StorageTarget>
}
