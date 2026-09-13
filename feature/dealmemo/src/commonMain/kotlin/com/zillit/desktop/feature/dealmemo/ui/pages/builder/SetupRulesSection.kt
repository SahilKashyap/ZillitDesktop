package com.zillit.desktop.feature.dealmemo.ui.pages.builder

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTooltip
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.dealmemo.domain.rates.AgreementFormat
import com.zillit.desktop.feature.dealmemo.domain.rates.Js
import com.zillit.desktop.feature.dealmemo.domain.rates.RateFormat
import com.zillit.desktop.feature.dealmemo.domain.rates.TerritoryCatalogue
import com.zillit.desktop.feature.dealmemo.domain.rules.AgreementRuleImport
import com.zillit.desktop.feature.dealmemo.domain.rules.BulkRuleRow
import com.zillit.desktop.feature.dealmemo.domain.rules.BulkRules
import com.zillit.desktop.feature.dealmemo.domain.rules.DayTypeRow
import com.zillit.desktop.feature.dealmemo.domain.rules.DayTypeRows
import com.zillit.desktop.feature.dealmemo.domain.rules.NonUnionPayBreakdown
import com.zillit.desktop.feature.dealmemo.domain.rules.RuleList
import com.zillit.desktop.feature.dealmemo.domain.rules.RuleOptions
import com.zillit.desktop.feature.dealmemo.domain.rules.RuleSummary
import com.zillit.desktop.feature.dealmemo.domain.rules.TriggerField
import com.zillit.desktop.feature.dealmemo.ui.BuilderEvent
import com.zillit.desktop.feature.dealmemo.ui.DealMemoEvent
import com.zillit.desktop.feature.dealmemo.ui.DealMemoUiState
import com.zillit.desktop.feature.dealmemo.ui.builder.BuilderState
import com.zillit.desktop.feature.dealmemo.ui.builder.DayTypesDraft
import com.zillit.desktop.feature.dealmemo.ui.builder.RuleImportState
import com.zillit.desktop.feature.dealmemo.ui.components.DmButton
import com.zillit.desktop.feature.dealmemo.ui.components.DmButtonStyle
import com.zillit.desktop.feature.dealmemo.ui.components.DmModal
import com.zillit.desktop.feature.dealmemo.ui.components.DmType
import com.zillit.desktop.feature.dealmemo.ui.components.TerritoryFlag
import com.zillit.desktop.feature.dealmemo.ui.components.rememberHover
import com.zillit.desktop.feature.dealmemo.ui.pages.rules.rp
import com.zillit.desktop.feature.dealmemo.ui.pages.rules.toneOf
import kotlinx.serialization.json.JsonObject

/**
 * Non-Union Pay Rules on a setup page: the project's rule book, read-only
 * with its Edit opening the full-page grid, and its Day Types beneath. Both
 * save straight to Production Setup.
 */
@Composable
internal fun NonUnionRulesSection(state: DealMemoUiState, builder: BuilderState, onEvent: (DealMemoEvent) -> Unit) {
    val settingsJson = state.projectSettings.view.json
    val rows = remember(settingsJson) {
        var seed = 0
        BulkRules.rows(NonUnionPayBreakdown.lists(NonUnionPayBreakdown.section(settingsJson)) { "preview-${seed++}" }) {
            "preview-${seed++}"
        }
    }
    Column {
        RulesPreview(rows) { onEvent(BuilderEvent.OpenProjectRules) }
        Box(Modifier.padding(top = 20.dp).fillMaxWidth().height(1.dp).background(bp.divider))
        builder.setupPage.dayTypes?.let { draft ->
            Box(Modifier.padding(top = 20.dp)) { DayTypesEditor(draft, onEvent) }
        }
    }
}

// -- the rules preview ------------------------------------------------------------------------------

