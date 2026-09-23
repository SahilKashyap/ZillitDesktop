package com.zillit.desktop.feature.invoices.domain

import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str

/**
 * A batch of approved invoices paid together — the web's Active Runs, on
 * `/invoices/active-runs`.
 *
 * A run is built from open items, authorised by someone with run access, and
 * then paid by one method; its total is the server's, not a client sum.
 */
data class PaymentRun(
    val id: String,
    val number: String = "",
    val name: String = "",
    val payMethod: PayMethod = PayMethod.Bacs,
    val total: Double = 0.0,
    val currency: String = "",
    val invoiceCount: Int = 0,
    val status: PaymentRunStatus = PaymentRunStatus.Draft,
)

/** Where a run stands. Only a pending one can be authorised or turned down. */
enum class PaymentRunStatus(val wire: String, private val labelKey: String) {
    Draft("draft", S.draft),
    Pending("pending", S.dm_filter_status_pending),
    Approved("approved", S.approved),
    Rejected("rejected", S.rejected),
    Paid("paid", S.desktop_paid),
    ;

    val label: String get() = str(labelKey)

    val isDecidable: Boolean get() = this == Pending || this == Draft

    companion object {
        fun from(wire: String?): PaymentRunStatus = entries.firstOrNull { it.wire == wire } ?: Draft
    }
}

/**
 * One vendor's open items in one currency — what a single run pays.
 *
 * A run is single-vendor and single-currency on the web, so a vendor with
 * both GBP and USD invoices makes two runs, not one mixed batch.
 */
data class PaymentGroup(
    val vendorId: String,
    val vendorName: String,
    val currency: String,
    val invoices: List<Invoice>,
) {
    val ids: List<String> get() = invoices.map { it.id }
    val total: Double get() = invoices.sumOf { it.grossAmount }

    /** What the run is called on the ledger — the web's own wording. */
    val runName: String get() = "BACs Run — $vendorName ($currency)"
}

object PaymentRuns {
    /** Wire and faster payment share the Wires tab; they are paid the same way. */
    val WIRE_METHODS = setOf(PayMethod.Wire, PayMethod.Faster)

    fun groupByVendorCurrency(
        invoices: List<Invoice>,
        vendorName: (Invoice) -> String,
        defaultCurrency: String,
    ): List<PaymentGroup> = invoices
        .groupBy { it.vendorId.ifBlank { "unknown" } to it.currency.ifBlank { defaultCurrency }.uppercase() }
        .map { (key, rows) ->
            PaymentGroup(
                vendorId = key.first,
                vendorName = vendorName(rows.first()),
                currency = key.second,
                invoices = rows,
            )
        }
        .sortedWith(compareBy({ it.vendorName.lowercase() }, { it.currency }))

    /**
     * The next run number, continuing the sequence already on the server.
     *
     * The web reads the digits out of every existing number and adds one, so
     * `PR-007` and a hand-typed `Run 12` both count.
     */
    fun nextNumber(runs: List<PaymentRun>, offset: Int = 0): String {
        val highest = runs.mapNotNull { run -> run.number.filter { it.isDigit() }.toIntOrNull() }.maxOrNull() ?: 0
        return "PR-" + (highest + 1 + offset).toString().padStart(RUN_NUMBER_DIGITS, '0')
    }

    private const val RUN_NUMBER_DIGITS = 3
}

/** The four surfaces of the Payment Runs page — the web's tabs. */
enum class PaymentTab(val id: String, private val labelKey: String) {
    OpenItems("openItems", S.desktop_open_items),
    Wires("wires", S.desktop_wires),
    Cheques("cheques", S.desktop_cheques),
    Runs("runs", S.desktop_active_runs),
    ;

    val label: String get() = str(labelKey)

}

/**
 * A sales invoice — money owed *to* the production, on
 * `/invoices/sales-invoices`.
 *
 * The only record in this module that faces outward: it is raised against a
 * client, sent, and then marked paid.
 */
data class SalesInvoice(
    val id: String,
    val reference: String = "",
    val clientName: String = "",
    val description: String = "",
    val grossAmount: Double = 0.0,
    val currency: String = "",
    val dueDateMs: Long? = null,
    val createdAtMs: Long? = null,
    val status: SalesInvoiceStatus = SalesInvoiceStatus.Draft,
)

/** A sales invoice's life: drafted, sent to the client, paid. */
enum class SalesInvoiceStatus(val wire: String, private val labelKey: String) {
    Draft("draft", S.draft),
    Sent("sent", S.cs_sent),
    Paid("paid", S.desktop_paid),
    Overdue("overdue", S.desktop_overdue),
    Cancelled("cancelled", S.cancelled),
    ;

    val label: String get() = str(labelKey)

    /** Sending is for a draft; marking paid is for one already out. */
    val canSend: Boolean get() = this == Draft
    val canMarkPaid: Boolean get() = this == Sent || this == Overdue

    companion object {
        fun from(wire: String?): SalesInvoiceStatus = entries.firstOrNull { it.wire == wire } ?: Draft
    }
}

/**
 * A vendor as the Vendors page shows it: the record, plus what the production
 * has spent with them.
 *
 * The spend is counted here rather than fetched, because no route answers it —
 * the web adds up the same invoice list on the client.
 */
data class VendorRow(
    val vendor: Vendor,
    val totalSpend: Double,
    val invoiceCount: Int,
    val currency: String,
) {
    val isCompliant: Boolean get() = vendor.taxNumber.isNotBlank() && vendor.bankName.isNotBlank()

    /** What the compliance column says: what is missing, or that nothing is. */
    val complianceLabel: String
        get() = when {
            isCompliant -> str(S.dm_action_complete)
            vendor.taxNumber.isBlank() && vendor.bankName.isBlank() -> str(S.desktop_no_tax_id_or_bank)
            vendor.taxNumber.isBlank() -> str(S.desktop_no_tax_id)
            else -> str(S.desktop_no_bank)
        }
}

object VendorSpendReport {
    /** One row per vendor, biggest spend first — the web's Vendors table. */
    fun rows(vendors: List<Vendor>, invoices: List<Invoice>): List<VendorRow> {
        val byVendor = invoices.groupBy { it.vendorId }
        return vendors.map { vendor ->
            val theirs = byVendor[vendor.id].orEmpty()
            VendorRow(
                vendor = vendor,
                totalSpend = theirs.sumOf { it.grossAmount },
                invoiceCount = theirs.size,
                currency = theirs.firstNotNullOfOrNull { it.currency.takeIf(String::isNotBlank) }.orEmpty(),
            )
        }.sortedByDescending { it.totalSpend }
    }
}
