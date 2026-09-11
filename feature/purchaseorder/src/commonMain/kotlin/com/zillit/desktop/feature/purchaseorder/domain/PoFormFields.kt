package com.zillit.desktop.feature.purchaseorder.domain

/**
 * The form template's own names for the fields on a purchase order.
 *
 * Taken from the web's `POForm.validate()`, which switches on exactly these
 * strings — they are the server's system-field keys, not a guess.
 *
 * This screen renders four of them. The rest exist on the web's larger form,
 * and a template that marks one of those required cannot be satisfied here;
 * see [RENDERED].
 */
object PoFormFields {

    /** The section holding the order's own details. */
    const val DETAILS = "po_details"

    /** The section holding the lines. */
    const val LINE_ITEMS = "line_items"

    const val VENDOR = "vendor"
    const val ACCOUNT_CODE = "account_code"
    const val DESCRIPTION = "description"
    const val NOTES = "notes"

    /**
     * What this screen actually offers a control for.
     *
     * Company, department, currency, the two dates and the delivery address
     * belong to the web's fuller form. A template requiring one of those is
     * reported rather than enforced: refusing the raise would leave the person
     * with nothing on screen to put right.
     */
    val RENDERED: Set<String> = setOf(VENDOR, ACCOUNT_CODE, DESCRIPTION, NOTES)
}
