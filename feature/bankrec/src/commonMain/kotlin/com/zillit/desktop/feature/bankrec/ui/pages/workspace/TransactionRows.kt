package com.zillit.desktop.feature.bankrec.ui.pages.workspace

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ZillitDivider
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.bankrec.domain.BankRecFormat
import com.zillit.desktop.feature.bankrec.domain.BankRow
import com.zillit.desktop.feature.bankrec.domain.FxDetail
import com.zillit.desktop.feature.bankrec.domain.LedgerRow
import com.zillit.desktop.feature.bankrec.domain.RowTag
import com.zillit.desktop.feature.bankrec.domain.RowTagKind
import com.zillit.desktop.feature.bankrec.domain.SuggestionBar
import com.zillit.desktop.feature.bankrec.domain.SuggestionKind
import com.zillit.desktop.feature.bankrec.domain.TxnStatus
import com.zillit.desktop.feature.bankrec.ui.components.BankRecIcons
import com.zillit.desktop.feature.bankrec.ui.components.BrLinkButton
import com.zillit.desktop.feature.bankrec.ui.components.BrTone
import com.zillit.desktop.feature.bankrec.ui.components.bg
import com.zillit.desktop.feature.bankrec.ui.components.edge
import com.zillit.desktop.feature.bankrec.ui.components.eyebrow
import com.zillit.desktop.feature.bankrec.ui.components.fg
import com.zillit.desktop.feature.bankrec.ui.components.mono
import com.zillit.desktop.feature.bankrec.ui.pages.tone

/** How a row is lit: picked, linked to what is picked, or being quick-added. */
internal enum class RowLight { None, Selected, QuickAdding }

/** What a bank row's bars and bubble can ask for. */
internal class BankRowActions(
    val onClick: () -> Unit,
    val onQuickAdd: () -> Unit,
    val onManualMatch: () -> Unit,
    val onAccept: (invoiceId: String) -> Unit,
    val onViewInvoice: (invoiceId: String) -> Unit,
    val onReviewFraud: () -> Unit,
    val onPostFx: () -> Unit,
)

/**
 * A line off the statement, as the web draws it: a status-coloured edge, a
 * tick when matched, the date, the payee and its reference, the amount in the
 * colour of its state, and the bars under it that say what to do next.
 */
@Composable
internal fun BankRowItem(row: BankRow, light: RowLight, fallbackCurrency: String, actions: BankRowActions) {
    val colors = ZillitTheme.colors
    val status = row.status
    val fraud = status == TxnStatus.FraudFlag
    Column(
        Modifier.fillMaxWidth()
            .background(if (fraud) colors.dangerSoft.copy(alpha = 0.45f) else Color.Transparent),
    ) {
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            Box(Modifier.width(3.dp).fillMaxHeight().background(status.tone.fg()))
            Column(Modifier.weight(1f)) {
                MainLine(
                    light = light,
                    matched = status == TxnStatus.Matched,
                    date = BankRecFormat.statementDay(row.txn.transactionDateMillis),
                    title = row.title,
                    reference = row.reference,
                    tags = row.tags,
                    amount = BankRecFormat.signedMoney(row.amount, row.amountCurrency ?: fallbackCurrency),
                    amountColor = amountColorFor(row.amount, status, light),
                    status = status,
                    onClick = actions.onClick,
                )
                Bars(row, fallbackCurrency, actions)
            }
        }
        ZillitDivider()
    }
}

/** A ledger entry: the same line, with no bars — the ledger is answered, not actioned. */
@Composable
internal fun LedgerRowItem(row: LedgerRow, light: RowLight, fallbackCurrency: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            Box(Modifier.width(3.dp).fillMaxHeight().background(row.status.tone.fg()))
            MainLine(
                light = light,
                matched = row.entry.isMatched,
                date = BankRecFormat.statementDay(row.entry.displayDateMillis),
                title = row.title,
                reference = row.reference.ifBlank { BankRecFormat.DASH },
                tags = emptyList(),
                amount = row.amount?.let { BankRecFormat.signedMoney(it, row.entry.currency ?: fallbackCurrency) }
                    ?: BankRecFormat.DASH,
                amountColor = row.amount?.let { amountColorFor(it, row.status, light) }
                    ?: ZillitTheme.colors.textDisabled,
                status = row.status,
                onClick = onClick,
                modifier = Modifier.weight(1f),
            )
        }
        ZillitDivider()
    }
}

