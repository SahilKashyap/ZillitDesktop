// The process editor's line grid — the web's invoice `LineItemsEditor` as the card editor uses it.
@file:Suppress("TooManyFunctions")

package com.zillit.desktop.feature.cardexpenses.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitCheckbox
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitMultiSelect
import com.zillit.desktop.core.designsystem.component.ZillitSelect
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.cardexpenses.domain.ProcessFigures
import com.zillit.desktop.feature.cardexpenses.domain.ProcessLine
import com.zillit.desktop.feature.cardexpenses.domain.ProcessLines
import com.zillit.desktop.feature.cardexpenses.domain.ProcessRefs
import com.zillit.desktop.feature.cardexpenses.domain.TaxLineDraft
import com.zillit.desktop.feature.cardexpenses.domain.TrackingSet
import com.zillit.desktop.feature.cardexpenses.ui.CardEvent
import com.zillit.desktop.feature.cardexpenses.ui.ProcessDraft
import com.zillit.desktop.feature.cardexpenses.ui.ProcessPageEvent
import com.zillit.desktop.feature.cardexpenses.ui.money

/**
 * The coded lines: Description · Code · Layers · Tags · Amount · Tax, split
 * children indented under their parent, the reclaimable-tax row, then Split
 * line and Add line on the left and Net / Tax / Gross Total on the right
 * (`LineItemsEditor.jsx`). Amounts are **net**; the tax column carries each
 * parent's type, and a child's tax is its parent's.
 */
@Composable
internal fun ProcessLineItems(
    draft: ProcessDraft,
    refs: ProcessRefs,
    figures: ProcessFigures,
    frozen: Boolean,
    currency: String?,
    onEvent: (CardEvent) -> Unit,
    /** Opens the Layers picker; drawn at the page root, since a dialog shell is not a popup. */
    onLayers: (LayersTarget) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
        GridRow(header = true) {
            HeaderCell("", Modifier.width(SELECT_WIDTH))
            HeaderCell(str(S.description), Modifier.weight(1f))
            HeaderCell(str(S.code), Modifier.width(CODE_WIDTH))
            HeaderCell(str(S.desktop_layers), Modifier.width(LAYERS_WIDTH))
            HeaderCell(str(S.drive_tags), Modifier.width(TAGS_WIDTH))
            HeaderCell(str(S.amount), Modifier.width(AMOUNT_WIDTH))
            HeaderCell(str(S.ah_lbl_vat), Modifier.width(TAX_WIDTH))
            HeaderCell("", Modifier.width(REMOVE_WIDTH))
        }
        draft.lines.forEach { line ->
            LineRow(draft, refs, line, frozen, currency, onEvent) { onLayers(LayersTarget.Line(line.id.orEmpty())) }
        }
        TaxRow(draft, refs, figures, frozen, onEvent) { onLayers(LayersTarget.Tax) }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = ZillitTheme.spacing.sm),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (!frozen) {
                ZillitButton(
                    text = str(S.desktop_po_split_line),
                    onClick = { onEvent(ProcessPageEvent.SplitLine) },
                    variant = ButtonVariant.Tertiary,
                    size = ButtonSize.Small,
                    enabled = draft.selectedLineId != null,
                )
                ZillitButton(
                    text = str(S.desktop_po_add_line),
                    onClick = { onEvent(ProcessPageEvent.AddLine) },
                    variant = ButtonVariant.Tertiary,
                    size = ButtonSize.Small,
                    leadingIcon = ZillitIcons.Add,
                )
            }
            Spacer(Modifier.weight(1f))
            TotalCell(str(S.ah_lbl_net_total), money(figures.codedNet, currency))
            TotalCell(str(S.desktop_inv_tax_total), money(figures.codedTax, currency))
            TotalCell(str(S.ah_lbl_gross_total), money(figures.codedGross, currency), strong = true)
        }
    }
}

