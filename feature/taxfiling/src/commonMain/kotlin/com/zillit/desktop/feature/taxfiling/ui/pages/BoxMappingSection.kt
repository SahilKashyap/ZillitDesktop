package com.zillit.desktop.feature.taxfiling.ui.pages

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.taxfiling.domain.BoxMapping
import com.zillit.desktop.feature.taxfiling.domain.TaxFormat
import com.zillit.desktop.feature.taxfiling.domain.VatBox
import com.zillit.desktop.feature.taxfiling.domain.VatReturn
import com.zillit.desktop.feature.taxfiling.ui.ReturnState
import com.zillit.desktop.feature.taxfiling.ui.TaxFilingEvent
import com.zillit.desktop.feature.taxfiling.ui.TaxFilingUiState
import com.zillit.desktop.feature.taxfiling.ui.components.MtdBoxBadge
import com.zillit.desktop.feature.taxfiling.ui.components.MtdButton
import com.zillit.desktop.feature.taxfiling.ui.components.MtdButtonSize
import com.zillit.desktop.feature.taxfiling.ui.components.MtdButtonVariant
import com.zillit.desktop.feature.taxfiling.ui.components.MtdPill
import com.zillit.desktop.feature.taxfiling.ui.components.PillTone
import com.zillit.desktop.feature.taxfiling.ui.components.mtdEyebrow
import com.zillit.desktop.feature.taxfiling.ui.components.mtdPalette
import com.zillit.desktop.feature.taxfiling.ui.components.mtdText

/**
 * "Box mapping" — the web's accordion of nine boxes.
 *
 * Seven are mapped: each a row that opens onto its codes, layers, tags and
 * date window. Boxes 3 and 5 are arithmetic on the rest and sit between them,
 * read-only, box 5 in the accent as the figure the return is about.
 */
@Composable
internal fun BoxMappingSection(
    state: TaxFilingUiState,
    onEvent: (TaxFilingEvent) -> Unit,
    onOpenLayers: (VatBox) -> Unit,
) {
    val returnState = state.returnState
    val shown = returnState.shown
    Column(Modifier.fillMaxWidth()) {
        MappingHeader(returnState, onEvent)
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            VatBox.entries.forEach { box ->
                if (box.computed) {
                    AutoRow(box = box, shown = shown)
                } else {
                    BoxRow(
                        box = box,
                        mapping = returnState.mappingFor(box),
                        open = box in returnState.expanded,
                        value = shown?.get(box),
                        state = state,
                        onEvent = onEvent,
                        onOpenLayers = { onOpenLayers(box) },
                    )
                }
            }
        }
    }
}

/** The section's title, and the two buttons that act on every box at once. */
@Composable
private fun MappingHeader(returnState: ReturnState, onEvent: (TaxFilingEvent) -> Unit) {
    val palette = mtdPalette()
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            ZillitText(
                text = str(S.desktop_tax_box_mapping),
                style = mtdText(16.5.sp, FontWeight.Bold, tracking = (-0.02).em),
                color = palette.ink,
            )
            ZillitText(
                text = str(S.desktop_tax_box_mapping_subtitle),
                style = mtdText(13.5.sp),
                color = palette.ink3,
                modifier = Modifier.widthIn(max = 520.dp),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MtdButton(
                text = if (returnState.anyCollapsed) str(S.ah_expand_all) else str(S.ah_collapse_all),
                onClick = { onEvent(TaxFilingEvent.ToggleAllBoxes) },
                variant = MtdButtonVariant.Ghost,
                size = MtdButtonSize.Small,
                icon = if (returnState.anyCollapsed) ZillitIcons.Expand else ZillitIcons.Collapse,
            )
            MtdButton(
                text = if (returnState.savingMapping) str(S.ah_saving) else str(S.desktop_tax_save_mapping),
                onClick = { onEvent(TaxFilingEvent.SaveMapping) },
                variant = MtdButtonVariant.Secondary,
                size = MtdButtonSize.Small,
                icon = ZillitIcons.Save,
                loading = returnState.savingMapping,
                enabled = !returnState.mappingLoading && !returnState.calculating,
            )
        }
    }
}

