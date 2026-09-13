package com.zillit.desktop.feature.dealmemo.domain.preview

import com.zillit.desktop.feature.dealmemo.domain.DealDoc
import com.zillit.desktop.feature.dealmemo.domain.DealLabels
import com.zillit.desktop.feature.dealmemo.domain.DocRead
import com.zillit.desktop.feature.dealmemo.domain.rates.Js
import com.zillit.desktop.feature.dealmemo.domain.rates.RateFormat
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** The holiday-pay fringe and the treatment a deal applies it with. */
data class HolidayPay(val fringe: JsonObject, val percent: Double, val treatment: String?) {
    val inclusive: Boolean get() = treatment == INCLUSIVE

    companion object {
        const val INCLUSIVE = "incl"
        const val EXCLUSIVE = "excl"
        private val HOLIDAY_PAY_NAME = Regex("holiday\\s*pay", RegexOption.IGNORE_CASE)

        /** `isHolidayPayRow` on a deal row: id `holiday_pay` or `hp`, or a name that says holiday pay. */
        fun isHolidayPayRow(row: JsonObject): Boolean {
            val source = DocRead.obj(row, "source")
            val id = DocRead.text(source, "id") ?: DocRead.text(row, "row_id").orEmpty()
            val name = (DocRead.text(source, "name") ?: DocRead.text(source, "label")).orEmpty()
            return id == "holiday_pay" || id == "hp" || HOLIDAY_PAY_NAME.containsMatchIn(name)
        }

        /** `deal.holiday_pay.treatment ?? rates.hp_treatment` — the legacy spelling still read. */
        fun treatmentOf(deal: DealDoc): String? =
            DocRead.text(DocRead.obj(deal.json, "holiday_pay"), "treatment") ?: DocRead.text(deal.rates, "hp_treatment")

        fun of(deal: DealDoc): HolidayPay? {
            val fringe = DocRead.objects(deal.json["fringes"]).firstOrNull(::isHolidayPayRow) ?: return null
            val source = DocRead.obj(fringe, "source")
            val amount = source?.get("rate_amount")?.takeUnless { it is JsonNull } ?: source?.get("percentage")
            return HolidayPay(fringe, Js.toNumber(amount ?: JsonPrimitive(0)) ?: Double.NaN, treatmentOf(deal))
        }
    }
}

/** The deal's terms on the memo card: structure, rates, holiday pay, the rule tables, conditions. */
internal object MemoTerms {

    private const val DASH = MemoFormat.DASH
    private val FLAT_FEE_TYPES = setOf("picture", "buyout", "buy-out")

    fun isFlatFee(deal: DealDoc): Boolean = deal.dealType in FLAT_FEE_TYPES

    fun blocks(deal: DealDoc, context: MemoContext): List<MemoBlock> {
        val flatFee = isFlatFee(deal)
        val symbol = RateFormat.currencySymbol(deal.contractCurrency ?: "GBP")
        val holidayPay = HolidayPay.of(deal)
        return buildList {
            add(structure(deal, context))
            add(rates(deal, symbol, flatFee))
            if (!flatFee && holidayPay != null) {
                add(MemoBlock.HolidayPay("Holiday Pay", holidayView(deal, holidayPay, symbol)))
            }
            if (!flatFee) addAll(MemoTables.all(deal, symbol))
            conditions(deal)?.let(::add)
        }
    }

    private fun structure(deal: DealDoc, context: MemoContext): MemoBlock {
        val terms = deal.terms
        val conditions = DocRead.obj(deal.json, "credit_conditions")
        fun condition(key: String) = conditions?.get(key)?.takeUnless { it is JsonNull } ?: terms?.get(key)
        val type = deal.dealType
        val zone = context.zone
        val fields = mutableListOf(
            MemoField(
                "Deal Type",
                type?.let { MemoFormat.DEAL_LABELS[it] ?: DealLabels.formatLabel(it, context.translate) } ?: DASH,
            ),
            // Blank, not the dash, when the basis is missing — the web's formatLabel("") prints nothing.
            MemoField("Billing Basis", billingBasis(DocRead.text(terms, "billing_basis"), context)),
            MemoField("Start Date", MemoFormat.date(deal.startDate, zone)),
            MemoField("End Date", MemoFormat.date(deal.endDate, zone)),
            MemoField("Date of Deal Memo", MemoFormat.date(DocRead.epoch(terms, "deal_memo_date"), zone)),
            MemoField("Date Due", MemoFormat.date(deal.completionDue, zone)),
            MemoField("Prep", MemoFormat.dateRange(DocRead.obj(terms, "prep"), zone)),
            MemoField("Shoot", MemoFormat.dateRange(DocRead.obj(terms, "shoot"), zone)),
            MemoField("Wrap", MemoFormat.dateRange(DocRead.obj(terms, "wrap"), zone)),
            MemoField(
                "Notice Period",
                MemoFormat.durationToken(deal.noticePeriod).ifEmpty {
                    MemoFormat.NOTICE_LABELS[deal.noticePeriod] ?: DASH
                },
            ),
        )
        DocRead.text(terms, "notice_reminder")?.let { reminder ->
            fields += MemoField("Notice Reminder", MemoFormat.durationToken(reminder).ifEmpty { DASH })
        }
        val location = (condition("work_location") as? JsonPrimitive)?.content.orEmpty()
        fields += MemoField("Work Location", DealLabels.formatLabel(location, context.translate).ifEmpty { DASH })
        val zoneValue = (condition("travel_zone") as? JsonPrimitive)?.content.orEmpty()
        fields += MemoField(
            "Travel Zone",
            MemoFormat.TRAVEL_ZONE_LABELS[zoneValue] ?: DealLabels.formatLabel(zoneValue, context.translate),
        )
        fields += MemoField("Distant Location", if (Js.truthy(condition("distant_loc_applied"))) "Yes" else "No")
        DocRead.text(terms, "additional_notes")?.let { fields += MemoField("Additional Notes", it, wide = true) }
        return MemoBlock.Fields("Deal Structure", fields)
    }

