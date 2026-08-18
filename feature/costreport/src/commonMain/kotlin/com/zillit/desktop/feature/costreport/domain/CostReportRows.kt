package com.zillit.desktop.feature.costreport.domain

/** One rendered line of the worksheet, in order, with everything the row needs already resolved. */
sealed interface CrRow {
    data class Section(val section: CrSection, val open: Boolean) : CrRow

    /** Collapsed: values inline. Open: values stripped, nominals follow, then a [HeaderTotal]. */
    data class Header(val sectionId: String, val header: CrHeader, val open: Boolean, val figures: CrFigures) : CrRow

    data class HeaderTotal(val header: CrHeader, val figures: CrFigures) : CrRow

    data class Nominal(val header: CrHeader, val nominal: CrNominal, val open: Boolean, val figures: CrFigures) : CrRow

    data class Set(val nominal: CrNominal, val set: CrSet) : CrRow

    data class GrandTotal(val figures: CrFigures) : CrRow
}

/** What the user has opened and closed. Sections start open; headers and nominals start closed. */
data class TreeToggles(
    val closedSections: Set<String> = emptySet(),
    val openHeaders: Set<String> = emptySet(),
    val openNominals: Set<String> = emptySet(),
) {
    fun toggleSection(id: String) = copy(closedSections = closedSections.flip(id))
    fun toggleHeader(key: String) = copy(openHeaders = openHeaders.flip(key))
    fun toggleNominal(key: String) = copy(openNominals = openNominals.flip(key))

    private fun Set<String>.flip(id: String): Set<String> = if (id in this) this - id else this + id

    companion object {
        fun headerKey(sectionId: String, header: CrHeader): String = "$sectionId/${header.code}"
        fun nominalKey(header: CrHeader, nominal: CrNominal): String = "${header.code}/${nominal.identity}"
    }
}

/**
 * The tree flattened for rendering, with the search applied: a matching
 * leaf reveals (and opens) its ancestors, a matching parent reveals its
 * subtree, and the totals are never pruned.
 */
fun flattenRows(
    sections: List<CrSection>,
    figuresOf: (CrNominal) -> CrFigures,
    query: String,
    toggles: TreeToggles,
): List<CrRow> {
    val needle = query.trim().lowercase()
    val out = ArrayList<CrRow>()
    sections.forEach { section -> appendSection(out, section, figuresOf, needle, toggles) }
    out += CrRow.GrandTotal(grandTotal(sections, figuresOf))
    return out
}

/** True when nothing but the footer survived the search. */
fun List<CrRow>.isEmptyBesidesTotal(): Boolean = none { it !is CrRow.GrandTotal }

private fun String.hit(needle: String): Boolean = needle.isNotEmpty() && lowercase().contains(needle)

private fun CrSet.matches(needle: String) = code.hit(needle) || name.hit(needle) || identity.hit(needle)

private fun CrNominal.matches(needle: String) = code.hit(needle) || name.hit(needle) || identity.hit(needle)

private fun CrNominal.subtreeMatches(needle: String) = matches(needle) || sets.any { it.matches(needle) }

private fun CrHeader.matches(needle: String) = code.hit(needle) || name.hit(needle)

private fun CrHeader.subtreeMatches(needle: String) = matches(needle) || nominals.any { it.subtreeMatches(needle) }

private fun CrSection.matches(needle: String) = id.hit(needle) || sec.hit(needle)

private fun appendSection(
    out: MutableList<CrRow>,
    section: CrSection,
    figuresOf: (CrNominal) -> CrFigures,
    needle: String,
    toggles: TreeToggles,
) {
    val searching = needle.isNotEmpty()
    val selfHit = section.matches(needle)
    val headers = when {
        !searching || selfHit -> section.headers
        else -> section.headers.filter { it.subtreeMatches(needle) }
    }
    if (searching && !selfHit && headers.isEmpty()) return
    val open = section.id !in toggles.closedSections || (searching && !selfHit)
    out += CrRow.Section(section, open)
    if (!open) return
    headers.forEach { header -> appendHeader(out, section, header, figuresOf, needle, toggles, revealed = selfHit) }
}

private fun appendHeader(
    out: MutableList<CrRow>,
    section: CrSection,
    header: CrHeader,
    figuresOf: (CrNominal) -> CrFigures,
    needle: String,
    toggles: TreeToggles,
    revealed: Boolean,
) {
    val searching = needle.isNotEmpty()
    val selfHit = revealed || header.matches(needle)
    val nominals = when {
        !searching || selfHit -> header.nominals
        else -> header.nominals.filter { it.subtreeMatches(needle) }
    }
    val descendantHit = searching && !selfHit && nominals.isNotEmpty()
    val open = TreeToggles.headerKey(section.id, header) in toggles.openHeaders || descendantHit
    val figures = headerFigures(header, figuresOf)
    out += CrRow.Header(section.id, header, open, figures)
    if (!open) return
    nominals.forEach { nominal -> appendNominal(out, header, nominal, figuresOf, needle, toggles, revealed = selfHit) }
    out += CrRow.HeaderTotal(header, figures)
}

private fun appendNominal(
    out: MutableList<CrRow>,
    header: CrHeader,
    nominal: CrNominal,
    figuresOf: (CrNominal) -> CrFigures,
    needle: String,
    toggles: TreeToggles,
    revealed: Boolean,
) {
    val searching = needle.isNotEmpty()
    val selfHit = revealed || nominal.matches(needle)
    val sets = when {
        !searching || selfHit -> nominal.sets
        else -> nominal.sets.filter { it.matches(needle) }
    }
    val descendantHit = searching && !selfHit && sets.isNotEmpty()
    val open = nominal.hasSets && (TreeToggles.nominalKey(header, nominal) in toggles.openNominals || descendantHit)
    out += CrRow.Nominal(header, nominal, open, figuresOf(nominal))
    if (!open) return
    sets.forEach { set -> out += CrRow.Set(nominal, set) }
}
