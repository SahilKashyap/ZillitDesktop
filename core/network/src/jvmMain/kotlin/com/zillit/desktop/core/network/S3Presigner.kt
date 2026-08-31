package com.zillit.desktop.core.network

import java.security.MessageDigest
import java.time.Clock
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Presigned S3 GET URLs, for fetchers that cannot carry an `Authorization`
 * header.
 *
 * That is the OS browser opening a document, and any bare HTTP client. Both
 * phones do the same through the AWS SDK's `generatePresignedUrl`; the desktop
 * has no SDK, so [AwsV4Signer.presignedUrl] does the arithmetic and this
 * supplies the platform's crypto and clock.
 *
 * Returns null rather than throwing when the workspace has no credentials —
 * a production with no file storage configured is a state to report, not a
 * crash.
 */
class S3Presigner(
    private val credentials: suspend () -> Pair<String, String>?,
    private val clock: Clock = Clock.systemUTC(),
) {
    suspend fun presignedGet(
        bucket: String,
        region: String,
        key: String,
        ttlSeconds: Int = DEFAULT_TTL_SECONDS,
    ): String? {
        if (bucket.isBlank() || key.isBlank()) return null
        val (accessKey, secretKey) = credentials() ?: return null

        return AwsV4Signer.presignedUrl(
            request = AwsRequest(
                method = "GET",
                // Signed and carried as the same encoded path — a key with a
                // space signs one string and travels as another otherwise.
                path = s3KeyPath(key),
                host = host(bucket, region),
                payloadSha256 = "",
                timestamp = ZonedDateTime.now(clock).format(AMZ_DATE),
                region = region,
            ),
            accessKey = accessKey,
            secretKey = secretKey,
            ttlSeconds = ttlSeconds,
            hmacSha256 = ::hmacSha256,
            sha256Hex = ::sha256Hex,
        )
    }

    /**
     * `us-east-1` is the one region S3 also answers to without a region in the
     * host, and some rows carry no region at all.
     */
    private fun host(bucket: String, region: String): String =
        if (region.isBlank()) "$bucket.s3.amazonaws.com" else "$bucket.s3.$region.amazonaws.com"

    private companion object {
        /** Long enough to open and read; short enough that a leaked URL dies. */
        const val DEFAULT_TTL_SECONDS = 900

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
