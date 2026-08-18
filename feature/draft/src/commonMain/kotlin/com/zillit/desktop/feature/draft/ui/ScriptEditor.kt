package com.zillit.desktop.feature.draft.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
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
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.feature.draft.domain.ElementType
import com.zillit.desktop.feature.draft.domain.ScreenplayLayout
import com.zillit.desktop.feature.draft.domain.ScriptElement

/**
 * The page. Each element is its own text field laid out where the format
 * puts it — headings and action at the left margin, cues in the middle,
 * dialogue in the middle third — in a monospace face on a paper-coloured
 * column, so what is typed reads like the script it will print as.
 *
 * Keys are the writer's: Enter finishes the element, Tab moves sideways
 * (or accepts SmartType), Backspace at the start joins upwards, ⌘/Ctrl+1–8
 * sets the type, ↑/↓ at the first/last line cross into the neighbour.
 */
@Composable
fun ScriptEditor(open: OpenScript, onEvent: (DraftEvent) -> Unit, modifier: Modifier = Modifier) {
    val colors = ZillitTheme.colors
    val listState = rememberLazyListState()
    val pages = open.pagination.pageOfElement

    // Bring the caret's element into view when the view model moves it.
    LaunchedEffect(open.caret?.nonce) {
        val caret = open.caret ?: return@LaunchedEffect
        val index = open.elements.indexOfFirst { it.id == caret.elementId }
        if (index < 0) return@LaunchedEffect
        val visible = listState.layoutInfo.visibleItemsInfo
        if (visible.none { it.index == index }) listState.animateScrollToItem(index)
    }

    Box(modifier.fillMaxSize().background(colors.surfaceSunken), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            state = listState,
            modifier = Modifier.width(PAPER_WIDTH).fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = ZillitTheme.spacing.lg),
        ) {
            itemsIndexed(open.elements, key = { _, e -> e.id }) { index, element ->
                if (index > 0 && pages.getOrNull(index) != pages.getOrNull(index - 1)) {
                    PageBreak(pages[index])
                }
                Box(Modifier.fillMaxWidth().background(colors.surface)) {
                    ElementRow(
                        element = element,
                        sceneNumber = open.screenplay.scenes.indexOfFirst { it.value.id == element.id }
                            .takeIf { it >= 0 }?.plus(1),
                        focused = element.id == open.focusedId,
                        caret = open.caret?.takeIf { it.elementId == element.id },
                        spaceBefore = ScreenplayLayout.spaceBefore(open.elements.getOrNull(index - 1)?.type,
                            element.type),
                        suggestions = if (element.id == open.focusedId) open.suggestions else emptyList(),
                        onEvent = onEvent,
                    )
                }
            }
            item { Box(Modifier.fillMaxWidth().height(PAPER_TAIL).background(colors.surface)) }
        }
    }
}

@Composable
private fun PageBreak(page: Int) {
    val colors = ZillitTheme.colors
    Row(
        Modifier.fillMaxWidth().background(colors.surface).padding(vertical = ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f).height(1.dp).background(colors.border))
        ZillitText(
            text = "  page $page  ",
            style = ZillitTheme.typography.labelSmall,
            color = colors.textMuted,
        )
        Box(Modifier.weight(1f).height(1.dp).background(colors.border))
    }
}

/**
 * One element: gutter (scene number, or the type while focused), the field
 * at its indent and width, and SmartType under it.
 */
@Composable
@Suppress("LongParameterList", "LongMethod",
    "CyclomaticComplexMethod") // The row's layout and key contract, in one place.