@Suppress("LongParameterList") // The line's parts, already resolved by the row above it.
@Composable
private fun MainLine(
    light: RowLight,
    matched: Boolean,
    date: String,
    title: String,
    reference: String,
    tags: List<RowTag>,
    amount: String,
    amountColor: Color,
    status: TxnStatus,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = ZillitTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val background by animateColorAsState(
        when (light) {
            RowLight.QuickAdding -> colors.infoSoft
            RowLight.Selected -> colors.accentSoft
            RowLight.None -> if (hovered) colors.surfaceHover else Color.Transparent
        },
    )
    val emphasis = when (light) {
        RowLight.QuickAdding -> colors.info
        RowLight.Selected -> colors.accentText
        RowLight.None -> null
    }
    Row(
        modifier = modifier.fillMaxWidth().background(background)
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        MatchTick(matched)
        ZillitText(
            date,
            style = mono(10.5.sp, if (light == RowLight.None) FontWeight.Normal else FontWeight.SemiBold),
            color = emphasis ?: colors.textMuted,
            maxLines = 1,
            // "05 Apr 25" — the web adds the year for a line from another year.
            modifier = Modifier.width(62.dp),
        )
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                ZillitText(
                    title,
                    style = ZillitTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                    color = emphasis ?: colors.textPrimary,
                    maxLines = 1,
                    modifier = Modifier.weight(1f, fill = false),
                )
                tags.forEach { RowTagChip(it) }
            }
            ZillitText(reference, style = mono(10.5.sp), color = emphasis ?: colors.textMuted, maxLines = 1)
        }
        ZillitText(
            amount,
            style = mono(13.sp, if (light == RowLight.None) FontWeight.Normal else FontWeight.SemiBold),
            color = amountColor,
            maxLines = 1,
        )
        StatusGlyph(status)
    }
}

@Composable
private fun amountColorFor(amount: Double, status: TxnStatus, light: RowLight): Color {
    val c = ZillitTheme.colors
    return when {
        light == RowLight.QuickAdding -> c.info
        light == RowLight.Selected -> c.accentText
        amount > 0 -> c.success
        status == TxnStatus.Unmatched -> c.danger
        status == TxnStatus.Suggested -> c.warning
        status == TxnStatus.Fx -> c.teal
        else -> c.textPrimary
    }
}

/** A small square, ticked green when the line is reconciled. */
@Composable
private fun MatchTick(checked: Boolean) {
    val colors = ZillitTheme.colors
    Box(
        modifier = Modifier.size(13.dp).clip(RoundedCornerShape(3.dp))
            .background(if (checked) colors.success else colors.surface)
            .border(1.dp, if (checked) colors.success else colors.border, RoundedCornerShape(3.dp)),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) ZillitIcon(ZillitIcons.Check, tint = colors.textOnAccent, size = 9.dp)
    }
}

/** The status as a filled disc with its glyph — a flag pulses red instead. */
@Composable
private fun StatusGlyph(status: TxnStatus) {
    val colors = ZillitTheme.colors
    if (status == TxnStatus.FraudFlag) {
        ZillitIcon(ZillitIcons.Warning, tint = colors.danger, size = 15.dp)
        return
    }
    val glyph = when (status) {
        TxnStatus.Matched -> ZillitIcons.Check
        TxnStatus.Suggested -> BankRecIcons.Question
        TxnStatus.Unmatched -> BankRecIcons.Exclaim
        TxnStatus.Fx -> BankRecIcons.Coin
        TxnStatus.FraudFlag -> ZillitIcons.Warning
    }
    Box(
        Modifier.size(17.dp).clip(CircleShape).background(status.tone.fg()),
        contentAlignment = Alignment.Center,
    ) {
        ZillitIcon(glyph, tint = colors.textOnAccent, size = 11.dp)
    }
}

@Composable
private fun RowTagChip(tag: RowTag) {
    val tone = when (tag.kind) {
        RowTagKind.Fraud -> BrTone.Red
        RowTagKind.Actioned -> if (tag.label == "ACCEPTED") BrTone.Green else BrTone.Gray
        RowTagKind.Currency -> BrTone.Teal
    }
    Box(Modifier.clip(RoundedCornerShape(3.dp)).background(tone.bg()).padding(horizontal = 4.dp, vertical = 1.dp)) {
        ZillitText(tag.label, style = mono(8.5.sp, FontWeight.Bold), color = tone.fg(), maxLines = 1)
    }
}

