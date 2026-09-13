package com.zillit.desktop.feature.dealmemo.ui.pages.builder

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.feature.dealmemo.domain.authoring.DealForm
import com.zillit.desktop.feature.dealmemo.domain.authoring.RateResolve
import com.zillit.desktop.feature.dealmemo.domain.authoring.RateTables
import com.zillit.desktop.feature.dealmemo.domain.authoring.RuleAuthoring
import com.zillit.desktop.feature.dealmemo.domain.authoring.RuleSection
import com.zillit.desktop.feature.dealmemo.domain.rates.AgreementFormat
import com.zillit.desktop.feature.dealmemo.domain.rates.Js
import com.zillit.desktop.feature.dealmemo.domain.rates.RateFormat
import com.zillit.desktop.feature.dealmemo.ui.BuilderEvent
import com.zillit.desktop.feature.dealmemo.ui.DealMemoEvent
import com.zillit.desktop.feature.dealmemo.ui.builder.BuilderState
import com.zillit.desktop.feature.dealmemo.ui.components.DmType
import com.zillit.desktop.feature.dealmemo.ui.components.rememberHover
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * Overtime Structure: the deal's own pay rules — inherited or customised,
 * edited in the full-page grid — over the agreement's contracted hours,
 * overtimes, premiums and turnarounds. Starts closed.
 */
@Composable
internal fun OvertimeCard(builder: BuilderState, view: RatesView, onEvent: (DealMemoEvent) -> Unit) {
    var open by remember { mutableStateOf(false) }
    CollapsibleBlock("Overtime Structure", open, { open = !open }, tag = view.otLabel, tone = BuilderTone.Gold) {
        RefFrame {
            PayRulesRow(builder, view, onEvent)
            view.sections.overtime?.note?.let { NoteBar(it) }
            ContractedRates(builder, view)
            OvertimesTable(view)
            RuleTable("Premiums", view.sections.premiums, "Trigger", view.sym, rateColor = null)
            RuleTable("Turnarounds", view.sections.turnaround, "Condition", view.sym, rateColor = bp.teal)
        }
    }
}

