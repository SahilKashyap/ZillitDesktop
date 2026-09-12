package com.zillit.desktop.feature.taxfiling.domain

import kotlin.math.abs
import kotlin.math.floor

/**
 * One filing regime the service can handle.
 *
 * Read from the server's catalogue rather than hard-coded: today it answers
 * with one entry, HMRC's Making Tax Digital VAT for the United Kingdom, and
 * the shape exists so a second country does not need a client release.
 */
data class TaxFiling(
    val country: String = "",
    val countryName: String = "",
    /** The country's flag as an emoji, as the catalogue sends it. */
    val flag: String = "",
    val regime: String = "",
    val key: String = "",
    val title: String = "",
    val subtitle: String = "",
    val description: String = "",
)

/** A company's enrolment with the tax authority. */
data class TaxRegistration(
    val id: String = "",
    val companyId: String = "",
    /** The company's name, resolved from the companies list; the id until then. */
    val companyName: String = "",
    /** The VAT registration number. */
    val registrationNumber: String = "",
    val filingFrequency: String = "",
    val status: String = ACTIVE,
    /**
     * Whether the tax authority has been authorised.
     *
     * Nothing can be filed until it has: HMRC's consent is an OAuth grant the
     * accountant gives in a browser, and it expires.
     */
    val connected: Boolean = false,
) {
    val isActive: Boolean get() = status.equals(ACTIVE, ignoreCase = true)

    /** This registration wearing the name its company has in [companies]. */
    fun named(companies: List<TaxCompany>): TaxRegistration {
        val name = companies.firstOrNull { it.id == companyId }?.name?.takeIf { it.isNotBlank() }
        return copy(companyName = name ?: companyName.ifBlank { companyId })
    }

    companion object {
        const val ACTIVE = "active"
    }
}

/** A company that can hold a registration. */
data class TaxCompany(
    val id: String = "",
    val name: String = "",
    /** ISO country, upper-case — which filings belong to "your countries". */
    val countryCode: String = "",
) {
    /** `Zillit Films Ltd (GB)`, as the register dialog lists it. */
    val pickerLabel: String
        get() = if (countryCode.isBlank()) name else "$name ($countryCode)"
}

/**
 * A new registration, as the register dialog collects it.
 *
 * [countryCode] and [regime] come from the filing it is made under, never from
 * the dialog: a VAT number registered under the wrong regime is enrolled with
 * an authority that has never heard of it.
 */
data class RegistrationRequest(
    val companyId: String,
    val registrationNumber: String,
    val filingFrequency: String,
    /** `yyyy-mm-dd`, or null when the accountant left it blank. */
    val registrationDate: String? = null,
    val countryCode: String = SupportedFiling.MtdVat.country,
    val regime: String = SupportedFiling.MtdVat.regime,
)

/**
 * A period the authority expects a return for.
 *
 * [periodKey] is HMRC's own identifier for the period and is what every later
 * call names — never the dates, which are display.
 */
data class FilingObligation(
    val periodKey: String = "",
    val start: String = "",
    val end: String = "",
    val due: String = "",
    val received: String = "",
    /** HMRC's own: `O` open, `F` fulfilled. Blank on rows that predate it. */
    val status: String = "",
) {
    /**
     * Still owed. A fulfilled period has been filed and cannot be filed again.
     *
     * HMRC's `status` decides, not the received date: a period can be
     * fulfilled by a return HMRC has not yet stamped, and reading the date
     * instead would offer to file it a second time.
     */
    val isOpen: Boolean
        get() = if (status.isNotBlank()) status.equals(OPEN, ignoreCase = true) else received.isBlank()

    /** `2026-01-01 → 2026-03-31`. */
    val range: String get() = "$start → $end"

    /** `2026-01-01 → 2026-03-31 · 18A1`, the period picker's line. */
    val pickerLabel: String get() = "$range · $periodKey"

    companion object {
        const val OPEN = "O"
        const val FULFILLED = "F"
    }
}

/**
 * How a box reads the ledger, inferred from the box rather than stored.
 *
 * Credit-natural boxes are what the production owes or has sold; debit-natural
 * boxes are what it has spent or reclaimed. There is no direction field on a
 * config row — the box decides.
 */
const val CREDIT_DIRECTION = "Σ (credit − debit)"
const val DEBIT_DIRECTION = "Σ (debit − credit)"

/**
 * One of HMRC's nine VAT boxes.
 *
 * Boxes 3 and 5 are [computed] and never mapped or typed into: box 3 is boxes
 * 1 and 2 added, and box 5 is the difference between 3 and 4. Filing a figure
 * that disagrees with that arithmetic is a return HMRC rejects.
 *
 * [wholePounds] boxes are the value totals, 6 to 9, which HMRC takes to the
 * pound rather than the penny. [label] and [description] are the web's
 * `VAT_BOXES` wording; [short] is the summary rail's.
 */
