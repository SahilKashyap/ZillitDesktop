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
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.accounthub.domain.DayType
import com.zillit.desktop.feature.accounthub.domain.DayTypes
import com.zillit.desktop.feature.accounthub.ui.AccountHubEvent
import com.zillit.desktop.feature.accounthub.ui.AccountHubUiState
import com.zillit.desktop.feature.accounthub.ui.SetupSection
import com.zillit.desktop.feature.accounthub.ui.components.MonoLabel
import com.zillit.desktop.feature.accounthub.ui.components.SectionLoadState
import com.zillit.desktop.feature.accounthub.ui.components.SubCard
import com.zillit.desktop.feature.accounthub.ui.sectionLoad

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
@Suppress("LongMethod") // A screen, read top to bottom; the order is the reading order.
@Composable
internal fun ColumnScope.DayTypesEditor(
    state: AccountHubUiState,
    onEvent: (AccountHubEvent) -> Unit,
) {
    val section = state.setup.dayTypes
    val rows = section.edited
    val editable = state.viewer.canEdit
    val load = state.sectionLoad(SetupSection.DayTypes, onEvent)

    SubCard(
        title = str(S.desktop_day_types),
        hint = str(S.desktop_hub_the_defaults_are_swd_cwd_and_scwd_add_any_custom),
        action = {
            if (editable && section.dirty && load.ready) {
                ZillitButton(
                    text = str(S.cancel),
                    onClick = { onEvent(AccountHubEvent.RevertSection(SetupSection.DayTypes)) },
                    variant = ButtonVariant.Tertiary,
                    size = ButtonSize.Small,
                    enabled = !section.saving,
                )
                ZillitButton(
                    text = str(S.desktop_save_day_types),
                    onClick = { onEvent(AccountHubEvent.SaveSection(SetupSection.DayTypes)) },
                    size = ButtonSize.Small,
                    loading = section.saving,
                )
            }
        },
    ) {
        // Its own slice: the seeded defaults on screen are not the saved list
        // until the read lands, and saving them would replace it.
        if (!load.ready) {
            SectionLoadState(load)
            return@SubCard
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            modifier = Modifier.fillMaxWidth(),
        ) {
            HeadCell(str(S.code), Modifier.width(CODE_WIDTH))
            HeadCell(str(S.ah_lbl_title), Modifier.weight(1f))
            HeadCell(str(S.desktop_working_min), Modifier.width(MINUTES_WIDTH))
            HeadCell(str(S.desktop_meal_break_min), Modifier.width(MINUTES_WIDTH))
        }
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
                text = str(S.desktop_hub_add_a_day_type),
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
    // standard day says so here. Locked by origin, not by the code typed: a
    // custom row typed as "SWD" used to lock itself, undeletable, while the
    // duplicate refused the save (the web's `isDefault` is stamped the same way).
    val locked = DayTypes.isSeededDefault(index, rows)
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
                onValueChange = { update(row.copy(dayType = it.trim().uppercase())) },
                placeholder = "CWD",
                enabled = editable && !locked,
                modifier = Modifier.width(CODE_WIDTH),
            )
            ZillitTextField(
                value = row.label,
                onValueChange = { update(row.copy(label = it)) },
                placeholder = str(S.desktop_10_hour_day),
                enabled = editable,
                modifier = Modifier.weight(1f),
            )
            MinutesField(
                value = row.workMinutes,
                placeholder = "600",
                enabled = editable,
            ) { update(row.copy(workMinutes = it)) }
            MinutesField(
                value = row.mealBreakMinutes,
                // Empty and zero mean different things here, and the engine
                // reads them differently: blank is unspecified, 0 no formal break.
                placeholder = "—",
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
    placeholder: String,
    enabled: Boolean,
    onChange: (Int?) -> Unit,
) {
    ZillitTextField(
        value = value?.toString().orEmpty(),
        onValueChange = { text ->
            val digits = text.filter { it.isDigit() }
            onChange(digits.takeIf { it.isNotEmpty() }?.toIntOrNull())
        },
        placeholder = placeholder,
        enabled = enabled,
        keyboardType = KeyboardType.Number,
        modifier = Modifier.width(MINUTES_WIDTH),
    )
}

@Composable
private fun HeadCell(text: String, modifier: Modifier) {
    MonoLabel(text, modifier = modifier)
}
