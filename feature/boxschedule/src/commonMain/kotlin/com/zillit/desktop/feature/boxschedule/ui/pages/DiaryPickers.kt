package com.zillit.desktop.feature.boxschedule.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.component.zillitVerticalScroll
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.boxschedule.domain.DiaryCalendar
import com.zillit.desktop.feature.boxschedule.domain.DiaryFormat
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.plus

/** The field look every picker shares — the design system's select, with an error border. */
@Composable
private fun FieldBox(
    enabled: Boolean,
    error: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    val colors = ZillitTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        modifier = modifier
            .defaultMinSize(minHeight = FIELD_HEIGHT)
            .clip(ZillitTheme.shapes.medium)
            .background(if (enabled) colors.surfaceSunken else colors.surfaceSunken.copy(alpha = DISABLED_BG))
            .border(
                1.dp,
                when {
                    error -> colors.danger
                    hovered && enabled -> colors.borderStrong
                    else -> colors.border
                },
                ZillitTheme.shapes.medium,
            )
            .hoverable(interaction)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) { content() }
}

// Date ---------------------------------------------------------------------

/**
 * A date picked from a month grid — antd's `DatePicker`. Past dates, or any
 * the caller refuses, are greyed out and cannot be picked; [markers] dot the
 * days a block already covers.
 */
