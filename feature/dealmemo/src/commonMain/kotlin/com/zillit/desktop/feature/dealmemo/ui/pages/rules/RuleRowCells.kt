package com.zillit.desktop.feature.dealmemo.ui.pages.rules

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTooltip
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.dealmemo.domain.rules.BulkRuleRow
import com.zillit.desktop.feature.dealmemo.domain.rules.RuleNamePresets
import com.zillit.desktop.feature.dealmemo.domain.rules.RuleOptions
import com.zillit.desktop.feature.dealmemo.domain.rules.RuleTemplate
import com.zillit.desktop.feature.dealmemo.domain.rules.TriggerField
import com.zillit.desktop.feature.dealmemo.ui.components.DmType
import com.zillit.desktop.feature.dealmemo.ui.pages.preview.CoaCodeField
import com.zillit.desktop.feature.dealmemo.ui.pages.preview.shadowed
import com.zillit.desktop.feature.dealmemo.ui.preview.CoaState

private const val CUSTOM = "__custom__"

/** Every editable cell of one rule row, after its number. */
@Suppress("LongMethod")
@Composable
internal fun RowScope.RuleCellsRow(
    row: BulkRuleRow,
    tried: Boolean,
    coa: CoaState,
    patch: (BulkRuleRow) -> Unit,
    onRemove: () -> Unit,
) {
    GridCell(1) {
        GridSelect(
            value = row.template?.id.orEmpty(),
            options = RuleTemplate.entries.map { GridOption(it.id, it.label, group = it.group) },
            onPick = { id -> patch(row.withTemplate(RuleTemplate.byId(id))) },
            placeholder = "Select rule type…",
            required = tried && row.template == null,
            menuWidth = 260.dp,
        )
    }
    GridCell(2) { NameCell(row, tried, patch) }
    GridCell(3) {
        GridSelect(
            value = row.rateType,
            options = RuleOptions.RATE_TYPES.map { (value, label) -> GridOption(value, label) },
            onPick = { patch(row.copy(rateType = it)) },
        )
    }
    GridCell(4) {
        GridInput(
            value = row.amount,
            onValueChange = { patch(row.copy(amount = it)) },
            placeholder = "0",
            mono = true,
            required = tried && row.amount.isEmpty(),
            filter = numberFilter,
        )
    }
    GridCell(5) {
        GridSelect(
            value = row.basis,
            options = RuleOptions.BASES.map { (value, label) -> GridOption(value, label) },
            onPick = { patch(row.copy(basis = it)) },
        )
    }
    GridCell(6) { TriggerCell(row, patch) }
    GridCell(7) {
        GridSelect(
            value = row.dayType,
            options = RuleOptions.DAY_TYPES.map { (value, label) -> GridOption(value, label) },
            onPick = { patch(row.copy(dayType = it)) },
            mono = true,
            menuWidth = 140.dp,
        )
    }
    GridCell(8) { IncrementCell(row, patch) }
    GridCell(9, center = true) {
        ZillitTooltip(text = "Basic + OT on Top — pays base × (1 + amount) instead of base × amount") {
            CheckBox(checked = row.isEnhancement, onToggle = { patch(row.copy(isEnhancement = !row.isEnhancement)) })
        }
    }
    GridCell(10) {
        GridInput(row.bdrMin, { patch(row.copy(bdrMin = it)) }, "—", mono = true, prefix = "£", filter = numberFilter)
    }
    GridCell(11) {
        GridInput(row.bdrMax, { patch(row.copy(bdrMax = it)) }, "—", mono = true, prefix = "£", filter = numberFilter)
    }
    GridCell(12) {
        GridInput(row.cap, { patch(row.copy(cap = it)) }, "—", mono = true, prefix = "£", filter = numberFilter)
    }
    GridCell(13) {
        CoaCodeField(
            value = row.nominal,
            onValueChange = { patch(row.copy(nominal = it)) },
            coa = coa,
            placeholder = "—",
            alignEnd = false,
            borderless = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    GridCell(14) { GridInput(row.note, { patch(row.copy(note = it)) }, "Statute ref, edge cases…") }
    GridCell(15, center = true) {
        ZillitTooltip(text = "Remove rule") { GridIcon(ZillitIcons.Trash, onClick = onRemove, danger = true) }
    }
}

/**
 * The Name column: the picked type's presets — each sets the whole rule —
 * or "Fill in…" for a name typed by hand.
 */
@Composable
private fun NameCell(row: BulkRuleRow, tried: Boolean, patch: (BulkRuleRow) -> Unit) {
    val presets = RuleNamePresets.of(row.template)
    val isPreset = presets.any { it.label == row.label }
    var custom by remember(row.uid) { mutableStateOf(row.label.isNotEmpty() && !isPreset) }
    if (custom || (row.label.isNotEmpty() && !isPreset)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Box(Modifier.weight(1f)) {
                GridInput(row.label, { patch(row.copy(label = it)) }, "Name", required = tried && row.label.isBlank())
            }
            ZillitTooltip(text = "Clear and pick from the list") {
                GridIcon(ZillitIcons.Close, onClick = {
                    custom = false
                    patch(row.copy(label = ""))
                })
            }
        }
        return
    }
    GridSelect(
        value = if (isPreset) row.label else "",
        options = presets.map { GridOption(it.label, it.label) } + GridOption(CUSTOM, "Fill in…"),
        onPick = { picked ->
            if (picked == CUSTOM) {
                custom = true
                patch(row.copy(label = ""))
            } else {
                presets.firstOrNull { it.label == picked }?.let { patch(row.withPreset(it)) }
            }
        },
        placeholder = if (row.template != null) "Pick a name…" else "Pick a rule type first…",
        required = tried && row.label.isBlank(),
        menuWidth = 280.dp,
    )
}

/** The trigger for the template's one form field: hours, a clock time, day kinds — or nothing. */
@Composable
private fun TriggerCell(row: BulkRuleRow, patch: (BulkRuleRow) -> Unit) {
    when (row.template?.field) {
        TriggerField.Hours -> HoursCell(row, patch)
        TriggerField.Time -> GridInput(
            value = row.form.time,
            onValueChange = { patch(row.copy(form = row.form.copy(time = it))) },
            placeholder = "00:00",
            mono = true,
            filter = { it.length <= CLOCK_LENGTH && Regex("^[0-9:]*$").matches(it) },
        )
        TriggerField.DayKinds -> DayKindsCell(row, patch)
        TriggerField.None, null -> ZillitText(
            text = "—",
            style = DmType.sans(12.5.sp),
            color = rp.ink3,
            modifier = Modifier.padding(start = 10.dp),
        )
    }
}

/** Whole hours 1–24, or "Fill in…" for a half hour. */
@Composable
private fun HoursCell(row: BulkRuleRow, patch: (BulkRuleRow) -> Unit) {
    val value = row.form.hours
    val preset = value.isNotEmpty() && value in HOURS.map { it.toString() }
    var custom by remember(row.uid) { mutableStateOf(value.isNotEmpty() && !preset) }
    fun set(hours: String) = patch(row.copy(form = row.form.copy(hours = hours)))
    if (custom || (value.isNotEmpty() && !preset)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                GridInput(value, ::set, "N", mono = true, suffix = "hrs", filter = numberFilter)
            }
            GridIcon(ZillitIcons.Close, onClick = {
                custom = false
                set("")
            }, size = 22.dp)
        }
        return
    }
    GridSelect(
        value = value,
        options = listOf(GridOption("", "N hrs")) +
            HOURS.map { GridOption(it.toString(), "$it hrs") } +
            GridOption(CUSTOM, "Fill in…"),
        onPick = { picked ->
            if (picked == CUSTOM) {
                custom = true
                set("")
            } else {
                set(picked)
            }
        },
        placeholder = "N hrs",
        mono = true,
        menuWidth = 140.dp,
    )
}

