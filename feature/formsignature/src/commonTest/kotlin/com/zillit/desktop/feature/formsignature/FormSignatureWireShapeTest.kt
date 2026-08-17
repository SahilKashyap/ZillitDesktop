package com.zillit.desktop.feature.formsignature

import com.zillit.desktop.feature.formsignature.data.SignDocumentDto
import com.zillit.desktop.feature.formsignature.data.SignSpotDto
import com.zillit.desktop.feature.formsignature.data.SignatureBlockDto
import com.zillit.desktop.feature.formsignature.data.StandardFormDto
import com.zillit.desktop.feature.formsignature.data.toWire
import com.zillit.desktop.feature.formsignature.domain.SignSpotKind
import com.zillit.desktop.feature.formsignature.domain.StandardFormType
import com.zillit.desktop.feature.formsignature.domain.StoredDocument
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The wire shapes, pinned from the web client's payloads.
 *
 * The backend family's habits are the reason these exist: booleans arrive as
 * strings, serial numbers as bare numbers, and the two standard-forms tabs
 * spell the same row differently. A decode that survives all of that is the
 * property under test.
 */
class FormSignatureWireShapeTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `an all-forms row and a your-forms row fold into one model`() {
        val allForms = json.decodeFromString(
            StandardFormDto.serializer(),
            """
            {"_id":"f1","document_serial_no":7,
             "document":{"media":"k/doc.pdf","bucket":"b","region":"r","name":"NDA.pdf"},
             "document_type":"contract","created_on":"2026-08-01",
             "user_id":"u9","full_name":"Ada Producer"}
            """.trimIndent(),
        ).toDomain()

        assertNotNull(allForms)
        assertEquals("7", allForms.serialNo)
        assertEquals(StandardFormType.Contract, allForms.type)
        assertEquals("NDA.pdf", allForms.name)
        assertEquals("u9", allForms.uploaderId)

        val mine = json.decodeFromString(
            StandardFormDto.serializer(),
            """
            {"_id":"f2","sender_documents":{"media":"k/w9.pdf","name":"W9.pdf"},
             "sender_id":"u1","document_type":"unheard_of_type"}
            """.trimIndent(),
        ).toDomain()

        assertNotNull(mine)
        assertEquals("W9.pdf", mine.name)
        assertEquals("u1", mine.uploaderId)
        // Anything unrecognised is a reference document — the web's default.
        assertEquals(StandardFormType.Reference, mine.type)
    }

    @Test
    fun `string booleans and quoted numbers decode`() {
        val document = json.decodeFromString(
            SignDocumentDto.serializer(),
            """
            {"_id":"d1","document":{"media":"k/c.pdf","name":"Contract.pdf"},
             "only_signature_required":"true","user_signature_required":"false",
             "document_finalized":"false",
             "users":[{"user_id":"u1","status":"pending","order_of_signing":"2",
                       "is_external":"false",
                       "document_coordinates":[{"type":"initials","page_number":"3",
                          "x_coordinate":"72.5","y_coordinate":"600","width":"120","height":"40"}]}]}
            """.trimIndent(),
        ).toDomain()

        assertNotNull(document)
        assertTrue(document.onlySignatureRequired)
        assertFalse(document.userSignatureRequired)
        val signer = document.signers.single()
        assertEquals(2, signer.order)
        val spot = signer.spots.single()
        assertEquals(SignSpotKind.Initials, spot.kind)
        assertEquals(3, spot.page)
        assertEquals(72.5, spot.x)
    }

    /**
     * `user_status` is the sibling of `document_finalized`: the web treats
     * either as "this one is done for you".
     */
    @Test
    fun `a signed user_status reads as finalized`() {
        val document = json.decodeFromString(
            SignDocumentDto.serializer(),
            """{"_id":"d2","user_status":"signed"}""",
        ).toDomain()

        assertNotNull(document)
        assertTrue(document.finalized)
    }

    @Test
    fun `the write shape carries the full attachment quartet`() {
        val wire = StoredDocument(
            media = "key/doc.pdf",
            bucket = "b",
            region = "eu-west-2",
            name = "Deal.pdf",
        ).toWire()

        assertEquals("", wire["caption"]!!.jsonPrimitive.content)
        assertEquals("1", wire["duration"]!!.jsonPrimitive.content)
        assertEquals("1", wire["height"]!!.jsonPrimitive.content)
        assertEquals("1", wire["width"]!!.jsonPrimitive.content)
        assertEquals("document", wire["content_type"]!!.jsonPrimitive.content)
        assertEquals("pdf", wire["content_subtype"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a spot round-trips through its wire spelling`() {
        val spotJson = json.decodeFromString(
            SignSpotDto.serializer(),
            """{"type":"signature","page_number":1,"x_coordinate":10.0,
                "y_coordinate":20.0,"width":160.0,"height":56.0}""",
        ).toDomain()!!.toWire()

        assertEquals("signature", spotJson["type"]!!.jsonPrimitive.content)
        assertEquals(1, spotJson["page_number"]!!.jsonPrimitive.content.toInt())
        assertEquals(10.0, spotJson["x_coordinate"]!!.jsonPrimitive.content.toDouble())
    }

    @Test
    fun `a signature block resolves either id spelling`() {
        val byUnderscore = json.decodeFromString(
            SignatureBlockDto.serializer(),
            """{"_id":"s1","is_signature":true,
                "signature":{"media":"p/sign/a.png","name":"Full"}}""",
        ).toDomain()
        val bySignatureId = json.decodeFromString(
            SignatureBlockDto.serializer(),
            """{"signature_id":"s2","is_signature":"false",
                "signature":{"media":"p/sign/b.png","name":"Init"}}""",
        ).toDomain()

        assertEquals("s1", byUnderscore?.id)
        assertTrue(byUnderscore!!.isSignature)
        assertEquals("s2", bySignatureId?.id)
        assertFalse(bySignatureId!!.isSignature)
    }
}
