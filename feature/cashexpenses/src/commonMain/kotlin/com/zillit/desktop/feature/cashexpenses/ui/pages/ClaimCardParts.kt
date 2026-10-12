package com.zillit.desktop.feature.cashexpenses.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDivider
import com.zillit.desktop.core.designsystem.component.ZillitNotice
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.cashexpenses.domain.CashTaxType
import com.zillit.desktop.feature.cashexpenses.domain.CashTrackingSet
import com.zillit.desktop.feature.cashexpenses.domain.Claim
import com.zillit.desktop.feature.cashexpenses.domain.ClaimLineItem
import com.zillit.desktop.feature.cashexpenses.domain.LineItemEditor
import com.zillit.desktop.feature.cashexpenses.domain.ProcessingRule
import com.zillit.desktop.feature.cashexpenses.domain.layersOf
import com.zillit.desktop.feature.cashexpenses.ui.money
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.round

// The read-only half of the web's `ClaimCard` (`ClaimCard.jsx`): the rule banners
// above a receipt and its line-items table, as the approval queue, audit, sign-off
// and ledger panes show them.

/**
 * The processing-rule banners over a receipt: a red query, a blue senior review
 * and an amber deduction, each with the rule's own description under it. A flag
 * the web draws no banner for is skipped.
 */
@Composable
internal fun ProcessingBanners(rules: List<ProcessingRule>, currency: String?, modifier: Modifier = Modifier) {
    val shown = rules.filter { it.flag in BANNER_FLAGS }
    if (shown.isEmpty()) return
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
        shown.forEach { rule ->
            ZillitNotice(
                text = listOf(ruleHeadline(rule) { money(it, currency) }, rule.description.trim())
                    .filter { it.isNotEmpty() }
                    .joinToString("\n"),
                tone = when (rule.flag) {
                    QUERY -> StatusTone.Rejected
                    REVIEW -> StatusTone.Progress
                    else -> StatusTone.Pending
                },
                icon = if (rule.flag == QUERY) ZillitIcons.Warning else ZillitIcons.Info,
            )
        }
    }
}

/**
 * A rule's banner sentence, built as the web builds it (`buildBannerHeadline`):
 * the rule's title with "rule" on the end, and the amount it fires at when it
 * has one — a deduction's is a percentage or an amount, a review's and a
 * query's the receipt amount above which they apply.
 */
internal fun ruleHeadline(rule: ProcessingRule, format: (Double) -> String): String {
    val name = rule.ruleName ?: str(S.desktop_ce_process_rule)
    val threshold = rule.thresholdValue
    return when (rule.flag) {
        DEDUCT -> if (threshold == null) {
            str(S.desktop_ce_process_rule_deduct, name)
        } else {
            val figure = if (rule.isPercentage) "${threshold.plain()}%" else format(threshold)
            str(S.desktop_ce_process_rule_deduct_amount, figure, name)
        }
        REVIEW -> if (threshold == null) {
            str(S.desktop_ce_process_rule_review, name)
        } else {
            str(S.desktop_ce_process_rule_review_amount, format(threshold), name)
        }
        else -> if (threshold == null) {
            str(S.desktop_ce_process_rule_query, name)
        } else {
            str(S.desktop_ce_process_rule_query_amount, format(threshold), name)
        }
    }
}

/**
 * The receipt's tax under its header, and its line items behind a "View Line
 * Items" toggle — the web shows both only to an approver, never as an editor.
 */