private fun ElementRow(
    element: ScriptElement,
    sceneNumber: Int?,
    focused: Boolean,
    caret: CaretRequest?,
    spaceBefore: Int,
    suggestions: List<String>,
    onEvent: (DraftEvent) -> Unit,
) {
    val colors = ZillitTheme.colors
    val focusRequester = remember { FocusRequester() }
    var value by remember(element.id) { mutableStateOf(TextFieldValue(element.text, TextRange(element.text.length))) }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }

    // The view model's copy wins (uppercasing, accepted suggestions), the
    // caret staying put where it can.
    LaunchedEffect(element.text) {
        if (value.text != element.text) {
            val at = value.selection.start.coerceAtMost(element.text.length)
            value = TextFieldValue(element.text, TextRange(at))
        }
    }
    LaunchedEffect(caret?.nonce) {
        val request = caret ?: return@LaunchedEffect
        focusRequester.requestFocus()
        value = value.copy(selection = TextRange(request.offset.coerceIn(0, value.text.length)))
    }

    val textStyle = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = FONT_SIZE,
        lineHeight = LINE_HEIGHT,
        color = colors.textPrimary,
        textAlign = if (element.type == ElementType.Transition) TextAlign.End else TextAlign.Start,
    )
    val indent = ((ScreenplayLayout.leftInches(element.type) - LEFT_MARGIN_INCHES) * INCH.value).dp
    val width = (ScreenplayLayout.widthChars(element.type) * CHAR.value).dp

    Column(Modifier.fillMaxWidth().padding(top = if (spaceBefore > 0) LINE_HEIGHT_DP else 0.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            // Gutter: the scene number, or what this element is while it has focus.
            Box(Modifier.width(GUTTER).padding(top = 2.dp), contentAlignment = Alignment.TopEnd) {
                val label = when {
                    sceneNumber != null -> sceneNumber.toString()
                    focused -> element.type.label.uppercase().take(GUTTER_LABEL_CHARS)
                    else -> ""
                }
                ZillitText(
                    text = label,
                    style = ZillitTheme.typography.labelSmall,
                    color = if (sceneNumber != null) colors.textSecondary else colors.textMuted,
                    modifier = Modifier.padding(end = ZillitTheme.spacing.sm),
                )
            }
            Box(Modifier.padding(start = indent).width(width)) {
                BasicTextField(
                    value = value,
                    onValueChange = { next ->
                        value = next
                        if (next.text != element.text) onEvent(DraftEvent.TextChanged(element.id, next.text))
                    },
                    textStyle = textStyle,
                    cursorBrush = SolidColor(colors.accent),
                    onTextLayout = { layout = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(if (focused) colors.surfaceSelected else colors.surface)
                        .focusRequester(focusRequester)
                        .onFocusChanged { if (it.isFocused) onEvent(DraftEvent.Focus(element.id)) }
                        .onPreviewKeyEvent { event -> handleKey(event, element, value, layout, onEvent) },
                    decorationBox = { inner ->
                        Box {
                            if (value.text.isEmpty()) {
                                ZillitText(
                                    text = placeholder(element.type),
                                    style = textStyle.copy(color = colors.textMuted),
                                )
                            }
                            inner()
                        }
                    },
                )
            }
        }
        if (focused && suggestions.isNotEmpty()) {
            Row(
                Modifier.padding(start = GUTTER + indent, top = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                suggestions.forEach { suggestion ->
                    Box(
                        Modifier
                            .background(colors.accentSoft, ZillitTheme.shapes.pill)
                            .clickable { onEvent(DraftEvent.Accept(element.id, suggestion)) }
                            .padding(horizontal = ZillitTheme.spacing.sm, vertical = 2.dp),
                    ) {
                        ZillitText(text = suggestion, style = ZillitTheme.typography.labelSmall,
                            color = colors.accentText)
                    }
                }
                ZillitText(text = "Tab accepts", style = ZillitTheme.typography.labelSmall, color = colors.textMuted)
            }
        }
    }
}

/** The editor's key contract; true when the key was ours. */
@Suppress("ReturnCount", "CyclomaticComplexMethod") // One decision per key; a table would read worse.
private fun handleKey(
    event: KeyEvent,
    element: ScriptElement,
    value: TextFieldValue,
    layout: TextLayoutResult?,
    onEvent: (DraftEvent) -> Unit,
): Boolean {
    if (event.type != KeyEventType.KeyDown) return false
    val command = event.isMetaPressed || event.isCtrlPressed
    val caret = value.selection.start
    val collapsed = value.selection.collapsed
    when {
        event.key == Key.Enter && !event.isShiftPressed && !command -> {
            onEvent(DraftEvent.Split(element.id, caret))
            return true
        }
        event.key == Key.Tab -> {
            onEvent(if (event.isShiftPressed) DraftEvent.BackTab(element.id) else DraftEvent.Tab(element.id))
            return true
        }
        event.key == Key.Backspace && collapsed && caret == 0 -> {
            onEvent(DraftEvent.MergeUp(element.id))
            return true
        }
        event.key == Key.Delete && value.text.isEmpty() -> {
            onEvent(DraftEvent.RemoveEmpty(element.id))
            return true
        }
        command && event.key in DIGITS -> {
            ElementType.fromShortcut(DIGITS.indexOf(event.key) + 1)?.let { onEvent(DraftEvent.SetType(element.id, it)) }
            return true
        }
        event.key == Key.DirectionUp && collapsed -> {
            val line = layout?.getLineForOffset(caret) ?: 0
            if (line == 0) {
                onEvent(DraftEvent.MoveFocus(element.id, -1, toEnd = true))
                return true
            }
        }
        event.key == Key.DirectionDown && collapsed -> {
            val last = (layout?.lineCount ?: 1) - 1
            val line = layout?.getLineForOffset(caret) ?: 0
            if (line >= last) {
                onEvent(DraftEvent.MoveFocus(element.id, +1, toEnd = false))
                return true
            }
        }
    }
    return false
}

private fun placeholder(type: ElementType): String = when (type) {
    ElementType.SceneHeading -> "INT. LOCATION - DAY"
    ElementType.Action -> "Action"
    ElementType.Character -> "CHARACTER"
    ElementType.Parenthetical -> "(parenthetical)"
    ElementType.Dialogue -> "Dialogue"
    ElementType.Transition -> "CUT TO:"
    ElementType.Shot -> "SHOT"
    ElementType.General -> "General"
}

private val DIGITS = listOf(Key.One, Key.Two, Key.Three, Key.Four, Key.Five, Key.Six, Key.Seven, Key.Eight)

private val FONT_SIZE = 13.sp
private val LINE_HEIGHT = 20.sp
private val LINE_HEIGHT_DP = 20.dp
/** Screen inch: the paper is drawn at ~65% of print size so a page fits a window. */
private val INCH: Dp = 68.dp
/** Monospace 13sp is close to 8dp a character. */
private val CHAR: Dp = 8.dp
private const val LEFT_MARGIN_INCHES = 1.5
private val GUTTER = 96.dp
private val PAPER_WIDTH = 640.dp
private val PAPER_TAIL = 240.dp
private const val GUTTER_LABEL_CHARS = 8
