package com.zillit.desktop.feature.crewlist.domain

import com.zillit.desktop.core.strings.S
/**
 * The crew-list PDF's letterhead, as the web customiser designs it
 * (`CrewListCustom.jsx`, ZL-19725): three sections — the title block, the
 * logo and the company details — arranged in rows of one or two.
 *
 * The same object rides every render (`crewlist/html`, `crewlist`, publish) as
 * `header_layout`, so what is designed is what the backend prints.
 */
enum class HeaderSection(val wire: String, val label: String) {
    Title("title", "Title"),
    Logo("logo", "Logo"),
    Company("company", "Company details"),
    ;

    companion object {
        fun of(wire: String?): HeaderSection? = entries.firstOrNull { it.wire == wire }
    }
}

/** Where the logo sits inside its own cell — only meaningful once it has one. */
enum class LogoAlign(val wire: String, val label: String, val labelKey: String) {
    Left("left", "Left", S.left),
    Center("center", "Center", S.desktop_align_center),
    Right("right", "Right", S.desktop_align_right),
}

/** A section's content nudged inside its cell, in CSS pixels. */
data class SectionOffset(val x: Int = 0, val y: Int = 0)

data class HeaderLayout(
    val logo: LogoAlign = LogoAlign.Left,
    /** The logo's width in px; the backend keeps the aspect ratio. */
    val logoSize: Int = DEFAULT_LOGO_SIZE,
    val offsets: Map<HeaderSection, SectionOffset> = HeaderSection.entries.associateWith { SectionOffset() },
    /**
     * Rows top to bottom. A two-section row prints side by side; a one-section
     * row is full width. The default is the merged letterhead: logo beside the
     * company details, the title underneath.
     */
    val order: List<List<HeaderSection>> = DEFAULT_ORDER,
) {
    /**
     * The web's `logoHasOwnCell`: the logo stops sharing the fixed masthead cell
     * the moment the arrangement leaves the default, and only then does its
     * alignment mean anything.
     */
    val logoHasOwnCell: Boolean get() = order != DEFAULT_ORDER

    /** Differs from the shipped default — drives Reset. */
    val isCustomised: Boolean get() = this != HeaderLayout()

    fun offsetOf(section: HeaderSection): SectionOffset = offsets[section] ?: SectionOffset()

    companion object {
        const val DEFAULT_LOGO_SIZE = 160
        const val MIN_LOGO_SIZE = 60
        const val MAX_LOGO_SIZE = 400
        const val LOGO_SIZE_STEP = 5

        val DEFAULT_ORDER: List<List<HeaderSection>> = listOf(
            listOf(HeaderSection.Logo, HeaderSection.Company),
            listOf(HeaderSection.Title),
        )

        /**
         * What the Design canvas asks the backend for: all three sections as
         * separate stacked blocks, so the arranger can regroup them in place.
         * Preview and the PDF send the real grouped order instead.
         */
        val PREVIEW_STACK_ORDER: List<List<HeaderSection>> = HeaderSection.entries.map { listOf(it) }
    }
}

/**
 * Rearranging the sections — the web drag layer's `dropOnBlock` / `dropToGap`,
 * ported rule for rule so the desktop's arranger and the web's agree on every
 * drop. Each returns the new order; the input is never modified.
 */
object HeaderArranger {

    /**
     * Every section exactly once, unknown or repeated ids dropped, a missing
     * section appended as its own row — the layer's sanitiser, run on anything
     * that arrives from outside (a stored layout, a message from the page).
     */
    fun sanitise(order: List<List<HeaderSection>>): List<List<HeaderSection>> {
        val seen = mutableSetOf<HeaderSection>()
        val rows: MutableList<List<HeaderSection>> = order.mapNotNull { row ->
            val kept = mutableListOf<HeaderSection>()
            row.forEach { section ->
                if (kept.size < MAX_ROW && section !in seen) {
                    seen += section
                    kept += section
                }
            }
            kept.toList().takeIf { it.isNotEmpty() }
        }.toMutableList()
        HeaderSection.entries.filter { it !in seen }.forEach { rows.add(listOf(it)) }
        return rows
    }

