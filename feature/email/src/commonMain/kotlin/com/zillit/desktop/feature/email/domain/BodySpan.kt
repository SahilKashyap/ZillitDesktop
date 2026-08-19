@file:Suppress(
    // An HTML subset parser: one small function per tag, attribute and entity
    // family. Splitting it across files would scatter a single grammar — the
    // reader wants `<font color>` beside the colour it produces.
    "TooManyFunctions",
)

package com.zillit.desktop.feature.email.domain

import kotlin.math.pow

/**
 * How a run of body text is drawn.
 *
 * Kept as plain values — hex strings, a scale factor, a depth — rather than
 * Compose types, so the whole conversion stays testable without a toolkit and
 * the design system decides what "bold" or "quoted" actually looks like.
 */
data class BodyStyle(
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val strike: Boolean = false,
    /** `<pre>`, `<code>`, `<tt>`, or a monospace `font-family`. */
    val monospace: Boolean = false,
    /** Text colour as `#rrggbb`; null leaves it to the theme. */
    val color: String? = null,
    /** Background as `#rrggbb`; null for none. */
    val background: String? = null,
    /** Font size relative to the body's — 1.0 is unchanged. */
    val sizeScale: Float = 1f,
    /** How many `<blockquote>`s deep the run sits; 0 for none. */
    val quoteDepth: Int = 0,
)

/**
 * A run of body text: what it says, where it links, and how it is drawn.
 *
 * Emitted rather than styled here so the design system decides how a link or a
 * heading looks — and so this whole conversion is testable without Compose.
 */
data class BodySpan(
    val text: String,
    val href: String? = null,
    val style: BodyStyle = BodyStyle(),
) {
    val isLink: Boolean get() = href != null
}

/**
 * Turns an HTML mail body into styled text with its links intact.
 *
 * ## Why not render the HTML
 *
 * Compose has no HTML renderer, and mail HTML is the worst HTML there is —
 * nested tables, inline CSS, tracking pixels. Android renders the body in a
 * WebView (`bottomNav/new_email/ui/components/EmailTrailAdapter.kt:426-483`,
 * with `img{max-width:100%}`, an orange link colour and a body colour picked
 * by theme); we are not adding a browser, so this honours the formatting people
 * actually apply in a composer — weight, slant, decoration, colour, size,
 * lists, quotes, links, code — and flattens everything else to its text.
 *
 * ## What is deliberately dropped
 *
 * `<script>`, `<style>`, `<head>` and comments are removed entirely rather than
 * shown as text — otherwise a styled newsletter renders as a wall of CSS.
 * Anything with `display:none` is dropped too, which is where newsletter
 * preheaders hide. Remote images are not fetched at all, which also happens to
 * defeat tracking pixels.
 */
fun htmlToSpans(html: String): List<BodySpan> {
    if (html.isBlank()) return emptyList()

    val reader = BodyReader()
    var index = 0
    while (index < html.length) {
        index += reader.consume(html, index)
    }
    return reader.finish().linkifyBareUrls()
}

/**
 * A plain-text body as spans, with bare URLs made clickable.
 *
 * Both reference clients linkify pasted URLs at display time — Android's
 * `EmailLinkify` (ZL-20216) and the web's `wrapUrlsInAnchorTags` — because a
 * call-sheet link someone typed by hand is the most common link in a mail.
 */
fun plainTextToSpans(text: String): List<BodySpan> =
    if (text.isEmpty()) emptyList() else listOf(BodySpan(text)).linkifyBareUrls()

/** The same body as one string, for snippets and search. */
fun htmlToPlainText(html: String): String =
    htmlToSpans(html).joinToString("") { it.text }.collapseBlankLines().trim()

/** Only web links open; anything else — `file:`, custom schemes — is a foothold. */
fun String.isWebUrl(): Boolean {
    val lower = trim().lowercase()
    return lower.startsWith("http://") || lower.startsWith("https://")
}

// -- colours ---------------------------------------------------------------

