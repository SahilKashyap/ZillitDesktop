// Compose screens read top to bottom in layout order; splitting them by length hides that.
@file:Suppress("LongMethod", "CyclomaticComplexMethod", "TooManyFunctions")

package com.zillit.desktop.feature.callsheet.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.zillit.desktop.core.designsystem.component.ZillitTooltip
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.callsheet.domain.ColumnSpec
import com.zillit.desktop.feature.callsheet.domain.SheetMember
import com.zillit.desktop.feature.callsheet.domain.SheetTime
import com.zillit.desktop.feature.callsheet.domain.sanitizeUserIds
import com.zillit.desktop.feature.callsheet.ui.components.ButtonKind
import com.zillit.desktop.feature.callsheet.ui.components.Face
import com.zillit.desktop.feature.callsheet.ui.components.ModalScrim
import com.zillit.desktop.feature.callsheet.ui.components.SheetButton
import com.zillit.desktop.feature.callsheet.ui.components.SheetInput
import com.zillit.desktop.feature.callsheet.ui.components.plainClick
import com.zillit.desktop.feature.callsheet.ui.components.rememberHover
import com.zillit.desktop.feature.callsheet.ui.components.sheetText
import com.zillit.desktop.feature.callsheet.ui.components.swallowClicks
import com.zillit.desktop.feature.callsheet.ui.theme.SheetTheme
import kotlin.time.Clock
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus

/** The pane's small-caps eyebrow — "Editing", "Header Orientation". */
@Composable
internal fun PaneEyebrow(text: String, modifier: Modifier = Modifier, strong: Boolean = false) {
    Text(
        text.uppercase(),
        style = sheetText(10.sp, if (strong) FontWeight.SemiBold else FontWeight.Medium, 14.sp)
            .copy(letterSpacing = if (strong) 1.2.sp else 0.5.sp),
        color = SheetTheme.colors.textMuted,
        modifier = modifier,
    )
}

/** A field label: 11 px, uppercase, 6 px above its control. */
@Composable
internal fun PaneLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        style = sheetText(11.sp, FontWeight.Medium).copy(letterSpacing = 0.5.sp),
        color = SheetTheme.colors.textSecondary,
        modifier = modifier.padding(bottom = 6.dp),
    )
}

// Select -----------------------------------------------------------------------------------------

/** A native-looking select: the value, a chevron, and a menu of options. */
@Composable
internal fun PaneSelect(
    value: String,
    options: List<Pair<String, String>>,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = str(S.select),
    enabled: Boolean = true,
    borderless: Boolean = false,
    fontSize: Int = 14,
    radius: Int = 8,
    centered: Boolean = false,
    onOpen: () -> Unit = {},
) {
    val colors = SheetTheme.colors
    var open by remember { mutableStateOf(false) }
    val (source, hovered) = rememberHover()
    val label = options.firstOrNull { it.first == value }?.second
    Box(modifier) {
        Row(
            Modifier
                .fillMaxWidth()
                .alpha(if (enabled) 1f else 0.5f)
                // The boxed look lines up with SheetInput, which keeps 2 px outside its border for the focus ring.
                .then(if (borderless) Modifier else Modifier.padding(2.dp))
                .clip(RoundedCornerShape(if (borderless) 4.dp else radius.dp))
                .background(
                    if (borderless) (if (hovered && enabled) colors.hover else Color.Transparent) else fieldGround(),
                )
                .then(
                    if (borderless) Modifier else Modifier.border(
                        1.dp,
                        if (open) colors.accent else colors.borderStrong,
                        RoundedCornerShape(radius.dp),
                    ),
                )
                .hoverable(source)
                .plainClick(enabled = enabled, source = source) {
                    onOpen()
                    open = true
                }
                .padding(
                    horizontal = if (borderless) 4.dp else if (radius < 8) 6.dp else 12.dp,
                    vertical = if (borderless) 3.dp else if (radius < 8) 5.dp else 8.dp,
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                label ?: placeholder,
                style = sheetText(fontSize.sp, lineHeight = (fontSize + 6).sp),
                color = if (label == null) colors.textMuted else colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = if (centered) TextAlign.Center else TextAlign.Start,
                modifier = Modifier.weight(1f),
            )
            Icon(
                ZillitIcons.ChevronDown,
                contentDescription = null,
                tint = colors.textMuted,
                modifier = Modifier.size(12.dp),
            )
        }
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            offset = DpOffset(0.dp, 4.dp),
            shape = RoundedCornerShape(10.dp),
            containerColor = colors.surface,
            modifier = Modifier.border(1.dp, colors.border, RoundedCornerShape(10.dp)).heightIn(max = 320.dp),
        ) {
            // A plain column: a lazy list inside a DropdownMenu crashes on its intrinsic measure.
            Column(Modifier.widthIn(min = 120.dp).padding(horizontal = 4.dp)) {
                options.forEach { (key, text) ->
                    MenuOption(text, selected = key == value) {
                        open = false
                        onSelect(key)
                    }
                }
            }
        }
    }
}

