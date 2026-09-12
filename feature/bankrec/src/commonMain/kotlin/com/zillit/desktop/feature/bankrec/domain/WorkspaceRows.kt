package com.zillit.desktop.feature.bankrec.domain

import kotlin.math.abs

/** What a bar under a workspace row is about. */
enum class SuggestionKind { Match, Info, Fraud, Accepted }

/**
 * A bar under a bank line — the engine's suggestion, a fraud warning, or the
 * "not in Zillit" prompt — with the actions it offers.
 *
 * [viewsInvoice] marks a match suggestion that sits under a fraud bar: there the
 * first action scrolls to the invoice and the second accepts, where a plain
 * suggestion's first action accepts.
 */
data class SuggestionBar(
    val kind: SuggestionKind,
    val text: String,
    val action: String? = null,
    val secondAction: String? = null,
    val viewsInvoice: Boolean = false,
    val invoiceId: String? = null,
)

/** A small uppercase marker beside a line's name. */
enum class RowTagKind { Fraud, Actioned, Currency }

data class RowTag(val kind: RowTagKind, val label: String)

/** A bank line as the workspace draws it. */
data class BankRow(
    val txn: BankTransaction,
    val status: TxnStatus,
    val title: String,
    val reference: String,
    /** The currency the amount is really in — see [BankTransaction.amountCurrency]. */
    val amountCurrency: String?,
    val tags: List<RowTag>,
    val suggestion: SuggestionBar?,
    val matchSuggestion: SuggestionBar?,
) {
    val id: String get() = txn.id
    val amount: Double get() = txn.amount
    val fx: FxDetail? get() = txn.fx
}

/** A ledger entry as the workspace draws it. */
data class LedgerRow(
    val entry: LedgerEntry,
    val title: String,
    val reference: String,
    val amount: Double?,
) {
    val id: String get() = entry.id
    val status: TxnStatus get() = if (entry.isMatched) TxnStatus.Matched else TxnStatus.Unmatched
}

/** How many of each, as the filter pills and the summary bar count them. */
data class WorkspaceCounts(
    val all: Int = 0,
    val matched: Int = 0,
    /** Bank lines *and* ledger entries still unmatched — the web counts both sides. */
    val unmatched: Int = 0,
    val suggested: Int = 0,
    val fraud: Int = 0,
    val fx: Int = 0,
)

/** A panel's total, compact for the header and exact for its tooltip. */
data class PanelTotal(val value: String, val exact: String, val total: Double)

/** Which rows the workspace shows. */
enum class WorkspaceFilter(val slug: String) {
    All("all"),
    Unmatched("unmatched"),
    Suggested("suggested"),
    Fraud("fraud"),
    Fx("fx"),
    ;

    /** Applied to both panels by status, as the web does: the ledger only ever answers All or Unmatched. */
    fun accepts(status: TxnStatus): Boolean = when (this) {
        All -> true
        Unmatched -> status == TxnStatus.Unmatched
        Suggested -> status == TxnStatus.Suggested
        Fraud -> status == TxnStatus.FraudFlag
        Fx -> status == TxnStatus.Fx
    }
}

/**
 * The workspace's reading of the wire — the web's `mapBankTxn` and
 * `mapInvoiceTxn`, as pure functions so the words on every bar are testable.
 */
object WorkspaceRows {

    private val PAYMENT_LABELS = mapOf(
        "bacs" to "BACS",
        "faster_payment" to "Faster Payment",
        "chaps" to "CHAPS",
        "swift" to "SWIFT",
        "direct_debit" to "Direct Debit",
        "transfer" to "Transfer",
        "card" to "Card",
    )

    /** An invoice's pay method as its code — `faster_payment` is the legacy spelling of `faster`. */
    fun payMethodCode(value: String): String {
        val code = value.trim().lowercase().ifBlank { "bacs" }
        return if (code == "faster_payment") "faster" else code
    }

