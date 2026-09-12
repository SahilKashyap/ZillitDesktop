package com.zillit.desktop.feature.purchaseorder

import com.zillit.desktop.feature.purchaseorder.data.PoAttachmentDto
import com.zillit.desktop.feature.purchaseorder.data.PoEmailDto
import com.zillit.desktop.feature.purchaseorder.data.attachmentsJson
import com.zillit.desktop.feature.purchaseorder.data.toJson
import com.zillit.desktop.feature.purchaseorder.domain.PoAddress
import com.zillit.desktop.feature.purchaseorder.domain.PoAttachment
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The routes and bodies the two registers speak, and the two calls that used to
 * answer 404.
 *
 * Four purchase-order routes the old web module declared are not registered on
 * develop at all — `/v2/list/attachments/{id}`, `/v2/add/attachments/{id}`,
 * `/v2/delete/{attachmentId}/{id}` and `/v2/send/{id}`, probed on every verb
 * 2026-09-12. The account hub's module never used them: attachments ride the
 * order record, and the vendor email is `/{id}/send-vendor-email`. These pin
 * the live shapes so nobody reinstates the dead ones.
 */
class PoRegisterWireTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

    /** The attachment column, as a PATCH of the order writes it. */
    @Test
    fun `attachments are a column on the order, keyed as the service stores them`() {
        val body = listOf(
            PoAttachment(
                id = "att-1",
                media = "purchase-order/abc/quote.pdf",
                name = "Quote.pdf",
                contentType = "application/pdf",
                bucket = "zillit-media",
                region = "eu-west-2",
            ),
        ).attachmentsJson()

        assertEquals(1, body.size)
        val file = body.first().jsonObject
        assertEquals("att-1", file["_id"]?.jsonPrimitive?.content)
        assertEquals("purchase-order/abc/quote.pdf", file["media"]?.jsonPrimitive?.content)
        assertEquals("Quote.pdf", file["name"]?.jsonPrimitive?.content)
        assertEquals("application/pdf", file["content_type"]?.jsonPrimitive?.content)
        assertEquals("zillit-media", file["bucket"]?.jsonPrimitive?.content)
        assertEquals("eu-west-2", file["region"]?.jsonPrimitive?.content)
    }

    /** A file the server has not seen yet carries no id, and must not send a blank one. */
    @Test
    fun `a new attachment sends no id`() {
        val file = listOf(PoAttachment(media = "k", name = "n", bucket = "b", region = "r"))
            .attachmentsJson()
            .first()
            .jsonObject

        assertNull(file["_id"])
    }

    /**
     * The delivery address keeps three camelCase keys inside its object.
     *
     * Every other key on this service is snake_case, but the web sends
     * `phoneCode` and `postalCode` inside `address` and the server stores the
     * object verbatim — renaming them would save an address the form cannot
     * read back.
     */
    @Test
    fun `the address object keeps the web's camelCase keys`() {
        val body = PoAddress(
            name = "Stage manager",
            phoneCode = "+44",
            phone = "1753 651700",
            line1 = "Pinewood Studios",
            postalCode = "SL0 0NH",
        ).toJson()

        assertTrue(body.containsKey("phoneCode"), "phoneCode, not phone_code")
        assertTrue(body.containsKey("postalCode"), "postalCode, not postal_code")
        assertEquals("+44", body["phoneCode"]?.jsonPrimitive?.content)
        assertEquals("SL0 0NH", body["postalCode"]?.jsonPrimitive?.content)
    }

    /**
     * A 200 with no `sent` flag still means the mail was accepted.
     *
     * The refusals arrive as error envelopes (`po_vendor_email_no_recipient`,
     * `po_vendor_email_failed`, `po_not_emailable_in_status`), so only an
     * explicit `false` here is a refusal — reading an absent flag as one would
     * report a sent order as unsent.
     */
    @Test
    fun `the vendor email receipt treats an absent flag as sent`() {
        val silent = json.decodeFromString(PoEmailDto.serializer(), """{"to":"accounts@vendor.com"}""")
        assertTrue(silent.toDomain().sent)
        assertEquals("accounts@vendor.com", silent.toDomain().to)

        val refused = json.decodeFromString(PoEmailDto.serializer(), """{"sent":false}""")
        assertTrue(!refused.toDomain().sent)
    }

    /** The PDF route answers a stored S3 attachment the host presigns like any other. */
    @Test
    fun `the pdf route answers an attachment`() {
        val dto = json.decodeFromString(
            PoAttachmentDto.serializer(),
            """{"media":"po-pdf/abc/PO-0001.pdf","name":"PO-0001.pdf","bucket":"b","region":"eu-west-2"}""",
        )
        val file = dto.toDomain()
        assertEquals("PO-0001.pdf", file?.displayName)
        assertEquals("b", file?.bucket)
    }

    /** An attachment with no display name falls back to the key's last segment. */
    @Test
    fun `an unnamed attachment is named from its key`() {
        assertEquals(
            "delivery-note.pdf",
            PoAttachment(media = "purchase-order/abc/delivery-note.pdf").displayName,
        )
    }
}
