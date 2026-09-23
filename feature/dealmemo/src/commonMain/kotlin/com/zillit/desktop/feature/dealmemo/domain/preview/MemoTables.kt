package com.zillit.desktop.feature.dealmemo.domain.preview

import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.dealmemo.domain.DealDoc
import com.zillit.desktop.feature.dealmemo.domain.DealLabels
import com.zillit.desktop.feature.dealmemo.domain.DocRead
import com.zillit.desktop.feature.dealmemo.domain.rates.Js
import com.zillit.desktop.feature.dealmemo.domain.rates.RateFormat
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

/**
 * The memo's rule and entitlement tables (`DMDealPreviewPage.jsx:4039-4369`):
 * overtimes, premiums, penalties, turnarounds, allowances, rentals, fringes.
 * None of them shows on a flat-fee deal.
 */
internal object MemoTables {

    private const val DASH = MemoFormat.DASH

    fun all(deal: DealDoc, symbol: String): List<MemoBlock> = listOf(
        rules(
            deal, "overtimes", S.dm_rates_overtimes, S.desktop_dm_band, S.av_rate,
            S.desktop_dm_no_overtime_rows, symbol,
        ),
        rules(deal, "premiums", S.dm_rates_premiums, S.desktop_premium, S.av_rate, S.desktop_dm_no_premiums, symbol),
        rules(
            deal, "penalties", S.dm_rates_penalties, S.desktop_penalty, S.desktop_compensation,
            S.desktop_dm_no_penalties, symbol,
        ),
        rules(
            deal, "turnarounds", S.dm_rates_turnarounds, S.dm_rule_section_rule, S.desktop_compensation,
            S.desktop_dm_no_turnaround_rules, symbol,
        ),
        entitlements(deal, rentals = false),
        entitlements(deal, rentals = true),
        fringes(deal, symbol),
    )

    @Suppress("LongParameterList")
    private fun rules(
        deal: DealDoc,
        key: String,
        title: String,
        first: String,
        rate: String,
        empty: String,
        symbol: String,
    ): MemoBlock.Table = MemoBlock.Table(
        title = str(title),
        columns = listOf(
            MemoColumn(str(first)),
            MemoColumn(str(rate), mono = true),
            MemoColumn(str(S.dm_allow_nominal), mono = true),
            MemoColumn(str(S.dm_pay_pay_frequency)),
        ),
        rows = DocRead.objects(deal.json[key]).map { row ->
            listOf(
                text(DocRead.obj(row, "source"), "label") ?: text(row, "row_id") ?: DASH,
                MemoFormat.unionComp(row, symbol),
                DocRead.text(row, "nominal_code") ?: DASH,
                MemoFormat.payFrequency(row),
            )
        },
        emptyText = str(empty),
    )

    private fun entitlements(deal: DealDoc, rentals: Boolean): MemoBlock.Table {
        val contract = deal.contractCurrency ?: "GBP"
        val columns = buildList {
            add(MemoColumn(str(S.name)))
            add(MemoColumn(str(S.type)))
            add(MemoColumn(str(S.amount), mono = true, alignEnd = true))
            add(MemoColumn(str(S.dm_pay_pay_frequency)))
            add(MemoColumn(str(S.asset_currency)))
            add(MemoColumn(str(S.dm_allow_applies_to)))
            if (rentals) add(MemoColumn(str(S.dm_allow_cap_type), mono = true))
            add(MemoColumn(str(S.dm_rule_nominal), mono = true))
            add(MemoColumn(str(S.dm_allow_enabled)))
        }
        val rows = DocRead.objects(deal.json[if (rentals) "rentals" else "allowances"]).map { row ->
            val rowSymbol = RateFormat.currencySymbol(
                row["currency"]?.takeUnless { it is JsonNull }?.let(Js::text) ?: contract,
            )
            buildList {
                add(DocRead.text(row, "name") ?: DocRead.text(row, "id") ?: DASH)
                add(DealLabels.formatLabel(DocRead.text(row, "rate_type").orEmpty()).ifEmpty { DASH })
                add(amount(row, rowSymbol))
                add(MemoFormat.entitlementBasis(DocRead.text(row, "basis")).ifEmpty { DASH })
                add(DocRead.text(row, "currency") ?: DASH)
                add(appliesTo(DocRead.text(row, "applies_to")))
                if (rentals) add(cap(row, rowSymbol))
                add(DocRead.text(row, "nominal_code") ?: DASH)
                add(if (Js.truthy(row["enable"])) str(S.yes) else str(S.no))
            }
        }
        return MemoBlock.Table(
            title = if (rentals) str(S.dm_allow_card_rentals) else str(S.allowances_label),
            columns = columns,
            rows = rows,
            emptyText = if (rentals) str(S.desktop_dm_no_rentals) else str(S.desktop_dm_no_allowances),
        )
    }

