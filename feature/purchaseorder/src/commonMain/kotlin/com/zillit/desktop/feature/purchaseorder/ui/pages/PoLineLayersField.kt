// The line grid's Layers picker — shared by the New PO form and PO Entry.
package com.zillit.desktop.feature.purchaseorder.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitScrollColumn
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
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
    val text = picked.values.filter { it.isNotBlank() }.joinToString(", ")
        .ifBlank { str(S.desktop_ce_process_add_layers) }
    Box(modifier) {
        ZillitButton(
            text = text,
            onClick = { open = true },
            variant = ButtonVariant.Secondary,
            size = ButtonSize.Small,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        )
        if (open) {
            PoLayersPopup(sets, picked, onDismiss = { open = false }) { next -> onChange(next) }
        }
    }
}

@Composable
private fun PoLayersPopup(
    sets: List<TrackingSet>,
    picked: Map<String, String>,
    onDismiss: () -> Unit,
    onChange: (Map<String, String>) -> Unit,
) {
    val colors = ZillitTheme.colors
    Popup(
        offset = IntOffset(0, POPUP_DROP),
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        Column(
            modifier = Modifier
                .width(LAYERS_POPUP_WIDTH)
                .clip(ZillitTheme.shapes.large)
                .background(colors.surfaceRaised)
                .border(1.dp, colors.border, ZillitTheme.shapes.large)
                .padding(vertical = ZillitTheme.spacing.xs),
        ) {
            val shown = sets.filter { it.active }
            if (shown.isEmpty()) {
                ZillitText(
                    text = str(S.desktop_tax_no_layers),
                    style = ZillitTheme.typography.bodySmall,
                    color = colors.textMuted,
                    modifier = Modifier.padding(ZillitTheme.spacing.md),
                )
            }
            ZillitScrollColumn(modifier = Modifier.heightIn(max = LAYERS_POPUP_HEIGHT)) {
                shown.forEach { set ->
                    PoLayersHeading(set.name.ifBlank { set.prefix })
                    val current = picked[set.id].orEmpty()
                    val currentCode = set.resolve(current)?.code ?: current
                    PoLayersOption(str(S.desktop_tax_none_dash), selected = currentCode.isBlank()) {
                        onChange(picked - set.id)
                    }
                    set.pickable.forEach { node ->
                        PoLayersOption(node.optionLabel, selected = node.code == currentCode) {
                            onChange(picked + (set.id to node.code))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PoLayersHeading(text: String) {
    ZillitText(
        text = text.uppercase(),
        style = ZillitTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
        color = ZillitTheme.colors.textMuted,
        modifier = Modifier.padding(
            start = ZillitTheme.spacing.md,
            top = ZillitTheme.spacing.sm,
            bottom = ZillitTheme.spacing.xxs,
        ),
        maxLines = 1,
    )
}

@Composable
private fun PoLayersOption(text: String, selected: Boolean, onClick: () -> Unit) {
    val colors = ZillitTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(if (selected) colors.surfaceSelected else colors.surfaceRaised)
            .padding(horizontal = ZillitTheme.spacing.md, vertical = ZillitTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        ZillitText(
            text = text,
            style = ZillitTheme.typography.bodySmall,
            color = if (selected) colors.accentText else colors.textPrimary,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        if (selected) ZillitIcon(icon = ZillitIcons.Check, tint = colors.accentText, size = CHECK_SIZE)
    }
}

private val LAYERS_POPUP_WIDTH = 280.dp
private val LAYERS_POPUP_HEIGHT = 320.dp
private val CHECK_SIZE = 14.dp
private const val POPUP_DROP = 32
