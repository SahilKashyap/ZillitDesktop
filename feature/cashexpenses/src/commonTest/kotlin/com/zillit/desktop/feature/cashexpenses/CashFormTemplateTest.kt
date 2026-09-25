package com.zillit.desktop.feature.cashexpenses

import com.zillit.desktop.core.forms.FormField
import com.zillit.desktop.core.forms.FormLayout
import com.zillit.desktop.core.forms.FormSection
import com.zillit.desktop.core.forms.FormTemplate
import com.zillit.desktop.core.forms.customValues
import com.zillit.desktop.feature.cashexpenses.data.CASH_FORM_MODULE
import com.zillit.desktop.feature.cashexpenses.data.isCashFormFrame
import com.zillit.desktop.feature.cashexpenses.domain.CashFormFields
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The float request form, as the production configured it.
 *
 * The field names are the server's own — the web's
 * `PCFloatRequestPage.FIELD_TO_PAYLOAD` maps each to the payload key it
 * becomes — so a mistake in them hides or requires a field nobody asked for.
 */
class CashFormTemplateTest {

    private fun layout(vararg fields: FormField) = FormLayout(
        FormTemplate(
            listOf(
                FormSection(
                    key = CashFormFields.FLOAT_REQUEST,
                    label = "Float Request",
                    order = 1,
                    systemDefault = true,
                    fields = fields.toList(),
                ),
            ),
        ),
    )

    @Test
    fun `the rendered fields are the ones this form has a control for`() {
        assertEquals(
            setOf(
                "user_id", "requested_amount", "purpose", "duration", "duration_type", "department_id",
                "collect_date", "episode", "collection_method",
            ),
            CashFormFields.RENDERED,
        )
        assertEquals("float_request", CashFormFields.FLOAT_REQUEST)
    }

    /** A hidden system field is off this form too. */
    @Test
    fun `a hidden duration is not shown`() {
        val form = layout(
            FormField(label = "requested_amount", systemDefault = true),
            FormField(label = "duration", systemDefault = true, hidden = true),
        )

        assertTrue(form.shows(CashFormFields.FLOAT_REQUEST, CashFormFields.AMOUNT))
        assertFalse(form.shows(CashFormFields.FLOAT_REQUEST, CashFormFields.DURATION))
    }

    /**
     * This service keeps more of a custom field than purchase orders do.
     *
     * The key, the type and a select's source travel with the answer, so a
     * saved request can be shown again without re-reading the template.
     */
    @Test
    fun `a custom answer carries the field's identity`() {
        val form = layout(
            FormField(
                label = "budget_code",
                name = "Budget code",
                type = "select",
                systemDefault = false,
                selectionType = "account_code",
            ),
        )

        val group = form.customValues(CashFormFields.FLOAT_REQUEST, mapOf("budget_code" to "4100"))

        assertEquals("Float Request", group?.section)
        val field = group?.fields?.single()
        assertEquals("Budget code", field?.name)
        assertEquals("budget_code", field?.label)
        assertEquals("select", field?.type)
        assertEquals("account_code", field?.selectionType)
        assertEquals("4100", field?.value)
    }

    /**
     * Every float-request field is required but the department, and the start
     * date and collection time are gone (`REMOVED_FLOAT_FIELDS`).
     */
    @Test
    fun `the request's fields are all required but the department, less the removed two`() {
        val form = layout(
            FormField(label = "department_id", name = "Department", systemDefault = true, order = 1),
            FormField(label = "collect_date", name = "Collection date", systemDefault = true, order = 2),
            FormField(label = "start_date", name = "Start date", systemDefault = true, order = 3, required = true),
            FormField(label = "collect_time", name = "Collect time", systemDefault = true, order = 4),
        )

        val fields = CashFormFields.requestFields(form.visible(CashFormFields.FLOAT_REQUEST))

        assertEquals(listOf("department_id", "collect_date"), fields.map { it.label })
        assertEquals(listOf(false, true), fields.map { it.required })
    }

    /** A form-template frame is filtered by module. */
    @Test
    fun `another module's form change is not this form's`() {
        assertEquals("cash_expenses", CASH_FORM_MODULE)
        assertTrue(isCashFormFrame("cash_expenses"))
        // A frame that names no module is taken as ours: the service does not
        // always fill it in, and a wasted read beats a stale form.
        assertTrue(isCashFormFrame(null))
        assertFalse(isCashFormFrame("purchase_orders"))
    }
}
