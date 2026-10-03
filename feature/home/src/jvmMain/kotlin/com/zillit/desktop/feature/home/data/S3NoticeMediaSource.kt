package com.zillit.desktop.feature.home.data

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitLog
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.network.AwsRequest
import com.zillit.desktop.core.network.AwsV4Signer
import com.zillit.desktop.core.network.s3KeyPath
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
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
                    userMessage = str(S.desktop_file_no_storage_location),
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
                userMessage = str(S.desktop_media_no_file_storage),
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
                    ZillitError.Http(response.status.value, str(S.desktop_could_not_load_file)),
                )
            }
        } catch (cancellation: kotlinx.coroutines.CancellationException) {
            throw cancellation
        } catch (@Suppress("TooGenericExceptionCaught") throwable: Throwable) {
            ZillitLog.w(TAG) { "media fetch failed: ${throwable::class.simpleName}" }
            ZillitResult.Failure(ZillitError.NoConnection(throwable.message))
        }
    }

    /**
     * A presigned GET for the whole object — what the in-app video viewer
     * plays.
     *
     * The fetcher is the platform's media stack, which cannot carry the
     * `Authorization` header [download] signs, so the signature travels in the
     * query string instead ([AwsV4Signer.presignedUrl], the same arithmetic
     * both phones get from the AWS SDK). S3 answers range requests on it, so
     * the clip starts on its first seconds rather than its last byte.
     *
     * **`response-content-type` is not cosmetic.** The player chooses its
     * demuxer from the content type S3 answers with, never from the key's
     * extension — so an iPhone's `.mov`, which is the same H.264 in the same
     * ISO container as an `.mp4`, plays only because this says so. A key with
     * no extension at all plays for the same reason. It is signed along with
     * everything else; appended afterwards it would be a parameter the
     * signature did not cover, and S3 would answer 403.
     *
     * It is **not** a blanket `video/mp4`, though it began as one. The web
     * client records with `MediaRecorder`, which writes WebM, and telling a
     * player that a WebM is an MP4 is how a file that plays everywhere else
     * stops playing here. See [forcedContentType].
     *
     * Short-lived on purpose: long enough to watch a reel through, short
     * enough that the URL is worth little by the time anyone finds it.
     */
    override suspend fun streamUrl(attachment: NoticeAttachment): String? {
        if (!attachment.isFetchable || attachment.media.isBlank()) return null
        val keys = credentials() ?: return null

        return AwsV4Signer.presignedUrl(
            request = AwsRequest(
                method = "GET",
                path = s3KeyPath(attachment.media),
                host = "${attachment.bucket}.s3.${attachment.region}.amazonaws.com",
                payloadSha256 = "",
                timestamp = now().format(AMZ_DATE),
                region = attachment.region.orEmpty(),
            ),
            accessKey = keys.first,
            secretKey = keys.second,
            ttlSeconds = STREAM_TTL_SECONDS,
            hmacSha256 = ::hmacSha256,
            sha256Hex = ::sha256Hex,
            extra = mapOf("response-content-type" to forcedContentType(attachment)),
        )
    }

    /**
     * What S3 is asked to answer with for this object.
     *
     * Only the containers that are genuinely something else get named; the
     * default stays `video/mp4` because that is what makes a `.mov` — and a
     * key with no extension at all — playable, which was the whole point of
     * overriding the stored type in the first place.
     *
     * Read from the file's name rather than its stored content type: the
     * stored one is whatever the uploading client claimed, and `.mov` arriving
     * as `video/quicktime` is exactly the case being corrected here.
     */
    private fun forcedContentType(attachment: NoticeAttachment): String {
        // Both the display name and the object key are consulted, and either
        // may be the one carrying the extension: the key is built from the
        // picked file's name at upload, but a row can arrive with the name
        // renamed, blank, or the key rewritten. One of the two naming a
        // container is enough.
        val names = listOf(attachment.fileName, attachment.media)
        return names.firstNotNullOfOrNull { name ->
            when (name.substringAfterLast('.', "").lowercase()) {
                "webm" -> WEBM_CONTENT_TYPE
                "ogv", "ogg" -> OGG_CONTENT_TYPE
                else -> null
            }
        } ?: STREAM_CONTENT_TYPE
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

        /** Four hours: longer than any clip a board carries, and it then dies. */
        const val STREAM_TTL_SECONDS = 4 * 60 * 60

        /**
         * What S3 is asked to answer with, whatever the object was stored as.
         *
         * Every clip any Zillit client uploads is H.264 in an ISO container —
         * `.mp4` from Android and the web, `.mov` from iOS, the same bytes
         * under two names. Saying so is what lets the second one play.
         */
        const val STREAM_CONTENT_TYPE = "video/mp4"

        /** What the web client's `MediaRecorder` writes. */
        const val WEBM_CONTENT_TYPE = "video/webm"

        const val OGG_CONTENT_TYPE = "video/ogg"

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
