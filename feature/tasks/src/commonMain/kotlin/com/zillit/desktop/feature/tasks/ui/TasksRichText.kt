@file:Suppress("LongMethod", "MaxLineLength") // The description is one control with two faces, laid out to the web's DescriptionField.

package com.zillit.desktop.feature.tasks.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.tasks.domain.LinkPart
import com.zillit.desktop.feature.tasks.domain.insertAt
import com.zillit.desktop.feature.tasks.domain.isPastedLink
import com.zillit.desktop.feature.tasks.domain.linkParts
import com.zillit.desktop.feature.tasks.domain.linkText
import com.zillit.desktop.feature.tasks.domain.webAddress

/**
 * The description and comments, shown with their links clickable — a port of
 * the web's `RichText.jsx` and `DescriptionField.jsx`.
 *
 * **The text is plain text.** It is stored exactly as it was typed
 * (`[Recce photos](https://…)`, or the bare address), so it reads well in the
 * phone apps and anywhere else; links are only how it is *shown*. Only `http`
 * and `https` become links (`lib/links.kt` → [linkParts]).
 */

/**
 * [text] as one annotated string: links clickable, and any real @mention
 * highlighted. A link opens through [onOpenUrl] — the host's guarded launcher,
 * never a URI handler of this module's own.
 */
@Composable
internal fun taskRichText(
    text: String,
    mentionNames: List<String> = emptyList(),
    onOpenUrl: ((String) -> Unit)? = null,
): AnnotatedString {
    val k = TasksTheme.c
    return remember(text, mentionNames, k.accent, onOpenUrl) {
        buildAnnotatedString {
            linkParts(text).forEach { part ->
                when (part) {
                    is LinkPart.Link -> {
                        val style = TextLinkStyles(SpanStyle(color = k.accent, textDecoration = TextDecoration.Underline))
                        // Clickable rather than Url: what may leave the app is the host's decision.
                        val link = LinkAnnotation.Clickable(tag = part.href, styles = style) { onOpenUrl?.invoke(part.href) }
                        withLink(link) { append(part.text) }
                    }
                    is LinkPart.Text -> appendMentions(part.text, mentionNames, k.accent)
                }
            }
        }
    }
}

/** Plain text, with each real @mention in the accent colour. */
private fun androidx.compose.ui.text.AnnotatedString.Builder.appendMentions(
    text: String,
    names: List<String>,
    accent: Color,
) {
    if (names.isEmpty()) {
        append(text)
        return
    }
    var rest = text
    while (rest.isNotEmpty()) {
        val hit = names.map { "@$it" }.map { it to rest.indexOf(it) }.filter { it.second >= 0 }.minByOrNull { it.second }
        if (hit == null) {
            append(rest)
            return
        }
        val (token, at) = hit
        append(rest.take(at))
        withStyle(SpanStyle(color = accent, fontWeight = FontWeight.SemiBold)) { append(token) }
        rest = rest.substring(at + token.length)
    }
}

/** Text with its links clickable, at the size the caller reads it in. */
@Composable
internal fun RichText(
    text: String,
    modifier: Modifier = Modifier,
    size: Int = 14,
    color: Color = TasksTheme.c.fg,
    mentionNames: List<String> = emptyList(),
    onOpenUrl: ((String) -> Unit)? = null,
) {
    ZillitText(
        text = taskRichText(text, mentionNames, onOpenUrl),
        modifier = modifier,
        style = txt(size, lineHeight = (size * 1.55f).sp),
        color = color,
    )
}

/**
 * The description: shown formatted, with a click anywhere but on a link
 * starting an edit; leaving the box shows it formatted again.
 *
 * Three ways to a link, as on the web: paste an address, paste one over
 * selected words, or "Add link". The formatted view and the text box share one
 * least height, so a short description does not change height when it
 * switches and the details under it stay under the click that caused it.
 */
@Composable
internal fun DescriptionField(
    value: String,
    onChange: (String) -> Unit,
    enabled: Boolean,
    placeholder: String,
    onOpenUrl: ((String) -> Unit)? = null,
) {
    val k = TasksTheme.c
    var editing by remember { mutableStateOf(false) }
    var field by remember { mutableStateOf(TextFieldValue(value)) }
    var linking by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val clipboard = LocalClipboardManager.current
    val shape = RoundedCornerShape(10.dp)

    // The panel's own copy is the truth: a save's reply, Discard or another
    // task replaces what is being edited.
    LaunchedEffect(value) { if (field.text != value) field = TextFieldValue(value, TextRange(value.length)) }
    LaunchedEffect(editing) { if (editing) runCatching { focus.requestFocus() } }

    /** Put [text] at the caret (or over the selection), and keep the caret after it. */
    val put = { text: String, spaced: Boolean ->
        val done = insertAt(field.text, field.selection.start, field.selection.end, text, spaced)
        field = TextFieldValue(done.text, TextRange(done.caret))
        onChange(done.text)
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            Modifier.fillMaxWidth().heightIn(min = DESCRIPTION_MIN).clip(shape)
                .background(if (editing) k.surface else k.surface2)
                .border(BorderStroke(1.dp, if (editing) k.accent else Color.Transparent), shape)
                .padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            if (editing && enabled) {
                DescriptionEditor(
                    field = field,
                    placeholder = placeholder,
                    focus = focus,
                    onChange = { next ->
                        field = next
                        if (next.text != value) onChange(next.text)
                    },
                    onLeave = { if (!linking) editing = false },
                    onPasteLink = { pasted ->
                        val url = webAddress(pasted)
                        if (url == null) {
                            false
                        } else {
                            val words = field.text.substring(field.selection.start, field.selection.end)
                            put(linkText(url, words), words.isEmpty())
                            true
                        }
                    },
                )
            } else {
                val source = remember { MutableInteractionSource() }
                Box(
                    Modifier.fillMaxWidth()
                        // A link stops its own click, so this only fires off one.
                        .then(if (enabled) Modifier.clickable(interactionSource = source, indication = null) { editing = true } else Modifier),
                ) {
                    if (value.isBlank()) {
                        ZillitText(placeholder, style = txt(14, lineHeight = 22.sp), color = k.faint)
                    } else {
                        RichText(value, onOpenUrl = onOpenUrl)
                    }
                }
            }
        }
        if (enabled) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                LinkButton(onInsert = { url, words -> put(linkText(url, words), true) }, onOpen = { linking = it; if (it) editing = true })
                TText(str(S.desktop_tasks_links_hint), 12, color = k.faint)
            }
        }
    }
}

