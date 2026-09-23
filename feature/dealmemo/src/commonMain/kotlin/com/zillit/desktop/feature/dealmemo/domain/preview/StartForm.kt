package com.zillit.desktop.feature.dealmemo.domain.preview

import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
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

    private val BASIS_SUFFIX: Map<String, String>
        get() = mapOf(
            "day" to str(S.day_label), "days" to str(S.day_label),
            "week" to str(S.week_label), "weeks" to str(S.week_label),
            "hour" to str(S.desktop_unit_hour), "night" to str(S.desktop_unit_night),
            "event" to str(S.desktop_unit_event), "mile" to str(S.desktop_unit_mile),
            "meal" to str(S.desktop_unit_meal),
        )

    fun build(deal: DealDoc, context: MemoContext, companyName: String): StartFormView {
        val names = CrewNames.of(deal, context)
        val person = context.labels.person(deal.userId)
        val crewName = person?.fullName?.takeIf { it.isNotEmpty() } ?: deal.fullLegalName ?: deal.crewName
            ?: str(S.crew_member)
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
                        StartField(str(S.status), status),
                        StartField(str(S.desktop_dm_start_form_for), crewName),
                        StartField(str(S.drive_created), MemoFormat.dateTime(deal.createdAt, context.zone)),
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
        str(S.desktop_dm_employment_details),
        listOf(
            StartField(str(S.job_title), names.role),
            StartField(str(S.department), names.department),
            StartField(str(S.start_date), MemoFormat.date(deal.startDate, context.zone)),
            StartField(str(S.desktop_dm_schedule_d_no), DASH),
            StartField(str(S.vehicle_model), DASH),
            StartField(str(S.desktop_dm_vehicle_reg_no), DASH, mono = true),
            StartField(str(S.desktop_dm_ltd_company), DASH),
            StartField(str(S.desktop_company_no), DASH),
            StartField(str(S.desktop_dm_vat_reg_no), DASH),
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
            str(S.desktop_dm_personal_information),
            listOf(
                StartField(str(S.desktop_surname), surname),
                StartField(str(S.first_name_label), first),
                StartField(str(S.address), home.format(), wide = true),
                StartField(str(S.desktop_post_code), home.postalCode, mono = true),
                StartField(str(S.country_of_residence), home.country),
                StartField(
                    str(S.gender),
                    DocRead.text(cd, "gender")?.let { DealLabels.formatLabel(it, context.translate) },
                ),
                StartField(
                    str(S.desktop_dm_dob_abbrev),
                    MemoFormat.date(DocRead.number(cd, "dob")?.toLong(), context.zone),
                ),
                StartField(str(S.dm_edit_personal_insurance), DocRead.text(cd, "insurance_no"), mono = true),
                StartField(str(S.desktop_citizenship), DASH),
                StartField(str(S.desktop_passport_no), DASH, mono = true),
                StartField(str(S.desktop_dm_tel_mobile_no), DocRead.text(cd, "mobile"), mono = true),
                StartField(str(S.desktop_dm_email_address), DocRead.text(cd, "email"), wide = true),
                StartField(
                    str(S.desktop_next_of_kin),
                    DocRead.text(cd, "emergency_contact_name") ?: DocRead.text(emergency, "name")
                        ?: DocRead.text(cd, "emergency_contact"),
                ),
                StartField(
                    str(S.desktop_next_of_kin_tel),
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
            str(S.dm_step2_card_bank),
            listOf(
                StartField(str(S.dm_step2_bank_name), DocRead.text(bank, "name")),
                StartField(str(S.desktop_branch), DASH),
                StartField(
                    str(S.dm_step2_bank_sort_code),
                    MemoFormat.sortCode(DocRead.text(bank, "sort_code")),
                    mono = true,
                ),
                StartField(str(S.desktop_dm_account_no), DocRead.text(bank, "account_number"), mono = true),
                StartField(str(S.desktop_payee_name), DocRead.text(bank, "account_holder_name")),
                StartField(str(S.desktop_dm_additional_ref), DASH),
                StartField(str(S.desktop_swift), DocRead.text(bank, "swift_code"), mono = true),
                StartField(str(S.dm_step2_bank_iban), DocRead.text(bank, "iban_number"), mono = true),
                StartField(str(S.desktop_dm_aba_routing), DASH, mono = true),
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
                StartField(str(S.dm_rates_day_rate), money(daily?.get("rate")), mono = true),
                StartField(str(S.desktop_dm_day_hours), hours(daily), mono = true),
                StartField(str(S.dm_rates_weekly_rate), money(weekly?.get("rate")), mono = true),
                StartField(str(S.desktop_dm_weekly_hours), hours(weekly), mono = true),
            )
            deal.dealType == "picture" ->
                listOf(StartField(str(S.dm_rates_card_picture), money(rates?.get("picture_fee")), mono = true))
            else -> {
                val covers = rates?.get("buyout_covers")
                listOf(
                    StartField(str(S.dm_rates_buyout_rate_weekly), money(rates?.get("buyout_rate")), mono = true),
                    StartField(
                        str(S.desktop_covers),
                        when {
                            (covers as? JsonPrimitive)?.content == "unlimited" ->
                                str(S.dm_rates_buyout_covers_unlimited)
                            Js.truthy(covers) -> str(S.desktop_dm_hrs_per_day, Js.text(covers))
                            else -> DASH
                        },
                    ),
                )
            }
        }
        return StartSection(
            str(S.dm_section_rates),
            fields + listOf(
                StartField(str(S.dm_rates_currency), deal.contractCurrency ?: "GBP", mono = true),
                StartField(str(S.dm_ds_deal_type), deal.dealType?.let { DealLabels.formatLabel(it) } ?: DASH),
            ),
        )
    }

    private fun holidayPay(deal: DealDoc): StartSection {
        val treatment = HolidayPay.treatmentOf(deal)
        val inclusive = treatment == HolidayPay.INCLUSIVE
        val percent = HolidayPay.of(deal)?.percent?.takeIf { it != 0.0 && !it.isNaN() }
        return StartSection(
            str(S.dm_rates_card_hp),
            listOf(
                StartField(
                    str(S.desktop_treatment),
                    treatment?.let {
                        if (inclusive) str(S.desktop_dm_inclusive_of_rate) else str(S.desktop_dm_exclusive_of_rate)
                    } ?: DASH,
                ),
                StartField(str(S.desktop_dm_holiday_ent), percent?.let { "${Js.number(it)}%" } ?: DASH, mono = true),
                StartField(
                    str(S.desktop_dm_included_in_rate),
                    treatment?.let { if (inclusive) str(S.yes) else str(S.no) } ?: DASH,
                ),
            ),
            columns = 3,
        )
    }

    private fun tables(deal: DealDoc, symbol: String, flatFee: Boolean): List<StartTable> = buildList {
        if (!flatFee) {
            listOf(
                Triple("overtimes", S.dm_rates_overtimes, S.desktop_dm_no_overtime_rows),
                Triple("premiums", S.dm_rates_premiums, S.desktop_dm_no_premiums),
                Triple("penalties", S.dm_rates_penalties, S.desktop_dm_no_penalties),
                Triple("turnarounds", S.dm_rates_turnarounds, S.desktop_dm_no_turnaround_rules),
            ).forEach { (key, title, empty) ->
                add(
                    StartTable(
                        title = str(title),
                        columns = listOf(
                            MemoColumn(str(if (key == "overtimes") S.desktop_dm_band else S.dm_rule_section_rule)),
                            MemoColumn(str(S.av_rate), mono = true),
                            MemoColumn(str(S.dm_rule_nominal), mono = true),
                            MemoColumn(str(S.dm_pay_pay_frequency), alignEnd = true),
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
                        emptyText = str(empty),
                    ),
                )
            }
        }
        add(entitlementTable(deal, "allowances", S.allowances_label, S.desktop_dm_no_allowances, symbol))
        add(entitlementTable(deal, "rentals", S.dm_allow_card_rentals, S.desktop_dm_no_rentals, symbol))
        add(fringeTable(deal, symbol))
    }

    private fun entitlementTable(deal: DealDoc, key: String, title: String, empty: String, symbol: String) = StartTable(
        title = str(title),
        columns = listOf(
            MemoColumn(str(S.name)),
            MemoColumn(str(S.type)),
            MemoColumn(str(S.amount), mono = true),
            MemoColumn(str(S.dm_pay_pay_frequency)),
            MemoColumn(str(S.asset_currency), alignEnd = true),
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
        emptyText = str(empty),
    )

    /** Row-level `percentage` / `flat` / `basis` — the memo reads `source.*`, so the two can differ. */
    private fun fringeTable(deal: DealDoc, symbol: String): StartTable {
        val inclusive = HolidayPay.treatmentOf(deal) == HolidayPay.INCLUSIVE
        return StartTable(
            title = str(S.desktop_dm_fringes_employer_costs),
            columns = listOf(
                MemoColumn(str(S.desktop_line)),
                MemoColumn("%", mono = true),
                MemoColumn(str(S.desktop_flat), mono = true),
                MemoColumn(str(S.dm_rule_basis)),
                MemoColumn(str(S.desktop_dm_pay_freq_abbrev), alignEnd = true),
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
            emptyText = str(S.desktop_dm_no_fringes),
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
