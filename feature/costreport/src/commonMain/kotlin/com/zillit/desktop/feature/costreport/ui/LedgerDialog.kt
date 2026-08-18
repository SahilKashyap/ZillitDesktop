// One composable per piece of the ledger drill-down.
@file:Suppress("LongMethod", "TooManyFunctions", "CyclomaticComplexMethod")

package com.zillit.desktop.feature.costreport.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitDivider
import com.zillit.desktop.core.designsystem.component.ZillitNotice
import com.zillit.desktop.core.designsystem.component.ZillitSpinner
import com.zillit.desktop.core.designsystem.component.ZillitStatTile
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.feature.costreport.domain.CrDates
import com.zillit.desktop.feature.costreport.domain.CrFormat
import com.zillit.desktop.feature.costreport.domain.LedgerItem
import com.zillit.desktop.feature.costreport.domain.LedgerType

private val DIALOG_WIDTH = 1040.dp
private val DATE_WIDTH: Dp = 96.dp
private val REF_WIDTH: Dp = 120.dp
private val TYPE_WIDTH: Dp = 76.dp
private val PARTY_WIDTH: Dp = 160.dp
private val CODE_WIDTH: Dp = 80.dp
private val AMOUNT_WIDTH: Dp = 110.dp

/** The bottom sheet's desktop shape: a dialog listing the account's line items, actuals then commits. */
@Composable
internal fun LedgerDialog(view: LedgerView, onEvent: (CostReportEvent) -> Unit) {
    val colors = ZillitTheme.colors
    val result = view.result
    ZillitDialogShell(
        title = "${view.nominal.apiCode}  ${result?.name ?: view.nominal.name}",
        subtitle = view.subtitle,
        onDismiss = { onEvent(CostReportEvent.CloseLedger) },
        visible = true,
        width = DIALOG_WIDTH,
        scrollable = false,
        actions = {
            ZillitButton(text = "Close", onClick = { onEvent(CostReportEvent.CloseLedger) },
                variant = ButtonVariant.Tertiary)
        },
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
            ZillitStatTile(label = "ATD", value = CrFormat.money(result?.actualsToDate, view.symbol),
                modifier = Modifier.weight(1f))
            ZillitStatTile(label = "Commits", value = CrFormat.money(result?.commitments, view.symbol),
                tone = StatusTone.Progress, modifier = Modifier.weight(1f))
            ZillitStatTile(label = "Budget", value = CrFormat.money(view.budget, view.symbol),
                modifier = Modifier.weight(1f))
        }
        view.error?.let { ZillitNotice(text = it, tone = StatusTone.Rejected) }
        when {
            view.loading -> Box(
                modifier = Modifier.fillMaxWidth().padding(ZillitTheme.spacing.xl),
                contentAlignment = Alignment.Center,
            ) { ZillitSpinner() }
            result == null || result.items.isEmpty() -> ZillitText(
                text = "No line items for this account.",
                style = ZillitTheme.typography.bodyMedium,
                color = colors.textMuted,
            )
            else -> LedgerTable(view, result.actuals, result.commits)
        }
        result?.let {
            ZillitText(
                text = "${it.items.size} line items · Total: " +
                    CrFormat.money(it.items.sumOf { i -> i.amount }, view.symbol),
                style = ZillitTheme.typography.labelSmall,
                color = colors.textMuted,
            )
        }
    }
}

@Composable
private fun ColumnScope.LedgerTable(view: LedgerView, actuals: List<LedgerItem>, commits: List<LedgerItem>) {
    Column(Modifier.fillMaxWidth().weight(1f, fill = false)) {
        LedgerHeaderRow()
        ZillitDivider()
        LazyColumn(Modifier.fillMaxWidth()) {
            if (actuals.isNotEmpty()) {
                item { GroupRow(LedgerType.Actuals.label, actuals, view.symbol) }
                items(actuals) { LedgerRow(it, view.symbol) }
            }
            if (commits.isNotEmpty()) {
                item { GroupRow(LedgerType.Commits.label, commits, view.symbol) }
                items(commits) { LedgerRow(it, view.symbol) }
            }
        }
    }
}

