package com.zillit.desktop.feature.esignature

import kotlin.test.assertFalse
import com.zillit.desktop.feature.esignature.data.FieldDto
import com.zillit.desktop.feature.esignature.data.EnvelopeDto
import com.zillit.desktop.feature.esignature.data.signedFieldWire
import com.zillit.desktop.feature.esignature.domain.EnvelopeStatus
import com.zillit.desktop.feature.esignature.domain.EsignPage
import com.zillit.desktop.feature.esignature.domain.EnvelopeField
import com.zillit.desktop.feature.esignature.domain.FieldAnswer
import com.zillit.desktop.feature.esignature.domain.FieldType
import com.zillit.desktop.feature.esignature.domain.SignedField
import com.zillit.desktop.feature.esignature.domain.StoredFile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The envelope wire shapes and the one geometry rule.
 *
 * The signed-fields test matters most: a mark that sends anything but the
 * stored image descriptor, or a checkbox that sends a real boolean, is
 * rejected by the backend — both were learnt from the web's own builder.
 */
class EsignWireShapeTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `an envelope decodes with recipients, tabs and quoted numbers`() {
        val envelope = json.decodeFromString(
            EnvelopeDto.serializer(),
            """
            {"_id":"e1","title":"Crew NDA","status":"sent",
             "attachment":{"media":"k/nda.pdf","name":"NDA.pdf","page_count":"3"},
             "recipients":[{"_id":"r1","user_id":"u1","name":"Ada","role":"signer",
                            "routing_order":"1","status":"delivered","is_external":"false"}],
             "tabs":[{"_id":"t1","type":"signHere","page":"2","x":"72.5","y":"600",
                      "recipient_id":"r1"}]}
            """.trimIndent(),
        ).toDomain()

        assertNotNull(envelope)
        assertEquals(EnvelopeStatus.Sent, envelope.status)
        assertEquals(3, envelope.document?.pageCount)
        val recipient = envelope.recipients.single()
        assertEquals("Viewed", recipient.statusLabel)
        val field = envelope.fields.single()
        assertEquals(FieldType.SignHere, field.type)
        assertEquals(2, field.page)
        assertEquals("r1", field.recipientId)
        assertEquals(field, envelope.fieldsFor(recipient).single())
    }

    @Test
    fun `a mark's answer is the stored descriptor itself`() {
        val wire = signedFieldWire(
            SignedField(
                tabId = "t1",
                type = FieldType.SignHere,
                documentIndex = 0,
                answer = FieldAnswer.Mark(
                    StoredFile(media = "p/sign/a.png", bucket = "b", region = "r", name = "a.png"),
                ),
            ),
            today = "13/08/2026",
        )

        assertEquals("t1", wire["tab_id"]!!.jsonPrimitive.content)
        assertEquals("signHere", wire["type"]!!.jsonPrimitive.content)
        val value = wire["value"]!!.jsonObject
        assertEquals("p/sign/a.png", value["media"]!!.jsonPrimitive.content)
        assertEquals("b", value["bucket"]!!.jsonPrimitive.content)
    }

    @Test
    fun `checkboxes speak string booleans and dates default to today`() {
        val ticked = signedFieldWire(
            SignedField("t2", FieldType.Checkbox, 0, FieldAnswer.Ticked(true)),
            today = "13/08/2026",
        )
        val unansweredBox = signedFieldWire(
            SignedField("t3", FieldType.Checkbox, 0, null),
            today = "13/08/2026",
        )
        val date = signedFieldWire(
            SignedField("t4", FieldType.DateSigned, 0, null),
            today = "13/08/2026",
        )

        assertEquals("true", ticked["value"]!!.jsonPrimitive.content)
        assertTrue(ticked["value"]!!.jsonPrimitive.isString)
        assertEquals("false", unansweredBox["value"]!!.jsonPrimitive.content)
        assertEquals("13/08/2026", date["value"]!!.jsonPrimitive.content)
    }

    /**
     * Top-left both sides: a field near the page top renders near the image
     * top. The flip that the documents tool needs would put every e-sign
     * field at the mirrored height — the two services disagree on origin,
     * and this pins this module to the right one.
     */
    @Test
    fun `field geometry does not flip`() {
        val page = EsignPage(
            page = 1,
            imageBytes = ByteArray(1),
            widthPx = 800,
            heightPx = 1131,
            widthPt = 595.28,
            heightPt = 841.89,
        )

        val nearTop = EnvelopeField(page = 1, x = 72.0, y = 50.0, width = 160.0, height = 48.0)
        val rect = page.pixelRect(nearTop)
        // y = 50pt from the TOP → a small pixel offset, not a huge one.
        assertTrue(rect[1] < 200f, "y=${rect[1]} should be near the image top")

        val (xPt, yPt) = page.pointFromTap(400f, 50f)
        assertEquals(400 * (595.28 / 800), xPt, absoluteTolerance = 0.5)
        assertEquals(50 * (841.89 / 1131), yPt, absoluteTolerance = 0.5)
    }

    @Test
    fun `an envelope without an id is refused, not invented`() {
        assertNull(
            json.decodeFromString(EnvelopeDto.serializer(), """{"title":"x"}""").toDomain(),
        )
    }
    /**
     * A tab is required unless the wire says otherwise.
     *
     * Android declares `required: Boolean = true` on every tab
     * (`DocuSignDtos`) and refuses the whole submit with "Please complete
     * every required field." This port read no such flag and sent a blank
     * Text field's default value instead, completing a legally binding
     * envelope with mandatory fields empty.
     */
    @Test
    fun `a tab with no required flag is required`() {
        val field = json.decodeFromString(
            FieldDto.serializer(),
            """{"_id":"t1","type":"text","page":1,"x":10,"y":10}""",
        ).toDomain()

        assertNotNull(field)
        assertTrue(field.required, "an unflagged tab must not read as optional")
    }

    @Test
    fun `an explicitly optional tab is optional`() {
        val field = json.decodeFromString(
            FieldDto.serializer(),
            """{"_id":"t1","type":"text","page":1,"x":10,"y":10,"required":false}""",
        ).toDomain()

        assertNotNull(field)
        assertFalse(field.required)
    }

}
