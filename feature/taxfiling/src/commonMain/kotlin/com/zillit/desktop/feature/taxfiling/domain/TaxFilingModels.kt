package com.zillit.desktop.feature.taxfiling.domain

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
    /** The company's name, resolved from the companies list. */
    val companyName: String = "",
    /** The VAT registration number. */
    val registrationNumber: String = "",
    val filingFrequency: String = "",
    val status: String = "active",
    /**
     * Whether the tax authority has been authorised.
     *
     * Nothing can be filed until it has: HMRC's consent is an OAuth grant the
     * accountant gives in a browser, and it expires.
     */
    val connected: Boolean = false,
)

/** A company that can hold a registration. */
data class TaxCompany(val id: String = "", val name: String = "")

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

    companion object {
        const val OPEN = "O"
        const val FULFILLED = "F"
    }
}

/**
 * One of HMRC's nine VAT boxes.
 *
 * Boxes 3 and 5 are [computed] and never mapped or typed into: box 3 is boxes
 * 1 and 2 added, and box 5 is the difference between 3 and 4. Filing a figure
 * that disagrees with that arithmetic is a return HMRC rejects.
 *
 * [wholePounds] boxes are the value totals, 6 to 9, which HMRC takes to the
 * pound rather than the penny.
 */
/**
 * How a box reads the ledger, inferred from the box rather than stored.
 *
 * Credit-natural boxes are what the production owes or has sold; debit-natural
 * boxes are what it has spent or reclaimed. There is no direction field on a
 * config row — the box decides.
 */
const val CREDIT_DIRECTION = "\u03a3 (credit \u2212 debit)"
const val DEBIT_DIRECTION = "\u03a3 (debit \u2212 credit)"

// The numbers are HMRC's box numbers, which are the boxes' names: "box 6" is
// what the guidance, the accountant and the return itself all call it, and a
// constant for each would be nine constants named after their own values.
@Suppress("MagicNumber")
enum class VatBox(
    val number: Int,
    val field: String,
    val label: String,
    val direction: String,
    val description: String,
    val computed: Boolean = false,
    val wholePounds: Boolean = false,
) {
    DueOnSales(
        1, "vatDueSales", "VAT due on sales", CREDIT_DIRECTION,
        "VAT you charged on sales and other outputs during the period.",
    ),
    DueOnAcquisitions(
        2, "vatDueAcquisitions", "VAT due on acquisitions", CREDIT_DIRECTION,
        "VAT due on goods and services acquired from EU member states.",
    ),
    TotalDue(
        3, "totalVatDue", "Total VAT due", "Box 1 + Box 2",
        "Boxes 1 and 2 added.", computed = true,
    ),
    ReclaimedOnPurchases(
        4, "vatReclaimedCurrPeriod", "VAT reclaimed on purchases", DEBIT_DIRECTION,
        "VAT you can reclaim on purchases and other inputs, acquisitions included.",
    ),
    NetDue(
        5, "netVatDue", "Net VAT to pay", "Box 3 − Box 4",
        "What is owed, or reclaimed when box 4 is the larger.", computed = true,
    ),
    SalesExVat(
        6, "totalValueSalesExVAT", "Total sales ex-VAT", CREDIT_DIRECTION,
        "Total value of sales and other outputs, excluding VAT.", wholePounds = true,
    ),
    PurchasesExVat(
        7, "totalValuePurchasesExVAT", "Total purchases ex-VAT", DEBIT_DIRECTION,
        "Total value of purchases and other inputs, excluding VAT.", wholePounds = true,
    ),
    GoodsSuppliedExVat(
        8, "totalValueGoodsSuppliedExVAT", "Goods supplied to the EU ex-VAT", CREDIT_DIRECTION,
        "Total value of goods supplied to EU member states, excluding VAT.", wholePounds = true,
    ),
    AcquisitionsExVat(
        9, "totalAcquisitionsExVAT", "Acquisitions from the EU ex-VAT", DEBIT_DIRECTION,
        "Total value of goods acquired from EU member states, excluding VAT.", wholePounds = true,
    ),
    ;

    /** The config slot this box's mapping is stored under. */
    val slot: String get() = "box$number"

    companion object {
        /** The seven boxes an accountant maps. Three and five are arithmetic. */
        val mappable: List<VatBox> get() = entries.filterNot { it.computed }

        fun byField(field: String): VatBox? = entries.firstOrNull { it.field == field }
    }
}

/**
 * Which ledger entries feed one box.
 *
 * Every clause narrows the same selection: a code, a tracking layer, a tag and
 * a date window all have to match. [markZero] files the box as zero without
 * reading the ledger at all, which is how a production with no EU trade files
 * boxes 8 and 9.
 */
data class BoxMapping(
    val box: String = "",
    val codes: List<String> = emptyList(),
    /** Tracking set id to the code chosen within it. */
    val layers: Map<String, String> = emptyMap(),
    val tags: List<String> = emptyList(),
    val fromMillis: Long? = null,
    val toMillis: Long? = null,
    val markZero: Boolean = false,
) {
    /** Whether this box would select anything at all. */
    val isConfigured: Boolean
        get() = markZero || codes.isNotEmpty() || layers.isNotEmpty() || tags.isNotEmpty()
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
     * the direction — so a reclaim is filed as a positive figure.
     */
    fun computed(): VatReturn {
        val one = values[VatBox.DueOnSales] ?: 0.0
        val two = values[VatBox.DueOnAcquisitions] ?: 0.0
        val four = values[VatBox.ReclaimedOnPurchases] ?: 0.0
        val three = one + two
        return copy(
            values = values +
                mapOf(VatBox.TotalDue to three, VatBox.NetDue to kotlin.math.abs(three - four)),
        )
    }

    /** Whether box 5 is owed to HMRC rather than reclaimed from it. */
    val isPayable: Boolean
        get() {
            val three = (values[VatBox.DueOnSales] ?: 0.0) + (values[VatBox.DueOnAcquisitions] ?: 0.0)
            return three >= (values[VatBox.ReclaimedOnPurchases] ?: 0.0)
        }
}

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
)

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
)
