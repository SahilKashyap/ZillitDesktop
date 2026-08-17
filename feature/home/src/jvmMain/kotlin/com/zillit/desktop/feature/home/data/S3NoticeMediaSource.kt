package com.zillit.desktop.feature.home.data

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitLog
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.network.AwsRequest
import com.zillit.desktop.core.network.AwsV4Signer
import com.zillit.desktop.core.network.s3KeyPath
import com.zillit.desktop.feature.home.domain.NoticeAttachment
import com.zillit.desktop.feature.home.domain.NoticeMediaSource
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.readRawBytes
import io.ktor.http.isSuccess
import java.security.MessageDigest
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Reads notice media straight from S3, as the web does.
 *
 * Each fetch is a SigV4-signed `GET` against the bucket named **on the
 * attachment** — not the production's upload bucket, because a board holds
 * posts from before a region migration, and their files stay where they were
 * written.
 *
 * ## The cache
 *
 * In memory, keyed by object key, capped by total bytes with the oldest-touched
 * evicted first. Scrolling a board re-composes every bubble; refetching each
 * image per composition would make scrolling a network activity. Previews are
 * small, so the cap comfortably holds a screenful — full-size lightbox fetches
 * pass through the same cache and are what the eviction exists for.
 */
class S3NoticeMediaSource(
    private val httpClient: HttpClient,
    private val credentials: suspend () -> Pair<String, String>?,
    private val maxCacheBytes: Long = DEFAULT_CACHE_BYTES,
    private val now: () -> ZonedDateTime = { ZonedDateTime.now(ZoneOffset.UTC) },
) : NoticeMediaSource {

    private val lock = Mutex()
    private val cache = LinkedHashMap<String, ByteArray>(0, LOAD_FACTOR, true)
    private var cachedBytes = 0L

    override suspend fun fetch(
        attachment: NoticeAttachment,
        preview: Boolean,
    ): ZillitResult<ByteArray> {
        val key = if (preview) attachment.previewKey else attachment.media

        lock.withLock { cache[key] }?.let { return ZillitResult.Success(it) }

        if (!attachment.isFetchable) {
            return ZillitResult.Failure(
                ZillitError.Storage(
                    technical = "attachment names no bucket/region",
                    userMessage = "This file cannot be shown — it has no storage location.",
                ),
            )
        }

        return download(attachment, key).also { result ->
            if (result is ZillitResult.Success) remember(key, result.data)
        }
    }

    private suspend fun download(
        attachment: NoticeAttachment,
        key: String,
    ): ZillitResult<ByteArray> = withContext(Dispatchers.IO) {
        val keys = credentials() ?: return@withContext ZillitResult.Failure(
            ZillitError.Storage(
                technical = "no AWS credentials in the remote configuration",
                userMessage = "Media is unavailable — this workspace has no file storage configured.",
            ),
        )

        try {
            val host = "${attachment.bucket}.s3.${attachment.region}.amazonaws.com"
            val timestamp = now().format(AMZ_DATE)
            // Signed and sent as the same encoded path — a key with a space
            // signs one string and travels as another otherwise (a 403).
            val path = s3KeyPath(key)

            val authorization = AwsV4Signer.authorization(
                request = AwsRequest(
                    method = "GET",
                    path = path,
                    host = host,
                    payloadSha256 = EMPTY_SHA256,
                    timestamp = timestamp,
                    region = attachment.region.orEmpty(),
                ),
                accessKey = keys.first,
                secretKey = keys.second,
                hmacSha256 = ::hmacSha256,
                sha256Hex = ::sha256Hex,
            )

            val response = httpClient.get("https://$host$path") {
                header("Authorization", authorization)
                header("x-amz-date", timestamp)
                header("x-amz-content-sha256", EMPTY_SHA256)
            }

            if (response.status.isSuccess()) {
                ZillitResult.Success(response.readRawBytes())
            } else {
                // Never the key: file names and paths on a production are content.
                ZillitLog.w(TAG) { "media fetch rejected: ${response.status.value}" }
                ZillitResult.Failure(
                    ZillitError.Http(response.status.value, "Could not load this file."),
                )
            }
        } catch (cancellation: kotlinx.coroutines.CancellationException) {
            throw cancellation
        } catch (@Suppress("TooGenericExceptionCaught") throwable: Throwable) {
            ZillitLog.w(TAG) { "media fetch failed: ${throwable::class.simpleName}" }
            ZillitResult.Failure(ZillitError.NoConnection(throwable.message))
        }
    }

    private suspend fun remember(key: String, bytes: ByteArray) {
        // A single object larger than the whole cache would evict everything
        // and still not fit; it is simply not cached.
        if (bytes.size > maxCacheBytes) return

        lock.withLock {
            cache.remove(key)?.let { cachedBytes -= it.size }
            cache[key] = bytes
            cachedBytes += bytes.size

            val eldest = cache.entries.iterator()
            while (cachedBytes > maxCacheBytes && eldest.hasNext()) {
                cachedBytes -= eldest.next().value.size
                eldest.remove()
            }
        }
    }

    private companion object {
        const val TAG = "NoticeMedia"

        /** SHA-256 of the empty string — a GET has no payload. */
        const val EMPTY_SHA256 =
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"

        const val DEFAULT_CACHE_BYTES = 64L * 1024 * 1024
        const val LOAD_FACTOR = 0.75f

        val AMZ_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")

        fun hmacSha256(key: ByteArray, data: String): ByteArray =
            Mac.getInstance("HmacSHA256").run {
                init(SecretKeySpec(key, "HmacSHA256"))
                doFinal(data.toByteArray(Charsets.UTF_8))
            }

        fun sha256Hex(value: String): String =
            MessageDigest.getInstance("SHA-256")
                .digest(value.toByteArray(Charsets.UTF_8))
                .joinToString("") { byte -> "%02x".format(byte) }
    }
}