@Composable
internal fun ClaimLinesSection(
    claim: Claim,
    currency: String?,
    taxTypes: List<CashTaxType>,
    trackingSets: List<CashTrackingSet>,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
        if (!claim.taxType.isNullOrBlank()) {
            ZillitText(
                text = taxSummary(claim, currency),
                style = ZillitTheme.typography.numeric,
                color = ZillitTheme.colors.info,
                textAlign = TextAlign.End,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (claim.lineItems.isEmpty()) return@Column
        var open by remember(claim.id) { mutableStateOf(false) }
        ZillitButton(
            text = if (open) str(S.desktop_ce_hide_line_items) else str(S.desktop_ce_view_line_items),
            onClick = { open = !open },
            variant = ButtonVariant.Tertiary,
            size = ButtonSize.Small,
        )
        if (open) ClaimLinesTable(claim, currency, taxTypes, trackingSets)
    }
}

/** "Tax 20% · Net £8.33 · TAX £1.67 · Gross £10.00" — what the accountant extracted from the receipt. */
private fun taxSummary(claim: Claim, currency: String?): String = listOf(
    "${str(S.ah_lbl_vat)} ${(claim.taxRate ?: 0.0).plain()}%",
    "${str(S.desktop_net)} ${money(claim.netAmount, currency)}",
    "${str(S.ah_lbl_vat).uppercase()} ${money(claim.vatAmount, currency)}",
    "${str(S.desktop_gross)} ${money(claim.grossAmount, currency)}",
).joinToString(" · ")

/**
 * The receipt's lines — description, cost code, tax, price, amount and, on a
 * production that has tracking sets, Layers — with what they add up to against
 * the receipt in red when they differ (`ClaimCard.jsx:795-1050`).
 */
@Suppress("LongMethod") // The heading, the header row and a row per line.
@Composable
private fun ClaimLinesTable(
    claim: Claim,
    currency: String?,
    taxTypes: List<CashTaxType>,
    trackingSets: List<CashTrackingSet>,
) {
    val showLayers = trackingSets.any { it.active }
    val lineTotal = claim.lineItems.sumOf { it.total }
    val mismatch = abs(lineTotal - claim.grossAmount) > MISMATCH
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(ZillitTheme.colors.surfaceSunken, ZillitTheme.shapes.medium)
            .padding(ZillitTheme.spacing.md),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ZillitText(
                text = str(S.ah_line_items),
                style = ZillitTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            ZillitText(
                text = listOf(
                    "${money(lineTotal, currency)} / ${money(claim.grossAmount, currency)}",
                    if (mismatch) str(S.desktop_ce_lines_mismatch) else "",
                ).filter { it.isNotEmpty() }.joinToString(" "),
                style = ZillitTheme.typography.numeric,
                color = if (mismatch) ZillitTheme.colors.danger else ZillitTheme.colors.success,
            )
        }
        LineRow(
            cells = listOf(
                str(S.description), str(S.desktop_pc_cost_code), str(S.ah_lbl_vat), str(S.price), str(S.amount),
            ).map { it.uppercase() },
            layers = if (showLayers) str(S.desktop_layers).uppercase() else null,
            header = true,
        )
        claim.lineItems.forEach { line ->
            ZillitDivider()
            LineRow(
                cells = listOf(
                    line.description.ifBlank { "—" },
                    line.account?.takeIf { it.isNotBlank() } ?: "—",
                    taxCell(line, taxTypes),
                    money(line.unitPrice, currency),
                    money(line.total, currency),
                ),
                layers = if (showLayers) trackingSets.layersOf(line.trackingCodes).joinToString(", ") else null,
            )
        }
    }
}

/** "VAT · 20%", or an em dash for a line with no tax type. */
private fun taxCell(line: ClaimLineItem, taxTypes: List<CashTaxType>): String {
    val type = line.taxType?.takeIf { it.isNotBlank() } ?: return "—"
    val label = taxTypes.firstOrNull { it.identifier == type }?.label ?: type
    val rate = LineItemEditor.normaliseTaxRate(line.taxRate)
    return if (rate > 0) "$label · ${rate.plain()}%" else label
}

@Composable
private fun LineRow(cells: List<String>, layers: String?, header: Boolean = false) {
    val style = if (header) ZillitTheme.typography.labelSmall else ZillitTheme.typography.bodySmall
    val color = if (header) ZillitTheme.colors.textMuted else null
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        ZillitText(text = cells[0], style = style, color = color, modifier = Modifier.weight(DESCRIPTION_WEIGHT))
        cells.drop(1).forEach { cell ->
            ZillitText(
                text = cell,
                style = style,
                color = color,
                textAlign = TextAlign.End,
                modifier = Modifier.weight(FIGURE_WEIGHT),
            )
        }
        layers?.let { ZillitText(text = it, style = style, color = color, modifier = Modifier.weight(FIGURE_WEIGHT)) }
    }
}

/** A tax rate or a threshold the way JavaScript prints a number: 20, not 20.0. */
private fun Double.plain(): String = if (this == floor(this)) round(this).toLong().toString() else toString()

private const val QUERY = "query"
private const val REVIEW = "review"
private const val DEDUCT = "deduct"
private val BANNER_FLAGS = setOf(QUERY, REVIEW, DEDUCT)
private const val MISMATCH = 0.01
private const val DESCRIPTION_WEIGHT = 1.75f
private const val FIGURE_WEIGHT = 1f