/** The Layers picker for one row of [draft] — the line's picks, or the tax row's. */
@Composable
internal fun LayersPicker(
    draft: ProcessDraft,
    refs: ProcessRefs,
    target: LayersTarget,
    onDismiss: () -> Unit,
    onEvent: (CardEvent) -> Unit,
) {
    val current = when (target) {
        LayersTarget.Tax -> draft.taxLine.trackingCodes
        is LayersTarget.Line -> draft.lines.firstOrNull { it.id == target.id }?.trackingCodes.orEmpty()
    }
    LayersDialog(refs.trackingSets, current, onDismiss) { picked ->
        when (target) {
            LayersTarget.Tax -> onEvent(ProcessPageEvent.EditTaxLine(draft.taxLine.copy(trackingCodes = picked)))
            is LayersTarget.Line -> draft.lines.firstOrNull { it.id == target.id }?.let {
                onEvent(ProcessPageEvent.EditLine(it.copy(trackingCodes = picked)))
            }
        }
        onDismiss()
    }
}

/** Which row the Layers picker is open for. */
internal sealed interface LayersTarget {
    data object Tax : LayersTarget
    data class Line(val id: String) : LayersTarget
}

@Suppress("LongMethod", "LongParameterList") // One row of the grid, cell by cell.
@Composable
private fun LineRow(
    draft: ProcessDraft,
    refs: ProcessRefs,
    line: ProcessLine,
    frozen: Boolean,
    currency: String?,
    onEvent: (CardEvent) -> Unit,
    onLayers: () -> Unit,
) {
    val id = line.id.orEmpty()
    val hasSplits = ProcessLines.hasSplits(draft.lines, line.id)
    val selected = draft.selectedLineId == id
    fun change(next: ProcessLine) = onEvent(ProcessPageEvent.EditLine(next))
    GridRow(selected = selected) {
        Box(Modifier.width(SELECT_WIDTH), contentAlignment = Alignment.Center) {
            if (line.isSplit) {
                ZillitText(text = "↳", color = ZillitTheme.colors.accentText)
            } else {
                ZillitCheckbox(
                    checked = selected,
                    onCheckedChange = { onEvent(ProcessPageEvent.SelectLine(id)) },
                    enabled = !frozen,
                )
            }
        }
        ZillitTextField(
            value = line.description,
            onValueChange = { change(line.copy(description = it)) },
            placeholder = str(S.description),
            enabled = !frozen,
            modifier = Modifier.weight(1f).padding(start = if (line.isSplit) SPLIT_INDENT else 0.dp),
        )
        CoaCodeInput(
            value = line.account,
            onValueChange = { change(line.copy(account = it)) },
            accounts = refs.accounts,
            placeholder = str(S.code),
            enabled = !frozen,
            error = id in draft.lineErrors,
            modifier = Modifier.width(CODE_WIDTH),
        )
        LayersButton(refs.trackingSets, line.trackingCodes, !frozen, Modifier.width(LAYERS_WIDTH), onLayers)
        TagsField(refs, line.tags, !frozen, Modifier.width(TAGS_WIDTH)) { change(line.copy(tags = it)) }
        when {
            line.isSplit -> AmountField(line.net, !frozen, Modifier.width(AMOUNT_WIDTH)) {
                onEvent(ProcessPageEvent.EditSplitAmount(id, it))
            }

            // A parent that has been split shows its read-only total; its children are the allocation.
            hasSplits -> ZillitText(
                text = money(line.net, currency),
                style = ZillitTheme.typography.numeric,
                modifier = Modifier.width(AMOUNT_WIDTH).padding(vertical = ZillitTheme.spacing.sm),
            )

            else -> AmountField(line.net, !frozen, Modifier.width(AMOUNT_WIDTH)) { change(line.withAmount(it)) }
        }
        if (line.isSplit) {
            ZillitText(
                text = EM_DASH,
                color = ZillitTheme.colors.textMuted,
                modifier = Modifier.width(TAX_WIDTH).padding(vertical = ZillitTheme.spacing.sm),
            )
        } else {
            TaxPicker(refs, line, !frozen, Modifier.width(TAX_WIDTH), ::change)
        }
        ZillitIconButton(
            icon = ZillitIcons.Close,
            contentDescription = str(S.desktop_po_remove_line),
            onClick = { onEvent(ProcessPageEvent.RemoveLine(id)) },
            enabled = !frozen && (line.isSplit || draft.lines.count { !it.isSplit } > 1),
            modifier = Modifier.width(REMOVE_WIDTH),
        )
    }
}

