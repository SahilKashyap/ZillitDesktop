package com.zillit.desktop

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitLog
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.drive.domain.DriveItem
import com.zillit.desktop.feature.drive.domain.DriveRepository
import com.zillit.desktop.feature.drive.domain.UploadPart
import com.zillit.desktop.feature.drive.domain.UploadRequest
import com.zillit.desktop.feature.drive.domain.UploadSession
import com.zillit.desktop.feature.drive.ui.DriveUploader
import com.zillit.desktop.feature.drive.ui.PickedFile
import io.ktor.client.HttpClient
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
        val dialog = FileDialog(null as Frame?, "Upload to Drive", FileDialog.LOAD)
        dialog.isMultipleMode = true
        dialog.isVisible = true
        dialog.files.orEmpty().mapNotNull(::describe)
    }

    private fun describe(file: File): PickedFile? = when {
        !file.isFile -> null
        else -> PickedFile(
            path = file.absolutePath,
            name = file.name,
            sizeBytes = file.length(),
            mimeType = URLConnection.guessContentTypeFromName(file.name)
                ?: "application/octet-stream",
        )
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
) : DriveUploader {

    override suspend fun upload(
        file: PickedFile,
        folderId: String?,
        onProgress: (uploaded: Int, total: Int) -> Unit,
    ): ZillitResult<DriveItem> {
        val source = File(file.path)
        if (!source.isFile) {
            return ZillitResult.Failure(
                ZillitError.Validation("\"${file.name}\" is no longer on disk."),
            )
        }

        val session = when (val opened = repository.initiateUpload(
            UploadRequest(
                fileName = file.name,
                sizeBytes = file.sizeBytes,
                mimeType = file.mimeType,
                folderId = folderId,
            ),
        )) {
            is ZillitResult.Success -> opened.data
            is ZillitResult.Failure -> return opened
        }

        onProgress(0, session.parts.size)
        val completed = sendParts(source, session, file.mimeType, onProgress)
            ?: return abort(session.uploadId, file.name)

        return repository.completeUpload(session.uploadId, completed)
    }

    /**
     * Pushes every part to S3, or gives up at the first one it refuses.
     *
     * Null rather than a partial list on failure: a completion built from some
     * of the parts assembles a truncated object that S3 accepts without
     * complaint, so there is no useful half-success to report.
     */
    private suspend fun sendParts(
        source: File,
        session: UploadSession,
        contentType: String,
        onProgress: (uploaded: Int, total: Int) -> Unit,
    ): List<UploadPart>? = try {
        val completed = mutableListOf<UploadPart>()
        RandomAccessFile(source, "r").use { handle ->
            for (part in session.parts) {
                val bytes = handle.readPart(session.plan.rangeOf(part.partNumber))
                val etag = putPart(part.url, bytes, contentType) ?: return null
                completed += part.copy(etag = etag)
                onProgress(completed.size, session.parts.size)
            }
        }
        completed
    } catch (cancellation: CancellationException) {
        // The session is left for the server's 24-hour TTL to reap rather than
        // aborted here: abort is itself a suspending call and would be
        // cancelled too, and a resume is still possible from the parts already
        // accepted.
        throw cancellation
    } catch (@Suppress("TooGenericExceptionCaught") throwable: Throwable) {
        // The path is not logged: it is the user's business.
        ZillitLog.e(TAG, throwable) { "upload failed while sending parts" }
        null
    }

    /**
     * Sends one part and returns its `ETag`, or null if S3 refused it.
     *
     * The ETag is not optional bookkeeping: the completion call sends every
     * part's back, and S3 rejects an assembly whose ETags do not match what it
     * stored. Quotes are stripped because S3 returns it quoted and the server's
     * validator compares the bare value.
     */
    private suspend fun putPart(url: String, bytes: ByteArray, contentType: String): String? {
        val response: HttpResponse = httpClient.put(url) {
            header(HttpHeaders.ContentType, contentType)
            setBody(streamedBody(bytes, contentType))
        }
        if (!response.status.isSuccess()) {
            ZillitLog.w(TAG) { "S3 refused a part: ${response.status.value}" }
            return null
        }
        return response.headers[HttpHeaders.ETag]?.trim('"')?.takeIf { it.isNotBlank() }
    }

    private suspend fun abort(uploadId: String, fileName: String): ZillitResult<DriveItem> {
        repository.abortUpload(uploadId)
        return ZillitResult.Failure(
            ZillitError.Validation("\"$fileName\" could not be uploaded. Nothing was saved."),
        )
    }

    private companion object {
        const val TAG = "Drive"
    }
}

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
    PickedFile(path = path, name = name, sizeBytes = sizeBytes, mimeType = contentType)
