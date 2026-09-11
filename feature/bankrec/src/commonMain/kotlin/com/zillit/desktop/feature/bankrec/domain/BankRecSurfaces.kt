package com.zillit.desktop.feature.bankrec.domain

/** What kind of thing an exception is, and what an accountant does about it. */
enum class ExceptionType(val wire: String, val label: String, val guidance: String) {
    BankCharge("bank_charge", "Bank charge", "Monthly service charge. No matching entry in Zillit."),
    VatReturn("vat_return", "VAT return", "HMRC payment. No tax control entry in Zillit for this period."),
    CardSettlement(
        "card_settlement",
        "Card settlement",
        "Corporate card settlement. Post to the card control account.",
    ),
    Payroll("payroll", "Payroll", "Payroll payment. No matching payroll journal in Zillit."),
    FxPayment(
        "fx_payment",
        "FX payment",
        "Foreign currency payment. Check the FX variance and post accordingly.",
    ),
    Interest("interest", "Interest", "Interest paid or received. Post to the interest nominal."),
    PettyCash("petty_cash", "Petty cash", "Petty cash replenishment. Post to the petty cash control."),
    Insurance(
        "insurance",
        "Insurance",
        "Insurance premium. Post to prepayments or the insurance nominal.",
    ),
    Unknown("unknown", "Unknown", "No matching entry found. Investigate and post manually."),
    ;

    companion object {
        fun from(wire: String?): ExceptionType =
            entries.firstOrNull { it.wire == wire?.lowercase() } ?: Unknown
    }
}

/** How far an exception has been taken. */
enum class ExceptionStatus(val wire: String, val label: String) {
    Open("open", "Open"),
    UnderInvestigation("under_investigation", "Under investigation"),
    Investigated("investigated", "Investigated"),
    Resolved("resolved", "Resolved"),
    Ignored("ignored", "Ignored"),
    ;

    /** Whether an accountant still has something to do with it. */
    val isOutstanding: Boolean get() = this == Open || this == UnderInvestigation

    companion object {
        fun from(wire: String?): ExceptionStatus =
            entries.firstOrNull { it.wire == wire?.lowercase() } ?: Open
    }
}

/** A bank line the reconciliation could not place. */
data class BankException(
    val id: String = "",
    val periodId: String = "",
    val type: ExceptionType = ExceptionType.Unknown,
    val status: ExceptionStatus = ExceptionStatus.Open,
    val title: String = "",
    val notes: String = "",
    val transaction: BankTransaction? = null,
) {
    val amount: Double get() = transaction?.amount ?: 0.0

    val currency: String? get() = transaction?.currency
}

/**
 * What is posted when an exception is cleared by hand.
 *
 * The same shape as the workspace's quick entry: the two forms were written
 * separately on the web, and one gained a picker the other never did.
 */
data class QuickAddForm(
    val nominalCode: String = "",
    val costCentre: String = "",
    val description: String = "",
    val taxTypeId: String = "",
    val taxRate: Double? = null,
) {
    val problem: String?
        get() = when {
            nominalCode.isBlank() -> "Choose the account code to post to."
            description.isBlank() -> "Say what this posting is for."
            else -> null
        }
}

/** The cost centres the quick forms offer. */
enum class CostCentre(val code: String, val label: String) {
    Production("PROD", "PROD — Production"),
    PostProduction("POST", "POST — Post production"),
    Administration("ADMIN", "ADMIN — Administration"),
    Operations("OPS", "OPS — Operations"),
}

/**
 * A payment the fraud engine wants a person to look at.
 *
 * [riskScore] runs 0 to 100; the web calls 75 and over high risk.
 */
data class FraudAlert(
    val id: String = "",
    val periodId: String = "",
    val alertType: FraudType? = null,
    val status: FraudStatus = FraudStatus.Active,
    val title: String = "",
    val description: String = "",
    val riskScore: Int = 0,
    val createdAtMillis: Long? = null,
    val signals: List<String> = emptyList(),
    val vendorName: String = "",
    val vendorSortCode: String = "",
    val vendorAccountNumber: String = "",
    val transaction: BankTransaction? = null,
) {
    val isHighRisk: Boolean get() = riskScore >= HIGH_RISK

    val riskLabel: String get() = if (isHighRisk) "High risk" else "Medium risk"

    /** Whether the two actions are still available. */
    val isOpen: Boolean get() = status == FraudStatus.Active

    private companion object {
        const val HIGH_RISK = 75
    }
}