/** "Pay rules for this deal": inherited verbatim, or customised — Reset once they moved since the save. */
@Composable
private fun PayRulesRow(builder: BuilderState, view: RatesView, onEvent: (DealMemoEvent) -> Unit) {
    val p = bp
    val form = builder.form
    val source = if (view.nonUnion) "project pay breakdown" else "agreement"
    Row(
        modifier = Modifier.fillMaxWidth().bottomRule(p.tableRule).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            ZillitText(text = "Pay rules for this deal", style = DmType.sans(12.sp, FontWeight.SemiBold), color = p.ink)
            ZillitText(
                text = if (form.flag("rulesCustomized")) {
                    "Customised for this deal — timecard pay uses these rates. The $source itself is unchanged."
                } else {
                    "Inheriting the ${if (view.nonUnion) "project pay breakdown" else "published agreement"} verbatim."
                },
                style = DmType.sans(10.5.sp).copy(lineHeight = 14.sp),
                color = p.muted,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        if (RuleAuthoring.changedSinceSave(form, builder.savedRules)) {
            SmallRuleButton("Reset", accent = false) { onEvent(BuilderEvent.ConfirmResetRules(open = true)) }
        }
        SmallRuleButton("Edit rules", accent = true) { onEvent(BuilderEvent.OpenRules) }
    }
}

@Composable
private fun SmallRuleButton(text: String, accent: Boolean, onClick: () -> Unit) {
    val p = bp
    val (source, hovered) = rememberHover()
    val shape = RoundedCornerShape(7.dp)
    val border = when {
        accent -> p.cta.copy(alpha = ACCENT_BORDER_ALPHA)
        hovered -> p.redBorder
        else -> p.cardBorder
    }
    val ink = when {
        accent -> p.gold
        hovered -> p.redHover
        else -> p.ink2
    }
    Box(
        modifier = Modifier
            .clip(shape)
            .background(if (accent && hovered) p.cta.copy(alpha = ACCENT_WASH_ALPHA) else Color.Transparent)
            .border(1.dp, border, shape)
            .hoverable(source)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) { ZillitText(text = text, style = DmType.sans(11.sp, FontWeight.SemiBold), color = ink, maxLines = 1) }
}

/** Basic Contracted Rates: the scale's day hours, and a special department's extra contracted hour. */
@Composable
private fun ContractedRates(builder: BuilderState, view: RatesView) {
    val hours = view.effective.daily?.hours?.takeIf(Js::truthy) ?: return
    val p = bp
    val form = builder.form
    val sym = view.sym
    val hourly = view.effective.hourly?.base?.let(Js::toNumber)
        ?: Js.toNumber(hours)?.takeIf { view.dayRate != 0.0 }?.let { view.dayRate / it }
    val hourlyText = hourly?.let { "$sym${RateFormat.groupAmount(it)}/hr" } ?: "—"
    SectionHeader("Basic Contracted Rates", note = null, topRule = view.sections.overtime?.note != null)
    HeaderRow(listOf("Description", "Contracted Hrs", "Multiplier", "Rate ($sym/hr)"))
    val special = specialDepartment(form, view.agreement)
    TableRow(last = special == null) {
        Cell { CellText("Contracted Hours", p.ink) }
        Cell { CellText("${Js.text(hours)}h", p.ink2, mono = true) }
        Cell { CellText("×1.0", p.gold, mono = true) }
        Cell { CellText(hourlyText, p.teal, mono = true) }
    }
    special?.let { (extraHours, multiplier) ->
        TableRow(last = true) {
            Cell {
                CellText("Additional Contracted Hour", p.ink)
                SubText("Special Department (${Js.text(hours)}+$extraHours)")
            }
            Cell { CellText("${extraHours}h", p.ink2, mono = true) }
            Cell { CellText("×$multiplier", p.gold, mono = true) }
            Cell { CellText(hourlyText, p.teal, mono = true) }
        }
    }
}

/** The special department's extra hour and its multiplier, when the role is one. */
private fun specialDepartment(form: DealForm, agreement: JsonObject?): Pair<String, String>? {
    val pact = agreement?.get("pact") as? JsonObject
    val matches = form.flag("pactSpecialDept") &&
        (pact?.get("special_depts") as? JsonArray).orEmpty().any { entry ->
            val row = entry as? JsonObject ?: return@any false
            val designation = row["designation_identifier"]
            Js.text(row["department_identifier"]) == form.text("department") &&
                (!Js.truthy(designation) || Js.text(designation) == form.text("designation"))
        }
    if (!matches) return null
    val extra = pact?.get("extra_contracted_hours") as? JsonObject
    val hours = extra?.get("hrs")?.takeUnless(Js::isNullish)?.let(Js::text) ?: "1"
    val multiplier = Js.toFixed(extra?.get("multiplier")?.takeUnless(Js::isNullish)?.let(Js::toNumber) ?: 1.0, 1)
    return hours to multiplier
}

@Composable
private fun OvertimesTable(view: RatesView) {
    val p = bp
    val sym = view.sym
    SectionHeader(
        "Overtimes",
        note = null,
        topRule = view.sections.overtime?.note != null || Js.truthy(view.effective.daily?.hours),
    )
    val lines = RateTables.overtimeLines(view.sections, sym)
    if (lines.isEmpty()) {
        ZillitText(
            text = if (view.agreementLoading) {
                "Loading overtime structure…"
            } else {
                "No overtime rows published for this agreement."
            },
            style = DmType.sans(12.sp).copy(fontStyle = FontStyle.Italic),
            color = p.muted,
            modifier = Modifier.padding(12.dp),
        )
        return
    }
    HeaderRow(listOf("Band", "Multiplier", "Min ($sym/hr)", "Max ($sym/hr)"))
    lines.forEachIndexed { index, line ->
        TableRow(last = index == lines.lastIndex) {
            Cell {
                CellText(line.band, p.ink)
                line.sub?.takeIf { it.isNotEmpty() }?.let { SubText(it) }
            }
            Cell { CellText(line.multiplier, if (line.enhancement) p.orange else p.gold, mono = true) }
            Cell { CellText(line.min, p.tealInk, mono = true) }
            Cell { CellText(line.max, p.tealInk, mono = true) }
        }
    }
}

/** Premiums or Turnarounds: description, when it applies, and what it pays. */
@Composable
private fun RuleTable(title: String, section: RuleSection?, trigger: String, sym: String, rateColor: Color?) {
    val rows = section?.rows.orEmpty()
    if (rows.isEmpty()) return
    val p = bp
    SectionHeader(title, note = section?.note, topRule = true)
    HeaderRow(listOf("Description", trigger, "Rate"))
    rows.forEachIndexed { index, row ->
        val applies = AgreementFormat.trigger(AgreementFormat.primaryTrigger(row))
        TableRow(last = index == rows.lastIndex) {
            Cell {
                CellText(row["label"]?.takeUnless(Js::isNullish)?.let(Js::text).orEmpty(), p.ink)
                row["note"]?.takeIf(Js::truthy)?.let { SubText(Js.text(it)) }
            }
            Cell {
                CellText(applies.main, p.ink2)
                if (applies.meta.isNotEmpty()) SubText(applies.meta)
            }
            Cell {
                val color = rateColor ?: if (Js.truthy(row["is_enhancement"])) p.orange else p.gold
                CellText(RateTables.compensation(row, sym), color, mono = true)
            }
        }
    }
}

/** Fringes & Employer Costs: the package's lines and what they add to a week. Starts closed. */
@Composable
internal fun FringesCard(view: RatesView, pkg: JsonObject) {
    var open by remember { mutableStateOf(false) }
    val p = bp
    val tag = pkg["label"]?.takeUnless(Js::isNullish)?.let(Js::text) ?: view.otLabel
    CollapsibleBlock("Fringes & Employer Costs", open, { open = !open }, tag = tag, tone = BuilderTone.Red) {
        RefFrame {
            HeaderRow(listOf("Cost", "%", "Flat", "Basis", "Type"))
            val lines = RateTables.fringeLines(pkg, view.hpMode)
            lines.forEachIndexed { index, line ->
                TableRow(last = index == lines.lastIndex) {
                    Cell { CellText(line.cost, p.ink2) }
                    Cell { CellText(line.percent, p.gold, mono = true) }
                    Cell { CellText(line.flat, p.tealInk, mono = true) }
                    Cell { CellText(line.basis, p.muted) }
                    Cell { CellText(line.type, p.muted) }
                }
            }
            FringeTotals(view, pkg)
            pkg["note"]?.takeIf(Js::truthy)?.let { note ->
                Box(
                    Modifier
                        .fillMaxWidth()
                        .background(p.tableHead)
                        .topRule(p.tableRule)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    ZillitText(text = Js.text(note), style = DmType.sans(10.sp), color = p.muted)
                }
            }
        }
    }
}

@Composable
private fun FringeTotals(view: RatesView, pkg: JsonObject) {
    val p = bp
    val form = view.form
    val weekly =
        Js.parseFloat(form["weeklyRate"])?.takeIf { it != 0.0 } ?: (RateResolve.number(form["dayRate"]) * DAYS_PER_WEEK)
    val totals = RateTables.fringeTotals(weekly, RateTables.items(pkg), view.hpMode)
    val hasRate = weekly > 0
    fun money(value: Double) = "${view.sym}${RateFormat.groupAmount(value)} / wk"
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(p.tableHead)
            .topRule(p.tableRule)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ZillitText(
                text = "Estimated total fringe cost (weekly)",
                style = DmType.sans(12.sp),
                color = p.ink2,
                modifier = Modifier.weight(1f),
            )
            ZillitText(text = if (hasRate) money(totals.total) else "—", style = DmType.mono(13.sp), color = p.orange)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            ZillitText(
                text = "Total weekly cost to production",
                style = DmType.sans(12.sp, FontWeight.SemiBold),
                color = p.ink,
                modifier = Modifier.weight(1f),
            )
            ZillitText(
                text = if (hasRate) money(weekly + totals.total) else "—",
                style = DmType.mono(14.sp, FontWeight.Bold),
                color = p.gold,
            )
        }
    }
}

