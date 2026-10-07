@file:Suppress("LongMethod", "MaxLineLength", "TooManyFunctions", "LongParameterList", "MagicNumber", "CyclomaticComplexMethod", "ComplexCondition")
// Controls drawn to the web's StillKills.css, one composable each. A control's
// branches are its CSS modifiers — `.stk-btn.stk-primary:disabled` and the rest —
// and splitting them into helpers would scatter one rule across four functions.

package com.zillit.desktop.feature.selectstills.ui

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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTooltip
import com.zillit.desktop.feature.selectstills.domain.PillTone
import kotlinx.coroutines.delay

/**
 * The Select Stills palette — the web's `StillKills.css` custom properties.
 *
 * **Dark only.** The web sheet says so in its first line: this is the
 * stills-tagger's dark studio theme, there is no light palette and no toggle.
 * The tool therefore draws in its own colours whatever the app's theme is, so
 * a photographer sees the same tool on both clients.
 */
internal class StillsColors(
    val bg: Color,
    val panel: Color,
    val panel2: Color,
    val ink: Color,
    val muted: Color,
    val line: Color,
    val accent: Color,
    val accentInk: Color,
    val ok: Color,
    val warn: Color,
    /** The top bar, a shade below the page. */
    val bar: Color,
    /** Text on the accent (`#1b1405`), and the count beside it. */
    val onAccent: Color,
    val onAccentMuted: Color,
    val placeholder: Color,
    val lineHover: Color,
    val dashed: Color,
    val danger: Color,
    val dangerSolid: Color,
    val errorBg: Color,
    val errorFg: Color,
    val okBg: Color,
    val okFg: Color,
    val hintBg: Color,
    val hintLine: Color,
    val stageBg: Color,
)

private fun c(hex: Long) = Color(0xFF000000 or hex)

private val StillsDark = StillsColors(
    bg = c(0x131416),
    panel = c(0x1b1d20),
    panel2 = c(0x24272b),
    ink = c(0xeceae5),
    muted = c(0x9aa0a6),
    line = c(0x2e3237),
    accent = c(0xe8a33d),
    accentInk = c(0xf3c87e),
    ok = c(0x58b583),
    warn = c(0xe07856),
    bar = c(0x0e0f11),
    onAccent = c(0x1b1405),
    onAccentMuted = c(0x4a3509),
    placeholder = c(0x6b7076),
    lineHover = c(0x3c4147),
    dashed = c(0x3a3f45),
    danger = c(0xa03222),
    dangerSolid = c(0x9c3524),
    errorBg = c(0x3a201a),
    errorFg = c(0xffb3a0),
    okBg = c(0x1c3126),
    okFg = c(0xa5dcbb),
    hintBg = c(0x382c15),
    hintLine = c(0x574322),
    stageBg = c(0x0c0d0e),
)

private val LocalStillsColors = staticCompositionLocalOf { StillsDark }

internal object StillsTheme {
    val c: StillsColors @Composable get() = LocalStillsColors.current
}

/** Provides the palette. One palette: the tool is dark on a light app too. */
@Composable
internal fun StillsThemeProvider(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalStillsColors provides StillsDark, content = content)
}

internal val SemiBold = FontWeight.SemiBold
internal val Bold = FontWeight.Bold

internal fun stkText(size: Int, weight: FontWeight = FontWeight.Normal): TextStyle =
    TextStyle(fontSize = size.sp, fontWeight = weight, lineHeight = (size * 1.5f).sp)

/** A line of the tool's text. */
@Composable
internal fun SText(
    text: String,
    size: Int = 15,
    weight: FontWeight = FontWeight.Normal,
    color: Color = StillsTheme.c.ink,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
    underline: Boolean = false,
    italic: Boolean = false,
) {
    ZillitText(
        text = text,
        modifier = modifier,
        style = stkText(size, weight).copy(
            textDecoration = if (underline) TextDecoration.Underline else null,
            fontStyle = if (italic) androidx.compose.ui.text.font.FontStyle.Italic else null,
        ),
        color = color,
        maxLines = maxLines,
    )
}

