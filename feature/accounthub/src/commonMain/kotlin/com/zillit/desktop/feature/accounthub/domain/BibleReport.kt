package com.zillit.desktop.feature.accounthub.domain

/**
 * Where a posted transaction came from.
 *
 * `po` is on the wire but returns nothing: purchase orders are commitments and
 * are not posted to the general ledger. It is offered anyway, because the
 * filter is the ledger's own vocabulary and hiding a value the server accepts
 * would make this list disagree with the export's.
 */
enum class LedgerSource(val wire: String, val label: String) {
    PurchaseOrder("po", "Purchase Order"),
    Invoice("invoice", "Invoice"),
    Card("card", "Production Expense Cards"),
    Cash("cash", "Petty Cash Expenses"),
    Payroll("payroll", "Payroll"),

    /** An accountant's own entry — an accrual, reclass or correction. */
    ManualJournal("manual_je", "Manual Journal"),
    ;

    companion object {
        fun from(wire: String?): LedgerSource? = entries.firstOrNull { it.wire == wire }

        /** What to show for a source the server sent and this client does not model. */
        fun labelFor(wire: String): String =
            from(wire)?.label ?: wire.replace('_', ' ').replaceFirstChar { it.uppercase() }
    }
}

/** One posted transaction, as the closeout bible lists it. */
data class LedgerTransaction(
    val source: String = "",
    val effectiveDateMillis: Long? = null,
    val invoiceNumber: String = "",
    val purchaseOrderNumber: String = "",
    /** A vendor, or a person where the source is cash or payroll. */
    val party: String = "",
    val description: String = "",
    /** What the transaction was raised in, before conversion. */
    val originalCurrency: String = "",
    /** In the report's display currency, converted server-side. */
    val amount: Double = 0.0,
)

/**
 * One account's transactions, and what they come to.
 *
 * The uncoded bucket arrives under the sentinel `__uncoded__` in both the code
 * and the name. It is a real bucket carrying real money, so it is kept and
 * named plainly rather than shown as its sentinel.
 */
data class BibleAccount(
    val code: String = "",
    val name: String = "",
    val total: Double = 0.0,
    val transactions: List<LedgerTransaction> = emptyList(),
) {
    val isUncoded: Boolean get() = code == UNCODED

    val displayCode: String get() = if (isUncoded) "Uncoded" else code.ifBlank { "—" }

    val displayName: String get() = if (name == UNCODED) "" else name

    companion object {
        const val UNCODED = "__uncoded__"
    }
}

/**
 * Every transaction of the period, grouped by the account it posted to.
 *
 * [errors] is the server's per-bucket failures. A bucket that could not be
 * read is reported rather than silently missing: this report is what a
 * production closes its books against, and a total that quietly omits payroll
 * is worse than one that says payroll is missing.
 */
data class BibleReport(
    val accounts: List<BibleAccount> = emptyList(),
    val grandTotal: Double = 0.0,
    /** The currency the amounts were converted into. */
    val currencyCode: String = "",
    val generatedAtMillis: Long? = null,
    val errors: Map<String, String> = emptyMap(),
) {
    val transactionCount: Int get() = accounts.sumOf { it.transactions.size }
}

/** What the report is asked for. */
data class BibleQuery(
    val periodStartMillis: Long,
    val periodEndMillis: Long,
    /** Blank means every source. */
    val source: String = "",
    /** Blank means every account type. */
    val accountType: String = "",
    val currency: String = "",
    /**
     * Commitments as well as postings.
     *
     * Only sent when false — the server's own default is true, and the web
     * omits the key rather than restating it.
     */
    val includeOpenPurchaseOrders: Boolean = true,
)
