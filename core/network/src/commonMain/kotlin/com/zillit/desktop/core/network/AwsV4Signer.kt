package com.zillit.desktop.core.network

/**
 * What a signed request needs.
 *
 * [payloadSha256] is hex-encoded. S3 requires it in the `x-amz-content-sha256`
 * header and in the signature, so it is computed once by the caller rather than
 * twice here.
 */
data class AwsRequest(
    val method: String,
    /** Path only, already percent-encoded, beginning with `/`. */
    val path: String,
    val host: String,
    val payloadSha256: String,
    /** `yyyyMMdd'T'HHmmss'Z'` in UTC. */
    val timestamp: String,
    val region: String,
    val service: String = "s3",
    /** Extra headers to sign, beyond host, date and content hash. */
    val extraHeaders: Map<String, String> = emptyMap(),
    /**
     * Whether to sign `x-amz-content-sha256`.
     *
     * S3 requires it; the generic examples in AWS's test suite do not send it.
     * Off only so this can be checked against those published vectors exactly.
     */
    val signsContentHash: Boolean = true,
)

/**
 * Signs requests for AWS, Signature Version 4.
 *
 * ## Why this exists rather than the AWS SDK
 *
 * The SDK is tens of megabytes of transitive dependencies for one operation:
 * a single `PUT` of an attachment to S3. This app ships to three desktop
 * platforms and the whole of what it needs is an `Authorization` header.
 *
 * The trade is that a signing mistake is *silent* — the request simply comes
 * back 403 with no clue which of the six steps was wrong. Hence the test
 * vectors: the algorithm is verified against AWS's own published example rather
 * than against whether an upload happened to work.
 *
 * ## A note on the credentials this signs with
 *
 * They arrive from the `configuration` endpoint, which means every client holds
 * long-lived keys for the bucket. That is the existing architecture — the web
 * app does the same — but it is worth knowing that anyone with the app has the
 * credentials, and the fix is server-issued presigned URLs, not anything this
 * file can do.
 */
object AwsV4Signer {

    private const val ALGORITHM = "AWS4-HMAC-SHA256"

    /** The only header a browser or bare client is sure to send. */
    private const val SIGNED_HEADER = "host"

    /** What a body-less GET signs as. */
    private const val UNSIGNED_PAYLOAD = "UNSIGNED-PAYLOAD"

    /**
     * The `Authorization` header value.
     *
     * [hmacSha256] and [sha256Hex] are injected so this stays in `commonMain`
     * and testable — the platform supplies the primitives.
     */
    fun authorization(
        request: AwsRequest,
        accessKey: String,
        secretKey: String,
        hmacSha256: (key: ByteArray, data: String) -> ByteArray,
        sha256Hex: (String) -> String,
    ): String {
        val headers = request.canonicalHeaders()
        val signedHeaders = headers.keys.joinToString(";")
        val date = request.timestamp.substringBefore('T')
        val scope = "$date/${request.region}/${request.service}/aws4_request"

        val canonicalRequest = listOf(
            request.method,
            request.path,
            // No query parameters: every call this signs is a plain object PUT.
            "",
            headers.entries.joinToString("") { (name, value) -> "$name:$value\n" },
            signedHeaders,
            request.payloadSha256,
        ).joinToString("\n")

        val stringToSign = listOf(
            ALGORITHM,
            request.timestamp,
            scope,
            sha256Hex(canonicalRequest),
        ).joinToString("\n")

        val signingKey = signingKey(secretKey, date, request.region, request.service, hmacSha256)
        val signature = hmacSha256(signingKey, stringToSign).toHex()

        return "$ALGORITHM Credential=$accessKey/$scope, " +
            "SignedHeaders=$signedHeaders, Signature=$signature"
    }