/**
 * The description's text box.
 *
 * A `TextFieldValue`, not a `String`: "Add link" and a pasted address go in at
 * the caret (or over the selection), so the real selection has to be the one
 * this holds.
 */
@Composable
private fun DescriptionEditor(
    field: TextFieldValue,
    placeholder: String,
    focus: FocusRequester,
    onChange: (TextFieldValue) -> Unit,
    onLeave: () -> Unit,
    /** A pasted web address: answers true once it has been put in as a link. */
    onPasteLink: (String) -> Boolean,
) {
    val k = TasksTheme.c
    val clipboard = LocalClipboardManager.current
    val style = txt(14, lineHeight = 22.sp)
    BasicTextField(
        value = field,
        onValueChange = { next -> if (next.text.length <= DESCRIPTION_MAX) onChange(next) },
        modifier = Modifier.fillMaxWidth().focusRequester(focus)
            // Leaving the box shows the formatted text again.
            .onFocusChanged { if (!it.isFocused) onLeave() }
            .onPreviewKeyEvent { event -> pastedLink(event, clipboard.getText()?.text, onPasteLink) },
        textStyle = style.copy(color = k.fg),
        cursorBrush = SolidColor(k.accent),
        decorationBox = { inner ->
            Box {
                if (field.text.isEmpty()) ZillitText(placeholder, style = style, color = k.faint)
                inner()
            }
        },
    )
}

/** Ctrl/Cmd+V of one whole web address becomes a link instead of plain text. */
private fun pastedLink(event: KeyEvent, pasted: String?, insert: (String) -> Boolean): Boolean {
    if (event.type != KeyEventType.KeyDown) return false
    if (!(event.isCtrlPressed || event.isMetaPressed) || event.key != Key.V) return false
    val text = pasted.orEmpty()
    if (!isPastedLink(text)) return false
    return insert(text)
}

/**
 * "Add link": a small form for the address and the words to show, inserted at
 * the caret. It keeps the focus where it is on a press, so the caret in the
 * description is not lost before the press arrives.
 */
@Composable
private fun LinkButton(onInsert: (String, String) -> Unit, onOpen: (Boolean) -> Unit) {
    val k = TasksTheme.c
    var open by remember { mutableStateOf(false) }
    var address by remember(open) { mutableStateOf("") }
    var words by remember(open) { mutableStateOf("") }
    val url = webAddress(address)

    Box {
        Row(
            Modifier.clip(RoundedCornerShape(6.dp)).clickable {
                onOpen(true)
                open = true
            }.padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ZillitIcon(ZillitIcons.Link, tint = k.accent, size = 14.dp)
            TText(str(S.desktop_csync_add_link), 13, FontWeight.SemiBold, k.accent)
        }
        com.zillit.desktop.core.designsystem.component.ZillitMenuSurface(
            expanded = open,
            onDismissRequest = {
                open = false
                onOpen(false)
            },
        ) {
            Column(Modifier.width(300.dp).padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                TText(str(S.desktop_csync_add_link), 14, FontWeight.SemiBold)
                LinkInput(str(S.link), address) { address = it }
                LinkInput(str(S.desktop_tasks_link_text), words, str(S.desktop_tasks_link_text_placeholder)) { words = it }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                    TBtn(str(S.cancel), {
                        open = false
                        onOpen(false)
                    }, kind = BtnKind.Ghost, small = true)
                    TBtn(str(S.insert), {
                        url?.let { onInsert(it, words) }
                        open = false
                        onOpen(false)
                    }, small = true, enabled = url != null)
                }
            }
        }
    }
}

@Composable
private fun LinkInput(label: String, value: String, placeholder: String = "", onChange: (String) -> Unit) {
    val k = TasksTheme.c
    val shape = RoundedCornerShape(6.dp)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        TText(label, 12, color = k.muted)
        TInput(
            value, onChange, Modifier.fillMaxWidth(), placeholder = placeholder,
            decoration = { inner, focused ->
                Box(
                    Modifier.fillMaxWidth().heightIn(min = 34.dp).clip(shape).background(k.surface)
                        .border(BorderStroke(1.dp, if (focused) k.accent else k.line2), shape)
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    contentAlignment = Alignment.CenterStart,
                ) { inner() }
            },
        )
    }
}

private val DESCRIPTION_MIN = 104.dp

/** The service's own limit; the same one the panel's other fields are held to. */
private const val DESCRIPTION_MAX = 10000
