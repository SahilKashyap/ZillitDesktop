package com.zillit.desktop.core.forms

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What a module's form shows, read from the template the hub edits.
 *
 * The rule that matters most is the one about a template that has not been
 * read: an unread template shows everything. A form that blanked its own
 * controls because a fetch failed would be a form nobody could raise anything
 * on, and the failure is invisible.
 */
class FormLayoutTest {

    private fun field(
        label: String,
        required: Boolean = false,
        hidden: Boolean = false,
        system: Boolean = true,
        name: String = label,
    ) = FormField(
        label = label,
        name = name,
        required = required,
        hidden = hidden,
        systemDefault = system,
    )

    private val template = FormTemplate(
        listOf(
            FormSection(
                key = "po_details",
                label = "Order details",
                order = 1,
                systemDefault = true,
                fields = listOf(
                    field("vendor", required = true),
                    field("account_code"),
                    field("notes", hidden = true),
                    field("department", required = true),
                    field("budget_code", system = false, required = true, name = "Budget code"),
                    field("cost_centre", system = false, name = "Cost centre"),
                ),
            ),
        ),
    )

    private val layout = FormLayout(template)

    /**
     * An unread template shows everything.
     *
     * The alternative is a form that hides its own fields because a fetch
     * failed, which nobody can raise anything on and which says nothing about
     * why.
     */
    @Test
    fun `an unread template shows every field`() {
        val empty = FormLayout(FormTemplate())

        assertFalse(empty.isLoaded)
        assertTrue(empty.shows("po_details", "vendor"))
        assertTrue(empty.shows("po_details", "anything_at_all"))
        assertFalse(empty.isRequired("po_details", "vendor"))
    }

    @Test
    fun `a hidden field is off the form`() {
        assertTrue(layout.shows("po_details", "vendor"))
        assertFalse(layout.shows("po_details", "notes"))
    }

    /** A field the template never mentions is off the form once it is read. */
    @Test
    fun `a field the template does not name is not shown`() {
        assertFalse(layout.shows("po_details", "delivery_date"))
    }

    @Test
    fun `required is read per field`() {
        assertTrue(layout.isRequired("po_details", "vendor"))
        assertFalse(layout.isRequired("po_details", "account_code"))
    }

    @Test
    fun `the custom fields are the ones this production added`() {
        assertEquals(listOf("budget_code", "cost_centre"), layout.custom("po_details").map { it.label })
    }

    /** A required custom field left blank is named, by its display name. */
    @Test
    fun `a blank required custom field is reported`() {
        val missing = layout.missingCustom("po_details", mapOf("cost_centre" to "PROD"))

        assertEquals(listOf("Budget code"), missing.map { it.name })
    }

    @Test
    fun `a filled required custom field is not reported`() {
        assertTrue(
            layout.missingCustom("po_details", mapOf("budget_code" to "4100")).isEmpty(),
        )
    }

    /**
     * A required system field this screen cannot offer is reported, not enforced.
     *
     * The desktop's form is smaller than the web's. Blocking somebody on a
     * control that is not on their screen leaves them with nothing to do about
     * it, so the caller is told and the server decides.
     */
    @Test
    fun `a required field the screen does not render is reported separately`() {
        val unanswerable = layout.requiredMissing("po_details", rendered = setOf("vendor", "account_code"))

        assertEquals(listOf("department"), unanswerable.map { it.label })
    }

    /** A custom field is never in that list — the screen renders all of them. */
    @Test
    fun `custom fields are not counted as unanswerable`() {
        val unanswerable = layout.requiredMissing("po_details", rendered = emptySet())

        assertTrue(unanswerable.none { it.isCustom })
    }

    /**
     * An answer nobody gave is left out rather than sent as an empty string.
     *
     * A field somebody skipped and a field somebody cleared read the same on
     * the wire, and storing blanks fills a printed order with empty rows.
     */
    @Test
    fun `blank answers are left out of what is sent`() {
        val group = layout.customValues(
            "po_details",
            mapOf("budget_code" to "4100", "cost_centre" to "   "),
        )

        assertEquals("Order details", group?.section)
        val field = group?.fields?.single()
        assertEquals("Budget code", field?.name)
        assertEquals("4100", field?.value)
        // The key and the type travel too: petty cash stores them so a saved
        // answer can be rendered again without the template.
        assertEquals("budget_code", field?.label)
        assertEquals("text", field?.type)
    }

    /** A section where nothing was answered sends nothing at all. */
    @Test
    fun `a section with no answers produces no group`() {
        assertNull(layout.customValues("po_details", emptyMap()))
        assertNull(FormLayout(FormTemplate()).customValues("po_details", mapOf("a" to "b")))
    }

    /** Groups are labelled by what a reader sees, not by the section's key. */
    @Test
    fun `the group is named by the section's label`() {
        val group = layout.customValues("po_details", mapOf("budget_code" to "4100"))

        assertEquals("Order details", group?.section)
    }
}
