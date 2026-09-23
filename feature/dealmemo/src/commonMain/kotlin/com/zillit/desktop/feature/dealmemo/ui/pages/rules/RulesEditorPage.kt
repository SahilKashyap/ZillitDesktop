package com.zillit.desktop.feature.dealmemo.ui.pages.rules

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitSpinner
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTooltip
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.dealmemo.domain.rules.BulkRuleRow
import com.zillit.desktop.feature.dealmemo.domain.rules.BulkRules
import com.zillit.desktop.feature.dealmemo.ui.DealMemoEvent
import com.zillit.desktop.feature.dealmemo.ui.DealMemoUiState
import com.zillit.desktop.feature.dealmemo.ui.RulesEvent
import com.zillit.desktop.feature.dealmemo.ui.components.DmType
import com.zillit.desktop.feature.dealmemo.ui.components.rememberHover
import com.zillit.desktop.feature.dealmemo.ui.pages.preview.AboveEndPosition
import com.zillit.desktop.feature.dealmemo.ui.pages.preview.BelowEndPosition
import com.zillit.desktop.feature.dealmemo.ui.pages.preview.dashedBorder
import com.zillit.desktop.feature.dealmemo.ui.pages.preview.shadowed
import com.zillit.desktop.feature.dealmemo.ui.preview.CoaState
import com.zillit.desktop.feature.dealmemo.ui.preview.RulesEditorState

/** The grid's columns, in order — fixed widths, the name the one that stretches. */
internal val RULE_COLUMNS: List<Pair<String, Dp?>> get() = listOf(
    "" to 28.dp,
    str(S.desktop_rule_type) to 196.dp,
    str(S.name) to null,
    str(S.dm_rule_rate_type) to 132.dp,
    str(S.dm_rule_amount) to 78.dp,
    str(S.dm_rates_scale_base_rate) to 118.dp,
    str(S.desktop_dm_trigger) to 128.dp,
    str(S.dm_rule_day_type) to 104.dp,
    str(S.desktop_dm_ot_increment) to 104.dp,
    str(S.dm_rule_add_on_top) to 112.dp,
    str(S.dm_rule_bdr_min) to 136.dp,
    str(S.dm_rule_bdr_max) to 136.dp,
    str(S.desktop_dm_ot_cap) to 96.dp,
    str(S.dm_rule_nominal) to 96.dp,
    str(S.dm_rates_scale_notes) to 200.dp,
    "" to 34.dp,
)

private val REQUIRED_COLUMNS get() = setOf(str(S.desktop_rule_type), str(S.name), str(S.dm_rule_amount))

/** The table's smallest width: every fixed column, the name's minimum, and the row padding. */
private val TABLE_MIN = 1888.dp
private val ROW_HEIGHT = 37.dp

/**
 * The full-page rules grid (`BulkRulesEditor.jsx`) over one deal's non-union
 * rules: one row per rule, every field a column; Save only once every row
 * has a type, a name and an amount.
 */
@Composable
fun RulesEditorPage(
    state: DealMemoUiState,
    editor: RulesEditorState,
    eyebrow: String,
    onEvent: (DealMemoEvent) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(rp.bg)
            // Swallows clicks, so nothing under the takeover can be reached.
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
    ) {
        TopBar(editor, eyebrow, onEvent)
        ZillitText(
            text = str(S.desktop_dm_add_or_edit_several_pay_rules_at),
            style = DmType.sans(12.5.sp).copy(lineHeight = 19.sp),
            color = rp.ink2,
            modifier = Modifier.widthIn(max = 760.dp).padding(start = 26.dp, end = 26.dp, top = 16.dp, bottom = 12.dp),
        )
        Column(modifier = Modifier.fillMaxWidth().weight(1f).background(rp.surface)) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(rp.border))
            Toolbar(editor, onEvent)
            Box(Modifier.fillMaxWidth().height(1.dp).background(rp.divider))
            Grid(editor, state.coa, onEvent)
        }
    }
}

