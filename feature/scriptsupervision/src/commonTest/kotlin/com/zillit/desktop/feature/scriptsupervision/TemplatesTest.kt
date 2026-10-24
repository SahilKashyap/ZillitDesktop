package com.zillit.desktop.feature.scriptsupervision

import com.zillit.desktop.feature.scriptsupervision.domain.Condition
import com.zillit.desktop.feature.scriptsupervision.domain.Rec
import com.zillit.desktop.feature.scriptsupervision.domain.TemplateField
import com.zillit.desktop.feature.scriptsupervision.domain.TemplateSection
import com.zillit.desktop.feature.scriptsupervision.domain.Templates
import com.zillit.desktop.feature.scriptsupervision.domain.Validation
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The template model: what is built, what is loaded, and what is sent.
 *
 * The round trip is the risk. A template is read, edited in place and written
 * back whole, so anything this drops on the way through is deleted from a
 * production's own form design without a word.
 */
class TemplatesTest {

    private val label: (String) -> String = { "label:$it" }

    @Test
    fun `a new template has every built-in section, on, in order`() {
        val sections = Templates.buildDefaultSections(label)

        assertEquals(Templates.SECTION_DEFS.map { it.key }, sections.map { it.key })
        assertTrue(sections.all { it.enabled })
        assertEquals(sections.indices.toList(), sections.map { it.sortOrder })
        // Labels are baked in, not resolved on read — see the model's note.
        assertEquals("label:scene", sections.first().label)
        assertEquals("label:field_scene_number", sections.first().fields.first().label)
    }

    @Test
    fun `a field whose label is already a module word uses that word`() {
        // The same rule the catalogue follows: no second key for text the
        // module already has.
        assertEquals("notes", Templates.fieldLabelKey("notes"))
        assertEquals("flag_is_ng", Templates.fieldLabelKey("is_ng"))
        assertEquals("take", Templates.fieldLabelKey("take_number"))
        // Everything else is field_<key>.
        assertEquals("field_lens_mm", Templates.fieldLabelKey("lens_mm"))
    }

    @Test
    fun `the scene and take sections are required and the rest are not`() {
        assertEquals(true, Templates.sectionDef("scene")?.required)
        assertEquals(true, Templates.sectionDef("take")?.required)
        assertEquals(false, Templates.sectionDef("sound_report")?.required)
        assertNull(Templates.sectionDef("a_custom_one"))
    }

    @Test
    fun `a saved section with no enabled key counts as ON`() {
        // ⚠ The service leaves the key out of a section nobody has switched.
        // Reading that as off would hide half a template on first open.
        val template = Rec(
            buildJsonObject {
                put(
                    "sections",
                    buildJsonArray {
                        add(buildJsonObject { put("key", "scene"); put("label", "Scene") })
                        add(buildJsonObject { put("key", "take"); put("label", "Take"); put("enabled", false) })
                    },
                )
            },
        )

        val sections = Templates.loadSections(template)

        assertEquals(true, sections.first { it.key == "scene" }.enabled)
        assertEquals(false, sections.first { it.key == "take" }.enabled)
    }

    @Test
    fun `sections load in their saved order, not the order they arrived in`() {
        val template = Rec(
            buildJsonObject {
                put(
                    "sections",
                    buildJsonArray {
                        add(buildJsonObject { put("key", "b"); put("sort_order", 2) })
                        add(buildJsonObject { put("key", "a"); put("sort_order", 1) })
                    },
                )
            },
        )

        assertEquals(listOf("a", "b"), Templates.loadSections(template).map { it.key })
    }

    @Test
    fun `sort_order is written from the position, not from the old number`() {
        // ⚠ Reordering moves list items only. Saving each section's stored
        // number would write the OLD order back.
        val sections = listOf(
            TemplateSection(key = "b", label = "B", sortOrder = 7),
            TemplateSection(key = "a", label = "A", sortOrder = 3),
        )

        val body = Templates.toSectionsBody(sections)

        assertEquals(listOf("b", "a"), body.map { it.jsonObject["key"]?.jsonPrimitive?.content })
        assertEquals(listOf(0, 1), body.map { it.jsonObject["sort_order"]?.jsonPrimitive?.content?.toInt() })
    }

