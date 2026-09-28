// Which of the line table's columns Forms Configuration shows — shared by
// the New PO form and PO Entry, whose line editors both draw the same
// `line_items` section.
package com.zillit.desktop.feature.purchaseorder.ui.pages

import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.forms.FormField
import com.zillit.desktop.core.forms.FormLayout
import com.zillit.desktop.feature.purchaseorder.domain.PoFormFields

/** The Custom column's width, shared by the header and every row's cell. */
internal val CUSTOM_FIELD_WIDTH = 110.dp

/**
 * Which system fields the template puts on the line table, and what this
 * production has added — the web's `getVisibleFields('line_items')`, not a
 * fixed column set. With no template read, [FormLayout.shows] answers true
 * for everything: a screen must not blank its own columns because a fetch
 * failed.
 */
internal class LineColumns(private val layout: FormLayout, isAccountant: Boolean) {
    val description = layout.shows(PoFormFields.LINE_ITEMS, PoFormFields.LINE_DESCRIPTION)
    val expType = layout.shows(PoFormFields.LINE_ITEMS, PoFormFields.EXP_TYPE)
    val quantity = layout.shows(PoFormFields.LINE_ITEMS, PoFormFields.LINE_QUANTITY)
    val unitPrice = layout.shows(PoFormFields.LINE_ITEMS, PoFormFields.LINE_UNIT_PRICE)
    val code = layout.shows(PoFormFields.LINE_ITEMS, PoFormFields.ACCOUNT_CODE)
    val tax = layout.shows(PoFormFields.LINE_ITEMS, PoFormFields.TAX_TYPE)

    /**
     * Accountant-only, as the web's own field is (`POForm.jsx`'s
     * `isAccountant || f.label !== 'tracking_codes'`) — a department raiser
     * has no reason to see an accounting dimension, whatever the template says.
     */
    val layers = isAccountant && layout.shows(PoFormFields.LINE_ITEMS, PoFormFields.TRACKING_CODES)

    /** This production's own fields on the line — the web's `lineItemCustomFields`. */
    val custom: List<FormField> = layout.custom(PoFormFields.LINE_ITEMS)

    /** [text], with the web's own " *" where the template marks [field] required (`reqMark`). */
    fun reqLabel(text: String, field: String): String =
        text + if (layout.isRequired(PoFormFields.LINE_ITEMS, field)) " *" else ""
}