    private fun amount(row: JsonObject, symbol: String): String {
        val amount = row["amount"]?.takeUnless { it is JsonNull }
        val percent = row["rate_pct"]?.takeUnless { it is JsonNull }
        return when {
            amount != null -> "$symbol${Js.toNumber(amount)?.let(RateFormat::groupAmountAuto) ?: Js.text(amount)}"
            percent != null -> "${Js.text(percent)}%"
            else -> DASH
        }
    }

    private fun cap(row: JsonObject, symbol: String): String {
        val type = DocRead.text(row, "cap_type")
        val amount = row["cap_amount"]?.takeUnless { it is JsonNull }
        return when {
            type == "capped" && amount != null ->
                "$symbol${Js.toNumber(amount)?.let(RateFormat::groupAmountAuto) ?: Js.text(amount)}"
            type == "uncapped" -> str(S.desktop_uncapped)
            else -> DASH
        }
    }

    private fun appliesTo(value: String?): String = when (value) {
        null -> DASH
        "shoot" -> str(S.cs_shoot_day)
        "non_shoot" -> str(S.desktop_dm_non_shoot_day)
        "full_production" -> str(S.desktop_dm_full_production)
        else -> DealLabels.formatLabel(value)
    }

    /** Every fringe — less the holiday-pay one when holiday pay is already inside the rate. */
    @Suppress("CyclomaticComplexMethod")
    private fun fringes(deal: DealDoc, symbol: String): MemoBlock.Table {
        val inclusive = HolidayPay.treatmentOf(deal) == HolidayPay.INCLUSIVE
        val rows = DocRead.objects(deal.json["fringes"])
            .filterNot { inclusive && HolidayPay.isHolidayPayRow(it) }
            .map { row ->
                val source = DocRead.obj(row, "source")
                val type = source?.get("rate_type")?.takeUnless { it is JsonNull }?.let(Js::text)
                val percentAmount = present(source, "rate_amount") ?: present(source, "percentage")
                val flatAmount = present(source, "rate_amount") ?: present(source, "flat")
                listOf(
                    text(source, "name") ?: text(source, "label") ?: text(row, "row_id") ?: DASH,
                    if ((type ?: "percentage") == "percentage" && percentAmount != null) {
                        "${Js.text(percentAmount)}%"
                    } else {
                        DASH
                    },
                    if (type == "flat" && flatAmount != null) MemoFormat.money(flatAmount, symbol) else DASH,
                    DocRead.text(source, "basis")?.let { DealLabels.formatLabel(it) } ?: DASH,
                    DocRead.text(row, "nominal_code") ?: DASH,
                    MemoFormat.payFrequency(row),
                )
            }
        return MemoBlock.Table(
            title = str(S.desktop_dm_fringes_employer_costs),
            columns = listOf(
                MemoColumn(str(S.desktop_line)),
                MemoColumn("%", mono = true),
                MemoColumn(str(S.desktop_flat), mono = true),
                MemoColumn(str(S.dm_rule_basis)),
                MemoColumn(str(S.dm_rule_nominal), mono = true),
                MemoColumn(str(S.desktop_dm_pay_freq_abbrev)),
            ),
            rows = rows,
            emptyText = str(S.desktop_dm_no_fringes),
        )
    }

    private fun present(json: JsonObject?, key: String) = json?.get(key)?.takeUnless { it is JsonNull }

    /** A present value as JavaScript prints it — an empty string included, as `??` keeps it. */
    private fun text(json: JsonObject?, key: String): String? = present(json, key)?.let(Js::text)
}