/**
 * A mapped box: its badge, name and figure, opening onto the fields that
 * decide which ledger entries it reads.
 */
@Composable
private fun BoxRow(
    box: VatBox,
    mapping: BoxMapping,
    open: Boolean,
    value: Double?,
    state: TaxFilingUiState,
    onEvent: (TaxFilingEvent) -> Unit,
    onOpenLayers: () -> Unit,
) {
    val palette = mtdPalette()
    val shape = RoundedCornerShape(14.dp)
    val elevation by animateDpAsState(if (open) 8.dp else 1.dp, label = "boxLift")
    val edge by animateColorAsState(if (open) palette.border2 else palette.border, label = "boxEdge")
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(elevation, shape, clip = false, ambientColor = Shade, spotColor = Shade)
            .clip(shape)
            .background(palette.surface)
            .border(1.dp, edge, shape),
    ) {
        BoxRowHeader(box, mapping, open, value) { onEvent(TaxFilingEvent.ToggleBox(box)) }
        AnimatedVisibility(
            visible = open,
            enter = expandVertically(tween(EXPAND_MS)) + fadeIn(tween(EXPAND_MS)),
            exit = shrinkVertically(tween(EXPAND_MS)) + fadeOut(tween(EXPAND_MS)),
        ) {
            BoxFields(box, mapping, state, onEvent, onOpenLayers)
        }
    }
}

/** The always-visible line of a mapped box, which opens and closes it. */
@Composable
private fun BoxRowHeader(box: VatBox, mapping: BoxMapping, open: Boolean, value: Double?, onToggle: () -> Unit) {
    val palette = mtdPalette()
    val chevron by animateFloatAsState(if (open) HALF_TURN else 0f, tween(CHEVRON_MS), label = "boxChevron")
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Button,
                onClickLabel = if (open) {
                    str(S.desktop_tax_close_box, box.number)
                } else {
                    str(S.desktop_tax_open_box, box.number)
                },
                onClick = onToggle,
            )
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        MtdBoxBadge(number = box.number)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            ZillitText(
                text = box.label,
                style = mtdText(14.5.sp, FontWeight.SemiBold, tracking = (-0.01).em),
                color = palette.ink,
                maxLines = 1,
            )
            if (open) {
                ZillitText(text = box.description, style = mtdText(12.5.sp), color = palette.ink3, maxLines = 1)
            } else {
                MappingSummary(mapping)
            }
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(3.dp)) {
            ZillitText(
                text = TaxFormat.gbp(value, box.decimals),
                style = mtdText(14.5.sp, FontWeight.Bold, mono = true, tracking = (-0.01).em),
                color = if (mapping.markZero) palette.muted else palette.ink,
                textAlign = TextAlign.End,
            )
            if (mapping.markZero) {
                MtdPill(text = str(S.desktop_tax_zero), tone = PillTone.Zero)
            } else {
                MtdPill(text = str(S.desktop_tax_computed), tone = PillTone.Computed)
            }
        }
        ZillitIcon(
            icon = ZillitIcons.ChevronDown,
            tint = palette.muted,
            size = 15.dp,
            modifier = Modifier.rotate(chevron),
        )
    }
}

/**
 * The collapsed row's one line: what the box reads, at a glance.
 *
 * "Forced to £0 — ledger ignored" for a zeroed box, "Not mapped — no ledger
 * codes" for an empty one, and otherwise the first two codes, then how many
 * layers and tags narrow them.
 */