@Composable
@Suppress("LongMethod") // Layout in one place; the sweep's wrapped calls added the lines.
private fun TopBar(editor: RulesEditorState, eyebrow: String, onEvent: (DealMemoEvent) -> Unit) {
    Column(Modifier.fillMaxWidth().background(rp.surface)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 26.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            ZillitTooltip(text = if (editor.saving) str(S.dm_nda_saving) else str(S.desktop_dm_back_to_pay_breakdown)) {
                val shape = RoundedCornerShape(10.dp)
                Box(
                    modifier = Modifier
                        .alpha(if (editor.saving) DISABLED else 1f)
                        .size(36.dp)
                        .clip(shape)
                        .background(rp.surfaceAlt)
                        .border(1.dp, rp.border, shape)
                        .clickable(enabled = !editor.saving) { onEvent(RulesEvent.Close) }
                        .pointerHoverIcon(if (editor.saving) PointerIcon.Default else PointerIcon.Hand),
                    contentAlignment = Alignment.Center,
                ) { ZillitIcon(ZillitIcons.ChevronLeft, size = 13.dp, tint = rp.ink2) }
            }
            Column(modifier = Modifier.weight(1f)) {
                ZillitText(
                    text = eyebrow.uppercase(),
                    style = DmType.sans(10.sp, FontWeight.Bold, 0.09.em),
                    color = rp.cta,
                    maxLines = 1,
                )
                Spacer(Modifier.height(3.dp))
                ZillitText(
                    text = str(S.dm_rules_edit),
                    style = DmType.sans(18.sp, FontWeight.Bold, (-0.02).em),
                    color = rp.ink,
                )
            }
            val total = editor.rows.size
            val ready = editor.readyCount
            ZillitText(
                text = buildAnnotatedString {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = rp.ink)) { append(ready.toString()) }
                    append(" " + str(S.desktop_dm_of_total_ready, total))
                    if (ready < total) withStyle(SpanStyle(color = rp.red)) {
                        append(" " + str(S.desktop_dm_n_incomplete, total - ready))
                    }
                },
                style = DmType.sans(12.sp),
                color = rp.ink2,
                maxLines = 1,
            )
            PrimaryButton(
                text = when {
                    editor.saving -> str(S.dm_nda_saving)
                    total == 1 -> str(S.desktop_dm_save_one_rule)
                    else -> str(S.desktop_dm_save_n_rules, total)
                },
                enabled = editor.allReady && !editor.saving,
                loading = editor.saving,
                onClick = { onEvent(RulesEvent.Save) },
            )
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(rp.border))
    }
}

@Suppress("CyclomaticComplexMethod")
@Composable
private fun Toolbar(editor: RulesEditorState, onEvent: (DealMemoEvent) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 14.dp, top = 11.dp, bottom = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        ZillitText(text = str(S.desktop_rules), style = DmType.sans(13.sp, FontWeight.Bold), color = rp.ink)
        CountPill(editor.rows.size)
        editor.importNote?.let { (added, skipped) ->
            ZillitText(
                text = str(S.desktop_dm_added_n, added) + if (skipped > 0) " " + str(
                    S.desktop_dm_n_already_here,
                    skipped,
                ) else "",
                style = DmType.sans(11.5.sp, FontWeight.SemiBold),
                color = rp.ink3,
            )
        }
        Spacer(Modifier.weight(1f))
        if (editor.agreementImport) {
            ToolbarButton(
                text = str(S.desktop_dm_import_union_rules_plus),
                enabled = true,
                loading = false,
                tooltip = str(S.dm_rule_import_subtitle),
                onClick = { onEvent(RulesEvent.ImportAgreement) },
            )
        }
        editor.importSource?.let { source ->
            val importable = BulkRules.importable(source.rows, editor.rows)
            if (source.loading || source.rows.isNotEmpty()) {
                val tooltip = when {
                    source.loading -> null
                    importable.isEmpty() -> str(S.desktop_dm_every_rule_already_on_deal, source.label)
                    importable.size == 1 -> str(S.desktop_dm_adds_one_rule_not_here)
                    else -> str(S.desktop_dm_adds_n_rules_not_here, importable.size)
                }
                val label = when {
                    source.loading -> str(S.desktop_media_loading_kind, source.label)
                    importable.isEmpty() -> str(S.desktop_dm_all_of_source_added, source.label)
                    else -> str(S.desktop_dm_add_n_from_source, importable.size, source.label)
                }
                ToolbarButton(
                    text = label,
                    enabled = !source.loading && importable.isNotEmpty(),
                    loading = source.loading,
                    tooltip = tooltip,
                    onClick = { onEvent(RulesEvent.Import) },
                )
            }
        }
        AddRulesMenu(up = false, onEvent = onEvent)
    }
}

