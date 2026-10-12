package com.zillit.desktop.feature.purchaseorder

import com.zillit.desktop.feature.purchaseorder.data.PoAttachmentDto
import com.zillit.desktop.feature.purchaseorder.data.attachmentsJson
import com.zillit.desktop.feature.purchaseorder.domain.PoAttachment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/**
 * Files on a purchase order.
 *
 * The order list has carried an attachment *count* since the port began, so
 * the app has been saying "3 attachments" while offering no way to reach one.
 */
class PoAttachmentTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun parse(body: String) = json
        .decodeFromString(ListSerializer(PoAttachmentDto.serializer()), body)
        .mapNotNull { it.toDomain() }

    /** The everyday row: a quote against the order. */
    @Test
    fun `an attachment carries its key and name`() {
        val rows = parse(
            """[{"_id":"a1","media":"po/quote.pdf","name":"Supplier quote.pdf","content_type":"application/pdf",
                 "bucket":"zillit","region":"ap-south-1"}]""",
        )

        val file = rows.single()
        assertEquals("po/quote.pdf", file.media)
        assertEquals("Supplier quote.pdf", file.displayName)
        assertEquals("zillit", file.bucket)
    }

    /**
     * The storage key and the display name are different strings, and a row
     * often carries only the key — the file is then named by its tail rather
     * than shown as a path.
     */
    @Test
    fun `an unnamed file is named by its key`() {
        assertEquals("quote.pdf", parse("""[{"_id":"a2","media":"po/2026/quote.pdf"}]""").single().displayName)
    }

    /** A row with no storage key points at nothing and is dropped. */
    @Test
    fun `a key-less row is dropped`() {
        assertTrue(parse("""[{"_id":"a3","name":"Ghost.pdf"}]""").isEmpty())
    }

    /** A thin row still lists — the file is reachable by key alone. */
    @Test
    fun `a thin row still reads`() {
        val file = parse("""[{"media":"po/x.pdf"}]""").single()

        assertEquals("", file.id)
        assertEquals("x.pdf", file.displayName)
    }

    /**
     * The web previews a file inline from `content_subtype`, and lists it by the
     * MIME's major type (`POForm.jsx` `buildAttachmentMeta`). The store here
     * could only say `application/octet-stream` for an image.
     */
    @Test
    fun `an attachment goes out with the web's meta`() {
        val pdf = PoAttachment(
            media = "po/quote.pdf", name = "Quote.PDF", contentType = "application/pdf", bucket = "b", region = "r",
            size = 2048,
        )
        val png = PoAttachment(media = "po/site.png", name = "site.png", contentType = "application/octet-stream")
        val docx = PoAttachment(media = "po/terms.docx", name = "terms.docx", contentType = "application/octet-stream")

        val sent = listOf(pdf, png, docx).attachmentsJson().map { it.jsonObject }

        assertEquals("application", sent[0]["content_type"]!!.jsonPrimitive.content)
        assertEquals("pdf", sent[0]["content_subtype"]!!.jsonPrimitive.content)
        assertEquals("", sent[0]["caption"]!!.jsonPrimitive.content)
        assertEquals(2048L, sent[0]["file_size"]!!.jsonPrimitive.long)
        assertEquals("image", sent[1]["content_type"]!!.jsonPrimitive.content)
        assertEquals("png", sent[1]["content_subtype"]!!.jsonPrimitive.content)
        assertEquals("application", sent[2]["content_type"]!!.jsonPrimitive.content)
        assertEquals("docx", sent[2]["content_subtype"]!!.jsonPrimitive.content)
        assertNull(sent[1]["file_size"], "no size known, none sent")
    }

    /** A row the web wrote keeps its meta through an edit, rather than being re-derived. */
    @Test
    fun `the web's own meta is read back and kept`() {
        val file = parse(
            """[{"media":"po/a.pdf","name":"a.pdf","content_type":"application","content_subtype":"pdf",
                 "caption":"Signed","file_size":"1500"}]""",
        ).single()

        assertEquals("pdf", file.contentSubtype)
        assertEquals("Signed", file.caption)
        assertEquals(1500L, file.size)
        val again = listOf(file).attachmentsJson().first().jsonObject
        assertEquals("Signed", again["caption"]!!.jsonPrimitive.content)
        assertEquals("application", again["content_type"]!!.jsonPrimitive.content)
    }
}