/** The bars and the FX bubble under a bank line, inset and edged as the web's are. */
@Composable
private fun Bars(row: BankRow, fallbackCurrency: String, actions: BankRowActions) {
    val bars = listOfNotNull(row.suggestion, row.matchSuggestion)
    if (bars.isEmpty() && row.fx == null) return
    Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 6.dp)) {
        bars.forEach { bar -> SuggestionStrip(bar, row, actions) }
        row.fx?.let { FxBubble(it, row.amountCurrency ?: fallbackCurrency, actions.onPostFx) }
    }
}

@Suppress("CyclomaticComplexMethod") // One bar per kind of suggestion the web draws.
@Composable
private fun SuggestionStrip(bar: SuggestionBar, row: BankRow, actions: BankRowActions) {
    val tone = when (bar.kind) {
        SuggestionKind.Match -> BrTone.Amber
        SuggestionKind.Info -> BrTone.Blue
        SuggestionKind.Fraud -> BrTone.Red
        SuggestionKind.Accepted -> BrTone.Green
    }
    val icon = when (bar.kind) {
        SuggestionKind.Fraud -> ZillitIcons.Shield
        SuggestionKind.Match -> ZillitIcons.BarChart
        SuggestionKind.Info -> ZillitIcons.Info
        SuggestionKind.Accepted -> ZillitIcons.Check
    }
    val actionColor = if (bar.kind == SuggestionKind.Match) ZillitTheme.colors.success else tone.fg()
    Row(
        modifier = Modifier.fillMaxWidth().background(tone.bg())
            .border(1.dp, tone.edge(), RoundedCornerShape(0.dp))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        ZillitIcon(icon, tint = tone.fg(), size = 11.dp)
        ZillitText(
            bar.text,
            style = ZillitTheme.typography.labelSmall,
            color = tone.fg(),
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        val firstMatched = row.txn.matchedInvoiceIds.firstOrNull()
        if (bar.viewsInvoice && bar.invoiceId != null) {
            BrLinkButton("View ›", ZillitTheme.colors.info, { actions.onViewInvoice(bar.invoiceId) })
        }
        bar.action?.let { label ->
            BrLinkButton("$label ›", actionColor, {
                when {
                    bar.kind == SuggestionKind.Info -> actions.onQuickAdd()
                    bar.kind == SuggestionKind.Fraud -> actions.onReviewFraud()
                    bar.viewsInvoice && bar.invoiceId != null -> actions.onAccept(bar.invoiceId)
                    firstMatched != null -> actions.onAccept(firstMatched)
                }
            })
        }
        bar.secondAction?.let { label -> BrLinkButton("$label ›", actionColor, actions.onManualMatch) }
    }
}

/**
 * A foreign payment's figures: the invoice in its own currency, the two rates,
 * and the gain or loss — which is a home-leg figure, so it is in the
 * statement's currency, never the foreign one.
 */
@Composable
private fun FxBubble(fx: FxDetail, homeCurrency: String, onPost: () -> Unit) {
    val colors = ZillitTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth().background(BrTone.Teal.bg().copy(alpha = 0.6f))
            .border(1.dp, BrTone.Teal.edge(), RoundedCornerShape(0.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        FxFigure(
            "Invoice (${fx.currency.ifBlank { "FX" }})",
            foreignMoney(fx.foreignAmount, fx.currency),
            colors.teal,
            Modifier.weight(1f),
        )
        FxFigure("Budget Rate", BankRecFormat.rate(fx.budgetRate), colors.textSecondary, Modifier.weight(1f))
        FxFigure("Bank Rate", BankRecFormat.rate(fx.bankRate), colors.textSecondary, Modifier.weight(1f))
        FxFigure(
            if (fx.isGain) "FX Gain" else "FX Loss",
            BankRecFormat.signedMoney(fx.gain, homeCurrency),
            if (fx.isGain) colors.success else colors.danger,
            Modifier.weight(1f),
        )
        if (fx.varianceId.isNotBlank()) {
            if (fx.isPosted) {
                ZillitText("Posted ✓", style = mono(11.sp, FontWeight.SemiBold), color = colors.success)
            } else {
                BrLinkButton("Post ›", colors.teal, onPost)
            }
        }
    }
}

/** A foreign amount, always positive, in its own currency. */
private fun foreignMoney(amount: Double, currency: String): String = BankRecFormat.plainMoney(
    amount,
    currency.ifBlank { null },
)

@Composable
private fun FxFigure(label: String, value: String, color: Color, modifier: Modifier) {
    Column(modifier) {
        ZillitText(label.uppercase(), style = eyebrow(9.sp), color = ZillitTheme.colors.textMuted, maxLines = 1)
        ZillitText(value, style = mono(12.5.sp, FontWeight.Medium), color = color, maxLines = 1)
    }
}
