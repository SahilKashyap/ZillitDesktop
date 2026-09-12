package com.zillit.desktop.feature.bankrec.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.common.Money
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDivider
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTooltip
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.bankrec.domain.BankRecFormat
import com.zillit.desktop.feature.bankrec.domain.FxRates
import com.zillit.desktop.feature.bankrec.domain.FxVariance
import com.zillit.desktop.feature.bankrec.domain.JournalLine
import com.zillit.desktop.feature.bankrec.ui.BankRecEvent
import com.zillit.desktop.feature.bankrec.ui.BankRecUiState
import com.zillit.desktop.feature.bankrec.ui.components.BankRecIcons
import com.zillit.desktop.feature.bankrec.ui.components.BrAlign
import com.zillit.desktop.feature.bankrec.ui.components.BrBadge
import com.zillit.desktop.feature.bankrec.ui.components.BrBanner
import com.zillit.desktop.feature.bankrec.ui.components.BrCard
import com.zillit.desktop.feature.bankrec.ui.components.BrColumn
import com.zillit.desktop.feature.bankrec.ui.components.BrEmpty
import com.zillit.desktop.feature.bankrec.ui.components.BrSkeletonRows
import com.zillit.desktop.feature.bankrec.ui.components.BrTable
import com.zillit.desktop.feature.bankrec.ui.components.BrTag
import com.zillit.desktop.feature.bankrec.ui.components.BrTone
import com.zillit.desktop.feature.bankrec.ui.components.mono

/**
 * What foreign payments cost against what they were budgeted at, and the
 * journal posting them would write.
 *
 * The budget rate is Production Setup's for an unposted row and the posted
 * rate for a posted one — see [FxRates] — and the chart, the table and the
 * Post All gate all read that one resolution, so the page never shows two
 * budget rates for the same currency.
 */
@Suppress("CyclomaticComplexMethod") // The empty, loading and blocked states the web draws.
@Composable
fun ColumnScope.FxVariancesPage(state: BankRecUiState, onEvent: (BankRecEvent) -> Unit) {
    val page = state.fxPage
    when {
        !state.fxLoading && !state.periodsLoading && state.openPeriods.isEmpty() -> {
            NoActivePeriod(BankRecIcons.Swap)
            return
        }

        (state.fxLoading || state.periodsLoading) && state.fxVariances.isEmpty() -> {
            BrSkeletonRows(5)
            return
        }
    }

    val rows = state.fxVariances.forPeriod(state, page.periodChoice) { it.periodId }
    val unposted = rows.filterNot { it.isPosted }
    val blocking = unposted.filterNot { FxRates.resolve(it, state.rates).ok }

    ActionRow {
        PeriodFilter(state, page.periodChoice) { onEvent(BankRecEvent.SetFxPeriod(it)) }
        if (rows.isNotEmpty()) {
            val codes = rows.map { it.invoiceCurrency }.filter { it.isNotBlank() }.distinct()
            BrTag("${codes.joinToString(", ")} · ${state.projectCurrency} account", BrTone.Teal)
            ZillitTooltip(if (blocking.isNotEmpty()) "Some rows are missing a budget or bank rate" else "") {
                ZillitButton(
                    text = if (page.postingAll) "Posting…" else "Post All Variances",
                    onClick = { onEvent(BankRecEvent.PostAllFx) },
                    size = ButtonSize.Small,
                    loading = page.postingAll,
                    enabled = !page.postingAll && unposted.isNotEmpty() && blocking.isEmpty(),
                )
            }
        }
    }

    if (rows.isEmpty()) {
        BrEmpty(
            title = "No FX Variances",
            message = "No foreign currency transactions detected in this period. Import a statement with " +
                "international payments to see FX variances here.",
            icon = BankRecIcons.Swap,
            tone = BrTone.Teal,
        )
        return
    }

    if (blocking.isNotEmpty()) RatesMissing(blocking)
    page.postAllMessage?.let { BrBanner(tone = BrTone.Teal, message = it, modifier = Modifier.fillMaxWidth()) }
    BrBanner(
        tone = BrTone.Teal,
        icon = ZillitIcons.Info,
        title = "How it works",
        message = "When actual bank rates differ from budget rates, the variance is captured here for posting " +
            "to the cost report and nominal ledger.",
        modifier = Modifier.fillMaxWidth(),
    )

    RatesChart(rows, state)
    VarianceTable(rows, state, onEvent)
    JournalPreview(rows, state.projectCurrency)
}

/** Why Post All is unavailable, naming the currencies — a greyed button alone reads as "nothing to post". */
@Composable
private fun RatesMissing(blocking: List<FxVariance>) {
    val codes = blocking.map { it.invoiceCurrency }.filter { it.isNotBlank() }.distinct()
    val noun = if (blocking.size == 1) "row" else "rows"
    val which = if (codes.isEmpty()) "" else " (${codes.joinToString(", ")})"
    BrBanner(
        tone = BrTone.Amber,
        icon = ZillitIcons.Warning,
        title = "Rates missing",
        message = "${blocking.size} unposted $noun$which have no budget or bank rate, so Post All Variances is " +
            "unavailable. Set the rate in Production Setup → Project Currencies, or open a row and enter it " +
            "when posting.",
        modifier = Modifier.fillMaxWidth(),
    )
}

