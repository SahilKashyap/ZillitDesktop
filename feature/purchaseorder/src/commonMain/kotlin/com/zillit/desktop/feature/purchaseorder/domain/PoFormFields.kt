package com.zillit.desktop.feature.purchaseorder.domain

/**
 * The form template's own names for the fields on a purchase order.
 *
 * Taken from the web's `POForm`, which passes exactly these strings to its
 * `isReq(field, section)` — they are the server's system-field keys, not a
 * guess. Two sections carry them: the order's own details and its delivery
 * address.
 */
object PoFormFields {

    /** The section holding the order's own details. */
    const val DETAILS = "po_details"

    /** The section holding the delivery address. */
    const val DELIVERY = "delivery_address"

    /** The section holding the lines. */
    const val LINE_ITEMS = "line_items"

    const val VENDOR = "vendor"
    const val VENDOR_ADDRESS = "vendor_address"
    const val ACCOUNT_CODE = "account_code"
    const val DESCRIPTION = "description"
    const val DEPARTMENT = "department"
    const val COMPANY = "company"
    const val CURRENCY = "currency"
    const val EPISODE = "episode"
    const val EFFECTIVE_DATE = "effective_date"
    const val DELIVERY_DATE = "delivery_date"
    const val NOTES = "notes"

    const val DELIVERY_NAME = "delivery_name"
    const val DELIVERY_EMAIL = "delivery_email"
    const val DELIVERY_PHONE = "delivery_phone"
    const val DELIVERY_LINE1 = "delivery_line1"
    const val DELIVERY_LINE2 = "delivery_line2"
    const val DELIVERY_CITY = "delivery_city"
    const val DELIVERY_STATE = "delivery_state"
    const val DELIVERY_POSTAL_CODE = "delivery_postal_code"
    const val COUNTRY = "country"

    /**
     * What the desktop form offers a control for.
     *
     * Everything the web's does, which is the point of the port. A template
     * requiring something outside this set is reported rather than enforced —
     * refusing a raise over a control that is not on screen would leave the
     * person with nothing to put right.
     */
    val RENDERED: Set<String> = setOf(
        VENDOR,
        ACCOUNT_CODE,
        DESCRIPTION,
        DEPARTMENT,
        COMPANY,
        CURRENCY,
        EPISODE,
        EFFECTIVE_DATE,
        DELIVERY_DATE,
        NOTES,
    )
}