/**
 * Whether [hex] would disappear against the theme's background.
 *
 * Near-black text on a dark theme — the default a light-mode composer emits —
 * is why "the colour I set shows as black" read as invisible. Near-white on a
 * light theme is the mirror case. Judged by relative luminance rather than by
 * name, so `#1A1A1A` and `#222` are caught as well as pure black.
 *
 * **Only greys are second-guessed.** A saturated colour is a choice somebody
 * made — a dark blue heading stays that blue on either theme, even where the
 * contrast is poor, because replacing it would be the app overruling the
 * author. What this catches is the *unintended* black: the colour a composer
 * wrote because its own background happened to be white.
 */
fun vanishesOnTheme(hex: String, isDark: Boolean): Boolean {
    if (saturationOf(hex) > NEUTRAL_SATURATION) return false
    val luminance = relativeLuminance(hex) ?: return false
    return if (isDark) luminance < DARK_THEME_FLOOR else luminance > LIGHT_THEME_CEILING
}

/** HSV saturation of a `#rrggbb`, 0 (grey) to 1; 0 for anything unreadable. */
private fun saturationOf(hex: String): Double {
    if (!HEX_LONG.matches(hex)) return 0.0
    val channels = HEX_CHANNEL_STARTS.map { start -> hex.substring(start, start + HEX_PAIR).toInt(HEX_RADIX) }
    val high = channels.max()
    val low = channels.min()
    return if (high == 0) 0.0 else (high - low) / high.toDouble()
}

/**
 * A text colour that reads on [backgroundHex]: dark on a light highlight,
 * light on a dark one. For runs that set a background but no colour, where
 * inheriting the theme's text would put light-grey text on a yellow highlight.
 */
fun contrastingTextOn(backgroundHex: String): String {
    val luminance = relativeLuminance(backgroundHex) ?: return DARK_TEXT
    return if (luminance > MID_LUMINANCE) DARK_TEXT else LIGHT_TEXT
}

/** WCAG relative luminance of a `#rrggbb` colour, 0 (black) to 1 (white). */
private fun relativeLuminance(hex: String): Double? {
    if (!HEX_LONG.matches(hex)) return null
    val channels = HEX_CHANNEL_STARTS.map { start ->
        val srgb = hex.substring(start, start + HEX_PAIR).toInt(HEX_RADIX) / MAX_CHANNEL.toDouble()
        if (srgb <= SRGB_LINEAR_LIMIT) {
            srgb / SRGB_LINEAR_DIVISOR
        } else {
            ((srgb + SRGB_OFFSET) / SRGB_SCALE).pow(SRGB_GAMMA)
        }
    }
    return LUMA_R * channels[0] + LUMA_G * channels[1] + LUMA_B * channels[2]
}

/** Normalises the colour syntaxes mail actually carries to `#rrggbb`, or null. */
private fun bodyCssColor(raw: String): String? {
    val value = raw.trim().lowercase().replace("!important", "").trim()
    return when {
        HEX_LONG.matches(value) -> value
        HEX_SHORT.matches(value) -> "#" + value.drop(1).map { "$it$it" }.joinToString("")
        value.startsWith("rgb") -> rgbHex(value)
        else -> NAMED_COLORS[value]
    }
}

private fun rgbHex(value: String): String? {
    val match = RGB_FUNC.matchEntire(value) ?: return null
    val channels = match.groupValues.drop(1).map { it.trim().toIntOrNull() }
    if (channels.any { it == null || it !in 0..MAX_CHANNEL }) return null
    return "#" + channels.joinToString("") { checkNotNull(it).toString(HEX_RADIX).padStart(2, '0') }
}

// -- the reader --------------------------------------------------------------

/**
 * A `<ul>` or `<ol>` being read: what to prefix each item with, and how far in.
 * Mutable because the counter advances per `<li>`.
 */
private class ListContext(val ordered: Boolean, val depth: Int) {
    var count = 0
}

/** Everything the enclosing tags have decided about the text being read. */
private data class Frame(
    val style: BodyStyle = BodyStyle(),
    val href: String? = null,
    /** Inside `<pre>`: whitespace is kept as written. */
    val preformatted: Boolean = false,
    /** Inside `display:none`: nothing is shown. */
    val hidden: Boolean = false,
    val list: ListContext? = null,
)

/** An element still open, and what closing it should do. */
private class OpenTag(val name: String, val frame: Frame, val closeBreak: Int)

