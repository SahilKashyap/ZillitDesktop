package com.zillit.desktop.core.designsystem.component

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
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.todayIn
import kotlin.time.Clock

/**
 * A date field with a calendar to pick from.
 *
 * Every date in the finance tools is asked for this way, because the web asks
 * for it this way: `<input type="date">` gives a browser a picker for free,
 * and a Compose text field gives one nothing. Typing a date nobody can parse
 * is the single most common way a form refuses to save.
 *
 * ## Typing still works
 *
 * The field stays editable rather than becoming a button that only opens the
 * grid. Someone entering twenty dates types faster than they click, and an
 * unreadable date is left alone rather than corrected under the cursor.
 */
@Composable
fun ZillitDateField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String = "YYYY-MM-DD",
    errorText: String? = null,
    helperText: String? = null,
    enabled: Boolean = true,
    today: LocalDate = Clock.System.todayIn(TimeZone.currentSystemDefault()),
    weekStart: DayOfWeek = DayOfWeek.MONDAY,
) {
    var open by remember { mutableStateOf(false) }
    // Which month the grid shows. Seeded from the field, and kept while the
    // popup is up so paging away does not snap back on every recomposition.
    var visibleMonth by remember(value, open) { mutableStateOf(value.asDate() ?: today) }

    Box(modifier) {
        ZillitTextField(
            value = value,
            onValueChange = onValueChange,
            label = label,
            placeholder = placeholder,
            errorText = errorText,
            helperText = helperText,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
            trailingContent = {
                ZillitIconButton(
                    icon = ZillitIcons.Calendar,
                    contentDescription = str(S.desktop_choose_a_date),
                    onClick = { if (enabled) open = true },
                )
            },
        )

        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            MonthGrid(
                month = visibleMonth,
                selected = value.asDate(),
                today = today,
                weekStart = weekStart,
                onMonth = { visibleMonth = it },
                onPick = { date ->
                    onValueChange(date.iso())
                    open = false
                },
            )
        }
    }
}

/** `YYYY-MM-DD`, or null for anything else — including a half-typed date. */
private fun String.asDate(): LocalDate? = runCatching { LocalDate.parse(trim()) }.getOrNull()

private fun LocalDate.iso(): String = toString()

@Composable
private fun MonthGrid(
    month: LocalDate,
    selected: LocalDate?,
    today: LocalDate,
    weekStart: DayOfWeek,
    onMonth: (LocalDate) -> Unit,
    onPick: (LocalDate) -> Unit,
) {
    val weeks = remember(month, weekStart) { weeksOf(month, weekStart) }
    Column(
        modifier = Modifier.width(PICKER_WIDTH).padding(ZillitTheme.spacing.sm),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ZillitIconButton(
                icon = ZillitIcons.ChevronLeft,
                contentDescription = str(S.desktop_previous_month),
                onClick = { onMonth(month.plus(-1, DateTimeUnit.MONTH)) },
            )
            ZillitText(
                text = month.title(),
                style = ZillitTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
            ZillitIconButton(
                icon = ZillitIcons.ChevronRight,
                contentDescription = str(S.next_month),
                onClick = { onMonth(month.plus(1, DateTimeUnit.MONTH)) },
            )
        }
        Row(Modifier.fillMaxWidth()) {
            weeks.first().forEach { day ->
                ZillitText(
                    text = day.dayOfWeek.name.take(1),
                    style = ZillitTheme.typography.labelSmall,
                    color = ZillitTheme.colors.textMuted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        weeks.forEach { week ->
            Row(Modifier.fillMaxWidth()) {
                week.forEach { date ->
                    DayCell(
                        date = date,
                        inMonth = date.month == month.month,
                        isSelected = date == selected,
                        isToday = date == today,
                        onPick = onPick,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

/** Six weeks covering [month], starting on [weekStart] — the shape every calendar has. */
private fun weeksOf(month: LocalDate, weekStart: DayOfWeek): List<List<LocalDate>> {
    val first = LocalDate(month.year, month.month, 1)
    val lead = (first.dayOfWeek.isoDayNumber - weekStart.isoDayNumber + DAYS_IN_WEEK) % DAYS_IN_WEEK
    val start = first.minus(lead, DateTimeUnit.DAY)
    return (0 until WEEKS_SHOWN).map { week ->
        (0 until DAYS_IN_WEEK).map { day -> start.plus(week * DAYS_IN_WEEK + day, DateTimeUnit.DAY) }
    }
}

private val DayOfWeek.isoDayNumber: Int get() = ordinal + 1

@Composable
private fun DayCell(
    date: LocalDate,
    inMonth: Boolean,
    isSelected: Boolean,
    isToday: Boolean,
    onPick: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = ZillitTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Box(modifier = modifier.padding(CELL_GAP), contentAlignment = Alignment.Center) {
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
                .clickable { onPick(date) },
            contentAlignment = Alignment.Center,
        ) {
            ZillitText(
                text = date.day.toString(),
                style = ZillitTheme.typography.labelSmall.copy(
                    fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
                ),
                color = when {
                    isSelected -> colors.textOnAccent
                    !inMonth -> colors.textMuted
                    isToday -> colors.accentText
                    else -> colors.textPrimary
                },
            )
        }
    }
}

/** "August 2026". */
private fun LocalDate.title(): String =
    month.name.lowercase().replaceFirstChar { it.uppercase() } + " " + year

private val PICKER_WIDTH = 280.dp
private val CELL_SIZE = 32.dp
private val CELL_GAP = 1.dp
private const val DAYS_IN_WEEK = 7
private const val WEEKS_SHOWN = 6