@Composable
internal fun MenuOption(text: String, selected: Boolean = false, danger: Boolean = false, onClick: () -> Unit) {
    val colors = SheetTheme.colors
    val (source, hovered) = rememberHover()
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(
                when {
                    selected -> colors.accentLight
                    hovered -> if (danger) colors.redBg else colors.hover
                    else -> Color.Transparent
                },
            )
            .hoverable(source)
            .plainClick(source = source, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            style = sheetText(13.sp, if (selected) FontWeight.SemiBold else FontWeight.Normal),
            color = when {
                danger -> colors.red
                selected -> colors.accent
                else -> colors.textPrimary
            },
            modifier = Modifier.weight(1f),
        )
        if (selected) Icon(
            ZillitIcons.Check,
            contentDescription = null,
            tint = colors.accent,
            modifier = Modifier.size(12.dp),
        )
    }
}

@Composable
internal fun fieldGround(): Color = if (SheetTheme.colors.isDark) Color(0x0FFFFFFF) else Color.White

// Inline grid text --------------------------------------------------------------------------------

/**
 * The borderless text of a grid cell: grows with its content, tints on
 * focus, and hands Tab to the grid so it walks cell to cell.
 */
@Composable
internal fun InlineText(
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    singleLine: Boolean = false,
    bold: Boolean = false,
    weight: FontWeight? = null,
    align: TextAlign = TextAlign.Start,
    fontSize: Int = 14,
    keyboardType: KeyboardType = KeyboardType.Text,
    focusRequester: FocusRequester? = null,
    onFocus: () -> Unit = {},
    onTab: ((backwards: Boolean) -> Unit)? = null,
    accept: (String) -> Boolean = { true },
    ground: Color = Color.Transparent,
    textColor: Color? = null,
) {
    val colors = SheetTheme.colors
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val fontWeight = weight ?: if (bold) FontWeight.Bold else FontWeight.Normal
    val style = sheetText(fontSize.sp, fontWeight).merge(
        TextStyle(
            fontSize = fontSize.sp,
            lineHeight = (fontSize * 1.45f).sp,
            fontWeight = fontWeight,
            textAlign = align,
            color = textColor ?: colors.textPrimary,
        ),
    )
    Box(
        modifier
            .background(if (focused) colors.accent.copy(alpha = if (colors.isDark) 0.08f else 0.07f) else ground)
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        if (value.isEmpty() && placeholder.isNotEmpty()) {
            Text(
                placeholder,
                style = style.copy(color = colors.textMuted, fontWeight = FontWeight.Normal),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        BasicTextField(
            value = value,
            onValueChange = { if (it == "" || accept(it)) onChange(it) },
            singleLine = singleLine,
            textStyle = style,
            cursorBrush = SolidColor(colors.accent),
            interactionSource = source,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            modifier = Modifier
                .fillMaxWidth()
                .then(focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier)
                .onFocusChanged { if (it.isFocused) onFocus() }
                .onPreviewKeyEvent { event ->
                    if (onTab != null && event.type == KeyEventType.KeyDown && event.key == Key.Tab) {
                        onTab(event.isShiftPressed)
                        true
                    } else {
                        false
                    }
                },
        )
    }
}

internal val NUMBER_TEXT = Regex("""^-?\d*\.?\d*$""")
internal val PHONE_TEXT = Regex("""^[+\d\s()-]*$""")
internal const val PHONE_MAX = 15

// Date ---------------------------------------------------------------------------------------------

/**
 * A date shown as DD/MM/YYYY and stored as the epoch of LOCAL midnight,
 * picked from a month grid. [value] is the stored epoch string; anything that
 * is not a positive number shows as empty and is kept until a day is picked.
 */
@Composable
internal fun DateInput(
    value: String,
    onPick: (Long?) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = str(S.hint_date),
    boxed: Boolean = false,
    clearable: Boolean = true,
    disablePast: Boolean = false,
    onFocus: () -> Unit = {},
) {
    val colors = SheetTheme.colors
    var open by remember { mutableStateOf(false) }
    val date = SheetTime.dateParts(value)
    val label = date?.let { "${it.day.pad()}/${(it.month.ordinal + 1).pad()}/${it.year}" }
    val (source, hovered) = rememberHover()
    Box(modifier) {
        Row(
            Modifier
                .fillMaxWidth()
                .then(
                    if (boxed) {
                        Modifier.padding(2.dp).clip(RoundedCornerShape(8.dp)).background(fieldGround())
                            .border(1.dp, if (open) colors.accent else colors.borderStrong, RoundedCornerShape(8.dp))
                            .heightIn(min = 38.dp)
                    } else {
                        Modifier.background(if (open) colors.accent.copy(alpha = 0.07f) else Color.Transparent)
                    },
                )
                .hoverable(source)
                .plainClick(source = source) {
                    onFocus()
                    open = true
                }
                .padding(horizontal = if (boxed) 12.dp else 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                label ?: placeholder,
                style = sheetText(14.sp),
                color = if (label == null) colors.textMuted else colors.textPrimary,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            // Revealed by alpha, never composed on hover: a control composed on hover loses its press.
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    ZillitIcons.Calendar,
                    contentDescription = null,
                    tint = colors.textMuted,
                    modifier = Modifier.size(13.dp).alpha(if (clearable && label != null && hovered) 0f else 1f),
                )
                if (clearable && label != null) {
                    Icon(
                        ZillitIcons.Close,
                        contentDescription = str(S.desktop_clear_date),
                        tint = colors.textMuted,
                        modifier = Modifier.size(12.dp).alpha(if (hovered) 1f else 0f).plainClick { onPick(null) },
                    )
                }
            }
        }
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            offset = DpOffset(0.dp, 4.dp),
            shape = RoundedCornerShape(12.dp),
            containerColor = colors.surface,
            modifier = Modifier.border(1.dp, colors.border, RoundedCornerShape(12.dp)),
        ) {
            MonthGrid(date, disablePast) {
                open = false
                onPick(SheetTime.encodeDate(it))
            }
        }
    }
}