/**
 * The parser's working state.
 *
 * Text is accumulated under the frame it was read in and flushed into a span
 * whenever the frame changes. Line breaks and spaces are *pending* rather than
 * written, so a run of block boundaries yields at most one blank line and a
 * body never starts with one — the same collapsing a browser does.
 */
private class BodyReader {
    private val spans = mutableListOf<BodySpan>()
    private val buffer = StringBuilder()
    private var bufferFrame: Frame? = null
    private val open = mutableListOf<OpenTag>()

    private var pendingBreaks = 0

    /** A collapsed space waiting for the next word, and the frame it was read in. */
    private var pendingSpace: Frame? = null
    private var atLineStart = true
    private var started = false

    /** Set after a list bullet, so `<li><p>` keeps its text on the bullet's line. */
    private var swallowNextBreak = false

    private val frame: Frame get() = open.lastOrNull()?.frame ?: ROOT

    /** Reads whatever starts at [index] and returns how many characters it took. */
    fun consume(html: String, index: Int): Int {
        if (html[index] != '<') return consumeText(html, index)

        skippedBlock(html, index)?.let { return it }

        val close = html.indexOf('>', index)
        val looksLikeTag = index + 1 < html.length && html[index + 1].let { it.isLetter() || it in "/!?" }
        if (close < 0 || !looksLikeTag) {
            // A bare "<" — "a < b" written without escaping — is text.
            text('<')
            return 1
        }
        tag(html.substring(index + 1, close).trim())
        return close + 1 - index
    }

    private fun consumeText(html: String, index: Int): Int {
        val entity = html.entityAt(index)
        if (entity != null) {
            entity.first.forEach { text(it) }
            return entity.second
        }
        text(html[index])
        return 1
    }

    /** Comments, and elements whose contents are never shown. Returns the width skipped, or null. */
    private fun skippedBlock(html: String, index: Int): Int? {
        if (html.startsWith(COMMENT_OPEN, index)) {
            val end = html.indexOf(COMMENT_CLOSE, index + COMMENT_OPEN.length)
            return if (end < 0) html.length - index else end + COMMENT_CLOSE.length - index
        }
        val name = SKIPPED_ELEMENTS.firstOrNull { html.startsWithTag(it, index) } ?: return null
        val end = html.indexOf("</$name", index, ignoreCase = true)
        if (end < 0) return html.length - index
        val close = html.indexOf('>', end)
        return (if (close < 0) html.length else close + 1) - index
    }

    private fun tag(raw: String) {
        val isClosing = raw.startsWith('/')
        val selfClosing = raw.endsWith('/')
        val name = raw.trimStart('/').takeWhile { !it.isWhitespace() && it != '/' }.lowercase()
        if (name.isEmpty() || name.startsWith('!') || name.startsWith('?')) return

        when {
            name == "br" -> lineBreak(1)
            isClosing -> close(name)
            selfClosing || name in VOID_ELEMENTS -> lineBreak(breakFor(name, frame))
            else -> openTag(name, raw)
        }
    }

    private fun openTag(name: String, raw: String) {
        val parent = frame
        val opened = parent.styledBy(name, raw)
        val breaks = breakFor(name, parent)
        // A new item always starts its own line, even after an empty one.
        if (name == "li") swallowNextBreak = false
        lineBreak(breaks)
        open += OpenTag(name, opened, closeBreak = breaks)

        if (name == "li") listItem(opened)
    }

    /** Pops back through the matching open tag; a stray close is ignored. */
    private fun close(name: String) {
        val at = open.indexOfLast { it.name == name }
        if (at < 0) return
        while (open.size > at) {
            val closed = open.removeAt(open.lastIndex)
            lineBreak(closed.closeBreak)
            // A cell boundary is at least a space, or "Name" and "Value" in
            // neighbouring cells arrive as one word.
            if (closed.name in CELL_ELEMENTS) pendingSpace = frame
        }
    }

    private fun listItem(itemFrame: Frame) {
        val list = itemFrame.list ?: return
        if (itemFrame.hidden) return
        list.count++
        val marker = if (list.ordered) "${list.count}." else BULLETS[minOf(list.depth, BULLETS.size) - 1]
        raw(INDENT.repeat(list.depth - 1) + marker, itemFrame)
        pendingSpace = itemFrame
        swallowNextBreak = true
    }