@Composable
internal fun DiaryDateField(
    value: LocalDate?,
    onPick: (LocalDate?) -> Unit,
    today: LocalDate,
    modifier: Modifier = Modifier,
    placeholder: String = str(S.hint_date),
    format: (LocalDate) -> String = DiaryFormat::mediumDate,
    enabled: Boolean = true,
    clearable: Boolean = false,
    error: Boolean = false,
    selectable: (LocalDate) -> Boolean = { true },
    markers: Set<LocalDate> = emptySet(),
) {
    var open by remember { mutableStateOf(false) }
    val colors = ZillitTheme.colors
    Box(modifier) {
        FieldBox(enabled = enabled, error = error, onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
            ZillitIcon(icon = ZillitIcons.Calendar, tint = colors.textMuted, size = 14.dp)
            ZillitText(
                text = value?.let(format) ?: placeholder,
                style = ZillitTheme.typography.bodyMedium,
                color = when {
                    !enabled -> colors.textDisabled
                    value == null -> colors.textMuted
                    else -> colors.textPrimary
                },
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            if (clearable && enabled && value != null) {
                ZillitIconButton(
                    icon = ZillitIcons.Close,
                    contentDescription = str(S.desktop_clear_date),
                    onClick = { onPick(null) },
                    size = 20.dp,
                )
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            MonthPicker(
                initialMonth = value ?: today,
                selected = setOfNotNull(value),
                today = today,
                selectable = selectable,
                markers = markers,
                onPick = { date ->
                    onPick(date)
                    open = false
                },
            )
        }
    }
}

/**
 * A month of days to pick from, Monday first. Used in a dropdown for one
 * date and inline for the schedule drawer's many.
 */
@Composable
internal fun MonthPicker(
    initialMonth: LocalDate,
    selected: Set<LocalDate>,
    today: LocalDate,
    onPick: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
    selectable: (LocalDate) -> Boolean = { true },
    markers: Set<LocalDate> = emptySet(),
    locked: LocalDate? = null,
) {
    var month by remember(initialMonth.year, initialMonth.month) {
        mutableStateOf(LocalDate(initialMonth.year, initialMonth.month, 1))
    }
    val colors = ZillitTheme.colors
    Column(modifier.width(PICKER_WIDTH).padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ZillitIconButton(
                icon = ZillitIcons.ChevronLeft,
                contentDescription = str(S.desktop_previous_month),
                onClick = { month = month.plus(DatePeriod(months = -1)) },
            )
            ZillitText(
                text = DiaryFormat.monthTitle(month),
                style = ZillitTheme.typography.titleSmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
            ZillitIconButton(
                icon = ZillitIcons.ChevronRight,
                contentDescription = str(S.next_month),
                onClick = { month = month.plus(DatePeriod(months = 1)) },
            )
        }
        Row(Modifier.fillMaxWidth()) {
            WEEKDAY_INITIALS.forEach { initial ->
                ZillitText(
                    text = initial,
                    style = ZillitTheme.typography.labelSmall,
                    color = colors.textMuted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        DiaryCalendar.monthGrid(month).forEach { week ->
            Row(Modifier.fillMaxWidth()) {
                week.forEach { date ->
                    DayCell(
                        date = date,
                        inMonth = DiaryCalendar.sameMonth(date, month),
                        isSelected = date in selected,
                        isLocked = date == locked,
                        isToday = date == today,
                        isMarked = date in markers,
                        enabled = selectable(date),
                        onPick = onPick,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun DayCell(
    date: LocalDate,
    inMonth: Boolean,
    isSelected: Boolean,
    isLocked: Boolean,
    isToday: Boolean,
    isMarked: Boolean,
    enabled: Boolean,
    onPick: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = ZillitTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val ring = if (isToday && !isSelected) Modifier.border(1.dp, colors.accent, ZillitTheme.shapes.small) else Modifier
    Box(modifier.padding(1.dp), contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .size(CELL_SIZE)
                .clip(ZillitTheme.shapes.small)
                .background(cellGround(locked = isLocked, selected = isSelected, hovered = hovered && enabled))
                .then(ring)
                .hoverable(interaction)
                .clickable(enabled = enabled) { onPick(date) }
                .alpha(if (enabled) 1f else DISABLED_ALPHA),
            contentAlignment = Alignment.Center,
        ) {
            ZillitText(
                text = date.day.toString(),
                style = ZillitTheme.typography.labelSmall.copy(
                    fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
                ),
                color = dayNumberColor(filled = isSelected || isLocked, inMonth = inMonth, isToday = isToday),
            )
            if (isMarked) {
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 3.dp)
                        .size(4.dp)
                        .clip(CircleShape)
                        .background(if (isSelected) colors.textOnAccent else colors.accent),
                )
            }
        }
    }
}

@Composable
private fun cellGround(locked: Boolean, selected: Boolean, hovered: Boolean): Color = when {
    locked -> ZillitTheme.colors.warning
    selected -> ZillitTheme.colors.accent
    hovered -> ZillitTheme.colors.surfaceHover
    else -> Color.Transparent
}

@Composable
private fun dayNumberColor(filled: Boolean, inMonth: Boolean, isToday: Boolean): Color = when {
    filled -> ZillitTheme.colors.textOnAccent
    !inMonth -> ZillitTheme.colors.textMuted
    isToday -> ZillitTheme.colors.accentText
    else -> ZillitTheme.colors.textPrimary
}

// Time ---------------------------------------------------------------------

/** A time from a list in five-minute steps — antd's `TimePicker` in `h:mm A`. */
@Composable
internal fun DiaryTimeField(
    value: LocalTime?,
    onPick: (LocalTime?) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = str(S.hint_scheduled_time),
    error: Boolean = false,
    enabled: Boolean = true,
) {
    var open by remember { mutableStateOf(false) }
    val colors = ZillitTheme.colors
    Box(modifier) {
        FieldBox(enabled = enabled, error = error, onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
            ZillitIcon(icon = ZillitIcons.Clock, tint = colors.textMuted, size = 14.dp)
            ZillitText(
                text = value?.let(::clockText) ?: placeholder,
                style = ZillitTheme.typography.bodyMedium,
                color = if (value == null) colors.textMuted else colors.textPrimary,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            val scroll = rememberScrollState()
            val density = LocalDensity.current
            val selectedIndex = value?.let { (it.hour * MINUTES_PER_HOUR + it.minute) / STEP_MINUTES }
                ?: DEFAULT_TIME_INDEX
            // Opens with the chosen time a few rows from the top, not at midnight.
            LaunchedEffect(open) {
                scroll.scrollTo(with(density) { (TIME_ROW * (selectedIndex - LEAD_ROWS).coerceAtLeast(0)).roundToPx() })
            }
            Box(Modifier.width(TIME_MENU_WIDTH).height(TIME_MENU_HEIGHT)) {
                Column(Modifier.fillMaxWidth().zillitVerticalScroll(scroll)) {
                    for (slot in 0 until SLOTS) {
                        val time = LocalTime(
                            slot * STEP_MINUTES / MINUTES_PER_HOUR,
                            slot * STEP_MINUTES % MINUTES_PER_HOUR,
                        )
                        TimeRow(time = time, selected = time == value) {
                            onPick(time)
                            open = false
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TimeRow(time: LocalTime, selected: Boolean, onClick: () -> Unit) {
    val colors = ZillitTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(TIME_ROW)
            .background(
                when {
                    selected -> colors.surfaceSelected
                    hovered -> colors.surfaceHover
                    else -> Color.Transparent
                },
            )
            .hoverable(interaction)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        ZillitText(
            text = clockText(time),
            style = ZillitTheme.typography.bodyMedium.copy(
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            ),
            color = if (selected) colors.accentText else colors.textPrimary,
        )
    }
}

/** "9:05 AM". */
internal fun clockText(time: LocalTime): String {
    val hour = time.hour % HALF_DAY
    val meridiem = if (time.hour >= HALF_DAY) "PM" else "AM"
    return "${if (hour == 0) HALF_DAY else hour}:${time.minute.toString().padStart(2, '0')} $meridiem"
}

// Choice -------------------------------------------------------------------

/**
 * A dropdown whose rows can carry a leading mark — a type's colour, a
 * person's face — and, for a long list, a search box.
 */
@Composable
internal fun <T> DiaryDropdown(
    selected: T?,
    options: List<T>,
    onSelect: (T) -> Unit,
    label: (T) -> String,
    modifier: Modifier = Modifier,
    placeholder: String = str(S.select),
    leading: (@Composable (T) -> Unit)? = null,
    searchable: Boolean = false,
    onClear: (() -> Unit)? = null,
    error: Boolean = false,
    enabled: Boolean = true,
    emptyText: String = str(S.desktop_nothing_to_choose_from),
    menuWidth: Dp = MENU_WIDTH,
) {
    var open by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val colors = ZillitTheme.colors
    Box(modifier) {
        FieldBox(enabled = enabled, error = error, onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
            if (selected != null) leading?.invoke(selected)
            ZillitText(
                text = selected?.let(label) ?: placeholder,
                style = ZillitTheme.typography.bodyMedium,
                color = if (selected == null) colors.textMuted else colors.textPrimary,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            if (onClear != null && selected != null && enabled) {
                ZillitIconButton(
                    icon = ZillitIcons.Close,
                    contentDescription = str(S.ah_clear),
                    onClick = onClear,
                    size = 20.dp,
                )
            }
            ZillitIcon(icon = ZillitIcons.ChevronDown, tint = colors.textMuted, size = 14.dp)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false; query = "" }) {
            ChoiceMenu(
                options = options,
                selected = selected,
                label = label,
                leading = leading,
                query = query.takeIf { searchable },
                onQuery = { query = it },
                emptyText = emptyText,
                width = menuWidth,
                onPick = { option ->
                    onSelect(option)
                    open = false
                    query = ""
                },
            )
        }
    }
}

/** The dropdown's open list: a search box when the list is long, then the matching rows. */
@Composable
private fun <T> ChoiceMenu(
    options: List<T>,
    selected: T?,
    label: (T) -> String,
    leading: (@Composable (T) -> Unit)?,
    query: String?,
    onQuery: (String) -> Unit,
    emptyText: String,
    width: Dp,
    onPick: (T) -> Unit,
) {
    val colors = ZillitTheme.colors
    Column(Modifier.width(width)) {
        if (query != null) {
            ZillitTextField(
                value = query,
                onValueChange = onQuery,
                placeholder = str(S.search),
                leadingIcon = ZillitIcons.Search,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
        val needle = query.orEmpty().trim()
        val shown = options.filter { needle.isEmpty() || label(it).contains(needle, ignoreCase = true) }
        if (shown.isEmpty()) {
            ZillitText(
                text = if (options.isEmpty()) emptyText else str(S.dm_picker_empty),
                style = ZillitTheme.typography.bodySmall,
                color = colors.textMuted,
                modifier = Modifier.padding(12.dp),
            )
        }
        Column(Modifier.heightIn(max = MENU_MAX_HEIGHT).zillitVerticalScroll(rememberScrollState())) {
            shown.forEach { option ->
                ChoiceRow(selected = option == selected, onClick = { onPick(option) }) {
                    leading?.invoke(option)
                    ZillitText(
                        text = label(option),
                        style = ZillitTheme.typography.bodyMedium,
                        color = if (option == selected) colors.accentText else colors.textPrimary,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun ChoiceRow(selected: Boolean, onClick: () -> Unit, content: @Composable RowScope.() -> Unit) {
    val colors = ZillitTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                when {
                    selected -> colors.surfaceSelected
                    hovered -> colors.surfaceHover
                    else -> Color.Transparent
                },
            )
            .hoverable(interaction)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) { content() }
}

// Colour -------------------------------------------------------------------

/** A row of preset swatches — the web's `BgColorPalette`. */
@Composable
internal fun SwatchRow(current: String, options: List<String>, onPick: (String) -> Unit, size: Dp = 26.dp) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        options.forEach { hex ->
            Swatch(hex = hex, selected = hex.equals(current, ignoreCase = true), size = size) { onPick(hex) }
        }
    }
}

@Composable
internal fun Swatch(hex: String, selected: Boolean, size: Dp = 26.dp, onClick: () -> Unit) {
    val colors = ZillitTheme.colors
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(6.dp))
            .background(hexColor(hex) ?: colors.textMuted)
            .border(
                if (selected) 2.dp else 1.dp,
                if (selected) colors.textPrimary else colors.border,
                RoundedCornerShape(6.dp),
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) ZillitIcon(icon = ZillitIcons.Check, tint = Color.White, size = size / 2)
    }
}

/**
 * A colour picked from presets or typed as hex — antd's `ColorPicker`, as a
 * swatch that opens a small palette.
 */
@Composable
internal fun ColorPickerButton(
    color: String,
    onPick: (String) -> Unit,
    presets: List<String> = TYPE_COLORS,
    size: Dp = 22.dp,
    showText: Boolean = false,
) {
    var open by remember { mutableStateOf(false) }
    var typed by remember(color) { mutableStateOf(color) }
    val colors = ZillitTheme.colors
    Box {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .border(1.dp, colors.border, RoundedCornerShape(6.dp))
                .clickable { open = true }
                .padding(3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(Modifier.size(size).clip(RoundedCornerShape(4.dp)).background(hexColor(color) ?: colors.surfaceSunken))
            if (showText) {
                ZillitText(color.uppercase(), style = ZillitTheme.typography.labelSmall, color = colors.textSecondary)
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Column(Modifier.width(PALETTE_WIDTH).padding(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                presets.chunked(PALETTE_COLUMNS).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { hex ->
                            Swatch(hex = hex, selected = hex.equals(color, ignoreCase = true)) {
                                onPick(hex)
                                open = false
                            }
                        }
                    }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    ZillitTextField(
                        value = typed,
                        onValueChange = { typed = it.take(HEX_LENGTH) },
                        placeholder = "#RRGGBB",
                        modifier = Modifier.weight(1f),
                    )
                    ZillitButton(
                        text = str(S.recce_use),
                        onClick = {
                            val hex = if (typed.startsWith("#")) typed else "#$typed"
                            if (hexColor(hex) != null) {
                                onPick(hex.uppercase())
                                open = false
                            }
                        },
                        size = ButtonSize.Small,
                        variant = ButtonVariant.Secondary,
                        enabled = hexColor(if (typed.startsWith("#")) typed else "#$typed") != null,
                    )
                }
            }
        }
    }
}

/** The ten the web offers for a new type (`CreateScheduleModal.presetColors`). */
internal val TYPE_COLORS = listOf(
    "#E74C3C", "#F39C12", "#27AE60", "#3498DB", "#9B59B6", "#1ABC9C", "#E67E22", "#95A5A6", "#2C3E50", "#D35400",
)

private val WEEKDAY_INITIALS = listOf("Mo", "Tu", "We", "Th", "Fr", "Sa", "Su")
private val FIELD_HEIGHT = 36.dp
private val PICKER_WIDTH = 280.dp
private val CELL_SIZE = 32.dp
private val TIME_MENU_WIDTH = 150.dp
private val TIME_MENU_HEIGHT = 260.dp
private val TIME_ROW = 32.dp
private val MENU_WIDTH = 320.dp
private val MENU_MAX_HEIGHT = 320.dp
private val PALETTE_WIDTH = 190.dp
private const val PALETTE_COLUMNS = 5
private const val HEX_LENGTH = 7
private const val DISABLED_ALPHA = 0.35f
private const val DISABLED_BG = 0.5f
private const val MINUTES_PER_HOUR = 60
private const val STEP_MINUTES = 5
private const val SLOTS = 24 * 60 / 5
private const val DEFAULT_TIME_INDEX = 9 * 12
private const val HALF_DAY = 12
private const val LEAD_ROWS = 3
