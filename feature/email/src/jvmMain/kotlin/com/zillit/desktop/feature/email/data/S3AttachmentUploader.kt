package com.zillit.desktop.feature.email.data

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitLog
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.network.AwsRequest
import com.zillit.desktop.core.network.AwsV4Signer
import com.zillit.desktop.core.network.s3KeyPath
import com.zillit.desktop.core.network.toHex
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.email.domain.AttachmentUploader
import com.zillit.desktop.feature.email.domain.StorageTarget
import com.zillit.desktop.feature.email.domain.StorageTargetSource
import com.zillit.desktop.feature.email.domain.StoredFile
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.utils.io.writeFully
import io.ktor.http.ContentType
import io.ktor.http.isSuccess
import java.security.MessageDigest
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The AWS credentials this client was given, or null when it has none. */
data class AwsCredentials(val accessKey: String, val secretKey: String)

/**
 * Uploads attachments straight to S3.
 *
 * ## Why the client talks to S3 at all
 *
 * Because that is the architecture: `configuration` hands every client a pair of
 * long-lived AWS keys, and the web app uploads the same way. It works, but it
 * means anyone with the app has write access to the bucket, and no amount of
 * care in this file changes that. The fix is server-issued presigned URLs.
 *
 * Only AWS is implemented. Productions on Box storage need a second uploader —
 * see [AttachmentUploader], which exists so that can be added without touching
 * the composer.
 */
class S3AttachmentUploader(
    private val httpClient: HttpClient,
    private val credentials: suspend () -> AwsCredentials?,
    private val storage: StorageTargetSource,
    private val newKey: (String) -> String = ::defaultKey,
    private val now: () -> ZonedDateTime = { ZonedDateTime.now(ZoneOffset.UTC) },
) : AttachmentUploader {

    override suspend fun upload(
        fileName: String,
        contentType: String,
        bytes: ByteArray,
        onProgress: (Int) -> Unit,
    ): ZillitResult<StoredFile> = withContext(Dispatchers.IO) {
        val keys = credentials() ?: return@withContext ZillitResult.Failure(
            ZillitError.Storage(
                technical = "no AWS credentials in the remote configuration",
                userMessage = str(S.desktop_email_attachments_no_workspace_storage),
            ),
        )

        val target = when (val resolved = storage.target()) {
            is ZillitResult.Failure -> return@withContext resolved
            is ZillitResult.Success -> resolved.data
        }
        if (!target.isUsable) {
            return@withContext ZillitResult.Failure(
                ZillitError.Storage(
                    technical = "suitable-region returned no bucket",
                    userMessage = str(S.desktop_email_attachments_no_region),
                ),
            )
        }

        onProgress(0)
        val key = newKey(fileName)
        var outcome = send(keys, target, key, fileName, contentType, bytes, onProgress)
        if (outcome is ZillitResult.Failure && outcome.error is ZillitError.NoConnection) {
            // Once more, on a fresh connection. Rapid sequential PUTs reuse a
            // pooled connection S3 may have quietly closed, and a streamed
            // body cannot be replayed by the HTTP client on its own — so the
            // second of three quick sends dies with a transport error that is
            // not a lost network. One resend distinguishes the two honestly;
            // a real outage fails twice and reports as before.
            ZillitLog.w(TAG) { "upload retrying once after a transport error" }
            outcome = send(keys, target, key, fileName, contentType, bytes, onProgress)
        }
        outcome.also { if (it is ZillitResult.Success) onProgress(PERCENT) }
    }

    @Suppress("LongParameterList") // The signed request needs every piece; a holder would rename, not reduce.
    private suspend fun send(
        keys: AwsCredentials,
        target: StorageTarget,
        key: String,
        fileName: String,
        contentType: String,
        bytes: ByteArray,
        onProgress: (Int) -> Unit,
    ): ZillitResult<StoredFile> = try {
        val host = "${target.bucket}.s3.${target.region}.amazonaws.com"
        val timestamp = now().format(AMZ_DATE)
        val payloadHash = MessageDigest.getInstance("SHA-256").digest(bytes).toHex()
        val path = s3KeyPath(key)

        val authorization = AwsV4Signer.authorization(
            request = AwsRequest(
                method = "PUT",
                path = path,
                host = host,
                payloadSha256 = payloadHash,
                timestamp = timestamp,
                region = target.region,
                extraHeaders = mapOf("content-type" to contentType),
            ),
            accessKey = keys.accessKey,
            secretKey = keys.secretKey,
            hmacSha256 = ::hmacSha256,
            sha256Hex = { value ->
                MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).toHex()
            },
        )

        val response = httpClient.put("https://$host$path") {
            header("Authorization", authorization)
            header("x-amz-date", timestamp)
            header("x-amz-content-sha256", payloadHash)
            header("Content-Type", contentType)
            // A hand-written streaming body rather than Ktor's `onUpload`:
            // the observer wrapper never dispatched the request at all on
            // this engine (the PUT hung before reaching OkHttp). Writing the
            // chunks ourselves keeps the declared Content-Length S3 requires
            // and reports honest percentages as each chunk leaves.
            setBody(progressBody(bytes, contentType, onProgress))
        }

        if (!response.status.isSuccess()) {
            // The body carries S3's own reason, which is the only clue when a
            // signature is subtly wrong.
            ZillitLog.w(TAG) { "upload rejected: ${response.status.value}" }
            ZillitResult.Failure(
                ZillitError.Http(response.status.value, "Could not upload $fileName."),
            )
        } else {
            ZillitResult.Success(
                StoredFile(
                    media = key,
                    bucket = target.bucket,
                    region = target.region,
                    fileName = fileName,
                    contentType = contentType,
                    sizeBytes = bytes.size.toLong(),
                ),
            )
        }
    } catch (@Suppress("TooGenericExceptionCaught") throwable: Throwable) {
        // The file name is not logged: it is the sender's business.
        ZillitLog.e(TAG, throwable) { "upload failed" }
        ZillitResult.Failure(ZillitError.NoConnection(throwable::class.simpleName))
    }

    private fun hmacSha256(key: ByteArray, data: String): ByteArray =
        Mac.getInstance("HmacSHA256")
            .apply { init(SecretKeySpec(key, "HmacSHA256")) }
            .doFinal(data.toByteArray(Charsets.UTF_8))

    private companion object {
        const val TAG = "Email"
        const val PERCENT = 100
        val AMZ_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
    }
}

