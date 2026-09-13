package com.zillit.desktop.feature.dealmemo.domain.preview

import com.zillit.desktop.feature.dealmemo.domain.DealDoc
import com.zillit.desktop.feature.dealmemo.domain.DealLabels
import com.zillit.desktop.feature.dealmemo.domain.DocRead
import com.zillit.desktop.feature.dealmemo.domain.rates.Js
import com.zillit.desktop.feature.dealmemo.domain.rates.RateFormat
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** `F` on the start form: an empty value prints a grey dash. */
data class StartField(
    val label: String,
    val value: String?,
    val mono: Boolean = false,
    val wide: Boolean = false,
) {
    val empty: Boolean get() = value.isNullOrEmpty() || value == MemoFormat.DASH
}

/** A titled grid of fields, two or three across. */
data class StartSection(val title: String?, val fields: List<StartField>, val columns: Int = 2)

/** A navy-headed rule table; the last column may be right-aligned. */
data class StartTable(
    val title: String,
    val columns: List<MemoColumn>,
    val widths: List<Int?>,
    val rows: List<List<String>>,
    val emptyText: String,
)

data class StartFormView(
    val subtitle: String,
    val status: String,
    val crewName: String,
    val roleLine: String,
    val sections: List<StartSection>,
    val tables: List<StartTable>,
)

/**
 * The read-only Crew Start Form (`CrewStartForm.jsx`) as data. It mirrors the
 * memo's rates and tables in its own document language — and keeps the web's
 * differences: no tax code, the postcode printed twice, the fringes read at
 * row level, allowances with a per-basis suffix.
 */
object StartForm {

    private const val DASH = MemoFormat.DASH

    private val BASIS_SUFFIX = mapOf(
        "day" to "day", "days" to "day", "week" to "week", "weeks" to "week",
        "hour" to "hour", "night" to "night", "event" to "event", "mile" to "mile", "meal" to "meal",
    )

    fun build(deal: DealDoc, context: MemoContext, companyName: String): StartFormView {
        val names = CrewNames.of(deal, context)
        val person = context.labels.person(deal.userId)
        val crewName = person?.fullName?.takeIf { it.isNotEmpty() } ?: deal.fullLegalName ?: deal.crewName
            ?: "Crew member"
        val status = deal.rawStatus.orEmpty().replace('_', ' ')
            .split(' ').joinToString(" ") { word -> word.replaceFirstChar { it.uppercase() } }
            .ifEmpty { DASH }
        val flatFee = MemoTerms.isFlatFee(deal)
        val symbol = RateFormat.currencySymbol(deal.contractCurrency ?: "GBP")
        return StartFormView(
            subtitle = listOf(context.projectName, companyName).filter { it.isNotEmpty() }.joinToString(" · ")
                .ifEmpty { DASH },
            status = status,
            crewName = crewName,
            roleLine = listOf(names.role, names.department).filter { it.isNotEmpty() }.joinToString(" · ")
                .ifEmpty { DASH },
            sections = listOfNotNull(
                StartSection(
                    null,
                    listOf(
                        StartField("Status", status),
                        StartField("Start Form For", crewName),
                        StartField("Created", MemoFormat.dateTime(deal.createdAt, context.zone)),
                    ),
                ),
                employment(deal, names, context),
                personal(deal, crewName, context),
                payroll(deal, context),
                bank(deal),
                rates(deal, symbol, flatFee),
                holidayPay(deal),
            ),
            tables = tables(deal, symbol, flatFee),
        )
    }

    private fun employment(deal: DealDoc, names: CrewNames, context: MemoContext) = StartSection(
        "Employment Details",
        listOf(
            StartField("Job Title", names.role),
            StartField("Department", names.department),
            StartField("Start Date", MemoFormat.date(deal.startDate, context.zone)),
            StartField("Schedule D No.", DASH),
            StartField("Vehicle Model", DASH),
            StartField("Vehicle Reg No.", DASH, mono = true),
            StartField("Ltd. Company", DASH),
            StartField("Company No.", DASH),
            StartField("VAT Reg. No.", DASH),
        ),
    )

    private fun personal(deal: DealDoc, crewName: String, context: MemoContext): StartSection {
        val cd = deal.crew
        val parts = (deal.fullLegalName ?: deal.crewName ?: crewName).trim().split(Regex("\\s+")).filter {
            it.isNotEmpty()
        }
        val first = if (parts.size <= 1) parts.firstOrNull().orEmpty() else parts.dropLast(1).joinToString(" ")
        val surname = if (parts.size <= 1) "" else parts.last()
        val home = DealAddress.of(cd?.get("home_address"))
        val emergency = DocRead.obj(cd, "emergency_details")
        return StartSection(
            "Personal Information",
            listOf(
                StartField("Surname", surname),
                StartField("First Name", first),
                StartField("Address", home.format(), wide = true),
                StartField("Post Code", home.postalCode, mono = true),
                StartField("Country of Residence", home.country),
                StartField("Gender", DocRead.text(cd, "gender")?.let { DealLabels.formatLabel(it, context.translate) }),
                StartField("D.O.B", MemoFormat.date(DocRead.number(cd, "dob")?.toLong(), context.zone)),
                StartField("Insurance / NI No.", DocRead.text(cd, "insurance_no"), mono = true),
                StartField("Citizenship", DASH),
                StartField("Passport No.", DASH, mono = true),
                StartField("Tel. / Mobile No.", DocRead.text(cd, "mobile"), mono = true),
                StartField("E-Mail Address", DocRead.text(cd, "email"), wide = true),
                StartField(
                    "Next of Kin",
                    DocRead.text(cd, "emergency_contact_name") ?: DocRead.text(emergency, "name")
                        ?: DocRead.text(cd, "emergency_contact"),
                ),
                StartField(
                    "Next of Kin Tel.",
                    DocRead.text(cd, "emergency_contact_number") ?: DocRead.text(emergency, "phone_number"),
                    mono = true,
                ),
            ),
        )
    }