/**
 * A line with one part in bold — `showing <b>Anna Bell</b> only`.
 *
 * The text is split and drawn as pieces, never set as markup, so a name is
 * always just text (the web's `Rich`).
 */
@Composable
internal fun SRich(
    text: String,
    size: Int = 14,
    color: Color = StillsTheme.c.muted,
    boldColor: Color = StillsTheme.c.accentInk,
    modifier: Modifier = Modifier,
) {
    // One Text with spans, not a Row of pieces: a Row cannot wrap mid-sentence,
    // and the enrolled-members tip is a full sentence that has to.
    val pieces = remember(text) { splitBold(text) }
    val annotated = remember(pieces, color, boldColor) {
        buildAnnotatedString {
            pieces.forEach { (part, bold) ->
                withStyle(SpanStyle(color = if (bold) boldColor else color, fontWeight = if (bold) Bold else FontWeight.Normal)) {
                    append(part)
                }
            }
        }
    }
    ZillitText(text = annotated, modifier = modifier, style = stkText(size), color = color)
}

/** `a <b>b</b> c` → [("a ", false), ("b", true), (" c", false)]. */
internal fun splitBold(text: String): List<Pair<String, Boolean>> {
    val out = mutableListOf<Pair<String, Boolean>>()
    var rest = text
    while (true) {
        val open = rest.indexOf("<b>")
        val close = rest.indexOf("</b>", startIndex = open + 1)
        if (open < 0 || close < 0) break
        if (open > 0) out += rest.substring(0, open) to false
        out += rest.substring(open + 3, close) to true
        rest = rest.substring(close + 4)
    }
    if (rest.isNotEmpty()) out += rest to false
    return out
}

// -- buttons --------------------------------------------------------------------------

/** The web's `.stk-btn` variants. */
internal enum class SBtnKind { Plain, Primary, Danger, Ghost, Link }

/**
 * `.stk-btn`: the plain panel button, the accent one, the red one, the quiet
 * one, and the underlined link.
 *
 * [on] draws the footer's pressed outline (`.stk-btn.stk-on`), so a decision
 * that is already taken is visible without a second control.
 */
