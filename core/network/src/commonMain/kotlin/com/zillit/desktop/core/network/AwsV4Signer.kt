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

/** Lowercase hex, which is what every part of SigV4 expects. */
fun ByteArray.toHex(): String = joinToString("") { byte ->
    val value = byte.toInt() and BYTE_MASK
    HEX[value shr NIBBLE_BITS].toString() + HEX[value and NIBBLE_MASK]
}

private const val HEX = "0123456789abcdef"
private const val BYTE_MASK = 0xFF
private const val NIBBLE_MASK = 0x0F
private const val NIBBLE_BITS = 4