    private fun payroll(deal: DealDoc, context: MemoContext): StartSection? {
        if (!UkPayroll.appliesTo(deal)) return null
        val rows = UkPayroll.memoRows(
            DocRead.obj(deal.crew, UkPayroll.KEY),
            money = { value ->
                value?.takeUnless { it is JsonNull || (it as? JsonPrimitive)?.content == "" }
                    ?.let { money(it, RateFormat.currencySymbol("GBP")) }.orEmpty()
            },
            date = { MemoFormat.date(it, context.zone) },
        )
        return StartSection(UkPayroll.LABEL, rows.map { StartField(it.label, it.value, mono = it.mono) })
    }

    private fun bank(deal: DealDoc): StartSection {
        val bank = DocRead.obj(deal.json, "bank")
        return StartSection(
            "Bank Details",
            listOf(
                StartField("Bank Name", DocRead.text(bank, "name")),
                StartField("Branch", DASH),
                StartField("Sort Code", MemoFormat.sortCode(DocRead.text(bank, "sort_code")), mono = true),
                StartField("Account No.", DocRead.text(bank, "account_number"), mono = true),
                StartField("Payee Name", DocRead.text(bank, "account_holder_name")),
                StartField("Additional Ref.", DASH),
                StartField("Swift", DocRead.text(bank, "swift_code"), mono = true),
                StartField("IBAN", DocRead.text(bank, "iban_number"), mono = true),
                StartField("ABA / Routing", DASH, mono = true),
            ),
        )
    }

    @Suppress("CyclomaticComplexMethod")
    private fun rates(deal: DealDoc, symbol: String, flatFee: Boolean): StartSection {
        val rates = deal.rates
        fun money(value: JsonElement?) = value.takeIf { Js.truthy(it) }?.let { money(it, symbol) } ?: DASH
        fun hours(block: JsonObject?) = block?.get("hrs").takeIf { Js.truthy(it) }?.let { "${Js.text(it)}h" } ?: DASH
        val daily = DocRead.obj(rates, "daily")
        val weekly = DocRead.obj(rates, "weekly")
        val fields = when {
            !flatFee -> listOf(
                StartField("Day Rate", money(daily?.get("rate")), mono = true),
                StartField("Day Hours", hours(daily), mono = true),
                StartField("Weekly Rate", money(weekly?.get("rate")), mono = true),
                StartField("Weekly Hours", hours(weekly), mono = true),
            )
            deal.dealType == "picture" ->
                listOf(StartField("Picture Fee", money(rates?.get("picture_fee")), mono = true))
            else -> {
                val covers = rates?.get("buyout_covers")
                listOf(
                    StartField("Buy-Out Rate (weekly)", money(rates?.get("buyout_rate")), mono = true),
                    StartField(
                        "Covers",
                        when {
                            (covers as? JsonPrimitive)?.content == "unlimited" -> "Unlimited"
                            Js.truthy(covers) -> "${Js.text(covers)} hrs/day"
                            else -> DASH
                        },
                    ),
                )
            }
        }
        return StartSection(
            "Rates",
            fields + listOf(
                StartField("Contract Currency", deal.contractCurrency ?: "GBP", mono = true),
                StartField("Deal Type", deal.dealType?.let { DealLabels.formatLabel(it) } ?: DASH),
            ),
        )
    }

    private fun holidayPay(deal: DealDoc): StartSection {
        val treatment = HolidayPay.treatmentOf(deal)
        val inclusive = treatment == HolidayPay.INCLUSIVE
        val percent = HolidayPay.of(deal)?.percent?.takeIf { it != 0.0 && !it.isNaN() }
        return StartSection(
            "Holiday Pay",
            listOf(
                StartField(
                    "Treatment",
                    treatment?.let { if (inclusive) "Inclusive of rate" else "Exclusive of rate" } ?: DASH,
                ),
                StartField("Holiday Ent.", percent?.let { "${Js.number(it)}%" } ?: DASH, mono = true),
                StartField("Included in Rate", treatment?.let { if (inclusive) "Yes" else "No" } ?: DASH),
            ),
            columns = 3,
        )
    }