@Composable
internal fun SBtn(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: SBtnKind = SBtnKind.Plain,
    small: Boolean = false,
    enabled: Boolean = true,
    on: Boolean = false,
    tooltip: String? = null,
) {
    val k = StillsTheme.c
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val shape = RoundedCornerShape(8.dp)

    val background = when (kind) {
        SBtnKind.Primary -> k.accent
        SBtnKind.Danger -> k.danger
        SBtnKind.Plain -> k.panel2
        SBtnKind.Ghost, SBtnKind.Link -> Color.Transparent
    }
    val foreground = when (kind) {
        SBtnKind.Primary -> k.onAccent
        SBtnKind.Danger -> Color.White
        SBtnKind.Plain -> k.ink
        SBtnKind.Ghost -> if (hovered) k.ink else k.muted
        SBtnKind.Link -> k.accentInk
    }
    val weight = if (kind == SBtnKind.Primary || kind == SBtnKind.Danger) Bold else FontWeight.Normal
    val bordered = kind == SBtnKind.Plain || kind == SBtnKind.Ghost
    val border = when {
        on -> foreground
        kind == SBtnKind.Primary -> k.accent
        kind == SBtnKind.Danger -> k.danger
        hovered -> k.lineHover
        else -> k.line
    }

    val body: @Composable () -> Unit = {
        Row(
            Modifier
                .then(if (kind == SBtnKind.Link) Modifier else Modifier.height(if (small) 28.dp else 40.dp))
                .clip(shape)
                .background(background)
                .then(if (bordered || on || kind == SBtnKind.Primary || kind == SBtnKind.Danger) Modifier.border(BorderStroke(if (on) 2.dp else 1.dp, border), shape) else Modifier)
                .then(if (enabled) Modifier.hoverable(source).clickable(interactionSource = source, indication = null, onClick = onClick) else Modifier.alpha(DISABLED))
                .padding(horizontal = if (kind == SBtnKind.Link) 0.dp else if (small) 12.dp else 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            SText(text, if (small) 13 else 15, weight, foreground, maxLines = 1, underline = kind == SBtnKind.Link)
        }
    }
    // The caller's modifier sits OUTSIDE the tooltip: `weight` and `align` are
    // parent-scope modifiers, and a tooltip layer between the parent and the
    // button would swallow them.
    Box(modifier) {
        if (tooltip.isNullOrBlank()) body() else ZillitTooltip(text = tooltip) { body() }
    }
}

/**
 * A button that asks before it acts: the first click turns it into
 * "Click again to …" for a few seconds, the second one does it. Used where a
 * whole dialog would be too much (delete a photo).
 */
@Composable
internal fun SConfirmBtn(
    label: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    small: Boolean = true,
) {
    var armed by remember { mutableStateOf(false) }
    if (armed) {
        // Letting go after a few seconds, so a half-pressed delete does not sit
        // armed on screen until somebody brushes it.
        DisposableEffect(Unit) { onDispose { } }
        LaunchedDisarm { armed = false }
    }
    SBtn(
        text = if (armed) confirmLabel else label,
        onClick = {
            if (armed) {
                armed = false
                onConfirm()
            } else {
                armed = true
            }
        },
        modifier = modifier,
        kind = if (armed) SBtnKind.Danger else SBtnKind.Plain,
        small = small,
        enabled = enabled,
    )
}

@Composable
private fun LaunchedDisarm(onTimeout: () -> Unit) {
    androidx.compose.runtime.LaunchedEffect(Unit) {
        delay(ARM_MS)
        onTimeout()
    }
}

// -- chips and tabs -------------------------------------------------------------------

/** A chip's colouring: the web's `.stk-chip` modifiers. */
internal enum class SChipTone { Plain, Ok, Bad, Warn, Quiet, Solid }

/**
 * `.stk-chip` — a quiet refinement, not another set of tabs. A chip whose
 * count is 0 is dimmed: it still answers the question ("none of those")
 * without looking like a way to photos that are not there.
 */
@Composable
internal fun SChip(
    label: String,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tone: SChipTone = SChipTone.Plain,
    count: Int? = null,
    hint: String? = null,
    enabled: Boolean = true,
) {
    val k = StillsTheme.c
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val shape = RoundedCornerShape(999.dp)

    val activeFg = when (tone) {
        SChipTone.Ok -> c(0xa6e3c2)
        SChipTone.Bad -> k.errorFg
        else -> k.accentInk
    }
    val activeBorder = when (tone) {
        SChipTone.Ok -> k.ok
        SChipTone.Bad -> k.warn
        else -> k.accent
    }
    val activeBg = when (tone) {
        SChipTone.Ok -> k.ok.copy(alpha = 0.16f)
        SChipTone.Bad -> k.warn.copy(alpha = 0.16f)
        else -> k.accent.copy(alpha = 0.12f)
    }
    val solid = tone == SChipTone.Solid
    val foreground = when {
        solid -> Color.White
        active -> activeFg
        hovered -> k.ink
        else -> k.muted
    }

    val body: @Composable () -> Unit = {
        Row(
            modifier
                .heightIn(min = 26.dp)
                .clip(shape)
                .background(if (solid) k.dangerSolid else if (active) activeBg else Color.Transparent)
                .border(BorderStroke(1.dp, if (solid) k.dangerSolid else if (active) activeBorder else if (hovered) k.lineHover else k.line), shape)
                .then(if (enabled) Modifier.hoverable(source).clickable(interactionSource = source, indication = null, onClick = onClick) else Modifier.alpha(0.4f))
                .then(if (count == 0 && !active) Modifier.alpha(0.45f) else Modifier)
                .padding(horizontal = 11.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            SText(label, 13, SemiBold, foreground, maxLines = 1)
            if (count != null) SCount(count, active, small = true)
        }
    }
    if (hint.isNullOrBlank()) body() else ZillitTooltip(text = hint) { body() }
}

/** `.stk-tab` — the one-click filter tabs above a grid, each with its count. */
@Composable
internal fun STab(
    label: String,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    count: Int? = null,
    hint: String? = null,
) {
    val k = StillsTheme.c
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val shape = RoundedCornerShape(999.dp)
    val body: @Composable () -> Unit = {
        Row(
            modifier
                .clip(shape)
                .background(if (active) k.accent else k.panel)
                .border(BorderStroke(1.dp, if (active) k.accent else if (hovered) k.lineHover else k.line), shape)
                .hoverable(source)
                .clickable(interactionSource = source, indication = null, onClick = onClick)
                .padding(horizontal = 18.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            SText(label, 15, SemiBold, if (active) k.onAccent else if (hovered) k.ink else k.muted, maxLines = 1)
            if (count != null) SCount(count, active, small = false)
        }
    }
    if (hint.isNullOrBlank()) body() else ZillitTooltip(text = hint) { body() }
}

/** The small number pill inside a tab or a chip. */
@Composable
private fun SCount(count: Int, active: Boolean, small: Boolean) {
    val k = StillsTheme.c
    SText(
        text = count.toString(),
        size = if (small) 11 else 12,
        weight = Bold,
        color = if (active) k.onAccent else k.ink,
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(if (active) Color.Black.copy(alpha = 0.16f) else Color.White.copy(alpha = 0.07f))
            .padding(horizontal = 6.dp, vertical = 1.dp),
        maxLines = 1,
    )
}

// -- pills ----------------------------------------------------------------------------

/** `.stk-pill` variants. */
internal enum class SPillKind { Member, Unknown, Working, Failed, Ghost, GateOk, GateWait, GateNo, Yours }

/** `.stk-pill` — what a tile and a lightbox head wear. */
@Composable
internal fun SPill(
    text: String,
    kind: SPillKind = SPillKind.Ghost,
    modifier: Modifier = Modifier,
    hint: String? = null,
) {
    val k = StillsTheme.c
    val (background, foreground) = when (kind) {
        SPillKind.Member -> k.accent.copy(alpha = 0.92f) to c(0x221503)
        SPillKind.Unknown -> c(0x282a2e).copy(alpha = 0.85f) to c(0xc3c7cc)
        SPillKind.Working -> c(0x233542).copy(alpha = 0.85f) to c(0x9fcde8)
        SPillKind.Failed -> c(0x5a1e14).copy(alpha = 0.9f) to k.errorFg
        SPillKind.Ghost -> Color.Black.copy(alpha = 0.65f) to c(0xbbbbbb)
        SPillKind.GateOk -> k.ok.copy(alpha = 0.92f) to c(0x0a2316)
        SPillKind.GateWait -> c(0xe0a856).copy(alpha = 0.9f) to c(0x2a1a04)
        SPillKind.GateNo -> c(0xbe4a3a).copy(alpha = 0.92f) to c(0xfff0ec)
        SPillKind.Yours -> c(0x3cc878).copy(alpha = 0.16f) to c(0xa6e3c2)
    }
    val dashed = kind == SPillKind.Unknown
    val shape = RoundedCornerShape(999.dp)
    val body: @Composable () -> Unit = {
        Box(
            modifier
                .clip(shape)
                .background(background)
                .then(if (dashed) Modifier.border(BorderStroke(1.dp, c(0x5a5f66)), shape) else Modifier)
                .padding(horizontal = if (kind == SPillKind.Yours) 7.dp else 10.dp, vertical = 2.dp),
        ) {
            SText(text, if (kind == SPillKind.Yours) 10 else 12, SemiBold, foreground, maxLines = 1)
        }
    }
    if (hint.isNullOrBlank()) body() else ZillitTooltip(text = hint) { body() }
}

/** The pill a photo's publication state wears. */
@Composable
internal fun SStatePill(tone: PillTone, text: String, hint: String?) {
    SPill(
        text = text,
        kind = when (tone) {
            PillTone.Ok -> SPillKind.GateOk
            PillTone.Warn -> SPillKind.GateWait
            PillTone.Bad -> SPillKind.GateNo
        },
        hint = hint,
    )
}

// -- text input -----------------------------------------------------------------------

/**
 * `.stk input[type=text]` — a panel-2 box with the accent rim on focus.
 *
 * [lines] over one makes it the review footer's reason box (`.stk-rl-note
 * textarea`), which is the only multi-line field in the tool.
 */
@Composable
internal fun SInput(
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    enabled: Boolean = true,
    maxLength: Int? = null,
    lines: Int = 1,
    size: Int = 15,
    background: Color? = null,
    focusBorder: Color? = null,
    onEnter: (() -> Unit)? = null,
    /**
     * Keys this field answers itself. True consumes the key, so whatever is
     * underneath — the lightbox's arrows, K and D — never hears it.
     */
    onKeys: ((KeyEvent) -> Boolean)? = null,
) {
    val k = StillsTheme.c
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(8.dp)
    val style = stkText(size)
    BasicTextField(
        value = value,
        onValueChange = { next -> if (maxLength == null || next.length <= maxLength) onChange(next) },
        modifier = modifier
            .onFocusChanged { focused = it.isFocused }
            .then(
                if (onKeys == null) {
                    Modifier
                } else {
                    Modifier.onPreviewKeyEvent { event -> event.type == KeyEventType.KeyDown && onKeys(event) }
                },
            ),
        enabled = enabled,
        singleLine = lines <= 1,
        maxLines = lines,
        textStyle = style.copy(color = k.ink),
        cursorBrush = SolidColor(k.accent),
        keyboardOptions = KeyboardOptions(imeAction = if (onEnter != null) ImeAction.Done else ImeAction.Default),
        keyboardActions = KeyboardActions(onDone = { onEnter?.invoke() }),
        decorationBox = { inner ->
            Box(
                Modifier
                    .fillMaxWidth()
                    .then(if (lines > 1) Modifier.heightIn(min = (size * 1.5f * lines + 18).dp) else Modifier.height(38.dp))
                    .clip(shape)
                    .background(background ?: k.panel2)
                    .border(BorderStroke(if (focused) 2.dp else 1.dp, if (focused) (focusBorder ?: k.accent) else k.line), shape)
                    .padding(horizontal = 11.dp, vertical = if (lines > 1) 8.dp else 0.dp),
                contentAlignment = if (lines > 1) Alignment.TopStart else Alignment.CenterStart,
            ) {
                if (value.isEmpty()) SText(placeholder, size, color = k.placeholder, maxLines = 1)
                inner()
            }
        },
    )
}

/** `.stk-mp-search.stk-wide` — the inline search over a list. */
@Composable
internal fun SSearch(value: String, onChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier) {
    val k = StillsTheme.c
    SInput(value, onChange, modifier.fillMaxWidth(), placeholder = placeholder, size = 14, background = k.panel2)
}

// -- layout pieces --------------------------------------------------------------------

/** `.stk-card`. */
@Composable
internal fun SCard(modifier: Modifier = Modifier, content: @Composable ColumnScopeAlias.() -> Unit) {
    val k = StillsTheme.c
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier.clip(shape).background(k.panel).border(BorderStroke(1.dp, k.line), shape).padding(20.dp),
        content = content,
    )
}

internal typealias ColumnScopeAlias = androidx.compose.foundation.layout.ColumnScope

/** `.stk-field` — a label, the control, and the lines under it. */
@Composable
internal fun SField(
    label: String?,
    modifier: Modifier = Modifier,
    hint: String? = null,
    warn: String? = null,
    required: Boolean = false,
    content: @Composable () -> Unit,
) {
    val k = StillsTheme.c
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (!label.isNullOrBlank()) {
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                SText(label, 13, color = k.muted)
                if (required) SText("*", 13, Bold, k.warn)
            }
        }
        content()
        if (!hint.isNullOrBlank()) SText(hint, 13, color = k.muted)
        if (!warn.isNullOrBlank()) SText(warn, 13, color = k.accentInk)
    }
}

