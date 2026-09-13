package com.zillit.desktop.feature.documentdistribution.domain

/**
 * The little HTML the composer needs, ported from the web's inline helpers.
 *
 * Bodies travel as HTML — templates, signatures and past sends all carry
 * markup — but the desktop edits them as text. Plain text becomes paragraphs
 * on the way out, and markup becomes readable text on the way in and in
 * every preview.
 */
object HtmlText {

    fun looksLikeHtml(value: String?): Boolean =
        value != null && TAG.containsMatchIn(value)

    fun escape(value: String?): String = value.orEmpty()
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

    /** Blank-line separated paragraphs → `<p>…<br>…</p>`. */
    fun plainToHtml(text: String?): String {
        if (text.isNullOrEmpty()) return ""
        return text.split(PARAGRAPH_BREAK)
            .joinToString("") { paragraph -> "<p>${escape(paragraph).replace("\n", "<br>")}</p>" }
    }

    /** What the editor holds: the HTML as-is, or plain text promoted to it. */
    fun toEditorHtml(value: String?): String = if (looksLikeHtml(value)) value.orEmpty() else plainToHtml(value)

    /**
     * Markup → text with the block structure kept as line breaks, so an
     * HTML template reads in the desktop's text editor and a plain body
     * round-trips unchanged.
     */
    fun toPlainText(html: String?): String {
        if (html.isNullOrEmpty()) return ""
        if (!looksLikeHtml(html)) return html
        return html
            .replace(STYLE_BLOCK, "")
            .replace(SCRIPT_BLOCK, "")
            .replace(LINE_BREAK, "\n")
            // A paragraph ends with a blank line, so [plainToHtml] rebuilds it as one.
            .replace(PARAGRAPH_CLOSE, "\n\n")
            .replace(BLOCK_CLOSE, "\n")
            .replace(TAG, "")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .lines()
            .joinToString("\n") { it.trim() }
            .replace(EXCESS_BLANK_LINES, "\n\n")
            .trim()
    }

    /** One line, [max] characters, for a list row. */
    fun snippet(html: String?, max: Int = SNIPPET_LENGTH): String {
        val text = toPlainText(html).replace(WHITESPACE, " ").trim()
        return if (text.length > max) text.take(max).trim() + "…" else text
    }

    /** Appends a signature beneath a body, spaced the way the mail composer does. */
    fun withSignature(bodyHtml: String, signatureHtml: String): String =
        if (signatureHtml.isBlank()) bodyHtml else "$bodyHtml<br/><br/><div>$signatureHtml</div>"

    private val TAG = Regex("</?[a-zA-Z][^>]*>")
    private val STYLE_BLOCK = Regex("<style[\\s\\S]*?</style>", RegexOption.IGNORE_CASE)
    private val SCRIPT_BLOCK = Regex("<script[\\s\\S]*?</script>", RegexOption.IGNORE_CASE)
    private val LINE_BREAK = Regex("<br\\s*/?>", RegexOption.IGNORE_CASE)
    private val PARAGRAPH_CLOSE = Regex("</p>", RegexOption.IGNORE_CASE)
    private val BLOCK_CLOSE = Regex("</(div|h[1-6]|li|tr)>", RegexOption.IGNORE_CASE)
    private val PARAGRAPH_BREAK = Regex("\n{2,}")
    private val EXCESS_BLANK_LINES = Regex("\n{3,}")
    private val WHITESPACE = Regex("\\s+")
    private const val SNIPPET_LENGTH = 100
}
