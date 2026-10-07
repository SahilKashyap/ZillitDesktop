package com.zillit.desktop.feature.selectstills.data

import com.zillit.desktop.core.common.ZillitLog
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.content.OutgoingContent
import io.ktor.http.isSuccess
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.CancellationException

/** How a single PUT to storage ended. */
sealed interface PutOutcome {
    data object Ok : PutOutcome

    /**
     * The link ran out or does not match the file (a 403): ask for a new one
     * and try again.
     */
    data object Expired : PutOutcome

    data class Failed(val status: Int) : PutOutcome
    data object Cancelled : PutOutcome
}

/**
 * Sends one file straight to the production's storage with a link from the
 * service.
 *
 * Never the signed Zillit client: that would attach the session token to a
 * request for the storage provider. The link takes exactly the bytes and the
 * type it was signed for, so the headers are sent as given and nothing else is
 * added.
 *
 * Never throws for a transport failure — a lost connection is an outcome, not
 * a crash in the middle of a 500-file card.
 */
interface StillsUploader {

    /** The file at [path], streamed, with [onProgress] narrating as it goes. */
    suspend fun put(
        url: String,
        headers: Map<String, String>,
        path: String,
        contentType: String,
        size: Long,
        onProgress: (Float) -> Unit,
    ): PutOutcome

    /** The whole file, for the small ones (a headshot). */
    suspend fun putBytes(
        url: String,
        headers: Map<String, String>,
        bytes: ByteArray,
        contentType: String,
    ): PutOutcome
}

/**
 * The real one.
 *
 * The body is written chunk by chunk rather than through Ktor's `onUpload`:
 * that observer never dispatched the request at all on this engine (the PUT
 * hung before reaching OkHttp). Writing the chunks keeps the declared content
 * length storage requires and reports honest progress as each one leaves.
 */
class KtorStillsUploader(
    private val httpClient: HttpClient,
    private val files: StillsFileReader,
) : StillsUploader {

    override suspend fun put(
        url: String,
        headers: Map<String, String>,
        path: String,
        contentType: String,
        size: Long,
        onProgress: (Float) -> Unit,
    ): PutOutcome = try {
        val response: HttpResponse = httpClient.put(url) {
            headers.forEach { (name, value) -> header(name, value) }
            setBody(progressBody(path, contentType, size, onProgress))
        }
        when {
            response.status.isSuccess() -> PutOutcome.Ok
            response.status.value == FORBIDDEN -> PutOutcome.Expired
            else -> PutOutcome.Failed(response.status.value)
        }
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (@Suppress("TooGenericExceptionCaught") throwable: Throwable) {
        // The file name is the photographer's business; the kind of failure is
        // ours. Status 0 is this client's word for "it never reached storage".
        ZillitLog.w(TAG) { "a still did not reach storage: ${throwable::class.simpleName}" }
        PutOutcome.Failed(NO_RESPONSE)
    }

    override suspend fun putBytes(
        url: String,
        headers: Map<String, String>,
        bytes: ByteArray,
        contentType: String,
    ): PutOutcome = try {
        val response: HttpResponse = httpClient.put(url) {
            headers.forEach { (name, value) -> header(name, value) }
            setBody(bytesBody(bytes, contentType))
        }
        when {
            response.status.isSuccess() -> PutOutcome.Ok
            response.status.value == FORBIDDEN -> PutOutcome.Expired
            else -> PutOutcome.Failed(response.status.value)
        }
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (@Suppress("TooGenericExceptionCaught") throwable: Throwable) {
        // The file name is the photographer's business; the kind of failure is
        // ours. Status 0 is this client's word for "it never reached storage".
        ZillitLog.w(TAG) { "a still did not reach storage: ${throwable::class.simpleName}" }
        PutOutcome.Failed(NO_RESPONSE)
    }

    private fun progressBody(
        path: String,
        contentType: String,
        size: Long,
        onProgress: (Float) -> Unit,
    ): OutgoingContent.WriteChannelContent = object : OutgoingContent.WriteChannelContent() {
        override val contentType: ContentType? = ContentType.parse(contentType)
        override val contentLength: Long = size

        override suspend fun writeTo(channel: ByteWriteChannel) {
            var written = 0L
            // Read the file in slices rather than whole: a card of 40 MB stills
            // would otherwise be held in the heap three at a time.
            files.readInChunks(path, CHUNK) { chunk, length ->
                channel.writeFully(chunk, 0, length)
                written += length
                onProgress(if (size > 0) written.toFloat() / size else 1f)
            }
        }
    }

    private fun bytesBody(bytes: ByteArray, contentType: String): OutgoingContent.WriteChannelContent =
        object : OutgoingContent.WriteChannelContent() {
            override val contentType: ContentType? = ContentType.parse(contentType)
            override val contentLength: Long = bytes.size.toLong()
            override suspend fun writeTo(channel: ByteWriteChannel) = channel.writeFully(bytes, 0, bytes.size)
        }

    private companion object {
        const val TAG = "Stills"
        const val FORBIDDEN = 403
        const val CHUNK = 256 * 1024

        /** No answer at all — the request never got to storage. */
        const val NO_RESPONSE = 0
    }
}