/**
 * The reclaimable-tax row: its own nominal, Layers and tags, and an amount
 * that follows the recoverable tax on the lines until someone types over it —
 * then ↻ brings the derived figure back.
 */
@Composable
private fun TaxRow(
    draft: ProcessDraft,
    refs: ProcessRefs,
    figures: ProcessFigures,
    frozen: Boolean,
    onEvent: (CardEvent) -> Unit,
    onLayers: () -> Unit,
) {
    val tax = draft.taxLine
    fun change(next: TaxLineDraft) = onEvent(ProcessPageEvent.EditTaxLine(next))
    GridRow {
        Spacer(Modifier.width(SELECT_WIDTH))
        Column(Modifier.weight(1f)) {
            ZillitText(text = str(S.ah_lbl_vat), style = ZillitTheme.typography.bodyMedium)
            ZillitText(
                text = "(${str(S.desktop_po_reclaimable_tax)})",
                style = ZillitTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic),
                color = ZillitTheme.colors.textMuted,
            )
        }
        CoaCodeInput(
            value = tax.account,
            onValueChange = { change(tax.copy(account = it)) },
            accounts = refs.accounts,
            placeholder = str(S.code),
            enabled = !frozen,
            error = ProcessFigures.TAX_ROW in draft.lineErrors,
            modifier = Modifier.width(CODE_WIDTH),
        )
        LayersButton(refs.trackingSets, tax.trackingCodes, !frozen, Modifier.width(LAYERS_WIDTH), onLayers)
        TagsField(refs, tax.tags, !frozen, Modifier.width(TAGS_WIDTH)) { change(tax.copy(tags = it)) }
        Row(Modifier.width(AMOUNT_WIDTH), verticalAlignment = Alignment.CenterVertically) {
            val overridden = tax.overridden && round(figures.effectiveTax) != round(figures.reclaimableTax)
            if (overridden && !frozen) {
                ZillitIconButton(
                    icon = ZillitIcons.Reload,
                    contentDescription = str(S.desktop_inv_recalculate_from_lines),
                    onClick = { change(tax.copy(amount = null, overridden = false)) },
                )
            }
            AmountField(figures.effectiveTax, !frozen, Modifier.weight(1f)) {
                change(tax.copy(amount = it.coerceAtLeast(0.0), overridden = true))
            }
        }
        ZillitText(
            text = EM_DASH,
            color = ZillitTheme.colors.textMuted,
            modifier = Modifier.width(TAX_WIDTH).padding(vertical = ZillitTheme.spacing.sm),
        )
        Spacer(Modifier.width(REMOVE_WIDTH))
    }
}

private fun round(value: Double): Long = kotlin.math.round(value * CENTS).toLong()

/**
 * The line's tax: none, a Production Setup type (its rate comes with it), or
 * Other with a rate typed in, capped to 0–100 as the web clamps it.
 */
