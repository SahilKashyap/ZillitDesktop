package com.zillit.desktop.feature.taxfiling.ui.pages

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.designsystem.component.ZillitCheckbox
import com.zillit.desktop.core.designsystem.component.ZillitDateField
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.taxfiling.domain.BoxMapping
import com.zillit.desktop.feature.taxfiling.domain.TaxDates
import com.zillit.desktop.feature.taxfiling.domain.VatBox
import com.zillit.desktop.feature.taxfiling.ui.TaxFilingEvent
import com.zillit.desktop.feature.taxfiling.ui.TaxFilingUiState
import com.zillit.desktop.feature.taxfiling.ui.components.FieldHeight
import com.zillit.desktop.feature.taxfiling.ui.components.FieldShape
import com.zillit.desktop.feature.taxfiling.ui.components.MtdCodeInput
import com.zillit.desktop.feature.taxfiling.ui.components.MtdDropdown
import com.zillit.desktop.feature.taxfiling.ui.components.MtdFieldLabel
import com.zillit.desktop.feature.taxfiling.ui.components.MtdOption
import com.zillit.desktop.feature.taxfiling.ui.components.MtdRule
import com.zillit.desktop.feature.taxfiling.ui.components.MtdTagChip
import com.zillit.desktop.feature.taxfiling.ui.components.mtdPalette
import com.zillit.desktop.feature.taxfiling.ui.components.mtdText

/**
 * An open box: its direction, then four fields side by side — ledger codes,
 * layers, tags and an optional date window — and the switch that ignores the
 * ledger for this box altogether.
 *
 * While that switch is on the four fields fade back and stop taking input, as
 * the web dims them: they are kept, not cleared, so switching it off again
 * restores what was there.
 */
@Composable
internal fun BoxFields(
    box: VatBox,
    mapping: BoxMapping,
    state: TaxFilingUiState,
    onEvent: (TaxFilingEvent) -> Unit,
    onOpenLayers: () -> Unit,
) {
    val palette = mtdPalette()
    val edit: (BoxMapping) -> Unit = { onEvent(TaxFilingEvent.EditMapping(it)) }
    val zero = mapping.markZero
    val fade by animateFloatAsState(if (zero) DIMMED else 1f, label = "boxFieldsDim")
    Column(Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, bottom = 18.dp)) {
        MtdRule()
        DirectionLine(box)
        Column(Modifier.fillMaxWidth().alpha(fade)) {
            FieldGrid(
                codes = { CodesField(mapping, state, enabled = !zero, edit = edit) },
                layers = { LayersField(mapping, state, enabled = !zero, edit = edit, onOpenLayers = onOpenLayers) },
                tags = { TagsField(mapping, state, enabled = !zero, edit = edit) },
                dates = { DatesField(box, mapping, enabled = !zero, edit = edit) },
            )
            ZillitText(
                text = str(S.desktop_tax_box_fields_hint),
                style = mtdText(12.sp),
                color = palette.muted,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
        MtdRule(Modifier.padding(top = 18.dp, bottom = 14.dp))
        ZillitCheckbox(
            checked = zero,
            onCheckedChange = { edit(mapping.copy(markZero = it)) },
            label = str(S.desktop_tax_mark_zero),
        )
    }
}

/** Four columns when there is room for four, two by two when there is not. */
@Composable
private fun FieldGrid(
    codes: @Composable () -> Unit,
    layers: @Composable () -> Unit,
    tags: @Composable () -> Unit,
    dates: @Composable () -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val cells = listOf(codes, layers, tags, dates)
        val perRow = if (maxWidth >= FOUR_ACROSS) 4 else 2
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            cells.chunked(perRow).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Top) {
                    row.forEach { cell -> Column(Modifier.weight(1f)) { cell() } }
                }
            }
        }
    }
}

