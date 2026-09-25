package com.zillit.desktop.feature.invoices.domain

import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str

/**
 * A vendor as the Vendors page shows it — the web's `SuppliersPage` row: the
 * record (or, for a supplier only ever named on an invoice, a stand-in), and
 * what the production has spent with them.
 *
 * The spend is counted here rather than fetched, because no route answers it —
 * the web adds up the same invoice list on the client (`SuppliersPage.jsx:175-269`).
 */
data class VendorRow(
    val vendor: Vendor,
    /** The spend, converted to the project default when it spans currencies. */
    val totalSpend: Double,
    val invoiceCount: Int,
    val currency: String,
    /** False for a supplier that exists only on invoices — compliance "Unknown". */
    val fromMaster: Boolean = true,
    /** Converted from more than one currency — the web's compact multi-currency total. */
    val mixedCurrency: Boolean = false,
) {
    val name: String get() = vendor.name

    /** The country, "UK" when none is stored (`SuppliersPage.jsx:219, 247`). */
    val country: String get() = vendor.country.ifBlank { VendorSpendReport.DEFAULT_COUNTRY }

    /** A bank is "on file" when the vendor links one — `v.bank_id` (`SuppliersPage.jsx:228`). */
    val hasBank: Boolean get() = vendor.bankId.isNotBlank()

    val hasTaxId: Boolean get() = vendor.taxNumber.isNotBlank()

    /** Any spend at all — the Active tile and chip. */
    val isActive: Boolean get() = totalSpend > 0.0

    /**
     * The web's fixed compliance label: "Pending" for every vendor on the
     * master list, "Unknown" for an invoice-only supplier (`SuppliersPage.jsx:232, 254`).
     */
    val complianceLabel: String get() = if (fromMaster) str(S.pending) else str(S.desktop_unknown)

    /**
     * Flagged for attention — the web's `compliance.variant === "pink"`, which
     * no row is ever given, so the Compliance Issues tile reads nought.
     */
    val hasComplianceIssue: Boolean get() = false

    /** Payment terms as the web prints them — `net_30` → "30 days", unknown passes through. */
    val termsLabel: String get() = VendorSpendReport.termsLabel(vendor.terms)
}

/** The quick filters over the vendor table (`SuppliersPage.jsx:386-408`). */
enum class VendorFilter(private val labelKey: String) {
    All(S.all),
    Active(S.active),
    WithTaxId(S.desktop_inv_with_tax_id),
    NoBank(S.desktop_inv_no_bank_chip),
    ;

    val label: String get() = str(labelKey)

    fun keeps(row: VendorRow): Boolean = when (this) {
        All -> true
        Active -> row.isActive
        WithTaxId -> row.hasTaxId
        NoBank -> !row.hasBank
    }
}

object VendorSpendReport {
    const val DEFAULT_COUNTRY = "UK"

    /**
     * One row per vendor, biggest spend first — `fetchSuppliers`.
     *
     * An invoice's amount is its gross, else its net. Spend is matched by
     * `vendor_id`, and a vendor with none falls back to invoices naming it as
     * `supplier_name`. Mixed currencies convert to the project default through
     * [rates]; one currency keeps its own. Suppliers that are only ever named
     * on invoices get a row of their own.
     */
    fun rows(vendors: List<Vendor>, invoices: List<Invoice>, rates: CurrencyRates = CurrencyRates()): List<VendorRow> {
        val byVendor = invoices.filter { it.vendorId.isNotBlank() }.groupBy { it.vendorId }
        val byName = invoices.filter { it.supplierName.isNotBlank() }.groupBy { it.supplierName.key() }
        val masterNames = vendors.map { it.name.ifBlank { str(S.desktop_unknown) }.key() }.toSet()
        val mapped = vendors.map { vendor ->
            val theirs = byVendor[vendor.id]?.takeIf { it.isNotEmpty() }
                ?: byName[vendor.name.ifBlank { str(S.desktop_unknown) }.key()].orEmpty()
            row(vendor, theirs, rates, fromMaster = true)
        }
        val invoiceOnly = invoices
            .map { it.supplierName }
            .filter { it.isNotBlank() && it.key() !in masterNames }
            .distinctBy { it.key() }
            .map { name -> row(Vendor(id = "", name = name), byName[name.key()].orEmpty(), rates, fromMaster = false) }
        return (mapped + invoiceOnly).sortedByDescending { it.totalSpend }
    }

    /** `formatPaymentTerms` — the enum values the vendor form stores, as words. */
    fun termsLabel(terms: String): String = when (terms.trim()) {
        "" -> "—"
        "net_7" -> str(S.drive_expiry_7d)
        "net_14" -> str(S.desktop_14_days)
        "net_30" -> str(S.drive_expiry_30d)
        "net_60" -> str(S.desktop_60_days)
        else -> terms
    }

    /** An invoice's spend — `gross_amount || net_amount`. */
    fun amountOf(invoice: Invoice): Double =
        invoice.grossAmount.takeIf { it != 0.0 } ?: invoice.netAmount ?: 0.0

    private fun row(vendor: Vendor, theirs: List<Invoice>, rates: CurrencyRates, fromMaster: Boolean): VendorRow {
        val total = rates.total(theirs.map { amountOf(it) to it.currency })
        return VendorRow(
            vendor = vendor,
            totalSpend = total.amount,
            invoiceCount = theirs.size,
            currency = total.currency,
            fromMaster = fromMaster,
            mixedCurrency = total.mixed,
        )
    }

    private fun String.key(): String = trim().lowercase()
}
