@file:Suppress("LongMethod", "CyclomaticComplexMethod", "MaxLineLength", "TooManyFunctions", "LongParameterList", "ComplexCondition", "MagicNumber") // Controls drawn to the web's Tasks.css, one composable each.

package com.zillit.desktop.feature.tasks.ui

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ZillitAvatar
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitMenuSurface
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.tasks.domain.TaskPerson
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.Month
import kotlinx.datetime.minus
import kotlinx.datetime.plus

/**
 * The Tasks palette — the web's `Tasks.css` custom properties, light and dark.
 * The tool draws in its own colours (a dark sidebar, a grey work area, a blue
 * accent) rather than the app's, as the web does, so it reads as the same
 * product on both.
 */
internal class TasksColors(
    val bg: Color, val surface: Color, val surface2: Color, val fg: Color, val muted: Color, val faint: Color,
    val line: Color, val line2: Color, val accent: Color, val accentFg: Color, val accentSoft: Color,
    val side: Color, val side2: Color, val sideFg: Color, val sideMuted: Color,
    val late: Color, val lateBg: Color, val warn: Color, val warnBg: Color, val ok: Color, val okBg: Color,
    val chip: Color, val chipFg: Color,
    val todo: Color, val todoBg: Color, val todoDot: Color,
    val prog: Color, val progBg: Color, val progDot: Color,
    val done: Color, val doneBg: Color, val doneDot: Color,
    val cncl: Color, val cnclBg: Color, val cnclDot: Color,
    val privateBg: Color, val privateLine: Color, val scrim: Color, val isDark: Boolean = false,
)

private fun c(hex: Long) = Color(0xFF000000 or hex)

private val LightTasks = TasksColors(
    bg = c(0xf3f4f7), surface = c(0xffffff), surface2 = c(0xf6f7f9), fg = c(0x1a1d26), muted = c(0x5d6475), faint = c(0x7d8497),
    line = c(0xe1e4ea), line2 = c(0xd5d9e2), accent = c(0x2d5be3), accentFg = c(0xffffff), accentSoft = c(0xe6eefc),
    side = c(0x1b2030), side2 = c(0x252b3d), sideFg = c(0xc9cedb), sideMuted = c(0x8a91a5),
    late = c(0xb3362c), lateBg = c(0xfde6e4), warn = c(0x8a5a06), warnBg = c(0xfbf0d9), ok = c(0x1f7a50), okBg = c(0xe2f4ea),
    chip = c(0xeef1f6), chipFg = c(0x3c4252),
    todo = c(0x4d5466), todoBg = c(0xeceef3), todoDot = c(0x8a91a5),
    prog = c(0x2553d4), progBg = c(0xe3ebfc), progDot = c(0x2d5be3),
    done = c(0x1f7a50), doneBg = c(0xe0f3e9), doneDot = c(0x2f9e6a),
    cncl = c(0x6a7183), cnclBg = c(0xe9ebf0), cnclDot = c(0x9aa1b2),
    privateBg = c(0xfafbfc), privateLine = c(0xb9bfcc), scrim = Color(0x660A0C14),
)

private val DarkTasks = TasksColors(
    bg = c(0x12141a), surface = c(0x1b1e27), surface2 = c(0x222633), fg = c(0xe8eaf0), muted = c(0xa3aabb), faint = c(0x8a91a3),
    line = c(0x2c303d), line2 = c(0x3a3f4e), accent = c(0x7497f5), accentFg = c(0x0e1220), accentSoft = c(0x1f2a45),
    side = c(0x0d0f14), side2 = c(0x1a1d27), sideFg = c(0xc9cedb), sideMuted = c(0x7d8497),
    late = c(0xf08a80), lateBg = c(0x3a2220), warn = c(0xe6b04f), warnBg = c(0x352a17), ok = c(0x62c795), okBg = c(0x183326),
    chip = c(0x262a36), chipFg = c(0xc3c8d4),
    todo = c(0xbfc4d0), todoBg = c(0x272b37), todoDot = c(0x8a91a5),
    prog = c(0x8eabf7), progBg = c(0x1f2a45), progDot = c(0x6f93f5),
    done = c(0x6fd1a0), doneBg = c(0x173226), doneDot = c(0x3fb07a),
    cncl = c(0x9aa1b2), cnclBg = c(0x242833), cnclDot = c(0x6b7285),
    privateBg = c(0x171a22), privateLine = c(0x4a5063), scrim = Color(0x990A0C14), isDark = true,
)