@Composable
private fun CountPill(count: Int) {
    val shape = RoundedCornerShape(50)
    Box(
        Modifier
            .clip(shape)
            .background(rp.chipBg)
            .border(1.dp, rp.chipBorder, shape)
            .padding(horizontal = 7.dp, vertical = 2.dp),
    ) {
        ZillitText(text = count.toString(), style = DmType.mono(10.5.sp, FontWeight.Bold), color = rp.ink3)
    }
}

/** The header and every row, scrolling both ways; the name column takes the slack. */
@Composable
private fun Grid(editor: RulesEditorState, coa: CoaState, onEvent: (DealMemoEvent) -> Unit) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val width = if (maxWidth > TABLE_MIN) maxWidth else TABLE_MIN
        val vertical = rememberScrollState()
        Box(modifier = Modifier.fillMaxSize().horizontalScroll(rememberScrollState())) {
            Column(modifier = Modifier.width(width).fillMaxHeight()) {
                HeaderRow()
                Column(modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(vertical)) {
                    editor.rows.forEachIndexed { index, row ->
                        RuleRow(index + 1, row, editor.tried, coa, onEvent)
                    }
                    Box(Modifier.padding(horizontal = 18.dp, vertical = 12.dp)) {
                        AddRulesMenu(up = true, onEvent = onEvent)
                    }
                }
            }
        }
    }
}

@Composable
private fun HeaderRow() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(46.dp)
            .background(rp.surfaceAlt)
            .padding(start = 18.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RULE_COLUMNS.forEachIndexed { index, (title, _) ->
            GridCell(index, center = index == 0 || title == str(S.dm_rule_add_on_top)) {
                if (title.isNotEmpty()) {
                    ZillitText(
                        text = buildAnnotatedString {
                            append(title.uppercase())
                            if (title in REQUIRED_COLUMNS) withStyle(SpanStyle(color = rp.red)) { append(" *") }
                        },
                        style = DmType.sans(11.sp, FontWeight.ExtraBold, 0.03.em).copy(lineHeight = 13.sp),
                        color = rp.ink,
                        maxLines = 2,
                        modifier = Modifier.padding(start = if (index == 9) 0.dp else 10.dp),
                    )
                }
            }
        }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(rp.border))
}

/** A cell at its column's width, with the grid's hairline on its left. */
@Composable
internal fun RowScope.GridCell(index: Int, center: Boolean = false, content: @Composable () -> Unit) {
    val (_, width) = RULE_COLUMNS[index]
    val base = if (width != null) Modifier.width(width) else Modifier.weight(1f).widthIn(min = 160.dp)
    val edge = index in EDGE_COLUMNS
    Row(modifier = base.fillMaxHeight(), verticalAlignment = Alignment.CenterVertically) {
        if (index > 0) Box(Modifier.width(1.dp).fillMaxHeight().background(if (edge) rp.border else rp.divider))
        Box(
            modifier = Modifier.weight(1f).fillMaxHeight(),
            contentAlignment = if (center) Alignment.Center else Alignment.CenterStart,
        ) { content() }
    }
}

private val EDGE_COLUMNS = setOf(7, 8, 12, 13, 14)

@Composable
private fun RuleRow(number: Int, row: BulkRuleRow, tried: Boolean, coa: CoaState, onEvent: (DealMemoEvent) -> Unit) {
    val tone = toneOf(row.template?.list)
    fun patch(next: BulkRuleRow) = onEvent(RulesEvent.Patch(row.uid, next))
    Box(modifier = Modifier.fillMaxWidth().height(ROW_HEIGHT).background(rp.surface)) {
        Box(Modifier.width(4.dp).fillMaxHeight().background(tone.rail))
        Row(
            modifier = Modifier.fillMaxSize().padding(start = 18.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GridCell(0, center = true) {
                Box(
                    Modifier.size(20.dp).clip(RoundedCornerShape(6.dp)).background(tone.bg),
                    contentAlignment = Alignment.Center,
                ) { ZillitText(text = number.toString(), style = DmType.mono(10.sp, FontWeight.Bold), color = tone.fg) }
            }
            RuleCellsRow(row, tried, coa, ::patch, onRemove = { onEvent(RulesEvent.Remove(row.uid)) })
        }
        Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(1.dp).background(rp.divider))
    }
}

