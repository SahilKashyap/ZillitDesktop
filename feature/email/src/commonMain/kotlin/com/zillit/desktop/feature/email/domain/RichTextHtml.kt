package com.zillit.desktop.feature.email.domain

/**
 * Converting a composed body to and from the HTML the wire carries.
 *
 * ## Mail bodies are HTML, always
 *
 * `imap-send` takes `body` as HTML — the web composes with Quill and posts its
 * markup. Sending plain text therefore does not send plain text: it sends
 * unmarked-up HTML, and every line break in it disappears at the far end, since
 * HTML collapses whitespace. Newlines have to become `<br>` whether or not the
 * user applied any formatting at all.
 */
fun RichText.toHtml(): String {
    if (text.isEmpty()) return ""

    val builder = StringBuilder()
    var open = emptySet<TextMark>()

    text.forEachIndexed { index, character ->
        // Ordered by family so the nesting is stable: valued spans outermost,
        // switches inside, the same shape for the same formatting every time.
        val wanted = marks
            .filter { index >= it.start && index < it.end }
            .sortedWith(compareBy({ it.style.family.ordinal }, { it.style.toString() }))
            .mapTo(LinkedHashSet()) { it.style }

        // Closed in reverse so the tags nest properly. `<b><i>x</i></b>` is
        // valid; `<b><i>x</b></i>` is what a naive writer emits, and mail
        // clients render the remainder of the message inside the stray tag.
        (open - wanted).reversed(open).forEach { builder.append(it.closeTag) }
        (wanted - open).forEach { builder.append(it.openTag) }
        open = wanted

        builder.append(character.escaped())
    }

    open.reversed(open).forEach { builder.append(it.closeTag) }

    return builder.toString()
}

/**
 * Reads HTML back into an editable document.
 *
 * Deliberately narrow: it understands the handful of tags this composer emits,
 * plus the equivalents other clients use (`<strong>`, `<em>`, `<del>`,
 * styled `<span>`s). Everything else is stripped to its text — the alternative
 * is showing raw markup in an editor, which is worse than losing formatting
 * nobody can edit here anyway.
 */
fun htmlToRichText(html: String): RichText {
    if (html.isBlank()) return RichText()

    val reader = HtmlReader()
    var index = 0

    while (index < html.length) {
        val close = if (html[index] == '<') html.indexOf('>', index) else -1

        index += when {
            html[index] == '<' && close < 0 -> {
                // An unterminated tag: the rest is text, rather than dropping a
                // message body because someone's client emitted broken markup.
                reader.text.append(html.substring(index))
                html.length - index
            }

            html[index] == '<' -> {
                reader.applyTag(html.substring(index + 1, close).trim())
                close + 1 - index
            }

            else -> {
                val entity = html.entityAt(index)
                reader.text.append(entity?.first ?: html[index])
                entity?.second ?: 1
            }
        }
    }

    return reader.finish()
}

/** The parser's working state: text so far, finished marks, and what's open. */
private class HtmlReader {
    val text = StringBuilder()
    private val marks = mutableListOf<Mark>()
    private val open = mutableMapOf<TextMark, MutableList<Int>>()

    // Every `<span>` pushes a frame — even a styleless one — so `</span>`
    // always closes its own opener and never someone else's.
    private val spans = mutableListOf<List<Pair<TextMark, Int>>>()

    fun applyTag(tag: String) {
        val name = tag.removePrefix("/").substringBefore(' ').substringBefore('/').lowercase()
        val isClosing = tag.startsWith('/')

        when {
            name in BREAK_TAGS -> {
                // `<br>` and the *end* of a paragraph are both line breaks.
                // Opening `<p>` is not, or every body would start with a blank
                // line.
                if (name == "br" || isClosing) {
                    splitAroundBreak()
                    text.append('\n')
                }
            }

            name == "span" -> if (isClosing) closeSpan() else openSpan(tag)

            else -> {
                val style = MARK_TAGS[name] ?: return
                if (isClosing) {
                    val start = open[style]?.removeLastOrNull() ?: return
                    marks += Mark(start, text.length, style)
                } else {
                    open.getOrPut(style) { mutableListOf() } += text.length
                }
            }
        }
    }

    fun finish(): RichText {
        // Tags left open at the end run to the end of the body.
        open.forEach { (style, starts) ->
            starts.forEach { start -> marks += Mark(start, text.length, style) }
        }
        spans.forEach { frame ->
            frame.forEach { (style, start) -> marks += Mark(start, text.length, style) }
        }
        return RichText(text.toString(), marks.filterNot { it.isEmpty }.sortedBy { it.start })
    }

    private fun openSpan(tag: String) {
        spans += stylesFromCss(tag.styleAttribute()).map { it to text.length }
    }

    private fun closeSpan() {
        val frame = spans.removeLastOrNull() ?: return
        frame.forEach { (style, start) -> marks += Mark(start, text.length, style) }
    }

    /**
     * A line break is a character in the document, so everything open has to be
     * split around it — otherwise a mark spans the break and the newline itself
     * ends up bold, which is invisible until it is pasted somewhere else.
     */
    private fun splitAroundBreak() {
        val position = text.length
        open.forEach { (style, starts) ->
            starts.indices.forEach { slot ->
                marks += Mark(starts[slot], position, style)
                starts[slot] = position + 1
            }
        }
        spans.indices.forEach { frame ->
            spans[frame] = spans[frame].map { (style, start) ->
                marks += Mark(start, position, style)
                style to position + 1
            }
        }
    }
}