private val LocalTasksColors = staticCompositionLocalOf { LightTasks }

internal object TasksTheme {
    val c: TasksColors @Composable get() = LocalTasksColors.current
}

/** Provides the palette: the app's own light/dark unless [dark] says otherwise. */
@Composable
internal fun TasksThemeProvider(dark: Boolean?, content: @Composable () -> Unit) {
    val isDark = dark ?: ZillitTheme.colors.isDark
    CompositionLocalProvider(LocalTasksColors provides if (isDark) DarkTasks else LightTasks, content = content)
}

internal fun txt(size: Int, weight: FontWeight = FontWeight.Normal, lineHeight: TextUnit = TextUnit.Unspecified) =
    TextStyle(fontSize = size.sp, fontWeight = weight, lineHeight = lineHeight)

internal val SemiBold = FontWeight.SemiBold
internal val Medium = FontWeight.Medium

/** What a status is coloured: text, wash and dot. */
internal class StatusLook(val fg: Color, val bg: Color, val dot: Color)

@Composable
internal fun statusLook(status: com.zillit.desktop.feature.tasks.domain.TaskStatus): StatusLook {
    val k = TasksTheme.c
    return when (status) {
        com.zillit.desktop.feature.tasks.domain.TaskStatus.Todo -> StatusLook(k.todo, k.todoBg, k.todoDot)
        com.zillit.desktop.feature.tasks.domain.TaskStatus.Progress -> StatusLook(k.prog, k.progBg, k.progDot)
        com.zillit.desktop.feature.tasks.domain.TaskStatus.Done -> StatusLook(k.done, k.doneBg, k.doneDot)
        com.zillit.desktop.feature.tasks.domain.TaskStatus.Cancelled -> StatusLook(k.cncl, k.cnclBg, k.cnclDot)
    }
}

// -- buttons ------------------------------------------------------------------------

internal enum class BtnKind { Primary, Ghost }

/** `.zt-btn`: 40dp, or 32dp when [small]; the accent button, or the white one with a line. */
@Composable
internal fun TBtn(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: BtnKind = BtnKind.Primary,
    small: Boolean = false,
    enabled: Boolean = true,
    icon: ImageVector? = null,
) {
    val k = TasksTheme.c
    val primary = kind == BtnKind.Primary
    val shape = RoundedCornerShape(8.dp)
    Row(
        modifier
            .height(if (small) 32.dp else 40.dp)
            .clip(shape)
            .background(if (primary) k.accent else k.surface)
            .then(if (primary) Modifier else Modifier.border(BorderStroke(1.dp, k.line2), shape))
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = if (small) 10.dp else 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
    ) {
        if (icon != null) ZillitIcon(icon, tint = if (primary) k.accentFg else k.fg, size = 16.dp)
        ZillitText(
            text = text,
            style = txt(if (small) 13 else 14, if (primary) SemiBold else Medium),
            color = (if (primary) k.accentFg else k.fg).let { if (enabled) it else it.copy(alpha = 0.6f) },
            maxLines = 1,
        )
    }
}

