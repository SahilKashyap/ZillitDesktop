package com.zillit.desktop.core.media

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage

/**
 * How a received file is decided and drawn — the one gate every surface that
 * shows an attachment goes through, so a format that opens here opens on the
 * notice board, in a chat thread and in the mailbox alike.
 */
class DocumentBodyTest {

    @Test
    fun `a PDF renders a page per page`() {
        val pages = assertIs<DocumentBody.Pages>(
            renderDocumentBody("callsheet.pdf", "application/pdf", pdf(pages = 3)),
        )
        assertEquals(3, pages.pages.size)
    }

    @Test
    fun `a PDF is recognised by its magic number alone`() {
        // What a Box production and a Drive share actually send: no extension
        // on the name, and `application/octet-stream` for a type. Read by
        // either, this is a file nobody can open; read by its first four
        // bytes, it is a script.
        assertIs<DocumentBody.Pages>(
            renderDocumentBody("attachment", "application/octet-stream", pdf(pages = 1)),
        )
    }

    @Test
    fun `an encrypted or truncated PDF falls back to the download`() {
        assertEquals(
            DocumentBody.Unsupported,
            renderDocumentBody("broken.pdf", "application/pdf", "%PDF-1.7 and then nothing".encodeToByteArray()),
        )
    }

    @Test
    fun `pictures decode and text is read as text`() {
        assertIs<DocumentBody.Picture>(renderDocumentBody("set.png", "image/png", png()))
        assertEquals(DocumentBody.Text("a,b"), renderDocumentBody("rows.csv", null, "a,b".encodeToByteArray()))
    }

    @Test
    fun `a long text is cut rather than laid out whole`() {
        val body = assertIs<DocumentBody.Text>(
            renderDocumentBody("app.log", "text/plain", "x".repeat(400_000).encodeToByteArray()),
        )
        assertTrue(body.text.length < 400_000, "a multi-megabyte log is not laid out in one go")
    }

    @Test
    fun `what the app cannot draw says so, and so does an empty file`() {
        assertEquals(
            DocumentBody.Unsupported,
            renderDocumentBody("budget.xlsx", "application/vnd.ms-excel", byteArrayOf(1, 2)),
        )
        assertEquals(DocumentBody.Unsupported, renderDocumentBody("empty.pdf", "application/pdf", byteArrayOf()))
    }

    private fun pdf(pages: Int): ByteArray = PDDocument().use { document ->
        repeat(pages) { document.addPage(PDPage()) }
        ByteArrayOutputStream().also { document.save(it) }.toByteArray()
    }

    private fun png(): ByteArray {
        val image = BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB)
        return ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
    }
}