/**
 * The object key.
 *
 * Prefixed with a random component so two people attaching `call sheet.pdf`
 * within a second of each other do not overwrite one another — the bucket is
 * shared across the whole production.
 */
private fun defaultKey(fileName: String): String {
    val safe = fileName.replace(UNSAFE, "_").take(MAX_NAME)
    return "email/${java.util.UUID.randomUUID()}/$safe"
}

/**
 * The request body, written chunk by chunk so [onProgress] can narrate.
 *
 * Content length is declared — S3 rejects chunked transfers on plain PUTs —
 * and progress is the share of bytes handed to the engine, which is as close
 * to the wire as the client can honestly see.
 */
internal fun progressBody(
    bytes: ByteArray,
    contentType: String,
    onProgress: (Int) -> Unit,
): io.ktor.http.content.OutgoingContent.WriteChannelContent =
    object : io.ktor.http.content.OutgoingContent.WriteChannelContent() {
        override val contentType: ContentType? = ContentType.parse(contentType)
        override val contentLength: Long = bytes.size.toLong()

        override suspend fun writeTo(channel: io.ktor.utils.io.ByteWriteChannel) {
            var written = 0
            while (written < bytes.size) {
                val end = minOf(written + PROGRESS_CHUNK, bytes.size)
                channel.writeFully(bytes, written, end)
                written = end
                onProgress(((written.toLong() * FULL) / bytes.size.coerceAtLeast(1)).toInt())
            }
        }
    }

private const val PROGRESS_CHUNK = 256 * 1024
private const val FULL = 100

private val UNSAFE = Regex("""[^A-Za-z0-9._-]""")
private const val MAX_NAME = 80