/** Ledger codes: picked from the chart one at a time, listed as chips under the field. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CodesField(mapping: BoxMapping, state: TaxFilingUiState, enabled: Boolean, edit: (BoxMapping) -> Unit) {
    val lookups = state.lookups
    MtdFieldLabel(str(S.desktop_tax_ledger_codes))
    MtdCodeInput(
        codes = lookups.coa,
        onCommit = { code -> if (code !in mapping.codes) edit(mapping.copy(codes = mapping.codes + code)) },
        enabled = enabled,
        loading = lookups.coaLoading,
        failed = lookups.coaFailed,
        picked = mapping.codes,
        modifier = Modifier.fillMaxWidth(),
    )
    if (mapping.codes.isNotEmpty()) {
        FlowRow(
            modifier = Modifier.padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            mapping.codes.forEach { code ->
                CodeChip(
                    code = code,
                    onRemove = if (enabled) ({ edit(mapping.copy(codes = mapping.codes - code)) }) else null,
                )
            }
        }
    }
}

/** A picked ledger code: mono, white, removable — the web's code chip. */
@Composable
private fun CodeChip(code: String, onRemove: (() -> Unit)?) {
    val palette = mtdPalette()
    val shape = RoundedCornerShape(9.dp)
    Row(
        modifier = Modifier
            .clip(shape)
            .background(palette.surface)
            .border(1.dp, palette.border2, shape)
            .padding(start = 9.dp, end = if (onRemove == null) 9.dp else 3.dp, top = 3.dp, bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        ZillitText(text = code, style = mtdText(13.sp, FontWeight.SemiBold, mono = true), color = palette.ink)
        if (onRemove != null) {
            ZillitIconButton(
                icon = ZillitIcons.Close,
                contentDescription = str(S.desktop_tax_remove_code, code),
                onClick = onRemove,
                tint = palette.muted,
                size = 20.dp,
            )
        }
    }
}

/**
 * Layers: a "+ Layers" trigger that opens the picker, and the picks as solid
 * chips under it — the code, not the set, as the web's `showCode` draws them.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LayersField(
    mapping: BoxMapping,
    state: TaxFilingUiState,
    enabled: Boolean,
    edit: (BoxMapping) -> Unit,
    onOpenLayers: () -> Unit,
) {
    val palette = mtdPalette()
    MtdFieldLabel(str(S.desktop_layers))
    val dash = palette.border2
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = FieldHeight)
            .clip(FieldShape)
            .background(palette.surface)
            .drawBehind {
                // Inset by half the stroke, so the clip does not shave the dashes to a hairline.
                val stroke = 1.dp.toPx()
                drawRoundRect(
                    color = dash,
                    topLeft = Offset(stroke / 2, stroke / 2),
                    size = Size(size.width - stroke, size.height - stroke),
                    cornerRadius = CornerRadius(6.dp.toPx()),
                    style = Stroke(width = stroke, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 5f))),
                )
            }
            .clickable(enabled = enabled, role = Role.Button, onClick = onOpenLayers)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZillitText(text = "+ Layers", style = mtdText(13.5.sp), color = palette.muted)
    }
    if (mapping.layers.isNotEmpty()) {
        FlowRow(
            modifier = Modifier.padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            mapping.layers.forEach { (setId, value) ->
                val shown = state.lookups.layerSets.firstOrNull { it.id == setId }?.codeFor(value)?.code ?: value
                MtdTagChip(
                    text = shown,
                    solid = true,
                    onRemove = if (enabled) ({ edit(mapping.copy(layers = mapping.layers - setId)) }) else null,
                )
            }
        }
    }
}

/** Tags: the production's asset tags not yet picked, added one at a time. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TagsField(mapping: BoxMapping, state: TaxFilingUiState, enabled: Boolean, edit: (BoxMapping) -> Unit) {
    MtdFieldLabel(str(S.drive_tags))
    MtdDropdown(
        value = null,
        options = state.lookups.assetTags.filterNot { it in mapping.tags }.map { MtdOption(it, it) },
        onChange = { tag -> if (tag != null && tag !in mapping.tags) edit(mapping.copy(tags = mapping.tags + tag)) },
        placeholder = str(S.desktop_tax_add_tags),
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
    )
    if (mapping.tags.isNotEmpty()) {
        FlowRow(
            modifier = Modifier.padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            mapping.tags.forEach { tag ->
                MtdTagChip(
                    text = tag,
                    onRemove = if (enabled) ({ edit(mapping.copy(tags = mapping.tags - tag)) }) else null,
                )
            }
        }
    }
}

/** The optional window, day by day: from over to, and a way to clear both. */
@Composable
private fun DatesField(box: VatBox, mapping: BoxMapping, enabled: Boolean, edit: (BoxMapping) -> Unit) {
    val palette = mtdPalette()
    MtdFieldLabel(str(S.cs_date_range), hint = str(S.desktop_optional_tail))
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        ZillitDateField(
            value = mapping.fromDate,
            onValueChange = { edit(mapping.copy(fromDate = it)) },
            placeholder = str(S.fromText),
            errorText = invalidDate(mapping.fromDate),
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        )
        ZillitDateField(
            value = mapping.toDate,
            onValueChange = { edit(mapping.copy(toDate = it)) },
            placeholder = str(S.toText),
            errorText = invalidDate(mapping.toDate),
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    if (mapping.hasDateRange && enabled) {
        ZillitText(
            text = str(S.desktop_tax_clear_lower),
            style = mtdText(12.5.sp, FontWeight.SemiBold),
            color = palette.ink3,
            modifier = Modifier
                .padding(top = 6.dp)
                .clickable(role = Role.Button, onClickLabel = str(S.desktop_tax_clear_box_range, box.number)) {
                    edit(mapping.copy(fromDate = "", toDate = ""))
                },
        )
    }
}

private fun invalidDate(text: String): String? =
    str(S.desktop_tax_use_yyyy_mm_dd).takeIf { text.isNotBlank() && !TaxDates.isValid(text) }

private val FOUR_ACROSS = 720.dp
private const val DIMMED = 0.42f
