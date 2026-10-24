package com.zillit.desktop

import com.zillit.desktop.feature.scriptsupervision.domain.SsPickedFile
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.close
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.io.readByteArray
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The script-supervision multipart body's arithmetic.
 *
 * This is tested rather than eyeballed because the same two mistakes have
 * shipped in this repo twice. `writeFully(src, startIndex, endIndex)` takes an
 * END INDEX, not a length: pass a length and the first chunk goes, every later
 * pass has `start == end`, the request falls short of its declared
 * `Content-Length`, and OkHttp fails it as "unexpected end of stream". Neither
 * that nor a wrong `Content-Length` shows on a file under one chunk (256 KB),
 * so every case here uses a file bigger than that.
 */
class ScriptSupervisionUploadBodyTest {

    /** Three chunks and a bit, so the loop runs several times and ends ragged. */
    private val big = ByteArray(700 * 1024) { (it % 251).toByte() }

    private fun file(bytes: ByteArray = big) =
        SsPickedFile(name = "the script.fdx", bytes = bytes, mime = "application/xml")

    /** The body as the engine would see it: written to a channel, read back whole. */
    private suspend fun render(
        body: io.ktor.http.content.OutgoingContent.WriteChannelContent,
    ): ByteArray = coroutineScope {
        val channel = ByteChannel(autoFlush = true)
        launch {
            body.writeTo(channel)
            channel.close()
        }
        channel.readRemaining().readByteArray()
    }

    @Test
    fun `the whole file is written, not just its first chunk`() = runTest {
        val body = multipartProgressBody(emptyMap(), file()) { }

        val written = render(body)

        // The end-index bug shows here and nowhere else: it would send the
        // prefix plus ONE 256 KB chunk and stop.
        assertEquals(body.contentLength, written.size.toLong(), "short body: check the end index")
        assertTrue(written.size > big.size, "the envelope must carry the whole file")
    }

    @Test
    fun `the declared Content-Length is exactly what is written`() = runTest {
        // A wrong declared length is the other way OkHttp fails the request,
        // and it is silent until the engine reads the stream.
        val body = multipartProgressBody(mapOf("folder" to "characters"), file()) { }

        assertEquals(body.contentLength, render(body).size.toLong())
    }

    @Test
    fun `the file's bytes survive the chunking byte for byte`() = runTest {
        val body = multipartProgressBody(emptyMap(), file()) { }

        val written = render(body)
        // The file sits between the part headers and the closing boundary.
        val start = written.size - big.size - CLOSING_BYTES
        val carried = written.copyOfRange(start, start + big.size)

        assertTrue(carried.contentEquals(big), "the chunk loop reordered or dropped bytes")
    }

    @Test
    fun `progress climbs to a hundred and never goes backwards`() = runTest {
        val seen = mutableListOf<Int>()

        render(multipartProgressBody(emptyMap(), file()) { seen += it })

        assertTrue(seen.size > 1, "a multi-chunk file should report more than once")
        assertEquals(100, seen.last())
        assertEquals(seen.sorted(), seen, "progress went backwards")
        assertTrue(seen.all { it in 0..100 }, "progress left 0..100: $seen")
    }

    @Test
    fun `the fields the route reads are in the envelope, before the file`() = runTest {
        val body = multipartProgressBody(mapOf("folder" to "continuity-photos"), file()) { }

        val text = render(body).decodeToString()

        // `/upload/file` reads `folder`; without it the photo lands nowhere useful.
        assertContains(text, "name=\"folder\"")
        assertContains(text, "continuity-photos")
        assertContains(text, "name=\"file\"; filename=\"the script.fdx\"")
        assertTrue(
            text.indexOf("name=\"folder\"") < text.indexOf("name=\"file\""),
            "the file part must come last",
        )
    }

    @Test
    fun `a file smaller than one chunk still reports a hundred`() = runTest {
        val seen = mutableListOf<Int>()
        val small = file(ByteArray(10) { 1 })

        val body = multipartProgressBody(emptyMap(), small) { seen += it }
        val written = render(body)

        assertEquals(listOf(100), seen)
        assertEquals(body.contentLength, written.size.toLong())
    }

    private companion object {
        /** `\r\n--<boundary>--\r\n`: 2 + 2 + 8 + 32 + 2 + 2. */
        const val CLOSING_BYTES = 48
    }
}