    // -- text ------------------------------------------------------------------

    private fun lineBreak(count: Int) {
        if (count <= 0 || swallowNextBreak) return
        pendingBreaks = maxOf(pendingBreaks, count)
    }

    private fun text(character: Char) {
        val current = frame
        if (current.hidden) return
        if (current.preformatted) {
            preformatted(character, current)
            return
        }
        if (character.isWhitespace()) {
            pendingSpace = current
            return
        }
        emitPendingBreaks(current)
        emitPendingSpace()
        ensureFrame(current)
        buffer.append(character)
        mark()
    }

    /** Text the reader itself adds — list markers — bypassing whitespace collapsing. */
    private fun raw(text: String, current: Frame) {
        emitPendingBreaks(current)
        pendingSpace = null
        ensureFrame(current)
        buffer.append(text)
        mark()
    }

    private fun preformatted(character: Char, current: Frame) {
        if (character == '\r') return
        // The newline straight after `<pre>` is markup, not content — browsers
        // drop it too, and the block break before the element already starts
        // the line.
        if (character == '\n' && (pendingBreaks > 0 || !started)) return
        emitPendingBreaks(current)
        pendingSpace = null
        ensureFrame(current)
        buffer.append(character)
        if (character == '\n') atLineStart = true else mark()
    }

    private fun mark() {
        atLineStart = false
        started = true
        swallowNextBreak = false
    }

    /**
     * Writes the pending breaks under a plain frame at the block's quote depth.
     *
     * Plain rather than the text's own frame so a link never owns the blank
     * line before it — otherwise the whole empty line above a link is clickable.
     */
    private fun emitPendingBreaks(current: Frame) {
        if (pendingBreaks == 0) return
        // A space pending at a block boundary is dropped, not written: browsers
        // do the same, and "Hello \n" is a trailing space in plain text.
        pendingSpace = null
        if (started) {
            ensureFrame(Frame(style = BodyStyle(quoteDepth = current.style.quoteDepth)))
            repeat(pendingBreaks) { buffer.append('\n') }
        }
        pendingBreaks = 0
        atLineStart = true
    }

    /**
     * Writes a pending space under the frame it was read in — so the gap
     * between "Before" and a link stays plain, and a space inside `<b>` stays
     * bold, exactly as the markup had it.
     */
    private fun emitPendingSpace() {
        val owner = pendingSpace
        pendingSpace = null
        if (owner == null || atLineStart) return
        ensureFrame(owner)
        buffer.append(' ')
    }

    private fun ensureFrame(current: Frame) {
        if (bufferFrame == current) return
        flush()
        bufferFrame = current
    }

    private fun flush() {
        val held = bufferFrame
        if (buffer.isNotEmpty() && held != null) {
            spans += BodySpan(buffer.toString(), held.href, held.style)
        }
        buffer.clear()
    }

    fun finish(): List<BodySpan> {
        flush()
        return spans.merged()
    }
}

/** Adjacent runs drawn the same way become one, so a body is as few spans as it can be. */
private fun List<BodySpan>.merged(): List<BodySpan> {
    val out = mutableListOf<BodySpan>()
    forEach { span ->
        val last = out.lastOrNull()
        if (last != null && last.href == span.href && last.style == span.style) {
            out[out.lastIndex] = last.copy(text = last.text + span.text)
        } else {
            out += span
        }
    }
    return out.filter { it.text.isNotEmpty() }
}

/** Splits bare `http(s)://…` inside unlinked runs into link spans of their own. */
private fun List<BodySpan>.linkifyBareUrls(): List<BodySpan> = flatMap { span ->
    if (span.isLink) return@flatMap listOf(span)
    val pieces = mutableListOf<BodySpan>()
    var cursor = 0
    BARE_URL.findAll(span.text).forEach { match ->
        val url = match.value.trimEnd { it in URL_TRAILING_PUNCTUATION }
        if (match.range.first > cursor) pieces += span.copy(text = span.text.substring(cursor, match.range.first))
        pieces += span.copy(text = url, href = url)
        cursor = match.range.first + url.length
    }
    if (cursor < span.text.length) pieces += span.copy(text = span.text.substring(cursor))
    pieces
}

// -- what each tag means ---------------------------------------------------------

