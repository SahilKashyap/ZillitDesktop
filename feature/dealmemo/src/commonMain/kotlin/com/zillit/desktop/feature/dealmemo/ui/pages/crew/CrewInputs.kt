package com.zillit.desktop.feature.dealmemo.ui.pages.crew

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.dealmemo.domain.preview.CrewFormValues
import com.zillit.desktop.feature.dealmemo.domain.preview.DealCalc
import com.zillit.desktop.feature.dealmemo.ui.components.DmType
import com.zillit.desktop.feature.dealmemo.ui.components.rememberHover
import com.zillit.desktop.feature.dealmemo.ui.pages.preview.shadowed
import com.zillit.desktop.feature.dealmemo.ui.pages.rules.BelowStartPosition
import kotlin.time.Clock
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.Month
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.number
import kotlinx.datetime.plus
import kotlinx.datetime.todayIn

/**
 * A date field over an epoch (`<input type="date">`): typed as DD/MM/YYYY —
 * the slashes are drawn, never stored — or picked from a calendar. A typed
 * date after [max] is refused when [strictMax], as the web's date-of-birth
 * handler refuses it; otherwise [min] and [max] only bound the calendar, as
 * an input's `min`/`max` attributes do. Clearing the box clears the date.
 */
@Suppress("CyclomaticComplexMethod", "LongMethod")
@Composable
internal fun DateInput(
    millis: Long?,
    onChange: (Long?) -> Unit,
    zone: TimeZone,
    modifier: Modifier = Modifier,
    max: LocalDate? = null,
    min: LocalDate? = null,
    strictMax: Boolean = true,
    height: Dp = 44.dp,
    textSize: Float = 13.5f,
    enabled: Boolean = true,
    error: Boolean = false,
) {
    val p = cp
    val date = CrewFormValues.dateOf(millis, zone)
    var digits by remember { mutableStateOf(date?.let(::digitsOf).orEmpty()) }
    LaunchedEffect(date) {
        if (parseDigits(digits) != date) digits = date?.let(::digitsOf).orEmpty()
    }
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        FormInput(
            value = digits,
            onValueChange = { typed ->
                val next = typed.filter { it.isDigit() }.take(DATE_DIGITS)
                val parsed = parseDigits(next)
                when {
                    next.isEmpty() -> {
                        digits = next
                        onChange(null)
                    }
                    strictMax && parsed != null && max != null && parsed > max -> Unit
                    else -> {
                        digits = next
                        if (parsed != null) onChange(CrewFormValues.epochOf(parsed, zone))
                    }
                }
            },
            placeholder = "DD/MM/YYYY",
            enabled = enabled,
            error = error,
            height = height,
            textSize = textSize,
            visualTransformation = DateSlashes,
            onBlur = { if (parseDigits(digits) == null) digits = date?.let(::digitsOf).orEmpty() },
            trailing = {
                val (source, hovered) = rememberHover()
                Box(
                    modifier = Modifier
                        .size(height - 12.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (hovered || open) p.hover else Color.Transparent)
                        .hoverable(source)
                        .then(
                            if (enabled) {
                                Modifier.clickable(interactionSource = source, indication = null) { open = !open }
                                    .pointerHoverIcon(PointerIcon.Hand)
                            } else {
                                Modifier
                            },
                        ),
                    contentAlignment = Alignment.Center,
                ) { ZillitIcon(ZillitIcons.Calendar, size = 15.dp, tint = if (open) p.amber else p.muted) }
            },
        )
        if (open) {
            Popup(
                popupPositionProvider = remember { BelowStartPosition(gap = 6) },
                onDismissRequest = { open = false },
                properties = PopupProperties(focusable = true),
            ) {
                CalendarPanel(
                    selected = date,
                    min = min,
                    max = max,
                    onPick = { picked ->
                        open = false
                        digits = digitsOf(picked)
                        onChange(CrewFormValues.epochOf(picked, zone))
                    },
                    onClear = {
                        open = false
                        digits = ""
                        onChange(null)
                    },
                )
            }
        }
    }
}

private const val DATE_DIGITS = 8

@Suppress("MagicNumber") // ddMMyyyy: two digits, two, then four.
private fun digitsOf(date: LocalDate): String =
    date.day.toString().padStart(2, '0') + date.month.number.toString().padStart(2, '0') +
        date.year.toString().padStart(4, '0')