/** Each variance as a bar: the bank rate against the budget, and what the gap cost or saved. */
@Composable
private fun RatesChart(rows: List<FxVariance>, state: BankRecUiState) {
    val colors = ZillitTheme.colors
    val net = rows.sumOf { it.variance }
    BrCard(Modifier.fillMaxWidth(), title = "Bank Rates vs Budget", icon = ZillitIcons.BarChart, padded = true) {
        rows.forEachIndexed { index, row ->
            ChartRow(row, state)
            if (index != rows.lastIndex) ZillitDivider()
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ZillitText("Net FX ${if (net >= 0) "Gain" else "Loss"}", style = mono(11.sp), color = colors.textMuted)
            ZillitText(
                BankRecFormat.signedMoney(net, state.projectCurrency),
                style = mono(13.sp, FontWeight.Bold),
                color = if (net >= 0) colors.success else colors.danger,
            )
        }
    }
}

@Composable
private fun ChartRow(row: FxVariance, state: BankRecUiState) {
    val colors = ZillitTheme.colors
    val resolved = FxRates.resolve(row, state.rates)
    val tint = if (row.isGain) colors.success else colors.danger
    val arrow = if (row.isGain) "▲" else "▼"
    val word = if (row.isGain) "Gain" else "Loss"
    Row(
        Modifier.fillMaxWidth().padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(
            Modifier.width(128.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            ZillitText(BankRecFormat.localShortDay(row.createdAtMillis), style = mono(11.sp), color = colors.textMuted)
            ZillitText("·", style = ZillitTheme.typography.bodySmall, color = colors.textMuted)
            ZillitText(
                row.invoiceCurrency,
                style = ZillitTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                maxLines = 1,
            )
        }
        Box(Modifier.weight(1f).height(8.dp).clip(CircleShape).background(colors.surfaceHover)) {
            Box(
                Modifier.fillMaxWidth(FxRates.barPercent(resolved) / 100f).fillMaxHeight().clip(CircleShape)
                    .background(tint),
            )
        }
        ZillitText(
            BankRecFormat.rate(resolved.bank),
            style = mono(12.sp, FontWeight.SemiBold),
            color = tint,
            textAlign = TextAlign.End,
            modifier = Modifier.width(56.dp),
        )
        ZillitText(
            "$arrow ${BankRecFormat.signedRate(FxRates.rateDelta(resolved))} · $word",
            style = mono(11.sp),
            color = tint,
            maxLines = 1,
            modifier = Modifier.width(136.dp),
        )
        ZillitText(
            BankRecFormat.signedMoney(row.variance, state.projectCurrency),
            style = mono(13.sp, FontWeight.Medium),
            color = tint,
            textAlign = TextAlign.End,
            maxLines = 1,
            modifier = Modifier.width(104.dp),
        )
    }
}

@Suppress("LongMethod") // One column per figure the web's table shows.
@Composable
private fun VarianceTable(rows: List<FxVariance>, state: BankRecUiState, onEvent: (BankRecEvent) -> Unit) {
    val colors = ZillitTheme.colors
    val code = state.projectCurrency
    val figure = mono(12.5.sp)
    val bold = mono(12.5.sp, FontWeight.Bold)
    BrCard(Modifier.fillMaxWidth()) {
        BrTable(
            rows = rows,
            key = { it.id },
            minWeightWidth = 120.dp,
            columns = listOf(
                BrColumn("Date", width = 48.dp) { row ->
                    ZillitText(
                        BankRecFormat.localShortDay(row.createdAtMillis),
                        style = mono(12.sp),
                        color = colors.textSecondary,
                    )
                },
                BrColumn("Supplier", weight = 1.2f) { row -> SupplierCell(row) },
                BrColumn("Curr.", width = 64.dp) { row -> BrTag(currencyTag(row.invoiceCurrency), BrTone.Teal) },
                BrColumn("Foreign Amt", width = 84.dp, align = BrAlign.End) { row ->
                    ZillitText(
                        BankRecFormat.wholeMoney(row.foreignAmount, row.invoiceCurrency),
                        style = figure,
                        maxLines = 1,
                    )
                },
                BrColumn("Budget Rate", width = 84.dp, align = BrAlign.End) { row ->
                    val resolved = FxRates.resolve(row, state.rates)
                    val why = if (resolved.budget == null && !resolved.isPosted) BUDGET_RATE_MISSING else ""
                    ZillitTooltip(why) {
                        ZillitText(BankRecFormat.rate(resolved.budget), style = figure, color = colors.textSecondary)
                    }
                },
                BrColumn("Budget $code", width = 96.dp, align = BrAlign.End) { row ->
                    ZillitText(
                        BankRecFormat.money(row.budgetAmount, code),
                        style = figure,
                        color = colors.textSecondary,
                        maxLines = 1,
                    )
                },
                BrColumn("Bank Rate", width = 72.dp, align = BrAlign.End) { row ->
                    val bank = FxRates.resolve(row, state.rates).bank
                    ZillitTooltip(if (bank == null) "No bank rate on this transaction" else "") {
                        ZillitText(
                            BankRecFormat.rate(bank),
                            style = mono(12.5.sp, FontWeight.SemiBold),
                            color = if (row.isGain) colors.success else colors.danger,
                        )
                    }
                },
                BrColumn("$code Paid", width = 96.dp, align = BrAlign.End) { row ->
                    ZillitText(BankRecFormat.money(row.paidAmount, code), style = bold, maxLines = 1)
                },
                BrColumn("Variance", width = 88.dp, align = BrAlign.End) { row ->
                    ZillitText(
                        BankRecFormat.signedMoney(row.variance, code),
                        style = bold,
                        color = if (row.isGain) colors.success else colors.danger,
                        maxLines = 1,
                    )
                },
                BrColumn("Status", width = 76.dp) { row ->
                    BrBadge(
                        if (row.isPosted) "Posted" else "Unposted",
                        if (row.isPosted) BrTone.Green else BrTone.Amber,
                    )
                },
                BrColumn("", width = 84.dp, align = BrAlign.End) { row -> PostCell(row, state, onEvent) },
            ),
        )
    }
}

@Composable
private fun SupplierCell(row: FxVariance) {
    Column {
        ZillitText(
            row.supplierLabel,
            style = ZillitTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
            maxLines = 1,
        )
        ZillitText(
            row.reference.ifBlank { BankRecFormat.DASH },
            style = mono(10.sp),
            color = ZillitTheme.colors.textMuted,
            maxLines = 1,
        )
    }
}

@Composable
private fun PostCell(row: FxVariance, state: BankRecUiState, onEvent: (BankRecEvent) -> Unit) {
    if (row.isPosted) {
        BrBadge("Posted ✓", BrTone.Green)
        return
    }
    val posting = state.fxPage.post?.let { it.varianceId == row.id && it.posting } == true
    ZillitButton(
        text = if (posting) "…" else "Post",
        onClick = { onEvent(BankRecEvent.OpenFxPost(row.id)) },
        variant = ButtonVariant.Secondary,
        size = ButtonSize.Small,
        enabled = !posting && !state.fxPage.postingAll,
    )
}

/** `€ EUR`, or the bare code when the symbol would only repeat it — never `THB THB`. */
internal fun currencyTag(code: String): String {
    val symbol = Money.symbol(code).trim()
    return if (symbol.isBlank() || symbol.equals(code.trim(), ignoreCase = true)) code else "$symbol $code"
}

/** The Dr/Cr lines posting every variance would write, and the net to P&L. */
@Composable
private fun JournalPreview(rows: List<FxVariance>, defaultCode: String) {
    val colors = ZillitTheme.colors
    val net = rows.sumOf { it.variance }
    val lines = FxRates.journal(rows, defaultCode).withIndex().toList()
    BrCard(
        Modifier.fillMaxWidth(),
        title = "Journal Preview — Post All Variances",
        icon = ZillitIcons.File,
        titleRight = {
            ZillitText(
                "Auto-generated Dr/Cr entries",
                style = ZillitTheme.typography.bodySmall,
                color = colors.textMuted,
            )
        },
    ) {
        BrTable(
            rows = lines,
            key = { it.index },
            columns = listOf(
                BrColumn<IndexedValue<JournalLine>>("Nominal", width = 80.dp) { (_, line) ->
                    ZillitText(line.nominal, style = mono(12.sp, FontWeight.SemiBold), color = colors.teal)
                },
                BrColumn("Description", weight = 2f) { (_, line) ->
                    ZillitText(line.description, style = ZillitTheme.typography.bodySmall, maxLines = 1)
                },
                BrColumn("Dr", width = 124.dp, align = BrAlign.End) { (_, line) -> DrCr(line.debit, "Dr") },
                BrColumn("Cr", width = 124.dp, align = BrAlign.End) { (_, line) -> DrCr(line.credit, "Cr") },
            ),
        )
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ZillitText(
                "Net FX ${if (net >= 0) "Gain" else "Loss"} to P&L (${FxRates.FX_NOMINAL})",
                style = ZillitTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                modifier = Modifier.weight(1f),
            )
            ZillitText(
                BankRecFormat.signedMoney(net, defaultCode),
                style = mono(13.sp, FontWeight.Bold),
                color = if (net >= 0) colors.success else colors.danger,
            )
        }
    }
}

@Composable
private fun DrCr(amount: Double?, side: String) {
    if (amount == null) {
        ZillitText(BankRecFormat.DASH, style = mono(12.5.sp), color = ZillitTheme.colors.textMuted)
        return
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        ZillitText(BankRecFormat.figure(amount), style = mono(12.5.sp))
        ZillitText(side, style = mono(9.sp), color = ZillitTheme.colors.textMuted)
    }
}

private const val BUDGET_RATE_MISSING =
    "Not set in Production Setup → Project Currencies — enter it when posting"
