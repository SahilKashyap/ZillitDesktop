package com.zillit.desktop.feature.talentdeals.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.talentdeals.domain.TalentNumbers
import com.zillit.desktop.feature.talentdeals.domain.TalentWithholding
import com.zillit.desktop.feature.talentdeals.domain.WithholdingBasis
import com.zillit.desktop.feature.talentdeals.domain.WithholdingSummary

/**
 * What the production pays, what the performer receives and what is handed to
 * the tax authority — with the grossed-up lines shown at BOTH values, since
 * that is the part of the calculation nobody takes on trust.
 */
@Suppress("LongMethod", "CyclomaticComplexMethod") // A page laid out section by section.
@Composable
internal fun WithholdingBreakdown(summary: WithholdingSummary, symbol: String, authorised: Boolean,
    modifier: Modifier = Modifier) {
    val colors = ZillitTheme.colors
    val basis = summary.basis
    val rated = basis is WithholdingBasis.Rate
    val taxed = basis !is WithholdingBasis.None
    val grossedUp = summary.rows.any { it.grossedUp }
    val money = { value: Double -> TalentNumbers.formatMoney(value, symbol) }

    Column(
        modifier
            .fillMaxWidth()
            .padding(top = ZillitTheme.spacing.md)
            .clip(ZillitTheme.shapes.medium)
            .border(1.dp, colors.border, ZillitTheme.shapes.medium)
            .background(colors.surfaceSunken)
            .padding(ZillitTheme.spacing.md),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
            ZillitText(
                text = (if (taxed) str(S.desktop_td_bd_withholding) else str(S.ah_totals_section)).uppercase(),
                style = ZillitTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                color = colors.accentText,
            )
            when (basis) {
                is WithholdingBasis.Rate -> {
                    val rate = TalentNumbers.plain(basis.rate)
                    ZillitText(
                        text = if (authorised) str(S.desktop_td_bd_rate_preauth,
                            rate) else str(S.desktop_td_bd_rate_withholding, rate),
                        style = ZillitTheme.typography.labelSmall,
                        color = colors.textMuted,
                    )
                }
                is WithholdingBasis.Flat ->
                    ZillitText(str(S.desktop_td_bd_flat), style = ZillitTheme.typography.labelSmall,
                        color = colors.textMuted)
                WithholdingBasis.None -> Unit
            }
        }

        // The line / paid / taxed on / tax table.
        BreakdownRow(
            cells = buildList {
                add(str(S.desktop_line))
                add(str(S.desktop_paid))
                if (rated) add(str(S.desktop_td_bd_taxed_on))
                if (taxed) add(str(S.ah_lbl_vat))
            },
            header = true,
        )
        summary.rows.forEach { row ->
            val tags = buildList {
                if (row.grossedUp) add(str(S.desktop_td_bd_grossed_up))
                if (rated && !row.withheld) add(str(S.desktop_td_bd_not_taxable))
            }
            BreakdownRow(
                cells = buildList {
                    add(if (tags.isEmpty()) row.label else "${row.label}  ·  ${tags.joinToString(", ")}")
                    add(money(row.net))
                    if (rated) add(if (row.withheld) money(row.gross) else "—")
                    if (taxed) add(if (row.tax != 0.0) money(row.tax) else "—")
                },
            )
        }
        if (summary.rows.size > 2) {
            BreakdownRow(
                cells = buildList {
                    add(str(S.desktop_td_sec_expenses))
                    add(money(summary.expenses))
                    if (rated) add("")
                    if (taxed) add("")
                },
                emphasis = true,
            )
        }
        if (taxed) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border))
            BreakdownRow(
                cells = buildList {
                    add(str(S.desktop_td_bd_tax_due))
                    add("")
                    if (rated) add(money(summary.grossTotal))
                    add(money(summary.tax))
                },
                emphasis = true,
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xl)) {
            ZillitText(
                text = "${str(S.desktop_td_bd_cost)}  ${money(summary.cost)}",
                style = ZillitTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
            )
            ZillitText(
                text = "${str(S.desktop_td_bd_net)}  ${money(summary.net)}",
                style = ZillitTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
            )
        }

        if (basis is WithholdingBasis.Rate && grossedUp) {
            val rate = TalentNumbers.plain(basis.rate)
            val grossedHundred = TalentWithholding.grossUpAmount(HUNDRED, basis.rate)
            Hint(str(S.desktop_td_bd_gross_hint, money(HUNDRED), rate, money(grossedHundred)))
        }
        if (basis is WithholdingBasis.Flat) Hint(str(S.desktop_td_bd_flat_hint))
    }
}

@Composable
private fun BreakdownRow(cells: List<String>, header: Boolean = false, emphasis: Boolean = false) {
    val colors = ZillitTheme.colors
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
        cells.forEachIndexed { index, cell ->
            ZillitText(
                text = if (header) cell.uppercase() else cell,
                style = when {
                    header -> ZillitTheme.typography.columnHeader
                    emphasis -> ZillitTheme.typography.numeric.copy(fontWeight = FontWeight.SemiBold)
                    index == 0 -> ZillitTheme.typography.bodyMedium
                    else -> ZillitTheme.typography.numeric
                },
                color = if (header) colors.textMuted else colors.textPrimary,
                textAlign = if (index == 0) TextAlign.Start else TextAlign.End,
                modifier = Modifier.weight(if (index == 0) FIRST_COLUMN else 1f),
                maxLines = 1,
            )
        }
    }
}

@Composable
internal fun Hint(text: String, modifier: Modifier = Modifier, error: Boolean = false) {
    ZillitText(
        text = text,
        style = ZillitTheme.typography.bodySmall,
        color = if (error) ZillitTheme.colors.danger else ZillitTheme.colors.textMuted,
        maxLines = Int.MAX_VALUE,
        modifier = modifier,
    )
}

private const val HUNDRED = 100.0
private const val FIRST_COLUMN = 2f
