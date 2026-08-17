package com.zillit.desktop.feature.email.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.feature.email.domain.EditHistory
import com.zillit.desktop.feature.email.domain.MarkFamily
import com.zillit.desktop.feature.email.domain.RichText
import com.zillit.desktop.feature.email.domain.TextMark

/**
 * The message body, with formatting.
 *
 * ## How this holds together
 *
 * [RichText] is the document; this only renders it and reports edits. The field
 * itself is told about plain text and a selection, never about spans — Compose
 * is not asked to carry formatting across an edit, because how it does that is
 * undocumented and has changed between versions. Every mark movement happens in
 * [RichText.withText], which is testable without a UI toolkit.
 */
@Composable
@Suppress("LongMethod")
internal fun RichTextEditor(
    value: RichText,
    onValueChange: (RichText) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
) {
    val colors = ZillitTheme.colors

    // The selection is the field's business, not the document's, so it lives
    // here. Formatting acts on whatever it currently covers.
    var selection by remember { mutableStateOf(TextRangeState()) }
    var history by remember { mutableStateOf(EditHistory()) }

    // Every path that changes the document goes through here, so undo sees
    // toolbar formatting and typing alike.
    val change: (RichText) -> Unit = { updated ->
        if (updated != value) {
            history = history.record(value)
            onValueChange(updated)
        }
    }
    val undo = {
        history.undo(value)?.let { (rest, doc) ->
            history = rest
            onValueChange(doc)
        }
    }
    val redo = {
        history.redo(value)?.let { (rest, doc) ->
            history = rest
            onValueChange(doc)
        }
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
        FormatBar(
            state = FormatBarState(
                active = value.activeSwitches(selection),
                sizePx = value.pickedSize(selection),
                colorHex = value.pickedColor(selection),
                highlightHex = value.pickedHighlight(selection),
                canUndo = history.canUndo,
                canRedo = history.canRedo,
            ),
            onToggle = { style -> change(value.toggled(style, selection)) },
            onPick = { style -> change(value.set(style, selection.min, selection.max)) },
            onClear = { family -> change(value.clear(family, selection.min, selection.max)) },
            onUndo = { undo() },
            onRedo = { redo() },
        )

        BasicTextField(
            // Plain text in, styles via the visual transformation below.
            // Spans carried inside a TextFieldValue's AnnotatedString are
            // silently DROPPED by BasicTextField — the marks were always
            // stored and sent as HTML, but the editor drew everything plain,
            // which read as "the formatting buttons do nothing".
            value = TextFieldValue(
                text = value.text,
                selection = selection.toCompose(),
            ),
            visualTransformation = {
                TransformedText(value.annotated(), OffsetMapping.Identity)
            },
            onValueChange = { updated ->
                selection = TextRangeState(updated.selection.start, updated.selection.end)
                if (updated.text != value.text) change(value.withText(updated.text))
            },
            textStyle = ZillitTheme.typography.bodyMedium.copy(color = colors.textPrimary),
            cursorBrush = SolidColor(colors.accent),
            modifier = Modifier
                .fillMaxSize()
                .clip(ZillitTheme.shapes.medium)
                .background(colors.surface)
                .border(HAIRLINE, colors.border, ZillitTheme.shapes.medium)
                .padding(ZillitTheme.spacing.sm)
                // Cmd+B and friends, because nobody reaches for a toolbar
                // mid-sentence.
                .onPreviewKeyEvent { event ->
                    event.editorAction(
                        toggle = { style -> change(value.toggled(style, selection)) },
                        undo = { undo() },
                        redo = { redo() },
                    )
                },
            decorationBox = { field ->
                if (value.text.isEmpty()) {
                    ZillitText(
                        text = placeholder,
                        style = ZillitTheme.typography.bodyMedium,
                        color = colors.textMuted,
                    )
                }
                field()
            },
        )
    }
}

/** What the toolbar shows: current formatting at the selection, and history. */
internal data class FormatBarState(
    val active: Set<TextMark>,
    val sizePx: Int?,
    val colorHex: String?,
    val highlightHex: String?,
    val canUndo: Boolean,
    val canRedo: Boolean,
)

/** Where the caret or selection is. Held by the editor, not the document. */
internal data class TextRangeState(val start: Int = 0, val end: Int = 0)

private fun TextRangeState.toCompose() = androidx.compose.ui.text.TextRange(start, end)

internal val TextRangeState.min get() = minOf(start, end)
internal val TextRangeState.max get() = maxOf(start, end)

private fun RichText.activeSwitches(selection: TextRangeState): Set<TextMark> =
    SWITCH_MARKS.filterTo(mutableSetOf()) { isApplied(it, selection.min, selection.max) }

