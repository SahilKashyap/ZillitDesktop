// Compose screens read top to bottom in layout order; splitting them by length hides that.
@file:Suppress("LongMethod", "CyclomaticComplexMethod")

package com.zillit.desktop.feature.productionreport.ui.editor

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
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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
import com.zillit.desktop.core.locationpicker.LocalLocationPicker
import com.zillit.desktop.core.locationpicker.PickedLocation
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.productionreport.domain.ColumnSpec
import com.zillit.desktop.feature.productionreport.domain.ReportTime
import com.zillit.desktop.feature.productionreport.domain.SheetMember
import com.zillit.desktop.feature.productionreport.ui.components.ButtonKind
import com.zillit.desktop.feature.productionreport.ui.components.Face
import com.zillit.desktop.feature.productionreport.ui.components.ModalScrim
import com.zillit.desktop.feature.productionreport.ui.components.ReportButton
import com.zillit.desktop.feature.productionreport.ui.components.ReportInput
import com.zillit.desktop.feature.productionreport.ui.components.plainClick
import com.zillit.desktop.feature.productionreport.ui.components.rememberHover
import com.zillit.desktop.feature.productionreport.ui.components.reportText
import com.zillit.desktop.feature.productionreport.ui.components.swallowClicks
import com.zillit.desktop.feature.productionreport.ui.theme.ReportTheme
import kotlin.time.Clock
import kotlinx.coroutines.launch
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.todayIn

/** The pane's small-caps label — above the Type and Orientation controls. */
@Composable
internal fun PaneEyebrow(text: String, modifier: Modifier = Modifier, strong: Boolean = false) {
    Text(
        text.uppercase(),
        style = reportText(10.sp, if (strong) FontWeight.SemiBold else FontWeight.Medium, 14.sp)
            .copy(letterSpacing = if (strong) 1.2.sp else 0.5.sp),
        color = ReportTheme.colors.textMuted,
        modifier = modifier,
    )
}

