package com.zillit.desktop.feature.costumesetsync.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.feature.costumesetsync.domain.BudgetGroup
import com.zillit.desktop.feature.costumesetsync.domain.BudgetModel
import com.zillit.desktop.feature.costumesetsync.domain.Rec
import com.zillit.desktop.feature.costumesetsync.ui.t

// The web's `.csync-bs` columns: Account 96 · Description (rest) · Amt 70 · Unit 80 · X 50 · Rate 100 · Subtotal 120 · actions 130.
private val ACCOUNT_W = 96.dp
private val AMT_W = 70.dp
private val UNIT_W = 80.dp
private val X_W = 50.dp
private val RATE_W = 100.dp
private val SUB_W = 120.dp
private val ACTIONS_W = 130.dp
private val CELL_PAD = 10.dp
private val ROW_PAD = 6.dp
private val SHEET_MIN = 320.dp
private val SHEET_CHROME = 240.dp
private const val FONT = 13

/**
 * The budget read the way a production budget prints (Movie Magic) — the web's `.csync-bs` table: a header per
 * group (a scene, a character, a department), a block per account code (code in an amber tag, name in capitals),
 * bold "Name:" rows for who is paid, then Amt · Unit · X · Rate · Subtotal lines (every other one shaded) and a
 * Total per account. The sheet scrolls in its own box under a pinned header. A row click edits the line when
 * [onEdit] is given; [lineActions] adds a trailing cell of controls per line.
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
    val actions = lineActions != null
    fun total(lines: List<Rec>) = BudgetModel.sumByCurrency(lines, currency)
    val windowHeight = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() }
    Column(modifier.fillMaxWidth()) {
        Header(actions)
        Column(Modifier.fillMaxWidth().heightIn(max = maxOf(SHEET_MIN, windowHeight - SHEET_CHROME)).verticalScroll(rememberScrollState())) {
            groups.forEach { g ->
                GroupRow(g.title)
                BudgetModel.accountsOf(g.lines).forEach { a ->
                    AccountRow(a.code, a.name.ifBlank { null })
                    BudgetModel.payeesOf(a.lines).forEach { (payee, lines) ->
                        if (payee.isNotEmpty()) NameRow("${t("csync_budget_name_prefix")} $payee")
                        lines.forEachIndexed { i, l -> LineRow(l, currency, onEdit, lineActions, shaded = i % 2 == 1) }
                    }
                    TotalRow(t("csync_budget_total"), total(a.lines), actions)
                }
                SummaryRow("${t("csync_budget_total")} · ${g.title}", total(g.lines), actions, grand = false)
            }
            if (groups.size > 1) SummaryRow(t("csync_budget_grand_total"), total(groups.flatMap { it.lines }), actions, grand = true)
        }
    }
}

private fun Modifier.rule(color: Color, top: Dp = 0.dp, bottom: Dp = 1.dp, topColor: Color = color): Modifier = drawBehind {
    if (bottom > 0.dp) drawLine(color, Offset(0f, size.height - bottom.toPx() / 2), Offset(size.width, size.height - bottom.toPx() / 2), bottom.toPx())
    if (top > 0.dp) drawLine(topColor, Offset(0f, top.toPx() / 2), Offset(size.width, top.toPx() / 2), top.toPx())
}

@Composable
private fun bs(weight: FontWeight = FontWeight.Normal, size: Int = FONT, mono: Boolean = false) =
    ZillitTheme.typography.bodyMedium.copy(
        fontSize = size.sp,
        lineHeight = (size + 6).sp,
        fontWeight = weight,
        fontFamily = if (mono) FontFamily.Monospace else ZillitTheme.typography.bodyMedium.fontFamily,
    )

@Composable
private fun Header(actions: Boolean) {
    val colors = ZillitTheme.colors
    val head = ZillitTheme.typography.labelSmall.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.04.em)
    @Composable
    fun RowScope.th(text: String, w: Dp?, end: Boolean = false) {
        ZillitText(
            text.uppercase(),
            (if (w != null) Modifier.width(w) else Modifier.weight(1f)).padding(horizontal = CELL_PAD),
            style = head, color = colors.textMuted, maxLines = 1, textAlign = if (end) TextAlign.End else null,
        )
    }
    Row(Modifier.fillMaxWidth().background(colors.surfaceSunken).rule(colors.border).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        th(t("csync_budget_account"), ACCOUNT_W)
        th(t("csync_field_description"), null)
        th(t("csync_budget_amt"), AMT_W, true)
        th(t("csync_budget_unit"), UNIT_W)
        th("X", X_W, true)
        th(t("csync_budget_rate"), RATE_W, true)
        th(t("csync_budget_subtotal"), SUB_W, true)
        if (actions) Box(Modifier.width(ACTIONS_W))
    }
}

/** A `<tr>`: [code] cell shaded on its own, then the cells; each row carries the table's light bottom rule. */
@Composable
private fun SheetRow(
    modifier: Modifier = Modifier,
    background: Color = Color.Unspecified,
    codeCell: @Composable () -> Unit = {},
    content: @Composable RowScope.() -> Unit,
) {
    val colors = ZillitTheme.colors
    Row(
        modifier.fillMaxWidth().height(IntrinsicSize.Min).rule(colors.border.copy(alpha = LIGHT_RULE)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(ACCOUNT_W).fillMaxHeight().background(colors.surfaceSunken).padding(horizontal = CELL_PAD, vertical = ROW_PAD), contentAlignment = Alignment.CenterStart) { codeCell() }
        Row(Modifier.weight(1f).fillMaxHeight().then(if (background != Color.Unspecified) Modifier.background(background) else Modifier), verticalAlignment = Alignment.CenterVertically, content = content)
    }
}

