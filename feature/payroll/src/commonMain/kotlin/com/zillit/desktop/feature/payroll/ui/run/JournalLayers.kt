package com.zillit.desktop.feature.payroll.ui.run

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitMultiSelect
import com.zillit.desktop.core.designsystem.component.ZillitSelect
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.payroll.domain.JournalReference
import com.zillit.desktop.feature.payroll.domain.TrackingSet

/**
 * The journal's Layers and Tags cells.
 *
 * Layers are the production's tracking dimensions — one code per set, picked
 * in a dialog because a set can have many codes and the ledger's columns are
 * already narrow. Tags are the project's Account Tags, picked inline.
 *
 * Both are per LINE and per ALLOCATION: a split line's children each carry
 * their own, cloned from the parent when the split is made, because coding one
 * half of a cost to a different layer is the reason to split it at all.
 */
@Composable
internal fun LayersCell(
    sets: List<TrackingSet>,
    picked: Map<String, String>,
    editable: Boolean,
    modifier: Modifier = Modifier,
    onChange: (Map<String, String>) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    // The codes themselves, because a set NAME says nothing about which code
    // was picked and the cell has room for only one line.
    val label = picked.values.filter { it.isNotBlank() }.joinToString(", ")
        .ifBlank { str(S.desktop_ce_process_add_layers) }
    ZillitButton(
        text = label,
        onClick = { open = true },
        variant = ButtonVariant.Tertiary,
        size = ButtonSize.Small,
        // Nothing to configure and nothing configured is nothing to open.
        enabled = editable && (sets.isNotEmpty() || picked.isNotEmpty()),
        modifier = modifier,
    )
    if (open) {
        LayersDialog(sets, picked, onDismiss = { open = false }) {
            onChange(it)
            open = false
        }
    }
}

/** One select per set. A set left on "— none —" carries no key at all. */
@Composable
private fun LayersDialog(
    sets: List<TrackingSet>,
    current: Map<String, String>,
    onDismiss: () -> Unit,
    onSave: (Map<String, String>) -> Unit,
) {
    var draft by remember(current) { mutableStateOf(current) }
    ZillitDialogShell(
        title = str(S.desktop_layers),
        icon = ZillitIcons.Hierarchy,
        visible = true,
        width = DIALOG_WIDTH,
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
                modifier = Modifier.fillMaxWidth(),
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

/**
 * The account tags. A tag saved before it was removed from Production Setup
 * still shows, so history is never hidden by a later edit to the list.
 */
@Composable
internal fun TagsCell(
    reference: JournalReference,
    selected: List<String>,
    editable: Boolean,
    modifier: Modifier = Modifier,
    onChange: (List<String>) -> Unit,
) {
    ZillitMultiSelect(
        selected = selected,
        options = (reference.assetTags + selected).distinct(),
        label = { it },
        onChange = onChange,
        placeholder = str(S.desktop_tax_add_tags),
        enabled = editable,
        emptyText = str(S.desktop_ce_process_no_more_tags),
        modifier = modifier,
    )
}

private val DIALOG_WIDTH: Dp = 520.dp
private val SET_NAME_WIDTH: Dp = 150.dp
