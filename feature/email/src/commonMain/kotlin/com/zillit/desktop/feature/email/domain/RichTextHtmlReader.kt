package com.zillit.desktop.feature.email.domain

/**
 * Reading the wire's HTML back into an editable document — the other half of
 * `RichTextHtml.kt`.
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
                reader.write(html.substring(index))
                html.length - index
            }

            html[index] == '<' -> {
                reader.applyTag(html.substring(index + 1, close).trim())
                close + 1 - index
            }

            else -> {
                val entity = html.entityAt(index)
                reader.write((entity?.first ?: html[index]).toString())
                entity?.second ?: 1
            }
        }
    }

    return reader.finish()
}

/** The parser's working state: text so far, finished marks, and what's open. */
@Suppress("TooManyFunctions")
private class HtmlReader {
    private val text = StringBuilder()
    private val marks = mutableListOf<Mark>()
    private val open = mutableMapOf<TextMark, MutableList<Int>>()

    // Every `<span>` pushes a frame — even a styleless one — so `</span>`
    // always closes its own opener and never someone else's. `<a>` rides the
    // same stack: it is a span that happens to carry a link.
    private val spans = mutableListOf<List<Pair<TextMark, Int>>>()

    // The lists currently open, innermost last. An item's depth is its nesting
    // plus every enclosing tag's own margin — the writer's `appendList`.
    private val lists = mutableListOf<OpenList>()

    private val lineStyles = mutableListOf<LineStyle>()
    private var current = LineStyle()

    // A block's close is only a line break if something follows it. Held rather
    // than written, or `<div>a</div><div>b</div>` would end on a blank line the
    // sender never typed — and with indented lines now written as their own
    // `<div>`, that would be every paragraph in the body.
    private var pending = false

    fun write(characters: String) {
        flush()
        text.append(characters)
    }

