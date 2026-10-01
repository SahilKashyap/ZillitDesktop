package com.zillit.desktop.feature.costumesetsync.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.costumesetsync.ui.t
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.todayIn
import kotlin.time.Clock

/**
 * The web's `size="small"` antd controls (24px tall, 6px radius, 7px side padding, 14px text) for table
 * cells — the design system's fields are 36–40dp and cannot shrink under a table row.
 */
private val SMALL_H = 24.dp
private val SMALL_RADIUS = 6.dp
private val SMALL_PAD = 7.dp
private val SMALL_TEXT = 14.sp

/** A small single-line text input. [error] draws the red border (the message is the caller's to show). */
@Composable
internal fun CompactField(
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    error: Boolean = false,
    placeholder: String = "",
    numeric: Boolean = false,
    autoFocus: Boolean = false,
    onEnter: (() -> Unit)? = null,
    /** 32dp for antd's default size (the schedule review's own inputs); 24dp is `size="small"`. */
    height: Dp = SMALL_H,
) {
    val colors = ZillitTheme.colors
    var focused by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    if (autoFocus) LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val border = when {
        error -> colors.danger
        focused -> colors.accent
        else -> colors.border
    }
    Box(
        modifier
            .height(height)
            .clip(RoundedCornerShape(SMALL_RADIUS))
            .background(if (enabled) colors.surface else colors.surfaceHover)
            .border(1.dp, border, RoundedCornerShape(SMALL_RADIUS))
            .padding(horizontal = if (height > SMALL_H) 11.dp else SMALL_PAD),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (value.isEmpty() && placeholder.isNotEmpty()) {
            ZillitText(placeholder, style = ZillitTheme.typography.bodyMedium.copy(fontSize = SMALL_TEXT), color = colors.textMuted, maxLines = 1)
        }
        BasicTextField(
            value = value,
            onValueChange = onChange,
            modifier = Modifier.fillMaxWidth().focusRequester(focus).onFocusChanged { focused = it.isFocused },
            enabled = enabled,
            singleLine = true,
            textStyle = ZillitTheme.typography.bodyMedium.copy(
                fontSize = SMALL_TEXT,
                color = if (enabled) colors.textPrimary else colors.textDisabled,
            ),
            cursorBrush = SolidColor(colors.accent),
            keyboardOptions = KeyboardOptions(keyboardType = if (numeric) KeyboardType.Number else KeyboardType.Text, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onEnter?.invoke() }),
        )
    }
}

/**
 * A small dropdown over [options] (value, label). [searchable] puts a filter box above the list (the actor
 * picker); [onNew] adds a leading "+ <newLabel>" row (the "+ New actor" of the web's `ActorSelect`).
 */
@Composable
internal fun CompactSelect(
    value: String,
    options: List<Pair<String, String>>,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    placeholder: String = "",
    searchable: Boolean = false,
    newLabel: String? = null,
    onNew: () -> Unit = {},
) {
    val colors = ZillitTheme.colors
    var open by remember { mutableStateOf(false) }
    var query by remember(open) { mutableStateOf("") }
    val shown = options.firstOrNull { it.first == value && it.first.isNotEmpty() }
    Box(modifier) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(SMALL_H)
                .clip(RoundedCornerShape(SMALL_RADIUS))
                .background(if (enabled) colors.surface else colors.surfaceHover)
                .border(1.dp, if (open) colors.accent else colors.border, RoundedCornerShape(SMALL_RADIUS))
                .clickable(enabled = enabled) { open = true }
                .padding(start = SMALL_PAD, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            ZillitText(
                shown?.second ?: placeholder,
                Modifier.weight(1f),
                style = ZillitTheme.typography.bodyMedium.copy(fontSize = SMALL_TEXT),
                color = when {
                    !enabled -> colors.textDisabled
                    shown == null -> colors.textMuted
                    else -> colors.textPrimary
                },
                maxLines = 1,
            )
            ZillitIcon(ZillitIcons.ChevronDown, tint = colors.textMuted, size = 12.dp)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.widthIn(min = 150.dp)) {
            if (searchable) {
                val focus = remember { FocusRequester() }
                LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
                CompactField(query, { query = it }, Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp).focusRequester(focus), placeholder = t("csync_search"))
            }
            val needle = query.trim().lowercase()
            val rows = if (needle.isEmpty()) options else options.filter { it.second.lowercase().contains(needle) }
            Column(Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState())) {
                if (newLabel != null) {
                    MenuRow("+ $newLabel", false) {
                        open = false
                        onNew()
                    }
                }
                rows.take(MAX_ROWS).forEach { (v, label) ->
                    MenuRow(label, v == value) {
                        open = false
                        onChange(v)
                    }
                }
                if (rows.isEmpty() && newLabel == null) {
                    ZillitText("—", Modifier.padding(12.dp), style = ZillitTheme.typography.bodySmall, color = colors.textMuted)
                }
            }
        }
    }
}

private const val MAX_ROWS = 100

@Composable
private fun MenuRow(text: String, selected: Boolean, onClick: () -> Unit) {
    val colors = ZillitTheme.colors
    Box(
        Modifier
            .fillMaxWidth()
            .background(if (selected) colors.accentSoft else androidx.compose.ui.graphics.Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 5.dp),
    ) {
        ZillitText(text, style = ZillitTheme.typography.bodyMedium.copy(fontSize = SMALL_TEXT), maxLines = 1)
    }
}

