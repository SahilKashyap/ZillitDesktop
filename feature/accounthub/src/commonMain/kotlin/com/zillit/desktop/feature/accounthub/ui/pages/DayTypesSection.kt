package com.zillit.desktop.feature.accounthub.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDivider
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitNotice
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.accounthub.domain.DayType
import com.zillit.desktop.feature.accounthub.domain.DayTypes
import com.zillit.desktop.feature.accounthub.ui.AccountHubEvent
import com.zillit.desktop.feature.accounthub.ui.AccountHubUiState
import com.zillit.desktop.feature.accounthub.ui.SetupSection

private val CODE_WIDTH = 120.dp
private val MINUTES_WIDTH = 150.dp

/**
 * The project's day-type catalogue.
 *
 * Non-union deals copy this list into their own when they are saved; a union
 * deal takes its day types from the agreement instead. Rendered beside the
 * pay breakdown because that is where it is read from, but saved on its own
 * endpoint — editing a day type must not re-save the overtime, premium and
 * penalty rules next to it.
 */
@Composable
internal fun ColumnScope.DayTypesSection(
    state: AccountHubUiState,
    onEvent: (AccountHubEvent) -> Unit,
) {
    val section = state.setup.dayTypes
    val rows = section.edited
    val editable = state.viewer.canEdit

    SetupSectionCard(
        title = "Day Types",
        description = "What a working day is on this production, and the meal break that " +
            "drives the too-short-break penalty. Non-union deals take their day types from here.",
        dirty = section.dirty,
        saving = section.saving,
        onSave = { onEvent(AccountHubEvent.SaveSection(SetupSection.DayTypes)) },
        onRevert = { onEvent(AccountHubEvent.RevertSection(SetupSection.DayTypes)) },
        editable = editable,
    ) {
        rows.forEachIndexed { index, row ->
            DayTypeRow(row, index, rows, editable, onEvent)
        }

        DayTypes.problem(rows)?.let { problem ->
            ZillitNotice(
                text = problem,
                tone = StatusTone.Rejected,
                icon = ZillitIcons.Warning,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        if (editable) {
            ZillitButton(
                text = "Add a day type",
                onClick = { onEvent(AccountHubEvent.EditDayTypes(rows + DayType(dayType = ""))) },
                variant = ButtonVariant.Tertiary,
                size = ButtonSize.Small,
                leadingIcon = ZillitIcons.Add,
                enabled = rows.size < DayTypes.MAX_ROWS,
            )
        }
    }
}

@Composable
private fun ColumnScope.DayTypeRow(
    row: DayType,
    index: Int,
    rows: List<DayType>,
    editable: Boolean,
    onEvent: (AccountHubEvent) -> Unit,
) {
    // The three defaults are keyed by code: the pay engine looks a day type up
    // by it, and a renamed SWD is a standard working day nothing recognises.
    // Their figures stay editable — a production that runs a nine-hour
    // standard day says so here.
    val locked = DayTypes.isDefault(row)
    val update: (DayType) -> Unit = { next ->
        onEvent(AccountHubEvent.EditDayTypes(rows.toMutableList().also { it[index] = next }))
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            ZillitTextField(
                value = row.dayType,
                onValueChange = { update(row.copy(dayType = it.trim())) },
                label = "Code",
                enabled = editable && !locked,
                modifier = Modifier.width(CODE_WIDTH),
            )
            ZillitTextField(
                value = row.label,
                onValueChange = { update(row.copy(label = it)) },
                label = "Name",
                enabled = editable,
                modifier = Modifier.weight(1f),
            )
            MinutesField(
                value = row.workMinutes,
                label = "Working minutes",
                helper = null,
                enabled = editable,
            ) { update(row.copy(workMinutes = it)) }
            MinutesField(
                value = row.mealBreakMinutes,
                label = "Meal break",
                // Empty and zero mean different things here, and the engine
                // reads them differently, so the field says which is which.
                helper = "Blank means unspecified; 0 means no formal break.",
                enabled = editable,
            ) { update(row.copy(mealBreakMinutes = it)) }

            if (locked) {
                ZillitStatusPill(label = "Standard", tone = StatusTone.Pending)
            } else if (editable) {
                ZillitIconButton(
                    icon = ZillitIcons.Trash,
                    contentDescription = "Remove ${row.dayType.ifBlank { "this day type" }}",
                    onClick = {
                        onEvent(
                            AccountHubEvent.EditDayTypes(rows.filterIndexed { at, _ -> at != index }),
                        )
                    },
                )
            }
        }
        ZillitDivider()
    }
}

@Composable
private fun MinutesField(
    value: Int?,
    label: String,
    helper: String?,
    enabled: Boolean,
    onChange: (Int?) -> Unit,
) {
    ZillitTextField(
        value = value?.toString().orEmpty(),
        onValueChange = { text ->
            val digits = text.filter { it.isDigit() }
            onChange(digits.takeIf { it.isNotEmpty() }?.toIntOrNull())
        },
        label = label,
        helperText = helper,
        enabled = enabled,
        keyboardType = KeyboardType.Number,
        modifier = Modifier.width(MINUTES_WIDTH),
    )
}