    fun bankRow(txn: BankTransaction, ledger: List<LedgerEntry>, accountCurrency: String?): BankRow {
        val invoices = ledger.associateBy { it.entityId }
        val amountCurrency = txn.amountCurrency(accountCurrency)
        val vendor = txn.displayName
        val payLabel = PAYMENT_LABELS[txn.paymentMethod.lowercase()] ?: txn.paymentMethod
        val reference = listOf(payLabel, txn.statementReference).filter { it.isNotBlank() }.joinToString(" · ")

        val (suggestion, matchSuggestion) = suggestions(txn, invoices, vendor, amountCurrency)
        return BankRow(
            txn = txn,
            status = txn.effectiveStatus,
            title = vendor.ifBlank { BankRecFormat.DASH },
            reference = reference.ifBlank { BankRecFormat.DASH },
            amountCurrency = amountCurrency,
            tags = tags(txn),
            suggestion = suggestion,
            matchSuggestion = matchSuggestion,
        )
    }

    private fun tags(txn: BankTransaction): List<RowTag> = buildList {
        if (txn.fraudType != null) {
            add(RowTag(RowTagKind.Fraud, "FRAUD"))
            txn.fraudStatus?.takeIf { it.isActioned }?.let { add(RowTag(RowTagKind.Actioned, it.wire.uppercase())) }
        }
        txn.fx?.let { add(RowTag(RowTagKind.Currency, it.currency.uppercase())) }
    }

    @Suppress("CyclomaticComplexMethod") // The web's own five-way branch, kept in its order.
    private fun suggestions(
        txn: BankTransaction,
        invoices: Map<String, LedgerEntry>,
        vendor: String,
        currency: String?,
    ): Pair<SuggestionBar?, SuggestionBar?> {
        val actioned = txn.isFraudActioned
        val flagged = txn.fraudType != null || txn.status == TxnStatus.FraudFlag
        val warning = fraudWarning(txn, vendor, currency)
        val match = txn.matchConfidence?.takeIf { txn.matchedInvoiceIds.isNotEmpty() }?.let { confidence ->
            val detail = invoiceDetail(txn, invoices, vendor, currency)
            SuggestionBar(
                kind = SuggestionKind.Match,
                text = "Suggested: $detail · $confidence% confidence",
                action = "Accept",
                secondAction = "Manual Match",
                viewsInvoice = true,
                invoiceId = txn.matchedInvoiceIds.first(),
            )
        }
        return when {
            flagged && !actioned -> SuggestionBar(SuggestionKind.Fraud, warning, action = "Review") to match

            txn.fraudType != null && txn.status == TxnStatus.Matched -> SuggestionBar(
                SuggestionKind.Accepted,
                "Fraud ${actionedWord(txn).lowercase()} & matched to $vendor. Recorded in audit log.",
            ) to null

            txn.fraudType != null -> SuggestionBar(SuggestionKind.Fraud, "${actionedWord(txn)}: $warning") to match

            txn.status == TxnStatus.Suggested && txn.matchConfidence != null -> SuggestionBar(
                kind = SuggestionKind.Match,
                text = "AI Match: ${invoiceDetail(txn, invoices, vendor, currency)} · " +
                    "${txn.matchConfidence}% confidence",
                action = "Accept",
                secondAction = "Manual Match",
            ) to null

            txn.status == TxnStatus.Unmatched || (txn.fx != null && txn.status != TxnStatus.Matched) -> SuggestionBar(
                kind = SuggestionKind.Info,
                text = "Not in Zillit — add or match to ledger?",
                action = "Quick Add",
                secondAction = "Manual Match",
            ) to null

            else -> null to null
        }
    }

    private fun actionedWord(txn: BankTransaction): String = when (txn.fraudStatus) {
        FraudStatus.Dismissed -> "Dismissed"
        FraudStatus.Escalated -> "Escalated"
        FraudStatus.Accepted -> "Accepted"
        else -> "Reviewed"
    }

