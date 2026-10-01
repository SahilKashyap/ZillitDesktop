package com.zillit.desktop.feature.costumesetsync.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ZillitDivider
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.feature.costumesetsync.domain.BudgetGroup
import com.zillit.desktop.feature.costumesetsync.domain.BudgetModel
import com.zillit.desktop.feature.costumesetsync.domain.Rec
import com.zillit.desktop.feature.costumesetsync.ui.t

private val COLS = listOf(0.9f, 3.2f, 0.7f, 0.8f, 0.5f, 1f, 1.3f)
private val COLS_WITH_ACTIONS = COLS + 1.2f

/**
 * The budget read the way a production budget prints (Movie Magic): a header per group (a scene, a
 * character, a department), a block per account code with its title, "Name:" rows for who is paid,
 * then Amt · Unit · X · Rate · Subtotal lines and a Total per account. A row click edits the line
 * when [onEdit] is given; [lineActions] adds a trailing cell of controls per line.
 */
@Composable
internal fun BudgetSheet(
    groups: List<BudgetGroup>,
    currency: String,
    onEdit: ((Rec) -> Unit)?,
    modifier: Modifier = Modifier,
    empty: @Composable () -> Unit = {},
    lineActions: (@Composable (Rec) -> Unit)? = null,
) {
    if (groups.isEmpty()) {
        empty()
        return
    }
    val weights = if (lineActions != null) COLS_WITH_ACTIONS else COLS
    fun total(lines: List<Rec>) = BudgetModel.sumByCurrency(lines, currency)
    Header(weights, lineActions != null)
    groups.forEach { g ->
        Band(g.title)
        BudgetModel.accountsOf(g.lines).forEach { a ->
            Row(Modifier.fillMaxWidth().padding(horizontal = ZillitTheme.spacing.lg, vertical = ZillitTheme.spacing.xs), horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
                Cell(a.code, weights[0], mono = true, bold = true)
                Box(Modifier.weight(weights.drop(1).sum())) { ZillitText(a.name.ifBlank { t("csync_budget_no_account_name") }, style = bold(), maxLines = 1) }
            }
            BudgetModel.payeesOf(a.lines).forEach { (payee, lines) ->
                if (payee.isNotEmpty()) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = ZillitTheme.spacing.lg)) {
                        Cell("", weights[0])
                        ZillitText("${t("csync_budget_name_prefix")} $payee", Modifier.weight(weights.drop(1).sum()), style = ZillitTheme.typography.bodySmall, color = ZillitTheme.colors.textSecondary)
                    }
                }
                lines.forEach { l -> LineRow(l, currency, weights, onEdit, lineActions) }
            }
            TotalRow(weights, t("csync_budget_total"), total(a.lines), lineActions != null)
        }
        TotalRow(weights, "${t("csync_budget_total")} · ${g.title}", total(g.lines), lineActions != null, strong = true)
    }
    if (groups.size > 1) TotalRow(weights, t("csync_budget_grand_total"), total(groups.flatMap { it.lines }), lineActions != null, strong = true)
}

@Composable
private fun bold() = ZillitTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)

@Composable
private fun Header(weights: List<Float>, actions: Boolean) {
    Row(Modifier.fillMaxWidth().background(ZillitTheme.colors.surfaceSunken).padding(horizontal = ZillitTheme.spacing.lg, vertical = ZillitTheme.spacing.sm), horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
        val heads = listOf(t("csync_budget_account"), t("csync_field_description"), t("csync_budget_amt"), t("csync_budget_unit"), "X", t("csync_budget_rate"), t("csync_budget_subtotal"))
        heads.forEachIndexed { i, h ->
            ZillitText(h.uppercase(), Modifier.weight(weights[i]), style = ZillitTheme.typography.labelSmall, color = ZillitTheme.colors.textMuted, maxLines = 1, textAlign = if (i in RIGHT) TextAlign.End else null)
        }
        if (actions) Box(Modifier.weight(weights.last()))
    }
}

private val RIGHT = setOf(2, 4, 5, 6)

@Composable
private fun Band(title: String) {
    Row(Modifier.fillMaxWidth().background(ZillitTheme.colors.surfaceSelected).padding(horizontal = ZillitTheme.spacing.lg, vertical = ZillitTheme.spacing.sm)) {
        ZillitText(title, style = bold(), maxLines = 1)
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.Cell(text: String, weight: Float, mono: Boolean = false, bold: Boolean = false, end: Boolean = false) {
    var style = ZillitTheme.typography.bodyMedium
    if (mono) style = style.copy(fontFamily = FontFamily.Monospace)
    if (bold) style = style.copy(fontWeight = FontWeight.SemiBold)
    ZillitText(text, Modifier.weight(weight), style = style, maxLines = 1, textAlign = if (end) TextAlign.End else null)
}

@Composable
private fun LineRow(l: Rec, currency: String, weights: List<Float>, onEdit: ((Rec) -> Unit)?, actions: (@Composable (Rec) -> Unit)?) {
    val q = if (l.has("quantity")) l.double("quantity") else null
    Row(
        Modifier.fillMaxWidth().then(if (onEdit != null) Modifier.clickable { onEdit(l) } else Modifier)
            .padding(horizontal = ZillitTheme.spacing.lg, vertical = ZillitTheme.spacing.xs),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Cell("", weights[0])
        ZillitText(
            l.str("description").ifBlank { l.first("account_name", "payee").ifBlank { "—" } },
            Modifier.weight(weights[1]),
            style = ZillitTheme.typography.bodyMedium,
            color = if (l.str("description").isBlank()) ZillitTheme.colors.textMuted else ZillitTheme.colors.textPrimary,
            maxLines = 2,
        )
        Cell(BudgetModel.fmtNum(q), weights[2], mono = true, end = true)
        Cell(l.str("unit"), weights[3])
        Cell(if (q != null) BudgetModel.fmtNum(if (l.has("multiplier")) l.double("multiplier") else 1.0) else "", weights[4], mono = true, end = true)
        Cell(BudgetModel.fmtNum(if (l.has("rate")) l.double("rate") else null), weights[5], mono = true, end = true)
        Cell(BudgetModel.fmtAmount(l.double("amount"), l.str("currency").ifEmpty { currency }), weights[6], mono = true, end = true)
        actions?.let { Row(Modifier.weight(weights.last()), horizontalArrangement = Arrangement.End) { it(l) } }
    }
}

@Composable
private fun TotalRow(weights: List<Float>, label: String, amount: String, actions: Boolean, strong: Boolean = false) {
    ZillitDivider()
    Row(Modifier.fillMaxWidth().padding(horizontal = ZillitTheme.spacing.lg, vertical = ZillitTheme.spacing.xs), horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
        Cell("", weights[0])
        ZillitText(label, Modifier.weight(weights[1] + weights[2] + weights[3] + weights[4] + weights[5]), style = if (strong) bold() else ZillitTheme.typography.bodyMedium, maxLines = 1)
        Cell(amount, weights[6], mono = true, bold = true, end = true)
        if (actions) Box(Modifier.weight(weights.last()))
    }
}