/** The frame inside [name], given the frame outside it and the tag's attributes. */
private fun Frame.styledBy(name: String, raw: String): Frame {
    val byTag = markedBy(name) ?: structuredBy(name, raw) ?: this
    return byTag.styledByCss(raw.attribute(ATTR_STYLE).orEmpty())
}

/** The inline marks: how a run is drawn. */
private fun Frame.markedBy(name: String): Frame? = when (name) {
    "b", "strong", "th" -> copy(style = style.copy(bold = true))
    "i", "em", "cite", "dfn" -> copy(style = style.copy(italic = true))
    "u", "ins" -> copy(style = style.copy(underline = true))
    "s", "strike", "del" -> copy(style = style.copy(strike = true))
    "code", "tt", "kbd", "samp" -> copy(style = style.copy(monospace = true))
    "mark" -> copy(style = style.copy(background = MARK_BACKGROUND))
    "small" -> scaled(SMALL_SCALE)
    "big" -> scaled(BIG_SCALE)
    "sub", "sup" -> scaled(SUB_SCALE)
    else -> null
}

/** The structural tags: links, fonts, quotes, lists, headings, preformatted blocks. */
private fun Frame.structuredBy(name: String, raw: String): Frame? = when (name) {
    "a" -> copy(href = raw.attribute(ATTR_HREF)?.takeIf { it.isNotBlank() } ?: href)
    "font" -> fontTag(raw)
    "pre" -> copy(style = style.copy(monospace = true), preformatted = true)
    "blockquote" -> copy(style = style.copy(quoteDepth = style.quoteDepth + 1))
    "ul", "ol" -> copy(list = ListContext(ordered = name == "ol", depth = (list?.depth ?: 0) + 1))
    else -> HEADING_SCALES[name]?.let { copy(style = style.copy(bold = true, sizeScale = style.sizeScale * it)) }
}

private fun Frame.scaled(by: Float): Frame = copy(style = style.copy(sizeScale = style.sizeScale * by))

private fun Frame.fontTag(raw: String): Frame {
    var next = style
    raw.attribute(ATTR_COLOR)?.let { bodyCssColor(it) }?.let { next = next.copy(color = it) }
    raw.attribute(ATTR_SIZE)?.let { fontSizeScale(it) }?.let { next = next.copy(sizeScale = it) }
    raw.attribute(ATTR_FACE)?.let { if (it.isMonospaceFamily()) next = next.copy(monospace = true) }
    return copy(style = next)
}

/** `<font size>`: 1–7 absolute, or `+n`/`-n` from the default of 3. */
private fun fontSizeScale(value: String): Float? {
    val trimmed = value.trim()
    val number = trimmed.toIntOrNull() ?: return null
    val step = if (trimmed.startsWith('+') || trimmed.startsWith('-')) FONT_DEFAULT_STEP + number else number
    return FONT_STEP_SCALES[step.coerceIn(1, FONT_STEP_SCALES.size) - 1]
}

/** Applies a `style="…"` declaration list; unknown properties are ignored. */
private fun Frame.styledByCss(css: String): Frame {
    if (css.isBlank()) return this
    var next = this
    css.split(';').forEach { declaration ->
        val key = declaration.substringBefore(':').trim().lowercase()
        val value = declaration.substringAfter(':', "").replace("!important", "").trim().lowercase()
        if (key.isNotEmpty() && value.isNotEmpty()) {
            next = next.withColorCss(key, value) ?: next.withTypeCss(key, value) ?: next
        }
    }
    return next
}

/** Colour and size declarations; null when [key] is not one of them or the value is unreadable. */
private fun Frame.withColorCss(key: String, value: String): Frame? = when (key) {
    "color" -> bodyCssColor(value)?.let { copy(style = style.copy(color = it)) }
    "background-color", "background" -> bodyCssColor(value)?.let { copy(style = style.copy(background = it)) }
    "font-size" -> cssFontScale(value, style.sizeScale)?.let { copy(style = style.copy(sizeScale = it)) }
    else -> null
}

