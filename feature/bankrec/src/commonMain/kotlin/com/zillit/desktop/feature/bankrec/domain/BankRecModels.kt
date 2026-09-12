package com.zillit.desktop.feature.bankrec.domain

import kotlin.math.roundToInt

/** Whether a reconciliation period is still being worked on. */
enum class PeriodStatus(val wire: String, val label: String) {
    InProgress("in_progress", "In Progress"),
    Complete("complete", "Complete"),
    ;

    companion object {
        /**
         * `signed_off` is read as complete too: the web's share dialog tests
         * for that spelling, and a period read as open when it is not would be
         * offered for deletion — which the server refuses.
         */
        fun from(wire: String?): PeriodStatus = when (wire?.trim()?.lowercase()) {
            "complete", "completed", "signed_off" -> Complete
            else -> InProgress
        }
    }
}

/**
 * One month's reconciliation for one bank account.
 *
 * More than one can be open at once: a statement spanning several months opens
 * a period per month, and importing into an already signed-off month opens a
 * fresh one alongside it — so "the current period" is a choice, not a fact.
 *
 * [periodMillis] is a **first-of-month marker in UTC**. Rendering it with a
 * local calendar moves it a month either side of midnight, which is how a
 * March reconciliation comes to be labelled February. Older rows carry
 * [legacyMonth] and [legacyYear] instead, and the web still reads them.
 */
data class BankPeriod(
    val id: String = "",
    val periodMillis: Long? = null,
    val legacyMonth: Int? = null,
    val legacyYear: Int? = null,
    val bankAccountId: String = "",
    val status: PeriodStatus = PeriodStatus.InProgress,
    val totalTxns: Int = 0,
    val matchedCount: Int = 0,
    val suggestedCount: Int = 0,
    val unmatchedCount: Int = 0,
    val fraudCount: Int = 0,
    /** The statement's opening balance, in the *bank account's* currency. */
    val openingBank: Double = 0.0,
    /** The statement's closing balance, in the *bank account's* currency. */
    val closingBank: Double = 0.0,
    /** The ledger's closing balance, in the project's own currency. */
    val closingZillit: Double = 0.0,
    /** The server's difference. Only trustworthy when the two agree — see [differenceIn]. */
    val difference: Double = 0.0,
    /** Net FX variance over the period, in the bank account's currency. */
    val fxVarianceTotal: Double = 0.0,
    val openingDateMillis: Long? = null,
    val closingDateMillis: Long? = null,
    val signedBy: String = "",
    /**
     * The signer, resolved by the server.
     *
     * Shipped on the period so a page with no crew directory — the public
     * portal — can still name them; preferred over a directory miss here.
     */
    val signedByName: String = "",
    val signedByDesignation: String = "",
    val signedAtMillis: Long? = null,
    val signOffNotes: String = "",
    val createdAtMillis: Long? = null,
    val projectName: String = "",
) {
    val isOpen: Boolean get() = status != PeriodStatus.Complete

    /**
     * Whether the period may be deleted.
     *
     * Only while it is open. Signing off marks invoices paid without recording
     * what they were before, so the server cannot reverse it and refuses the
     * delete — the web keeps such a period out of the selection entirely.
     */
    val isDeletable: Boolean get() = isOpen

    val matchedFraction: Float
        get() = if (totalTxns <= 0) 0f else matchedCount.toFloat() / totalTxns

    /** Rounded, as the web rounds it: 39 of 47 is 83%, not 82%. */
    val matchedPercent: Int
        get() = if (totalTxns <= 0) 0 else (matchedCount * PERCENT.toDouble() / totalTxns).roundToInt()

    /**
     * The difference, in the project's currency.
     *
     * Recomputed rather than read from [difference] when the account is in
     * another currency: the server subtracts an **unconverted** bank balance
     * from the ledger figure, which is only right when the two are already the
     * same currency. [bankInProjectCurrency] is the converted closing balance.
     */
    fun differenceIn(bankInProjectCurrency: Double?, converted: Boolean): Double =
        if (converted && bankInProjectCurrency != null) {
            bankInProjectCurrency - closingZillit
        } else {
            difference
        }

    private companion object {
        const val PERCENT = 100
    }
}

/** How a bank line stands against the ledger. */
enum class TxnStatus(val wire: String, val label: String) {
    Matched("matched", "Matched"),
    Suggested("suggested", "Suggested"),
    Unmatched("unmatched", "Unmatched"),
    FraudFlag("fraud_flag", "Fraud"),
    Fx("fx", "FX"),
    ;

    companion object {
        fun from(wire: String?): TxnStatus =
            entries.firstOrNull { it.wire == wire?.lowercase() } ?: Unmatched
    }
}