/** `.stk-hintline` — the small grey line under a control. */
@Composable
internal fun SHint(text: String, modifier: Modifier = Modifier, warn: Boolean = false) {
    SText(text, 12, color = if (warn) StillsTheme.c.accentInk else StillsTheme.c.muted, modifier = modifier)
}

/** `.stk-alert` — error, ok, hint, or plain. */
internal enum class SAlertTone { Error, Ok, Hint, Plain }

@Composable
internal fun SAlert(
    tone: SAlertTone = SAlertTone.Hint,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScopeAlias.() -> Unit,
) {
    val k = StillsTheme.c
    val shape = RoundedCornerShape(8.dp)
    val background = when (tone) {
        SAlertTone.Error -> k.errorBg
        SAlertTone.Ok -> k.okBg
        SAlertTone.Hint -> k.hintBg
        SAlertTone.Plain -> Color.Transparent
    }
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(background)
            .then(if (tone == SAlertTone.Hint) Modifier.border(BorderStroke(1.dp, k.hintLine), shape) else Modifier)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

/** The text colour an alert's words take. */
@Composable
internal fun alertInk(tone: SAlertTone): Color {
    val k = StillsTheme.c
    return when (tone) {
        SAlertTone.Error -> k.errorFg
        SAlertTone.Ok -> k.okFg
        SAlertTone.Hint -> k.accentInk
        SAlertTone.Plain -> k.ink
    }
}

/** `.stk-check` — one of two, or a consent box. A choice that must be made, not left empty. */
@Composable
internal fun SCheck(
    checked: Boolean,
    label: String,
    onPick: () -> Unit,
    modifier: Modifier = Modifier,
    hint: String? = null,
    enabled: Boolean = true,
    radio: Boolean = true,
) {
    val k = StillsTheme.c
    Row(
        modifier
            .fillMaxWidth()
            .then(if (enabled) Modifier.clickable(onClick = onPick) else Modifier.alpha(DISABLED))
            .padding(vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier
                .padding(top = 4.dp)
                .size(16.dp)
                .clip(if (radio) CircleShape else RoundedCornerShape(4.dp))
                .background(if (checked) k.accent else Color.Transparent)
                .border(BorderStroke(if (checked) 0.dp else 1.5.dp, k.line), if (radio) CircleShape else RoundedCornerShape(4.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) {
                Box(
                    Modifier
                        .size(if (radio) 6.dp else 8.dp)
                        .clip(if (radio) CircleShape else RoundedCornerShape(1.dp))
                        .background(k.onAccent),
                )
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            SText(label, 15, color = k.ink)
            if (!hint.isNullOrBlank()) SHint(hint)
        }
    }
}

/** `.stk-progress` — the thin bar over the upload queue. */
@Composable
internal fun SProgress(percent: Int, modifier: Modifier = Modifier, height: Dp = 6.dp) {
    val k = StillsTheme.c
    Box(modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(3.dp)).background(k.panel2)) {
        Box(Modifier.fillMaxWidth(percent.coerceIn(0, 100) / 100f).height(height).background(k.accent))
    }
}

/** A dot: the top bar's "up to date" / "processing" light. */
@Composable
internal fun SDot(colour: Color, size: Dp = 8.dp) {
    Box(Modifier.size(size).clip(CircleShape).background(colour))
}

internal const val DISABLED = 0.45f
private const val ARM_MS = 4000L