/** `RulesPreviewTable`: the rule book as it is saved; any row opens the grid. */
@Composable
private fun RulesPreview(rows: List<BulkRuleRow>, onOpen: () -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            ZillitText(text = "Pay rules", style = DmType.sans(13.sp, FontWeight.Bold), color = rp.ink)
            CountPill(rows.size, Modifier.padding(start = 8.dp))
            Spacer(Modifier.weight(1f))
            PrimarySmallButton(if (rows.isEmpty()) "Add rules" else "Edit rules", onOpen)
        }
        if (rows.isEmpty()) {
            val (source, hovered) = rememberHover()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .dashedBorder(if (hovered) rp.cta else rp.border, 10.dp)
                    .hoverable(source)
                    .clickable(interactionSource = source, indication = null, onClick = onOpen)
                    .pointerHoverIcon(PointerIcon.Hand),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ZillitIcon(ZillitIcons.Add, size = 13.dp, tint = if (hovered) rp.cta else rp.ink3)
                ZillitText(
                    text = "Add overtime, premium & penalty rules",
                    style = DmType.sans(13.sp, FontWeight.SemiBold),
                    color = if (hovered) rp.cta else rp.ink2,
                    modifier = Modifier.padding(start = 6.dp),
                )
            }
            return@Column
        }
        val shape = RoundedCornerShape(10.dp)
        Box(
            Modifier.fillMaxWidth().clip(shape).border(1.dp, rp.border, shape).horizontalScroll(rememberScrollState()),
        ) {
            Column(Modifier.width(PREVIEW_WIDTH)) {
                PreviewHeader()
                rows.forEachIndexed { index, row -> PreviewRow(index + 1, row, onOpen) }
            }
        }
    }
}

@Composable
private fun PreviewHeader() {
    Row(
        Modifier
            .fillMaxWidth()
            .height(40.dp)
            .background(rp.surfaceAlt)
            .bottomRule(rp.border)
            .padding(start = 18.dp, end = 12.dp),
    ) {
        PREVIEW_COLUMNS.forEachIndexed { index, (title, width) ->
            PreviewCell(index, width) {
                ZillitText(
                    text = title.uppercase(),
                    style = DmType.sans(10.5.sp, FontWeight.ExtraBold, 0.3.sp),
                    color = rp.ink3,
                    maxLines = 2,
                    modifier = Modifier.padding(horizontal = 9.dp),
                )
            }
        }
    }
}

@Suppress("CyclomaticComplexMethod", "LongMethod")
@Composable
private fun PreviewRow(number: Int, row: BulkRuleRow, onOpen: () -> Unit) {
    val tone = toneOf(row.template?.list)
    val (source, hovered) = rememberHover()
    val amount = when {
        row.amount.isEmpty() -> "—"
        row.rateType == "multiplier" -> "×${row.amount}"
        row.rateType == "percentage" -> "${row.amount}%"
        else -> row.amount.toDoubleOrNull()?.let(RateFormat::groupAmountAuto) ?: "NaN"
    }
    ZillitTooltip(text = "Edit rules") {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(37.dp)
                .background(if (hovered) rp.surfaceHover else rp.surface)
                .bottomRule(rp.divider)
                .hoverable(source)
                .clickable(interactionSource = source, indication = null, onClick = onOpen)
                .pointerHoverIcon(PointerIcon.Hand),
        ) {
            Box(Modifier.width(4.dp).fillMaxHeight().background(tone.rail))
            Row(
                Modifier.fillMaxHeight().padding(start = 18.dp, end = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PreviewCell(0, PREVIEW_COLUMNS[0].second, center = true) {
                    Box(
                        Modifier.size(20.dp).clip(RoundedCornerShape(6.dp)).background(tone.bg),
                        contentAlignment = Alignment.Center,
                    ) {
                        ZillitText(
                            text = number.toString(),
                            style = DmType.mono(10.sp, FontWeight.Bold),
                            color = tone.fg,
                        )
                    }
                }
                PreviewText(1, row.template?.label.orEmpty(), color = tone.fg, weight = FontWeight.SemiBold)
                PreviewCell(2, PREVIEW_COLUMNS[2].second) {
                    if (row.label.isEmpty()) {
                        ZillitText(
                            text = "Untitled",
                            style = DmType.sans(12.5.sp).copy(fontStyle = FontStyle.Italic),
                            color = rp.ink3,
                            modifier = Modifier.padding(horizontal = 9.dp),
                        )
                    } else {
                        CellLine(row.label, rp.ink, FontWeight.SemiBold)
                    }
                }
                PreviewText(
                    3,
                    RuleOptions.RATE_TYPES.firstOrNull { it.first == row.rateType }?.second.orEmpty(),
                    color = rp.ink3,
                )
                PreviewText(4, amount, color = tone.fg, weight = FontWeight.Bold, mono = true)
                PreviewText(5, "per ${RuleSummary.basis(row.basis)}", color = rp.ink3)
                PreviewText(6, triggerText(row), color = rp.ink)
                PreviewText(7, row.dayType.ifEmpty { "—" }, color = rp.ink, mono = true)
                PreviewText(
                    8,
                    row.increment.takeIf { it.isNotEmpty() }?.let { "${it}m" } ?: "—",
                    color = rp.ink,
                    mono = true,
                )
                PreviewCell(9, PREVIEW_COLUMNS[9].second, center = true) {
                    ZillitText(
                        text = if (row.isEnhancement) "✓" else "—",
                        style = DmType.sans(12.5.sp, FontWeight.Bold),
                        color = if (row.isEnhancement) rp.cta else rp.ink4,
                    )
                }
                PreviewText(
                    10,
                    row.bdrMin.takeIf { it.isNotEmpty() }?.let { "£$it" } ?: "—",
                    color = rp.ink,
                    mono = true,
                )
                PreviewText(
                    11,
                    row.bdrMax.takeIf { it.isNotEmpty() }?.let { "£$it" } ?: "—",
                    color = rp.ink,
                    mono = true,
                )
                PreviewText(12, row.cap.takeIf { it.isNotEmpty() }?.let { "£$it" } ?: "—", color = rp.ink, mono = true)
                PreviewText(13, row.nominal.ifEmpty { "—" }, color = rp.ink, mono = true)
                PreviewText(14, row.note.ifEmpty { "—" }, color = rp.ink3)
            }
        }
    }
}