/** What the fraud engine thinks a line is. */
enum class FraudType(val wire: String, val label: String, val shortLabel: String) {
    MandateFraud("mandate_fraud", "Mandate / Bank Detail Change", "Mandate Fraud"),
    SplitPayment("split_payment", "Split Payment", "Split Payment"),
    UnregisteredPayee("unregistered_payee", "Unregistered Payee", "Unregistered Payee"),
    RoundLargePayment("round_large_payment", "Round-Number Large Payment", "Round-Number Payment"),
    DuplicatePayment("duplicate_payment", "Duplicate Payment", "Duplicate Payment"),
    ;

    companion object {
        fun from(wire: String?): FraudType? = entries.firstOrNull { it.wire == wire?.lowercase() }
    }
}

/** What has been decided about a flagged line or alert. */
enum class FraudStatus(val wire: String, val label: String) {
    Active("active", "Active"),
    Accepted("accepted", "Accepted"),
    Dismissed("dismissed", "Dismissed"),
    Escalated("escalated", "Escalated"),
    ;

    val isActioned: Boolean get() = this != Active

    companion object {
        fun from(wire: String?): FraudStatus? = entries.firstOrNull { it.wire == wire?.lowercase() }
    }
}

/** The foreign side of a payment, when a line carries one. */
data class FxDetail(
    val foreignAmount: Double = 0.0,
    /** The *foreign* code — never the currency the line's own amount is in. */
    val currency: String = "",
    val budgetRate: Double = 0.0,
    val bankRate: Double = 0.0,
    /** A home-leg figure, in the statement's currency. Positive is a gain. */
    val gain: Double = 0.0,
    val varianceId: String = "",
    val varianceStatus: String = "unposted",
) {
    val isPosted: Boolean get() = varianceStatus.equals("posted", ignoreCase = true)

    val isGain: Boolean get() = gain > 0
}

/**
 * One line off the bank statement.
 *
 * [debit] and [credit] arrive separately, as the statement writes them; the
 * signed [amount] is derived. Money out is negative.
 *
 * [currency] is the service's column as it stands — and on an FX line that
 * column holds the **foreign** code, while debit and credit stay in the
 * account's own currency. [amountCurrency] resolves which code the amount is
 * really in; symbolising the amount with [currency] prints a sterling figure
 * under a euro sign.
 */
data class BankTransaction(
    val id: String = "",
    val periodId: String = "",
    val transactionDateMillis: Long? = null,
    val description: String = "",
    val vendorName: String = "",
    val reference: String = "",
    val trReference: String = "",
    val paymentMethod: String = "",
    val debit: Double = 0.0,
    val credit: Double = 0.0,
    val currency: String? = null,
    val status: TxnStatus = TxnStatus.Unmatched,
    val fraudType: FraudType? = null,
    val fraudStatus: FraudStatus? = null,
    val fraudScore: Int? = null,
    val matchConfidence: Int? = null,
    val matchedInvoiceIds: List<String> = emptyList(),
    val fx: FxDetail? = null,
    val exceptionId: String = "",
    val exceptionType: String = "",
    val exceptionTitle: String = "",
    val exceptionDescription: String = "",
) {
    val amount: Double get() = if (credit > 0) credit else -debit

    val isMoneyOut: Boolean get() = amount < 0

    /** The payee, or the statement's own wording when it names none. */
    val displayName: String get() = vendorName.ifBlank { description }

    /** The bank's reference for the line: its transfer reference, else the plain one. */
    val statementReference: String get() = trReference.ifBlank { reference }

    /**
     * Whether a flag has been dealt with — accepted, dismissed or escalated.
     * A line whose flag has been actioned goes back to reading as whatever it
     * otherwise is.
     */
    val isFraudActioned: Boolean get() = fraudStatus?.isActioned == true

    /** Whether an unresolved fraud flag still sits on this line. */
    val hasActiveFraud: Boolean
        get() = (fraudType != null || status == TxnStatus.FraudFlag) && !isFraudActioned

    /**
     * What the line reads as on screen.
     *
     * Matched wins over everything: a line reconciled to the ledger is
     * reconciled whatever else was said about it. An unactioned fraud flag
     * comes next, then a foreign payment, then the stored status.
     *
     * Note that FX is decided by *carrying a foreign amount*, never by
     * "currency is not the project's" — that reading marks every line on a
     * euro account as a foreign payment.
     */
    val effectiveStatus: TxnStatus
        get() = when {
            status == TxnStatus.Matched -> TxnStatus.Matched
            fraudType != null && !isFraudActioned -> TxnStatus.FraudFlag
            fx != null -> TxnStatus.Fx
            else -> status
        }

    /** The code [amount] is denominated in — see the class note. */
    fun amountCurrency(accountCurrency: String?): String? =
        if (fx != null) accountCurrency else currency ?: accountCurrency
}

