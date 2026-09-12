package com.zillit.desktop.feature.taxfiling.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.taxfiling.domain.LayerSet
import com.zillit.desktop.feature.taxfiling.ui.components.MtdButton
import com.zillit.desktop.feature.taxfiling.ui.components.MtdButtonVariant
import com.zillit.desktop.feature.taxfiling.ui.components.MtdDropdown
import com.zillit.desktop.feature.taxfiling.ui.components.MtdModal
import com.zillit.desktop.feature.taxfiling.ui.components.MtdOption
import com.zillit.desktop.feature.taxfiling.ui.components.mtdPalette
import com.zillit.desktop.feature.taxfiling.ui.components.mtdText

/**
 * "Layers" — the web's `TrackingCodesPicker` modal.
 *
 * One picker per active tracking set; picks are held as a draft until Save,
 * and Cancel, Escape or the scrim discard them. Only the sets given a code are
 * saved — a set left at "— none —" gets no key at all — and a pick for a set
 * since deleted from the chart is shown struck through, then dropped on Save.
 */
@Composable
internal fun LayersDialog(
    visible: Boolean,
    boxNumber: Int?,
    sets: List<LayerSet>,
    value: Map<String, String>,
    onSave: (Map<String, String>) -> Unit,
    onDismiss: () -> Unit,
) {
    // Seeded each time the dialog opens, so a cancelled edit never survives to the next opening.
    var draft by remember(visible, value) { mutableStateOf(normalise(sets, value)) }
    val orphans = value.filterKeys { key -> sets.none { it.id == key } }

    MtdModal(
        visible = visible,
        title = "Layers",
        subtitle = boxNumber?.let { "Narrow box $it to the ledger entries tagged with these codes." },
        icon = ZillitIcons.Hierarchy,
        width = 520.dp,
        onDismiss = onDismiss,
        footer = {
            if (draft.isNotEmpty()) {
                MtdButton(text = "Clear all", onClick = { draft = emptyMap() }, variant = MtdButtonVariant.Ghost)
            }
            Spacer(Modifier.weight(1f))
            MtdButton(text = "Cancel", onClick = onDismiss, variant = MtdButtonVariant.Ghost)
            MtdButton(
                text = "Save",
                onClick = { onSave(draft.filterKeys { key -> sets.any { it.id == key } }) },
                variant = MtdButtonVariant.Primary,
            )
        },
    ) {
        if (orphans.isNotEmpty()) StaleCallout(orphans.values.toList())
        if (sets.isEmpty()) {
            ZillitText(
                text = "No layers configured. Add one in Chart of Accounts → Layers.",
                style = mtdText(13.sp),
                color = mtdPalette().ink3,
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            sets.forEach { set ->
                SetPicker(
                    set = set,
                    picked = draft[set.id],
                    onPick = { code -> draft = if (code == null) draft - set.id else draft + (set.id to code) },
                )
            }
        }
    }
}

/** Codes for sets that still exist, by code — a legacy node id becomes its code here. */
private fun normalise(sets: List<LayerSet>, value: Map<String, String>): Map<String, String> =
    value.mapNotNull { (setId, picked) ->
        val set = sets.firstOrNull { it.id == setId } ?: return@mapNotNull setId to picked
        setId to (set.codeFor(picked)?.code ?: picked)
    }.toMap()

@Composable
private fun SetPicker(set: LayerSet, picked: String?, onPick: (String?) -> Unit) {
    val palette = mtdPalette()
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(
                Modifier
                    .size(12.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(set.color.toColor() ?: palette.accent),
            )
            ZillitText(
                text = set.name.uppercase(),
                style = mtdText(11.sp, FontWeight.Bold, tracking = 0.05.em),
                color = palette.ink3,
                modifier = Modifier.weight(1f),
            )
            ZillitText(text = set.prefix, style = mtdText(10.5.sp, mono = true), color = palette.muted)
        }
        MtdDropdown(
            value = picked,
            options = set.codes.map { MtdOption(it.code, it.pickerLabel, sub = it.description.ifBlank { null }) },
            onChange = onPick,
            placeholder = "— none —",
            clearable = true,
            modifier = Modifier.fillMaxWidth(),
        )
        set.codeFor(picked.orEmpty())?.description?.takeIf { it.isNotBlank() }?.let { description ->
            ZillitText(text = description, style = mtdText(11.5.sp), color = palette.muted, maxLines = 1)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StaleCallout(codes: List<String>) {
    val palette = mtdPalette()
    val shape = RoundedCornerShape(8.dp)
    val plural = codes.size != 1
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 14.dp)
            .clip(shape)
            .background(palette.accentWash)
            .border(1.dp, palette.accentBorder, shape)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        ZillitText(
            text = "${codes.size} stale pick${if (plural) "s" else ""} on this box",
            style = mtdText(12.sp, FontWeight.Bold),
            color = palette.ink,
        )
        ZillitText(
            text = "The layer${if (plural) "s were" else " was"} removed from Chart of Accounts → Layers. " +
                "Save will clear ${if (plural) "them" else "it"}.",
            style = mtdText(12.sp),
            color = palette.ink2,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            codes.forEach { code ->
                ZillitText(
                    text = code,
                    style = mtdText(10.5.sp, mono = true).copy(textDecoration = TextDecoration.LineThrough),
                    color = palette.ink3,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(palette.surface3)
                        .padding(horizontal = 6.dp, vertical = 1.dp),
                )
            }
        }
    }
}

/** `#RRGGBB` or `#AARRGGBB` to a colour; anything else is no colour. */
internal fun String.toColor(): Color? {
    val hex = trim().removePrefix("#")
    val argb = when (hex.length) {
        RGB -> hex.toLongOrNull(HEX)?.let { it or OPAQUE }
        ARGB -> hex.toLongOrNull(HEX)
        else -> null
    } ?: return null
    return Color(argb)
}

private const val RGB = 6
private const val ARGB = 8
private const val HEX = 16
private const val OPAQUE = 0xFF000000L