/** The read-only trigger: its template's trigger in words, or a dash for a template with no trigger field. */
private fun triggerText(row: BulkRuleRow): String {
    val template = row.template ?: return "—"
    if (template.field == TriggerField.None) return "—"
    return RuleSummary.entry(template.toTrigger(row.form))
}

@Composable
private fun PreviewText(
    index: Int,
    text: String,
    color: Color,
    weight: FontWeight = FontWeight.Medium,
    mono: Boolean = false,
) {
    PreviewCell(index, PREVIEW_COLUMNS[index].second) { CellLine(text, color, weight, mono) }
}

@Composable
private fun CellLine(text: String, color: Color, weight: FontWeight = FontWeight.Medium, mono: Boolean = false) {
    ZillitTooltip(text = text) {
        ZillitText(
            text = text,
            style = if (mono) DmType.mono(12.5.sp, weight) else DmType.sans(12.5.sp, weight),
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 9.dp),
        )
    }
}

@Composable
private fun PreviewCell(index: Int, width: Dp, center: Boolean = false, content: @Composable () -> Unit) {
    val divider = if (index in EDGE_COLUMNS) rp.border else rp.divider
    Box(
        modifier = Modifier
            .width(width)
            .fillMaxHeight()
            .drawBehind { if (index > 0) drawLine(divider, Offset(0f, 0f), Offset(0f, size.height), 1.dp.toPx()) },
        contentAlignment = if (center) Alignment.Center else Alignment.CenterStart,
    ) { content() }
}

@Composable
private fun PrimarySmallButton(text: String, onClick: () -> Unit) {
    val (source, hovered) = rememberHover()
    Row(
        modifier = Modifier
            .height(32.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (hovered) rp.ctaHover else rp.cta)
            .hoverable(source)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        ZillitIcon(ZillitIcons.Add, size = 11.dp, tint = Color.White)
        ZillitText(text = text, style = DmType.sans(12.5.sp, FontWeight.SemiBold), color = Color.White, maxLines = 1)
    }
}

