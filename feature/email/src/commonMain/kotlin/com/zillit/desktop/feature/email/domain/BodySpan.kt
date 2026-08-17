package com.zillit.desktop.feature.email.domain

/**
 * A run of body text, and whether it is a link.
 *
 * Emitted rather than styled here so the design system decides how a link
 * looks — and so this whole conversion is testable without Compose.
 */
data class BodySpan(val text: String, val href: String? = null) {
    val isLink: Boolean get() = href != null
}

/**
 * Turns an HTML mail body into readable text with its links intact.
 *
 * ## Why not render the HTML
 *
 * Compose has no HTML renderer, and mail HTML is the worst HTML there is —
 * nested tables, inline CSS, tracking pixels. The plan's answer is an embedded
 * browser (KCEF, M6/M8); until that lands, a faithful *text* conversion is
 * better than a broken *visual* one. The web does the same in its trail item
 * (`html-to-text`).
 *
 * ## What is deliberately dropped
 *
 * `<script>` and `<style>` contents are removed entirely rather than shown as
 * text — otherwise a styled newsletter renders as a wall of CSS. Remote images
 * are not fetched at all, which also happens to defeat tracking pixels.
 */
fun htmlToSpans(html: String): List<BodySpan> {
    if (html.isBlank()) return emptyList()

    val cleaned = html
        .replace(SCRIPT_OR_STYLE, " ")
        .replace(BREAK_TAGS, "\n")
        .replace(BLOCK_END_TAGS, "\n\n")

    val spans = mutableListOf<BodySpan>()
    var cursor = 0

    ANCHOR.findAll(cleaned).forEach { match ->
        val before = cleaned.substring(cursor, match.range.first)
        spans += plain(before)

        val href = match.groupValues[1].trim().trim('"', '\'')
        val label = match.groupValues[2].stripTags().collapse()
        if (label.isNotBlank()) {
            // A bare URL as its own label reads better than the raw href when
            // the two differ only by tracking parameters.
            spans += BodySpan(label, href.takeIf { it.isNotBlank() })
        }
        cursor = match.range.last + 1
    }

    spans += plain(cleaned.substring(cursor))

    return spans.filter { it.text.isNotEmpty() }
}

/** The same body as one string, for snippets and search. */
fun htmlToPlainText(html: String): String =
    htmlToSpans(html).joinToString("") { it.text }.collapseBlankLines().trim()

private fun plain(raw: String): List<BodySpan> {
    val text = raw.stripTags().unescape().collapse()
    return if (text.isEmpty()) emptyList() else listOf(BodySpan(text))
}

private fun String.stripTags(): String = replace(TAG, "")

private fun String.unescape(): String = ENTITIES.entries.fold(this) { acc, (entity, char) ->
    acc.replace(entity, char)
}.replace(NUMERIC_ENTITY) { match ->
    match.groupValues[1].toIntOrNull()?.toChar()?.toString() ?: match.value
}

/** Collapses runs of spaces but keeps line breaks — paragraphs carry meaning. */
private fun String.collapse(): String = replace(HORIZONTAL_SPACE, " ").collapseBlankLines()

private fun String.collapseBlankLines(): String = replace(MANY_NEWLINES, "\n\n")

private val SCRIPT_OR_STYLE = Regex(
    "<(script|style)[^>]*>.*?</\\1>",
    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
)
private val BREAK_TAGS = Regex("<br\\s*/?>", RegexOption.IGNORE_CASE)
private val BLOCK_END_TAGS = Regex("</(p|div|tr|li|h[1-6]|table)>", RegexOption.IGNORE_CASE)
private val ANCHOR = Regex("""<a\b[^>]*href\s*=\s*("[^"]*"|'[^']*'|[^\s>]+)[^>]*>(.*?)</a>""",
    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
private val TAG = Regex("<[^>]*>")
private val NUMERIC_ENTITY = Regex("&#(\\d{1,6});")
private val HORIZONTAL_SPACE = Regex("[ \\t\\u00A0]+")
private val MANY_NEWLINES = Regex("\n{3,}")

private val ENTITIES = mapOf(
    "&nbsp;" to " ", "&amp;" to "&", "&lt;" to "<", "&gt;" to ">",
    "&quot;" to "\"", "&#39;" to "'", "&apos;" to "'", "&mdash;" to "—",
    "&ndash;" to "–", "&hellip;" to "…", "&rsquo;" to "'", "&lsquo;" to "'",
    "&ldquo;" to "\"", "&rdquo;" to "\"",
)
