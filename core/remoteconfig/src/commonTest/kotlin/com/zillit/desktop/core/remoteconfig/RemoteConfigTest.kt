package com.zillit.desktop.core.remoteconfig

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Decrypting the `GET api/v2/configuration` payload.
 *
 * The transport is covered by `ApiClientTest`; what matters here is which
 * fields get decrypted, what happens when one cannot be, and that no credential
 * reaches a string representation.
 */
class RemoteConfigTest {

    /** Identity-with-a-prefix, so a test can read what the real engine would hide. */
    private val fakeDecryptor = SecretDecryptor { hex ->
        if (hex.startsWith("ENC:")) {
            ZillitResult.Success(hex.removePrefix("ENC:"))
        } else {
            ZillitResult.Failure(ZillitError.Crypto("not decryptable"))
        }
    }

    private fun dto(
        map: String? = "ENC:maps-key",
        places: String? = "ENC:places-key",
        awsAccess: String? = "ENC:aws-access",
        awsSecret: String? = "ENC:aws-secret",
        gpt: String? = "ENC:gpt-token",
        download: String? = "https://zillit.com/download",
    ) = ConfigDataDto(map, places, awsAccess, awsSecret, gpt, download)

    /** The production mapping, not a copy of it. */
    private fun credentials(dto: ConfigDataDto) = dto.toCredentials(fakeDecryptor)

    @Test
    fun `the five encrypted fields are decrypted`() {
        val result = credentials(dto())

        assertEquals("maps-key", result.googleMapsKey)
        assertEquals("places-key", result.googlePlacesKey)
        assertEquals("aws-access", result.awsAccessKey)
        assertEquals("aws-secret", result.awsSecretKey)
        assertEquals("gpt-token", result.chatGptTranslationToken)
    }

    @Test
    fun `the download url is passed through undecrypted`() {
        // Android does not decrypt this one, and it is a public URL. Running it
        // through the decryptor would null it out.
        val result = credentials(dto())

        assertEquals("https://zillit.com/download", result.appDownloadUrl)
    }

    @Test
    fun `a field that will not decrypt becomes null, never ciphertext`() {
        // The Android version returns the input unchanged on failure, which
        // hands a hex blob to the Maps SDK as though it were an API key.
        val result = credentials(dto(map = "deadbeefdeadbeef"))

        assertNull(result.googleMapsKey, "ciphertext was passed off as a key")
    }

    @Test
    fun `one bad field does not discard the others`() {
        val result = credentials(dto(map = "deadbeef"))

        assertNull(result.googleMapsKey)
        assertEquals("aws-secret", result.awsSecretKey, "a Maps failure took AWS down with it")
    }

    @Test
    fun `absent fields stay null rather than becoming empty strings`() {
        // A feature asking for a credential that was never issued should get a
        // clear null, not "" failing later inside a third-party SDK.
        val result = credentials(dto(map = null, places = "", gpt = null))

        assertNull(result.googleMapsKey)
        assertNull(result.googlePlacesKey)
        assertNull(result.chatGptTranslationToken)
    }

    @Test
    fun `toString never prints a credential`() {
        // These reach the log the moment anyone debugs an upload failure.
        val text = credentials(dto()).toString()

        listOf("maps-key", "places-key", "aws-access", "aws-secret", "gpt-token").forEach {
            assertFalse(text.contains(it), "$it leaked via toString(): $text")
        }
        assertTrue(text.contains("https://zillit.com/download"), "the public URL should stay readable")
    }

    @Test
    fun `availability is reportable without naming a value`() {
        // "Maps failed" and "Maps was never issued a key" look identical from a
        // stack trace.
        val summary = credentials(dto(map = null)).describeAvailability()

        assertEquals("maps=no, places=yes, aws=yes, translation=yes", summary)
    }
}
