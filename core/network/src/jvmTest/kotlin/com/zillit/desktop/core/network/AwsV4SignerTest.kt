package com.zillit.desktop.core.network

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Signature Version 4, against AWS's own published example.
 *
 * A signing mistake is silent — the request comes back 403 with no clue which
 * of the six steps was wrong — so this is checked against the canonical
 * `get-vanilla` case from AWS's SigV4 test suite rather than against whether an
 * upload happened to work.
 */
class AwsV4SignerTest {

    private fun hmac(key: ByteArray, data: String): ByteArray =
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }
            .doFinal(data.toByteArray(Charsets.UTF_8))

    private fun sha256Hex(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).toHex()

    private fun sign(request: AwsRequest, key: String = ACCESS_KEY, secret: String = SECRET) =
        AwsV4Signer.authorization(request, key, secret, ::hmac, ::sha256Hex)

    @Test
    fun `the published get-vanilla example signs as AWS says it should`() {
        // From the AWS SigV4 test suite. The empty-payload hash below is
        // SHA-256 of "", which every GET and every empty PUT uses.
        val signed = sign(
            AwsRequest(
                method = "GET",
                path = "/",
                host = "example.amazonaws.com",
                payloadSha256 = EMPTY_SHA256,
                timestamp = "20150830T123600Z",
                region = "us-east-1",
                service = "service",
                // The published vector does not send the content hash header.
                signsContentHash = false,
            ),
        )

        assertEquals(
            "AWS4-HMAC-SHA256 Credential=AKIDEXAMPLE/20150830/us-east-1/service/aws4_request, " +
                "SignedHeaders=host;x-amz-date, " +
                "Signature=5fa00fa31553b73ebf1942676e86291e8372ff2a2260956d9b8aae1d763fbf31",
            signed,
        )
    }

    @Test
    fun `the signing key is scoped to date, region and service`() {
        // The chain is what stops a leaked signature working anywhere else, so
        // each element of the scope must change the signature.
        val base = AwsRequest(
            method = "PUT",
            path = "/bucket/key.pdf",
            host = "bucket.s3.eu-west-1.amazonaws.com",
            payloadSha256 = EMPTY_SHA256,
            timestamp = "20240101T000000Z",
            region = "eu-west-1",
        )

        val signature = sign(base).signature()

        assertTrue(signature != sign(base.copy(region = "us-east-1")).signature(), "region")
        assertTrue(signature != sign(base.copy(service = "s3-outposts")).signature(), "service")
        assertTrue(signature != sign(base.copy(timestamp = "20240102T000000Z")).signature(), "date")
    }

    @Test
    fun `the payload hash is part of the signature`() {
        // Otherwise a signed request could have its body swapped in flight.
        val base = AwsRequest(
            method = "PUT",
            path = "/bucket/key.pdf",
            host = "bucket.s3.amazonaws.com",
            payloadSha256 = EMPTY_SHA256,
            timestamp = "20240101T000000Z",
            region = "us-east-1",
        )

        val other = base.copy(payloadSha256 = sha256Hex("some other content"))

        assertTrue(sign(base).signature() != sign(other).signature())
    }

    @Test
    fun `extra headers are signed, lowercased and sorted`() {
        val request = AwsRequest(
            method = "PUT",
            path = "/bucket/key.pdf",
            host = "bucket.s3.amazonaws.com",
            payloadSha256 = EMPTY_SHA256,
            timestamp = "20240101T000000Z",
            region = "us-east-1",
            extraHeaders = mapOf("Content-Type" to "application/pdf"),
        )

        val signed = sign(request)

        assertTrue(
            signed.contains("SignedHeaders=content-type;host;x-amz-content-sha256;x-amz-date"),
            signed,
        )
    }

    @Test
    fun `a different secret produces a different signature`() {
        val request = AwsRequest(
            method = "PUT",
            path = "/bucket/key.pdf",
            host = "bucket.s3.amazonaws.com",
            payloadSha256 = EMPTY_SHA256,
            timestamp = "20240101T000000Z",
            region = "us-east-1",
        )

        assertTrue(sign(request).signature() != sign(request, secret = "other").signature())
    }

    @Test
    fun `hex encoding is lowercase and zero-padded`() {
        // An uppercase or unpadded byte anywhere in the chain invalidates the
        // whole signature, and the server will not say so.
        assertEquals("000f10ff", byteArrayOf(0, 15, 16, -1).toHex())
    }

    private fun String.signature(): String = substringAfter("Signature=")

    private companion object {
        const val ACCESS_KEY = "AKIDEXAMPLE"
        const val SECRET = "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY"
        const val EMPTY_SHA256 =
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
    }

    @Test
    fun `an object key becomes a percent-encoded path, slashes and unreserved bytes kept`() {
        assertEquals("/a/plain_key-1.pdf", s3KeyPath("a/plain_key-1.pdf"))
        assertEquals("/recce/Desktop%20recce%20test.pdf", s3KeyPath("recce/Desktop recce test.pdf"))
        assertEquals("/p/caf%C3%A9%20%26%20co%2Bx.pdf", s3KeyPath("p/café & co+x.pdf"))
        assertEquals("/~tilde/", s3KeyPath("~tilde/"))
    }
    /**
     * AWS's own worked example for a presigned GET
     * ("Example: Signature Calculation for Presigned URL", S3 developer guide).
     *
     * The whole reason this signer is hand-written rather than the SDK is that
     * a mistake here is silent — a presigned URL that is wrong comes back 403
     * with nothing to say which of the six steps drifted. So it is checked
     * against the published signature, digit for digit.
     */
    @Test
    fun `the published presigned-url example signs as AWS says it should`() {
        val url = AwsV4Signer.presignedUrl(
            request = AwsRequest(
                method = "GET",
                path = "/test.txt",
                host = "examplebucket.s3.amazonaws.com",
                payloadSha256 = "",
                timestamp = "20130524T000000Z",
                region = "us-east-1",
            ),
            accessKey = "AKIAIOSFODNN7EXAMPLE",
            secretKey = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY",
            ttlSeconds = 86_400,
            hmacSha256 = ::hmac,
            sha256Hex = ::sha256Hex,
        )

        assertTrue(
            url.endsWith(
                "&X-Amz-Signature=aeeed9bbccd4d02ee5c0109b86d86835f995330da4c265957d157751f604d404",
            ),
            "signature disagrees with AWS's worked example: $url",
        )
        // The credential's slashes must travel encoded, or the signature the
        // server recomputes reads a different string than the one signed.
        assertTrue(
            url.contains("X-Amz-Credential=AKIAIOSFODNN7EXAMPLE%2F20130524%2Fus-east-1%2Fs3%2Faws4_request"),
            "credential is not encoded as the signature reads it: $url",
        )
    }

    /** A URL that has expired by the time it is opened is worse than none. */
    @Test
    fun `the expiry travels in the query, and changes the signature`() {
        val request = AwsRequest(
            method = "GET",
            path = "/test.txt",
            host = "examplebucket.s3.amazonaws.com",
            payloadSha256 = "",
            timestamp = "20130524T000000Z",
            region = "us-east-1",
        )
        fun signed(ttl: Int) = AwsV4Signer.presignedUrl(
            request, "AKIAIOSFODNN7EXAMPLE", "secret", ttl, ::hmac, ::sha256Hex,
        )

        assertTrue(signed(900).contains("X-Amz-Expires=900"))
        assertTrue(signed(900) != signed(901), "expiry is not part of the signature")
    }

}