    private fun tables(deal: DealDoc, symbol: String, flatFee: Boolean): List<StartTable> = buildList {
        if (!flatFee) {
            listOf(
                Triple("overtimes", "Overtimes", "No overtime rows."),
                Triple("premiums", "Premiums", "No premiums."),
                Triple("penalties", "Penalties", "No penalties."),
                Triple("turnarounds", "Turnarounds", "No turnaround rules."),
            ).forEach { (key, title, empty) ->
                add(
                    StartTable(
                        title = title,
                        columns = listOf(
                            MemoColumn(if (title == "Overtimes") "Band" else "Rule"),
                            MemoColumn("Rate", mono = true),
                            MemoColumn("Nominal", mono = true),
                            MemoColumn("Pay Frequency", alignEnd = true),
                        ),
                        widths = listOf(null, null, NOMINAL_WIDTH, FREQUENCY_WIDTH),
                        rows = DocRead.objects(deal.json[key]).map { row ->
                            listOf(
                                present(DocRead.obj(row, "source"), "label") ?: present(row, "row_id") ?: DASH,
                                MemoFormat.unionComp(row, symbol),
                                DocRead.text(row, "nominal_code") ?: DASH,
                                MemoFormat.payFrequency(row),
                            )
                        },
                        emptyText = empty,
                    ),
                )
            }
        }
        listOf("allowances" to "Allowances", "rentals" to "Rentals").forEach { (key, title) ->
            add(entitlementTable(deal, key, title, symbol))
        }
        add(fringeTable(deal, symbol))
    }

    private fun entitlementTable(deal: DealDoc, key: String, title: String, symbol: String) = StartTable(
        title = title,
        columns = listOf(
            MemoColumn("Name"),
            MemoColumn("Type"),
            MemoColumn("Amount", mono = true),
            MemoColumn("Pay Frequency"),
            MemoColumn("Currency", alignEnd = true),
        ),
        widths = listOf(null, null, null, null, CURRENCY_WIDTH),
        rows = DocRead.objects(deal.json[key]).map { row ->
            listOf(
                DocRead.text(row, "name") ?: DocRead.text(row, "id") ?: DASH,
                DocRead.text(row, "rate_type")?.let { DealLabels.formatLabel(it) } ?: DASH,
                moneyPerBasis(row, symbol),
                DocRead.text(row, "basis")?.let { DealLabels.formatLabel(it) } ?: DASH,
                DocRead.text(row, "currency") ?: deal.contractCurrency ?: "GBP",
            )
        },
        emptyText = "No ${title.lowercase()}.",
    )

    /** Row-level `percentage` / `flat` / `basis` — the memo reads `source.*`, so the two can differ. */
    private fun fringeTable(deal: DealDoc, symbol: String): StartTable {
        val inclusive = HolidayPay.treatmentOf(deal) == HolidayPay.INCLUSIVE
        return StartTable(
            title = "Fringes / Employer Costs",
            columns = listOf(
                MemoColumn("Line"),
                MemoColumn("%", mono = true),
                MemoColumn("Flat", mono = true),
                MemoColumn("Basis"),
                MemoColumn("Pay Freq.", alignEnd = true),
            ),
            widths = listOf(null, PERCENT_WIDTH, FLAT_WIDTH, null, FRINGE_FREQUENCY_WIDTH),
            rows = DocRead.objects(deal.json["fringes"])
                .filterNot { inclusive && HolidayPay.isHolidayPayRow(it) }
                .map { row ->
                    val source = DocRead.obj(row, "source")
                    listOf(
                        present(source, "name") ?: present(source, "label") ?: present(row, "row_id") ?: DASH,
                        present(row, "percentage")?.let { "$it%" } ?: DASH,
                        row["flat"]?.takeUnless { it is JsonNull }?.let { money(it, symbol) } ?: DASH,
                        DocRead.text(row, "basis")?.let { DealLabels.formatLabel(it) } ?: DASH,
                        MemoFormat.payFrequency(row),
                    )
                },
            emptyText = "No fringes.",
        )
    }

    private fun moneyPerBasis(row: JsonObject, symbol: String): String {
        val amount = row["amount"]?.takeUnless { it is JsonNull } ?: row["rate"]?.takeUnless { it is JsonNull }
        if (amount == null || (amount as? JsonPrimitive)?.content == "") return DASH
        val basis = DocRead.text(row, "basis")
        val suffix = basis?.let { BASIS_SUFFIX[it] ?: it }.orEmpty()
        return "${money(amount, symbol)}${if (suffix.isNotEmpty()) " / $suffix" else ""}"
    }

    /** `${symbol}${groupAmount(v, 2)}` — no dash of its own; callers decide what empty prints. */
    private fun money(value: JsonElement, symbol: String): String =
        "$symbol${RateFormat.groupAmount(Js.toNumber(value) ?: Double.NaN)}"

    private fun present(json: JsonObject?, key: String): String? =
        json?.get(key)?.takeUnless { it is JsonNull }?.let(Js::text)

    private const val NOMINAL_WIDTH = 110
    private const val FREQUENCY_WIDTH = 120
    private const val CURRENCY_WIDTH = 80
    private const val PERCENT_WIDTH = 70
    private const val FLAT_WIDTH = 90
    private const val FRINGE_FREQUENCY_WIDTH = 110
}