/** The OT rounding step: the usual 5–45 minutes, or "Fill in…" for 1–59. */
@Composable
private fun IncrementCell(row: BulkRuleRow, patch: (BulkRuleRow) -> Unit) {
    val value = row.increment
    val preset = value.isNotEmpty() && value in RuleOptions.INCREMENTS.map { it.toString() }
    var custom by remember(row.uid) { mutableStateOf(value.isNotEmpty() && !preset) }
    fun set(next: String) = patch(row.copy(increment = next))
    if (custom) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                GridInput(
                    value = value,
                    onValueChange = { typed ->
                        set(typed.toIntOrNull()?.coerceIn(1, MAX_INCREMENT)?.toString() ?: "")
                    },
                    placeholder = "1–59",
                    mono = true,
                    suffix = "m",
                    filter = { it.isEmpty() || it.all(Char::isDigit) },
                )
            }
            GridIcon(ZillitIcons.Close, onClick = {
                custom = false
                set("")
            }, size = 22.dp)
        }
        return
    }
    GridSelect(
        value = value,
        options = listOf(GridOption("", "—")) + RuleOptions.INCREMENTS.map { GridOption(it.toString(), "$it mins") } +
            GridOption(CUSTOM, "Fill in…"),
        onPick = { picked ->
            if (picked == CUSTOM) {
                custom = true
                set("")
            } else {
                set(picked)
            }
        },
        placeholder = "—",
        mono = true,
        menuWidth = 140.dp,
    )
}