@Suppress("MagicNumber") // ddMMyyyy: two digits, two, then four.
private fun parseDigits(digits: String): LocalDate? {
    if (digits.length != DATE_DIGITS) return null
    val day = digits.substring(0, 2).toInt()
    val month = digits.substring(2, 4).toInt()
    val year = digits.substring(4, 8).toInt()
    return runCatching { LocalDate(year, month, day) }.getOrNull()
}

private val DateSlashes = PairSeparators('/')

/**
 * Separators drawn after the second and fourth characters — `15031990` as
 * `15/03/1990`, `204891` as `20-48-91` — each only once the character after it
 * exists. The stored text never holds them, so the cursor never jumps.
 */
@Suppress("MagicNumber") // The separator positions the KDoc above describes.
internal class PairSeparators(private val separator: Char) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val raw = text.text
        val out = buildString {
            raw.forEachIndexed { i, c ->
                append(c)
                if ((i == 1 || i == 3) && i < raw.lastIndex) append(separator)
            }
        }
        val mapping = object : OffsetMapping {
            override fun originalToTransformed(offset: Int): Int {
                val shifted = when {
                    offset <= 2 -> offset
                    offset <= 4 -> offset + 1
                    else -> offset + 2
                }
                return shifted.coerceAtMost(out.length)
            }

            override fun transformedToOriginal(offset: Int): Int {
                val shifted = when {
                    offset <= 2 -> offset
                    offset <= 5 -> offset - 1
                    else -> offset - 2
                }
                return shifted.coerceIn(0, raw.length)
            }
        }
        return TransformedText(AnnotatedString(out), mapping)
    }
}

