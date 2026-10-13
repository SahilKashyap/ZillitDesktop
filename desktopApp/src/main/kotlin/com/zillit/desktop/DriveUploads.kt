package com.zillit.desktop

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitLog
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.drive.domain.DriveItem
import com.zillit.desktop.feature.drive.domain.DriveRepository
import com.zillit.desktop.feature.drive.domain.UploadCheckpoint
import com.zillit.desktop.feature.drive.domain.UploadPart
import com.zillit.desktop.feature.drive.domain.UploadRequest
import com.zillit.desktop.feature.drive.domain.UploadSession
import com.zillit.desktop.feature.drive.ui.DrivePreviewHost
import com.zillit.desktop.feature.drive.ui.DriveUploader
import com.zillit.desktop.feature.drive.ui.PickedFile
import com.zillit.desktop.feature.drive.ui.UploadTarget
import com.zillit.desktop.feature.drive.ui.archiveMimeType
import com.zillit.desktop.feature.formsignature.data.PdfBoxWork
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.content.OutgoingContent
import io.ktor.http.isSuccess
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.io.RandomAccessFile
import java.net.URLConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * The Drive's file chooser.
 *
 * AWT's `FileDialog` rather than Swing's `JFileChooser`, for the same reason
 * mail uses it: it is the *native* dialog on macOS and Windows, so it looks like
 * every other Open dialog on the machine.
 *
 * Unlike mail's picker this returns **paths, not bytes**. A drive upload can be
 * 10 GB; reading one into memory to hand it on would exhaust the heap before
 * the first chunk left.
 */
internal class DriveFilePicker {

    suspend fun pick(): List<PickedFile> = withContext(Dispatchers.IO) {
        val dialog = FileDialog(null as Frame?, str(S.desktop_upload_to_drive), FileDialog.LOAD)
        dialog.isMultipleMode = true
        dialog.isVisible = true
        dialog.files.orEmpty().mapNotNull(::describe)
    }

    /**
     * A whole folder — the web's "Choose folder". AWT's dialog cannot pick
     * directories on Windows, and macOS needs the `fileDialogForDirectories`
     * property flipped, so this goes through Swing's chooser; every file
     * beneath comes back with its path relative to the folder's parent, which
     * is what recreates the tree in the Drive.
     */
    suspend fun pickFolder(): List<PickedFile> = withContext(Dispatchers.IO) {
        val chooser = javax.swing.JFileChooser().apply {
            dialogTitle = str(S.desktop_upload_folder_to_drive)
            fileSelectionMode = javax.swing.JFileChooser.DIRECTORIES_ONLY
            isMultiSelectionEnabled = false
        }
        val outcome = chooser.showOpenDialog(null)
        val approved = outcome == javax.swing.JFileChooser.APPROVE_OPTION
        val folder = chooser.selectedFile?.takeIf { approved && it.isDirectory } ?: return@withContext emptyList()
        val root = folder.parentFile ?: folder
        folder.walkTopDown()
            .filter { it.isFile && !it.name.startsWith('.') }
            .mapNotNull { file ->
                describe(file)?.copy(relativePath = file.relativeTo(root).path.replace(File.separatorChar, '/'))
            }
            .toList()
    }

    private fun describe(file: File): PickedFile? = when {
        !file.isFile -> null
        else -> PickedFile(
            path = file.absolutePath,
            name = file.name,
            sizeBytes = file.length(),
            mimeType = archiveMimeType(file.name)
                ?: URLConnection.guessContentTypeFromName(file.name)
                ?: "application/octet-stream",
        )
    }
}

/**
 * The bytes behind a presigned address, and PDFBox for the pages — what the
 * in-app preview needs. The plain client, as for uploads: a presigned S3 GET
 * must not carry the API's own headers.
 */
internal class AppDrivePreviewHost(private val httpClient: HttpClient) : DrivePreviewHost {

    private val pdf = PdfBoxWork()

