package com.zillit.desktop.feature.bankrec.domain

/** Whether a reconciliation period is still being worked on. */
enum class PeriodStatus(val wire: String, val label: String) {
    InProgress("in_progress", "In progress"),
    Complete("complete", "Complete"),
    ;

    companion object {
        fun from(wire: String?): PeriodStatus =
            entries.firstOrNull { it.wire == wire?.lowercase() } ?: InProgress
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
 * March reconciliation comes to be labelled February.
 */
data class BankPeriod(
    val id: String = "",
    val periodMillis: Long? = null,
    val bankAccountId: String = "",
    val status: PeriodStatus = PeriodStatus.InProgress,
    val totalTxns: Int = 0,
    val matchedCount: Int = 0,
    val suggestedCount: Int = 0,
    val unmatchedCount: Int = 0,
    val fraudCount: Int = 0,
    /** The statement's closing balance, in the *bank account's* currency. */
    val closingBank: Double = 0.0,
    /** The ledger's closing balance, in the project's own currency. */
    val closingZillit: Double = 0.0,
    /** The server's difference. Only trustworthy when the two agree — see [differenceIn]. */
    val difference: Double = 0.0,
    val signedBy: String = "",
    val signedAtMillis: Long? = null,
    val createdAtMillis: Long? = null,
    val note: String = "",
) {
    val isOpen: Boolean get() = status != PeriodStatus.Complete

    val matchedFraction: Float
        get() = if (totalTxns <= 0) 0f else matchedCount.toFloat() / totalTxns

    val matchedPercent: Int get() = (matchedFraction * PERCENT).toInt()

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
    FraudFlag("fraud_flag", "Fraud flag"),
    Fx("fx", "FX"),
    ;

    companion object {
        fun from(wire: String?): TxnStatus =
            entries.firstOrNull { it.wire == wire?.lowercase() } ?: Unmatched
    }
}

/** What the fraud engine thinks a line is. */
enum class FraudType(val wire: String, val label: String, val explanation: String) {
    MandateFraud(
        "mandate_fraud",
        "Mandate / bank detail change",
        "Bank account changed from the known sort code, and the supplier was not notified.",
    ),
    SplitPayment(
        "split_payment",
        "Split payment",
        "Several payments to the same vendor on the same day — possibly split to stay under an approval threshold.",
    ),
    UnregisteredPayee(
        "unregistered_payee",
        "Unregistered payee",
        "Payment to a payee that is not in the approved supplier register.",
    ),
    RoundLargePayment(
        "round_large_payment",
        "Round-number large payment",
        "A round-number payment of this size with no matched invoice reference.",
    ),
    DuplicatePayment(
        "duplicate_payment",
        "Duplicate payment",
        "The same amount paid to this vendor inside a seven-day window.",
    ),
    ;

    companion object {
        fun from(wire: String?): FraudType? = entries.firstOrNull { it.wire == wire?.lowercase() }
    }
}

/** What has been decided about a flagged line. */
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
    val currency: String = "",
    val budgetRate: Double = 0.0,
    val bankRate: Double = 0.0,
    val gain: Double = 0.0,
    val varianceId: String = "",
    val varianceStatus: String = "unposted",
) {
    val isPosted: Boolean get() = varianceStatus.equals("posted", ignoreCase = true)
}

/**
 * One line off the bank statement.
 *
 * [debit] and [credit] arrive separately, as the statement writes them; the
 * signed [amount] is derived. Money out is negative.
 */
data class BankTransaction(
    val id: String = "",
    val periodId: String = "",
    val transactionDateMillis: Long? = null,
    val description: String = "",
    val vendorName: String = "",
    val reference: String = "",
    val paymentMethod: String = "",
    val debit: Double = 0.0,
    val credit: Double = 0.0,
    /** The line's own currency. Null falls back to the account's. */
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

    /**
     * Whether an unresolved fraud flag still sits on this line.
     *
     * A flag that has been accepted, dismissed or escalated has been dealt
     * with; the line goes back to reading as whatever it otherwise is.
     */
    val hasActiveFraud: Boolean
        get() = fraudType != null && fraudStatus?.isActioned != true

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
            hasActiveFraud -> TxnStatus.FraudFlag
            fx != null -> TxnStatus.Fx
            else -> status
        }
}

/** What the ledger side of the workspace is showing. */
enum class LedgerEntryKind(val wire: String) {
    Invoice("invoice"),
    Transaction("transaction"),
    FxPosting("fx"),
    ;

    companion object {
        fun from(wire: String?): LedgerEntryKind =
            entries.firstOrNull { it.wire == wire?.lowercase() } ?: Invoice
    }
}

/** One entry on Zillit's side of the reconciliation. */
data class LedgerEntry(
    val id: String = "",
    /** The underlying record's id, which is not the ledger row's. */
    val entityId: String = "",
    val kind: LedgerEntryKind = LedgerEntryKind.Invoice,
    val title: String = "",
    val reference: String = "",
    val amount: Double? = null,
    val currency: String? = null,
    val dateMillis: Long? = null,
    /** The bank lines this entry is reconciled against. Empty means unmatched. */
    val transactionIds: List<String> = emptyList(),
) {
    val isMatched: Boolean get() = transactionIds.isNotEmpty()
}

/** A production bank account, as the reconciliation needs it. */
data class BankAccountRef(
    val id: String = "",
    val name: String = "",
    val currencyCode: String = "",
    val accountNumber: String = "",
    val sortCode: String = "",
)

/** Everything the workspace shows for one period. */
data class WorkspaceData(
    val transactions: List<BankTransaction> = emptyList(),
    val ledger: List<LedgerEntry> = emptyList(),
)