private const val LIGHT_RULE = 0.55f

@Composable
private fun GroupRow(title: String) {
    val colors = ZillitTheme.colors
    Box(Modifier.fillMaxWidth().background(colors.surface).rule(colors.border.copy(alpha = LIGHT_RULE), top = 2.dp, topColor = colors.textPrimary).padding(start = CELL_PAD, end = CELL_PAD, top = 14.dp, bottom = ROW_PAD + 2.dp)) {
        ZillitText(title.uppercase(), style = bs(FontWeight.ExtraBold).copy(letterSpacing = 0.02.em), maxLines = 1)
    }
}

@Composable
private fun AccountRow(code: String, name: String?) {
    val colors = ZillitTheme.colors
    SheetRow(codeCell = {
        if (code.isNotEmpty()) {
            ZillitText(
                code,
                Modifier.clip(RoundedCornerShape(3.dp)).background(colors.accent).padding(horizontal = 5.dp, vertical = 1.dp),
                style = bs(FontWeight.SemiBold, 12, mono = true),
                color = Color(0xFF3D2B00),
                maxLines = 1,
            )
        }
    }) {
        Box(Modifier.weight(1f).padding(horizontal = CELL_PAD, vertical = ROW_PAD)) {
            if (name != null) {
                ZillitText(name.uppercase(), style = bs(FontWeight.Bold).copy(letterSpacing = 0.02.em), maxLines = 1)
            } else {
                ZillitText(t("csync_budget_no_account_name"), style = bs(), color = colors.textMuted)
            }
        }
    }
}

@Composable
private fun NameRow(text: String) {
    SheetRow {
        Box(Modifier.weight(1f).padding(horizontal = CELL_PAD, vertical = ROW_PAD)) { ZillitText(text, style = bs(FontWeight.Bold), maxLines = 1) }
    }
}

