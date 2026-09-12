package com.zillit.desktop.feature.bankrec.domain

/** What kind of thing an exception is, and what an accountant does about it. */
enum class ExceptionType(val wire: String, val label: String, val guidance: String) {
    BankCharge("bank_charge", "Bank Charges", "Monthly service charge. No matching entry in Zillit."),
    VatReturn("vat_return", "Tax Return", "HMRC payment. No Tax control entry in Zillit for this period."),
    CardSettlement(
        "card_settlement",
        "Card Settlement",
        "Corporate card settlement. Post to Card Control account.",
    ),
    Payroll("payroll", "Payroll", "Payroll payment. No matching payroll journal in Zillit."),
    FxPayment(
        "fx_payment",
        "FX Payment",
        "Foreign currency payment. Check FX variance and post accordingly.",
    ),
    Interest("interest", "Interest", "Interest payment or receipt. Post to interest nominal."),
    PettyCash("petty_cash", "Petty Cash", "Petty cash replenishment. Post to petty cash control."),
    Insurance("insurance", "Insurance", "Insurance premium. Post to prepayments or insurance nominal."),
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
    UnderInvestigation("under_investigation", "Under Investigation"),
    Investigated("investigated", "Investigated"),
    Resolved("resolved", "Resolved"),
    Ignored("ignored", "Ignored"),
    ;

    val isOpen: Boolean get() = this == Open

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
    val description: String = "",
    val notes: String = "",
    val transaction: BankTransaction? = null,
    /** The exception's own currency, for a row that carries one beside its transaction. */
    val currency: String? = null,
) {
    val debit: Double get() = transaction?.debit ?: 0.0

    val credit: Double get() = transaction?.credit ?: 0.0

    /** Money in. A credit is investigated; a debit is posted. */
    val isCredit: Boolean get() = credit > 0

    val amount: Double get() = transaction?.amount ?: 0.0

    val isFx: Boolean get() = type == ExceptionType.FxPayment
}

/**
 * What is posted when an exception is cleared by hand.
 *
 * **The keys are camelCase on the wire**, alone among this service's bodies
 * bar one: the web posts its form object as it stands (`nominal`,
 * `costCentre`, `vatType`, `vatRate`, `effectiveDate`, `invoiceNumber`), and a
 * snake_case body is one the service reads nothing from. The desktop sent
 * `nominal_code` / `cost_centre` / `tax_type_id` and no amount at all, which
 * is a posting with no account and no money.
 *
 * Dates are `YYYY-MM-DD`, the value of the web's date inputs. [amount] stays
 * text for the same reason the web keeps it text: it reaches the wire as typed.
 */
data class QuickAddForm(
    val date: String = "",
    val invoiceNumber: String = "",
    val description: String = "",
    val effectiveDate: String = "",
    val amount: String = "",
    val vatType: String = "",
    /** A percentage — 20, not 0.2. Null only while a custom rate is being typed. */
    val vatRate: Double? = 0.0,
    val nominal: String = "",
    val costCentre: String = "",
) {
    val amountValue: Double? get() = amount.trim().replace(",", "").toDoubleOrNull()
}

/** The cost centres the quick forms offer — the web's shared list, one for both forms. */
enum class CostCentre(val code: String, val label: String) {
    Production("PROD", "PROD — Production"),
    PostProduction("POST", "POST — Post Production"),
    Administration("ADMIN", "ADMIN — Administration"),
    Operations("OPS", "OPS — Operations"),
}

/**
 * One reason the engine gave for an alert.
 *
 * Stored either as a bare sentence or as `{title, detail}`; a bare sentence is
 * both. Reading only the string shape — as the first port did — dropped every
 * structured signal without a word.
 */
data class FraudSignal(val title: String, val detail: String)

/** An invoice the engine thinks a flagged payment was meant to settle. */
data class AlertInvoice(
    val id: String = "",
    val invoiceNumber: String = "",
    val supplierName: String = "",
    val grossAmount: Double? = null,
    val currency: String? = null,
    val payMethod: String = "",
)

/** The supplier on the register, with the bank details it is known by. */
data class FraudVendor(
    val name: String = "",
    val sortCode: String = "",
    val accountNumber: String = "",
)

/**
 * A payment the fraud engine wants a person to look at.
 *
 * [riskScore] runs 0 to 100; 75 and over is high risk.
 */
data class FraudAlert(
    val id: String = "",
    val periodId: String = "",
    val alertType: FraudType? = null,
    /** The wire's own type, kept for a type this client does not know. */
    val alertTypeWire: String = "",
    val status: FraudStatus = FraudStatus.Active,
    val title: String = "",
    val description: String = "",
    val riskScore: Int = 0,
    val createdAtMillis: Long? = null,
    val signals: List<FraudSignal> = emptyList(),
    val invoices: List<AlertInvoice> = emptyList(),
    val vendor: FraudVendor? = null,
    val transaction: BankTransaction? = null,
) {
    val isHighRisk: Boolean get() = riskScore >= HIGH_RISK

    val riskLabel: String get() = if (isHighRisk) "High Risk" else "Medium Risk"

    val isActive: Boolean get() = status == FraudStatus.Active

    /** "Investigated — No Issue" stays on offer until the alert is dismissed or accepted. */
    val canDismiss: Boolean get() = status != FraudStatus.Dismissed && status != FraudStatus.Accepted

    /** Escalation is only for an alert nobody has acted on yet. */
    val canEscalate: Boolean get() = status == FraudStatus.Active

    val hasSuggestion: Boolean get() = invoices.isNotEmpty() || vendor != null

    val typeLabel: String
        get() = alertType?.label ?: alertTypeWire.ifBlank { "Fraud alert" }

    private companion object {
        const val HIGH_RISK = 75
    }
}