@Suppress("LongMethod")
@Composable
private fun CalendarPanel(
    selected: LocalDate?,
    min: LocalDate?,
    max: LocalDate?,
    onPick: (LocalDate) -> Unit,
    onClear: () -> Unit,
) {
    val p = cp
    val today = remember { Clock.System.todayIn(TimeZone.currentSystemDefault()) }
    var month by remember { mutableStateOf(firstOf(selected ?: min?.takeIf { it > today } ?: today)) }
    fun allowed(day: LocalDate) = (max == null || day <= max) && (min == null || day >= min)
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier = Modifier
            .width(292.dp)
            .shadowed(shape)
            .clip(shape)
            .background(p.menu)
            .border(1.dp, p.pickerBorder, shape)
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            NavButton("«") { month = month.minus(1, DateTimeUnit.YEAR) }
            NavButton("‹") { month = month.minus(1, DateTimeUnit.MONTH) }
            ZillitText(
                text = "${MONTH_NAMES[month.month.ordinal]} ${month.year}",
                style = DmType.sans(13.5.sp, FontWeight.Bold),
                color = p.ink,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
            NavButton("›") { month = month.plus(1, DateTimeUnit.MONTH) }
            NavButton("»") { month = month.plus(1, DateTimeUnit.YEAR) }
        }
        Row(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 4.dp)) {
            WEEKDAYS.forEach {
                ZillitText(
                    text = it,
                    style = DmType.mono(10.sp, FontWeight.Bold),
                    color = p.muted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        weeksOf(month).forEach { week ->
            Row(Modifier.fillMaxWidth()) {
                week.forEach { day ->
                    DayCell(
                        day = day,
                        inMonth = day.month == month.month,
                        selected = day == selected,
                        today = day == today,
                        enabled = allowed(day),
                        onPick = onPick,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            TextAction(str(S.dm_sign_clear), onClear)
            if (allowed(today)) TextAction(str(S.today)) { onPick(today) }
        }
    }
}

@Composable
private fun NavButton(glyph: String, onClick: () -> Unit) {
    val p = cp
    val (source, hovered) = rememberHover()
    Box(
        modifier = Modifier
            .size(28.dp)
            .clip(RoundedCornerShape(7.dp))
            .background(if (hovered) p.hover else Color.Transparent)
            .hoverable(source)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand),
        contentAlignment = Alignment.Center,
    ) { ZillitText(text = glyph, style = DmType.sans(15.sp, FontWeight.SemiBold), color = p.label) }
}

@Composable
private fun TextAction(text: String, onClick: () -> Unit) {
    val p = cp
    val (source, hovered) = rememberHover()
    ZillitText(
        text = text,
        style = DmType.sans(12.sp, FontWeight.SemiBold),
        color = p.amber,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (hovered) p.selected else Color.Transparent)
            .hoverable(source)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

@Composable
private fun DayCell(
    day: LocalDate,
    inMonth: Boolean,
    selected: Boolean,
    today: Boolean,
    enabled: Boolean,
    onPick: (LocalDate) -> Unit,
    modifier: Modifier,
) {
    val p = cp
    val (source, hovered) = rememberHover()
    Box(modifier.height(34.dp), contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(CircleShape)
                .background(
                    when {
                        selected -> p.amber
                        hovered && enabled -> p.hover
                        else -> Color.Transparent
                    },
                )
                .then(if (today && !selected) Modifier.border(1.dp, p.amber, CircleShape) else Modifier)
                .hoverable(source)
                .then(
                    if (enabled) {
                        Modifier
                            .clickable(interactionSource = source, indication = null) { onPick(day) }
                            .pointerHoverIcon(PointerIcon.Hand)
                    } else {
                        Modifier
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            ZillitText(
                text = day.day.toString(),
                style = DmType.sans(12.5.sp, if (selected || today) FontWeight.Bold else FontWeight.Medium),
                color = when {
                    selected -> Color.White
                    !enabled -> p.placeholder.copy(alpha = 0.6f)
                    inMonth -> p.ink
                    else -> p.placeholder
                },
            )
        }
    }
}

private fun firstOf(date: LocalDate): LocalDate = LocalDate(date.year, date.month.number, 1)

/** Six Monday-first weeks covering [month]. */
private fun weeksOf(month: LocalDate): List<List<LocalDate>> {
    val lead = month.dayOfWeek.ordinal
    val start = month.minus(lead, DateTimeUnit.DAY)
    return List(WEEKS) { week -> List(DAYS) { day -> start.plus(week * DAYS + day, DateTimeUnit.DAY) } }
}

private const val WEEKS = 6
private const val DAYS = 7
private val WEEKDAYS = listOf("M", "T", "W", "T", "F", "S", "S")
private val MONTH_NAMES = Month.entries.map { month -> month.name.lowercase().replaceFirstChar { it.uppercase() } }

/**
 * `CalcInput` with an empty value of `""`: a plain number commits as it is
 * typed, an expression resolves to 2dp when the field is left or Enter is
 * pressed, and a committed figure reads grouped to two places — a committed
 * zero reads as the placeholder.
 */
@Suppress("CyclomaticComplexMethod")
@Composable
internal fun MoneyInput(value: Double?, onCommit: (Double?) -> Unit, modifier: Modifier = Modifier) {
    val p = cp
    val focusManager = LocalFocusManager.current
    var draft by remember { mutableStateOf<String?>(null) }
    val editing = draft
    val expression = editing?.takeIf(DealCalc::isExpression)
    val shown = when {
        editing != null -> editing
        value == null || value == 0.0 -> ""
        else -> DealCalc.grouped(value)
    }
    fun commitExpression() {
        val text = draft ?: return
        if (DealCalc.isExpression(text)) DealCalc.evaluate(text)?.let { onCommit(DealCalc.round2(it)) }
        draft = null
    }
    Column(modifier) {
        FormInput(
            value = shown,
            onValueChange = { typed ->
                val next = typed.replace(",", "")
                if (!DealCalc.isAllowedInput(next)) return@FormInput
                draft = next
                if (!DealCalc.isExpression(next)) onCommit(DealCalc.plainValue(next))
            },
            placeholder = "0.00",
            visualTransformation = if (editing != null && expression == null) {
                GroupedDigits
            } else {
                VisualTransformation.None
            },
            onEnter = { focusManager.clearFocus() },
            onBlur = ::commitExpression,
        )
        if (expression != null) {
            val result = DealCalc.evaluate(expression)
            ZillitText(
                text = result?.let { "= ${DealCalc.grouped(it)}" } ?: "= —",
                style = DmType.mono(11.sp, FontWeight.Bold),
                color = if (result == null) p.error else p.teal,
                textAlign = TextAlign.End,
                modifier = Modifier.fillMaxWidth().padding(top = 3.dp),
            )
        }
    }
}

/** Thousands commas drawn into a typed number; the stored text keeps none. */
internal object GroupedDigits : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val raw = text.text
        val out = DealCalc.groupTyped(raw)
        // Map by counting the non-comma characters on each side.
        val mapping = object : OffsetMapping {
            override fun originalToTransformed(offset: Int): Int {
                var seen = 0
                var i = 0
                while (i < out.length && seen < offset) {
                    if (out[i] != ',') seen++
                    i++
                }
                return i
            }

            override fun transformedToOriginal(offset: Int): Int =
                out.take(offset.coerceIn(0, out.length)).count { it != ',' }.coerceAtMost(raw.length)
        }
        return TransformedText(AnnotatedString(out), mapping)
    }
}
