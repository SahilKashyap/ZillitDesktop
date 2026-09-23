package com.zillit.desktop.feature.bankrec.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ZillitDivider
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.bankrec.domain.BankRecFormat
import com.zillit.desktop.feature.bankrec.domain.BankTransaction
import com.zillit.desktop.feature.bankrec.domain.LedgerEntry
import com.zillit.desktop.feature.bankrec.ui.components.BrAlign
import com.zillit.desktop.feature.bankrec.ui.components.BrBadge
import com.zillit.desktop.feature.bankrec.ui.components.BrColumn
import com.zillit.desktop.feature.bankrec.ui.components.BrTable
import com.zillit.desktop.feature.bankrec.ui.components.eyebrow
import com.zillit.desktop.feature.bankrec.ui.components.mono

/*
 * A period's bank lines and ledger entries as plain tables — the portal
 * summary's transaction section and the period detail's fuller one.
 *
 * The two surfaces write money differently, as the web does: the portal signs
 * a debit (`-£120.00`) because it lists money out beside money in; the detail
 * dialog has a Debit column that already says so, and prints the figure bare.
 */

/** A panel's grey heading with its count. */
@Composable
internal fun PanelHeading(title: String, count: Int, icon: ImageVector?) {
    val colors = ZillitTheme.colors
    Row(
        Modifier.fillMaxWidth().background(colors.surfaceSunken).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        icon?.let { ZillitIcon(it, tint = colors.gold, size = 14.dp) }
        ZillitText(
            title.uppercase(),
            style = eyebrow(11.sp),
            color = colors.textSecondary,
            modifier = Modifier.weight(1f),
        )
        ZillitText("$count items", style = ZillitTheme.typography.labelSmall, color = colors.textMuted)
    }
    ZillitDivider()
}

/** [detail] true is the period dialog's reading, false the portal's — see the file note. */
@Composable
internal fun BankLinesTable(rows: List<BankTransaction>, accountCurrency: String, detail: Boolean) {
    val colors = ZillitTheme.colors
    BrTable(
        rows = rows,
        key = { it.id },
        rowPadding = 8.dp,
        minWeightWidth = 140.dp,
        empty = { EmptyLines(str(S.desktop_card_no_transactions)) },
        columns = listOf(
            BrColumn(str(S.date), width = DATE_WIDTH) { txn ->
                ZillitText(
                    BankRecFormat.statementDay(txn.transactionDateMillis),
                    style = mono(11.sp),
                    color = colors.textMuted,
                    maxLines = 1,
                )
            },
            BrColumn(str(S.description)) { txn ->
                TwoLine(txn.displayName.ifBlank { BankRecFormat.DASH }, txn.reference)
            },
            BrColumn(str(S.desktop_debit), width = 92.dp, align = BrAlign.End) { txn ->
                val code = txn.amountCurrency(accountCurrency)
                val debit = txn.debit.takeIf { it > 0 }?.let { if (detail) it else -it }
                Figure(debit?.let { BankRecFormat.money(it, code) }, colors.textPrimary)
            },
            BrColumn(str(S.desktop_credit), width = 92.dp, align = BrAlign.End) { txn ->
                val code = txn.amountCurrency(accountCurrency)
                Figure(if (txn.credit > 0) BankRecFormat.money(txn.credit, code) else null, colors.success)
            },
            BrColumn(str(S.status), width = 76.dp, align = BrAlign.Center) { txn ->
                BrBadge(txn.status.label, txn.status.tone)
            },
        ),
    )
}

/**
 * The ledger side. The detail dialog shows only what has been paid — the
 * entries a signed-off month settled — and falls back to an invoice's gross
 * where it carries no debit.
 */
@Composable
internal fun LedgerLinesTable(rows: List<LedgerEntry>, projectCurrency: String, detail: Boolean) {
    val colors = ZillitTheme.colors
    BrTable(
        rows = rows,
        key = { it.id.ifBlank { it.entityId } },
        rowPadding = 8.dp,
        minWeightWidth = 140.dp,
        empty = { EmptyLines(str(S.desktop_br_no_ledger_entries)) },
        columns = listOf(
            BrColumn(str(S.date), width = DATE_WIDTH) { entry ->
                ZillitText(
                    BankRecFormat.statementDay(entry.displayDateMillis),
                    style = mono(11.sp),
                    color = colors.textMuted,
                    maxLines = 1,
                )
            },
            BrColumn(str(S.title)) { entry ->
                // The row's own reading of its name — an invoice is its supplier,
                // a quick entry its title — rather than a field an invoice lacks.
                TwoLine(entry.displayName, entry.ledgerDescription.joinToString(" · "))
            },
            BrColumn(str(S.desktop_debit), width = 92.dp, align = BrAlign.End) { entry ->
                val code = entry.currency ?: projectCurrency
                // An invoice is money out by its gross; a posting by its debit.
                val out = entry.debit?.takeIf { it > 0 } ?: entry.grossAmount?.takeIf { it > 0 }
                Figure(out?.let { BankRecFormat.money(if (detail) it else -it, code) }, colors.textPrimary)
            },
            BrColumn(str(S.desktop_credit), width = 92.dp, align = BrAlign.End) { entry ->
                val credit = entry.credit?.takeIf { it > 0 }
                Figure(credit?.let { BankRecFormat.money(it, entry.currency ?: projectCurrency) }, colors.success)
            },
        ),
    )
}

@Composable
private fun TwoLine(title: String, sub: String) {
    Column {
        ZillitText(
            title,
            style = ZillitTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
            maxLines = 1,
        )
        if (sub.isNotBlank()) {
            ZillitText(sub, style = mono(9.5.sp), color = ZillitTheme.colors.textMuted, maxLines = 1)
        }
    }
}

@Composable
private fun Figure(text: String?, color: Color) {
    ZillitText(
        text ?: BankRecFormat.DASH,
        style = mono(12.sp),
        color = if (text == null) ZillitTheme.colors.textMuted else color,
        maxLines = 1,
        textAlign = TextAlign.End,
    )
}

@Composable
private fun EmptyLines(text: String) {
    ZillitText(
        text,
        style = ZillitTheme.typography.bodySmall,
        color = ZillitTheme.colors.textMuted,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(vertical = 28.dp),
    )
}

/**
 * A hairline down one side of a box, drawn rather than laid out — so a
 * two-column row needs no intrinsic height to rule between its halves.
 */
internal fun Modifier.edge(color: Color, end: Boolean, width: Dp = 1.dp): Modifier = drawBehind {
    val stroke = width.toPx()
    val x = if (end) size.width - stroke / 2 else stroke / 2
    drawLine(color, Offset(x, 0f), Offset(x, size.height), strokeWidth = stroke)
}

private val DATE_WIDTH = 66.dp