private val PREVIEW_COLUMNS: List<Pair<String, Dp>> = listOf(
    "" to 28.dp,
    "Rule type" to 196.dp,
    "Name" to 200.dp,
    "Rate type" to 132.dp,
    "Amount" to 78.dp,
    "Base rate" to 118.dp,
    "Trigger" to 128.dp,
    "Day type" to 104.dp,
    "OT Increment" to 104.dp,
    "Basic + OT on Top" to 112.dp,
    "Min Basic Daily Rate to apply OT" to 136.dp,
    "Max Basic Daily Rate no OT applied" to 136.dp,
    "OT Cap" to 96.dp,
    "Nominal" to 96.dp,
    "Notes" to 200.dp,
)
private val EDGE_COLUMNS = setOf(7, 8, 12, 13, 14)
private val PREVIEW_WIDTH = PREVIEW_COLUMNS.fold(30.dp) { total, (_, width) -> total + width }

// -- day types ----------------------------------------------------------------------------------------

/** `DayTypesEditor`: the three defaults (code fixed) and the project's own codes; saved on its own button. */
@Suppress("LongMethod")
@Composable
private fun DayTypesEditor(draft: DayTypesDraft, onEvent: (DealMemoEvent) -> Unit) {
    val p = bp
    Column {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                ZillitText(text = "Day Types", style = DmType.sans(14.sp, FontWeight.Bold), color = p.ink)
                ZillitText(
                    text = buildAnnotatedString {
                        append("Contracted working-day catalogue for non-union deals. ")
                        bold("Working min")
                        append(" sets the basic/overtime split; ")
                        bold("meal break")
                        append(" drives the short-break penalty. Non-union deals snapshot this list at save time. ")
                        bold("SWD, CWD and SCWD")
                        append(
                            " are the base day types — retune their values here (codes are fixed); add other codes " +
                                "below for custom day types.",
                        )
                    },
                    style = DmType.sans(12.sp).copy(lineHeight = 18.sp),
                    color = p.muted,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            if (draft.dirty) {
                DmButton(
                    text = if (draft.saving) "Saving…" else "Save Day Types",
                    onClick = { onEvent(BuilderEvent.SaveDayTypes) },
                    style = DmButtonStyle.ModalPrimary,
                    enabled = !draft.saving,
                )
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            listOf(
                "Code" to 1.2f,
                "Working min" to 1f,
                "Meal break min" to 1f,
                "Label" to 1.6f,
            ).forEach { (title, weight) ->
                ZillitText(
                    text = title.uppercase(),
                    style = DmType.sans(10.5.sp, FontWeight.Bold, 0.08.sp),
                    color = p.muted,
                    modifier = Modifier.weight(weight),
                )
            }
            Spacer(Modifier.width(ROW_ACTION))
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            draft.rows.forEach { row -> DayTypeLine(row, onEvent) }
        }
        if (draft.rows.none { !it.isDefault }) {
            ZillitText(
                text = "Only the defaults above apply. Add a custom day type below.",
                style = DmType.sans(12.5.sp).copy(fontStyle = FontStyle.Italic),
                color = p.muted,
                modifier = Modifier.padding(vertical = 12.dp),
            )
        }
        DashedAddButton(
            label = "+ Add day type",
            onClick = { onEvent(BuilderEvent.AddDayType) },
            modifier = Modifier.padding(top = 12.dp),
        )
    }
}

@Suppress("LongMethod")
@Composable
private fun DayTypeLine(row: DayTypeRow, onEvent: (DealMemoEvent) -> Unit) {
    val p = bp
    fun edit(next: DayTypeRow) = onEvent(BuilderEvent.EditDayType(next))
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.weight(1.2f).heightIn(min = CONTROL_HEIGHT), contentAlignment = Alignment.CenterStart) {
            if (row.isDefault) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    ZillitText(text = row.code, style = DmType.mono(13.sp, FontWeight.SemiBold), color = p.ink2)
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(p.tile)
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    ) {
                        ZillitText(
                            text = "DEFAULT",
                            style = DmType.sans(9.sp, FontWeight.Bold, 0.06.sp),
                            color = p.muted,
                        )
                    }
                }
            } else {
                BuilderInput(value = row.code, onValueChange = { edit(row.copy(code = it)) }, placeholder = "CWD")
            }
        }
        Column(Modifier.weight(1f)) {
            BuilderInput(
                value = row.workMin,
                onValueChange = { edit(row.copy(workMin = digits(it))) },
                placeholder = "600",
                mono = true,
            )
            DayTypeRows.minutesHint(row.workMin).takeIf { it.isNotEmpty() }?.let {
                ZillitText(
                    text = it,
                    style = DmType.mono(10.sp),
                    color = p.muted,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        Box(Modifier.weight(1f)) {
            BuilderInput(
                value = row.mealBreakMin,
                onValueChange = { edit(row.copy(mealBreakMin = digits(it))) },
                placeholder = "—",
                mono = true,
            )
        }
        Box(Modifier.weight(1.6f)) {
            BuilderInput(value = row.label, onValueChange = { edit(row.copy(label = it)) }, placeholder = "10-hour day")
        }
        Box(Modifier.width(ROW_ACTION).heightIn(min = CONTROL_HEIGHT), contentAlignment = Alignment.Center) {
            if (!row.isDefault) {
                val (source, hovered) = rememberHover()
                ZillitTooltip(text = "Remove") {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (hovered) p.redSoft else Color.Transparent)
                            .hoverable(source)
                            .clickable(interactionSource = source, indication = null) {
                                onEvent(BuilderEvent.RemoveDayType(row.id))
                            }
                            .pointerHoverIcon(PointerIcon.Hand),
                        contentAlignment = Alignment.Center,
                    ) { ZillitIcon(ZillitIcons.Trash, size = 13.dp, tint = if (hovered) p.red else p.muted) }
                }
            }
        }
    }
}

