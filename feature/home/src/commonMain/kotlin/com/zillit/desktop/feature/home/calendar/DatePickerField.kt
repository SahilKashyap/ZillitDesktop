package com.zillit.desktop.feature.home.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DropdownMenu
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus

/**
 * A date field with a calendar to pick from.
 *
 * ## Typing still works
 *
 * The field stays editable rather than becoming a button that only opens the
 * grid. Someone entering twenty shoot days types faster than they click, and a
 * picker that takes the keyboard away is the one people complain about. The
 * grid is an alternative, not a replacement — which is also why an unparseable
 * date is left alone rather than corrected.
 */
@Composable
internal fun DatePickerField(
    value: String,
    onValueChange: (String) -> Unit,
    today: LocalDate,
    modifier: Modifier = Modifier,
    placeholder: String = "Date — 2026-08-04",
    errorText: String? = null,
    weekStart: DayOfWeek = DayOfWeek.MONDAY,
) {
    var open by remember { mutableStateOf(false) }
    // Which month the grid shows. Seeded from the field, and kept while the
    // popup is up so paging away does not snap back on every recomposition.
    var visibleMonth by remember(value, open) { mutableStateOf(pickerMonthFor(value, today)) }

    Box(modifier) {
        ZillitTextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = placeholder,
            errorText = errorText,
            modifier = Modifier.fillMaxWidth(),
            trailingContent = {
                ZillitIconButton(
                    icon = ZillitIcons.Calendar,
                    contentDescription = "Choose a date",
                    onClick = { open = true },
                )
            },
        )

        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            MonthPicker(
                month = visibleMonth,
                selected = value.toLocalDateOrNull(),
                today = today,
                weekStart = weekStart,
                onMonth = { visibleMonth = it },
                onPick = { date ->
                    onValueChange(date.isoText())
                    open = false
                },
            )
        }
    }
}

/**
 * Which month to open on.
 *
 * The date already in the field when it is readable, so reopening a picker
 * lands where the user left it — otherwise today, which is where a blank field
 * most likely wants to be.
 */
internal fun pickerMonthFor(text: String, today: LocalDate): LocalDate =
    text.trim().toLocalDateOrNull() ?: today

@Composable
private fun MonthPicker(
    month: LocalDate,
    selected: LocalDate?,
    today: LocalDate,
    weekStart: DayOfWeek,
    onMonth: (LocalDate) -> Unit,
    onPick: (LocalDate) -> Unit,
) {
    val grid = monthGrid(month, weekStart)

    Column(
        modifier = Modifier.width(PICKER_WIDTH).padding(ZillitTheme.spacing.sm),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ZillitIconButton(
                icon = ZillitIcons.ChevronLeft,
                contentDescription = "Previous month",
                onClick = { onMonth(month.plus(-1, DateTimeUnit.MONTH)) },
            )
            ZillitText(
                text = grid.month.monthTitle(),
                style = ZillitTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
            ZillitIconButton(
                icon = ZillitIcons.ChevronRight,
                contentDescription = "Next month",
                onClick = { onMonth(month.plus(1, DateTimeUnit.MONTH)) },
            )
        }

        Row(Modifier.fillMaxWidth()) {
            grid.weeks.first().forEach { day ->
                ZillitText(
                    text = day.date.dayOfWeek.name.take(1),
                    style = ZillitTheme.typography.labelSmall,
                    color = ZillitTheme.colors.textMuted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        grid.weeks.forEach { week ->
            Row(Modifier.fillMaxWidth()) {
                week.forEach { day ->
                    DayButton(
                        day = day,
                        isSelected = day.date == selected,
                        isToday = day.date == today,
                        onPick = onPick,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun DayButton(
    day: MonthGrid.GridDay,
    isSelected: Boolean,
    isToday: Boolean,
    onPick: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = ZillitTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()

    Box(
        modifier = modifier.padding(CELL_GAP),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(CELL_SIZE)
                .clip(ZillitTheme.shapes.small)
                .background(
                    when {
                        isSelected -> colors.accent
                        hovered -> colors.surfaceHover
                        else -> colors.surface
                    },
                )
                .hoverable(interaction)
                // Days from the neighbouring months are pickable, not just
                // decoration: the 1st of next month is often exactly what
                // someone scrolling to the end of a month wants.
                .clickable { onPick(day.date) },
            contentAlignment = Alignment.Center,
        ) {
            ZillitText(
                text = day.date.day.toString(),
                style = ZillitTheme.typography.labelSmall.copy(
                    fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
                ),
                color = when {
                    isSelected -> colors.textOnAccent
                    !day.inMonth -> colors.textMuted
                    isToday -> colors.accentText
                    else -> colors.textPrimary
                },
            )
        }
    }
}

/** "August 2026". */
private fun LocalDate.monthTitle(): String {
    val name = month.name.lowercase().replaceFirstChar { it.uppercase() }
    return "$name $year"
}

private val PICKER_WIDTH = 280.dp
private val CELL_SIZE = 32.dp
private val CELL_GAP = 1.dp
