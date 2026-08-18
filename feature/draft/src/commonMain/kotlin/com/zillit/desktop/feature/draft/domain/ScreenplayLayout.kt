package com.zillit.desktop.feature.draft.domain

/**
 * The page a script is measured in: US Letter, Courier 12 — ten characters
 * to the inch, six lines to the inch, one page to the minute of screen time.
 *
 * The margins are the standard ones every reader expects: 1.5" on the left,
 * 1" elsewhere, dialogue in the middle third, character cues further in.
 * The same numbers drive the editor's indents, the page count in the
 * navigator, and the PDF — one source, so the estimate on screen is the
 * page the PDF prints.
 */
@Suppress("MagicNumber") // These ARE the numbers: the format's margins and widths, in inches and characters.
object ScreenplayLayout {

    /** Characters per inch at Courier 12. */
    const val CHARS_PER_INCH = 10

    /** Lines per inch. */
    const val LINES_PER_INCH = 6

    /** Body lines on a page: 11" minus 1" top and 1" bottom. */
    const val LINES_PER_PAGE = 54

    /** Page width in characters at 8.5". */
    const val PAGE_CHARS = 85

    /** Where an element starts, in inches from the page's left edge. */
    fun leftInches(type: ElementType): Double = when (type) {
        ElementType.SceneHeading, ElementType.Action, ElementType.Shot, ElementType.General -> 1.5
        ElementType.Character -> 3.7
        ElementType.Parenthetical -> 3.1
        ElementType.Dialogue -> 2.5
        ElementType.Transition -> 6.0
    }

    /** How wide the text runs, in characters. */
    fun widthChars(type: ElementType): Int = when (type) {
        ElementType.SceneHeading, ElementType.Action, ElementType.Shot, ElementType.General -> 60
        ElementType.Character -> 33
        ElementType.Parenthetical -> 20
        ElementType.Dialogue -> 35
        ElementType.Transition -> 20
    }

    /** Blank lines before an element of this type (none before the first). */
    fun spaceBefore(previous: ElementType?, type: ElementType): Int = when {
        previous == null -> 0
        // A speech is one block: cue, parenthetical and lines sit tight.
        type == ElementType.Parenthetical || type == ElementType.Dialogue -> 0
        else -> 1
    }

    /**
     * Word-wraps [text] to [width] characters. A word longer than the width
     * is broken rather than overflowing the margin.
     */
    @Suppress("NestedBlockDepth") // Paragraph → word → overflow; the loop is the algorithm.
    fun wrap(text: String, width: Int): List<String> {
        if (text.isEmpty()) return listOf("")
        val out = mutableListOf<String>()
        for (paragraph in text.split('\n')) {
            var line = StringBuilder()
            for (word in paragraph.split(' ')) {
                var w = word
                while (w.length > width) {
                    if (line.isNotEmpty()) {
                        out += line.toString()
                        line = StringBuilder()
                    }
                    out += w.take(width)
                    w = w.drop(width)
                }
                if (line.isEmpty()) {
                    line.append(w)
                } else if (line.length + 1 + w.length <= width) {
                    line.append(' ').append(w)
                } else {
                    out += line.toString()
                    line = StringBuilder(w)
                }
            }
            out += line.toString()
        }
        return out
    }

    /** Lines an element occupies, its own text only. */
    fun lines(element: ScriptElement): Int = wrap(element.text, widthChars(element.type)).size

    /**
     * Which page each element starts on, 1-based, and the total.
     *
     * A speech that would straddle a page break is not split mid-cue: if the
     * character cue lands on the last line, the whole speech moves down. Any
     * finer widow/orphan rules are the PDF's; this is what the navigator and
     * the list page show.
     */
    fun paginate(elements: List<ScriptElement>): Pagination {
        val starts = IntArray(elements.size)
        var page = 1
        var line = 0
        var previous: ElementType? = null
        elements.forEachIndexed { index, element ->
            val needed = spaceBefore(previous, element.type) + lines(element)
            val cueOnLastLine = element.type == ElementType.Character && line + needed >= LINES_PER_PAGE
            if (line + needed > LINES_PER_PAGE || cueOnLastLine) {
                page++
                line = lines(element)
            } else {
                line += needed
            }
            starts[index] = page
            previous = element.type
        }
        return Pagination(if (elements.isEmpty()) 1 else page, starts.toList())
    }

    data class Pagination(val pageCount: Int, val pageOfElement: List<Int>)
}