    /**
     * A presigned GET URL — signature in the query string rather than a header.
     *
     * Needed wherever the fetcher cannot carry an `Authorization` header: the
     * OS browser opening a document, or a bare HTTP client fetching bytes for
     * a production whose files live in S3. Both phones do the same thing
     * through the AWS SDK's `generatePresignedUrl`.
     *
     * Only `host` is signed, because it is the only header the eventual
     * fetcher is guaranteed to send. The payload is `UNSIGNED-PAYLOAD`, which
     * is what a GET with no body signs as.
     *
     * [ttlSeconds] is how long the URL stays good; AWS refuses more than seven
     * days.
     */
    fun presignedUrl(
        request: AwsRequest,
        accessKey: String,
        secretKey: String,
        ttlSeconds: Int,
        hmacSha256: (key: ByteArray, data: String) -> ByteArray,
        sha256Hex: (String) -> String,
    ): String {
        val date = request.timestamp.substringBefore('T')
        val scope = "$date/${request.region}/${request.service}/aws4_request"

        // Sorted by name, and each part encoded the way the signature reads it
        // — the credential's slashes become %2F or the signatures disagree.
        val query = listOf(
            "X-Amz-Algorithm" to ALGORITHM,
            "X-Amz-Credential" to "$accessKey/$scope",
            "X-Amz-Date" to request.timestamp,
            "X-Amz-Expires" to ttlSeconds.toString(),
            "X-Amz-SignedHeaders" to SIGNED_HEADER,
        ).sortedBy { it.first }
            .joinToString("&") { (name, value) -> "${uriEncode(name)}=${uriEncode(value)}" }

        val canonicalRequest = listOf(
            request.method,
            request.path,
            query,
            "$SIGNED_HEADER:${request.host}\n",
            SIGNED_HEADER,
            UNSIGNED_PAYLOAD,
        ).joinToString("\n")

        val stringToSign = listOf(
            ALGORITHM,
            request.timestamp,
            scope,
            sha256Hex(canonicalRequest),
        ).joinToString("\n")

        val signingKey = signingKey(secretKey, date, request.region, request.service, hmacSha256)
        val signature = hmacSha256(signingKey, stringToSign).toHex()

        return "https://${request.host}${request.path}?$query&X-Amz-Signature=$signature"
    }

    /** Every header that gets signed, lowercased and sorted as the spec requires. */
    fun AwsRequest.canonicalHeaders(): Map<String, String> = buildMap {
        put("host", host)
        if (signsContentHash) put("x-amz-content-sha256", payloadSha256)
        put("x-amz-date", timestamp)
        extraHeaders.forEach { (name, value) -> put(name.lowercase(), value.trim()) }
    }.toSortedMap()

    /**
     * The four-step key derivation.
     *
     * Chained deliberately — each step's output is the next step's key, which is
     * what scopes a signature to one date, region and service and stops a
     * leaked signature working anywhere else.
     */
    private fun signingKey(
        secretKey: String,
        date: String,
        region: String,
        service: String,
        hmacSha256: (ByteArray, String) -> ByteArray,
    ): ByteArray {
        val initial = "AWS4$secretKey".encodeToByteArray()
        val dateKey = hmacSha256(initial, date)
        val regionKey = hmacSha256(dateKey, region)
        val serviceKey = hmacSha256(regionKey, service)
        return hmacSha256(serviceKey, "aws4_request")
    }
}

/**
 * An S3 object key as the path SigV4 signs and the URL carries — every
 * segment percent-encoded per RFC 3986 (unreserved bytes kept, `/` kept),
 * as the SDK's `GetObjectCommand` does.
 *
 * The two MUST agree byte for byte: signing the raw key while the HTTP
 * client encodes a space to `%20` on the way out is a silent 403 — which is
 * exactly what a report the server named with spaces did, while every
 * key this app writes itself (sanitised to `[A-Za-z0-9._-]`) sailed through.
 */
fun s3KeyPath(key: String): String =
    "/" + key.split('/').joinToString("/") { segment -> uriEncodeSegment(segment) }

/**
 * One RFC 3986 encoding, used for both a path segment and a query part.
 *
 * `/` is *not* unreserved, so it encodes — which is what a query value needs
 * (the credential's scope is full of slashes) and what [s3KeyPath] relies on
 * by splitting the key first and joining the results back with a literal `/`.
 */
internal fun uriEncode(value: String): String = uriEncodeSegment(value)

private fun uriEncodeSegment(segment: String): String = buildString {
    for (byte in segment.encodeToByteArray()) {
        val value = byte.toInt() and BYTE_MASK
        val char = value.toChar()
        if (char.isUnreserved()) {
            append(char)
        } else {
            append('%')
            append(HEX[value shr NIBBLE_BITS].uppercaseChar())
            append(HEX[value and NIBBLE_MASK].uppercaseChar())
        }
    }
}

/** RFC 3986 unreserved: letters, digits, and `-_.~` — everything else is percent-encoded. */
private fun Char.isUnreserved(): Boolean =
    this in 'A'..'Z' || this in 'a'..'z' || this in '0'..'9' || this in "-_.~"

/** Lowercase hex, which is what every part of SigV4 expects. */
fun ByteArray.toHex(): String = joinToString("") { byte ->
    val value = byte.toInt() and BYTE_MASK
    HEX[value shr NIBBLE_BITS].toString() + HEX[value and NIBBLE_MASK]
}

private const val HEX = "0123456789abcdef"
private const val BYTE_MASK = 0xFF
private const val NIBBLE_MASK = 0x0F
private const val NIBBLE_BITS = 4
