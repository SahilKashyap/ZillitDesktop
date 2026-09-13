package com.zillit.desktop.core.forms

import com.zillit.desktop.core.forms.FormField
import com.zillit.desktop.core.forms.FormSection
import com.zillit.desktop.core.forms.FormTemplate
import com.zillit.desktop.core.forms.fieldLabelFor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The document a form is built from, and the reordering rules around it.
 *
 * The web's own comments name the bug this file exists to prevent twice: every
 * surface filters something out, so a position taken from what is on screen
 * addresses a different row in the document.
 */
class FormTemplateTest {

    private fun field(
        label: String,
        order: Int,
        system: Boolean = true,
        hidden: Boolean = false,
    ) = FormField(
        label = label,
        name = label.replaceFirstChar(Char::uppercase),
        order = order,
        systemDefault = system,
        hidden = hidden,
    )

    private fun template() = FormTemplate(
        listOf(
            FormSection(
                key = "header",
                label = "Header",
                order = 1,
                systemDefault = true,
                fields = listOf(field("vendor", 1), field("date", 2), field("notes", 3)),
            ),
            FormSection(
                key = "lines",
                label = "Line Items",
                order = 2,
                systemDefault = true,
                fields = listOf(field("code", 1), field("amount", 2)),
            ),
            FormSection(
                key = FormTemplate.TERMS_SECTION,
                label = "Terms of Engagement",
                order = 3,
                systemDefault = true,
                fields = listOf(field("terms", 1)),
            ),
        ),
    )

    /**
     * The terms section is hidden from the editor but kept in the document.
     *
     * Dropping it would delete a production's clauses on the next save, and it
     * is superseded by the Terms and Conditions document rather than gone.
     */
    @Test
    fun `the terms section is not configurable and not deleted`() {
        val template = template()

        assertEquals(listOf("header", "lines"), template.configurable.map { it.key })
        assertNotNull(template.section(FormTemplate.TERMS_SECTION))
        assertEquals(3, template.sections.size)
    }

    /**
     * A move addresses sections by key.
     *
     * The editor's list is two rows shorter than the document, so a position
     * from it names the wrong section — the web's ZL-20902.
     */
    @Test
    fun `moving a section uses keys, not the positions on screen`() {
        val moved = template().moveSection("lines", "header")

        assertEquals(listOf("lines", "header", FormTemplate.TERMS_SECTION), moved.ordered.map { it.key })
        assertEquals(listOf(1, 2, 3), moved.ordered.map { it.order })
    }

    @Test
    fun `nudging a section past the end changes nothing`() {
        val template = template()

        assertEquals(template.ordered.map { it.key }, template.nudgeSection("header", -1).ordered.map { it.key })
    }

    /** A new section lands after the one named, and every order is renumbered. */
    @Test
    fun `a section is inserted after the key it names`() {
        val added = template().addSection("Delivery", afterKey = "header", nowMillis = 42)

        assertEquals(
            listOf("header", "custom_42", "lines", FormTemplate.TERMS_SECTION),
            added.ordered.map { it.key },
        )
        assertEquals(listOf(1, 2, 3, 4), added.ordered.map { it.order })
        assertFalse(added.section("custom_42")!!.systemDefault)
    }

    @Test
    fun `a section with no name is not added`() {
        assertEquals(3, template().addSection("   ", null, nowMillis = 1).sections.size)
    }

    /**
     * Two fields cannot share a key.
     *
     * The form stores a value under it, so a duplicate would have the second
     * field overwrite the first on every submission.
     */
    @Test
    fun `a custom field gets a key nothing else in the section holds`() {
        val added = template()
            .addField("header", "Vendor", "text")
            .addField("header", "Vendor", "text")

        val keys = added.section("header")!!.fields.map { it.label }
        assertEquals(listOf("vendor", "date", "notes", "vendor_2", "vendor_3"), keys)
    }

    @Test
    fun `a field name becomes a key with nothing but letters, digits and underscores`() {
        assertEquals("budget_code", fieldLabelFor("Budget Code"))
        assertEquals("cost_category", fieldLabelFor("  Cost Category!  "))
        assertEquals("custom_field", fieldLabelFor("!!!"))
    }

    /**
     * A system field is taken off the form, not deleted, and stops being
     * required on the way out.
     *
     * Leaving it required would resurrect a mandatory field the moment
     * somebody put it back.
     */
    @Test
    fun `removing a system field hides it and clears required`() {
        val required = template().editField("header", "vendor") { it.copy(required = true) }

        val hidden = required.hideField("header", "vendor")

        val vendor = hidden.section("header")!!.fields.first { it.label == "vendor" }
        assertTrue(vendor.hidden)
        assertFalse(vendor.required)
        assertEquals(3, hidden.section("header")!!.fields.size)
    }

