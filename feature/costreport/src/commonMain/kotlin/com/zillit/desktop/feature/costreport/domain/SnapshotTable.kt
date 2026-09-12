package com.zillit.desktop.feature.costreport.domain

/**
 * A posted snapshot's figures for one row: what was spent and committed
 * against the budget, frozen. No ETC and no this-period movement — those
 * columns read "—" on the snapshot page, because a snapshot stores neither.
 */
data class SnapshotFigures(
    val atd: Double = 0.0,
    val po: Double = 0.0,
    val card: Double = 0.0,
    val cash: Double = 0.0,
    val pr: Double = 0.0,
    val budget: Double = 0.0,
) {
    val commits: Double get() = po + card + cash + pr
    val efc: Double get() = atd + commits
    val variance: Double get() = budget - efc

    operator fun plus(other: SnapshotFigures) = SnapshotFigures(
        atd = atd + other.atd,
        po = po + other.po,
        card = card + other.card,
        cash = cash + other.cash,
        pr = pr + other.pr,
        budget = budget + other.budget,
    )

    companion object {
        val ZERO = SnapshotFigures()

        fun of(nominal: CrNominal): SnapshotFigures {
            val a = nominal.actuals()
            return SnapshotFigures(a.atd, a.po, a.card, a.cash, a.pr, nominal.line.budget)
        }

        fun of(header: CrHeader): SnapshotFigures = header.nominals.fold(ZERO) { acc, n -> acc + of(n) }

        fun of(section: CrSection): SnapshotFigures = section.headers.fold(ZERO) { acc, h -> acc + of(h) }
    }
}

/**
 * What is open on the snapshot page. Unlike the worksheet, headers start
 * **closed** here — the web's detail table opens one only on `=== true`.
 */
data class SnapshotToggles(
    val closedSections: Set<String> = emptySet(),
    val openHeaders: Set<String> = emptySet(),
) {
    fun toggleSection(id: String) =
        copy(closedSections = if (id in closedSections) closedSections - id else closedSections + id)

    fun toggleHeader(code: String) =
        copy(openHeaders = if (code in openHeaders) openHeaders - code else openHeaders + code)
}

sealed interface SnapshotRow {
    val key: String

    data class Section(val section: CrSection, val open: Boolean, val figures: SnapshotFigures) : SnapshotRow {
        override val key: String get() = CrPaths.section(section)
    }

    data class Header(
        val section: CrSection,
        val header: CrHeader,
        val open: Boolean,
        val figures: SnapshotFigures,
    ) : SnapshotRow {
        override val key: String get() = CrPaths.header(section, header)
    }

    data class Nominal(
        val section: CrSection,
        val header: CrHeader,
        val nominal: CrNominal,
        val figures: SnapshotFigures,
    ) : SnapshotRow {
        override val key: String get() = CrPaths.nominal(section, header, nominal)
    }

    data class NoMatches(val query: String) : SnapshotRow {
        override val key: String get() = "no-matches"
    }
}

data class SnapshotTable(val rows: List<SnapshotRow>, val total: SnapshotFigures, val search: CrSearch)

/**
 * The snapshot page's table — the web's `CostReportDetailTable`: section bands
 * with their own totals, headers, and nominals (no sets), searched the same
 * way as the worksheet, and a grand total over everything.
 */
fun buildSnapshotTable(sections: List<CrSection>, query: String, toggles: SnapshotToggles): SnapshotTable {
    val search = CrSearch.build(sections, query, withSets = false)
    val rows = ArrayList<SnapshotRow>()
    sections.forEach { section ->
        if (!search.isVisible(CrPaths.section(section))) return@forEach
        val open = search.active || section.id !in toggles.closedSections
        rows += SnapshotRow.Section(section, open, SnapshotFigures.of(section))
        if (open) section.headers.forEach { header -> appendSnapshotHeader(rows, section, header, search, toggles) }
    }
    if (search.active && search.matches == 0) rows += SnapshotRow.NoMatches(query.trim())
    return SnapshotTable(rows, sections.fold(SnapshotFigures.ZERO) { acc, s -> acc + SnapshotFigures.of(s) }, search)
}

private fun appendSnapshotHeader(
    rows: MutableList<SnapshotRow>,
    section: CrSection,
    header: CrHeader,
    search: CrSearch,
    toggles: SnapshotToggles,
) {
    if (!search.isVisible(CrPaths.header(section, header))) return
    val open = search.active || header.code in toggles.openHeaders
    rows += SnapshotRow.Header(section, header, open, SnapshotFigures.of(header))
    if (!open) return
    header.nominals
        .filter { search.isVisible(CrPaths.nominal(section, header, it)) }
        .forEach { rows += SnapshotRow.Nominal(section, header, it, SnapshotFigures.of(it)) }
}