/** A 32dp icon-only button (`.zt-icon-btn`). */
@Composable
internal fun TIconBtn(icon: ImageVector, description: String, onClick: () -> Unit, modifier: Modifier = Modifier, tint: Color? = null) {
    val k = TasksTheme.c
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    Box(
        modifier.size(32.dp).clip(RoundedCornerShape(8.dp)).background(if (hovered) k.chip else Color.Transparent)
            .hoverable(source).clickable(interactionSource = source, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { ZillitIcon(icon, contentDescription = description, tint = tint ?: k.muted, size = 18.dp) }
}

// -- text ----------------------------------------------------------------------------

/** A bare text input: no chrome of its own. [decoration] draws what is around it. */
@Composable
internal fun TInput(
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    style: TextStyle = txt(14),
    singleLine: Boolean = true,
    enabled: Boolean = true,
    maxLength: Int? = null,
    onEnter: (() -> Unit)? = null,
    onFocus: (Boolean) -> Unit = {},
    decoration: @Composable (inner: @Composable () -> Unit, focused: Boolean) -> Unit = { inner, _ -> inner() },
) {
    val k = TasksTheme.c
    var focused by remember { mutableStateOf(false) }
    BasicTextField(
        value = value,
        onValueChange = { next -> if (maxLength == null || next.length <= maxLength) onChange(next) },
        modifier = modifier.onFocusChanged {
            focused = it.isFocused
            onFocus(it.isFocused)
        },
        enabled = enabled,
        singleLine = singleLine,
        textStyle = style.copy(color = k.fg),
        cursorBrush = SolidColor(k.accent),
        keyboardOptions = KeyboardOptions(imeAction = if (onEnter != null) ImeAction.Done else ImeAction.Default),
        keyboardActions = KeyboardActions(onDone = { onEnter?.invoke() }),
        decorationBox = { inner ->
            decoration(
                {
                    Box {
                        if (value.isEmpty()) ZillitText(placeholder, style = style, color = k.faint, maxLines = 1)
                        inner()
                    }
                },
                focused,
            )
        },
    )
}

/** The search box of the toolbar (`.zt-search`). */
@Composable
internal fun TSearch(value: String, onChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier) {
    val k = TasksTheme.c
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(8.dp)
    Row(
        modifier.height(36.dp).clip(shape).background(k.surface).border(BorderStroke(1.dp, if (focused) k.accent else k.line2), shape).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ZillitIcon(ZillitIcons.Search, tint = k.faint, size = 16.dp)
        TInput(value, onChange, Modifier.weight(1f), placeholder = placeholder, style = txt(13), onFocus = { focused = it })
        if (value.isNotEmpty()) {
            Box(Modifier.size(22.dp).clip(CircleShape).clickable { onChange("") }, contentAlignment = Alignment.Center) {
                ZillitIcon(ZillitIcons.Close, tint = k.faint, size = 14.dp)
            }
        }
    }
}

// -- small pieces ---------------------------------------------------------------------

@Composable
internal fun Pill(text: String, fg: Color, bg: Color, modifier: Modifier = Modifier, size: Int = 12) {
    ZillitText(
        text = text,
        modifier = modifier.clip(RoundedCornerShape(999.dp)).background(bg).padding(horizontal = 9.dp, vertical = 3.dp),
        style = txt(size, SemiBold),
        color = fg,
        maxLines = 1,
    )
}

@Composable
internal fun Dot(color: Color, size: Dp = 8.dp) {
    Box(Modifier.size(size).clip(CircleShape).background(color))
}

@Composable
internal fun TAvatar(person: TaskPerson?, size: Dp = 26.dp) {
    if (person == null) {
        Box(Modifier.size(size).border(BorderStroke(1.5.dp, TasksTheme.c.line2), CircleShape))
    } else {
        ZillitAvatar(name = person.fullName, userId = person.id, size = size)
    }
}

/** A one-line (or [maxLines]) label in the palette's text colours. */
@Composable
internal fun TText(text: String, size: Int = 14, weight: FontWeight = FontWeight.Normal, color: Color = TasksTheme.c.fg, modifier: Modifier = Modifier, maxLines: Int = Int.MAX_VALUE, strike: Boolean = false) {
    ZillitText(
        text = text,
        modifier = modifier,
        style = txt(size, weight, (size * 1.4f).sp).copy(textDecoration = if (strike) androidx.compose.ui.text.style.TextDecoration.LineThrough else null),
        color = color,
        maxLines = maxLines,
    )
}

// -- pickers ----------------------------------------------------------------------------

/** What a [TPicker] offers; `id == ""` is "nothing chosen". */
internal data class Pick(val id: String, val label: String, val subtitle: String? = null, val person: TaskPerson? = null, val colour: Color? = null)

/**
 * The crew and department pickers: a ghost button showing the choice (with an
 * avatar or a department dot), opening a searchable list. [compact] is the
 * bordered 36dp form of the Board's toolbar.
 */
@Composable
internal fun TPicker(
    value: String?,
    picks: List<Pick>,
    emptyLabel: String,
    onChange: (String?) -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    enabled: Boolean = true,
    searchHint: String = str(S.dd_history_sender_picker_search_hint),
    avatars: Boolean = false,
) {
    val k = TasksTheme.c
    var open by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val none = Pick("", emptyLabel)
    val chosen = picks.firstOrNull { it.id == (value ?: "") } ?: none
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val shape = RoundedCornerShape(if (compact) 8.dp else 6.dp)

    Box(modifier) {
        Row(
            Modifier
                .height(if (compact) 36.dp else 34.dp)
                .clip(shape)
                .background(if (compact) k.surface else if (hovered && enabled) k.surface2 else Color.Transparent)
                .then(if (compact) Modifier.border(BorderStroke(1.dp, k.line2), shape) else Modifier)
                .hoverable(source)
                .then(if (enabled) Modifier.clickable(interactionSource = source, indication = null) { open = true } else Modifier)
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when {
                chosen.person != null -> TAvatar(chosen.person, 22.dp)
                avatars && chosen.id.isEmpty() -> TAvatar(null, 22.dp)
                chosen.colour != null && chosen.id.isNotEmpty() -> Dot(chosen.colour)
            }
            TText(chosen.label, 14, color = if (chosen.id.isEmpty()) k.faint else k.fg, maxLines = 1, modifier = Modifier.widthIn(max = 220.dp))
            ZillitIcon(ZillitIcons.ChevronDown, tint = k.faint, size = 14.dp)
        }
        ZillitMenuSurface(expanded = open, onDismissRequest = { open = false; query = "" }) {
            Column(Modifier.width(300.dp)) {
                TInput(
                    value = query,
                    onChange = { query = it },
                    placeholder = searchHint,
                    style = txt(13),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                )
                Box(Modifier.fillMaxWidth().height(1.dp).background(k.line))
                val words = query.lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
                val shown = (listOf(none) + picks).filter { p ->
                    p.id.isEmpty() && words.isEmpty() || p.id.isNotEmpty() && words.all { w -> "${p.label} ${p.subtitle.orEmpty()}".lowercase().contains(w) }
                }
                Column(Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState())) {
                    if (shown.isEmpty() || (shown.size == 1 && shown[0].id.isEmpty() && words.isNotEmpty())) {
                        TText(str(S.dm_picker_empty), 13, color = k.faint, modifier = Modifier.padding(12.dp))
                    }
                    shown.forEach { p ->
                        val row = remember { MutableInteractionSource() }
                        val rowHover by row.collectIsHoveredAsState()
                        Row(
                            Modifier.fillMaxWidth().background(if (rowHover || p.id == chosen.id) k.accentSoft else Color.Transparent)
                                .hoverable(row)
                                .clickable(interactionSource = row, indication = null) {
                                    open = false
                                    query = ""
                                    onChange(p.id.ifEmpty { null })
                                }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            when {
                                p.person != null -> TAvatar(p.person, 28.dp)
                                p.colour != null -> Dot(p.colour)
                            }
                            Column(Modifier.weight(1f)) {
                                TText(p.label, 13, if (p.id == chosen.id) SemiBold else Medium, color = if (p.id.isEmpty()) k.muted else k.fg, maxLines = 1)
                                p.subtitle?.let { TText(it, 12, color = k.faint, maxLines = 1) }
                            }
                        }
                    }
                }
            }
        }
    }
}

// -- date ---------------------------------------------------------------------------------

private val WEEKDAYS = listOf("Mo", "Tu", "We", "Th", "Fr", "Sa", "Su")

private fun Month.title(): String = name.take(3).lowercase().replaceFirstChar { it.uppercase() }

/**
 * A date button and its calendar. Days outside [min]..[max] are greyed out and
 * cannot be picked, as the web's picker greys the days the server would
 * refuse; "No due date" always clears.
 */
@Composable
internal fun TDate(
    value: String?,
    label: String,
    onChange: (String?) -> Unit,
    today: LocalDate,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    compact: Boolean = false,
    min: String? = null,
    max: String? = null,
    hint: String? = null,
) {
    val k = TasksTheme.c
    var open by remember { mutableStateOf(false) }
    val selected = value?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
    var month by remember(open) { mutableStateOf(LocalDate((selected ?: today).year, (selected ?: today).month, 1)) }
    val lo = min?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
    val hi = max?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val shape = RoundedCornerShape(if (compact) 8.dp else 6.dp)

    Box(modifier) {
        Row(
            Modifier.height(if (compact) 36.dp else 34.dp).clip(shape)
                .background(if (compact) k.surface else if (hovered && enabled) k.surface2 else Color.Transparent)
                .then(if (compact) Modifier.border(BorderStroke(1.dp, k.line2), shape) else Modifier)
                .hoverable(source)
                .then(if (enabled) Modifier.clickable(interactionSource = source, indication = null) { open = true } else Modifier)
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ZillitIcon(ZillitIcons.Calendar, tint = k.faint, size = 15.dp)
            TText(label, 14, color = if (value == null) k.faint else k.fg, maxLines = 1)
        }
        ZillitMenuSurface(expanded = open, onDismissRequest = { open = false }) {
            Column(Modifier.width(272.dp).padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TIconBtn(ZillitIcons.ChevronLeft, "", { month = month.minus(DatePeriod(months = 1)) })
                    TText("${month.month.title()} ${month.year}", 14, SemiBold, modifier = Modifier.weight(1f), maxLines = 1)
                    TIconBtn(ZillitIcons.ChevronRight, "", { month = month.plus(DatePeriod(months = 1)) })
                }
                Row(Modifier.fillMaxWidth()) {
                    WEEKDAYS.forEach { d -> TText(d, 11, SemiBold, k.faint, Modifier.weight(1f), maxLines = 1) }
                }
                val lead = month.dayOfWeek.ordinal
                val days = month.plus(DatePeriod(months = 1)).minus(DatePeriod(days = 1)).day
                val cells = List(lead) { null } + (1..days).map { LocalDate(month.year, month.month, it) }
                cells.chunked(7).forEach { week ->
                    Row(Modifier.fillMaxWidth()) {
                        (week + List(7 - week.size) { null }).forEach { day ->
                            Box(Modifier.weight(1f).height(32.dp), contentAlignment = Alignment.Center) {
                                if (day != null) {
                                    val allowed = (lo == null || day >= lo) && (hi == null || day <= hi)
                                    val on = day == selected
                                    Box(
                                        Modifier.size(30.dp).clip(CircleShape)
                                            .background(if (on) k.accent else Color.Transparent)
                                            .then(if (day == today && !on) Modifier.border(BorderStroke(1.dp, k.accent), CircleShape) else Modifier)
                                            .then(
                                                if (allowed) {
                                                    Modifier.clickable {
                                                        open = false
                                                        onChange(day.toString())
                                                    }
                                                } else {
                                                    Modifier
                                                },
                                            ),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        TText(day.day.toString(), 13, if (on) SemiBold else Normal, if (on) k.accentFg else if (allowed) k.fg else k.line2)
                                    }
                                }
                            }
                        }
                    }
                }
                hint?.let { TText(it, 12, color = k.muted) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TText(str(S.desktop_tasks_no_date_pick), 13, SemiBold, k.accent, Modifier.clickable { open = false; onChange(null) }.padding(4.dp))
                    TText(str(S.today), 13, SemiBold, k.accent, Modifier.clickable { open = false; onChange(today.toString()) }.padding(4.dp))
                }
            }
        }
    }
}

private val Normal = FontWeight.Normal

/** The comment line: a TextFieldValue input (so the caret is known for @mentions) with no chrome of its own. */
@Composable
internal fun CommentField(value: androidx.compose.ui.text.input.TextFieldValue, onChange: (androidx.compose.ui.text.input.TextFieldValue) -> Unit, placeholder: String, onEnter: () -> Unit) {
    val k = TasksTheme.c
    BasicTextField(
        value = value,
        onValueChange = { if (it.text.length <= COMMENT_MAX) onChange(it) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        textStyle = txt(14).copy(color = k.fg),
        cursorBrush = SolidColor(k.accent),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
        keyboardActions = KeyboardActions(onSend = { onEnter() }),
        decorationBox = { inner ->
            Box {
                if (value.text.isEmpty()) ZillitText(placeholder, style = txt(14), color = k.faint, maxLines = 1)
                inner()
            }
        },
    )
}

private const val COMMENT_MAX = 4000
