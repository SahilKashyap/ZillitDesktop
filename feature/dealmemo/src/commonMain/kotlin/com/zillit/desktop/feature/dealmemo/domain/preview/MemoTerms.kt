package com.zillit.desktop.feature.dealmemo.domain.preview

import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
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
                add(MemoBlock.HolidayPay(str(S.dm_rates_card_hp), holidayView(deal, holidayPay, symbol)))
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
                str(S.dm_ds_deal_type),
                type?.let { MemoFormat.DEAL_LABELS[it] ?: DealLabels.formatLabel(it, context.translate) } ?: DASH,
            ),
            // Blank, not the dash, when the basis is missing — the web's formatLabel("") prints nothing.
            MemoField(str(S.dm_ds_billing_basis), billingBasis(DocRead.text(terms, "billing_basis"), context)),
            MemoField(str(S.start_date), MemoFormat.date(deal.startDate, zone)),
            MemoField(str(S.end_date), MemoFormat.date(deal.endDate, zone)),
            MemoField(
                str(S.desktop_dm_date_of_deal_memo),
                MemoFormat.date(DocRead.epoch(terms, "deal_memo_date"), zone),
            ),
            MemoField(str(S.dm_prev_date_due), MemoFormat.date(deal.completionDue, zone)),
            MemoField(str(S.dm_ds_phase_prep), MemoFormat.dateRange(DocRead.obj(terms, "prep"), zone)),
            MemoField(str(S.dm_ds_phase_shoot), MemoFormat.dateRange(DocRead.obj(terms, "shoot"), zone)),
            MemoField(str(S.dm_ds_phase_wrap), MemoFormat.dateRange(DocRead.obj(terms, "wrap"), zone)),
            MemoField(
                str(S.dm_ds_card_notice),
                MemoFormat.durationToken(deal.noticePeriod).ifEmpty {
                    MemoFormat.NOTICE_LABELS[deal.noticePeriod] ?: DASH
                },
            ),
        )
        DocRead.text(terms, "notice_reminder")?.let { reminder ->
            fields += MemoField(str(S.desktop_dm_notice_reminder), MemoFormat.durationToken(reminder).ifEmpty { DASH })
        }
        val location = (condition("work_location") as? JsonPrimitive)?.content.orEmpty()
        fields += MemoField(
            str(S.dm_cond_card_location),
            DealLabels.formatLabel(location, context.translate).ifEmpty { DASH },
        )
        val zoneValue = (condition("travel_zone") as? JsonPrimitive)?.content.orEmpty()
        fields += MemoField(
            str(S.desktop_dm_travel_zone),
            MemoFormat.TRAVEL_ZONE_LABELS[zoneValue] ?: DealLabels.formatLabel(zoneValue, context.translate),
        )
        fields += MemoField(
            str(S.desktop_dm_distant_location),
            if (Js.truthy(condition("distant_loc_applied"))) str(S.yes) else str(S.no),
        )
        DocRead.text(terms, "additional_notes")?.let {
            fields += MemoField(str(S.dm_ds_card_additional_notes), it, wide = true)
        }
        return MemoBlock.Fields(str(S.dm_ds_title), fields)
    }

    private fun billingBasis(value: String?, context: MemoContext): String =
        value?.let { MemoFormat.BILLING_BASIS_LABELS[it] ?: DealLabels.formatLabel(it, context.translate) }.orEmpty()

    @Suppress("CyclomaticComplexMethod")
    private fun rates(deal: DealDoc, symbol: String, flatFee: Boolean): MemoBlock {
        val rates = deal.rates
        val fields = mutableListOf(
            MemoField(str(S.dm_rates_currency), DocRead.text(rates, "contract_currency") ?: DASH),
            MemoField(str(S.dm_rates_payment_currency), DocRead.text(rates, "pay_currency") ?: DASH),
        )
        fun money(key: String, from: JsonObject? = rates) =
            from?.get(key).takeIf { Js.truthy(it) }?.let { MemoFormat.money(it, symbol) } ?: DASH
        when (deal.dealType) {
            "picture" -> fields += MemoField(str(S.dm_rates_card_picture), money("picture_fee"), mono = true)
            "buyout", "buy-out" -> {
                fields += MemoField(str(S.dm_rates_buyout_rate_weekly), money("buyout_rate"), mono = true)
                val covers = rates?.get("buyout_covers")
                val coversText = when {
                    (covers as? JsonPrimitive)?.content == "unlimited" -> str(S.dm_rates_buyout_covers_unlimited)
                    Js.truthy(covers) -> str(S.desktop_dm_hrs_per_day, Js.text(covers))
                    else -> DASH
                }
                fields += MemoField(str(S.desktop_covers), coversText)
            }
            else -> {
                fields += rateWithHours(str(S.dm_rates_day_rate), DocRead.obj(rates, "daily"), symbol)
                fields += rateWithHours(str(S.dm_rates_weekly_rate), DocRead.obj(rates, "weekly"), symbol)
                fields += MemoField(str(S.desktop_dm_hourly_rate), money("hr_rate"), mono = true)
            }
        }
        if (!flatFee) {
            val treatment = HolidayPay.treatmentOf(deal)
            fields += MemoField(
                str(S.desktop_dm_hp_treatment),
                when (treatment) {
                    HolidayPay.INCLUSIVE -> str(S.dm_rates_hp_inclusive_title)
                    HolidayPay.EXCLUSIVE -> str(S.desktop_dm_hp_exclusive_on_top)
                    else -> DASH
                },
            )
            fields += MemoField(
                str(S.dm_rates_card_phases),
                phaseRates(DocRead.obj(rates, "phase_rates"), symbol),
                wide = true,
            )
        }
        val dgaFields = dga(DocRead.obj(rates, "dga_production_fee"), symbol)
        return MemoBlock.Fields(str(S.dm_section_rates), fields, dga = dgaFields)
    }

    private fun rateWithHours(label: String, block: JsonObject?, symbol: String): MemoField {
        val rate = block?.get("rate").takeIf { Js.truthy(it) }?.let { MemoFormat.money(it, symbol) } ?: DASH
        val hours = block?.get("hrs").takeIf { Js.truthy(it) }?.let { "· ${Js.text(it)}h" }
        return MemoField(label, MemoValue.Figure(rate, hours), mono = true)
    }

    private fun phaseRates(phases: JsonObject?, symbol: String): MemoValue {
        if (!Js.truthy(phases?.get("on"))) return MemoValue.Text(str(S.desktop_dm_single_rate_all_phases))
        return MemoValue.Parts(
            listOf(
                "prep_rate" to S.dm_ds_phase_prep,
                "shoot_rate" to S.dm_ds_phase_shoot,
                "wrap_rate" to S.dm_ds_phase_wrap,
            ).map { (key, label) ->
                "${str(label)} ${MemoFormat.money(phases?.get(key), symbol)}"
            },
        )
    }

    private fun dga(fee: JsonObject?, symbol: String): List<MemoField> {
        if (fee == null) return emptyList()
        fun money(key: String) = MemoFormat.money(fee[key], symbol)
        val basis = DocRead.text(fee, "coa_basis")
        return listOf(
            MemoField(str(S.desktop_dm_weekly_fee), money("weekly_fee"), mono = true),
            MemoField(
                str(S.desktop_dm_pp_weeks),
                fee["pp_weeks"]?.takeUnless { it is JsonNull }?.let(Js::text) ?: DASH,
                mono = true,
            ),
            MemoField(str(S.desktop_dm_total_pp_fee), money("total_pp_fee"), mono = true),
            MemoField(str(S.desktop_dm_total_pp_salary), money("total_pp_salary"), mono = true),
            MemoField(str(S.desktop_dm_total_compensation), money("total_compensation"), mono = true),
            MemoField(str(S.desktop_dm_coa_basis), basis?.let { MemoFormat.COA_BASIS_LABELS[it] ?: it } ?: DASH),
            MemoField(str(S.desktop_dm_coa_amount), money("coa_amount"), mono = true),
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
            str(
                S.desktop_dm_hp_alert_inclusive,
                Js.toFixed(1 + rate, DIVISOR_DIGITS),
                money(day.base),
                money(day.holiday),
            )
        } else {
            str(S.desktop_dm_hp_alert_exclusive, pct, money(day.total))
        }
        val lines = buildList {
            if (daily > 0) {
                add(RateLine(str(if (inclusive) S.dm_rates_summary_day else S.desktop_dm_day_rate_base), money(daily)))
                if (inclusive) {
                    add(RateLine(str(S.desktop_dm_base_rate_ex_hp), money(day.base), derived = true))
                    add(RateLine(str(S.desktop_dm_hp_element, pct), money(day.holiday), derived = true))
                } else {
                    add(RateLine(str(S.desktop_dm_hp_element_on_top, pct), money(day.holiday), derived = true))
                    add(RateLine(str(S.desktop_dm_total_day_rate_with_hp), money(day.total), derived = true))
                }
            }
            if (weekly > 0) {
                add(
                    RateLine(
                        str(if (inclusive) S.dm_rates_summary_weekly else S.desktop_dm_weekly_rate_base),
                        money(weekly),
                    ),
                )
                if (inclusive) {
                    add(RateLine(str(S.desktop_dm_weekly_base_ex_hp), money(week.base), derived = true))
                    add(RateLine(str(S.desktop_dm_weekly_hp_element, pct), money(week.holiday), derived = true))
                } else {
                    add(RateLine(str(S.desktop_dm_weekly_hp_element_on_top, pct), money(week.holiday), derived = true))
                    add(RateLine(str(S.desktop_dm_total_weekly_with_hp), money(week.total), derived = true))
                }
            }
        }
        return HolidayPayView(tag, inclusive, alert, lines)
    }

    private fun conditions(deal: DealDoc): MemoBlock? {
        val fromConditions = DocRead.obj(deal.json, "credit_conditions")?.get("custom_conditions")
        val list = (fromConditions as? JsonArray) ?: deal.terms?.get("custom_conditions")
        val items = DocRead.array(list).mapNotNull { (it as? JsonPrimitive)?.content?.takeIf(String::isNotBlank) }
        return if (items.isEmpty()) null else MemoBlock.Conditions(str(S.dm_ds_card_conditions), items)
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