/** A shared-field label: 11 px, uppercase, 6 px above its control. */
@Composable
internal fun PaneLabel(text: String) {
    Text(
        text.uppercase(),
        style = reportText(11.sp, FontWeight.Medium).copy(letterSpacing = 0.5.sp),
        color = ReportTheme.colors.textSecondary,
        modifier = Modifier.padding(bottom = 6.dp),
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
) {
    val colors = ReportTheme.colors
    var open by remember { mutableStateOf(false) }
    val (source, hovered) = rememberHover()
    val label = options.firstOrNull { it.first == value }?.second
    Box(modifier) {
        Row(
            Modifier
                .fillMaxWidth()
                .alpha(if (enabled) 1f else 0.5f)
                // The boxed look lines up with ReportInput, which keeps 2 px outside its border for the focus ring.
                .then(if (borderless) Modifier else Modifier.padding(2.dp))
                .clip(RoundedCornerShape(if (borderless) 4.dp else 8.dp))
                .background(
                    if (borderless) (if (hovered && enabled) colors.hover else Color.Transparent) else fieldGround(),
                )
                .then(
                    if (borderless) Modifier else Modifier.border(
                        1.dp,
                        if (open) colors.accent else colors.borderStrong,
                        RoundedCornerShape(8.dp),
                    ),
                )
                .hoverable(source)
                .plainClick(enabled = enabled, source = source) { open = true }
                .padding(horizontal = if (borderless) 4.dp else 12.dp, vertical = if (borderless) 3.dp else 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                label ?: placeholder,
                style = reportText(fontSize.sp, lineHeight = (fontSize + 6).sp),
                color = if (label == null) colors.textMuted else colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
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
            Column(Modifier.widthIn(min = 140.dp).padding(horizontal = 4.dp)) {
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
    val colors = ReportTheme.colors
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
            style = reportText(13.sp, if (selected) FontWeight.SemiBold else FontWeight.Normal),
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
internal fun fieldGround(): Color = if (ReportTheme.colors.isDark) Color(0x0FFFFFFF) else Color.White

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
    align: TextAlign = TextAlign.Start,
    fontSize: Int = 14,
    keyboardType: KeyboardType = KeyboardType.Text,
    focusRequester: FocusRequester? = null,
    onFocus: () -> Unit = {},
    onBlur: () -> Unit = {},
    onTab: ((backwards: Boolean) -> Unit)? = null,
    accept: (String) -> Boolean = { true },
    ring: Color = Color.Transparent,
    ground: Color = Color.Transparent,
    textColor: Color? = null,
) {
    val colors = ReportTheme.colors
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val style = TextStyle(
        fontSize = fontSize.sp,
        lineHeight = (fontSize * 1.45f).sp,
        fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
        textAlign = align,
        color = textColor ?: colors.textPrimary,
    ).let { reportText(fontSize.sp, if (bold) FontWeight.Bold else FontWeight.Normal).merge(it) }
    Box(
        modifier
            .background(if (focused) colors.accent.copy(alpha = if (colors.isDark) 0.08f else 0.07f) else ground)
            .then(if (ring != Color.Transparent) Modifier.border(2.dp, ring) else Modifier)
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        if (value.isEmpty() && placeholder.isNotEmpty()) {
            Text(
                placeholder,
                style = style.copy(color = colors.textMuted),
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
                .onFocusChanged { if (it.isFocused) onFocus() else onBlur() }
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
internal val EMAIL_TEXT = Regex("""^[^\s@]+@[^\s@]+\.[^\s@]+$""")
internal const val PHONE_MAX = 15

// Date ---------------------------------------------------------------------------------------------

/** A date shown as DD/MM/YYYY and stored as `YYYY-MM-DD`, picked from a month grid. */
@Composable
internal fun DateInput(
    ymd: String,
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = str(S.hint_date),
    boxed: Boolean = false,
    clearable: Boolean = true,
    onFocus: () -> Unit = {},
) {
    val colors = ReportTheme.colors
    var open by remember { mutableStateOf(false) }
    val wire = ReportTime.toWireDate(ymd)
    val date = runCatching { LocalDate.parse(wire) }.getOrNull()
    val label = date?.let { "${it.dayOfMonth.pad()}/${it.monthNumber.pad()}/${it.year}" }
    val (source, hovered) = rememberHover()
    Box(modifier) {
        Row(
            Modifier
                .fillMaxWidth()
                .then(
                    if (boxed) {
                        Modifier.padding(2.dp).clip(RoundedCornerShape(8.dp)).background(fieldGround())
                            .border(1.dp, if (open) colors.accent else colors.borderStrong, RoundedCornerShape(8.dp))
                            .heightIn(min = 36.dp)
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
                style = reportText(if (boxed) 14.sp else 13.sp),
                color = if (label == null) colors.textMuted else colors.textPrimary,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            if (clearable && label != null && hovered) {
                Icon(
                    ZillitIcons.Close,
                    contentDescription = str(S.txt_clear),
                    tint = colors.textMuted,
                    modifier = Modifier.size(12.dp).plainClick { onPick("") },
                )
            } else {
                Icon(
                    ZillitIcons.Calendar,
                    contentDescription = null,
                    tint = colors.textMuted,
                    modifier = Modifier.size(13.dp),
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
            MonthGrid(date) {
                open = false
                onPick(it.toString())
            }
        }
    }
}

@Composable
private fun MonthGrid(selected: LocalDate?, onPick: (LocalDate) -> Unit) {
    val colors = ReportTheme.colors
    val today = remember { Clock.System.todayIn(TimeZone.currentSystemDefault()) }
    var month by remember { mutableStateOf(LocalDate((selected ?: today).year, (selected ?: today).monthNumber, 1)) }
    Column(Modifier.width(252.dp).padding(horizontal = 10.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CalendarNav(ZillitIcons.ChevronLeft, str(S.desktop_previous_month)) {
                month = month.plus(-1, DateTimeUnit.MONTH)
            }
            Text(
                "${MONTH_NAMES[month.monthNumber - 1]} ${month.year}",
                style = reportText(13.sp, FontWeight.SemiBold),
                color = colors.textPrimary,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
            CalendarNav(ZillitIcons.ChevronRight, str(S.desktop_next_month)) {
                month = month.plus(1, DateTimeUnit.MONTH)
            }
        }
        Row(Modifier.padding(top = 6.dp)) {
            listOf("Mo", "Tu", "We", "Th", "Fr", "Sa", "Su").forEach {
                Text(
                    it,
                    style = reportText(10.sp, FontWeight.Medium),
                    color = colors.textMuted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        val lead = month.dayOfWeek.ordinal
        val days = month.plus(1, DateTimeUnit.MONTH).plus(-1, DateTimeUnit.DAY).dayOfMonth
        val cells = List(lead) { null } + (1..days).map { LocalDate(month.year, month.monthNumber, it) }
        cells.chunked(DAYS_IN_WEEK).forEach { week ->
            Row(Modifier.padding(top = 2.dp)) {
                repeat(DAYS_IN_WEEK) { index ->
                    val day = week.getOrNull(index)
                    Box(Modifier.weight(1f).height(30.dp), contentAlignment = Alignment.Center) {
                        if (day != null) DayCell(day, selected = day == selected, today = day == today) { onPick(day) }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.End) {
            Text(
                str(S.today),
                style = reportText(12.sp, FontWeight.SemiBold),
                color = colors.accent,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .plainClick { onPick(today) }.padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
}

private val MONTH_NAMES = listOf(
    "January",
    "February",
    "March",
    "April",
    "May",
    "June",
    "July",
    "August",
    "September",
    "October",
    "November",
    "December",
)
private const val DAYS_IN_WEEK = 7

@Composable
private fun CalendarNav(icon: ImageVector, description: String, onClick: () -> Unit) {
    val colors = ReportTheme.colors
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
private fun DayCell(day: LocalDate, selected: Boolean, today: Boolean, onClick: () -> Unit) {
    val colors = ReportTheme.colors
    val (source, hovered) = rememberHover()
    Box(
        Modifier
            .size(28.dp)
            .clip(CircleShape)
            .background(
                when {
                    selected -> colors.accent
                    hovered -> colors.accentLight
                    else -> Color.Transparent
                },
            )
            .then(if (today && !selected) Modifier.border(1.dp, colors.accent, CircleShape) else Modifier)
            .hoverable(source)
            .plainClick(source = source, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            day.dayOfMonth.toString(),
            style = reportText(12.sp, if (selected || today) FontWeight.SemiBold else FontWeight.Normal),
            color = when {
                selected -> Color.White
                hovered -> colors.accent
                else -> colors.textPrimary
            },
        )
    }
}

// Time ---------------------------------------------------------------------------------------------

/**
 * `DraftTimePicker`: a 24-hour `HH:mm` you can type, or pick from an hour
 * column and a five-minute column; Clear empties it, Done closes.
 */
@Composable
internal fun TimeInput(
    wire: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "HH:MM",
    compact: Boolean = true,
    onFocus: () -> Unit = {},
) {
    val colors = ReportTheme.colors
    var open by remember { mutableStateOf(false) }
    val clock = ReportTime.toWireTime(wire)
    var draft by remember(clock) { mutableStateOf(clock) }
    Box(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            InlineText(
                value = draft,
                onChange = { text ->
                    draft = text
                    val normalised = ReportTime.toWireTime(text)
                    if (text.isEmpty()) onChange("") else if (normalised.isNotEmpty()) onChange(normalised)
                },
                placeholder = placeholder,
                singleLine = true,
                fontSize = if (compact) 13 else 14,
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
            TimePanel(clock, onChange = onChange, onDone = { open = false })
        }
    }
}

private const val CLOCK_LENGTH = 5

@Composable
private fun TimePanel(clock: String, onChange: (String) -> Unit, onDone: () -> Unit) {
    val colors = ReportTheme.colors
    val hour = clock.substringBefore(":", "").takeIf { clock.isNotEmpty() }
    val minute = clock.substringAfter(":", "").takeIf { clock.isNotEmpty() }
    Column(Modifier.width(176.dp)) {
        Row(Modifier.padding(horizontal = 6.dp).height(176.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TimeColumn(
                (0..23).map { it.toString().padStart(2, '0') },
                hour,
                Modifier.weight(1f),
            ) { onChange("$it:${minute ?: "00"}") }
            TimeColumn(
                (0..55 step 5).map { it.toString().padStart(2, '0') },
                minute,
                Modifier.weight(1f),
            ) { onChange("${hour ?: "00"}:$it") }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                str(S.txt_clear),
                style = reportText(12.sp),
                color = colors.textMuted,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .plainClick { onChange("") }.padding(horizontal = 6.dp, vertical = 4.dp),
            )
            Box(Modifier.weight(1f))
            ReportButton(
                str(S.done_text),
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
private fun TimeColumn(items: List<String>, selected: String?, modifier: Modifier, onPick: (String) -> Unit) {
    val colors = ReportTheme.colors
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        val index = items.indexOf(selected)
        if (index > 0) scope.launch { scroll.scrollTo((index - 2).coerceAtLeast(0) * ITEM_PX) }
    }
    Column(modifier.verticalScroll(scroll)) {
        items.forEach { item ->
            val active = item == selected
            val (source, hovered) = rememberHover()
            Text(
                item,
                style = reportText(13.sp, if (active) FontWeight.SemiBold else FontWeight.Normal),
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

/** Comma-separated member ids as chips, chosen in the "Select Users" dialog. */
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
    val colors = ReportTheme.colors
    var open by remember { mutableStateOf(false) }
    val ids = value.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    val chosen = ids.mapNotNull { id -> members.firstOrNull { it.userId == id } }
    FlowRow(
        modifier
            .heightIn(min = 30.dp)
            .plainClick {
                onFocus()
                open = true
            }
            .padding(horizontal = 8.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (chosen.isEmpty()) Text(placeholder, style = reportText(13.sp), color = colors.textMuted)
        chosen.forEach { member ->
            Row(
                Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(colors.accentLight)
                    .border(1.dp, colors.accent, RoundedCornerShape(4.dp))
                    .padding(horizontal = 5.dp, vertical = 1.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    member.fullName,
                    style = reportText(11.sp, FontWeight.Medium),
                    color = colors.chipOnText,
                    maxLines = 1,
                )
                Icon(
                    ZillitIcons.Close,
                    contentDescription = str(S.bs_chip_remove, member.fullName),
                    tint = colors.red,
                    modifier = Modifier.size(9.dp).plainClick { onChange(toggledId(ids, member.userId)) },
                )
            }
        }
    }
    if (open) UsersDialog(ids, members, onToggle = { onChange(toggledId(ids, it)) }, onClose = { open = false })
}

private fun toggledId(ids: List<String>, id: String): String =
    (if (id in ids) ids - id else ids + id).joinToString(",")

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
    ): IntOffset =
        IntOffset.Zero
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
    val colors = ReportTheme.colors
    var search by remember { mutableStateOf("") }
    val query = search.trim().lowercase()
    val pool = members.filter { it.isAccepted && it.userId.isNotBlank() }
        .filter { query.isEmpty() || listOf(
            it.fullName,
            it.designation,
            it.department,
        ).any { field -> field.lowercase().contains(query) } }
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
                    Text(
                        str(S.select_users),
                        style = reportText(16.sp, FontWeight.SemiBold),
                        color = colors.textPrimary,
                    )
                    Text(
                        if (ids.isEmpty()) str(S.desktop_choose_team_members) else str(S.desktop_n_selected, ids.size),
                        style = reportText(12.sp),
                        color = colors.textMuted,
                    )
                }
                CloseSquare(onClose)
            }
            Divider()
            Box(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                ReportInput(
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
                        style = reportText(14.sp),
                        color = colors.textMuted,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                    )
                }
                pool.forEach { member ->
                    val picked = member.userId in ids
                    val (source, hovered) = rememberHover()
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
                            .padding(horizontal = 20.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Face(member.userId, member.fullName, 36.dp)
                        Column(Modifier.weight(1f)) {
                            Text(
                                member.fullName,
                                style = reportText(14.sp, FontWeight.Medium),
                                color = colors.textPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (member.designation.isNotBlank()) {
                                Text(
                                    member.designation,
                                    style = reportText(12.sp),
                                    color = colors.textTertiary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        CheckCircle(picked)
                    }
                }
            }
            Divider()
            Row(
                Modifier.fillMaxWidth().background(colors.elevated).padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (pool.size == 1) "1 member" else "${pool.size} members",
                    style = reportText(12.sp),
                    color = colors.textTertiary,
                    modifier = Modifier.weight(1f),
                )
                ReportButton(
                    str(S.done_text),
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

@Composable
internal fun Divider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(ReportTheme.colors.border))
}

@Composable
internal fun CloseSquare(onClose: () -> Unit, description: String = str(S.close)) {
    val colors = ReportTheme.colors
    val (source, hovered) = rememberHover()
    ZillitTooltip(description) {
        Box(
            Modifier
                .size(28.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(if (hovered) colors.sunken else Color.Transparent)
                .hoverable(source)
                .plainClick(source = source, onClick = onClose),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                ZillitIcons.Close,
                contentDescription = description,
                tint = if (hovered) colors.textSecondary else colors.textMuted,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

@Composable
internal fun PersonChip(member: SheetMember, onRemove: () -> Unit) {
    val colors = ReportTheme.colors
    Row(
        Modifier
            .clip(CircleShape)
            .background(colors.accentLight)
            .border(1.dp, colors.accent, CircleShape)
            .padding(start = 4.dp, end = 10.dp, top = 3.dp, bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Face(member.userId, member.fullName, 20.dp)
        Text(
            member.fullName,
            style = reportText(12.sp, FontWeight.Medium),
            color = colors.chipOnText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 120.dp),
        )
        ZillitTooltip(str(S.remove)) {
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
    val colors = ReportTheme.colors
    Box(
        Modifier.size(20.dp).clip(CircleShape).background(if (checked) colors.accent else Color.Transparent)
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

// Location ------------------------------------------------------------------------------------------

/** The address as text, and a pin button that opens the map picker when the host has one. */
@Composable
internal fun LocationInput(
    value: String,
    attachment: String,
    onChange: (address: String, lat: Double?, lng: Double?) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = str(S.address),
    onFocus: () -> Unit = {},
) {
    val colors = ReportTheme.colors
    val picker = LocalLocationPicker.current
    val scope = rememberCoroutineScope()
    val pin = pinOf(attachment)
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        InlineText(
            value = value,
            onChange = { onChange(it, pin?.first, pin?.second) },
            placeholder = placeholder,
            singleLine = true,
            onFocus = onFocus,
            modifier = Modifier.weight(1f),
        )
        if (picker != null) {
            val (source, hovered) = rememberHover()
            ZillitTooltip(
                if (pin != null) {
                    str(S.desktop_edit_pinned_location_on_map)
                } else {
                    str(S.desktop_pick_location_on_map)
                },
            ) {
                Box(
                    Modifier
                        .padding(end = 4.dp)
                        .size(28.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(
                            when {
                                !hovered -> Color.Transparent
                                pin != null -> colors.accentLight
                                else -> colors.sunken
                            },
                        )
                        .hoverable(source)
                        .plainClick(source = source) {
                            onFocus()
                            scope.launch {
                                val initial = pin?.let { PickedLocation(value, value, it.first, it.second) }
                                picker.pick(initial, str(S.desktop_select_location))?.let { picked ->
                                    onChange(picked.address.ifBlank { picked.name }, picked.lat, picked.lng)
                                }
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "📍",
                        style = reportText(13.sp),
                        modifier = Modifier.alpha(if (pin != null || hovered) 1f else 0.55f),
                    )
                }
            }
        }
    }
}

// Crew IN / OUT -----------------------------------------------------------------------------------------

/** `InSelector`: Time (HH : MM), Per HOD, O/C, or Others with free text — stored mode-prefixed. */
@Composable
internal fun InOutSelector(
    value: String,
    onChange: (String) -> Unit,
    onFocus: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val mode = when {
        value == "Per HOD" -> "Per HOD"
        value == "O/C" -> "O/C"
        value.startsWith("Time:") -> "Time"
        value.startsWith("Other:") -> "Others"
        else -> ""
    }
    Column(modifier.padding(horizontal = 6.dp, vertical = 5.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        PaneSelect(
            value = mode,
            options = listOf(
                "" to str(S.desktop_select_ellipsis),
                "Time" to str(S.time),
                "Per HOD" to str(S.desktop_per_hod),
                "O/C" to "O/C",
                "Others" to str(S.desktop_others_title),
            ),
            onSelect = { picked ->
                onFocus()
                onChange(
                    when (picked) {
                        "Time" -> "Time:"
                        "Others" -> "Other:"
                        else -> picked
                    },
                )
            },
            fontSize = 13,
        )
        when (mode) {
            "Time" -> {
                val clock = ReportTime.toWireTime(value.removePrefix("Time:"))
                val hour = clock.substringBefore(":", "")
                val minute = clock.substringAfter(":", "")
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    PaneSelect(hour, listOf("" to "HH") + (0..23).map { it.pad() to it.pad() }, { h ->
                        onChange("Time:" + ReportTime.toWireTime("$h:${minute.ifEmpty { "00" }}"))
                    }, Modifier.weight(1f), placeholder = "HH", fontSize = 13)
                    Text(":", style = reportText(14.sp, FontWeight.Bold), color = ReportTheme.colors.textSecondary)
                    PaneSelect(minute, listOf("" to "MM") + (0..59).map { it.pad() to it.pad() }, { m ->
                        onChange("Time:" + ReportTime.toWireTime("${hour.ifEmpty { "00" }}:$m"))
                    }, Modifier.weight(1f), placeholder = "MM", fontSize = 13)
                }
            }
            "Others" -> ReportInput(
                value.removePrefix("Other:"),
                { onChange("Other:$it") },
                Modifier.fillMaxWidth(),
                placeholder = str(S.docusign_text_value_hint),
                radius = 6.dp,
                textStyle = reportText(13.sp),
                onFocusChange = { if (it) onFocus() },
            )
        }
    }
}

private fun Int.pad(): String = toString().padStart(2, '0')

/** Placeholder text per column type, the header name first. */
internal fun placeholderFor(column: ColumnSpec?): String {
    val name = column?.label?.trim().orEmpty()
    if (name.isNotEmpty()) return name
    return when (column?.type) {
        "number" -> "0"
        "phone" -> "+1 555 123 4567"
        "email" -> "email@example.com"
        "date" -> str(S.hint_date)
        "time" -> "HH:MM"
        "users" -> str(S.desktop_select_users_placeholder)
        "location" -> str(S.address)
        else -> ""
    }
}
