package com.zillit.desktop.core.forms

import com.zillit.desktop.core.forms.formTemplateBody
import com.zillit.desktop.core.forms.parseFormTemplate
import com.zillit.desktop.core.forms.FormTemplate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun json(text: String) = Json.parseToJsonElement(text)

private const val TEMPLATE = """
[
 {"key":"header","label":"Header","order":1,"system_default":true,
  "fields":[
    {"order":1,"name":"Vendor","type":"select","label":"vendor","required":true,
     "system_default":true,"hide":false,"selection_type":"vendor"},
    {"order":2,"name":"Notes","type":"textarea","label":"notes","required":false,
     "system_default":true,"hide":true,"selection_type":null,"max_length":500}
  ],
  "collapsible":true},
 {"key":"lines","label":"Line Items","order":2,"system_default":true,"fields":[]}
]
"""

/**
 * The document this service stores, read and written back.
 *
 * Written from response bodies rather than from the model, because the whole
 * hazard on this screen is a save that round-trips *less* than it read: the
 * template is replaced wholesale, so a key dropped on the way in is deleted on
 * the way out, silently, for every field at once.
 */
class FormTemplateWireTest {

    @Test
    fun `a template is read with its fields, types and flags`() {
        val template = parseFormTemplate(json(TEMPLATE))

        val header = template.section("header")!!
        assertEquals("Header", header.label)
        assertTrue(header.systemDefault)
        val vendor = header.fields.first { it.label == "vendor" }
        assertEquals("Vendor", vendor.name)
        assertEquals("select", vendor.type)
        assertEquals("vendor", vendor.selectionType)
        assertTrue(vendor.required)
        assertFalse(vendor.hidden)
        assertTrue(template.section("lines")!!.fields.isEmpty())
    }

    /** A hidden field is read but kept out of the visible list. */
    @Test
    fun `a removed system field is read as off the form`() {
        val header = parseFormTemplate(json(TEMPLATE)).section("header")!!

        assertEquals(listOf("vendor"), header.visible.map { it.label })
        assertEquals(listOf("notes"), header.removed.map { it.label })
    }

    /**
     * A type this client does not offer survives untouched.
     *
     * Petty Cash has `textarea` system fields. Narrowing them to `text` on read
     * would rewrite them on the next save, and a multi-line box nobody touched
     * would quietly become a one-line one.
     */
    @Test
    fun `a type the picker does not offer is neither changed nor lost`() {
        val template = parseFormTemplate(json(TEMPLATE))

        val notes = template.section("header")!!.fields.first { it.label == "notes" }
        assertEquals("textarea", notes.type)
        assertNull(notes.knownType)

        val sent = formTemplateBody(template).fields("header").first { it.text("label") == "notes" }
        assertEquals("textarea", sent.text("type"))
    }

    /**
     * Keys this client does not model come back out.
     *
     * The save replaces the whole document, so anything dropped on the way in
     * is deleted — for every field on the form, and with no error to notice.
     */
    @Test
    fun `keys this client does not model round-trip`() {
        val body = formTemplateBody(parseFormTemplate(json(TEMPLATE)))

        val notes = body.fields("header").first { it.text("label") == "notes" }
        assertEquals(500, notes["max_length"]?.jsonPrimitive?.intOrNull)
        val header = body.sections().first { it.text("key") == "header" }
        assertEquals(true, header["collapsible"]?.jsonPrimitive?.booleanOrNull)
    }

    /**
     * The template arrives as a document or as the text of one.
     *
     * The service stores it as text and does not always parse it on the way
     * out, so both shapes come back from the same route.
     */
    @Test
    fun `a template stored as text reads the same as one stored as a document`() {
        val asText = JsonPrimitive(TEMPLATE)

        val fromText = parseFormTemplate(asText)
        val fromDocument = parseFormTemplate(json(TEMPLATE))

        assertEquals(fromDocument, fromText)
    }

    @Test
    fun `a module with no template reads as empty rather than failing`() {
        assertEquals(FormTemplate(), parseFormTemplate(null))
        assertEquals(FormTemplate(), parseFormTemplate(json("null")))
        assertEquals(FormTemplate(), parseFormTemplate(json("""{"template":[]}""")))
    }

    /** Written back in display order, with the orders the document holds. */
    @Test
    fun `the save body is ordered and carries every section`() {
        val edited = parseFormTemplate(json(TEMPLATE)).moveSection("lines", "header")

        val sections = formTemplateBody(edited).sections()
        assertEquals(listOf("lines", "header"), sections.map { it.text("key") })
        assertEquals(listOf(1, 2), sections.map { it["order"]?.jsonPrimitive?.intOrNull })
    }

    /** A cleared source goes as null rather than as the string "null". */
    @Test
    fun `a field with no selection source sends null`() {
        val body = formTemplateBody(parseFormTemplate(json(TEMPLATE)))

        val notes = body.fields("header").first { it.text("label") == "notes" }
        assertTrue(notes["selection_type"] is JsonNull)
    }
}

private fun JsonObject.sections(): List<JsonObject> =
    (this["template"] as JsonArray).map { it.jsonObject }

private fun JsonObject.fields(sectionKey: String): List<JsonObject> =
    sections().first { it.text("key") == sectionKey }["fields"]!!.jsonArray.map { it.jsonObject }

private fun JsonObject.text(key: String): String = this[key]?.jsonPrimitive?.content.orEmpty()