@Composable
private fun TaxPicker(
    refs: ProcessRefs,
    line: ProcessLine,
    enabled: Boolean,
    modifier: Modifier,
    onChange: (ProcessLine) -> Unit,
) {
    val known = refs.taxTypes.any { it.identifier == line.taxType }
    val current = when {
        known -> line.taxType
        line.taxType.isNotBlank() || line.taxRate != null -> OTHER
        else -> ""
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
        ZillitSelect(
            value = current,
            options = listOf("") + refs.taxTypes.map { it.identifier } + OTHER,
            onSelect = { picked ->
                val type = refs.taxTypes.firstOrNull { it.identifier == picked }
                onChange(
                    when {
                        picked.isBlank() -> line.copy(taxType = "", taxRate = null)
                        type != null -> line.copy(taxType = type.identifier, taxRate = type.rate)
                        else -> line.copy(taxType = OTHER, taxRate = line.taxRate)
                    },
                )
            },
            label = { id ->
                when (id) {
                    "" -> str(S.ah_select_vat_tax_dots)
                    OTHER -> str(S.ah_tax_other_label)
                    else -> refs.taxTypes.firstOrNull { it.identifier == id }?.optionLabel ?: id
                }
            },
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        )
        if (current == OTHER) {
            AmountField(line.taxRate ?: 0.0, enabled, Modifier.fillMaxWidth(), suffix = "%") { rate ->
                onChange(line.copy(taxType = OTHER, taxRate = rate.coerceIn(0.0, MAX_RATE)))
            }
        }
    }
}

/** "+ Layers", or the picked codes — opens the per-set picker. */
@Composable
private fun LayersButton(
    sets: List<TrackingSet>,
    picked: Map<String, String>,
    enabled: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val text = picked.values.filter { it.isNotBlank() }.joinToString(", ")
        .ifBlank { str(S.desktop_ce_process_add_layers) }
    ZillitButton(
        text = text,
        onClick = onClick,
        variant = ButtonVariant.Secondary,
        size = ButtonSize.Small,
        enabled = enabled && (sets.isNotEmpty() || picked.isNotEmpty()),
        modifier = modifier,
    )
}

/**
 * The Layers picker (`TrackingCodesPicker`): one select per active set over
 * its codes; picks are a draft until Save, and a set left on "— none —" gets
 * no key at all.
 */