    private fun billingBasis(value: String?, context: MemoContext): String =
        value?.let { MemoFormat.BILLING_BASIS_LABELS[it] ?: DealLabels.formatLabel(it, context.translate) }.orEmpty()

    @Suppress("CyclomaticComplexMethod")
    private fun rates(deal: DealDoc, symbol: String, flatFee: Boolean): MemoBlock {
        val rates = deal.rates
        val fields = mutableListOf(
            MemoField("Contract Currency", DocRead.text(rates, "contract_currency") ?: DASH),
            MemoField("Payment Currency", DocRead.text(rates, "pay_currency") ?: DASH),
        )
        fun money(key: String, from: JsonObject? = rates) =
            from?.get(key).takeIf { Js.truthy(it) }?.let { MemoFormat.money(it, symbol) } ?: DASH
        when (deal.dealType) {
            "picture" -> fields += MemoField("Picture Fee", money("picture_fee"), mono = true)
            "buyout", "buy-out" -> {
                fields += MemoField("Buy-Out Rate (weekly)", money("buyout_rate"), mono = true)
                val covers = rates?.get("buyout_covers")
                val coversText = when {
                    (covers as? JsonPrimitive)?.content == "unlimited" -> "Unlimited"
                    Js.truthy(covers) -> "${Js.text(covers)} hrs/day"
                    else -> DASH
                }
                fields += MemoField("Covers", coversText)
            }
            else -> {
                fields += rateWithHours("Day Rate", DocRead.obj(rates, "daily"), symbol)
                fields += rateWithHours("Weekly Rate", DocRead.obj(rates, "weekly"), symbol)
                fields += MemoField("Hourly Rate", money("hr_rate"), mono = true)
            }
        }
        if (!flatFee) {
            val treatment = HolidayPay.treatmentOf(deal)
            fields += MemoField(
                "HP Treatment",
                when (treatment) {
                    HolidayPay.INCLUSIVE -> "Inclusive"
                    HolidayPay.EXCLUSIVE -> "Exclusive (on top)"
                    else -> DASH
                },
            )
            fields += MemoField("Phase Rates", phaseRates(DocRead.obj(rates, "phase_rates"), symbol), wide = true)
        }
        return MemoBlock.Fields("Rates", fields, dga = dga(DocRead.obj(rates, "dga_production_fee"), symbol))
    }

    private fun rateWithHours(label: String, block: JsonObject?, symbol: String): MemoField {
        val rate = block?.get("rate").takeIf { Js.truthy(it) }?.let { MemoFormat.money(it, symbol) } ?: DASH
        val hours = block?.get("hrs").takeIf { Js.truthy(it) }?.let { "· ${Js.text(it)}h" }
        return MemoField(label, MemoValue.Figure(rate, hours), mono = true)
    }

    private fun phaseRates(phases: JsonObject?, symbol: String): MemoValue {
        if (!Js.truthy(phases?.get("on"))) return MemoValue.Text("Single rate (all phases)")
        return MemoValue.Parts(
            listOf("prep_rate" to "Prep", "shoot_rate" to "Shoot", "wrap_rate" to "Wrap").map { (key, label) ->
                "$label ${MemoFormat.money(phases?.get(key), symbol)}"
            },
        )
    }