/** The agreement's own allowances — travel, idle days, ICP. Starts closed. */
@Composable
internal fun AllowancesCard(view: RatesView, section: RuleSection) {
    var open by remember { mutableStateOf(false) }
    val p = bp
    CollapsibleBlock("Allowances", open, { open = !open }, tag = view.otLabel, tone = BuilderTone.Teal) {
        RefFrame {
            section.note?.let { NoteBar(it) }
            HeaderRow(listOf("Fee", "Rate"))
            section.rows.forEachIndexed { index, row ->
                TableRow(last = index == section.rows.lastIndex) {
                    Cell {
                        CellText(row["label"]?.takeUnless(Js::isNullish)?.let(Js::text).orEmpty(), p.ink)
                        row["note"]?.takeIf(Js::truthy)?.let { SubText(Js.text(it)) }
                    }
                    Cell { CellText(RateTables.compensation(row, view.sym), p.tealInk, mono = true) }
                }
            }
        }
    }
}

/** How rates are paid on travel and rest days. Starts open. */
@Composable
internal fun RateConditionsCard(form: DealForm, ops: FormOps) {
    var open by remember { mutableStateOf(true) }
    CollapsibleBlock("Conditions", open, { open = !open }) {
        ToggleRow(
            "Travel day paid at full rate",
            null,
            form.flag("travelDayFull"),
            { ops.set("travelDayFull", !form.flag("travelDayFull")) },
        )
        ToggleRow(
            "Rest day worked — double time",
            null,
            form.flag("restDayDouble"),
            { ops.set("restDayDouble", !form.flag("restDayDouble")) },
        )
    }
}