/** "Add Rules": how many rows — one to five — behind one button. */
@Composable
private fun AddRulesMenu(up: Boolean, onEvent: (DealMemoEvent) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        ToolbarButton(
            text = str(S.desktop_dm_add_rules_plus),
            enabled = true,
            dashed = true,
            onClick = { open = !open },
        )
        if (open) {
            Popup(
                popupPositionProvider = remember(up) {
                    if (up) AboveEndPosition(gap = 6) else BelowEndPosition(gap = 6)
                },
                onDismissRequest = { open = false },
                properties = PopupProperties(focusable = true),
            ) {
                val shape = RoundedCornerShape(10.dp)
                Column(
                    modifier = Modifier
                        .widthIn(min = 128.dp)
                        .shadowed(shape)
                        .clip(shape)
                        .background(rp.surface)
                        .border(1.dp, rp.border, shape)
                        .padding(4.dp),
                ) {
                    for (count in 1..MAX_ADD) {
                        val (source, hovered) = rememberHover()
                        ZillitText(
                            text = if (count == 1) str(S.desktop_dm_one_row) else str(S.desktop_dm_n_rows, count),
                            style = DmType.sans(12.5.sp, FontWeight.SemiBold),
                            color = rp.ink,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(7.dp))
                                .background(if (hovered) rp.surfaceAlt else Color.Transparent)
                                .hoverable(source)
                                .clickable(interactionSource = source, indication = null) {
                                    open = false
                                    onEvent(RulesEvent.Add(count))
                                }
                                .pointerHoverIcon(PointerIcon.Hand)
                                .padding(horizontal = 10.dp, vertical = 7.dp),
                        )
                    }
                }
            }
        }
    }
}

@Suppress("CyclomaticComplexMethod")
@Composable
private fun ToolbarButton(
    text: String,
    enabled: Boolean,
    onClick: () -> Unit,
    loading: Boolean = false,
    dashed: Boolean = false,
    tooltip: String? = null,
) {
    val (source, hovered) = rememberHover()
    val shape = RoundedCornerShape(8.dp)
    val active = enabled && !loading
    val button: @Composable () -> Unit = {
        Row(
            modifier = Modifier
                .alpha(if (enabled || loading) 1f else DISABLED)
                .height(34.dp)
                .clip(shape)
                .background(rp.surface)
                .then(
                    if (dashed) {
                        Modifier.dashedBorder(if (hovered) rp.cta else rp.border, 8.dp)
                    } else {
                        Modifier.border(1.dp, if (hovered && active) rp.borderStrong else rp.border, shape)
                    },
                )
                .hoverable(source)
                .then(
                    if (active) {
                        Modifier.clickable(interactionSource = source, indication = null, onClick = onClick)
                    } else {
                        Modifier
                    },
                )
                .pointerHoverIcon(if (active) PointerIcon.Hand else PointerIcon.Default)
                .padding(horizontal = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (loading) ZillitSpinner(size = 11.dp, color = rp.ink3)
            ZillitText(
                text = text,
                style = DmType.sans(12.5.sp, FontWeight.SemiBold),
                color = if (dashed && hovered) rp.cta else if (hovered && active) rp.ink else rp.ink2,
                maxLines = 1,
            )
        }
    }
    if (tooltip != null) ZillitTooltip(text = tooltip) { button() } else button()
}

@Composable
private fun PrimaryButton(text: String, enabled: Boolean, loading: Boolean, onClick: () -> Unit) {
    val (source, hovered) = rememberHover()
    val shape = RoundedCornerShape(8.dp)
    val active = enabled && !loading
    Row(
        modifier = Modifier
            .alpha(if (enabled || loading) 1f else HALF)
            .then(
                if (active) {
                    Modifier.shadow(
                        5.dp,
                        shape,
                        ambientColor = rp.cta.copy(alpha = 0.26f),
                        spotColor = rp.cta.copy(alpha = 0.26f),
                    )
                } else {
                    Modifier
                },
            )
            .height(34.dp)
            .clip(shape)
            .background(if (hovered && active) rp.ctaHover else rp.cta)
            .hoverable(source)
            .then(
                if (active) {
                    Modifier.clickable(interactionSource = source, indication = null, onClick = onClick)
                } else {
                    Modifier
                },
            )
            .pointerHoverIcon(if (active) PointerIcon.Hand else PointerIcon.Default)
            .padding(horizontal = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (loading) ZillitSpinner(size = 12.dp, color = Color.White)
        ZillitText(text = text, style = DmType.sans(12.5.sp, FontWeight.SemiBold), color = Color.White, maxLines = 1)
    }
}

private const val DISABLED = 0.45f
private const val HALF = 0.5f
private const val MAX_ADD = 5
