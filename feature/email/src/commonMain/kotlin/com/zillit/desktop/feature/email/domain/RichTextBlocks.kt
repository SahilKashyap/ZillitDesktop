package com.zillit.desktop.feature.email.domain

/** The list marker a paragraph wears, if any. */
enum class BlockKind { Bullet, Ordered }

/**
 * A paragraph attribute over `[start, end)` — a list marker, an indent depth,
 * or both.
 *
 * Stored as character ranges rather than as a per-line array for one reason:
 * [RichText.withText] already knows how to carry a range across an edit, and a
 * line *number* does not survive a paste that adds three lines above it. The
 * ranges are kept line-aligned by [RichText.realignedBlocks], which every edit
 * ends with, so the two views stay interchangeable.
 */
data class Block(val start: Int, val end: Int, val kind: BlockKind? = null, val indent: Int = 0)

/** What one line looks like: its marker, and how far it is pushed. */
data class LineStyle(val kind: BlockKind? = null, val indent: Int = 0) {
    /** Carries nothing, so it need not be stored. */
    val isPlain: Boolean get() = kind == null && indent == 0
}

/** A line of [RichText.text] as `[start, end)`, the `\n` itself excluded. */
data class LineSpan(val start: Int, val end: Int)

/**
 * The web's ceiling (`IndentBlock.MAX_INDENT`). Matched rather than chosen:
 * indenting past what the other client can represent would be lost on the
 * round trip, which reads as the button having silently done nothing.
 */
const val MAX_INDENT = 8

/** Every line, in order. A trailing `\n` yields a final empty line, as editors show. */
fun RichText.lines(): List<LineSpan> = text.lineSpans()

internal fun String.lineSpans(): List<LineSpan> {
    val spans = mutableListOf<LineSpan>()
    var start = 0
    while (true) {
        val next = indexOf('\n', start)
        if (next < 0) {
            spans += LineSpan(start, length)
            return spans
        }
        spans += LineSpan(start, next)
        start = next + 1
    }
}

/**
 * The paragraph attribute of every line, one entry per line.
 *
 * A line takes the first block it overlaps — overlap, not containment, because
 * a block whose edge has just been pushed by an insertion at a line boundary
 * still belongs to that line. Tidying that up is what [RichText.realignedBlocks]
 * is for.
 */
fun RichText.lineStyles(): List<LineStyle> = lines().map { line ->
    val block = blocks.firstOrNull { it.covers(line) } ?: return@map LineStyle()
    LineStyle(block.kind, block.indent.coerceIn(0, MAX_INDENT))
}

private fun Block.covers(line: LineSpan): Boolean =
    if (line.start == line.end) {
        start <= line.start && end >= line.start
    } else {
        start < line.end && end > line.start
    }

/** Replaces every paragraph attribute at once, packing equal neighbours into one block. */
fun RichText.withLineStyles(styles: List<LineStyle>): RichText {
    val spans = lines()
    val packed = mutableListOf<Block>()

    spans.indices.forEach { index ->
        val style = styles.getOrElse(index) { LineStyle() }
        if (style.isPlain) return@forEach

        val last = packed.lastOrNull()
        if (last != null && last.runsInto(spans[index], style)) {
            packed[packed.lastIndex] = last.copy(end = spans[index].end)
        } else {
            packed += Block(spans[index].start, spans[index].end, style.kind, style.indent)
        }
    }

    return copy(blocks = packed)
}

/**
 * Whether this block simply carries on into [line].
 *
 * Only across *adjacent* lines wearing the same attribute: a plain line in
 * between has to break the block, or it inherits the marker when the block is
 * read back.
 */
private fun Block.runsInto(line: LineSpan, style: LineStyle): Boolean =
    kind == style.kind && indent == style.indent && end + 1 >= line.start

/** Snaps every block back onto line boundaries after an edit has moved its edges. */
fun RichText.realignedBlocks(): RichText = if (blocks.isEmpty()) this else withLineStyles(lineStyles())

/** The lines a selection touches — what a paragraph-level control acts on. */
fun RichText.selectedLines(start: Int, end: Int): IntRange {
    val spans = lines()
    val from = spans.indexOfLast { it.start <= minOf(start, end) }.coerceAtLeast(0)
    val to = spans.indexOfLast { it.start <= maxOf(start, end) }.coerceAtLeast(from)
    return from..to
}

/**
 * Turns [kind] on or off over the selected lines.
 *
 * Off when every selected line already wears it, on otherwise — the same rule
 * [RichText.toggle] uses for a switch, so a half-bulleted selection becomes
 * fully bulleted on the first press rather than alternating line by line.
 */
fun RichText.toggleList(kind: BlockKind, start: Int, end: Int): RichText {
    val range = selectedLines(start, end)
    val styles = lineStyles().toMutableList()
    val remove = range.all { styles[it].kind == kind }

    range.forEach { line -> styles[line] = styles[line].copy(kind = if (remove) null else kind) }
    return withLineStyles(styles)
}

/**
 * Pushes the selected lines [delta] steps in or out.
 *
 * A line already at the floor or the ceiling simply stays, rather than the
 * whole command refusing: a selection that mixes depths should still move the
 * lines that can move.
 */
fun RichText.indentBy(delta: Int, start: Int, end: Int): RichText {
    val range = selectedLines(start, end)
    val styles = lineStyles().toMutableList()

    range.forEach { line ->
        styles[line] = styles[line].copy(indent = (styles[line].indent + delta).coerceIn(0, MAX_INDENT))
    }
    return withLineStyles(styles)
}

/** The paragraph attribute shared by the whole selection, or null where they differ. */
fun RichText.selectedLineStyle(start: Int, end: Int): LineStyle? {
    val styles = lineStyles()
    val range = selectedLines(start, end)
    val first = styles[range.first]
    return first.takeIf { range.all { line -> styles[line] == first } }
}

/**
 * The number each ordered line shows; 0 for a line that shows none.
 *
 * Numbering restarts whenever the run breaks — a different marker, a different
 * depth, or any line that is not a list at all — which is what makes a sublist
 * start again at 1 while the parent list carries on underneath it.
 */
fun List<LineStyle>.orderedNumbers(): List<Int> {
    val counters = mutableMapOf<Int, Int>()
    var previous: LineStyle? = null

    return map { style ->
        if (style.kind == null) {
            counters.clear()
            previous = null
            return@map 0
        }
        // Coming back out to a shallower depth resumes that depth's count and
        // forgets everything deeper, so a second sublist starts at 1 again.
        counters.keys.filter { it > style.indent }.toList().forEach { counters.remove(it) }
        if (previous?.indent == style.indent && previous?.kind != style.kind) counters.remove(style.indent)

        previous = style
        if (style.kind != BlockKind.Ordered) return@map 0

        val next = (counters[style.indent] ?: 0) + 1
        counters[style.indent] = next
        next
    }
}