// -- the reference-table kit ------------------------------------------------------------------

/** A card whose whole header opens and closes it, the chevron turning with it. */
@Composable
internal fun CollapsibleBlock(
    title: String,
    open: Boolean,
    onToggle: () -> Unit,
    tag: String? = null,
    tone: BuilderTone = BuilderTone.Dim,
    content: @Composable ColumnScope.() -> Unit,
) {
    val p = bp
    val (source, hovered) = rememberHover()
    val turn by animateFloatAsState(if (open) QUARTER_TURN else 0f, tween(CHEVRON_MILLIS))
    Column(Modifier.fillMaxWidth().padding(bottom = 14.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(if (hovered) p.chipHover else Color.Transparent)
                .hoverable(source)
                .clickable(interactionSource = source, indication = null, onClick = onToggle)
                .pointerHoverIcon(PointerIcon.Hand)
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ZillitText(
                text = title,
                style = DmType.sans(13.5.sp, FontWeight.Bold),
                color = p.ink2,
                modifier = Modifier.weight(1f),
            )
            tag?.takeIf { it.isNotEmpty() }?.let { BuilderTag(it, tone) }
            ZillitText(text = "▶", style = DmType.sans(10.sp), color = p.muted, modifier = Modifier.rotate(turn))
        }
        if (open) Column(Modifier.fillMaxWidth().padding(top = 8.dp), content = content)
    }
}

/** The tables' shared frame: one hairline box, clipped, so header bands and rules meet its edges. */
@Composable
private fun RefFrame(content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Column(Modifier.fillMaxWidth().clip(shape).border(1.dp, bp.hairline, shape), content = content)
}

@Composable
private fun NoteBar(text: String) {
    val p = bp
    Box(
        Modifier
            .fillMaxWidth()
            .background(p.tableHead)
            .bottomRule(p.tableRule)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        ZillitText(text = text, style = DmType.sans(10.sp).copy(fontStyle = FontStyle.Italic), color = p.muted)
    }
}

@Composable
private fun SectionHeader(label: String, note: String?, topRule: Boolean) {
    val p = bp
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(p.tableHead)
            .then(if (topRule) Modifier.topRule(p.tableRule) else Modifier)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZillitText(text = label.uppercase(), style = DmType.display(9.5.sp, FontWeight.Bold, 0.5.sp), color = p.muted)
        note?.let {
            ZillitText(
                text = it,
                style = DmType.sans(9.5.sp).copy(fontStyle = FontStyle.Italic),
                color = p.muted,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

@Composable
private fun HeaderRow(labels: List<String>) {
    val p = bp
    Row(Modifier.fillMaxWidth().background(p.tableHead).bottomRule(p.tableRule)) {
        labels.forEach { label ->
            ZillitText(
                text = label.uppercase(),
                style = DmType.display(9.5.sp, FontWeight.Bold, 0.5.sp),
                color = p.muted,
                modifier = Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun TableRow(last: Boolean, content: @Composable RowScope.() -> Unit) {
    val rule = bp.tableRule
    Row(
        modifier = Modifier.fillMaxWidth().then(if (last) Modifier else Modifier.bottomRule(rule)),
        verticalAlignment = Alignment.Top,
        content = content,
    )
}

@Composable
private fun RowScope.Cell(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 8.dp), content = content)
}

@Composable
private fun CellText(text: String, color: Color, mono: Boolean = false) {
    ZillitText(text = text, style = if (mono) DmType.mono(12.sp) else DmType.sans(12.sp), color = color)
}

@Composable
private fun SubText(text: String) {
    ZillitText(
        text = text,
        style = DmType.sans(10.sp).copy(lineHeight = 13.sp),
        color = bp.muted,
        modifier = Modifier.padding(top = 2.dp),
    )
}

private fun Modifier.bottomRule(color: Color): Modifier = drawBehind {
    val stroke = 1.dp.toPx()
    drawLine(color, Offset(0f, size.height - stroke / 2), Offset(size.width, size.height - stroke / 2), stroke)
}

private fun Modifier.topRule(color: Color): Modifier = drawBehind {
    val stroke = 1.dp.toPx()
    drawLine(color, Offset(0f, stroke / 2), Offset(size.width, stroke / 2), stroke)
}

private const val DAYS_PER_WEEK = 5
private const val QUARTER_TURN = 90f
private const val CHEVRON_MILLIS = 150
private const val ACCENT_BORDER_ALPHA = 0.4f
private const val ACCENT_WASH_ALPHA = 0.08f