/** Weight, slant, decoration, family and visibility; null when [key] is not one of them. */
private fun Frame.withTypeCss(key: String, value: String): Frame? = when (key) {
    "font-weight" -> copy(style = style.copy(bold = value.weightIsBold()))
    "font-style" -> copy(style = style.copy(italic = value == "italic" || value == "oblique"))
    "text-decoration", "text-decoration-line" -> copy(style = style.decoratedBy(value))
    "font-family" -> if (value.isMonospaceFamily()) copy(style = style.copy(monospace = true)) else this
    "display" -> if (value == "none") copy(hidden = true) else this
    else -> null
}

/** `text-decoration` adds to what is on; only `none` takes anything off. */
private fun BodyStyle.decoratedBy(value: String): BodyStyle {
    val cleared = "none" in value
    return copy(
        underline = "underline" in value || (underline && !cleared),
        strike = "line-through" in value || (strike && !cleared),
    )
}

private fun String.weightIsBold(): Boolean =
    this == "bold" || this == "bolder" || (toIntOrNull() ?: 0) >= BOLD_WEIGHT

private fun String.isMonospaceFamily(): Boolean = MONOSPACE_FAMILIES.any { lowercase().contains(it) }

/**
 * A CSS `font-size` as a multiple of the body size.
 *
 * Absolute units are read against a 16px body, which is what every mail
 * client's default resolves to; relative units multiply the enclosing scale,
 * as they do in CSS. Clamped so a stray `font-size: 200pt` cannot fill the pane.
 */
private fun cssFontScale(value: String, parent: Float): Float? {
    val scale = FONT_KEYWORD_SCALES[value]
        ?: relativeKeywordScale(value, parent)
        ?: cssLengthScale(value, parent)
    return scale?.coerceIn(MIN_SCALE, MAX_SCALE)
}

private fun relativeKeywordScale(value: String, parent: Float): Float? = when (value) {
    "smaller" -> parent * SMALL_SCALE
    "larger" -> parent * BIG_SCALE
    else -> null
}

private fun cssLengthScale(value: String, parent: Float): Float? {
    val match = CSS_LENGTH.matchEntire(value) ?: return null
    val number = match.groupValues[1].toFloatOrNull() ?: return null
    return when (match.groupValues[2]) {
        "px", "" -> number / BASE_PX
        "pt" -> number * PX_PER_PT / BASE_PX
        "em" -> number * parent
        "rem" -> number
        "%" -> number / PERCENT * parent
        else -> null
    }
}

/** How many line breaks [name] puts around itself, given where it sits. */
private fun breakFor(name: String, parent: Frame): Int {
    val base = BLOCK_BREAKS[name] ?: return 0
    return when {
        // A list separates itself from the text around it, but nested lists
        // and blocks inside an item stay tight, as they do in a browser.
        name == "ul" || name == "ol" -> if (parent.list != null) 1 else 2
        parent.list != null -> 1
        else -> base
    }
}

/** The value of `name="…"` in a tag, unquoted; null when absent. */
private fun String.attribute(name: String): String? {
    val match = ATTRIBUTE_PATTERNS.getValue(name).find(this) ?: return null
    return match.groupValues.drop(1).firstOrNull { it.isNotEmpty() } ?: ""
}

private fun String.startsWithTag(name: String, index: Int): Boolean {
    if (!regionMatches(index + 1, name, 0, name.length, ignoreCase = true)) return false
    val after = getOrNull(index + 1 + name.length) ?: return false
    return after == '>' || after == '/' || after.isWhitespace()
}

/** Returns the decoded text and how many source characters the entity spanned. */
private fun String.entityAt(index: Int): Pair<String, Int>? {
    if (this[index] != '&') return null
    val semicolon = indexOf(';', index)
    if (semicolon < 0 || semicolon - index > MAX_ENTITY) return null
    val entity = substring(index, semicolon + 1)
    val decoded = when {
        entity.startsWith("&#x", ignoreCase = true) ->
            entity.drop(HEX_ENTITY_PREFIX).dropLast(1).toIntOrNull(HEX_RADIX)?.codePointText()
        entity.startsWith("&#") -> entity.drop(2).dropLast(1).toIntOrNull()?.codePointText()
        else -> ENTITIES[entity.lowercase()]
    } ?: return null
    return decoded to entity.length
}

private fun Int.codePointText(): String? =
    if (this in 1..MAX_CODE_POINT) buildString { appendCodePoint(this@codePointText) } else null

