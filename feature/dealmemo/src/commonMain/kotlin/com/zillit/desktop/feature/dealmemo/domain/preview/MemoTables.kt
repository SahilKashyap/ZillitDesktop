package com.zillit.desktop.feature.dealmemo.domain.preview

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
        rules(deal, "overtimes", "Overtimes", "Band", "Rate", "No overtime rows.", symbol),
        rules(deal, "premiums", "Premiums", "Premium", "Rate", "No premiums.", symbol),
        rules(deal, "penalties", "Penalties", "Penalty", "Compensation", "No penalties.", symbol),
        rules(deal, "turnarounds", "Turnarounds", "Rule", "Compensation", "No turnaround rules.", symbol),
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
        title = title,
        columns = listOf(
            MemoColumn(first),
            MemoColumn(rate, mono = true),
            MemoColumn("Nominal Code", mono = true),
            MemoColumn("Pay Frequency"),
        ),
        rows = DocRead.objects(deal.json[key]).map { row ->
            listOf(
                text(DocRead.obj(row, "source"), "label") ?: text(row, "row_id") ?: DASH,
                MemoFormat.unionComp(row, symbol),
                DocRead.text(row, "nominal_code") ?: DASH,
                MemoFormat.payFrequency(row),
            )
        },
        emptyText = empty,
    )

    private fun entitlements(deal: DealDoc, rentals: Boolean): MemoBlock.Table {
        val contract = deal.contractCurrency ?: "GBP"
        val columns = buildList {
            add(MemoColumn("Name"))
            add(MemoColumn("Type"))
            add(MemoColumn("Amount", mono = true, alignEnd = true))
            add(MemoColumn("Pay Frequency"))
            add(MemoColumn("Currency"))
            add(MemoColumn("Applies To"))
            if (rentals) add(MemoColumn("Cap", mono = true))
            add(MemoColumn("Nominal", mono = true))
            add(MemoColumn("Enabled"))
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
                add(if (Js.truthy(row["enable"])) "Yes" else "No")
            }
        }
        return MemoBlock.Table(
            title = if (rentals) "Rentals" else "Allowances",
            columns = columns,
            rows = rows,
            emptyText = if (rentals) "No rentals." else "No allowances.",
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
            type == "uncapped" -> "Uncapped"
            else -> DASH
        }
    }

    private fun appliesTo(value: String?): String = when (value) {
        null -> DASH
        "shoot" -> "Shoot Day"
        "non_shoot" -> "Non-shoot Day"
        "full_production" -> "Full Production"
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
            title = "Fringes / Employer Costs",
            columns = listOf(
                MemoColumn("Line"),
                MemoColumn("%", mono = true),
                MemoColumn("Flat", mono = true),
                MemoColumn("Basis"),
                MemoColumn("Nominal", mono = true),
                MemoColumn("Pay Freq."),
            ),
            rows = rows,
            emptyText = "No fringes.",
        )
    }

    private fun present(json: JsonObject?, key: String) = json?.get(key)?.takeUnless { it is JsonNull }

    /** A present value as JavaScript prints it — an empty string included, as `??` keeps it. */
    private fun text(json: JsonObject?, key: String): String? = present(json, key)?.let(Js::text)
}