    private fun dga(fee: JsonObject?, symbol: String): List<MemoField> {
        if (fee == null) return emptyList()
        fun money(key: String) = MemoFormat.money(fee[key], symbol)
        val basis = DocRead.text(fee, "coa_basis")
        return listOf(
            MemoField("Weekly Fee", money("weekly_fee"), mono = true),
            MemoField("PP Weeks", fee["pp_weeks"]?.takeUnless { it is JsonNull }?.let(Js::text) ?: DASH, mono = true),
            MemoField("Total PP Fee", money("total_pp_fee"), mono = true),
            MemoField("Total PP Salary", money("total_pp_salary"), mono = true),
            MemoField("Total Compensation", money("total_compensation"), mono = true),
            MemoField("COA Basis", basis?.let { MemoFormat.COA_BASIS_LABELS[it] ?: it } ?: DASH),
            MemoField("COA Amount", money("coa_amount"), mono = true),
        )
    }

    /** The derived HP block (`DMDealPreviewPage.jsx:1927-1948, 3923-4035`). */
    @Suppress("CyclomaticComplexMethod")
    fun holidayView(deal: DealDoc, holidayPay: HolidayPay, symbol: String): HolidayPayView {
        val percent = holidayPay.percent
        val rate = percent / PERCENT
        val inclusive = holidayPay.inclusive
        val daily = Js.toNumber(DocRead.obj(deal.rates, "daily")?.get("rate") ?: JsonPrimitive(0)) ?: Double.NaN
        val weekly = Js.toNumber(DocRead.obj(deal.rates, "weekly")?.get("rate") ?: JsonPrimitive(0)) ?: Double.NaN
        val day = HolidaySplit.of(daily, rate, inclusive)
        val week = HolidaySplit.of(weekly, rate, inclusive)
        val pct = Js.number(percent)
        fun money(value: Double) = if (value.isNaN()) DASH else MemoFormat.money(value, symbol)
        val source = DocRead.obj(holidayPay.fringe, "source")
        val tag = (source?.get("label")?.takeUnless { it is JsonNull } ?: source?.get("name"))
            ?.takeIf { Js.truthy(it) }?.let(Js::text) ?: "$pct%"
        val alert = if (inclusive) {
            "HP included within the agreed day rate (÷${Js.toFixed(1 + rate, DIVISOR_DIGITS)}). Base rate: " +
                "${money(day.base)}/day · HP element: ${money(day.holiday)}/day"
        } else {
            "HP added on top of the day rate at $pct%. Total with HP: ${money(day.total)}/day"
        }
        val lines = buildList {
            if (daily > 0) {
                add(RateLine(if (inclusive) "Agreed Day Rate" else "Day Rate (base)", money(daily)))
                if (inclusive) {
                    add(RateLine("Base Rate (ex HP)", money(day.base), derived = true))
                    add(RateLine("HP Element ($pct%)", money(day.holiday), derived = true))
                } else {
                    add(RateLine("HP Element ($pct%, on top)", money(day.holiday), derived = true))
                    add(RateLine("Total Day Rate (with HP)", money(day.total), derived = true))
                }
            }
            if (weekly > 0) {
                add(RateLine(if (inclusive) "Weekly (5-day basis)" else "Weekly Rate (base)", money(weekly)))
                if (inclusive) {
                    add(RateLine("Weekly Base (ex HP)", money(week.base), derived = true))
                    add(RateLine("Weekly HP Element ($pct%)", money(week.holiday), derived = true))
                } else {
                    add(RateLine("Weekly HP Element ($pct%, on top)", money(week.holiday), derived = true))
                    add(RateLine("Total Weekly (with HP)", money(week.total), derived = true))
                }
            }
        }
        return HolidayPayView(tag, inclusive, alert, lines)
    }

    private fun conditions(deal: DealDoc): MemoBlock? {
        val fromConditions = DocRead.obj(deal.json, "credit_conditions")?.get("custom_conditions")
        val list = (fromConditions as? JsonArray) ?: deal.terms?.get("custom_conditions")
        val items = DocRead.array(list).mapNotNull { (it as? JsonPrimitive)?.content?.takeIf(String::isNotBlank) }
        return if (items.isEmpty()) null else MemoBlock.Conditions("Conditions", items)
    }

    private const val PERCENT = 100.0
    private const val DIVISOR_DIGITS = 4
}

/** A rate split around holiday pay: inclusive rates are divided out, exclusive ones topped up. */
private data class HolidaySplit(val base: Double, val holiday: Double, val total: Double) {
    companion object {
        fun of(rate: Double, hpRate: Double, inclusive: Boolean): HolidaySplit {
            val base = if (inclusive && hpRate > 0) rate / (1 + hpRate) else rate
            val holiday = if (inclusive) rate - base else rate * hpRate
            return HolidaySplit(base, holiday, if (inclusive) rate else rate + holiday)
        }
    }
}