// The numbers are HMRC's box numbers, which are the boxes' names: "box 6" is
// what the guidance, the accountant and the return itself all call it, and a
// constant for each would be nine constants named after their own values.
@Suppress("MagicNumber")
enum class VatBox(
    val number: Int,
    val field: String,
    val label: String,
    val short: String,
    val direction: String,
    val description: String,
    val computed: Boolean = false,
    val wholePounds: Boolean = false,
) {
    DueOnSales(
        1, "vatDueSales", "VAT due on sales", "VAT on sales", CREDIT_DIRECTION,
        "VAT you charged on sales and other outputs during the period.",
    ),
    DueOnAcquisitions(
        2, "vatDueAcquisitions", "VAT due on acquisitions", "VAT on acquisitions", CREDIT_DIRECTION,
        "VAT due on goods and services acquired from EU member states (acquisition tax).",
    ),
    TotalDue(
        3, "totalVatDue", "Total VAT due", "Total VAT due", "Box 1 + Box 2",
        "Boxes 1 and 2 added.", computed = true,
    ),
    ReclaimedOnPurchases(
        4, "vatReclaimedCurrPeriod", "VAT reclaimed on purchases", "VAT on purchases", DEBIT_DIRECTION,
        "VAT you can reclaim on purchases and other inputs (including acquisitions).",
    ),
    NetDue(
        5, "netVatDue", "Net VAT to pay to HMRC", "Net VAT", "Box 3 − Box 4",
        "What is owed, or reclaimed when box 4 is the larger.", computed = true,
    ),
    SalesExVat(
        6, "totalValueSalesExVAT", "Total value of sales ex-VAT", "Total sales", CREDIT_DIRECTION,
        "Total value of sales and all other outputs excluding VAT (whole pounds).", wholePounds = true,
    ),
    PurchasesExVat(
        7, "totalValuePurchasesExVAT", "Total value of purchases ex-VAT", "Total purchases", DEBIT_DIRECTION,
        "Total value of purchases and all other inputs excluding VAT (whole pounds).", wholePounds = true,
    ),
    GoodsSuppliedExVat(
        8, "totalValueGoodsSuppliedExVAT", "Goods supplied to EU ex-VAT", "Goods to EU", CREDIT_DIRECTION,
        "Total net value of goods supplied to EU member states, excluding VAT (whole pounds).",
        wholePounds = true,
    ),
    AcquisitionsExVat(
        9, "totalAcquisitionsExVAT", "Acquisitions from EU ex-VAT", "Acquisitions from EU", DEBIT_DIRECTION,
        "Total net value of goods acquired from EU member states, excluding VAT (whole pounds).",
        wholePounds = true,
    ),
    ;

    /** The config slot this box's mapping is stored under. */
    val slot: String get() = "box$number"

    /** How many decimals the box is shown and filed with. */
    val decimals: Int get() = if (wholePounds) 0 else 2

    companion object {
        /** The seven boxes an accountant maps. Three and five are arithmetic. */
        val mappable: List<VatBox> get() = entries.filterNot { it.computed }

        fun byField(field: String): VatBox? = entries.firstOrNull { it.field == field }

        /** `box6`, and the bare `6` older rows were saved with. */
        fun bySlot(slot: String): VatBox? {
            val trimmed = slot.trim()
            return entries.firstOrNull { it.slot == trimmed || it.number.toString() == trimmed }
        }
    }
}

/**
 * Which ledger entries feed one box.
 *
 * Every clause narrows the same selection: a code, a tracking layer, a tag and
 * a date window all have to match. [markZero] files the box as zero without
 * reading the ledger at all, which is how a production with no EU trade files
 * boxes 8 and 9.
 *
 * The window is held as the `yyyy-mm-dd` text the accountant sees and becomes
 * UTC-midnight epoch milliseconds only on the wire — the web's `ymdToMs` —
 * so a half-typed date stays on screen rather than being lost in conversion.
 */
data class BoxMapping(
    val box: String = "",
    val codes: List<String> = emptyList(),
    /** Tracking set id to the code chosen within it. */
    val layers: Map<String, String> = emptyMap(),
    val tags: List<String> = emptyList(),
    val fromDate: String = "",
    val toDate: String = "",
    val markZero: Boolean = false,
) {
    /**
     * Whether this box would select anything at all.
     *
     * A date window alone does not count, as it does not on the web: a window
     * over no codes, layers or tags selects nothing, and the row is dropped.
     */
    val isConfigured: Boolean
        get() = markZero || codes.isNotEmpty() || layers.isNotEmpty() || tags.isNotEmpty()

    /** A date that was typed but is not a date, which would be dropped on save. */
    val hasInvalidDate: Boolean
        get() = listOf(fromDate, toDate).any { it.isNotBlank() && TaxDates.toMillis(it) == null }

    val hasDateRange: Boolean get() = fromDate.isNotBlank() || toDate.isNotBlank()
}