/** One line of the fraud audit trail. */
data class FraudAuditEntry(
    val id: String = "",
    val action: String = "",
    val detail: String = "",
    val userName: String = "",
    val atMillis: Long? = null,
    val alertId: String = "",
)

/** Whether an FX variance has reached the ledger. */
enum class FxStatus(val wire: String, val label: String) {
    Unposted("unposted", "Unposted"),
    Posted("posted", "Posted"),
    ;

    companion object {
        fun from(wire: String?): FxStatus =
            entries.firstOrNull { it.wire == wire?.lowercase() } ?: Unposted
    }
}

/**
 * The difference between what a foreign invoice was budgeted at and what the
 * bank actually took.
 *
 * A positive [variance] is a gain, a negative one a loss.
 */
data class FxVariance(
    val id: String = "",
    val periodId: String = "",
    val invoiceCurrency: String = "",
    val foreignAmount: Double = 0.0,
    val budgetAmount: Double = 0.0,
    val paidAmount: Double = 0.0,
    val variance: Double = 0.0,
    val budgetRate: Double = 0.0,
    val bankRate: Double = 0.0,
    val status: FxStatus = FxStatus.Unposted,
    val createdAtMillis: Long? = null,
    val vendorName: String = "",
    val reference: String = "",
) {
    val isGain: Boolean get() = variance > 0

    val isPosted: Boolean get() = status == FxStatus.Posted
}

/** What a variance is posted as. */
data class FxPosting(
    val nominalCode: String = DEFAULT_NOMINAL,
    val costCentre: String = "",
) {
    companion object {
        /** The web's own default nominal for an FX gain or loss. */
        const val DEFAULT_NOMINAL = "7850"
    }
}

/**
 * Which automatic checks run, and at what thresholds.
 *
 * Two halves saved separately, as on the web: the matching rules and the fraud
 * detections are different sections with their own buttons, and one section's
 * save must not carry the other's unsaved edits.
 */
data class RulesSettings(
    val autoMatch: Map<String, Boolean> = DEFAULT_AUTO_MATCH,
    val fraud: Map<String, FraudRule> = DEFAULT_FRAUD,
) {
    companion object {
        val DEFAULT_AUTO_MATCH: Map<String, Boolean> =
            MatchRule.entries.associate { it.key to true }

        val DEFAULT_FRAUD: Map<String, FraudRule> = FraudDetection.entries.associate {
            it.key to FraudRule(enabled = true, amount = it.defaultAmount)
        }
    }
}

/** One fraud check: on or off, and its threshold when it has one. */
data class FraudRule(val enabled: Boolean = true, val amount: Double? = null)

/** The matching rules the engine runs, in the order the web lists them. */
enum class MatchRule(val key: String, val label: String, val description: String, val confidence: String) {
    AmountAndReference(
        "amt_ref_match",
        "Exact amount and reference",
        "Matches lines where the amount and the payment reference are identical.",
        "100%",
    ),
    AmountAndVendor(
        "amt_fuzzy_vendor_match",
        "Amount and supplier name",
        "Matches on the amount with a fuzzy supplier-name similarity above the threshold.",
        "85–94%",
    ),
}

/**
 * The fraud checks, their thresholds and how severe each is.
 *
 * [defaultAmount] is null for a check with no threshold — the web renders
 * those without an amount box at all, and sending one would store a threshold
 * nothing reads.
 */
enum class FraudDetection(
    val key: String,
    val label: String,
    val description: String,
    val severity: String,
    val defaultAmount: Double? = null,
) {
    BankChange(
        "bank_change_detection",
        "Mandate or bank detail change",
        "Flag any payment to a sort code that is not on the supplier's verified record.",
        "Critical",
    ),
    SplitPayment(
        "split_pay_threshold",
        "Split payment threshold",
        "Flag several same-day payments to one supplier when the combined total is over the threshold.",
        "Threshold",
        defaultAmount = 5_000.0,
    ),
    UnregisteredPayee(
        "unregistered_payee",
        "New or unregistered payees",
        "Flag payments to bank accounts that are not in the supplier register.",
        "Medium risk",
    ),
    RoundLargePayments(
        "round_large_payments",
        "Round-number large payments",
        "Flag exact round-number payments over a set amount with no invoice reference.",
        "Minimum amount",
        defaultAmount = 10_000.0,
    ),
    DuplicateDetection(
        "duplicate_detection",
        "Duplicate detection",
        "Flags likely duplicate payments inside a rolling seven-day window.",
        "Always on",
    ),
    ;

    val hasThreshold: Boolean get() = defaultAmount != null
}