/** What an audit entry records. The web's own labels, and its reading of each. */
enum class AuditAction(val wire: String, val label: String) {
    Created("created", "Fraud Detected"),
    Accepted("accepted", "Accepted & Matched"),
    Escalated("escalated", "Escalated to Finance"),
    Dismissed("dismissed", "Investigated — No Issue"),
    RemovedByRerun("removed_by_rerun", "Removed (Auto-Match Re-run)"),
    AutoMatchRerun("auto_match_rerun", "Auto-Match Re-run"),
    StatementImport("statement_import", "Statement Imported"),
    ImportResults("import_results", "Import Complete"),
    PeriodDeleted("period_deleted", "Period Deleted"),
    ;

    companion object {
        fun from(wire: String?): AuditAction? = entries.firstOrNull { it.wire == wire?.lowercase() }

        /** A label for any action, known or not — never the raw snake_case. */
        fun labelFor(wire: String): String = from(wire)?.label ?: wire.replace('_', ' ')
            .replaceFirstChar { it.uppercase() }
    }
}

/**
 * One line of the fraud audit trail.
 *
 * The trail is the module's own, not only the fraud engine's: imports, re-runs
 * and deleted periods are written into it too, which is why most of these
 * fields can be empty on any given row.
 */
data class FraudAuditEntry(
    val id: String = "",
    val action: String = "",
    val performedBy: String = "",
    val bankAccountId: String = "",
    val periodId: String = "",
    val createdAtMillis: Long? = null,
    val fraudType: String = "",
    val riskScore: Int? = null,
    /** The statement file, on an import row. */
    val file: String = "",
    val reason: String = "",
    val transaction: BankTransaction? = null,
    val periodMillis: Long? = null,
    val bankName: String = "",
    val bankSortCode: String = "",
    val bankAccountNumber: String = "",
) {
    /** The line's description, the file it came from, or why it was written. */
    val detail: String get() = transaction?.description?.ifBlank { null } ?: file.ifBlank { reason }

    /** What the line moved: its debit, else its credit. */
    val amount: Double? get() = transaction?.let { txn -> txn.debit.takeIf { it != 0.0 } ?: txn.credit }
}

/** The audit trail's filters, which also scope its export. */
data class AuditFilters(
    val bankAccountId: String = "",
    val periodId: String = "",
    val performedBy: String = "",
    val action: String = "",
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
 * A positive [variance] is a gain, a negative one a loss. [budgetAmount] and
 * [paidAmount] are the service's `budget_gbp` and `gbp_paid`, which are **not**
 * sterling — they are the project's default currency, whatever that is.
 */
data class FxVariance(
    val id: String = "",
    val periodId: String = "",
    val invoiceCurrency: String = "",
    val foreignAmount: Double = 0.0,
    val budgetAmount: Double = 0.0,
    val paidAmount: Double = 0.0,
    val variance: Double = 0.0,
    /** The rate stored on the row. Only an already-posted row is read by it — see [FxRates]. */
    val budgetRate: Double? = null,
    val bankRate: Double? = null,
    val status: FxStatus = FxStatus.Unposted,
    val createdAtMillis: Long? = null,
    val vendorName: String = "",
    val reference: String = "",
    val nominalCode: String = "",
    val costCentre: String = "",
) {
    val isGain: Boolean get() = variance > 0

    val isPosted: Boolean get() = status == FxStatus.Posted

    /** The supplier, else the currency, else a dash — the web's order. */
    val supplierLabel: String get() = vendorName.ifBlank { invoiceCurrency }.ifBlank { "—" }
}

/**
 * What a variance is posted as.
 *
 * The rates ride the body because the budget rate comes from Production Setup
 * and the service has no other way to know it. Null leaves a rate off the
 * wire — the workspace's quick entry posts without them, as the web's does.
 */
data class FxPosting(
    val nominalCode: String = DEFAULT_NOMINAL,
    val costCentre: String = "",
    val budgetRate: Double? = null,
    val bankRate: Double? = null,
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
        "Exact amount + reference match",
        "Matches transactions where amount and payment reference are identical.",
        "100%",
    ),
    AmountAndVendor(
        "amt_fuzzy_vendor_match",
        "Amount + supplier name (fuzzy)",
        "Matches on amount with fuzzy supplier name similarity above threshold.",
        "85–94%",
    ),
}

/** How loudly the web marks a check. */
enum class RuleSeverity { Critical, Warning }

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
    val tone: RuleSeverity,
    val defaultAmount: Double? = null,
) {
    BankChange(
        "bank_change_detection",
        "Mandate / bank detail change detection",
        "Flag any payment to a sort code not on the supplier’s verified record",
        "Critical",
        RuleSeverity.Critical,
    ),
    SplitPayment(
        "split_pay_threshold",
        "Split payment threshold",
        "Flag multiple same-day payments to same supplier if combined total exceeds threshold",
        "Threshold",
        RuleSeverity.Warning,
        defaultAmount = 5_000.0,
    ),
    UnregisteredPayee(
        "unregistered_payee",
        "New / unregistered payees",
        "Flag payments to bank accounts not in the Zillit supplier register",
        "Medium risk",
        RuleSeverity.Warning,
    ),
    RoundLargePayments(
        "round_large_payments",
        "Round-number large payments",
        "Flag exact round-number payments above a set amount with no invoice reference",
        "Min. amount",
        RuleSeverity.Warning,
        defaultAmount = 10_000.0,
    ),
    DuplicateDetection(
        "duplicate_detection",
        "Duplicate detection (7-day window)",
        "Flags potential duplicate payments within a rolling 7-day window",
        "Always on",
        RuleSeverity.Critical,
    ),
    ;

    val hasThreshold: Boolean get() = defaultAmount != null
}