/** The value of a tag's `style="..."` attribute, or empty. */
private fun String.styleAttribute(): String {
    val match = STYLE_ATTR.find(this) ?: return ""
    return match.groupValues[1].ifEmpty { match.groupValues[2] }
}

/** The marks a CSS declaration list describes — unknown properties ignored. */
private fun stylesFromCss(css: String): List<TextMark> =
    css.split(';').mapNotNull { declaration ->
        val key = declaration.substringBefore(':').trim().lowercase()
        val value = declaration.substringAfter(':', "").replace("!important", "").trim().lowercase()
        valuedStyle(key, value) ?: switchStyle(key, value)
    }

private fun valuedStyle(key: String, value: String): TextMark? = when (key) {
    "color" -> cssColor(value)?.let { TextMark.TextColor(it) }
    "background-color", "background" -> cssColor(value)?.let { TextMark.Highlight(it) }
    "font-size" -> value.removeSuffix("px").toIntOrNull()?.let { TextMark.FontSize(it) }
    else -> null
}

private fun switchStyle(key: String, value: String): TextMark? = when (key) {
    "text-decoration" -> when {
        "line-through" in value -> TextMark.Strike
        "underline" in value -> TextMark.Underline
        else -> null
    }
    "font-weight" -> TextMark.Bold.takeIf { value == "bold" || (value.toIntOrNull() ?: 0) >= BOLD_WEIGHT }
    "font-style" -> TextMark.Italic.takeIf { value == "italic" }
    else -> null
}

/** Normalises the colour syntaxes mail actually carries to `#rrggbb`. */
private fun cssColor(value: String): String? =
    value.takeIf { HEX_LONG.matches(it) } ?: shortHex(value) ?: rgbHex(value)

private fun shortHex(value: String): String? {
    val match = HEX_SHORT.matchEntire(value) ?: return null
    return "#" + match.groupValues[1].map { "$it$it" }.joinToString("")
}

private fun rgbHex(value: String): String? {
    val match = RGB_FUNC.matchEntire(value) ?: return null
    val channels = match.groupValues.drop(1).map { it.trim().toIntOrNull() }
    if (channels.any { it == null || it !in 0..MAX_CHANNEL }) return null
    return "#" + channels.joinToString("") {
        checkNotNull(it).toString(HEX_RADIX).padStart(2, '0')
    }
}

/** `<` and `&` must be escaped or the body stops being the body. */
private fun Char.escaped(): String = when (this) {
    '&' -> "&amp;"
    '<' -> "&lt;"
    '>' -> "&gt;"
    '\n' -> "<br>"
    else -> toString()
}

/** Returns the decoded character and how many source characters it spanned. */
private fun String.entityAt(index: Int): Pair<Char, Int>? {
    if (this[index] != '&') return null
    val semicolon = indexOf(';', index)
    if (semicolon < 0 || semicolon - index > MAX_ENTITY) return null

    // The span is the matched text's own length, not a number per entity —
    // which is one fewer thing to get wrong when another entity is added.
    val entity = substring(index, semicolon + 1)
    val decoded = when (entity.lowercase()) {
        "&amp;" -> '&'
        "&lt;" -> '<'
        "&gt;" -> '>'
        "&quot;" -> '"'
        "&nbsp;" -> ' '
        "&#39;", "&apos;" -> '\''
        else -> return null
    }
    return decoded to entity.length
}

/** Reversed against a stable order, so tags close in the order they opened. */
private fun Set<TextMark>.reversed(order: Set<TextMark>): List<TextMark> =
    order.toList().reversed().filter { it in this }

private val TextMark.openTag: String
    get() = when (this) {
        TextMark.Bold -> "<b>"
        TextMark.Italic -> "<i>"
        TextMark.Underline -> "<u>"
        TextMark.Strike -> "<s>"
        // Values are emitted only from the editor's own palette, but the body
        // is a wire format: anything that isn't a clean value goes out as a
        // bare span rather than as markup injection.
        is TextMark.TextColor -> "<span style=\"color: ${hex.sanitisedColor()}\">"
        is TextMark.Highlight -> "<span style=\"background-color: ${hex.sanitisedColor()}\">"
        is TextMark.FontSize -> "<span style=\"font-size: ${px.coerceIn(MIN_PX, MAX_PX)}px\">"
    }

private val TextMark.closeTag: String
    get() = when (this) {
        TextMark.Bold -> "</b>"
        TextMark.Italic -> "</i>"
        TextMark.Underline -> "</u>"
        TextMark.Strike -> "</s>"
        else -> "</span>"
    }

private fun String.sanitisedColor(): String = takeIf { HEX_LONG.matches(it) } ?: "#000000"

private val MARK_TAGS = mapOf(
    "b" to TextMark.Bold,
    "strong" to TextMark.Bold,
    "i" to TextMark.Italic,
    "em" to TextMark.Italic,
    "u" to TextMark.Underline,
    "s" to TextMark.Strike,
    "strike" to TextMark.Strike,
    "del" to TextMark.Strike,
)

private val BREAK_TAGS = setOf("br", "p", "div", "li", "tr")

private val STYLE_ATTR = Regex("""style\s*=\s*(?:"([^"]*)"|'([^']*)')""", RegexOption.IGNORE_CASE)
private val HEX_LONG = Regex("""#[0-9a-f]{6}""")
private val HEX_SHORT = Regex("""#([0-9a-f]{3})""")
private val RGB_FUNC = Regex("""rgb\(([^,]+),([^,]+),([^,)]+)\)""")

private const val MAX_ENTITY = 8
private const val MAX_CHANNEL = 255
private const val HEX_RADIX = 16
private const val BOLD_WEIGHT = 600
private const val MIN_PX = 8
private const val MAX_PX = 72
