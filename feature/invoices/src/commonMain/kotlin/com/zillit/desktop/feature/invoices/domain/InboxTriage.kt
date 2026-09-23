package com.zillit.desktop.feature.invoices.domain

import kotlin.math.abs
import kotlin.math.floor

/**
 * What an inbox entry must carry before it may be processed on to
 * pre-approval — `lib/inboxRequiredFields.js`, shared by the single review's
 * Accept and the queue's bulk Process so the two cannot disagree.
 */
enum class InboxField { Vendor, Department, Currency, EffectiveDate, PayMethod, GrossAmount }

/** The resolved values the rule reads — a form's, or a queue row's with the review's fallbacks. */
data class InboxValues(
    val vendorId: String = "",
    val departmentId: String = "",
    val currency: String = "",
    val effectiveDate: String = "",
    val payMethod: String = "",
    val gross: Double? = null,
)

/** Which figure of the Net / Tax / Gross trio was typed. */
enum class AmountField { Net, Tax, Gross }

/** The trio as typed, and whether Gross was typed itself (then it stays put). */
data class AmountSplit(val net: String, val tax: String, val gross: String, val grossAnchored: Boolean)

/** One order picked to match an inbox invoice — its id and the number the chip shows. */
data class PoPick(val id: String, val number: String, val gross: Double? = null)

/**
 * What Accept sends as `updates` on `POST /invoices/process` — the review
 * modal's `proceedWithSubmit`, field for field.
 */
data class InboxAccept(
    val invoiceNumber: String,
    val vendorId: String,
    val description: String,
    /** `YYYY-MM-DD`; blank dates are left off. */
    val invoiceDate: String,
    val dueDate: String,
    val effectiveDate: String,
    val net: String,
    val tax: String,
    val gross: String,
    val poIds: List<String>,
    val payMethod: PayMethod,
    val departmentId: String,
    val currency: String,
    val companyId: String,
    val bankId: String,
    val episode: String,
)

object InboxTriage {

    fun missing(values: InboxValues): List<InboxField> = buildList {
        if (values.vendorId.isBlank()) add(InboxField.Vendor)
        if (values.departmentId.isBlank()) add(InboxField.Department)
        if (values.currency.isBlank()) add(InboxField.Currency)
        if (values.effectiveDate.isBlank()) add(InboxField.EffectiveDate)
        if (values.payMethod.isBlank()) add(InboxField.PayMethod)
        if ((values.gross ?: 0.0) <= 0.0) add(InboxField.GrossAmount)
    }

    /**
     * A queue row as the review would seed it — `inboxRowValues`: the currency
     * falls back to the project's, and a blank pay method reads as BACS, so
     * bulk is never stricter than a single accept on the same invoice.
     */
    fun valuesOf(invoice: Invoice, defaultCurrency: String): InboxValues = InboxValues(
        vendorId = invoice.vendorId,
        departmentId = invoice.departmentId,
        currency = invoice.currency.ifBlank { defaultCurrency },
        effectiveDate = InvoiceFormat.toDateInput(invoice.effectiveDateMs),
        payMethod = invoice.payMethod.wire,
        gross = invoice.grossAmount,
    )

    /**
     * Net, Tax and Gross kept consistent — `applyAmountEdit`. Until Gross is
     * typed it follows Net + Tax; once typed it is the anchor, and editing Net
     * works Tax out (and the other way round). Clearing a field never moves
     * an anchored Gross.
     */
    fun applyAmountEdit(split: AmountSplit, field: AmountField, value: String): AmountSplit {
        if (field == AmountField.Gross) return split.copy(gross = value, grossAnchored = true)
        val next = if (field == AmountField.Net) split.copy(net = value) else split.copy(tax = value)
        if (!split.grossAnchored) {
            val bothBlank = next.net.isBlank() && next.tax.isBlank()
            return next.copy(gross = if (bothBlank) "" else derived(amount(next.net) + amount(next.tax)))
        }
        if (value.isBlank()) return next
        val gross = amount(split.gross)
        return if (field == AmountField.Net) {
            next.copy(tax = derived(gross - amount(value)))
        } else {
            next.copy(net = derived(gross - amount(value)))
        }
    }

    /** Net and Tax typed, and not adding up to a positive Gross — `describeAmountSplit.mismatch`. */
    fun splitMismatch(split: AmountSplit): Boolean {
        val checked = split.net.isNotBlank() || split.tax.isNotBlank()
        val gross = amount(split.gross)
        val diff = round2(round2(amount(split.net) + amount(split.tax)) - gross)
        return checked && gross > 0.0 && abs(diff) > PENNY
    }

    /** The OCR'd supplier name, matched to a vendor exactly (case aside) — `resolveSupplierVendor`. */
    fun vendorFor(supplierName: String, vendors: Collection<Vendor>): String? {
        val key = supplierName.trim().lowercase()
        if (key.isEmpty()) return null
        return vendors.firstOrNull { it.name.trim().lowercase() == key }?.id
    }

    /**
     * The selected orders against the invoice's gross — the review's PO badge:
     * matched to the penny, over, or under; null when an order's amount is
     * not known.
     */
    fun poBalance(invoiceGross: Double, picks: List<PoPick>): Double? {
        if (picks.isEmpty() || picks.any { it.gross == null }) return null
        return round2(invoiceGross - picks.sumOf { it.gross ?: 0.0 })
    }

    fun amount(text: String): Double = text.trim().replace(",", "").toDoubleOrNull() ?: 0.0

    /** The web's `String(Math.round(n * 100) / 100)`: "100", "100.5", never "100.00"; nothing below zero. */
    private fun derived(value: Double): String {
        val rounded = round2(value)
        if (rounded <= 0.0) return "0"
        return InvoiceFormat.plain(rounded).trimEnd('0').trimEnd('.')
    }

    private fun round2(value: Double): Double = floor(value * CENTS + HALF) / CENTS

    private const val CENTS = 100.0
    private const val HALF = 0.5
    private const val PENNY = 0.01
}