private fun StringBuilder.appendCodePoint(codePoint: Int) {
    if (codePoint < SUPPLEMENTARY_START) {
        append(codePoint.toChar())
    } else {
        val offset = codePoint - SUPPLEMENTARY_START
        append((HIGH_SURROGATE_BASE + (offset shr SURROGATE_SHIFT)).toChar())
        append((LOW_SURROGATE_BASE + (offset and SURROGATE_MASK)).toChar())
    }
}

private fun String.collapseBlankLines(): String = replace(MANY_NEWLINES, "\n\n")

// -- tables ----------------------------------------------------------------------

private val ROOT = Frame()

private const val COMMENT_OPEN = "<!--"
private const val COMMENT_CLOSE = "-->"

/** Elements whose contents are never text: markup, scripts, the head. */
private val SKIPPED_ELEMENTS = listOf("script", "style", "head", "title", "noscript", "template")

private val VOID_ELEMENTS = setOf(
    "img", "hr", "input", "meta", "link", "area", "base", "col", "embed", "param", "source", "track", "wbr",
)

private val CELL_ELEMENTS = setOf("td", "th")

/** Two breaks is a blank line — a paragraph; one is a line break. */
private val BLOCK_BREAKS = mapOf(
    "p" to 2, "h1" to 2, "h2" to 2, "h3" to 2, "h4" to 2, "h5" to 2, "h6" to 2,
    "blockquote" to 2, "pre" to 2, "table" to 2, "ul" to 2, "ol" to 2, "hr" to 1,
    "div" to 1, "li" to 1, "tr" to 1, "dt" to 1, "dd" to 1, "address" to 1, "center" to 1,
    "section" to 1, "article" to 1, "header" to 1, "footer" to 1, "nav" to 1, "aside" to 1,
    "form" to 1, "fieldset" to 1, "figure" to 1, "figcaption" to 1, "caption" to 1,
)

/** Browser default heading sizes, as multiples of the body. */
private val HEADING_SCALES = mapOf(
    "h1" to 2f, "h2" to 1.5f, "h3" to 1.17f, "h4" to 1f, "h5" to 0.83f, "h6" to 0.67f,
)

/** `<font size=1..7>` as browsers draw it, against a 16px body. */
private val FONT_STEP_SCALES = listOf(0.63f, 0.82f, 1f, 1.13f, 1.5f, 2f, 3f)
private const val FONT_DEFAULT_STEP = 3

private val FONT_KEYWORD_SCALES = mapOf(
    "xx-small" to 0.5625f, "x-small" to 0.625f, "small" to 0.8125f, "medium" to 1f,
    "large" to 1.125f, "x-large" to 1.5f, "xx-large" to 2f, "xxx-large" to 3f,
)

private val MONOSPACE_FAMILIES = listOf("monospace", "courier", "consolas", "menlo", "monaco", "mono")

private val BULLETS = listOf("•", "◦", "▪")
private const val INDENT = "   "
private const val MARK_BACKGROUND = "#ffff00"

private const val SMALL_SCALE = 0.83f
private const val BIG_SCALE = 1.2f
private const val SUB_SCALE = 0.75f
private const val MIN_SCALE = 0.5f
private const val MAX_SCALE = 3f
private const val BASE_PX = 16f
private const val PX_PER_PT = 4f / 3f
private const val PERCENT = 100f
private const val BOLD_WEIGHT = 600

private const val DARK_TEXT = "#1a1a1a"
private const val LIGHT_TEXT = "#f2f2f2"
/** Where `#rrggbb`'s three channels start, and how long each is. */
private val HEX_CHANNEL_STARTS = listOf(1, 3, 5)
private const val HEX_PAIR = 2

/** `&#x` — the three characters before a hexadecimal entity's digits. */
private const val HEX_ENTITY_PREFIX = 3

/** Above this the colour is a choice, not an accident — see [vanishesOnTheme]. */
private const val NEUTRAL_SATURATION = 0.25

private const val DARK_THEME_FLOOR = 0.15
private const val LIGHT_THEME_CEILING = 0.5
private const val MID_LUMINANCE = 0.4
private const val SRGB_LINEAR_LIMIT = 0.03928
private const val SRGB_LINEAR_DIVISOR = 12.92
private const val SRGB_OFFSET = 0.055
private const val SRGB_SCALE = 1.055
private const val SRGB_GAMMA = 2.4
private const val LUMA_R = 0.2126
private const val LUMA_G = 0.7152
private const val LUMA_B = 0.0722