/**
 * A return, as the draft builds it and the submission sends it.
 *
 * Held by box rather than by field name so the arithmetic can be checked
 * before it is filed.
 */
data class VatReturn(
    val values: Map<VatBox, Double> = emptyMap(),
    /**
     * The period this was built for, as the server named it.
     *
     * Carried on the return rather than read from the screen at submit time:
     * the draft belongs to one period, and if the two ever disagree the
     * draft's is the one the figures were computed for.
     */
    val periodKey: String = "",
) {
    operator fun get(box: VatBox): Double? = values[box]

    /**
     * The return with boxes 3 and 5 filled in from the rest.
     *
     * Recomputed here rather than trusted from the draft: they are the two
     * figures HMRC checks against the others, and a draft that disagrees is a
     * rejected submission at best.
     *
     * Box 5 is the **absolute** difference — HMRC takes the amount and infers
     * the direction — so a reclaim is filed as a positive figure. Both are
     * rounded to the penny as the web's `round2` does: a sum of two doubles is
     * `1000.5000000001` often enough, and HMRC refuses a third decimal.
     */
    fun computed(): VatReturn {
        val three = roundPence(one + two)
        return copy(
            values = values + mapOf(VatBox.TotalDue to three, VatBox.NetDue to abs(roundPence(three - four))),
        )
    }

    /** Box 3 − box 4 with its sign kept: positive is owed, negative reclaimed. */
    val netSigned: Double get() = roundPence(roundPence(one + two) - four)

    /** Whether box 5 is owed to HMRC rather than reclaimed from it. */
    val isPayable: Boolean get() = netSigned >= 0.0

    private val one get() = values[VatBox.DueOnSales] ?: 0.0
    private val two get() = values[VatBox.DueOnAcquisitions] ?: 0.0
    private val four get() = values[VatBox.ReclaimedOnPurchases] ?: 0.0
}

/** JavaScript's `Math.round(n * 100) / 100` — half up, not half to even. */
fun roundPence(value: Double): Double = floor(value * PENCE + HALF) / PENCE

private const val PENCE = 100.0
private const val HALF = 0.5

/**
 * What the ledger had to work with, when the answer is all zeroes.
 *
 * A return of nine zeroes is either a quiet quarter or a mapping that selects
 * nothing, and those look identical on screen. The counts tell them apart.
 */
data class DraftDiagnostics(
    val rowsInScope: Int? = null,
    /** Ledger rows with no company against them, so no registration claims them. */
    val nullCompanyRows: Int? = null,
)

/** A built draft and what the server saw while building it. */
data class VatDraft(
    val vatReturn: VatReturn = VatReturn(),
    val diagnostics: DraftDiagnostics = DraftDiagnostics(),
) {
    /** Whether every box a person maps came back empty. */
    val isAllZero: Boolean
        get() = VatBox.mappable.all { (vatReturn[it] ?: 0.0) == 0.0 }

    /**
     * The web's explanation of an all-zero draft, word for word.
     *
     * A box with a date range scopes by entry date and ignores the obligation
     * period, so the in-scope count is informational rather than the verdict.
     */
    val allZeroExplanation: String
        get() = buildString {
            append("All boxes are £0. Ledger rows for this company in the obligation period: ")
            append(diagnostics.rowsInScope?.toString() ?: "—")
            append(".")
            val orphans = diagnostics.nullCompanyRows ?: 0
            if (orphans > 0) append(" ($orphans project GL row(s) have no company assigned.)")
            append(" A box with no date range uses the obligation period;")
            append(" set a per-box date range to include other dates.")
        }
}

/**
 * A return already filed, with HMRC's receipt.
 *
 * Only for periods filed through Zillit. A period fulfilled elsewhere is
 * fulfilled all the same, and there is simply no receipt to show for it.
 */
data class FiledReturn(
    val periodKey: String = "",
    val values: Map<VatBox, Double> = emptyMap(),
    /** HMRC's form bundle number, which is the reference on any query. */
    val reference: String = "",
    val processedAt: String = "",
) {
    /** Whether the stored payload survived — without it there are no figures to show. */
    val hasFigures: Boolean get() = values.isNotEmpty()
}

/** One ledger line behind a box, for the export. */
data class LedgerLine(
    val box: String = "",
    val accountCode: String = "",
    val debit: Double = 0.0,
    val credit: Double = 0.0,
    val periodYear: Int? = null,
    val periodMonth: Int? = null,
    val tracking: Map<String, String> = emptyMap(),
    val memo: String = "",
) {
    /** `2026-04`, or blank when the row carries no period. */
    val periodLabel: String
        get() {
            val year = periodYear ?: return ""
            val month = periodMonth ?: return year.toString()
            return "$year-${month.toString().padStart(2, '0')}"
        }

    /** `dept:CAM loc:LON` — the web's tracking column. */
    val trackingLabel: String
        get() = tracking.entries.joinToString(" ") { "${it.key}:${it.value}" }
}
