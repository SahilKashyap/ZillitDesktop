package com.zillit.desktop.feature.esignature

import com.zillit.desktop.feature.esignature.data.EnvelopeDto
import com.zillit.desktop.feature.esignature.data.asMillis
import com.zillit.desktop.feature.esignature.data.signedFieldWire
import com.zillit.desktop.feature.esignature.data.toWire
import com.zillit.desktop.feature.esignature.domain.EnvelopeDraft
import com.zillit.desktop.feature.esignature.domain.EnvelopeField
import com.zillit.desktop.feature.esignature.domain.EnvelopeRecipient
import com.zillit.desktop.feature.esignature.domain.EnvelopeSettings
import com.zillit.desktop.feature.esignature.domain.EnvelopeStatus
import com.zillit.desktop.feature.esignature.domain.EsignPage
import com.zillit.desktop.feature.esignature.domain.FieldAnswer
import com.zillit.desktop.feature.esignature.domain.FieldOption
import com.zillit.desktop.feature.esignature.domain.FieldType
import com.zillit.desktop.feature.esignature.domain.FieldValue
import com.zillit.desktop.feature.esignature.domain.SignedField
import com.zillit.desktop.feature.esignature.domain.StoredFile
import com.zillit.desktop.feature.esignature.domain.TemplateDraft
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The envelope wire shapes, read and written.
 *
 * The signed-fields test matters most: a mark that sends anything but the
 * stored image descriptor, or a checkbox that sends a real boolean, is
 * rejected by the backend — both were learnt from the web's own builder.
 * The value-as-object test matters next: a completed envelope carries each
 * mark's descriptor under `value`, and a decoder typed for strings dropped
 * the whole envelope from the list.
 */
class EsignWireShapeTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `an envelope decodes with recipients, tabs, quoted numbers and settings`() {
        val envelope = json.decodeFromString(
            EnvelopeDto.serializer(),
            """
            {"_id":"e1","title":"Crew NDA","status":"sent","created":1700000000000,"sent_on":"2026-05-01T10:00:00Z",
             "attachment":{"media":"k/nda.pdf","name":"NDA.pdf","page_count":"3","size":"184302"},
             "recipients":[{"_id":"r1","user_id":"u1","name":"Ada","role":"signer","routing_order":"1",
                            "status":"delivered","is_external":"false","viewed_on":1700000001000,
                            "accepted_terms_on":0}],
             "tabs":[{"_id":"t1","type":"signHere","page":"2","x":"72.5","y":"600","recipient_id":"r1",
                      "required":"false","locked":"true",
                      "default_value":"x","options":[{"option_id":"a","label":"Yes"},"No"]}],
             "settings":{"email_subject":"Please sign","signing_order_enabled":"true","expiration_days":30},
             "initials_on_all_pages":true,"reminder_cadence_days":"3","bulk_job_id":"job9"}
            """.trimIndent(),
        ).toDomain()