@Composable
private fun LedgerHeaderRow() {
    val colors = ZillitTheme.colors
    val style = ZillitTheme.typography.labelSmall
    Row(
        modifier = Modifier.fillMaxWidth().background(colors.surfaceSunken).padding(vertical = ZillitTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Cell("Date", DATE_WIDTH, style, colors.textMuted)
        Cell("Ref", REF_WIDTH, style, colors.textMuted)
        Cell("Type", TYPE_WIDTH, style, colors.textMuted)
        ZillitText(text = "Description", style = style, color = colors.textMuted, maxLines = 1,
            modifier = Modifier.weight(1f).padding(horizontal = ZillitTheme.spacing.xs))
        Cell("Supplier / Crew", PARTY_WIDTH, style, colors.textMuted)
        Cell("Code", CODE_WIDTH, style, colors.textMuted)
        Cell("Amount", AMOUNT_WIDTH, style, colors.textMuted, TextAlign.End)
    }
}

@Composable
private fun GroupRow(label: String, items: List<LedgerItem>, symbol: String) {
    val colors = ZillitTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth().background(colors.accentSoft).padding(
            horizontal = ZillitTheme.spacing.xs, vertical = ZillitTheme.spacing.xxs,
        ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZillitText(text = "$label · ${items.size}", style = ZillitTheme.typography.label,
            color = colors.accentText, modifier = Modifier.weight(1f))
        ZillitText(
            text = CrFormat.money(items.sumOf { it.amount }, symbol),
            style = ZillitTheme.typography.numeric.copy(fontWeight = FontWeight.SemiBold),
            color = colors.accentText,
            textAlign = TextAlign.End,
            modifier = Modifier.width(AMOUNT_WIDTH).padding(horizontal = ZillitTheme.spacing.xs),
        )
    }
}

@Composable
private fun LedgerRow(item: LedgerItem, symbol: String) {
    val colors = ZillitTheme.colors
    val style = ZillitTheme.typography.bodySmall
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = ZillitTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Cell(CrDates.date(item.effDateMs).ifBlank { "—" }, DATE_WIDTH, ZillitTheme.typography.numeric,
            colors.textSecondary)
        Cell(item.reference, REF_WIDTH, ZillitTheme.typography.numeric, colors.textPrimary)
        Box(Modifier.width(TYPE_WIDTH).padding(horizontal = ZillitTheme.spacing.xs)) {
            val tone = if (item.effectiveType == LedgerType.Actuals) StatusTone.Done else StatusTone.Progress
            ZillitStatusPill(label = item.typeLabel, tone = tone)
        }
        ZillitText(text = item.description.ifBlank { "—" }, style = style, color = colors.textPrimary, maxLines = 1,
            modifier = Modifier.weight(1f).padding(horizontal = ZillitTheme.spacing.xs))
        Cell(item.vendor.ifBlank { "—" }, PARTY_WIDTH, style, colors.textSecondary)
        Cell(item.account ?: "—", CODE_WIDTH, ZillitTheme.typography.numeric, colors.textSecondary)
        Cell(
            text = CrFormat.money(item.amount, symbol),
            width = AMOUNT_WIDTH,
            style = ZillitTheme.typography.numeric,
            color = if (item.amount < 0) colors.danger else colors.textPrimary,
            align = TextAlign.End,
        )
    }
    ZillitDivider()
}

@Composable
private fun Cell(
    text: String,
    width: Dp,
    style: TextStyle,
    color: Color,
    align: TextAlign? = null,
) {
    ZillitText(
        text = text,
        style = style,
        color = color,
        maxLines = 1,
        textAlign = align,
        modifier = Modifier.width(width).padding(horizontal = ZillitTheme.spacing.xs),
    )
}
