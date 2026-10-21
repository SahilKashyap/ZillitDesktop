package com.zillit.desktop.feature.email.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.text.font.FontFamily
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
import com.zillit.desktop.feature.email.domain.BlockKind
import com.zillit.desktop.feature.email.domain.EditHistory
import com.zillit.desktop.feature.email.domain.LineSpan
import com.zillit.desktop.feature.email.domain.LineStyle
import com.zillit.desktop.feature.email.domain.MarkFamily
import com.zillit.desktop.feature.email.domain.RichText
import com.zillit.desktop.feature.email.domain.TextMark
import com.zillit.desktop.feature.email.domain.indentBy
import com.zillit.desktop.feature.email.domain.lineStyles
import com.zillit.desktop.feature.email.domain.lines
import com.zillit.desktop.feature.email.domain.orderedNumbers
import com.zillit.desktop.feature.email.domain.selectedLineStyle
import com.zillit.desktop.feature.email.domain.toggleList

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
 *
 * Bullets, numbers and indents are not in the document's text either: they are
 * drawn by the visual transformation and mapped out of the caret's way by
 * [listMapping], so backspace at the start of a list item deletes the user's
 * own first character rather than half a bullet.
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
    // Inserted text takes the caret with it, or the next keystroke lands
    // behind the emoji that was just dropped in.
    val insert: (String, Set<TextMark>) -> Unit = { inserted, styles ->
        change(value.replacingSelection(selection, inserted, styles))
        val caret = selection.min + inserted.length
        selection = TextRangeState(caret, caret)
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
        FormatBar(
            state = FormatBarState(
                active = value.activeSwitches(selection),
                fontFamily = value.pickedFamily(selection),
                sizePx = value.pickedSize(selection),
                colorHex = value.pickedColor(selection),
                highlightHex = value.pickedHighlight(selection),
                lineStyle = value.selectedLineStyle(selection.min, selection.max),
                canUndo = history.canUndo,
                canRedo = history.canRedo,
            ),
            onToggle = { style -> change(value.toggled(style, selection)) },
            onPick = { style -> change(value.set(style, selection.min, selection.max)) },
            onClear = { family -> change(value.clear(family, selection.min, selection.max)) },
            onUndo = { undo() },
            onRedo = { redo() },
            onToggleList = { kind -> change(value.toggleList(kind, selection.min, selection.max)) },
            onIndent = { delta -> change(value.indentBy(delta, selection.min, selection.max)) },
            onInsertLink = { label, href -> insert(label, setOf(TextMark.Link(href))) },
            onInsertText = { inserted -> insert(inserted, value.stylesAt(selection.min)) },
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
            visualTransformation = { value.transformed() },
            onValueChange = { updated ->
                selection = TextRangeState(updated.selection.start, updated.selection.end)
                if (updated.text != value.text) change(value.withText(updated.text))
            },
            textStyle = ZillitTheme.typography.bodyMedium.copy(color = colors.textPrimary),
            cursorBrush = SolidColor(colors.accent),
            modifier = Modifier
                // A floor rather than the whole pane: the composer scrolls,
                // so the field grows with the message and a click anywhere in
                // its first screenful lands the caret.
                .fillMaxWidth()
                .defaultMinSize(minHeight = FIELD_MIN_HEIGHT)
                .clip(ZillitTheme.shapes.medium)
                .background(colors.surface)
                .border(HAIRLINE, colors.border, ZillitTheme.shapes.medium)
                .padding(ZillitTheme.spacing.sm)
                // Cmd+B and friends, because nobody reaches for a toolbar
                // mid-sentence.
                .onPreviewKeyEvent { event ->
                    event.editorAction(
                        toggle = { style -> change(value.toggled(style, selection)) },
                        list = { kind -> change(value.toggleList(kind, selection.min, selection.max)) },
                        indent = { delta -> change(value.indentBy(delta, selection.min, selection.max)) },
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
    val fontFamily: String?,
    val sizePx: Int?,
    val colorHex: String?,
    val highlightHex: String?,
    val lineStyle: LineStyle?,
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

private fun RichText.pickedFamily(selection: TextRangeState): String? =
    (valueOf(MarkFamily.FontFamily, selection.min, selection.max) as? TextMark.FontFamily)?.name

private fun RichText.pickedColor(selection: TextRangeState): String? =
    (valueOf(MarkFamily.TextColor, selection.min, selection.max) as? TextMark.TextColor)?.hex

private fun RichText.pickedHighlight(selection: TextRangeState): String? =
    (valueOf(MarkFamily.Highlight, selection.min, selection.max) as? TextMark.Highlight)?.hex

/** Replaces the selection with [inserted], wearing [styles]. */
private fun RichText.replacingSelection(
    selection: TextRangeState,
    inserted: String,
    styles: Set<TextMark>,
): RichText {
    val start = selection.min
    val updated = withText(text.substring(0, start) + inserted + text.substring(selection.max))
    return styles.fold(updated) { doc, style -> doc.set(style, start, start + inserted.length) }
}

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

/**
 * The document as the field should draw it: markers and indents in front of
 * each line, with the mapping that keeps the caret out of them.
 *
 * Prefixes are not stored in [RichText.text] — a bullet the user can select,
 * delete half of, and send in the body is not a list, it is a typo. Drawing
 * them here keeps the document exactly what was typed, and leaves the HTML
 * writer free to emit real `<li>`s.
 */
internal fun RichText.transformed(): TransformedText {
    val prefixes = linePrefixes()
    if (prefixes.all { it.isEmpty() }) return TransformedText(annotated(), OffsetMapping.Identity)

    val spans = lines()
    val styled = annotated()
    val builder = AnnotatedString.Builder()

    spans.forEachIndexed { index, line ->
        if (index > 0) builder.append('\n')
        builder.append(prefixes[index])
        builder.append(styled.subSequence(line.start, line.end))
    }

    return TransformedText(builder.toAnnotatedString(), listMapping(spans, prefixes))
}

/** What is drawn in front of each line: its indent, then its marker if it has one. */
internal fun RichText.linePrefixes(): List<String> {
    val styles = lineStyles()
    val numbers = styles.orderedNumbers()

    return styles.mapIndexed { index, style ->
        val pad = INDENT_PAD.repeat(style.indent)
        when (style.kind) {
            BlockKind.Bullet -> pad + BULLET
            BlockKind.Ordered -> "$pad${numbers[index]}.  "
            null -> pad
        }
    }
}

/**
 * Moves offsets across the drawn prefixes.
 *
 * Every position in line *i* shifts by the total length of the prefixes up to
 * and including that line, which puts the start of a line's text after its own
 * bullet. Coming back, an offset landing inside a prefix is clamped to the
 * start of that line's text, so the caret can never sit between a number and
 * its dot.
 */
private fun listMapping(spans: List<LineSpan>, prefixes: List<String>): OffsetMapping {
    val cumulative = IntArray(spans.size)
    var running = 0
    spans.indices.forEach { index ->
        running += prefixes[index].length
        cumulative[index] = running
    }

    return object : OffsetMapping {
        override fun originalToTransformed(offset: Int): Int {
            val line = spans.indexOfLast { it.start <= offset }.coerceAtLeast(0)
            return offset + cumulative[line]
        }

        override fun transformedToOriginal(offset: Int): Int {
            val line = spans.indices.lastOrNull { index ->
                spans[index].start + cumulative[index] - prefixes[index].length <= offset
            } ?: 0
            return (offset - cumulative[line]).coerceIn(spans[line].start, spans[line].end)
        }
    }
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
            is TextMark.FontFamily -> span = span.copy(fontFamily = mark.name.fontFace())
            // A link is blue and underlined wherever mail is read; an editor
            // that showed it any other way would be the one lying.
            is TextMark.Link -> {
                decorations += TextDecoration.Underline
                span = span.copy(color = LINK_BLUE)
            }
        }
    }

    if (decorations.isNotEmpty()) {
        span = span.copy(textDecoration = TextDecoration.combine(decorations.distinct()))
    }
    return span
}

/**
 * A CSS family name as a Compose font.
 *
 * Only the generic families can be satisfied from Compose's own bundle, so a
 * named face is drawn in the nearest generic. The mark itself survives to the
 * wire either way, where the recipient's client has the real font — the
 * alternative, refusing to store a family this desktop cannot draw, would
 * silently drop formatting the web can.
 */
private fun String.fontFace(): FontFamily = when {
    equals("serif", ignoreCase = true) || this in SERIF_FACES -> FontFamily.Serif
    equals("monospace", ignoreCase = true) || this in MONO_FACES -> FontFamily.Monospace
    equals("cursive", ignoreCase = true) || this in SCRIPT_FACES -> FontFamily.Cursive
    else -> FontFamily.SansSerif
}

private val SERIF_FACES = setOf(
    "Baskerville", "Big Caslon", "Bodoni MT", "Book Antiqua", "Bookman", "Calisto MT", "Cambria",
    "Century", "Century Schoolbook", "Didot", "Garamond", "Georgia", "Goudy Old Style",
    "Hoefler Text", "Lucida Bright", "Palatino", "Palatino Linotype", "Perpetua",
    "Perpetua Titling MT", "Rockwell", "Rockwell Extra Bold", "Times", "Times New Roman",
)
private val MONO_FACES = setOf("Consolas", "Courier", "Courier New", "Lucida Console", "Monaco")
private val SCRIPT_FACES = setOf("Bradley Hand", "Brush Script MT", "Chalkduster")

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
 * is why strikethrough needs Shift. The list pair is the web composer's own
 * (Mod-Shift-7 and Mod-Shift-8), so one set of fingers works in both clients.
 */
@Suppress("CyclomaticComplexMethod")
private fun KeyEvent.editorAction(
    toggle: (TextMark) -> Unit,
    list: (BlockKind) -> Unit,
    indent: (Int) -> Unit,
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
        key == Key.Seven && isShiftPressed -> list(BlockKind.Ordered)
        key == Key.Eight && isShiftPressed -> list(BlockKind.Bullet)
        key == Key.RightBracket -> indent(1)
        key == Key.LeftBracket -> indent(-1)
        else -> return false
    }
    return true
}

private val HAIRLINE = 1.dp
private const val HEX_DIGITS = 6
private const val HEX_RADIX = 16
private const val OPAQUE = 0xFF000000L

/** Four spaces to a step, which reads at about the depth the web's 3em renders. */
private const val INDENT_PAD = "    "
private const val BULLET = "•  "

private val LINK_BLUE = Color(0xFF1A73E8)

/** The editor's floor: a message pane that starts at a few lines reads as a form field, not a page. */
private val FIELD_MIN_HEIGHT = 220.dp
