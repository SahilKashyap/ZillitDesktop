package com.zillit.desktop.feature.purchaseorder

import com.zillit.desktop.feature.purchaseorder.data.PoAttachmentDto
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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
}
