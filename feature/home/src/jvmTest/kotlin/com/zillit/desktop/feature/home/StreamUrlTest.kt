package com.zillit.desktop.feature.home

import com.zillit.desktop.feature.home.data.S3NoticeMediaSource
import com.zillit.desktop.feature.home.domain.NoticeAttachment
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * The address the in-app video player is handed.
 *
 * It matters more than it looks: the player is Chromium, which cannot carry
 * the `Authorization` header every other fetch here signs, so everything has
 * to be in the query string. A signature that is merely *nearly* right comes
 * back as a 403 the page reports as a dead video, which reads as a broken
 * feature rather than a wrong string.
 */
class StreamUrlTest {

    private val clip = NoticeAttachment(
        media = "notice/unit 1/clip.mp4",
        fileName = "clip.mp4",
        bucket = "zillit-media",
        region = "ap-south-1",
    )

    private fun source(credentials: Pair<String, String>?) = S3NoticeMediaSource(
        httpClient = HttpClient(MockEngine { respond("") }),
        credentials = { credentials },
        now = { ZonedDateTime.of(2026, 10, 1, 9, 30, 0, 0, ZoneOffset.UTC) },
    )

    @Test
    fun `the clip's URL carries its whole signature in the query`() = runTest {
        val url = source("AKIAEXAMPLE" to "secret").streamUrl(clip)

        requireNotNull(url)
        assertTrue(url.startsWith("https://zillit-media.s3.ap-south-1.amazonaws.com/"), url)
        // The key is encoded in the path, and signed as it travels — a space
        // that signs one string and travels as another is a 403.
        assertTrue(url.contains("/notice/unit%201/clip.mp4?"), url)

        val query = url.substringAfter('?').split('&').associate {
            it.substringBefore('=') to it.substringAfter('=')
        }
        // The player picks its demuxer from the answered content type, not from
        // the key — this is the whole reason an iPhone's `.mov` plays at all.
        // Signed, not appended: appended, S3 answers 403.
        assertEquals("video%2Fmp4", query["response-content-type"])
        assertEquals("AWS4-HMAC-SHA256", query["X-Amz-Algorithm"])
        assertEquals("20261001T093000Z", query["X-Amz-Date"])
        assertEquals("host", query["X-Amz-SignedHeaders"])
        assertTrue(query.getValue("X-Amz-Credential").endsWith("ap-south-1%2Fs3%2Faws4_request"))
        assertTrue(query.getValue("X-Amz-Signature").length == SIGNATURE_HEX_LENGTH)
    }

    @Test
    fun `a key under any name is asked for as mp4`() = runTest {
        // Android and the web write `.mp4`, iOS writes `.mov`, a Drive share
        // can carry no extension at all — one container, three names.
        val names = listOf("clip.mp4", "IMG_0042.mov", "notice/abc123")
        val signer = source("AKIAEXAMPLE" to "secret")
        names.forEach { key ->
            val url = requireNotNull(signer.streamUrl(clip.copy(media = key)))
            assertTrue(url.contains("response-content-type=video%2Fmp4"), key)
        }
    }

    @Test
    fun `a webm is not called an mp4`() = runTest {
        // The web client records with `MediaRecorder`, which writes WebM, and
        // the player picks its demuxer from the answered type — so calling one
        // `video/mp4` is how a file that plays everywhere else stops playing
        // here. It is also what routes the viewer to the engine that can
        // decode it, so getting this wrong costs twice.
        val signer = source("AKIAEXAMPLE" to "secret")
        val webm = requireNotNull(signer.streamUrl(clip.copy(media = "recording_1789.webm")))
        assertTrue(webm.contains("response-content-type=video%2Fwebm"), webm)

        // And the default is unchanged for everything else, which is what
        // makes a `.mov` — and a key with no extension — playable at all.
        listOf("clip.mp4", "IMG_0042.mov", "notice/abc123").forEach { key ->
            val url = requireNotNull(signer.streamUrl(clip.copy(media = key)))
            assertTrue(url.contains("response-content-type=video%2Fmp4"), key)
        }
    }

    @Test
    fun `the name decides, not the content type the uploader claimed`() = runTest {
        // A `.mov` arrives as `video/quicktime`, which is exactly the claim
        // being corrected — so the stored type is not consulted.
        val signer = source("AKIAEXAMPLE" to "secret")
        val url = requireNotNull(
            signer.streamUrl(clip.copy(media = "a.webm", contentType = "video/quicktime")),
        )
        assertTrue(url.contains("response-content-type=video%2Fwebm"), url)
    }

    @Test
    fun `the same object signs the same way twice`() = runTest {
        val source = source("AKIAEXAMPLE" to "secret")
        assertEquals(source.streamUrl(clip), source.streamUrl(clip))
    }

    @Test
    fun `nothing to sign with, or nothing to sign, answers null rather than a dead URL`() = runTest {
        // No credentials on the production.
        assertNull(source(null).streamUrl(clip))
        // A row from before a region migration that names no bucket at all.
        val signer = source("AKIAEXAMPLE" to "secret")
        assertNull(signer.streamUrl(clip.copy(bucket = null)))
        assertNull(signer.streamUrl(clip.copy(region = "")))
        assertNull(signer.streamUrl(clip.copy(media = "")))
    }

    private companion object {
        /** SHA-256, hex. */
        const val SIGNATURE_HEX_LENGTH = 64
    }
}