    @Test
    fun `a field's settings survive the round trip, and empty ones are left out`() {
        val field = TemplateField(
            key = "n",
            label = "Channels",
            type = "number",
            required = true,
            validation = Validation(min = 1.0, maxLength = 4),
            conditions = listOf(
                Condition("record_type", "equals", "sync"),
                // Half-written: the service requires a field on every rule.
                Condition("", "equals", "x"),
            ),
        )

        val body = field.toBody()

        assertEquals("number", body["type"]?.jsonPrimitive?.content)
        assertEquals(true, body["required"]?.jsonPrimitive?.content?.toBoolean())
        assertEquals(1.0, body["validation"]?.jsonObject?.get("min")?.jsonPrimitive?.content?.toDouble())
        assertEquals(4, body["validation"]?.jsonObject?.get("maxLength")?.jsonPrimitive?.content?.toInt())
        // The blank-field rule is dropped rather than sent and refused.
        assertEquals(1, body["conditions"]?.jsonArray?.size)
        // Nothing empty travels: no defaultValue, no formula, no lookupConfig.
        assertEquals(false, body.containsKey("defaultValue"))
        assertEquals(false, body.containsKey("formula"))
        assertEquals(false, body.containsKey("lookupConfig"))
        // Not a choice field, so no options key at all.
        assertEquals(false, body.containsKey("options"))
    }

    @Test
    fun `a choice field always sends its options, and never a blank one`() {
        val field = TemplateField("c", "Camera", "select", options = listOf("A-Cam", "", "B-Cam"))

        val options = field.toBody()["options"]?.jsonArray?.map { it.jsonPrimitive.content }

        assertEquals(listOf("A-Cam", "B-Cam"), options)
    }

    @Test
    fun `a multiselect default survives as a list rather than becoming text`() {
        // ⚠ Why defaultValue is raw JSON: flattened to a String this would
        // come back as "[a, b]" the first time the template was saved.
        val field = TemplateField(
            key = "m",
            label = "Cameras",
            type = "multiselect",
            options = listOf("A-Cam", "B-Cam"),
            defaultValue = buildJsonArray { add(JsonPrimitive("A-Cam")); add(JsonPrimitive("B-Cam")) },
        )

        val back = field.toBody()["defaultValue"]

        assertTrue(back is JsonArray)
        assertEquals(listOf("A-Cam", "B-Cam"), back.map { it.jsonPrimitive.content })
    }

    @Test
    fun `moving an item off either end leaves the list alone`() {
        val list = listOf("a", "b", "c")

        assertEquals(listOf("b", "a", "c"), Templates.moved(list, 1, -1))
        assertEquals(listOf("a", "c", "b"), Templates.moved(list, 1, 1))
        // Off the top and off the bottom: the same list back, so a call site
        // can offer the control and let this refuse it.
        assertEquals(list, Templates.moved(list, 0, -1))
        assertEquals(list, Templates.moved(list, 2, 1))
        assertEquals(list, Templates.moved(list, 9, 1))
    }

    @Test
    fun `a section's colour is its own, then its built-in one, then its type's`() {
        assertEquals("#2563eb", Templates.sectionColour(TemplateSection("scene", "Scene")))
        assertEquals(
            "#abcdef",
            Templates.sectionColour(TemplateSection("scene", "Scene", color = "#abcdef")),
        )
        assertEquals(
            "#6366f1",
            Templates.sectionColour(TemplateSection("custom_1", "Notes", sectionType = "notes")),
        )
        assertEquals("#6366f1", Templates.sectionColour(TemplateSection("custom_2", "Mine")))
    }

    @Test
    fun `an exported template carries the design and none of the bookkeeping`() {
        val template = Rec(
            buildJsonObject {
                put("_id", "t1")
                put("name", "Day template")
                put("description", "d")
                put("version", 7)
                put("is_default", true)
                put("tags", buildJsonArray { add(JsonPrimitive("unit")) })
                put("sections", buildJsonArray { add(buildJsonObject { put("key", "scene") }) })
            },
        )

        val file = Templates.portable(template)

        assertEquals(setOf("name", "description", "tags", "sections"), file.keys)
        // An import must not claim another project's default or pretend to
        // be version 7 of something.
        assertEquals(false, file.containsKey("version"))
        assertEquals(false, file.containsKey("is_default"))
        assertEquals(false, file.containsKey("_id"))
    }

    @Test
    fun `a file is a template only with a name and a sections array`() {
        assertTrue(
            Templates.isTemplateFile(
                Rec(buildJsonObject { put("name", "x"); put("sections", JsonArray(emptyList())) }),
            ),
        )
        assertEquals(false, Templates.isTemplateFile(null))
        assertEquals(false, Templates.isTemplateFile(Rec(buildJsonObject { put("name", "x") })))
        assertEquals(
            false,
            Templates.isTemplateFile(Rec(buildJsonObject { put("sections", JsonArray(emptyList())) })),
        )
        // A `sections` that is not an array at all.
        assertEquals(
            false,
            Templates.isTemplateFile(Rec(buildJsonObject { put("name", "x"); put("sections", "none") })),
        )
    }

    @Test
    fun `a file name keeps its letters and loses everything else`() {
        // A RUN collapses to one underscore, so the trailing ")" leaves one
        // too — which is exactly what the web writes.
        assertEquals("Day_1_template_", Templates.fileSafe("Day 1 (template)"))
        assertEquals("template", Templates.fileSafe(""))
        assertEquals("_", Templates.fileSafe("///"))
    }
}