    override suspend fun fetchBytes(url: String, maxBytes: Long): ZillitResult<ByteArray> = try {
        val response: HttpResponse = httpClient.get(url)
        when {
            !response.status.isSuccess() ->
                ZillitResult.Failure(ZillitError.Http(response.status.value, "the file could not be fetched"))

            (response.headers[HttpHeaders.ContentLength]?.toLongOrNull() ?: 0L) > maxBytes ->
                ZillitResult.Failure(ZillitError.Validation(str(S.desktop_drive_preview_too_large)))

            else -> {
                val bytes: ByteArray = response.body()
                if (bytes.size > maxBytes) {
                    ZillitResult.Failure(ZillitError.Validation(str(S.desktop_drive_preview_too_large)))
                } else {
                    ZillitResult.Success(bytes)
                }
            }
        }
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (@Suppress("TooGenericExceptionCaught") throwable: Throwable) {
        ZillitLog.w(TAG) { "preview fetch failed: ${throwable.message}" }
        ZillitResult.Failure(ZillitError.Unknown(throwable.message ?: "preview fetch failed"))
    }

    override suspend fun renderPdfPages(pdf: ByteArray, targetWidthPx: Int): ZillitResult<List<ByteArray>> =
        withContext(Dispatchers.Default) {
            when (val pages = this@AppDrivePreviewHost.pdf.renderPages(pdf, targetWidthPx)) {
                is ZillitResult.Failure -> pages
                is ZillitResult.Success -> ZillitResult.Success(pages.data.map { it.imageBytes })
            }
        }

    private companion object {
        const val TAG = "Drive"
    }
}

/**
 * Puts a file into the Drive as an S3 multipart upload.
 *
 * ## Three steps, and the middle one is the whole job
 *
 * The server opens a session and hands back one presigned `PUT` URL per part;
 * this pushes the bytes straight to S3 and collects each part's `ETag`; the
 * server then assembles them. The API is never in the data path, which is what
 * makes a 10 GB upload possible at all.
 *
 * ## A failure keeps the session
 *
 * A part is tried three times with backoff before the upload gives up, and
 * giving up leaves the session open: every ETag collected so far has gone to
 * the caller through `onCheckpoint`, and handing the last one back as `resume`
 * sends only what is missing — the web's pause/resume, and its answer to a
 * dropped connection at part 40 of 50. Only the caller's cancel aborts a
 * session. A resume asks the server for fresh presigned URLs (the old ones may
 * have expired during a long pause); if the server has forgotten the session,
 * or the completion is refused for parts it cannot find, the upload starts
 * again from nothing instead of failing.
 *
 * ## Why a hand-written streaming body
 *
 * Ktor's `onUpload` progress observer **stalls the request outright** on this
 * stack (Ktor + OkHttp + JBR): a 20 MB PUT sat for minutes with nothing ever
 * reaching the engine. Each part is therefore written as an
 * `OutgoingContent.WriteChannelContent` that declares its length — which S3
 * requires on a plain PUT — and progress is reported per *part* rather than per
 * byte. See `S3AttachmentUploader.progressBody`, which is the same fix in mail.
 *
 * ## Parts are read one at a time
 *
 * `RandomAccessFile` seeks to each part's offset rather than holding the file.
 * Uploading six parts concurrently would be faster (the web does), and is the
 * obvious next step — but it means six chunk buffers live at once, which at the
 * 128 MB chunk size a >5 GB file uses is 768 MB of heap. Sequential first,
 * measured concurrency after.
 */
internal class MultipartDriveUploader(
    private val repository: DriveRepository,
    /**
     * The plain HTTP client, deliberately.
     *
     * S3 signs its own requests through the presigned URL. Sending the Zillit
     * `moduledata`/`bodyhash` headers alongside would make the signature cover
     * headers S3 did not sign for, and it rejects the PUT.
     */
    private val httpClient: HttpClient,
    /** Waits between a part's attempts; a test passes one that does not sleep. */
    private val pause: suspend (millis: Long) -> Unit = { delay(it) },
) : DriveUploader {

    override suspend fun upload(
        file: PickedFile,
        target: UploadTarget,
        resume: UploadCheckpoint?,
        onCheckpoint: (UploadCheckpoint) -> Unit,
        onProgress: (uploaded: Int, total: Int) -> Unit,
    ): ZillitResult<DriveItem> {
        val source = File(file.path)
        if (!source.isFile) {
            return ZillitResult.Failure(
                ZillitError.Validation("\"${file.name}\" is no longer on disk."),
            )
        }
        val job = Job(source, file, target, onCheckpoint, onProgress)
        if (resume == null) return job.startFresh()

        val session = reopen(resume, file)
        if (session == null) {
            // The server no longer knows it (reaped, or aborted): earlier ETags are
            // worthless, and the old session is not worth keeping either.
            ZillitLog.i(TAG) { "upload session ${resume.uploadId} is gone; starting again" }
            repository.abortUpload(resume.uploadId)
            return job.startFresh()
        }
        return job.send(session, resume.etags.toMutableMap(), resumed = true)
    }

    /** One call to [upload]: what it is sending, and who to tell. */
    private inner class Job(
        val source: File,
        val file: PickedFile,
        val target: UploadTarget,
        val onCheckpoint: (UploadCheckpoint) -> Unit,
        val onProgress: (uploaded: Int, total: Int) -> Unit,
    ) {

        suspend fun startFresh(): ZillitResult<DriveItem> {
            val session = when (val opened = repository.initiateUpload(
                UploadRequest(
                    fileName = file.name,
                    sizeBytes = file.sizeBytes,
                    mimeType = file.mimeType,
                    folderId = target.folderId,
                    description = target.description,
                    fileAccess = target.fileAccess,
                ),
            )) {
                is ZillitResult.Success -> opened.data
                is ZillitResult.Failure -> return opened
            }
            // Told at once, before a byte moves: a pause that lands during the
            // first part still has a session to come back to.
            onCheckpoint(UploadCheckpoint(session.uploadId, session.plan))
            // Which headers the server signed into the part URLs — names only, no
            // secret: a header sent that the URL signed differently is a 403 S3
            // answers by closing the connection, which reads as a broken pipe.
            ZillitLog.d(TAG) {
                val signed = session.parts.firstOrNull()?.url
                    ?.substringAfter("X-Amz-SignedHeaders=", "")?.substringBefore('&')
                "upload ${session.parts.size} part(s); part URLs sign: ${signed?.ifBlank { "?" } ?: "?"}"
            }
            return send(session, mutableMapOf(), resumed = false)
        }

        /** Sends what is missing, then completes. [etags] is every part S3 holds so far. */
        suspend fun send(
            session: UploadSession,
            etags: MutableMap<Int, String>,
            resumed: Boolean,
        ): ZillitResult<DriveItem> {
            val total = session.plan.totalParts
            onProgress(etags.size, total)
            if (!sendParts(session, etags)) {
                // Not aborted: the parts already accepted are what Retry resumes from.
                return ZillitResult.Failure(
                    ZillitError.Validation(
                        "\"${file.name}\" could not be uploaded. Retry to continue from where it stopped.",
                    ),
                )
            }
            if (!etags.keys.containsAll((1..total).toList())) {
                // Some part is neither held nor offered — the server's list and ours disagree.
                return if (resumed) restart(session.uploadId) else lostParts()
            }
            val completed = repository.completeUpload(
                uploadId = session.uploadId,
                parts = etags.map { (number, etag) -> UploadPart(partNumber = number, url = "", etag = etag) },
                fileName = file.name,
                description = target.description,
            )
            if (resumed && completed is ZillitResult.Failure && completed.error.namesLostParts()) {
                return restart(session.uploadId)
            }
            return completed
        }

        private suspend fun restart(staleUploadId: String): ZillitResult<DriveItem> {
            ZillitLog.i(TAG) { "upload session $staleUploadId cannot be completed; starting again" }
            repository.abortUpload(staleUploadId)
            return startFresh()
        }

        private fun lostParts(): ZillitResult<DriveItem> = ZillitResult.Failure(
            ZillitError.Validation("\"${file.name}\" could not be uploaded. Nothing was saved."),
        )

        /**
         * Pushes every part not yet accepted, or gives up at the first one S3
         * keeps refusing. True when [etags] now covers what was offered.
         *
         * A cancellation (a pause) propagates; the checkpoint already holds
         * everything up to the last part that finished.
         */
        private suspend fun sendParts(session: UploadSession, etags: MutableMap<Int, String>): Boolean = try {
            RandomAccessFile(source, "r").use { handle ->
                for (part in session.parts.filter { it.partNumber !in etags }) {
                    val bytes = handle.readPart(session.plan.rangeOf(part.partNumber))
                    etags[part.partNumber] = putPartWithRetry(part.url, bytes) ?: return false
                    onCheckpoint(UploadCheckpoint(session.uploadId, session.plan, etags.toMap()))
                    onProgress(etags.size, session.plan.totalParts)
                }
            }
            true
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (@Suppress("TooGenericExceptionCaught") throwable: Throwable) {
            // The path is not logged: it is the user's business.
            ZillitLog.e(TAG, throwable) { "upload failed while sending parts" }
            false
        }
    }

    /**
     * The session as the server has it now, or null when it must be started
     * again: the server has forgotten it, or it would slice the file differently
     * from the way the ETags in hand were earned.
     */
    private suspend fun reopen(checkpoint: UploadCheckpoint, file: PickedFile): UploadSession? {
        val session = (repository.resumeUpload(checkpoint.uploadId, file.sizeBytes) as? ZillitResult.Success)
            ?.data ?: return null
        val sameSlicing = session.plan.chunkSizeBytes == checkpoint.plan.chunkSizeBytes &&
            session.plan.totalParts == checkpoint.plan.totalParts
        return session.takeIf { sameSlicing }
    }

    /**
     * One part, up to three attempts with backoff — the web's
     * `uploadChunkWithRetry` (650 ms doubling to 6 s). A network error, a 5xx, a
     * 408 or a 429 is worth another go; any other refusal (an expired signature, a
     * forbidden) is not, and is what Retry's fresh URLs are for.
     */
    private suspend fun putPartWithRetry(url: String, bytes: ByteArray): String? {
        for (attempt in 0 until PART_ATTEMPTS) {
            when (val outcome = putPart(url, bytes)) {
                is PartOutcome.Stored -> return outcome.etag
                PartOutcome.Refused -> return null
                PartOutcome.Transient -> if (attempt < PART_ATTEMPTS - 1) pause(backoffMillis(attempt))
            }
        }
        return null
    }

    /**
     * Sends one part and reports what S3 made of it.
     *
     * The ETag is not optional bookkeeping: the completion call sends every
     * part's back, and S3 rejects an assembly whose ETags do not match what it
     * stored. Quotes are stripped because S3 returns it quoted and the server's
     * validator compares the bare value.
     *
     * Every part goes as `application/octet-stream`, whatever the file is —
     * exactly what the web sends (`useFileUpload.js`). A part's type is not the
     * object's (that was fixed when the session opened). On 2026-10-12 a `.zip`
     * sent as `application/zip` failed: S3 closed the connection five seconds
     * into the first part (a broken pipe), and the same file sent as
     * `application/octet-stream` uploaded. Why is not established — the part
     * URLs sign only `host`, so the type is not in the signature, and only one
     * failure was seen — but this is the web's request, and the web's works.
     */
    private suspend fun putPart(url: String, bytes: ByteArray): PartOutcome = try {
        val response: HttpResponse = httpClient.put(url) {
            header(HttpHeaders.ContentType, PART_CONTENT_TYPE)
            setBody(streamedBody(bytes, PART_CONTENT_TYPE))
        }
        val status = response.status.value
        when {
            response.status.isSuccess() ->
                response.headers[HttpHeaders.ETag]?.trim('"')?.takeIf { it.isNotBlank() }
                    ?.let { PartOutcome.Stored(it) } ?: PartOutcome.Refused
            status == STATUS_REQUEST_TIMEOUT || status == STATUS_TOO_MANY || status >= STATUS_SERVER_ERROR ->
                PartOutcome.Transient.also { ZillitLog.w(TAG) { "S3 failed a part ($status); will try again" } }
            else -> PartOutcome.Refused.also { ZillitLog.w(TAG) { "S3 refused a part: $status" } }
        }
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (@Suppress("TooGenericExceptionCaught") throwable: Throwable) {
        // A reset or a timeout mid-part — the network, not the file.
        ZillitLog.w(TAG) { "a part did not arrive: ${throwable.message}" }
        PartOutcome.Transient
    }

    private sealed interface PartOutcome {
        data class Stored(val etag: String) : PartOutcome
        data object Transient : PartOutcome
        data object Refused : PartOutcome
    }

    private companion object {
        const val TAG = "Drive"

        /** What every part is sent as — the web's own choice; see [putPart]. */
        const val PART_CONTENT_TYPE = "application/octet-stream"

        const val PART_ATTEMPTS = 3
        const val BACKOFF_BASE_MILLIS = 650L
        const val BACKOFF_CAP_MILLIS = 6_000L
        const val STATUS_REQUEST_TIMEOUT = 408
        const val STATUS_TOO_MANY = 429
        const val STATUS_SERVER_ERROR = 500

        fun backoffMillis(attempt: Int): Long =
            minOf(BACKOFF_CAP_MILLIS, BACKOFF_BASE_MILLIS shl attempt)
    }
}

/**
 * Whether a completion refusal says S3 no longer has the parts the ETags in
 * hand were issued for — the web's "parts could not be found / entity tag may
 * not match" (ZL-18280), which is the cue to start a fresh session.
 */
internal fun String.namesLostParts(): Boolean {
    val text = lowercase()
    return LOST_PART_MARKERS.any { it in text }
}

private fun ZillitError.namesLostParts(): Boolean = "${technical.orEmpty()} $userMessage".namesLostParts()

/**
 * The request body for one part, written in slices.
 *
 * Declares its content length, which S3 requires on a plain PUT — a chunked
 * transfer is refused outright. Written by hand rather than via Ktor's upload
 * observer for the reason in [MultipartDriveUploader]'s doc.
 */
private fun streamedBody(
    bytes: ByteArray,
    contentType: String,
): OutgoingContent.WriteChannelContent = object : OutgoingContent.WriteChannelContent() {
    override val contentType: ContentType? = ContentType.parse(contentType)
    override val contentLength: Long = bytes.size.toLong()

    override suspend fun writeTo(channel: ByteWriteChannel) {
        var written = 0
        while (written < bytes.size) {
            // `writeFully(src, startIndex, endIndex)` — an **end index**, not a
            // length. Passing a length writes the first slice correctly and
            // then nothing at all (start == end on every later pass), so the
            // request sends 256 KB under a declared Content-Length of the whole
            // part and OkHttp fails it as `unexpected end of stream`. Verified
            // live 2026-08-11; `progressBody` in mail has the same shape.
            val end = minOf(written + WRITE_SLICE, bytes.size)
            channel.writeFully(bytes, written, end)
            written = end
        }
    }
}

/** Reads exactly the byte range of one part. */
private fun RandomAccessFile.readPart(range: LongRange): ByteArray {
    val length = (range.last - range.first + 1).toInt()
    val buffer = ByteArray(length)
    seek(range.first)
    // `readFully`, not `read`: a short read on a large file is legal and would
    // silently upload a part padded with zeroes.
    readFully(buffer)
    return buffer
}

/** 256 KB, matching the slice size mail's uploader settled on. */
private const val WRITE_SLICE = 256 * 1024

/** The shared picker's path onto Drive's own row. */
internal fun com.zillit.desktop.core.media.PickedPath.toDrivePick() =
    PickedFile(path = path, name = name, sizeBytes = sizeBytes, mimeType = archiveMimeType(name) ?: contentType)