    /** The engine's reason, in the web's words. */
    fun fraudWarning(txn: BankTransaction, vendor: String, currency: String?): String = when (txn.fraudType) {
        FraudType.MandateFraud ->
            "Bank account changed from known sort code — supplier not notified. Possible mandate fraud."

        FraudType.SplitPayment ->
            "⚠ Multiple payments to same vendor same day — possible split payment to avoid approval threshold."

        FraudType.UnregisteredPayee ->
            "Payment to unregistered payee — $vendor not found in approved supplier register."

        FraudType.RoundLargePayment ->
            "Round-number large payment of ${BankRecFormat.plainMoney(txn.amount, currency)} " +
                "with no matched invoice reference."

        FraudType.DuplicatePayment ->
            "Possible duplicate — same amount paid to $vendor within 7-day window."

        null -> "Flagged for fraud review."
    }

    /** `Panavision INV-88 · £1,200.00 · BACS`, or the vendor and amount when no invoice is known. */
    private fun invoiceDetail(
        txn: BankTransaction,
        invoices: Map<String, LedgerEntry>,
        vendor: String,
        currency: String?,
    ): String {
        val fallback = "${vendor.ifBlank { "Unknown" }} · ${BankRecFormat.plainMoney(txn.amount, currency)}"
        val found = txn.matchedInvoiceIds.mapNotNull { invoices[it] }
        return when (found.size) {
            0 -> fallback
            1 -> {
                val inv = found.first()
                val name = inv.supplierName.ifBlank { inv.vendorName }.ifBlank { vendor }
                val gross = inv.grossAmount?.takeIf { it != 0.0 } ?: abs(txn.amount)
                val amount = BankRecFormat.plainMoney(gross, inv.currency ?: currency)
                val method = inv.payMethod.takeIf { it.isNotBlank() }?.let { payMethodCode(it).uppercase() }.orEmpty()
                "$name ${inv.invoiceNumber} · $amount · $method"
            }

            else -> "${found.first().supplierName.ifBlank { vendor }} · " +
                found.joinToString(", ") { it.invoiceNumber.ifBlank { it.entityId } }
        }
    }

    /**
     * A ledger entry's reference line: an invoice by pay method, number and
     * department, anything else by its ledger description.
     */
    fun ledgerRow(entry: LedgerEntry, departments: Map<String, String>): LedgerRow {
        val reference = when (entry.kind) {
            LedgerEntryKind.Invoice -> listOfNotNull(
                entry.payMethod.takeIf { it.isNotBlank() }?.let { payMethodCode(it).uppercase() },
                entry.invoiceNumber.takeIf { it.isNotBlank() },
                departments[entry.departmentId]?.takeIf { it.isNotBlank() },
            ).joinToString(" · ")

            LedgerEntryKind.Transaction, LedgerEntryKind.FxPosting -> entry.ledgerDescription.joinToString(" · ")
            LedgerEntryKind.Other -> ""
        }
        return LedgerRow(entry = entry, title = entry.displayName, reference = reference, amount = entry.amount)
    }

    fun counts(bank: List<BankRow>, ledger: List<LedgerRow>): WorkspaceCounts = WorkspaceCounts(
        all = bank.size,
        matched = bank.count { it.status == TxnStatus.Matched },
        unmatched = bank.count { it.status == TxnStatus.Unmatched } + ledger.count { it.status == TxnStatus.Unmatched },
        suggested = bank.count { it.status == TxnStatus.Suggested },
        fraud = bank.count { it.status == TxnStatus.FraudFlag },
        fx = bank.count { it.status == TxnStatus.Fx },
    )

    /**
     * The rows linked to [selectedId], across the divider.
     *
     * A bank line lights the ledger entries whose `tr_ids` hold it, else the
     * ones its own matched-invoice ids name; a ledger entry lights the lines in
     * its `tr_ids`, else the lines whose matched ids name it.
     */
    fun linked(selectedId: String?, bank: List<BankRow>, ledger: List<LedgerRow>): Set<String> {
        val id = selectedId ?: return emptySet()
        val row = bank.firstOrNull { it.id == id }
        if (row != null) return ledgerLinkedTo(row, ledger)
        return ledger.firstOrNull { it.id == id }?.let { bankLinkedTo(it, bank) }.orEmpty()
    }