    @Test
    fun `a restored field comes back on the form`() {
        val restored = template().hideField("header", "date").restoreField("header", "date")

        assertTrue(restored.section("header")!!.visible.any { it.label == "date" })
    }

    /** A custom field is deleted outright, and the rest renumber. */
    @Test
    fun `deleting a custom field renumbers what is left`() {
        val withCustom = template().addField("lines", "Budget Code", "text")

        val gone = withCustom.removeField("lines", "budget_code")

        assertEquals(listOf("code", "amount"), gone.section("lines")!!.fields.map { it.label })
        assertEquals(listOf(1, 2), gone.section("lines")!!.fields.map { it.order })
    }

    /**
     * A field is nudged past its hidden neighbours, not around them.
     *
     * `ordered` keeps hidden fields in the list, so one step is one step in the
     * document rather than one step in what happens to be on screen.
     */
    @Test
    fun `nudging a field moves it one place in the document`() {
        val moved = template().nudgeField("header", "notes", -1)

        assertEquals(listOf("vendor", "notes", "date"), moved.section("header")!!.ordered.map { it.label })
        assertEquals(listOf(1, 2, 3), moved.section("header")!!.ordered.map { it.order })
    }

    @Test
    fun `nudging the first field up changes nothing`() {
        val template = template()

        assertEquals(
            template.section("header")!!.ordered.map { it.label },
            template.nudgeField("header", "vendor", -1).section("header")!!.ordered.map { it.label },
        )
    }

    /**
     * A drop lands the field where the target sits, whatever is hidden above.
     *
     * The rearrange panel lists only the fields on the form; addressing the
     * drop by position would move the wrong field whenever one is hidden.
     */
    @Test
    fun `dropping a field moves it to the target's place by key`() {
        val withHidden = template().hideField("header", "date")

        val moved = withHidden.moveField("header", "notes", "vendor")

        assertEquals(listOf("notes", "vendor", "date"), moved.section("header")!!.ordered.map { it.label })
        assertEquals(listOf(1, 2, 3), moved.section("header")!!.ordered.map { it.order })
    }

    @Test
    fun `dropping a field on a key the section does not hold changes nothing`() {
        val template = template()

        assertEquals(template, template.moveField("header", "vendor", "nowhere"))
        assertEquals(template, template.moveField("header", "vendor", "vendor"))
    }

    /** A field moved into another section lands at the end of it, renumbered. */
    @Test
    fun `a field moved between sections is renumbered in both`() {
        val moved = template().moveFieldToSection("header", "date", "lines")

        assertEquals(listOf("vendor", "notes"), moved.section("header")!!.ordered.map { it.label })
        assertEquals(listOf(1, 2), moved.section("header")!!.ordered.map { it.order })
        assertEquals(listOf("code", "amount", "date"), moved.section("lines")!!.ordered.map { it.label })
        assertEquals(listOf(1, 2, 3), moved.section("lines")!!.ordered.map { it.order })
    }

    /** Landing on a section that already holds that key re-keys the arrival. */
    @Test
    fun `a field moved onto a key the destination holds is re-keyed`() {
        val clash = FormTemplate(
            listOf(
                FormSection(key = "a", order = 1, fields = listOf(field("code", 1))),
                FormSection(key = "b", order = 2, fields = listOf(field("code", 1))),
            ),
        )

        val moved = clash.moveFieldToSection("a", "code", "b")

        assertEquals(listOf("code", "code_2"), moved.section("b")!!.ordered.map { it.label })
        assertTrue(moved.section("a")!!.fields.isEmpty())
    }

    @Test
    fun `removing a section takes its fields and renumbers the rest`() {
        val gone = template().removeSection("header")

        assertEquals(listOf("lines", FormTemplate.TERMS_SECTION), gone.ordered.map { it.key })
        assertEquals(listOf(1, 2), gone.ordered.map { it.order })
        assertNull(gone.section("header"))
    }

    @Test
    fun `the counts describe the document, not the editor`() {
        val edited = template().addField("header", "Budget Code", "text")

        assertEquals(2, edited.configurable.size)
        assertEquals(7, edited.fieldCount)
        assertEquals(1, edited.customFieldCount)
    }
}