private fun digits(text: String): String = text.filter { it.isDigit() }

private fun AnnotatedString.Builder.bold(text: String) =
    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(text) }

private val ROW_ACTION = 36.dp

private fun Modifier.bottomRule(color: Color): Modifier = drawBehind {
    val stroke = 1.dp.toPx()
    drawLine(color, Offset(0f, size.height - stroke / 2), Offset(size.width, size.height - stroke / 2), stroke)
}

// -- import rules from a union agreement ---------------------------------------------------------------

/**
 * `ImportAgreementRulesModal`: a territory, one of its agreements, and a
 * preview of the rules it would add. Rendered over the rules grid.
 */
@Composable
internal fun RuleImportModal(builder: BuilderState, onEvent: (DealMemoEvent) -> Unit) {
    val import = builder.setupPage.ruleImport
    val projected = remember(import?.agreement) {
        var seed = 0
        AgreementRuleImport.project(import?.agreement?.takeIf { import.agreementId.isNotEmpty() }) { list ->
            "preview-${list.wire}-${seed++}"
        }
    }
    val total = AgreementRuleImport.count(projected)
    DmModal(
        visible = import != null,
        title = "Import rules from a union agreement",
        onDismiss = { onEvent(BuilderEvent.CloseRuleImport) },
        maxWidth = 640.dp,
        footer = {
            ZillitText(
                text = if (total > 0) "$total rule${if (total == 1) "" else "s"} ready to import" else "",
                style = DmType.sans(12.sp),
                color = bp.muted,
                modifier = Modifier.weight(1f),
            )
            DmButton("Cancel", { onEvent(BuilderEvent.CloseRuleImport) }, DmButtonStyle.ModalNeutral)
            DmButton(
                text = if (total > 0) "Import $total rule${if (total == 1) "" else "s"}" else "Import",
                onClick = { onEvent(BuilderEvent.ConfirmRuleImport) },
                style = DmButtonStyle.ModalPrimary,
                enabled = total > 0,
            )
        },
    ) {
        import ?: return@DmModal
        ImportSelectors(builder, import, onEvent)
        Box(Modifier.fillMaxWidth().height(1.dp).background(bp.hairline))
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 420.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp),
        ) {
            when {
                import.agreementId.isEmpty() -> ImportEmpty("Select a territory and agreement to preview its rules.")
                import.agreementLoading -> ImportEmpty("Loading agreement…")
                total == 0 -> ImportEmpty("This agreement has no importable rules.")
                else -> listOf(
                    RuleList.Overtimes to "Overtimes",
                    RuleList.Premiums to "Premiums",
                    RuleList.Penalties to "Penalties",
                )
                    .forEach { (list, label) ->
                        projected.lists[list].orEmpty().takeIf { it.isNotEmpty() }?.let { ImportGroup(label, it) }
                    }
            }
        }
    }
}