@Composable
private fun LineRow(l: Rec, currency: String, onEdit: ((Rec) -> Unit)?, actions: (@Composable (Rec) -> Unit)?, shaded: Boolean) {
    val colors = ZillitTheme.colors
    val q = if (l.has("quantity")) l.double("quantity") else null
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val fill = when {
        onEdit != null && hovered -> colors.infoSoft
        shaded -> colors.surfaceSunken
        else -> Color.Unspecified
    }
    @Composable
    fun RowScope.td(text: String, w: Dp, mono: Boolean = false, end: Boolean = false) {
        ZillitText(
            text,
            Modifier.width(w).padding(horizontal = CELL_PAD, vertical = ROW_PAD),
            style = bs(mono = mono), maxLines = 1, textAlign = if (end) TextAlign.End else null,
        )
    }
    SheetRow(Modifier.hoverable(source).then(if (onEdit != null) Modifier.clickable { onEdit(l) } else Modifier), fill) {
        val blank = l.str("description").isBlank()
        ZillitText(
            if (blank) l.first("account_name", "payee").ifBlank { "—" } else l.str("description"),
            Modifier.weight(1f).padding(horizontal = CELL_PAD, vertical = ROW_PAD),
            style = bs(),
            color = if (blank) colors.textMuted else colors.textPrimary,
            maxLines = 2,
        )
        td(BudgetModel.fmtNum(q), AMT_W, mono = true, end = true)
        td(l.str("unit"), UNIT_W)
        td(if (q != null) BudgetModel.fmtNum(if (l.has("multiplier")) l.double("multiplier") else 1.0) else "", X_W, mono = true, end = true)
        td(BudgetModel.fmtNum(if (l.has("rate")) l.double("rate") else null), RATE_W, mono = true, end = true)
        td(BudgetModel.fmtAmount(l.double("amount"), l.str("currency").ifEmpty { currency }), SUB_W, mono = true, end = true)
        actions?.let { Row(Modifier.width(ACTIONS_W).padding(horizontal = 4.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) { it(l) } }
    }
}

/** The account's Total: bold, shaded, with a rule above it. */
@Composable
private fun TotalRow(label: String, amount: String, actions: Boolean) {
    val colors = ZillitTheme.colors
    SheetRow(Modifier.drawBehind { drawLine(colors.border, Offset(ACCOUNT_W.toPx(), 0.5f), Offset(size.width, 0.5f), 1.dp.toPx()) }, colors.surfaceSunken) {
        ZillitText(label, Modifier.weight(1f).padding(horizontal = CELL_PAD, vertical = ROW_PAD), style = bs(FontWeight.Bold), maxLines = 1)
        ZillitText(amount, Modifier.width(SUB_W).padding(horizontal = CELL_PAD, vertical = ROW_PAD), style = bs(FontWeight.Bold), maxLines = 1, textAlign = TextAlign.End)
        if (actions) Box(Modifier.width(ACTIONS_W))
    }
}

/** A group's total (heavy rule above) or the grand total (inverted: surface text on the primary ink). */
@Composable
private fun SummaryRow(label: String, amount: String, actions: Boolean, grand: Boolean) {
    val colors = ZillitTheme.colors
    val fg = if (grand) colors.surface else colors.textPrimary
    val size = if (grand) 14 else FONT
    Row(
        Modifier.fillMaxWidth()
            .then(if (grand) Modifier.background(colors.textPrimary) else Modifier.drawBehind { drawLine(colors.textPrimary, Offset(0f, 0.5f), Offset(this.size.width, 0.5f), 1.dp.toPx()) })
            .padding(vertical = ROW_PAD),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(ACCOUNT_W))
        ZillitText(label, Modifier.weight(1f).padding(horizontal = CELL_PAD), style = bs(FontWeight.ExtraBold, size), color = fg, maxLines = 1)
        ZillitText(amount, Modifier.width(SUB_W).padding(horizontal = CELL_PAD), style = bs(FontWeight.ExtraBold, size), color = fg, maxLines = 1, textAlign = TextAlign.End)
        if (actions) Box(Modifier.width(ACTIONS_W))
    }
}
