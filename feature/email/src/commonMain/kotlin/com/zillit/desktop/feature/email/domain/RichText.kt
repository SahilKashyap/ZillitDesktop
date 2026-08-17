package com.zillit.desktop.feature.email.domain

/**
 * The formatting this composer can apply.
 *
 * Two kinds live in one type: switches ([Bold] .. [Strike]) that a range either
 * has or hasn't, and valued styles ([TextColor], [FontSize], [Highlight]) where
 * a range holds at most one value per [family]. `data` equality is what makes
 * the domain operations honest — `TextColor("#e60000")` in a mark list is the
 * same style wherever it appears.
 */
sealed interface TextMark {
    data object Bold : TextMark
    data object Italic : TextMark
    data object Underline : TextMark
    data object Strike : TextMark

    /** Text colour, as `#rrggbb`. */
    data class TextColor(val hex: String) : TextMark

    /** Background colour, as `#rrggbb`. */
    data class Highlight(val hex: String) : TextMark

    /** Font size in CSS pixels — the unit the web composer's whitelist uses. */
    data class FontSize(val px: Int) : TextMark
}

/**
 * The exclusivity group a style belongs to. Switches are alone in theirs;
 * valued styles share one per kind, because text can be red or blue but not
 * both — setting a family's value replaces whatever value the range had.
 */
enum class MarkFamily { FontSize, TextColor, Highlight, Bold, Italic, Underline, Strike }

val TextMark.family: MarkFamily
    get() = when (this) {
        TextMark.Bold -> MarkFamily.Bold
        TextMark.Italic -> MarkFamily.Italic
        TextMark.Underline -> MarkFamily.Underline
        TextMark.Strike -> MarkFamily.Strike
        is TextMark.TextColor -> MarkFamily.TextColor
        is TextMark.Highlight -> MarkFamily.Highlight
        is TextMark.FontSize -> MarkFamily.FontSize
    }

/** A run of [style] over `[start, end)`. */
data class Mark(val start: Int, val end: Int, val style: TextMark) {
    val isEmpty: Boolean get() = start >= end
}

/**
 * A formatted message body.
 *
 * ## Why this owns the model rather than leaning on Compose
 *
 * The obvious implementation is to hand `AnnotatedString` to a text field and
 * let the framework carry the spans. That works until an edit lands *across* a
 * span boundary, and then the behaviour is the framework's, undocumented, and
 * different between versions.
 *
 * Formatting that quietly detaches from the words it was applied to is the
 * defining bug of every home-grown rich text editor. So the document is plain
 * text plus [marks], every edit is applied here as an explicit transform, and
 * the whole thing is testable without a UI toolkit. Compose only renders it.
 */
data class RichText(
    val text: String = "",
    val marks: List<Mark> = emptyList(),
) {

    /** Whether [style] covers the whole of `[start, end)` — what the toolbar shows as "on". */
    fun isApplied(style: TextMark, start: Int, end: Int): Boolean {
        if (start >= end) {
            // An empty selection asks about the caret: what would typing here
            // be? Answered by the run immediately behind it, which is what
            // makes the toolbar stay lit as you type.
            return marks.any { it.style == style && start > it.start && start <= it.end }
        }
        return (start until end).all { index ->
            marks.any { it.style == style && index >= it.start && index < it.end }
        }
    }

    /**
     * Turns [style] on or off across `[start, end)`.
     *
     * Off when the selection is already fully covered, on otherwise — the rule
     * every editor uses, and the reason partially-bold selections become fully
     * bold on the first press rather than flipping each character.
     */
    fun toggle(style: TextMark, start: Int, end: Int): RichText {
        if (start >= end) return this

        return if (isApplied(style, start, end)) {
            copy(marks = marks.carve(start, end) { it == style }.normalised())
        } else {
            // Applying via [set] is what makes this safe for valued styles
            // too: turning "red" on over a blue run replaces the blue instead
            // of stacking a second colour under it.
            set(style, start, end)
        }
    }

    /**
     * Sets [style] across `[start, end)`, replacing any value its family had.
     *
     * What a picker does, as opposed to a button: choosing red twice leaves
     * the text red, where [toggle] would flip it back off.
     */
    fun set(style: TextMark, start: Int, end: Int): RichText {
        if (start >= end) return this
        val carved = marks.carve(start, end) { it.family == style.family }
        return copy(marks = (carved + Mark(start, end, style)).normalised())
    }

    /** Returns [family] to its default (no mark at all) across `[start, end)`. */
    fun clear(family: MarkFamily, start: Int, end: Int): RichText {
        if (start >= end) return this
        return copy(marks = marks.carve(start, end) { it.family == family }.normalised())
    }

    /**
     * The single value [family] holds across the whole of `[start, end)`, or
     * null when the range has none or a mixture — what a size or colour picker
     * shows as the current value. An empty range asks about the caret, with the
     * same behind-the-caret rule as [isApplied].
     */
    fun valueOf(family: MarkFamily, start: Int, end: Int): TextMark? {
        val candidate = marks.firstOrNull { mark ->
            mark.style.family == family &&
                if (start >= end) start > mark.start && start <= mark.end else start >= mark.start && start < mark.end
        }?.style ?: return null

        return candidate.takeIf { start >= end || isApplied(it, start, end) }
    }

    /**
     * Applies a text edit, carrying the formatting with it.
     *
     * Works out what changed by comparing common prefix and suffix, which
     * covers typing, deleting, pasting and replace-selection alike — the field
     * reports a whole new string, not an edit description.
     */
    fun withText(updated: String): RichText {
        if (updated == text) return this

        val prefix = commonPrefix(text, updated)
        val suffix = commonSuffix(text, updated, prefix)

        val removedEnd = text.length - suffix
        val insertedEnd = updated.length - suffix
        val delta = insertedEnd - removedEnd

        val shifted = marks.mapNotNull { mark ->
            val start = shift(mark.start, prefix, removedEnd, delta)
            val end = shift(mark.end, prefix, removedEnd, delta)
            Mark(start, end, mark.style).takeUnless { it.isEmpty }
        }

        return RichText(updated, shifted.normalised())
    }

    /** The marks covering a position, for carrying formatting to newly typed text. */
    fun stylesAt(index: Int): Set<TextMark> =
        marks.filter { index > it.start && index <= it.end }.mapTo(mutableSetOf()) { it.style }

    /** Never prints the text. */
    override fun toString(): String = "RichText(chars=${text.length}, marks=${marks.size})"

    companion object {
        /** Plain text, unformatted — what a reply's quoted body starts as. */
        fun plain(text: String): RichText = RichText(text)
    }
}