    /**
     * [dragged] dropped onto [target]. Two sections already sharing a row swap,
     * wherever in the target the drop lands. Otherwise [dragged] joins the
     * target's row on [before]'s side; a full row evicts its other member to a
     * row of its own directly below.
     */
    fun dropOnSection(
        order: List<List<HeaderSection>>,
        dragged: HeaderSection,
        target: HeaderSection,
        before: Boolean,
    ): List<List<HeaderSection>> {
        if (dragged == target) return order
        val rows = order.map { it.toMutableList() }.toMutableList()
        val from = rows.indexOfFirst { dragged in it }
        if (from >= 0 && from == rows.indexOfFirst { target in it }) {
            val row = rows[from]
            val draggedAt = row.indexOf(dragged)
            val targetAt = row.indexOf(target)
            row.removeAt(draggedAt)
            row.add(row.indexOf(target) + if (draggedAt < targetAt) 1 else 0, dragged)
            return rows
        }
        rows.removeSection(dragged)
        val at = rows.indexOfFirst { target in it }
        if (at < 0) {
            rows += mutableListOf(dragged)
            return rows
        }
        if (rows[at].size >= MAX_ROW) {
            val evicted = rows[at].first { it != target }
            rows[at] = mutableListOf(target)
            rows.add(at + 1, mutableListOf(evicted))
        }
        val row = rows[at]
        val targetAt = row.indexOf(target)
        row.add(if (before) targetAt else targetAt + 1, dragged)
        return rows
    }

    /**
     * [dragged] dropped into the gap before row [gap] (0 is above the first
     * row, `order.size` below the last): it becomes a full-width row there.
     * Only a solo row collapses when its section leaves, so only then does the
     * gap index shift — the web's off-by-one fix.
     */
    fun dropInGap(order: List<List<HeaderSection>>, dragged: HeaderSection, gap: Int): List<List<HeaderSection>> {
        val rows = order.map { it.toMutableList() }.toMutableList()
        val from = rows.indexOfFirst { dragged in it }
        val collapses = from >= 0 && rows[from].size == 1
        rows.removeSection(dragged)
        var index = if (collapses && from < gap) gap - 1 else gap
        index = index.coerceIn(0, rows.size)
        rows.add(index, mutableListOf(dragged))
        return rows
    }

    private fun MutableList<MutableList<HeaderSection>>.removeSection(section: HeaderSection) {
        val at = indexOfFirst { section in it }
        if (at < 0) return
        this[at].remove(section)
        if (this[at].isEmpty()) removeAt(at)
    }

    private const val MAX_ROW = 2
}

/**
 * Undo, redo and reset over the letterhead — every change goes through
 * [push], so the history and the Reset state never disagree.
 */
data class LayoutHistory(
    val current: HeaderLayout = HeaderLayout(),
    val past: List<HeaderLayout> = emptyList(),
    val future: List<HeaderLayout> = emptyList(),
) {
    val canUndo: Boolean get() = past.isNotEmpty()
    val canRedo: Boolean get() = future.isNotEmpty()

    /** The web pushes every commit, even one that lands where it started. */
    fun push(next: HeaderLayout): LayoutHistory =
        LayoutHistory(current = next, past = past + current, future = emptyList())

    fun undo(): LayoutHistory = if (past.isEmpty()) {
        this
    } else {
        LayoutHistory(current = past.last(), past = past.dropLast(1), future = listOf(current) + future)
    }

    fun redo(): LayoutHistory = if (future.isEmpty()) {
        this
    } else {
        LayoutHistory(current = future.first(), past = past + current, future = future.drop(1))
    }

    /** Back to the shipped letterhead, as one undoable step; a no-op when already there. */
    fun reset(): LayoutHistory = if (current.isCustomised) push(HeaderLayout()) else this
}
