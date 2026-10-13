package com.zillit.desktop.feature.purchaseorder.domain

/**
 * What a send to the vendor has to warn about — the web's `vendorEmailConfirm`
 * (`lib/vendorEmail.js`), asked of both surfaces that can send in the same
 * words. A plain, coded, first send raises nothing: the button is the
 * confirmation, and a confirm that fires every time gets clicked through.
 */
enum class PoVendorEmailWarning {
    /** Lines without a nominal: the cost will not reach the Cost Report's PO-committed column. */
    Uncoded,

    /** The vendor already has this PO; a second email goes out. */
    Resend,
}

/**
 * The warning, if any, for sending [this] now. Uncoded outranks resend: it
 * names a Cost Report consequence the reader may not know of, and its copy
 * ends in its own question, so the two cannot be joined.
 *
 * Reads the order's SAVED lines. Every line counts, the persisted tax row
 * included — an uncoded tax row is genuinely uncoded — and an untouched spare
 * row (no description, no amount) is exempt, as `isBlankLine` makes it. Run it
 * over the record read back from a save, never over the editor's lines: a page
 * that seeds uncoded rows with the header's code could never look uncoded.
 */
fun PurchaseOrder.vendorEmailWarning(): PoVendorEmailWarning? = when {
    lines.any { line -> line.nominalCode.isNullOrBlank() && !(line.description.isBlank() && line.total == 0.0) } ->
        PoVendorEmailWarning.Uncoded

    emailed -> PoVendorEmailWarning.Resend
    else -> null
}