@Composable
private fun ImportSelectors(builder: BuilderState, import: RuleImportState, onEvent: (DealMemoEvent) -> Unit) {
    val covered = builder.reference.coveredTerritories?.takeIf { it.isNotEmpty() }
    val territories = TerritoryCatalogue.territories.filter {
        covered == null || it.id in covered || it.id == import.territory
    }
    // Side by side at any width — the dialog is narrower than the grid's stacking breakpoint.
    Row(Modifier.padding(horizontal = 24.dp, vertical = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.weight(1f)) {
            Field("Territory") {
                RichSelect(
                    options = territories.map { PickOption(it.id, it.label) },
                    selectedKey = import.territory.ifEmpty { null },
                    onPick = { onEvent(BuilderEvent.PickImportTerritory(it)) },
                    placeholder = "Select a territory…",
                    leading = { TerritoryCatalogue.territory(import.territory)?.let { TerritoryFlag(it.id, 16.dp) } },
                    rowLeading = { option -> TerritoryFlag(option.key, 18.dp) },
                )
            }
        }
        Box(Modifier.weight(1f)) {
            Field("Agreement") {
                RichSelect(
                    options = import.agreements.map { agreement ->
                        PickOption(
                            agreement.identifier,
                            agreement.name,
                            sub = agreement.shortLabel,
                            search = "${agreement.name} ${agreement.shortLabel.orEmpty()}",
                        )
                    },
                    selectedKey = import.agreementId.ifEmpty { null },
                    onPick = { onEvent(BuilderEvent.PickImportAgreement(it)) },
                    placeholder = when {
                        import.territory.isEmpty() -> "Pick a territory first"
                        import.agreementsLoading -> "Loading agreements…"
                        else -> "Select an agreement…"
                    },
                    enabled = import.territory.isNotEmpty(),
                )
            }
        }
    }
}

@Composable
private fun ImportEmpty(text: String) {
    ZillitText(
        text = text,
        style = DmType.sans(13.sp),
        color = bp.muted,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp),
    )
}

@Composable
private fun ImportGroup(label: String, rows: List<JsonObject>) {
    val p = bp
    Column(Modifier.padding(bottom = 16.dp)) {
        ZillitText(
            text = "${label.uppercase()} · ${rows.size}",
            style = DmType.sans(11.sp, FontWeight.SemiBold, 0.06.sp),
            color = p.muted,
            modifier = Modifier.padding(bottom = 6.dp),
        )
        val shape = RoundedCornerShape(8.dp)
        Column(Modifier.fillMaxWidth().clip(shape).border(1.dp, p.hairline, shape)) {
            rows.forEachIndexed { index, row ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(
                            if (index > 0) {
                                Modifier.drawBehind {
                                    drawLine(p.hairline, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx())
                                }
                            } else {
                                Modifier
                            },
                        )
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    ZillitText(
                        text = Js.text(row["label"]),
                        style = DmType.sans(13.sp),
                        color = p.ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    ZillitText(text = importRate(row), style = DmType.mono(12.5.sp), color = p.ink2)
                    ZillitText(
                        text = "/${Js.text(row["basis"])}",
                        style = DmType.sans(11.5.sp),
                        color = p.muted,
                        textAlign = TextAlign.End,
                        modifier = Modifier.width(42.dp),
                    )
                }
            }
        }
    }
}

/** `fmtRate`: `×1.5`, `10%`, or the flat amount. */
private fun importRate(row: JsonObject): String {
    val amount = row["rate_amount"]?.takeUnless(Js::isNullish) ?: return "—"
    val text = AgreementFormat.groupAmount(amount)
    return when (Js.text(row["rate_type"])) {
        "multiplier" -> "×$text"
        "percentage" -> "$text%"
        else -> text
    }
}
