package com.zillit.desktop.feature.home

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.home.data.S3NoticeMediaSource
import com.zillit.desktop.feature.home.domain.NoticeAttachment
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.test.runTest

/**
 * A post whose file is gone.
 *
 * The bytes cache only holds what came back, so until an absent object was
 * remembered as absent, every re-composition of the bubble re-signed and
 * re-sent the fetch: one window logged 687 rejected fetches, in bursts of
 * eight and ten — a screenful of bubbles asking again on each scroll.
 */
class MissingMediaTest {

    private val attachment = NoticeAttachment(
        media = "notice/unit 1/gone.jpg",
        fileName = "gone.jpg",
        bucket = "zillit-media",
        region = "ap-south-1",
    )

    private var asked = 0

    private fun source(status: HttpStatusCode) = S3NoticeMediaSource(
        httpClient = HttpClient(
            MockEngine {
                asked += 1
                respond("", status)
            },
        ),
        credentials = { "AKIAEXAMPLE" to "secret" },
        now = { ZonedDateTime.of(2026, 10, 1, 9, 30, 0, 0, ZoneOffset.UTC) },
    )

    @Test
    fun `a file S3 says is not there is asked for once, however often it is drawn`() = runTest {
        val media = source(HttpStatusCode.NotFound)

        assertIs<ZillitResult.Failure>(media.fetch(attachment, preview = true))
        assertEquals(1, asked)

        repeat(30) { assertIs<ZillitResult.Failure>(media.fetch(attachment, preview = true)) }
        assertEquals(1, asked, "the bubble re-composed; S3 was not asked again")
    }

    /** And it still reads as the refusal it is, so the bubble draws its placeholder. */
    @Test
    fun `the remembered answer is the same refusal S3 gave`() = runTest {
        val media = source(HttpStatusCode.NotFound)

        val asked = (media.fetch(attachment, preview = true) as ZillitResult.Failure).error
        val remembered = (media.fetch(attachment, preview = true) as ZillitResult.Failure).error

        assertEquals(asked::class, remembered::class)
    }

    /**
     * Only a 404 is the object being absent. A 403 is a signature or a clock,
     * which the next attempt may well get right — refusing to retry those
     * would turn a passing cloud into a permanently broken board.
     */
    @Test
    fun `a refusal that is not 404 is asked again`() = runTest {
        val media = source(HttpStatusCode.Forbidden)

        assertIs<ZillitResult.Failure>(media.fetch(attachment, preview = true))
        assertIs<ZillitResult.Failure>(media.fetch(attachment, preview = true))

        assertEquals(2, asked, "a 403 may come right; it is not the object being gone")
    }

    /** The full-size fetch is its own key, so the lightbox is not barred by a missing thumbnail. */
    @Test
    fun `the preview being gone does not bar the object itself`() = runTest {
        val withThumbnail = attachment.copy(thumbnail = "notice/unit 1/gone-thumb.jpg")
        val media = source(HttpStatusCode.NotFound)

        media.fetch(withThumbnail, preview = true)
        assertEquals(1, asked)

        media.fetch(withThumbnail, preview = false)
        assertEquals(2, asked, "a different key, and nobody has said it is missing")
    }
}
