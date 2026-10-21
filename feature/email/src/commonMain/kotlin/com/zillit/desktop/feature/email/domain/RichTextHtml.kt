package com.zillit.desktop.feature.email.domain

/**
 * Writing a composed body as the HTML the wire carries.
 *
 * Reading it back is [htmlToRichText], in `RichTextHtmlReader.kt`. The two
 * halves share only the small vocabulary at the foot of this file — the
 * sanitisers and the unit a length is measured in — because that shared
 * vocabulary *is* the round-trip guarantee.
 *
 * ## Mail bodies are HTML, always
 *
 * `imap-send` takes `body` as HTML — the web composes with TipTap and posts its
 * markup. Sending plain text therefore does not send plain text: it sends
 * unmarked-up HTML, and every line break in it disappears at the far end, since
 * HTML collapses whitespace. Newlines have to become `<br>` whether or not the
 * user applied any formatting at all.
 *
 * ## Paragraphs take the web's shapes, not ours
 *
 * Lists go out as `<ul>`/`<ol>` with real nesting, and an indent as a multiple
 * of [HtmlText.INDENT_EM] — `padding-left` on a plain line, `margin-left` on a list,
 * exactly as the web's `IndentBlock` writes and reads them. Agreeing on the
 * unit is what lets a message indented here come back indented there.
 */
fun RichText.toHtml(): String {
    if (text.isEmpty()) return ""

    val spans = lines()
    val styles = lineStyles()
    val out = StringBuilder()
    var index = 0

    while (index < spans.size) {
        val style = styles[index]
        index = if (style.kind == null) {
            appendParagraph(out, spans[index], style, isLast = index == spans.lastIndex)
            index + 1
        } else {
            appendList(out, spans, styles, index, depth = style.indent, margin = style.indent)
        }
    }

    return out.toString()
}

/** One line wearing no marker: bare text, or a pushed-right `<div>`. */
private fun RichText.appendParagraph(out: StringBuilder, line: LineSpan, style: LineStyle, isLast: Boolean) {
    if (style.indent == 0) {
        appendInline(out, line)
        // Between lines, never after the last one: a trailing `<br>` is a
        // blank line the composer never showed.
        if (!isLast) out.append("<br>")
        return
    }
    out.append("<div style=\"padding-left: ${style.indent * HtmlText.INDENT_EM}em\">")
    appendInline(out, line)
    out.append("</div>")
}

/**
 * One list, from [from] until the run breaks, returning the line it stopped at.
 *
 * [depth] is the indent its own items wear; [margin] is how much of that depth
 * the tag itself has to carry, because nesting alone cannot express it — the
 * whole of a top-level list's indent, or the surplus when a child jumps more
 * than one step past its parent. The reader adds the two back together.
 */
private fun RichText.appendList(
    out: StringBuilder,
    spans: List<LineSpan>,
    styles: List<LineStyle>,
    from: Int,
    depth: Int,
    margin: Int,
): Int {
    val kind = styles[from].kind ?: return from + 1
    val tag = if (kind == BlockKind.Ordered) "ol" else "ul"

    out.append(if (margin > 0) "<$tag style=\"margin-left: ${margin * HtmlText.INDENT_EM}em\">" else "<$tag>")

    var index = from
    while (index < spans.size && styles[index].belongsIn(kind, depth)) {
        val style = styles[index]
        index = if (style.indent > depth) {
            appendList(out, spans, styles, index, style.indent, style.indent - depth - 1)
        } else {
            out.append("<li>")
            appendInline(out, spans[index])
            out.append("</li>")
            index + 1
        }
    }

    out.append("</$tag>")
    return index
}

/**
 * Whether this line belongs to a list of [kind] at [depth], as an item of its
 * own or as something nested under one.
 *
 * A plain line, a shallower one, or a different marker at this very depth all
 * end the list; the caller then opens whatever comes next.
 */
private fun LineStyle.belongsIn(kind: BlockKind, depth: Int): Boolean =
    this.kind != null && indent >= depth && (indent > depth || this.kind == kind)

/** The inline markup over one line — its marks, opened and closed in a stable order. */
private fun RichText.appendInline(out: StringBuilder, line: LineSpan) {
    var open = emptySet<TextMark>()

    for (index in line.start until line.end) {
        // Ordered by family so the nesting is stable: valued spans outermost,
        // switches inside, the same shape for the same formatting every time.
        val wanted = marks
            .filter { index >= it.start && index < it.end }
            .sortedWith(compareBy({ it.style.family.ordinal }, { it.style.toString() }))
            .mapTo(LinkedHashSet()) { it.style }

        // Closed in reverse so the tags nest properly. `<b><i>x</i></b>` is
        // valid; `<b><i>x</b></i>` is what a naive writer emits, and mail
        // clients render the remainder of the message inside the stray tag.
        (open - wanted).reversed(open).forEach { out.append(it.closeTag) }
        (wanted - open).forEach { out.append(it.openTag) }
        open = wanted

        out.append(text[index].escaped())
    }

    open.reversed(open).forEach { out.append(it.closeTag) }
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
        is TextMark.FontFamily -> "<span style=\"font-family: ${name.sanitisedFont()}\">"
        is TextMark.Link -> "<a href=\"${href.sanitisedHref().orEmpty()}\" target=\"_blank\">"
    }

private val TextMark.closeTag: String
    get() = when (this) {
        TextMark.Bold -> "</b>"
        TextMark.Italic -> "</i>"
        TextMark.Underline -> "</u>"
        TextMark.Strike -> "</s>"
        is TextMark.Link -> "</a>"
        else -> "</span>"
    }

/** `<` and `&` must be escaped or the body stops being the body. */
private fun Char.escaped(): String = when (this) {
    '&' -> "&amp;"
    '<' -> "&lt;"
    '>' -> "&gt;"
    '\n' -> "<br>"
    else -> toString()
}

private fun String.sanitisedColor(): String = takeIf { HtmlText.HEX_LONG.matches(it) } ?: "#000000"

private fun String.sanitisedFont(): String = takeIf { HtmlText.FONT_NAME.matches(it) } ?: "inherit"

/**
 * A link target fit to put in an attribute, or null.
 *
 * An allowlist of schemes rather than an escape: `javascript:` in a mail body
 * is not formatting, and a quote in the value ends the attribute and starts
 * writing markup of its own.
 */
internal fun String.sanitisedHref(): String? {
    val trimmed = trim()
    if (trimmed.any { it.isWhitespace() || it in "\"'<>" }) return null
    return trimmed.takeIf { candidate -> HtmlText.LINK_SCHEMES.any { candidate.startsWith(it, ignoreCase = true) } }
}

/**
 * The vocabulary the writer and the reader must agree on.
 *
 * An object rather than top-level constants because this package already has
 * another `HEX_LONG` — `BodySpan.kt`'s, for reading a received body's colours.
 * Two file-private copies coexist; the moment one goes `internal` to be shared
 * they collide, and the name a reader of either file reaches for is the wrong
 * one. The namespace says which layer a value belongs to.
 */
internal object HtmlText {
    /** The web's indent step (`IndentBlock.INDENT_UNIT_EM`), in em. */
    const val INDENT_EM = 3

    val HEX_LONG = Regex("""#[0-9a-f]{6}""")
    val FONT_NAME = Regex("""[A-Za-z][A-Za-z0-9 \-]{0,48}""")
    val LINK_SCHEMES = listOf("http://", "https://", "mailto:")
}

private const val MIN_PX = 8
private const val MAX_PX = 72
