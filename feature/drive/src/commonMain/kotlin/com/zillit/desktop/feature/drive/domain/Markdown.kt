package com.zillit.desktop.feature.drive.domain

/**
 * Markdown as blocks and spans, for Drive's text viewer.
 *
 * ## Why this exists at all
 *
 * The web renders a `.md` file by loading `marked` and sanitising the HTML it
 * produces with DOMPurify (`markdownUtils.js`). There is no HTML surface here
 * to sanitise — the viewer is Compose — so the text is parsed to a small tree
 * the view draws directly. That also disposes of the whole sanitising problem:
 * nothing from the file can become markup, because nothing from the file is
 * ever interpreted as anything but text, a weight and, for a link, an address.
 *
 * ## What it understands
 *
 * GitHub-flavoured Markdown as people actually write it in a production's
 * notes: ATX headings, paragraphs, bullet and numbered lists, block quotes,
 * fenced and indented code, horizontal rules, and inline emphasis, code and
 * links. Deliberately not a complete CommonMark implementation — tables,
 * footnotes, reference links and nested lists past one level are shown as the
 * text they are rather than guessed at, which is the honest failure for a
 * viewer whose source is one click away.
 */
sealed interface MarkdownBlock {

    /** `# Heading` — [level] is 1 to 6. */
    data class Heading(val level: Int, val spans: List<MarkdownSpan>) : MarkdownBlock

    data class Paragraph(val spans: List<MarkdownSpan>) : MarkdownBlock

    /** One list item. [marker] is the bullet or the number the writer used. */
    data class ListItem(val marker: String, val spans: List<MarkdownSpan>) : MarkdownBlock

    /** `> quoted` — consecutive quoted lines are one block. */
    data class Quote(val spans: List<MarkdownSpan>) : MarkdownBlock

    /** A fenced or indented code block, kept exactly as written. */
    data class Code(val text: String, val language: String = "") : MarkdownBlock

    /** `---`, `***`, `___`. */
    data object Rule : MarkdownBlock
}

/**
 * A run of text with the weights that apply to it, and the address if it is a
 * link. Flat on purpose: a span is what one `AnnotatedString` style covers.
 */
data class MarkdownSpan(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val code: Boolean = false,
    /** The link's target, or null when this run is not a link. */
    val href: String? = null,
)

/** Parses [text] into the blocks the viewer draws, in document order. */
fun parseMarkdown(text: String): List<MarkdownBlock> =
    MarkdownReader(text.replace("\r\n", "\n").replace('\r', '\n').split('\n')).read()

/**
 * One pass down the lines.
 *
 * A class rather than a long function because two blocks run across lines —
 * a paragraph and a quote are joined until something ends them — and that
 * state is what the "flush" calls below are about.
 */
private class MarkdownReader(private val lines: List<String>) {

    private val blocks = mutableListOf<MarkdownBlock>()
    private val paragraph = mutableListOf<String>()
    private val quote = mutableListOf<String>()

    fun read(): List<MarkdownBlock> {
        var index = 0
        while (index < lines.size) index = step(index)
        flush()
        return blocks
    }

    /** Reads whatever starts at [index] and answers where the next block starts. */
    private fun step(index: Int): Int {
        val line = lines[index]
        val trimmed = line.trim()
        return when {
            trimmed.isEmpty() -> {
                flush()
                index + 1
            }

            FENCE.matchAt(trimmed, 0) != null -> fenced(index, trimmed)
            // Four spaces or a tab, outside a paragraph: a code block, which
            // is how most writers paste a command into a note.
            isIndentedCode(line) -> indentedCode(index)
            else -> {
                line(trimmed)
                index + 1
            }
        }
    }

    /** One ordinary line, which may continue a paragraph or a quote. */
    private fun line(trimmed: String) {
        when {
            RULE.matches(trimmed) -> {
                flush()
                blocks += MarkdownBlock.Rule
            }

            HEADING.matches(trimmed) -> heading(trimmed)
            trimmed.startsWith(">") -> {
                flushParagraph()
                quote += trimmed.removePrefix(">").trim()
            }

            BULLET.matchAt(trimmed, 0) != null -> item(trimmed, BULLET, BULLET_DOT)
            ORDERED.matchAt(trimmed, 0) != null -> item(trimmed, ORDERED, marker = null)
            else -> {
                flushQuote()
                paragraph += trimmed
            }
        }
    }

    private fun heading(trimmed: String) {
        flush()
        val hashes = trimmed.takeWhile { it == '#' }
        blocks += MarkdownBlock.Heading(
            level = hashes.length.coerceAtMost(MAX_HEADING_LEVEL),
            spans = parseInlineMarkdown(trimmed.removePrefix(hashes).trim().trimEnd('#').trim()),
        )
    }