    @Suppress("CyclomaticComplexMethod")
    fun applyTag(tag: String) {
        val name = tag.removePrefix("/").substringBefore(' ').substringBefore('/').lowercase()
        val isClosing = tag.startsWith('/')

        when {
            // A hard break inside a list item stays inside it, so the line it
            // starts keeps the item's own marker.
            name == "br" -> breakLine(current)

            name in LIST_TAGS -> if (isClosing) closeList() else openList(name, tag)

            name == "li" -> if (isClosing) markBreak() else openItem()

            name in BLOCK_TAGS -> if (isClosing) markBreak() else openBlock(tag)

            name == "span" || name == "a" -> if (isClosing) closeSpan() else openSpan(tag, isAnchor = name == "a")

            else -> applyMarkTag(name, isClosing)
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
        lineStyles += current

        return RichText(text.toString(), marks.filterNot { it.isEmpty }.sortedBy { it.start })
            .withLineStyles(lineStyles)
    }

    private fun applyMarkTag(name: String, isClosing: Boolean) {
        val style = MARK_TAGS[name] ?: return
        if (isClosing) {
            val start = open[style]?.removeLastOrNull() ?: return
            marks += Mark(start, text.length, style)
        } else {
            flush()
            open.getOrPut(style) { mutableListOf() } += text.length
        }
    }

    /** Remembers that a block ended; the break is written only once more content arrives. */
    private fun markBreak() {
        pending = true
    }

    private fun flush() {
        if (!pending) return
        pending = false
        breakLine(LineStyle())
    }

    private fun breakLine(next: LineStyle) {
        pending = false
        lineStyles += current
        splitAroundBreak()
        text.append('\n')
        current = next
    }

    private fun openList(name: String, tag: String) {
        flush()
        lists += OpenList(
            kind = if (name == "ol") BlockKind.Ordered else BlockKind.Bullet,
            // The web puts a list's own indent on the tag as `margin-left`;
            // `padding-left` there is ordinary list padding, not an indent.
            margin = indentLevel(tag.styleAttribute(), "margin-left"),
        )
    }

    private fun closeList() {
        lists.removeLastOrNull()
        markBreak()
    }

    private fun openItem() {
        flush()
        val list = lists.lastOrNull() ?: return
        current = LineStyle(list.kind, lists.indentOfItems())
    }

    /**
     * A `<p>` or `<div>` opens a line — unless it is the one TipTap wraps every
     * list item in, which would otherwise strip the marker off every item the
     * web has ever sent.
     */
    private fun openBlock(tag: String) {
        flush()
        val style = tag.styleAttribute()
        val indent = maxOf(indentLevel(style, "padding-left"), indentLevel(style, "margin-left"))
        current = if (lists.isEmpty()) LineStyle(null, indent) else current.copy(indent = maxOf(current.indent, indent))
    }

    private fun openSpan(tag: String, isAnchor: Boolean) {
        flush()
        val styles = stylesFromCss(tag.styleAttribute()) + listOfNotNull(
            tag.hrefAttribute()?.takeIf { isAnchor }?.sanitisedHref()?.let { TextMark.Link(it) },
        )
        spans += styles.map { it to text.length }
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

/** A list the parser is inside: its marker, and the indent levels its own tag carries. */
private data class OpenList(val kind: BlockKind, val margin: Int)

/** An item's depth: one per enclosing list beyond the first, plus every tag's own margin. */
private fun List<OpenList>.indentOfItems(): Int =
    (size - 1 + sumOf { it.margin }).coerceIn(0, MAX_INDENT)

/** The indent level a length in the given CSS property stands for, in [HtmlText.INDENT_EM] steps. */
private fun indentLevel(css: String, property: String): Int {
    val value = css.split(';')
        .firstOrNull { it.substringBefore(':').trim().lowercase() == property }
        ?.substringAfter(':', "")
        ?.replace("!important", "")
        ?.trim()
        ?.lowercase()
        ?: return 0

    val em = EM_LENGTH.matchEntire(value)?.groupValues?.get(1)?.toFloatOrNull()
        ?: PX_LENGTH.matchEntire(value)?.groupValues?.get(1)?.toFloatOrNull()?.div(PX_PER_EM)
        ?: return 0

    return (em / HtmlText.INDENT_EM).toInt().coerceIn(0, MAX_INDENT)
}

/** The value of a tag's `style="..."` attribute, or empty. */
private fun String.styleAttribute(): String {
    val match = STYLE_ATTR.find(this) ?: return ""
    return match.groupValues[1].ifEmpty { match.groupValues[2] }
}

/** The value of a tag's `href="..."` attribute, or null. */
private fun String.hrefAttribute(): String? {
    val match = HREF_ATTR.find(this) ?: return null
    return match.groupValues[1].ifEmpty { match.groupValues[2] }.ifEmpty { null }
}

/** The marks a CSS declaration list describes — unknown properties ignored. */
private fun stylesFromCss(css: String): List<TextMark> =
    css.split(';').mapNotNull { declaration ->
        val key = declaration.substringBefore(':').trim().lowercase()
        val raw = declaration.substringAfter(':', "").replace("!important", "").trim()
        val value = raw.lowercase()
        // A family name keeps its case and its quoting; every other value is a
        // keyword or a number, where lowercasing is what makes matching work.
        namedStyle(key, raw) ?: valuedStyle(key, value) ?: switchStyle(key, value)
    }

private fun namedStyle(key: String, raw: String): TextMark? =
    if (key == "font-family") raw.cssFontFamily()?.let { TextMark.FontFamily(it) } else null

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

/** The first family of a CSS font stack, unquoted, or null if it isn't a name. */
private fun String.cssFontFamily(): String? =
    substringBefore(',').trim().trim('"', '\'').trim().takeIf { HtmlText.FONT_NAME.matches(it) }

/** Normalises the colour syntaxes mail actually carries to `#rrggbb`. */
private fun cssColor(value: String): String? =
    value.takeIf { HtmlText.HEX_LONG.matches(it) } ?: shortHex(value) ?: rgbHex(value)

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

private val LIST_TAGS = setOf("ul", "ol")
private val BLOCK_TAGS = setOf("p", "div", "tr")

private val STYLE_ATTR = Regex("""style\s*=\s*(?:"([^"]*)"|'([^']*)')""", RegexOption.IGNORE_CASE)
private val HREF_ATTR = Regex("""href\s*=\s*(?:"([^"]*)"|'([^']*)')""", RegexOption.IGNORE_CASE)
private val HEX_SHORT = Regex("""#([0-9a-f]{3})""")
private val RGB_FUNC = Regex("""rgb\(([^,]+),([^,]+),([^,)]+)\)""")
private val EM_LENGTH = Regex("""([0-9]+(?:\.[0-9]+)?)\s*em""")
private val PX_LENGTH = Regex("""([0-9]+(?:\.[0-9]+)?)\s*px""")

private const val PX_PER_EM = 16f
private const val MAX_ENTITY = 8
private const val MAX_CHANNEL = 255
private const val HEX_RADIX = 16
private const val BOLD_WEIGHT = 600