/** What the ledger side of the workspace is showing. */
enum class LedgerEntryKind(val wire: String) {
    Invoice("invoice"),
    Transaction("transaction"),
    FxPosting("fx"),
    Other("other"),
    ;

    companion object {
        fun from(wire: String?): LedgerEntryKind {
            val text = wire?.trim()?.lowercase()
            if (text.isNullOrEmpty()) return Invoice
            return entries.firstOrNull { it.wire == text } ?: Other
        }
    }
}

/**
 * One entry on Zillit's side of the reconciliation.
 *
 * The raw parts are kept rather than one pre-joined line, because the line
 * depends on the kind — an invoice reads by pay method, number and department,
 * a quick entry by its ledger description — and the department is a name only
 * the host can resolve.
 */
data class LedgerEntry(
    val id: String = "",
    /** The underlying record's id, which is not the ledger row's. */
    val entityId: String = "",
    val kind: LedgerEntryKind = LedgerEntryKind.Invoice,
    val title: String = "",
    val vendorName: String = "",
    val supplierName: String = "",
    val invoiceNumber: String = "",
    val payMethod: String = "",
    val departmentId: String = "",
    val ledgerDescription: List<String> = emptyList(),
    val grossAmount: Double? = null,
    val debit: Double? = null,
    val credit: Double? = null,
    val currency: String? = null,
    val invoiceDateMillis: Long? = null,
    val dateMillis: Long? = null,
    val ledgerStatus: String = "",
    /** The bank lines this entry is reconciled against. Empty means unmatched. */
    val transactionIds: List<String> = emptyList(),
) {
    val isMatched: Boolean get() = transactionIds.isNotEmpty()

    val isPaid: Boolean get() = ledgerStatus.equals("paid", ignoreCase = true)

    /** The web's reading of the row's name, per kind. */
    val displayName: String
        get() = when (kind) {
            LedgerEntryKind.Invoice ->
                vendorName.ifBlank { supplierName }.ifBlank { title }.ifBlank { "Unknown Supplier" }

            LedgerEntryKind.Transaction -> title.ifBlank { "Quick Add" }
            LedgerEntryKind.FxPosting -> title.ifBlank { "FX Variance" }
            LedgerEntryKind.Other -> title.ifBlank { supplierName }.ifBlank { "Unknown" }
        }

    /**
     * The signed amount — money out negative.
     *
     * An invoice is money out by its gross; a quick entry or an FX posting by
     * its debit, else it is money in by its credit. Null when the row carries
     * no figure at all, which is drawn as a dash rather than as zero.
     */
    val amount: Double?
        get() = when (kind) {
            LedgerEntryKind.Invoice, LedgerEntryKind.Other ->
                grossAmount?.takeIf { it != 0.0 }?.let { -it }

            LedgerEntryKind.Transaction, LedgerEntryKind.FxPosting ->
                debit?.takeIf { it != 0.0 }?.let { -it } ?: credit?.takeIf { it != 0.0 }
        }

    /** The date the row is filed under: an invoice's own date, else the ledger's. */
    val displayDateMillis: Long?
        get() = if (kind == LedgerEntryKind.Invoice) invoiceDateMillis ?: dateMillis else dateMillis
}

/** A production bank account, as the reconciliation needs it. */
data class BankAccountRef(
    val id: String = "",
    val name: String = "",
    val bankName: String = "",
    val holderName: String = "",
    val currencyCode: String = "",
    val accountNumber: String = "",
    val sortCode: String = "",
    val iban: String = "",
    val nominalCode: String = "",
) {
    /** Never the id: an account with no name reads as its holder, or not at all. */
    val displayName: String get() = name.ifBlank { bankName }.ifBlank { holderName }

    /** `···1234` — enough to tell two accounts apart without printing the number. */
    val maskedNumber: String
        get() = if (accountNumber.isBlank()) "" else "···${accountNumber.takeLast(MASK_DIGITS)}"

    private companion object {
        const val MASK_DIGITS = 4
    }
}

/** Everything the workspace shows for one period. */
data class WorkspaceData(
    val transactions: List<BankTransaction> = emptyList(),
    val ledger: List<LedgerEntry> = emptyList(),
    /** The ledger's closing balance as of this read, when the server sends it. */
    val closingZillit: Double? = null,
)

/**
 * What an import produced.
 *
 * Counted by the server as it ingests, so the summary reflects this statement
 * rather than the whole period it landed in.
 */
data class ImportResult(
    val imported: Int = 0,
    val matched: Int = 0,
    val suggested: Int = 0,
    val unmatched: Int = 0,
    val fraud: Int = 0,
)