private fun RichText.toggled(style: TextMark, selection: TextRangeState): RichText =
    toggle(style, selection.min, selection.max)

private fun RichText.pickedSize(selection: TextRangeState): Int? =
    (valueOf(MarkFamily.FontSize, selection.min, selection.max) as? TextMark.FontSize)?.px

private fun RichText.pickedColor(selection: TextRangeState): String? =
    (valueOf(MarkFamily.TextColor, selection.min, selection.max) as? TextMark.TextColor)?.hex

private fun RichText.pickedHighlight(selection: TextRangeState): String? =
    (valueOf(MarkFamily.Highlight, selection.min, selection.max) as? TextMark.Highlight)?.hex

internal val SWITCH_MARKS =
    listOf(TextMark.Bold, TextMark.Italic, TextMark.Underline, TextMark.Strike)

/**
 * Renders the document's marks as Compose spans.
 *
 * Internal because it is the editor's whole render path: these spans reach
 * the screen only through the field's visual transformation — spans carried
 * inside a `TextFieldValue` are dropped silently — and the regression test
 * pins that this mapping exists and covers the marked ranges.
 *
 * Built per segment rather than per mark: two decorations on one range
 * (underline plus strikethrough) must be combined into one [TextDecoration],
 * because a later span's decoration *replaces* an earlier one instead of
 * stacking.
 */
internal fun RichText.annotated(): AnnotatedString {
    if (marks.isEmpty()) return AnnotatedString(text)

    val cuts = marks.flatMap { listOf(it.start, it.end) }
        .map { it.coerceIn(0, text.length) }
        .plus(listOf(0, text.length))
        .distinct()
        .sorted()

    val spans = cuts.zipWithNext().mapNotNull { (from, to) ->
        val active = marks.filter { it.start <= from && to <= it.end }.map { it.style }
        if (active.isEmpty()) null else AnnotatedString.Range(active.spanStyle(), from, to)
    }

    return AnnotatedString(text, spans)
}

@Suppress("CyclomaticComplexMethod")
private fun List<TextMark>.spanStyle(): SpanStyle {
    var span = SpanStyle()
    val decorations = mutableListOf<TextDecoration>()

    forEach { mark ->
        when (mark) {
            TextMark.Bold -> span = span.copy(fontWeight = FontWeight.Bold)
            TextMark.Italic -> span = span.copy(fontStyle = FontStyle.Italic)
            TextMark.Underline -> decorations += TextDecoration.Underline
            TextMark.Strike -> decorations += TextDecoration.LineThrough
            is TextMark.TextColor -> hexColor(mark.hex)?.let { span = span.copy(color = it) }
            is TextMark.Highlight -> hexColor(mark.hex)?.let { span = span.copy(background = it) }
            is TextMark.FontSize -> span = span.copy(fontSize = mark.px.sp)
        }
    }

    if (decorations.isNotEmpty()) {
        span = span.copy(textDecoration = TextDecoration.combine(decorations.distinct()))
    }
    return span
}

/** `#rrggbb` to a Compose colour; null for anything else. */
internal fun hexColor(hex: String): Color? {
    val digits = hex.removePrefix("#")
    if (digits.length != HEX_DIGITS) return null
    val value = digits.toLongOrNull(HEX_RADIX) ?: return null
    return Color(OPAQUE or value)
}

/**
 * Cmd+B / Ctrl+B and friends.
 *
 * Both modifiers rather than branching on the platform: this ships to macOS,
 * Windows and ChromeOS, and a Windows user pressing Ctrl+B expects bold
 * wherever the build was made.
 *
 * Only combinations this editor owns return true — Cmd+X must stay cut, which
 * is why strikethrough needs Shift.
 */
private fun KeyEvent.editorAction(
    toggle: (TextMark) -> Unit,
    undo: () -> Unit,
    redo: () -> Unit,
): Boolean {
    if (type != KeyEventType.KeyDown || !(isMetaPressed || isCtrlPressed)) return false

    when {
        key == Key.Z && isShiftPressed -> redo()
        key == Key.Z -> undo()
        key == Key.B -> toggle(TextMark.Bold)
        key == Key.I -> toggle(TextMark.Italic)
        key == Key.U -> toggle(TextMark.Underline)
        key == Key.X && isShiftPressed -> toggle(TextMark.Strike)
        else -> return false
    }
    return true
}

private val HAIRLINE = 1.dp
private const val HEX_DIGITS = 6
private const val HEX_RADIX = 16
private const val OPAQUE = 0xFF000000L