/**
 * Where a position lands after `[from, removedEnd)` became something [delta]
 * characters longer.
 *
 * One rule for starts and ends alike, which is what keeps a mark's two edges
 * from drifting apart:
 *
 *  - before the edit: unmoved
 *  - at or after where the removal ended: shifted
 *  - inside the removed region: collapsed to its start
 *
 * The middle case is what makes typing *inside* a bold word stay bold while
 * typing immediately *before* one does not: an insertion has `from ==
 * removedEnd`, so a mark starting exactly there shifts right and leaves the new
 * text outside it, while a mark ending there extends.
 */
private fun shift(position: Int, from: Int, removedEnd: Int, delta: Int): Int = when {
    position < from -> position
    position >= removedEnd -> position + delta
    else -> from
}

private fun commonPrefix(old: String, new: String): Int {
    val limit = minOf(old.length, new.length)
    var index = 0
    while (index < limit && old[index] == new[index]) index++
    return index
}

private fun commonSuffix(old: String, new: String, prefix: Int): Int {
    val limit = minOf(old.length, new.length) - prefix
    var index = 0
    while (index < limit && old[old.length - 1 - index] == new[new.length - 1 - index]) index++
    return index
}

/**
 * Removes every mark matching [affected] from `[start, end)`, splitting any
 * that straddles the boundary so the parts outside survive.
 */
private fun List<Mark>.carve(start: Int, end: Int, affected: (TextMark) -> Boolean): List<Mark> =
    flatMap { mark ->
        if (!affected(mark.style) || mark.end <= start || mark.start >= end) {
            listOf(mark)
        } else {
            listOfNotNull(
                mark.copy(end = minOf(mark.end, start)).takeUnless { it.isEmpty },
                mark.copy(start = maxOf(mark.start, end)).takeUnless { it.isEmpty },
            )
        }
    }

/**
 * Merges touching runs of the same style and drops empty ones.
 *
 * Without this every keystroke inside a bold word leaves another fragment
 * behind, and the mark list grows without bound over a long message.
 */
private fun List<Mark>.normalised(): List<Mark> =
    filterNot { it.isEmpty }
        .groupBy { it.style }
        .flatMap { (style, group) ->
            group.sortedBy { it.start }.fold(mutableListOf<Mark>()) { merged, mark ->
                val last = merged.lastOrNull()
                if (last != null && mark.start <= last.end) {
                    merged[merged.lastIndex] = last.copy(end = maxOf(last.end, mark.end))
                } else {
                    merged += mark
                }
                merged
            }.map { it.copy(style = style) }
        }
        .sortedWith(compareBy({ it.start }, { it.style.family.ordinal }, { it.style.toString() }))
