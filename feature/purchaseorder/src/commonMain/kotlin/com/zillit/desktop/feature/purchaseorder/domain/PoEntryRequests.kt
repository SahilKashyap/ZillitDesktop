package com.zillit.desktop.feature.purchaseorder.domain

import kotlinx.serialization.json.JsonElement

/**
 * The header fields the processing page may correct — the web's `hdrPatch`
 * (vendor, company, department, nominal, currency, delivery date). Bank is
 * absent on purpose: the order has no bank column.
 */
data class PoEntryHeader(
    val vendorId: String?,
    val companyId: String?,
    val departmentId: String?,
    val nominalCode: String?,
    val currency: String,
    val deliveryDate: Long?,
)

/**
 * The VAT figures the web sends with every processing-page write —
 * `calcVat(ledgerTotal, vatTreatment)` over the coded net.
 */
data class PoVat(val treatment: String, val amount: Double, val gross: Double) {
    companion object {
        private const val STANDARD_RATE = 0.20

        /**
         * The web's `calcVat`: pending and every zero treatment carry no VAT;
         * standard adds 20%; reverse charge *reports* 20% but leaves the gross at
         * the net, because the production does not pay it to the vendor.
         */
        fun of(net: Double, treatment: String): PoVat = when (treatment) {
            "standard_20" -> PoVat(treatment, net * STANDARD_RATE, net * (1 + STANDARD_RATE))
            "reverse_charged" -> PoVat(treatment, net * STANDARD_RATE, net)
            else -> PoVat(treatment, 0.0, net)
        }
    }
}

/**
 * Save on the processing page — the web's `handleSavePOEntry` PATCH.
 *
 * Deliberately *not* the create/update body: that one carries the description,
 * the attachments column and the custom fields, and resending those from a
 * page that does not edit them is how a partial copy overwrites the record.
 * [lines] includes the consolidated tax line when there is one.
 */
data class PoEntryUpdate(
    val header: PoEntryHeader,
    val lines: List<PoLine>,
    val vat: PoVat,
    val effectiveDate: Long?,
)

/**
 * Post to the ledger — the web's `handlePostPO` body.
 *
 * The server takes the coded lines and the header **with** the post, in the
 * web's camelCase envelope (`vatTreatment`, `lineItems`, `poDetails`), so a post
 * does not depend on an earlier save having landed.
 */
data class PoPostRequest(
    val header: PoEntryHeader,
    val lines: List<PoLine>,
    val vat: PoVat,
    val effectiveDate: Long?,
    val description: String,
    val notes: String?,
    val episode: String?,
    /** The order's `delivery_address` exactly as it was read — an object or a string. */
    val deliveryAddress: JsonElement?,
    val deliveryAddressId: String?,
)
