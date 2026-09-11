package com.zillit.desktop.feature.purchaseorder

import com.zillit.desktop.core.forms.FormField
import com.zillit.desktop.core.forms.FormLayout
import com.zillit.desktop.core.forms.FormSection
import com.zillit.desktop.core.forms.FormTemplate
import com.zillit.desktop.core.socket.SocketEventName
import com.zillit.desktop.feature.purchaseorder.data.PO_FORM_MODULE
import com.zillit.desktop.feature.purchaseorder.data.PO_FORM_SYNC_EVENTS
import com.zillit.desktop.feature.purchaseorder.data.PO_SYNC_EVENTS
import com.zillit.desktop.feature.purchaseorder.data.poRefreshFor
import com.zillit.desktop.feature.purchaseorder.domain.PoRefresh
import com.zillit.desktop.feature.purchaseorder.domain.PoFormFields
import com.zillit.desktop.feature.purchaseorder.domain.PoLine
import com.zillit.desktop.feature.purchaseorder.ui.PoDraft
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The purchase order form, as the production configured it.
 *
 * The field names here are the server's own — the web's `POForm.validate()`
 * switches on exactly these strings — so a mistake in them is a field hidden
 * or required that nobody asked for.
 */
class PoFormTemplateTest {

    private fun template(vararg fields: FormField) = FormTemplate(
        listOf(
            FormSection(
                key = PoFormFields.DETAILS,
                label = "Order details",
                order = 1,
                systemDefault = true,
                fields = fields.toList(),
            ),
        ),
    )

    private val draft = PoDraft(
        vendorId = "v1",
        vendorName = "Panavision",
        description = "Camera package",
        lines = listOf(PoLine(null, "Body", 1.0, 100.0, null, null)),
    )

    /** The four this form renders, named as the service names them. */
    @Test
    fun `the rendered fields are the ones this form has a control for`() {
        assertEquals(
            setOf("vendor", "account_code", "description", "notes"),
            PoFormFields.RENDERED,
        )
        assertEquals("po_details", PoFormFields.DETAILS)
    }

    /** A hidden system field is off this form too. */
    @Test
    fun `a field the template hides is not shown`() {
        val layout = FormLayout(
            template(
                FormField(label = "vendor", systemDefault = true),
                FormField(label = "notes", systemDefault = true, hidden = true),
            ),
        )

        assertTrue(layout.shows(PoFormFields.DETAILS, PoFormFields.VENDOR))
        assertFalse(layout.shows(PoFormFields.DETAILS, PoFormFields.NOTES))
    }

    /**
     * The custom answers go out grouped by the section's label.
     *
     * Only what was filled in: a blank answer and a skipped one read the same
     * on the wire.
     */
    @Test
    fun `a custom answer travels with the order`() {
        val layout = FormLayout(
            template(
                FormField(label = "budget_code", name = "Budget code", systemDefault = false),
                FormField(label = "cost_centre", name = "Cost centre", systemDefault = false),
            ),
        )

        val request = draft
            .copy(customFields = mapOf("budget_code" to "4100", "cost_centre" to ""))
            .toRequest(layout = layout)

        assertEquals(1, request.customFields.size)
        assertEquals("Order details", request.customFields.first().section)
        assertEquals(listOf("Budget code"), request.customFields.first().fields.map { it.name })
        assertEquals(listOf("4100"), request.customFields.first().fields.map { it.value })
    }

    /** A production with no custom fields sends none, not an empty group. */
    @Test
    fun `an order with nothing custom carries nothing custom`() {
        val request = draft.toRequest(layout = FormLayout(FormTemplate()))

        assertTrue(request.customFields.isEmpty())
    }

    /**
     * A form-template frame is filtered by module.
     *
     * A change to Petty Cash's form must not reload this one's — the frame
     * names which module it is about, and the two share the event name.
     */
    @Test
    fun `the form template frames are subscribed and named`() {
        assertEquals(
            listOf(SocketEventName("form_template:changed"), SocketEventName("form_template:reset")),
            PO_FORM_SYNC_EVENTS,
        )
        assertTrue(PO_SYNC_EVENTS.containsAll(PO_FORM_SYNC_EVENTS))
        assertEquals("purchase_orders", PO_FORM_MODULE)
    }

    /** Another module's form change is not this form's business. */
    @Test
    fun `a petty cash form change does not reload the purchase order form`() {
        val changed = SocketEventName("form_template:changed")

        assertEquals(PoRefresh.FormTemplate, poRefreshFor(changed, module = "purchase_orders"))
        // A frame that names no module is taken as ours; the service does not
        // always fill it in, and a wasted read beats a stale form.
        assertEquals(PoRefresh.FormTemplate, poRefreshFor(changed, module = null))
        assertNull(poRefreshFor(changed, module = "cash_expenses"))
    }
}