@Composable
private fun MappingSummary(mapping: BoxMapping) {
    val palette = mtdPalette()
    when {
        mapping.markZero -> ZillitText(
            text = str(S.desktop_tax_forced_zero),
            style = mtdText(12.5.sp, FontWeight.SemiBold),
            color = palette.amber,
            maxLines = 1,
        )
        mapping.codes.isEmpty() -> ZillitText(
            text = str(S.desktop_tax_not_mapped),
            style = mtdText(12.5.sp),
            color = palette.muted,
            maxLines = 1,
        )
        else -> ZillitText(
            text = summaryLine(mapping),
            style = mtdText(12.sp, mono = true, tracking = 0.02.em),
            color = palette.ink3,
            maxLines = 1,
        )
    }
}

/** `4000, 4010 +2  ·  1 layer  ·  3 tags`. */
internal fun summaryLine(mapping: BoxMapping): String {
    val parts = mutableListOf<String>()
    val extra = mapping.codes.size - SUMMARY_CODES
    parts += mapping.codes.take(SUMMARY_CODES).joinToString(", ") + if (extra > 0) " +$extra" else ""
    mapping.layers.size.takeIf { it > 0 }?.let {
        parts += if (it == 1) str(S.desktop_tax_layer_one, it) else str(S.desktop_tax_layer_many, it)
    }
    mapping.tags.size.takeIf { it > 0 }?.let {
        parts += if (it == 1) str(S.desktop_tax_tag_one, it) else str(S.desktop_tax_tag_many, it)
    }
    return parts.joinToString("  ·  ")
}

/**
 * Box 3 or box 5: worked out, never mapped. Box 5 wears the accent — it is the
 * figure the return is about — and keeps its sign here; the rail and the
 * filing carry its size and say which way it goes.
 */
@Composable
private fun AutoRow(box: VatBox, shown: VatReturn?) {
    val palette = mtdPalette()
    val net = box == VatBox.NetDue
    val shape = RoundedCornerShape(14.dp)
    val value = when {
        shown == null -> null
        net -> shown.netSigned
        else -> shown[box]
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (net) palette.accentWash else palette.surface2)
            .border(1.dp, if (net) palette.accentBorder else palette.border, shape)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        MtdBoxBadge(number = box.number, highlight = net)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            ZillitText(
                text = box.label,
                style = mtdText(14.5.sp, FontWeight.SemiBold, tracking = (-0.01).em),
                color = palette.ink,
            )
            FormulaChip(text = box.direction, highlight = net)
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(3.dp)) {
            ZillitText(
                text = TaxFormat.gbp(value, box.decimals),
                style = mtdText(if (net) 17.sp else 14.5.sp, FontWeight.Bold, mono = true, tracking = (-0.02).em),
                color = if (net) palette.accentText else palette.ink,
            )
            MtdPill(text = str(S.desktop_auto), tone = PillTone.Auto)
        }
        Spacer(Modifier.width(15.dp))
    }
}

/** `Σ (credit − debit)`, `Box 1 + Box 2` — how a box's figure is arrived at. */
@Composable
internal fun FormulaChip(text: String, highlight: Boolean = false) {
    val palette = mtdPalette()
    val shape = RoundedCornerShape(7.dp)
    Box(
        modifier = Modifier
            .clip(shape)
            .background(if (highlight) palette.surface else palette.surface3)
            .border(1.dp, if (highlight) palette.accentBorder else palette.border, shape)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        ZillitText(
            text = text,
            style = mtdText(12.sp, FontWeight.SemiBold, mono = true),
            color = if (highlight) palette.accentText else palette.ink2,
        )
    }
}

/** The direction eyebrow over the fields, as the open row starts. */
@Composable
internal fun DirectionLine(box: VatBox) {
    val palette = mtdPalette()
    Row(
        modifier = Modifier.padding(top = 12.dp, bottom = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ZillitText(text = str(S.desktop_tax_direction_caps), style = mtdEyebrow(), color = palette.muted)
        FormulaChip(text = box.direction)
    }
}

private val Shade = Color.Black.copy(alpha = 0.16f)
private const val HALF_TURN = 180f
private const val CHEVRON_MS = 180
private const val EXPAND_MS = 200
private const val SUMMARY_CODES = 2