private const val HEX_RADIX = 16
private const val MAX_CHANNEL = 255
private const val MAX_ENTITY = 10
private const val MAX_CODE_POINT = 0x10FFFF
private const val SUPPLEMENTARY_START = 0x10000
private const val HIGH_SURROGATE_BASE = 0xD800
private const val LOW_SURROGATE_BASE = 0xDC00
private const val SURROGATE_SHIFT = 10
private const val SURROGATE_MASK = 0x3FF

private const val ATTR_HREF = "href"
private const val ATTR_STYLE = "style"
private const val ATTR_COLOR = "color"
private const val ATTR_SIZE = "size"
private const val ATTR_FACE = "face"

/** Compiled once: a long newsletter has thousands of tags. */
private val ATTRIBUTE_PATTERNS = listOf(ATTR_HREF, ATTR_STYLE, ATTR_COLOR, ATTR_SIZE, ATTR_FACE).associateWith {
    Regex("""\b$it\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s>]+))""", RegexOption.IGNORE_CASE)
}

private val HEX_LONG = Regex("""#[0-9a-f]{6}""")
private val HEX_SHORT = Regex("""#[0-9a-f]{3}""")
private val RGB_FUNC = Regex("""rgba?\(([^,]+),([^,]+),([^,)]+)(?:,[^)]*)?\)""")
private val CSS_LENGTH = Regex("""([0-9]*\.?[0-9]+)\s*(px|pt|em|rem|%)?""")
private val MANY_NEWLINES = Regex("\n{3,}")
private val BARE_URL = Regex("""https?://[^\s<>"'\]]+""", RegexOption.IGNORE_CASE)
private const val URL_TRAILING_PUNCTUATION = ".,;:!?)"

private val NAMED_COLORS = mapOf(
    "black" to "#000000", "white" to "#ffffff", "red" to "#ff0000", "green" to "#008000",
    "blue" to "#0000ff", "yellow" to "#ffff00", "orange" to "#ffa500", "purple" to "#800080",
    "gray" to "#808080", "grey" to "#808080", "silver" to "#c0c0c0", "maroon" to "#800000",
    "navy" to "#000080", "teal" to "#008080", "olive" to "#808000", "lime" to "#00ff00",
    "aqua" to "#00ffff", "cyan" to "#00ffff", "fuchsia" to "#ff00ff", "magenta" to "#ff00ff",
    "brown" to "#a52a2a", "pink" to "#ffc0cb", "gold" to "#ffd700", "darkred" to "#8b0000",
    "darkgreen" to "#006400", "darkblue" to "#00008b", "lightgray" to "#d3d3d3", "lightgrey" to "#d3d3d3",
    "darkgray" to "#a9a9a9", "darkgrey" to "#a9a9a9", "violet" to "#ee82ee", "indigo" to "#4b0082",
)

private val ENTITIES = mapOf(
    "&nbsp;" to " ", "&amp;" to "&", "&lt;" to "<", "&gt;" to ">",
    "&quot;" to "\"", "&#39;" to "'", "&apos;" to "'", "&mdash;" to "—",
    "&ndash;" to "–", "&hellip;" to "…", "&rsquo;" to "'", "&lsquo;" to "'",
    "&ldquo;" to "\"", "&rdquo;" to "\"", "&copy;" to "©", "&reg;" to "®",
    "&trade;" to "™", "&bull;" to "•", "&middot;" to "·", "&laquo;" to "«",
    "&raquo;" to "»", "&euro;" to "€", "&pound;" to "£", "&yen;" to "¥",
    "&cent;" to "¢", "&deg;" to "°", "&times;" to "×", "&divide;" to "÷",
    "&frac12;" to "½", "&frac14;" to "¼", "&frac34;" to "¾", "&para;" to "¶",
    "&sect;" to "§", "&iexcl;" to "¡", "&iquest;" to "¿", "&zwnj;" to "", "&zwj;" to "",
    "&ensp;" to " ", "&emsp;" to " ", "&thinsp;" to " ",
)