@Composable
private fun MonthGrid(selected: LocalDate?, disablePast: Boolean, onPick: (LocalDate) -> Unit) {
    val colors = SheetTheme.colors
    val today = remember { SheetTime.localDate(Clock.System.now().toEpochMilliseconds()) }
    val anchor = selected ?: today
    var month by remember { mutableStateOf(LocalDate(anchor.year, anchor.month, 1)) }
    Column(Modifier.width(252.dp).padding(horizontal = 10.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CalendarNav(
                ZillitIcons.ChevronLeft,
                str(S.desktop_previous_month),
            ) { month = month.plus(-1, DateTimeUnit.MONTH) }
            Text(
                "${str(MONTH_NAMES[month.month.ordinal])} ${month.year}",
                style = sheetText(13.sp, FontWeight.SemiBold),
                color = colors.textPrimary,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
            CalendarNav(
                ZillitIcons.ChevronRight,
                str(S.desktop_next_month),
            ) { month = month.plus(1, DateTimeUnit.MONTH) }
        }
        Row(Modifier.padding(top = 6.dp)) {
            listOf("Mo", "Tu", "We", "Th", "Fr", "Sa", "Su").forEach {
                Text(
                    it,
                    style = sheetText(10.sp, FontWeight.Medium),
                    color = colors.textMuted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        val lead = month.dayOfWeek.ordinal
        val days = month.plus(1, DateTimeUnit.MONTH).plus(-1, DateTimeUnit.DAY).day
        val cells = List(lead) { null } + (1..days).map { LocalDate(month.year, month.month, it) }
        cells.chunked(DAYS_IN_WEEK).forEach { week ->
            Row(Modifier.padding(top = 2.dp)) {
                repeat(DAYS_IN_WEEK) { index ->
                    val day = week.getOrNull(index)
                    Box(Modifier.weight(1f).height(30.dp), contentAlignment = Alignment.Center) {
                        if (day != null) {
                            DayCell(
                                day,
                                selected = day == selected,
                                today = day == today,
                                enabled = !disablePast || day >= today,
                            ) { onPick(day) }
                        }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.End) {
            Text(
                str(S.today),
                style = sheetText(12.sp, FontWeight.SemiBold),
                color = colors.accent,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .plainClick { onPick(today) }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
}

private val MONTH_NAMES = listOf(
    S.desktop_month_full_january,
    S.desktop_month_full_february,
    S.desktop_month_full_march,
    S.desktop_month_full_april,
    S.desktop_month_short_may,
    S.desktop_month_full_june,
    S.desktop_month_full_july,
    S.desktop_month_full_august,
    S.desktop_month_full_september,
    S.desktop_month_full_october,
    S.desktop_month_full_november,
    S.desktop_month_full_december,
)
private const val DAYS_IN_WEEK = 7

@Composable
private fun CalendarNav(icon: ImageVector, description: String, onClick: () -> Unit) {
    val colors = SheetTheme.colors
    val (source, hovered) = rememberHover()
    Box(
        Modifier
            .size(26.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(if (hovered) colors.sunken else Color.Transparent)
            .hoverable(source)
            .plainClick(source = source, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = description, tint = colors.textSecondary, modifier = Modifier.size(13.dp))
    }
}

@Composable
private fun DayCell(day: LocalDate, selected: Boolean, today: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val colors = SheetTheme.colors
    val (source, hovered) = rememberHover()
    Box(
        Modifier
            .size(28.dp)
            .alpha(if (enabled) 1f else 0.35f)
            .clip(CircleShape)
            .background(
                when {
                    selected -> colors.accent
                    hovered && enabled -> colors.accentLight
                    else -> Color.Transparent
                },
            )
            .then(if (today && !selected) Modifier.border(1.dp, colors.accent, CircleShape) else Modifier)
            .hoverable(source)
            .plainClick(enabled = enabled, source = source, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            day.day.toString(),
            style = sheetText(12.sp, if (selected || today) FontWeight.SemiBold else FontWeight.Normal),
            color = when {
                selected -> Color.White
                hovered && enabled -> colors.accent
                else -> colors.textPrimary
            },
        )
    }
}

// Time ---------------------------------------------------------------------------------------------

/**
 * A 24-hour `HH:mm` time stored as an epoch on the sheet's date: type it, or
 * pick an hour and a five-minute step. [value] is the stored epoch string —
 * a legacy text time shows as empty and is kept until a new time is set.
 */
@Composable
internal fun TimeInput(
    value: String,
    onChange: (String) -> Unit,
    sheetDateMs: Long?,
    modifier: Modifier = Modifier,
    placeholder: String = str(S.hint_scheduled_time),
    onFocus: () -> Unit = {},
) {
    val colors = SheetTheme.colors
    var open by remember { mutableStateOf(false) }
    val parts = SheetTime.clockParts(value)
    val clock = parts?.let { (h, m) -> "${h.pad()}:${m.pad()}" }.orEmpty()
    var draft by remember(clock) { mutableStateOf(clock) }
    val encode = { hour: Int, minute: Int ->
        SheetTime.encodeClock(hour, minute, sheetDateMs, Clock.System.now().toEpochMilliseconds())
    }
    Box(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            InlineText(
                value = draft,
                onChange = { text ->
                    draft = text
                    val match = CLOCK_TEXT.matchEntire(text)
                    when {
                        text.isEmpty() -> onChange("")
                        match != null -> onChange(encode(match.groupValues[1].toInt(), match.groupValues[2].toInt()))
                    }
                },
                placeholder = placeholder,
                singleLine = true,
                accept = { it.length <= CLOCK_LENGTH && it.all { c -> c.isDigit() || c == ':' } },
                onFocus = onFocus,
                modifier = Modifier.weight(1f),
            )
            ZillitTooltip(str(S.desktop_pick_a_time)) {
                Icon(
                    ZillitIcons.Clock,
                    contentDescription = str(S.desktop_pick_a_time),
                    tint = if (open) colors.accent else colors.textMuted,
                    modifier = Modifier.padding(end = 6.dp).size(13.dp).plainClick {
                        onFocus()
                        open = true
                    },
                )
            }
        }
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            offset = DpOffset(0.dp, 4.dp),
            shape = RoundedCornerShape(12.dp),
            containerColor = colors.surface,
            modifier = Modifier.border(1.dp, colors.border, RoundedCornerShape(12.dp)),
        ) {
            TimePanel(
                hour = parts?.first,
                minute = parts?.second,
                onPick = { h, m -> onChange(encode(h, m)) },
                onClear = { onChange("") },
                onDone = { open = false },
            )
        }
    }
}

private val CLOCK_TEXT = Regex("""([01]?\d|2[0-3]):([0-5]\d)""")
private const val CLOCK_LENGTH = 5
private const val MINUTE_STEP = 5

@Composable
private fun TimePanel(
    hour: Int?,
    minute: Int?,
    onPick: (Int, Int) -> Unit,
    onClear: () -> Unit,
    onDone: () -> Unit,
) {
    val colors = SheetTheme.colors
    Column(Modifier.width(176.dp)) {
        Row(Modifier.padding(horizontal = 6.dp).height(176.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TimeColumn((0..23).toList(), hour, Modifier.weight(1f)) { onPick(it, minute ?: 0) }
            TimeColumn((0..55 step MINUTE_STEP).toList(), minute, Modifier.weight(1f)) { onPick(hour ?: 0, it) }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                str(S.ah_clear),
                style = sheetText(12.sp),
                color = colors.textMuted,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .plainClick(onClick = onClear)
                    .padding(horizontal = 6.dp, vertical = 4.dp),
            )
            Box(Modifier.weight(1f))
            SheetButton(
                str(S.ah_done),
                onDone,
                kind = ButtonKind.Accent,
                height = 28.dp,
                fontSize = 12.sp,
                horizontalPadding = 14.dp,
            )
        }
    }
}

@Composable
private fun TimeColumn(items: List<Int>, selected: Int?, modifier: Modifier, onPick: (Int) -> Unit) {
    val colors = SheetTheme.colors
    val scroll = rememberScrollState()
    LaunchedEffect(Unit) {
        val index = items.indexOf(selected)
        if (index > 0) scroll.scrollTo((index - 2).coerceAtLeast(0) * ITEM_PX)
    }
    Column(modifier.verticalScroll(scroll)) {
        items.forEach { item ->
            val active = item == selected
            val (source, hovered) = rememberHover()
            Text(
                item.pad(),
                style = sheetText(13.sp, if (active) FontWeight.SemiBold else FontWeight.Normal),
                color = when {
                    active -> Color.White
                    hovered -> colors.accent
                    else -> colors.textPrimary
                },
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 1.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(
                        when {
                            active -> colors.accent
                            hovered -> colors.accentLight
                            else -> Color.Transparent
                        },
                    )
                    .hoverable(source)
                    .plainClick(source = source) { onPick(item) }
                    .padding(vertical = 5.dp),
            )
        }
    }
}

private const val ITEM_PX = 60

// Users --------------------------------------------------------------------------------------------

/**
 * `UserPicker`: comma-separated member ids as chips, chosen in the "Select
 * Users" dialog. Every change keeps only ids of current crew.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun UsersInput(
    value: String,
    members: List<SheetMember>,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = str(S.desktop_select_users_placeholder),
    onFocus: () -> Unit = {},
) {
    val colors = SheetTheme.colors
    var open by remember { mutableStateOf(false) }
    val ids = value.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    val chosen = ids.mapNotNull { id -> members.firstOrNull { it.userId == id } }
    val toggle = { id: String ->
        val next = if (id in ids) ids - id else ids + id
        onChange(sanitizeUserIds(next.joinToString(","), members))
    }
    FlowRow(
        modifier
            .heightIn(min = 30.dp)
            .plainClick {
                onFocus()
                open = true
            }
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (chosen.isEmpty()) Text(placeholder, style = sheetText(14.sp), color = colors.textMuted)
        chosen.forEach { member ->
            Row(
                Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(colors.accentLight)
                    .border(1.dp, colors.accent, RoundedCornerShape(4.dp))
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    member.fullName,
                    style = sheetText(10.sp, FontWeight.Medium, 13.sp),
                    color = colors.chipOnText,
                    maxLines = 1,
                )
                ZillitTooltip(str(S.bs_chip_remove, member.fullName)) {
                    Icon(
                        ZillitIcons.Close,
                        contentDescription = str(S.bs_chip_remove, member.fullName),
                        tint = colors.red,
                        modifier = Modifier.size(9.dp).plainClick { toggle(member.userId) },
                    )
                }
            }
        }
    }
    if (open) UsersDialog(ids, members, onToggle = toggle, onClose = { open = false })
}

/** A dialog over the whole window, whatever pane opened it. */
@Composable
internal fun WindowOverlay(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    Popup(
        popupPositionProvider = FullWindow,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        ModalScrim(onDismiss = onDismiss, onEscape = onDismiss) { content() }
    }
}

private object FullWindow : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset = IntOffset.Zero
}

/** "Select Users": search, the chosen strip, every accepted member, Done. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun UsersDialog(
    ids: List<String>,
    members: List<SheetMember>,
    onToggle: (String) -> Unit,
    onClose: () -> Unit,
) {
    val colors = SheetTheme.colors
    var search by remember { mutableStateOf("") }
    val query = search.trim().lowercase()
    val pool = members.filter { it.isAccepted && it.userId.isNotBlank() }
        .filter { member ->
            query.isEmpty() ||
                listOf(member.fullName, member.designation, member.department).any { it.lowercase().contains(query) }
        }
    val chosen = ids.mapNotNull { id -> members.firstOrNull { it.userId == id } }
    WindowOverlay(onClose) {
        Column(
            Modifier
                .width(480.dp)
                .heightIn(max = 640.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(colors.surface)
                .swallowClicks(),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(str(S.select_users), style = sheetText(16.sp, FontWeight.SemiBold), color = colors.textPrimary)
                    Text(
                        if (ids.isEmpty()) str(S.desktop_choose_team_members) else str(S.dd_n_selected, ids.size),
                        style = sheetText(12.sp),
                        color = colors.textMuted,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                CloseSquare(onClose, size = 32)
            }
            Divider()
            Box(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                SheetInput(
                    search,
                    { search = it },
                    Modifier.fillMaxWidth(),
                    placeholder = str(S.desktop_search_by_name_role_department),
                    autoFocus = true,
                    leadingIcon = ZillitIcons.Search,
                )
            }
            if (chosen.isNotEmpty()) {
                Divider()
                FlowRow(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 120.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    chosen.forEach { member -> PersonChip(member) { onToggle(member.userId) } }
                }
            }
            Divider()
            Column(Modifier.weight(1f, fill = false).heightIn(min = 300.dp).verticalScroll(rememberScrollState())) {
                if (pool.isEmpty()) {
                    Text(
                        str(S.desktop_no_members_found),
                        style = sheetText(14.sp),
                        color = colors.textMuted,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                    )
                }
                pool.forEachIndexed { index, member ->
                    val picked = member.userId in ids
                    val (source, hovered) = rememberHover()
                    if (index > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(colors.borderFaint))
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(
                                when {
                                    hovered -> colors.accentLight
                                    picked -> colors.accent.copy(alpha = 0.06f)
                                    else -> Color.Transparent
                                },
                            )
                            .hoverable(source)
                            .plainClick(source = source) { onToggle(member.userId) }
                            .padding(horizontal = 20.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Face(member.userId, member.fullName, 36.dp)
                        Column(Modifier.weight(1f)) {
                            Text(
                                member.fullName,
                                style = sheetText(14.sp, FontWeight.Medium),
                                color = colors.textPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            MemberRole(member, 12)
                        }
                        CheckCircle(picked)
                    }
                }
            }
            Divider()
            Row(
                Modifier.fillMaxWidth().background(colors.elevated).padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    str(S.desktop_ce_member_count, pool.size),
                    style = sheetText(12.sp),
                    color = colors.textTertiary,
                    modifier = Modifier.weight(1f),
                )
                SheetButton(
                    str(S.ah_done),
                    onClose,
                    kind = ButtonKind.Accent,
                    height = 36.dp,
                    fontSize = 14.sp,
                    horizontalPadding = 24.dp,
                )
            }
        }
    }
}

/** "Designation · Department", the department muted; nothing when both are blank. */
@Composable
internal fun MemberRole(member: SheetMember, fontSize: Int) {
    val colors = SheetTheme.colors
    if (member.designation.isBlank() && member.department.isBlank()) return
    Text(
        buildAnnotatedString {
            append(member.designation)
            if (member.department.isNotBlank()) {
                withStyle(SpanStyle(color = colors.textMuted)) {
                    append(if (member.designation.isNotBlank()) " · ${member.department}" else member.department)
                }
            }
        },
        style = sheetText(fontSize.sp),
        color = colors.textTertiary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
internal fun Divider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(SheetTheme.colors.border))
}

@Composable
internal fun CloseSquare(onClose: () -> Unit, description: String = str(S.close), size: Int = 28) {
    val colors = SheetTheme.colors
    val (source, hovered) = rememberHover()
    ZillitTooltip(description) {
        Box(
            Modifier
                .size(size.dp)
                .clip(RoundedCornerShape(if (size > 28) 8.dp else 6.dp))
                .background(if (hovered) colors.sunken else Color.Transparent)
                .hoverable(source)
                .plainClick(source = source, onClick = onClose),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                ZillitIcons.Close,
                contentDescription = description,
                tint = if (hovered) colors.textSecondary else colors.textMuted,
                modifier = Modifier.size(if (size > 28) 16.dp else 14.dp),
            )
        }
    }
}

@Composable
internal fun PersonChip(member: SheetMember, onRemove: () -> Unit) {
    val colors = SheetTheme.colors
    Row(
        Modifier
            .clip(CircleShape)
            .background(colors.accentLight)
            .border(1.dp, colors.accent, CircleShape)
            .padding(start = 4.dp, end = 10.dp, top = 3.dp, bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Face(member.userId, member.fullName, 22.dp)
        Text(
            member.fullName,
            style = sheetText(12.sp, FontWeight.Medium),
            color = colors.chipOnText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 110.dp),
        )
        ZillitTooltip(str(S.desktop_remove_approver)) {
            Icon(
                ZillitIcons.Close,
                contentDescription = str(S.bs_chip_remove, member.fullName),
                tint = colors.red,
                modifier = Modifier.size(11.dp).plainClick(onClick = onRemove),
            )
        }
    }
}

@Composable
internal fun CheckCircle(checked: Boolean) {
    val colors = SheetTheme.colors
    Box(
        Modifier
            .size(20.dp)
            .clip(CircleShape)
            .background(if (checked) colors.accent else Color.Transparent)
            .border(2.dp, if (checked) colors.accent else colors.borderStrong, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) Icon(
            ZillitIcons.Check,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(11.dp),
        )
    }
}

// Crew "In" ---------------------------------------------------------------------------------------------

/**
 * `InSelector`: Time (HH : MM on the sheet's date), Per HOD, O/C, or Others
 * with free text — a mode-prefixed string under a text column. Choosing a
 * mode drops the previous detail, as on the web.
 */
@Composable
internal fun InSelector(
    value: String,
    onChange: (String) -> Unit,
    sheetDateMs: Long?,
    onFocus: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val mode = SheetTime.inModeOf(value)
    Column(modifier.padding(horizontal = 8.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        PaneSelect(
            value = mode.name,
            options = SheetTime.InMode.entries.map { it.name to it.label },
            onSelect = { picked -> onChange(SheetTime.inValueFor(SheetTime.InMode.valueOf(picked))) },
            radius = 4,
            onOpen = onFocus,
        )
        when (mode) {
            SheetTime.InMode.Time -> {
                val (hour, minute) = SheetTime.inClockParts(value)
                val write = { h: Int, m: Int ->
                    onChange(SheetTime.encodeInTime(h, m, sheetDateMs, Clock.System.now().toEpochMilliseconds()))
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    PaneSelect(
                        hour?.pad().orEmpty(),
                        listOf("" to "HH") + (0..23).map { it.pad() to it.pad() },
                        { h -> write(h.toIntOrNull() ?: 0, minute ?: 0) },
                        Modifier.weight(1f),
                        placeholder = "HH",
                        radius = 4,
                        centered = true,
                        onOpen = onFocus,
                    )
                    Text(":", style = sheetText(14.sp, FontWeight.Bold), color = SheetTheme.colors.textSecondary)
                    PaneSelect(
                        minute?.pad().orEmpty(),
                        listOf("" to "MM") + (0..59).map { it.pad() to it.pad() },
                        { m -> write(hour ?: 0, m.toIntOrNull() ?: 0) },
                        Modifier.weight(1f),
                        placeholder = "MM",
                        radius = 4,
                        centered = true,
                        onOpen = onFocus,
                    )
                }
            }
            SheetTime.InMode.Other -> SheetInput(
                value.removePrefix(SheetTime.OTHER_PREFIX),
                { onChange(SheetTime.OTHER_PREFIX + it) },
                Modifier.fillMaxWidth(),
                placeholder = str(S.docusign_text_value_hint),
                radius = 4.dp,
                textStyle = sheetText(14.sp),
                onFocusChange = { if (it) onFocus() },
            )
            else -> Unit
        }
    }
}

private fun Int.pad(): String = toString().padStart(2, '0')

/** A value input's placeholder: the column's name, else the type's hint. */
internal fun placeholderFor(column: ColumnSpec?): String {
    val name = column?.label?.trim().orEmpty()
    if (name.isNotEmpty()) return name
    return when (column?.type) {
        "phone" -> "+1 555 123 4567"
        "email" -> "name@example.com"
        "url" -> "https://..."
        "date" -> str(S.hint_date)
        "time" -> str(S.hint_scheduled_time)
        "users" -> str(S.desktop_select_users_placeholder)
        else -> ""
    }
}
