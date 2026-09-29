package com.zillit.desktop.feature.purchaseorder

import com.zillit.desktop.core.forms.FormField
import com.zillit.desktop.core.forms.FormLayout
import com.zillit.desktop.core.forms.FormSection
import com.zillit.desktop.core.forms.FormTemplate
import com.zillit.desktop.core.socket.SocketEventName
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.purchaseorder.data.PO_FORM_MODULE
import com.zillit.desktop.feature.purchaseorder.data.PO_FORM_SYNC_EVENTS
import com.zillit.desktop.feature.purchaseorder.data.PO_SYNC_EVENTS
import com.zillit.desktop.feature.purchaseorder.data.poRefreshFor
import com.zillit.desktop.feature.purchaseorder.domain.PoRefresh
import com.zillit.desktop.feature.purchaseorder.domain.PoFormFields
import com.zillit.desktop.feature.purchaseorder.domain.PoLine
import com.zillit.desktop.feature.purchaseorder.domain.customFieldPicks
import com.zillit.desktop.feature.purchaseorder.domain.customFieldsJson
import com.zillit.desktop.feature.purchaseorder.ui.PoFormMode
import com.zillit.desktop.feature.purchaseorder.ui.PoFormState
import com.zillit.desktop.feature.purchaseorder.ui.lineProblems
import com.zillit.desktop.feature.purchaseorder.ui.pages.LineColumns
import com.zillit.desktop.feature.purchaseorder.ui.toRequest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
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

    private fun lineTemplate(vararg fields: FormField) = FormTemplate(
        listOf(
            FormSection(
                key = PoFormFields.LINE_ITEMS,
                label = "Line Items",
                order = 2,
                systemDefault = true,
                fields = fields.toList(),
            ),
        ),
    )

    private val draft = PoFormState(
        mode = PoFormMode.NewOrder,
        vendorId = "v1",
        vendorName = "Panavision",
        description = "Camera package",
        lines = listOf(PoLine(null, "Body", 1.0, 100.0, null, null)),
    )

    /**
     * What this form renders, named as the service names them.
     *
     * Ten of them since the full web port: the desktop form now offers every
     * control the web's does, so a template requiring any of these can be
     * satisfied here.
     */
    @Test
    fun `the rendered fields are the ones this form has a control for`() {
        assertEquals(
            setOf(
                "vendor",
                "account_code",
                "description",
                "department",
                "company",
                "currency",
                "episode",
                "effective_date",
                "delivery_date",
                "notes",
            ),
            PoFormFields.RENDERED,
        )
        assertEquals("po_details", PoFormFields.DETAILS)
        assertEquals("delivery_address", PoFormFields.DELIVERY)
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
            .toRequest(status = null, layout = layout)

        assertEquals(1, request.customFields.size)
        assertEquals("Order details", request.customFields.first().section)
        assertEquals(listOf("Budget code"), request.customFields.first().fields.map { it.name })
        assertEquals(listOf("4100"), request.customFields.first().fields.map { it.value })
    }

    /** A production with no custom fields sends none, not an empty group. */
    @Test
    fun `an order with nothing custom carries nothing custom`() {
        val request = draft.toRequest(status = null, layout = FormLayout(FormTemplate()))

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

    /**
     * [LineColumns] — the line table's own columns, from the `line_items`
     * section rather than a fixed set (`POForm.jsx`'s `getVisibleFields`): a
     * hidden system field draws no column, a required one carries " *"
     * (`reqMark`), and the section's own extra fields are the custom columns.
     */
    @Test
    fun `line columns follow the template's own visibility and required marks`() {
        val layout = FormLayout(
            lineTemplate(
                FormField(label = PoFormFields.LINE_DESCRIPTION, systemDefault = true, required = true),
                FormField(label = PoFormFields.EXP_TYPE, systemDefault = true, hidden = true),
                FormField(label = PoFormFields.LINE_QUANTITY, systemDefault = true),
                FormField(label = PoFormFields.LINE_UNIT_PRICE, systemDefault = true),
                FormField(label = PoFormFields.ACCOUNT_CODE, systemDefault = true),
                FormField(label = PoFormFields.TAX_TYPE, systemDefault = true),
                FormField(label = PoFormFields.TRACKING_CODES, systemDefault = true),
                FormField(label = "serial_no", name = "Serial No", systemDefault = false, required = true),
            ),
        )
        val cols = LineColumns(layout, isAccountant = true)

        assertTrue(cols.description)
        assertFalse(cols.expType)
        assertTrue(cols.quantity)
        assertTrue(cols.unitPrice)
        assertTrue(cols.code)
        assertTrue(cols.tax)
        assertTrue(cols.layers)
        assertEquals(listOf("serial_no"), cols.custom.map { it.label })
        assertEquals("Description *", cols.reqLabel("Description", PoFormFields.LINE_DESCRIPTION))
        assertEquals("Code", cols.reqLabel("Code", PoFormFields.ACCOUNT_CODE))

        // A department raiser never sees Layers, whatever the template says —
        // the web's own `isAccountant || f.label !== 'tracking_codes'`.
        assertFalse(LineColumns(layout, isAccountant = false).layers)
    }

    /** With no template read, every system column shows — a fetch failure must not blank the table. */
    @Test
    fun `line columns show everything when the template has not loaded`() {
        val cols = LineColumns(FormLayout(FormTemplate()), isAccountant = true)

        assertTrue(cols.description)
        assertTrue(cols.expType)
        assertTrue(cols.quantity)
        assertTrue(cols.unitPrice)
        assertTrue(cols.code)
        assertTrue(cols.tax)
        assertTrue(cols.layers)
        assertTrue(cols.custom.isEmpty())
    }

    /**
     * [customFieldPicks]/[customFieldsJson] round-trip a line's `custom_fields`
     * — the web's flat `[{name, value}]`, not the header's `{section, fields}`
     * [com.zillit.desktop.core.forms.CustomFieldGroup]. A blank answer drops out,
     * same as the web's own `val !== undefined && val !== ""`.
     */
    @Test
    fun `a line's custom fields round-trip as name-value pairs`() {
        val wire: JsonArray = buildJsonArray {
            add(buildJsonObject { put("name", JsonPrimitive("serial_no")); put("value", JsonPrimitive("SN-42")) })
            add(buildJsonObject { put("name", JsonPrimitive("po_ref")); put("value", JsonPrimitive("")) })
            add(JsonPrimitive("not an object"))
        }
        assertEquals(mapOf("serial_no" to "SN-42"), wire.customFieldPicks())
        assertEquals(
            mapOf("serial_no" to "SN-42"),
            mapOf("serial_no" to "SN-42", "po_ref" to "").customFieldsJson().customFieldPicks(),
        )
        assertEquals(emptyMap(), (null as JsonArray?).customFieldPicks())
    }

    /**
     * [lineProblems] — the web's line-item loop (`POForm.jsx:816-852`). Every
     * required-field check is gated the same way the header's are: only when
     * the template marks that field required, and only for a line someone
     * has started typing a description into.
     */
    @Test
    fun `a required quantity must be greater than zero`() {
        val line = PoLine(null, "Camera body", 0.0, 100.0, null, null)
        val required = FormLayout(
            lineTemplate(FormField(label = PoFormFields.LINE_QUANTITY, systemDefault = true, required = true)),
        )
        assertEquals(
            listOf(str(S.desktop_po_line_qty_required, "1")),
            lineProblems(draft.copy(lines = listOf(line)), required),
        )

        val notRequired = FormLayout(
            lineTemplate(FormField(label = PoFormFields.LINE_QUANTITY, systemDefault = true, required = false)),
        )
        assertTrue(lineProblems(draft.copy(lines = listOf(line)), notRequired).isEmpty())
    }

    /** `li.unitPrice == null || li.unitPrice < 0` — a Kotlin `Double` is never null, so this is just the sign. */
    @Test
    fun `a required unit price must not be negative`() {
        val layout = FormLayout(
            lineTemplate(FormField(label = PoFormFields.LINE_UNIT_PRICE, systemDefault = true, required = true)),
        )
        val line = PoLine(null, "Camera body", 1.0, -5.0, null, null)
        assertEquals(
            listOf(str(S.desktop_po_line_price_required, "1")),
            lineProblems(draft.copy(lines = listOf(line)), layout),
        )
    }

    @Test
    fun `a required expenditure type must be set`() {
        val layout = FormLayout(
            lineTemplate(FormField(label = PoFormFields.EXP_TYPE, systemDefault = true, required = true)),
        )
        val line = PoLine(null, "Camera body", 1.0, 100.0, null, null, expenditureType = null)
        assertEquals(
            listOf(str(S.desktop_po_line_exp_type_required, "1")),
            lineProblems(draft.copy(lines = listOf(line)), layout),
        )
    }

    /** A production's own extra field on a line — the web's generic `default:` branch. */
    @Test
    fun `a required custom field on a line must be answered`() {
        val layout = FormLayout(
            lineTemplate(FormField(label = "serial_no", name = "Serial No", systemDefault = false, required = true)),
        )
        val line = PoLine(null, "Camera body", 1.0, 100.0, null, null)
        assertEquals(
            listOf(str(S.desktop_po_line_custom_required, "1", "Serial No")),
            lineProblems(draft.copy(lines = listOf(line)), layout),
        )

        val answered = line.copy(customFields = mapOf("serial_no" to "SN-1").customFieldsJson())
        assertTrue(lineProblems(draft.copy(lines = listOf(answered)), layout).isEmpty())
    }

    /**
     * The overflow guard (`POForm.jsx:824-833`, ZL-20601) is the one check the
     * web runs unconditionally — not gated by any `required` flag, and not
     * skipped for a line with no description yet, since a blank row's total
     * is always 0 either way.
     */
    @Test
    fun `the overflow guard fires unconditionally, even with no template loaded`() {
        val huge = PoLine(null, "Camera body", 1_000_000_000.0, 1_000_000_000.0, null, null)
        assertEquals(
            listOf(str(S.desktop_po_line_total_too_large, "1")),
            lineProblems(draft.copy(lines = listOf(huge)), FormLayout(FormTemplate())),
        )
    }

    /**
     * `if (li.splitParentId) return` (`POForm.jsx:817`) skips a split child
     * from every check in one shot — not just the required-field switch, the
     * overflow guard too.
     */
    @Test
    fun `a split child is skipped entirely, whatever the template requires and however large its total`() {
        val layout = FormLayout(
            lineTemplate(
                FormField(label = PoFormFields.LINE_QUANTITY, systemDefault = true, required = true),
                FormField(label = PoFormFields.EXP_TYPE, systemDefault = true, required = true),
            ),
        )
        val child = PoLine(
            null,
            "Camera body",
            1_000_000_000.0,
            1_000_000_000.0,
            null,
            null,
            expenditureType = null,
            splitParentId = "line-0",
        )
        assertTrue(lineProblems(draft.copy(lines = listOf(child)), layout).isEmpty())
    }

    /** `if (!li.description?.trim()) return` (`POForm.jsx:834`) — placed after the overflow check, not before it. */
    @Test
    fun `a line with no description yet is skipped for required checks but not the overflow guard`() {
        val layout = FormLayout(
            lineTemplate(FormField(label = PoFormFields.LINE_QUANTITY, systemDefault = true, required = true)),
        )
        val blank = PoLine(null, "", 0.0, 0.0, null, null)
        assertTrue(lineProblems(draft.copy(lines = listOf(blank)), layout).isEmpty())

        val blankButHuge = PoLine(null, "", 1_000_000_000.0, 1_000_000_000.0, null, null)
        assertEquals(
            listOf(str(S.desktop_po_line_total_too_large, "1")),
            lineProblems(draft.copy(lines = listOf(blankButHuge)), layout),
        )
    }
}