    /** A list item; [marker] is null for a numbered one, which keeps its number. */
    private fun item(trimmed: String, pattern: Regex, marker: String?) {
        flush()
        val matched = pattern.matchAt(trimmed, 0)?.value.orEmpty()
        blocks += MarkdownBlock.ListItem(
            marker = marker ?: matched.trim(),
            spans = parseInlineMarkdown(trimmed.removePrefix(matched)),
        )
    }

    private fun fenced(index: Int, opening: String): Int {
        flush()
        var cursor = index + 1
        val body = mutableListOf<String>()
        while (cursor < lines.size && FENCE.matchAt(lines[cursor].trim(), 0) == null) {
            body += lines[cursor]
            cursor++
        }
        blocks += MarkdownBlock.Code(body.joinToString("\n"), opening.trimStart('`', '~').trim())
        return cursor + 1
    }

    private fun indentedCode(index: Int): Int {
        var cursor = index
        val body = mutableListOf<String>()
        while (cursor < lines.size && isIndented(lines[cursor])) {
            body += lines[cursor].removePrefix("\t").removePrefix(INDENT)
            cursor++
        }
        blocks += MarkdownBlock.Code(body.joinToString("\n"))
        return cursor
    }

    private fun isIndentedCode(line: String) = paragraph.isEmpty() && quote.isEmpty() && isIndented(line)

    private fun isIndented(line: String) = line.startsWith(INDENT) || line.startsWith("\t")

    private fun flush() {
        flushParagraph()
        flushQuote()
    }

    private fun flushParagraph() {
        if (paragraph.isEmpty()) return
        blocks += MarkdownBlock.Paragraph(parseInlineMarkdown(paragraph.joinToString(" ")))
        paragraph.clear()
    }

    private fun flushQuote() {
        if (quote.isEmpty()) return
        blocks += MarkdownBlock.Quote(parseInlineMarkdown(quote.joinToString(" ")))
        quote.clear()
    }
}

/**
 * One line (or joined paragraph) as spans: `**bold**`, `*italic*`,
 * `` `code` `` and `[text](url)`, in one pass.
 *
 * Code wins over emphasis, as every Markdown parser has it — `` `**x**` ``
 * is two asterisks and an x — and an unmatched marker stays the character it
 * is rather than swallowing the rest of the line.
 */
fun parseInlineMarkdown(text: String): List<MarkdownSpan> = InlineReader(text).read()

/** The inline pass. A class for the same reason [MarkdownReader] is one. */
private class InlineReader(private val text: String) {

    private val spans = mutableListOf<MarkdownSpan>()
    private val plain = StringBuilder()
    private var bold = false
    private var italic = false

    fun read(): List<MarkdownSpan> {
        var index = 0
        while (index < text.length) index += step(text.substring(index))
        flush()
        return spans.filter { it.text.isNotEmpty() }
    }

    /** Reads whatever starts the rest of the line; answers how much it took. */
    private fun step(rest: String): Int = when {
        rest.startsWith("`") -> code(rest)
        rest.startsWith("[") -> link(rest)
        rest.startsWith("**") || rest.startsWith("__") -> {
            flush()
            bold = !bold
            2
        }

        rest.startsWith("*") || rest.startsWith("_") -> {
            flush()
            italic = !italic
            1
        }

        // An escaped marker is the character itself.
        rest.startsWith("\\") && rest.length > 1 -> {
            plain.append(rest[1])
            2
        }

        else -> {
            plain.append(rest[0])
            1
        }
    }

    private fun code(rest: String): Int {
        val close = rest.indexOf('`', startIndex = 1)
        if (close < 0) {
            plain.append('`')
            return 1
        }
        flush()
        spans += MarkdownSpan(rest.substring(1, close), code = true)
        return close + 1
    }

    private fun link(rest: String): Int {
        val matched = LINK.matchAt(rest, 0)
        if (matched == null) {
            plain.append('[')
            return 1
        }
        flush()
        val (label, href) = matched.destructured
        spans += MarkdownSpan(
            text = label.ifBlank { href },
            bold = bold,
            italic = italic,
            href = href.trim(),
        )
        return matched.value.length
    }

    private fun flush() {
        if (plain.isEmpty()) return
        spans += MarkdownSpan(plain.toString(), bold = bold, italic = italic)
        plain.clear()
    }
}

/** What a bullet is drawn as, whatever the writer typed. */
private const val BULLET_DOT = "•"
private const val MAX_HEADING_LEVEL = 6
private const val INDENT = "    "
private val FENCE = Regex("(```|~~~).*")
private val RULE = Regex("^([-*_])\\1{2,}$")
private val HEADING = Regex("^#{1,6}\\s+.*$")
private val BULLET = Regex("^[-*+]\\s+")
private val ORDERED = Regex("^\\d{1,9}[.)]\\s+")
private val LINK = Regex("""\[([^\]]*)]\(([^)\s]+)[^)]*\)""")