@Composable
private fun LayersDialog(
    sets: List<TrackingSet>,
    current: Map<String, String>,
    onDismiss: () -> Unit,
    onSave: (Map<String, String>) -> Unit,
) {
    var draft by remember(current) { mutableStateOf(current) }
    ZillitDialogShell(
        title = str(S.desktop_ce_process_sub_codes),
        icon = ZillitIcons.Hierarchy,
        visible = true,
        width = LAYERS_DIALOG_WIDTH,
        onDismiss = onDismiss,
        actions = {
            Spacer(Modifier.weight(1f))
            ZillitButton(text = str(S.cancel), onClick = onDismiss, variant = ButtonVariant.Tertiary)
            ZillitButton(text = str(S.save), onClick = { onSave(draft.filterValues { it.isNotBlank() }) })
        },
    ) {
        if (sets.isEmpty()) {
            ZillitText(
                text = str(S.desktop_tax_no_layers),
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textMuted,
            )
        }
        sets.forEach { set ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            ) {
                ZillitText(
                    text = set.name,
                    style = ZillitTheme.typography.bodyMedium,
                    modifier = Modifier.width(SET_NAME_WIDTH),
                )
                ZillitSelect(
                    value = draft[set.id].orEmpty(),
                    options = listOf("") + set.nodes.map { it.code },
                    onSelect = { code -> draft = if (code.isBlank()) draft - set.id else draft + (set.id to code) },
                    label = { code ->
                        if (code.isBlank()) {
                            str(S.desktop_tax_none_dash)
                        } else {
                            set.nodes.firstOrNull { it.code == code }?.let { "${it.code} · ${it.label}" } ?: code
                        }
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** Account tags from Production Setup; a saved tag no longer on the list still shows. */
@Composable
private fun TagsField(
    refs: ProcessRefs,
    selected: List<String>,
    enabled: Boolean,
    modifier: Modifier,
    onChange: (List<String>) -> Unit,
) {
    ZillitMultiSelect(
        selected = selected,
        options = (refs.assetTags + selected).distinct(),
        label = { it },
        onChange = onChange,
        placeholder = str(S.desktop_tax_add_tags),
        enabled = enabled,
        emptyText = str(S.desktop_ce_process_no_more_tags),
        modifier = modifier,
    )
}

/**
 * A money field that keeps what is typed ("12." stays "12.") and only takes
 * the model's figure when it really changed — a split re-cut, say.
 */
@Composable
private fun AmountField(
    value: Double,
    enabled: Boolean,
    modifier: Modifier,
    suffix: String? = null,
    onCommit: (Double) -> Unit,
) {
    var text by remember { mutableStateOf(value.plain()) }
    LaunchedEffect(value) {
        if (text.parseAmount() != value) text = value.plain()
    }
    ZillitTextField(
        value = text,
        onValueChange = { typed ->
            text = typed.filter { it.isDigit() || it == '.' || it == ',' }
            text.parseAmount()?.let(onCommit)
        },
        enabled = enabled,
        placeholder = "0.00",
        keyboardType = KeyboardType.Decimal,
        trailingContent = suffix?.let { { ZillitText(text = it, color = ZillitTheme.colors.textMuted) } },
        modifier = modifier,
    )
}

private fun String.parseAmount(): Double? =
    trim().replace(",", "").let { if (it.isEmpty()) 0.0 else it.toDoubleOrNull() }

private fun Double.plain(): String = when {
    this == 0.0 -> ""
    this == kotlin.math.floor(this) -> toLong().toString()
    else -> toString()
}

@Composable
private fun GridRow(header: Boolean = false, selected: Boolean = false, content: @Composable RowScope.() -> Unit) {
    val colors = ZillitTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                when {
                    header -> colors.surfaceSunken
                    selected -> colors.accentSoft
                    else -> Color.Transparent
                },
                ZillitTheme.shapes.small,
            )
            .padding(horizontal = ZillitTheme.spacing.xs, vertical = ZillitTheme.spacing.xxs),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        verticalAlignment = if (header) Alignment.CenterVertically else Alignment.Top,
        content = content,
    )
}

@Composable
private fun HeaderCell(text: String, modifier: Modifier) {
    ZillitText(
        text = text.uppercase(),
        style = ZillitTheme.typography.columnHeader,
        color = ZillitTheme.colors.textMuted,
        maxLines = 1,
        modifier = modifier.padding(vertical = ZillitTheme.spacing.xs),
    )
}

@Composable
private fun TotalCell(label: String, value: String, strong: Boolean = false) {
    Column(horizontalAlignment = Alignment.End) {
        ZillitText(
            text = label.uppercase(),
            style = ZillitTheme.typography.columnHeader,
            color = ZillitTheme.colors.textMuted,
        )
        ZillitText(
            text = value,
            style = ZillitTheme.typography.numeric.copy(
                fontWeight = if (strong) FontWeight.Bold else FontWeight.Normal,
            ),
            color = if (strong) ZillitTheme.colors.accentText else ZillitTheme.colors.textPrimary,
        )
    }
}

/** The web's `OTHER_TAX_OPTION` — a rate typed in, not a Production Setup type. */
private const val OTHER = "other"
private const val MAX_RATE = 100.0
private const val CENTS = 100.0
private val SELECT_WIDTH = 28.dp
private val CODE_WIDTH = 150.dp
private val LAYERS_WIDTH = 132.dp
private val TAGS_WIDTH = 150.dp
private val AMOUNT_WIDTH = 130.dp
private val TAX_WIDTH = 160.dp
private val REMOVE_WIDTH = 36.dp
private val SPLIT_INDENT = 16.dp
private val SET_NAME_WIDTH = 140.dp
private val LAYERS_DIALOG_WIDTH = 520.dp