/** A small date box (YYYY-MM-DD, as antd's default) with a month calendar to pick from. */
@Composable
internal fun CompactDate(
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    height: Dp = SMALL_H,
    /** Show DD/MM/YYYY (the schedule review's format) instead of the stored YYYY-MM-DD. */
    dmy: Boolean = false,
) {
    val colors = ZillitTheme.colors
    var open by remember { mutableStateOf(false) }
    val today = remember { Clock.System.todayIn(TimeZone.currentSystemDefault()) }
    val picked = remember(value) { runCatching { LocalDate.parse(value.trim()) }.getOrNull() }
    var month by remember(open, picked) { mutableStateOf(picked ?: today) }
    Box(modifier) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(height)
                .clip(RoundedCornerShape(SMALL_RADIUS))
                .background(if (enabled) colors.surface else colors.surfaceHover)
                .border(1.dp, if (open) colors.accent else colors.border, RoundedCornerShape(SMALL_RADIUS))
                .clickable(enabled = enabled) { open = true }
                .padding(start = if (height > SMALL_H) 11.dp else SMALL_PAD, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            val shape = if (dmy) "DD/MM/YYYY" else "YYYY-MM-DD"
            ZillitText(
                if (value.isEmpty()) shape else if (dmy && picked != null) "%02d/%02d/%04d".format(picked.day, picked.month.ordinal + 1, picked.year) else value,
                Modifier.weight(1f),
                style = ZillitTheme.typography.bodyMedium.copy(fontSize = SMALL_TEXT),
                color = if (value.isEmpty() || !enabled) colors.textMuted else colors.textPrimary,
                maxLines = 1,
            )
            ZillitIcon(ZillitIcons.Calendar, tint = colors.textMuted, size = 14.dp)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            MonthGrid(
                month = month, selected = picked, today = today, onMonth = { month = it },
                onPick = {
                    open = false
                    onChange(it.toString())
                },
                onClear = if (value.isNotEmpty()) {
                    {
                        open = false
                        onChange("")
                    }
                } else {
                    null
                },
            )
        }
    }
}

private val DAY = 30.dp
private val DAY_NAMES = listOf("Mo", "Tu", "We", "Th", "Fr", "Sa", "Su")
private val MONTH_NAMES = listOf("January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December")

@Composable
private fun MonthGrid(
    month: LocalDate,
    selected: LocalDate?,
    today: LocalDate,
    onMonth: (LocalDate) -> Unit,
    onPick: (LocalDate) -> Unit,
    onClear: (() -> Unit)?,
) {
    val colors = ZillitTheme.colors
    val first = LocalDate(month.year, month.month, 1)
    val lead = first.dayOfWeek.ordinal
    val days = first.plus(1, DateTimeUnit.MONTH).minus(1, DateTimeUnit.DAY).day
    Column(Modifier.width(DAY * 7 + 16.dp).padding(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(DAY).clickable { onMonth(first.minus(1, DateTimeUnit.MONTH)) }, contentAlignment = Alignment.Center) { ZillitText("‹") }
            ZillitText(
                "${MONTH_NAMES[month.month.ordinal]} ${month.year}", Modifier.weight(1f),
                style = ZillitTheme.typography.titleSmall, textAlign = TextAlign.Center,
            )
            Box(Modifier.size(DAY).clickable { onMonth(first.plus(1, DateTimeUnit.MONTH)) }, contentAlignment = Alignment.Center) { ZillitText("›") }
        }
        Row { DAY_NAMES.forEach { Box(Modifier.size(DAY), Alignment.Center) { ZillitText(it, style = ZillitTheme.typography.bodySmall, color = colors.textMuted) } } }
        (0 until 6).forEach { week ->
            Row {
                (0 until 7).forEach { col ->
                    val n = week * 7 + col - lead + 1
                    if (n < 1 || n > days) {
                        Box(Modifier.size(DAY))
                    } else {
                        val date = LocalDate(month.year, month.month, n)
                        val on = date == selected
                        Box(
                            Modifier
                                .size(DAY)
                                .clip(RoundedCornerShape(SMALL_RADIUS))
                                .background(if (on) colors.accent else androidx.compose.ui.graphics.Color.Transparent)
                                .then(if (date == today && !on) Modifier.border(1.dp, colors.accent, RoundedCornerShape(SMALL_RADIUS)) else Modifier)
                                .clickable { onPick(date) },
                            contentAlignment = Alignment.Center,
                        ) {
                            ZillitText(
                                n.toString(), style = ZillitTheme.typography.bodyMedium.copy(fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal),
                                color = if (on) colors.textOnAccent else colors.textPrimary,
                            )
                        }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            ZillitText(
                t("csync_when_today"), Modifier.clickable { onPick(today) },
                style = ZillitTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold), color = colors.accentText,
            )
            if (onClear != null) {
                ZillitText(t("csync_clear"), Modifier.clickable(onClick = onClear), style = ZillitTheme.typography.bodySmall, color = colors.textMuted)
            }
        }
    }
}