/** The day kinds a premium fires on — "Any of…" until some are ticked. */
@Suppress("LongMethod")
@Composable
private fun DayKindsCell(row: BulkRuleRow, patch: (BulkRuleRow) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val kinds = row.form.dayKinds
    val label = kinds.joinToString(", ") { kind ->
        RuleOptions.DAY_KINDS.firstOrNull { it.first == kind }?.second ?: kind
    }
    Box(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(34.dp)
                .clip(RoundedCornerShape(5.dp))
                .clickable { open = !open }
                .pointerHoverIcon(PointerIcon.Hand)
                .padding(start = 10.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ZillitText(
                text = label.ifEmpty { "Any of…" },
                style = DmType.sans(12.sp),
                color = if (kinds.isEmpty()) rp.ink3 else rp.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            ZillitIcon(ZillitIcons.ChevronDown, size = 11.dp, tint = rp.ink3)
        }
        if (open) {
            Popup(
                popupPositionProvider = remember { BelowStartPosition(gap = 4) },
                onDismissRequest = { open = false },
                properties = PopupProperties(focusable = true),
            ) {
                val shape = RoundedCornerShape(10.dp)
                Column(
                    modifier = Modifier
                        .widthIn(min = 190.dp)
                        .shadowed(shape)
                        .clip(shape)
                        .background(rp.surface)
                        .border(1.dp, rp.border, shape)
                        .padding(5.dp),
                ) {
                    RuleOptions.DAY_KINDS.forEach { (value, name) ->
                        val on = value in kinds
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    val next = if (on) kinds - value else kinds + value
                                    patch(row.copy(form = row.form.copy(dayKinds = next)))
                                }
                                .pointerHoverIcon(PointerIcon.Hand)
                                .padding(horizontal = 9.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(9.dp),
                        ) {
                            CheckBox(checked = on, onToggle = null)
                            ZillitText(text = name, style = DmType.sans(12.5.sp), color = rp.ink)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CheckBox(checked: Boolean, onToggle: (() -> Unit)?) {
    val shape = RoundedCornerShape(5.dp)
    Box(
        modifier = Modifier
            .size(18.dp)
            .clip(shape)
            .background(if (checked) rp.cta else Color.Transparent)
            .border(1.5.dp, if (checked) rp.cta else rp.borderStrong, shape)
            .then(
                if (onToggle != null) {
                    Modifier.clickable(onClick = onToggle).pointerHoverIcon(PointerIcon.Hand)
                } else {
                    Modifier
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) ZillitIcon(ZillitIcons.Check, size = 11.dp, tint = Color.White)
    }
}

private val HOURS = 1..24
private const val MAX_INCREMENT = 59
private const val CLOCK_LENGTH = 5
