// The line grid's Layers picker — shared by the New PO form and PO Entry.
package com.zillit.desktop.feature.purchaseorder.ui.pages

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitOptionPopup
import com.zillit.desktop.core.designsystem.component.popupWidth
import com.zillit.desktop.core.designsystem.component.rememberZillitSelectAnchor
import com.zillit.desktop.core.designsystem.component.zillitSelectAnchor
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.purchaseorder.domain.TrackingSet

/** The Layers column's width, shared by the header and every row's cell. */
internal val PO_LAYERS_WIDTH = 120.dp

/**
 * A line's Layers — the web's compact `TrackingCodesPicker`: the picked codes
 * (or "+ Layers") on a button that opens every active set with its codes;
 * picking a code sets that set, "— none —" clears it. Accountant-only, as the
 * web's own field is (`POForm.jsx`'s `lineItemSystemLabels` filter) — the
 * picks are an accounting dimension a department raiser has no reason to see.
 */
@Composable
internal fun PoLineLayersField(
    sets: List<TrackingSet>,
    picked: Map<String, String>,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onChange: (Map<String, String>) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val anchor = rememberZillitSelectAnchor()
    val text = picked.values.filter { it.isNotBlank() }.joinToString(", ")
        .ifBlank { str(S.desktop_ce_process_add_layers) }
    Box(modifier.zillitSelectAnchor(anchor)) {
        ZillitButton(
            text = text,
            onClick = { open = true },
            variant = ButtonVariant.Secondary,
            size = ButtonSize.Small,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        )
        if (open) {
            val none = str(S.desktop_tax_none_dash)
            val rows = sets.filter { it.active }.flatMap { set ->
                listOf(PoLayerRow(set, null, none)) + set.pickable.map { PoLayerRow(set, it.code, it.optionLabel) }
            }
            // One pick per set: a code sets its set, "none" clears it, and the
            // list stays open so every set can be coded in one visit.
            ZillitOptionPopup(
                onDismiss = { open = false },
                width = maxOf(anchor.popupWidth(), LAYERS_POPUP_WIDTH),
                options = rows,
                isSelected = { row ->
                    val current = picked[row.set.id].orEmpty()
                    val currentCode = row.set.resolve(current)?.code ?: current
                    if (row.code == null) currentCode.isBlank() else row.code == currentCode
                },
                onPick = { row ->
                    onChange(if (row.code == null) picked - row.set.id else picked + (row.set.id to row.code))
                },
                label = PoLayerRow::label,
                searchText = { "${it.heading} ${it.label}" },
                showInitials = false,
                section = PoLayerRow::heading,
                emptyText = str(S.desktop_tax_no_layers),
            )
        }
    }
}

/** One row of the Layers list: a set's code, or its "none" ([code] null). */
private data class PoLayerRow(val set: TrackingSet, val code: String?, val label: String) {
    val heading: String get() = set.name.ifBlank { set.prefix }
}

private val LAYERS_POPUP_WIDTH = 280.dp