    private fun ledgerLinkedTo(row: BankRow, ledger: List<LedgerRow>): Set<String> {
        val byTrIds = ledger.filter { row.id in it.entry.transactionIds }.map { it.id }.toSet()
        return byTrIds.ifEmpty {
            row.txn.matchedInvoiceIds
                .mapNotNull { invoice -> ledger.firstOrNull { it.entry.entityId == invoice }?.id }
                .toSet()
        }
    }

    private fun bankLinkedTo(entry: LedgerRow, bank: List<BankRow>): Set<String> =
        entry.entry.transactionIds.toSet().ifEmpty {
            bank.filter { entry.entry.entityId in it.txn.matchedInvoiceIds }.map { it.id }.toSet()
        }

    /**
     * A panel's total.
     *
     * One currency is totalled in its own; several are converted to the
     * project's and totalled there, with a rate-less currency added at face value
     * and the exact figure saying so. [fallbackCode] is what a row with no
     * currency is taken to be in.
     */
    fun panelTotal(amounts: List<Pair<Double, String?>>, fallbackCode: String, rates: ProjectRates): PanelTotal {
        val codes = amounts.map { (_, code) -> (code ?: fallbackCode).uppercase() }.toSet()
        return if (codes.size <= 1) {
            val code = codes.firstOrNull() ?: fallbackCode
            val total = amounts.sumOf { it.first }
            PanelTotal(BankRecFormat.compactMoney(total, code), BankRecFormat.money(total, code), total)
        } else {
            val (total, unrated) = rates.sumInDefault(amounts.map { (amount, code) ->
                amount to (code ?: fallbackCode)
            })
            val exact = BankRecFormat.money(total, rates.defaultCode) +
                if (unrated) " · some amounts had no exchange rate (added at face value)" else ""
            PanelTotal(BankRecFormat.compactMoney(total, rates.defaultCode), exact, total)
        }
    }

    /** Which quick-entry form a line opens on — the exception's own type, else its wording. */
    fun quickEntryTypeFor(txn: BankTransaction): QuickEntryType =
        QuickEntryType.fromException(txn.exceptionType)
            ?: QuickEntryType.classify(txn.description.ifBlank { txn.displayName })
}

/** The quick-entry tiles, in the web's order. */
enum class QuickEntryType(val key: String, val label: String) {
    BankCharge("bankcharge", "Bank Charges"),
    TaxReturn("vat", "Tax Return"),
    CardSettle("corp", "Card Settle"),
    Payroll("payroll", "Payroll"),
    FxPayment("fx", "FX Payment"),
    FraudFlag("fraud", "Fraud Flag"),
    Interest("interest", "Interest"),
    Other("other", "Other"),
    ;

    companion object {
        private val BY_EXCEPTION = mapOf(
            "bank_charge" to BankCharge,
            "vat_return" to TaxReturn,
            "card_settlement" to CardSettle,
            "payroll" to Payroll,
            "interest" to Interest,
            // An FX payment quick-adds as Other; the FX form is for posting a variance.
            "fx_payment" to Other,
        )

        private val KEYWORDS = listOf(
            BankCharge to listOf("BANK CHARGE", "SERVICE CHARGE", "ACCOUNT MAINTENANCE", "BANK FEE", "BNKCHRG"),
            TaxReturn to listOf("HMRC", "VAT", "TAX PAYMENT", "VAT RETURN"),
            CardSettle to listOf("BARCLAYCARD", "CARD SETTLEMENT", "CORPORATE CARD", "CREDIT CARD"),
            Payroll to listOf("PAYROLL", "CREW PAYROLL", "SALARY", "WAGES"),
            Interest to listOf("INTEREST", "INT PAYMENT", "INT RECEIVED"),
        )

        fun fromException(wire: String): QuickEntryType? = BY_EXCEPTION[wire.lowercase()]

        fun classify(description: String): QuickEntryType {
            val upper = description.uppercase()
            return KEYWORDS.firstOrNull { (_, words) -> words.any { it in upper } }?.first ?: Other
        }
    }
}