        assertNotNull(envelope)
        assertEquals(EnvelopeStatus.Sent, envelope.status)
        assertEquals(3, envelope.document?.pageCount)
        assertEquals(184302L, envelope.document?.sizeBytes)
        assertEquals(1700000000000L, envelope.created)
        assertNotNull(envelope.sentOn, "an ISO stamp parses to millis")
        val recipient = envelope.recipients.single()
        assertEquals("Viewed", recipient.statusLabel)
        assertEquals(1700000001000L, recipient.viewedOn)
        assertNull(recipient.acceptedTermsOn, "epoch zero is 'never', not 1970")
        val field = envelope.fields.single()
        assertEquals(FieldType.SignHere, field.type)
        assertEquals(2, field.page)
        assertEquals("r1", field.recipientId)
        assertEquals(0, field.recipientIndex, "the index is derived from the owner's id")
        assertFalse(field.required)
        assertTrue(field.locked)
        assertEquals(listOf(FieldOption("a", "Yes"), FieldOption("No", "No")), field.options)
        assertEquals(field, envelope.fieldsFor(recipient).single())
        assertEquals("Please sign", envelope.settings.emailSubject)
        assertTrue(envelope.settings.signingOrderEnabled)
        assertTrue(envelope.settings.initialsOnAllPages)
        assertEquals(3, envelope.settings.reminderCadenceDays)
        assertEquals(30, envelope.settings.expirationDays)
        assertTrue(envelope.fromBulkSend)
    }

    @Test
    fun `a signed mark's value is a stored file, a typed one text, and neither breaks the decode`() {
        val envelope = json.decodeFromString(
            EnvelopeDto.serializer(),
            """
            {"_id":"e2","status":"completed","recipients":[],
             "tabs":[{"_id":"t1","type":"signHere","page":1,"value":{"media":"sig/a.png","bucket":"b"}},
                     {"_id":"t2","type":"text","page":1,"value":"Ada"},
                     {"_id":"t3","type":"checkbox","page":1,"value":""}]}
            """.trimIndent(),
        ).toDomain()
        assertNotNull(envelope)
        val (mark, text, box) = envelope.fields
        assertEquals("sig/a.png", (mark.value as FieldValue.File).file.media)
        assertEquals("Ada", (text.value as FieldValue.Text).text)
        assertEquals(FieldValue.None, box.value)
    }

    @Test
    fun `a legacy numeric owner is an index, never an id`() {
        val envelope = json.decodeFromString(
            EnvelopeDto.serializer(),
            """
            {"_id":"e3","recipients":[{"_id":"r1"},{"_id":"r2"}],
             "tabs":[{"type":"text","page":1,"recipient_id":1}]}
            """.trimIndent(),
        ).toDomain()!!
        val field = envelope.fields.single()
        assertEquals("", field.recipientId)
        assertEquals(1, field.recipientIndex)
        assertEquals(field, envelope.fieldsFor(envelope.recipients[1]).single())
    }

    @Test
    @Suppress("LongMethod") // One body, every key checked.
    fun `create sends recipient_index, options, defaults and never a client-minted recipient id`() {
        val body = EnvelopeDraft(
            title = "NDA",
            document = StoredFile("k/nda.pdf", name = "nda.pdf", pageCount = 2),
            recipients = listOf(
                EnvelopeRecipient(userId = "u1", name = "Ada", email = "ada@x.io", routingOrder = 1),
                EnvelopeRecipient(email = "cc@x.io", name = "Copy", role = "cc", routingOrder = 99, isExternal = true),
            ),
            fields = listOf(
                EnvelopeField(
                    type = FieldType.Dropdown,
                    page = 1,
                    x = 10.0,
                    y = 20.0,
                    recipientIndex = 0,
                    recipientId = "local-1",
                    label = "Role",
                    options = listOf(FieldOption("o1", "Grip"), FieldOption("o2", "Gaffer")),
                    defaultValue = "o1",
                    locked = true,
                ),
                EnvelopeField(
                    id = "5f1e2d3c4b5a69788796a5b4",
                    type = FieldType.SignHere,
                    page = 1,
                    recipientIndex = 0,
                    recipientId = "5f1e2d3c4b5a69788796a5b5",
                    autoInitial = true,
                ),
            ),
            settings = EnvelopeSettings(
                emailSubject = "Sign please",
                initialsOnAllPages = true,
                reminderCadenceDays = 3,
                signingOrderEnabled = true,
                placementMode = "fastPlace",
            ),
        ).toWire()

        assertEquals(false, body["send_now"]!!.jsonPrimitive.boolean)
        assertEquals(2, body["attachment"]!!.jsonObject["page_count"]!!.jsonPrimitive.content.toInt())
        assertEquals(true, body["initials_on_all_pages"]!!.jsonPrimitive.boolean)
        assertEquals(3, body["reminder_cadence_days"]!!.jsonPrimitive.content.toInt())
        val recipients = body["recipients"]!!.jsonArray
        assertEquals("u1", recipients[0].jsonObject["user_id"]!!.jsonPrimitive.content)
        assertNull(recipients[1].jsonObject["user_id"], "an external has no user id to send")
        assertEquals("cc", recipients[1].jsonObject["role"]!!.jsonPrimitive.content)
        val tabs = body["tabs"]!!.jsonArray
        val dropdown = tabs[0].jsonObject
        assertEquals(0, dropdown["recipient_index"]!!.jsonPrimitive.content.toInt())
        assertNull(dropdown["recipient_id"], "a non-ObjectId owner id is dropped")
        assertNull(dropdown["_id"], "a client field has no id yet")
        assertEquals("o1", dropdown["default_value"]!!.jsonPrimitive.content)
        assertEquals(true, dropdown["locked"]!!.jsonPrimitive.boolean)
        assertEquals("Grip", dropdown["options"]!!.jsonArray[0].jsonObject["label"]!!.jsonPrimitive.content)
        assertEquals("o1", dropdown["options"]!!.jsonArray[0].jsonObject["option_id"]!!.jsonPrimitive.content)
        val mark = tabs[1].jsonObject
        assertEquals(
            "5f1e2d3c4b5a69788796a5b5",
            mark["recipient_id"]!!.jsonPrimitive.content,
            "a real ObjectId travels",
        )
        assertEquals("5f1e2d3c4b5a69788796a5b4", mark["_id"]!!.jsonPrimitive.content)
        assertEquals(true, mark["auto_initial"]!!.jsonPrimitive.boolean)
        assertNull(mark["options"], "only choice fields carry options")
        val settings = body["settings"]!!.jsonObject
        assertEquals("Sign please", settings["email_subject"]!!.jsonPrimitive.content)
        assertEquals(true, settings["signing_order_enabled"]!!.jsonPrimitive.boolean)
        assertEquals("fastPlace", settings["placement_mode"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a template sends role slots, not people, and clears field ids`() {
        val body = TemplateDraft(
            name = "Deal memo",
            category = "deal_memo",
            documents = listOf(StoredFile("k/dm.pdf", name = "dm.pdf", pageCount = 4)),
            slots = listOf(
                EnvelopeRecipient(userId = "u1", name = "Ada", email = "ada@x.io", routingOrder = 1),
                EnvelopeRecipient(name = "Signer 2", role = "signer", placeholderLabel = "Signer 2"),
            ),
            fields = listOf(
                EnvelopeField(
                    id = "5f1e2d3c4b5a69788796a5b4",
                    type = FieldType.Text,
                    page = 1,
                    recipientIndex = 1,
                    value = FieldValue.Text("old"),
                ),
            ),
            settings = EnvelopeSettings(emailSubject = "Hi"),
            fromEnvelopeId = "e1",
        ).toWire()

        val slots = body["recipients"]!!.jsonArray
        assertEquals("Ada", slots[0].jsonObject["placeholder_label"]!!.jsonPrimitive.content)
        assertNull(slots[0].jsonObject["email"], "personal data is stripped")
        assertNull(slots[0].jsonObject["user_id"])
        assertEquals("Signer 2", slots[1].jsonObject["placeholder_label"]!!.jsonPrimitive.content)
        assertEquals(2, slots[1].jsonObject["routing_order"]!!.jsonPrimitive.content.toInt())
        val tab = body["tabs"]!!.jsonArray[0].jsonObject
        assertNull(tab["_id"], "ids are per envelope")
        assertEquals("", tab["value"]!!.jsonPrimitive.content)
        assertEquals(0, body["documents"]!!.jsonArray[0].jsonObject["document_index"]!!.jsonPrimitive.content.toInt())
        assertEquals("e1", body["from_envelope_id"]!!.jsonPrimitive.content)
        assertEquals("Hi", body["email_subject"]!!.jsonPrimitive.content)
    }

    @Test
    fun `signed fields speak the builder's vocabulary`() {
        val mark = signedFieldWire(
            SignedField("t1", FieldType.SignHere, 0, FieldAnswer.Mark(StoredFile("sig/a.png", bucket = "b"))),
            "01/05/2026",
        )
        assertEquals("sig/a.png", mark["value"]!!.jsonObject["media"]!!.jsonPrimitive.content)
        assertEquals("t1", mark["tab_id"]!!.jsonPrimitive.content)

        val ticked = signedFieldWire(SignedField("t2", FieldType.Checkbox, 0, FieldAnswer.Ticked(true)), "x")
        assertEquals("true", ticked["value"]!!.jsonPrimitive.content)
        assertTrue(ticked["value"]!!.jsonPrimitive.isString, "a string, never a boolean")

        val untouchedBox = signedFieldWire(SignedField("t3", FieldType.Checkbox, 0, null, defaultValue = "true"), "x")
        assertEquals("true", untouchedBox["value"]!!.jsonPrimitive.content, "an untouched box keeps its default")

        val date = signedFieldWire(SignedField("t4", FieldType.DateSigned, 0, null), "01/05/2026")
        assertEquals("01/05/2026", date["value"]!!.jsonPrimitive.content)

        val chosen = signedFieldWire(SignedField("t5", FieldType.Radio, 0, FieldAnswer.Chosen("o2")), "x")
        assertEquals("o2", chosen["value"]!!.jsonPrimitive.content)

        val untouchedText = signedFieldWire(SignedField("t6", FieldType.Text, 0, null, defaultValue = "Prod Co"), "x")
        assertEquals(
            "Prod Co",
            untouchedText["value"]!!.jsonPrimitive.content,
            "a locked default is what the signer saw",
        )
    }

    @Test
    fun `moments parse from millis, seconds, quoted numbers and ISO, and zero is nothing`() {
        assertEquals(1700000000000L, JsonPrimitive(1700000000000L).asMillis())
        assertEquals(1700000000000L, JsonPrimitive(1700000000L).asMillis(), "seconds are promoted")
        assertEquals(1700000000000L, JsonPrimitive("1700000000000").asMillis())
        assertEquals(1777629600000L, JsonPrimitive("2026-05-01T10:00:00Z").asMillis())
        assertNull(JsonPrimitive(0).asMillis())
        assertNull(JsonPrimitive("").asMillis())
        assertNull(JsonPrimitive("soon").asMillis())
    }

    @Test
    fun `field geometry does not flip`() {
        val page = EsignPage(
            page = 1,
            imageBytes = ByteArray(0),
            widthPx = 1224,
            heightPx = 1584,
            widthPt = 612.0,
            heightPt = 792.0,
        )
        val (x, y) = page.pointFromTap(xPx = 612f, yPx = 100f)
        assertEquals(306.0, x)
        assertEquals(50.0, y, "top-left in pixels is top-left in points")
        val rect = page.pixelRect(EnvelopeField(x = 100.0, y = 200.0, width = 50.0, height = 25.0))
        assertEquals(listOf(200f, 400f, 100f, 50f), rect)
    }

    @Test
    fun `an envelope without an id is refused, not invented`() {
        assertNull(json.decodeFromString(EnvelopeDto.serializer(), """{"title":"x"}""").toDomain())
    }
}
