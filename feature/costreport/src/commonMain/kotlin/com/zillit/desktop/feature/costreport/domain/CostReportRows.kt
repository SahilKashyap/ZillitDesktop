package com.zillit.desktop.feature.costreport.domain

/** The worksheet's Expand control: how deep the tree opens without a click per row. */
enum class CrViewMode(val id: String, val label: String) {
    Headers("section", "Headers"),
    Nominals("nominal", "Nominals"),
    SetCodes("set", "Set Codes"),
}

/** The worksheet's Filter control. Anything but All flattens the grid to header lines. */
enum class CrLineFilter(val id: String, val label: String, val stripLabel: String) {
    All("all", "All", "All"),
    OverBudget("over", "Over Budget", "Over Budget"),
    ActiveThisWeek("active", "Active This Wk", "Active This Week"),
}

enum class SortDirection { Descending, Ascending }

/** A column sort: one click sorts high → low, a second low → high, a third clears it. */
data class CrSort(val column: CrColumn? = null, val direction: SortDirection = SortDirection.Descending) {
    val active: Boolean get() = column != null

    fun toggled(on: CrColumn): CrSort = when {
        column != on -> CrSort(on, SortDirection.Descending)
        direction == SortDirection.Descending -> CrSort(on, SortDirection.Ascending)
        else -> CrSort()
    }
}

/**
 * What the reader has opened and closed.
 *
 * Sections and headers start **open** and nominals **closed** — the web's
 * `secOpen[id] !== false`, `hdrOpen[code] !== false` and truthy `nomOpen`.
 * Headers are keyed by code alone and nominals by [CrNominal.identity], as
 * there, so a toggle survives a reload of the same week.
 */
data class TreeToggles(
    val closedSections: Set<String> = emptySet(),
    val closedHeaders: Set<String> = emptySet(),
    val openNominals: Set<String> = emptySet(),
) {
    fun toggleSection(id: String) = copy(closedSections = closedSections.flip(id))
    fun toggleHeader(code: String) = copy(closedHeaders = closedHeaders.flip(code))
    fun toggleNominal(identity: String) = copy(openNominals = openNominals.flip(identity))

    fun sectionOpen(id: String): Boolean = id !in closedSections
    fun headerOpen(code: String): Boolean = code !in closedHeaders
    fun nominalOpen(identity: String): Boolean = identity in openNominals

    /** What the Expand all / Collapse all button reads: every header open, and at least one header. */
    fun allHeadersOpen(sections: List<CrSection>): Boolean {
        val headers = sections.flatMap { it.headers }
        return headers.isNotEmpty() && headers.none { it.code in closedHeaders }
    }

    private fun Set<String>.flip(id: String): Set<String> = if (id in this) this - id else this + id

    companion object {
        /** Collapse all: every section open again, every header and nominal closed. */
        fun collapsed(sections: List<CrSection>): TreeToggles =
            TreeToggles(closedHeaders = sections.flatMap { it.headers }.map { it.code }.toSet())

        /** Expand all: every header open, and every nominal that has sets. */
        fun expanded(sections: List<CrSection>): TreeToggles = TreeToggles(openNominals = nominalsWithSets(sections))

        /**
         * Picking an Expand mode past Headers opens every header, and Set
         * Codes every nominal as well; Headers leaves the reader's own
         * toggles alone — the web's `setViewModeFull`.
         */
        fun forViewMode(current: TreeToggles, mode: CrViewMode, sections: List<CrSection>): TreeToggles =
            when (mode) {
                CrViewMode.Headers -> current
                CrViewMode.Nominals -> current.copy(closedHeaders = emptySet())
                CrViewMode.SetCodes -> current.copy(
                    closedHeaders = emptySet(),
                    openNominals = nominalsWithSets(sections),
                )
            }

        private fun nominalsWithSets(sections: List<CrSection>): Set<String> =
            sections.flatMap { it.headers }.flatMap { it.nominals }.filter { it.hasSets }.map { it.identity }.toSet()
    }
}

/**
 * A search over the loaded tree — the web's `buildVisibility`.
 *
 * Matching is a case-insensitive substring on a row's own code or label, at
 * every level. A matching leaf pulls in its breadcrumb; a matching parent
 * reveals its whole subtree. Every shown header is forced open, and a nominal
 * is forced open when one of its sets is shown. Totals are never pruned —
 * this only decides what renders.
 */
class CrSearch private constructor(
    val needle: String,
    private val visible: Set<String>,
    private val open: Set<String>,
    /** How many rows matched on their own code or label. */
    val matches: Int,
) {
    val active: Boolean get() = needle.isNotEmpty()

    fun isVisible(path: String): Boolean = !active || path in visible

    fun isOpen(path: String): Boolean = !active || path in open

    companion object {
        val NONE = CrSearch("", emptySet(), emptySet(), 0)

        fun build(sections: List<CrSection>, query: String, withSets: Boolean): CrSearch {
            val needle = query.trim().lowercase()
            if (needle.isEmpty()) return NONE
            val walk = SearchWalk(needle, withSets)
            sections.forEach(walk::section)
            return CrSearch(needle, walk.visible, walk.open, walk.matches)
        }
    }
}

private class SearchWalk(private val needle: String, private val withSets: Boolean) {
    val visible = HashSet<String>()
    val open = HashSet<String>()
    var matches = 0

    private fun hit(vararg fields: String?): Boolean {
        val self = fields.any { it != null && it.lowercase().contains(needle) }
        if (self) matches += 1
        return self
    }

    fun section(section: CrSection) {
        val secSelf = hit(section.id, section.sec)
        var anyHeader = false
        section.headers.forEach { header -> if (header(section, header, secSelf)) anyHeader = true }
        if (secSelf || anyHeader) {
            val path = CrPaths.section(section)
            visible += path
            open += path
        }
    }

    private fun header(section: CrSection, header: CrHeader, secSelf: Boolean): Boolean {
        val hdrSelf = hit(header.code, header.name)
        var anyNominal = false
        header.nominals.forEach { nominal ->
            if (nominal(section, header, nominal, ancestorMatched = secSelf || hdrSelf)) anyNominal = true
        }
        val shown = hdrSelf || secSelf || anyNominal
        if (shown) {
            val path = CrPaths.header(section, header)
            visible += path
            open += path
        }
        return shown
    }

    private fun nominal(section: CrSection, header: CrHeader, nominal: CrNominal, ancestorMatched: Boolean): Boolean {
        val nomSelf = hit(nominal.code, nominal.name)
        var anySet = false
        if (withSets) {
            nominal.sets.forEach { set ->
                val setSelf = hit(set.code, set.name)
                if (setSelf || ancestorMatched || nomSelf) {
                    visible += CrPaths.set(section, header, nominal, set)
                    anySet = true
                }
            }
        }
        val shown = nomSelf || ancestorMatched || anySet
        if (shown) {
            val path = CrPaths.nominal(section, header, nominal)
            visible += path
            if (anySet) open += path
        }
        return shown
    }
}

/** Stable row paths: unique across the tree even where two rows share a code. */
object CrPaths {
    fun section(section: CrSection): String = "s:${section.id}"
    fun header(section: CrSection, header: CrHeader): String = "h:${section.id}/${header.code}"
    fun nominal(section: CrSection, header: CrHeader, nominal: CrNominal): String =
        "n:${section.id}/${header.code}/${nominal.identity}"
    fun set(section: CrSection, header: CrHeader, nominal: CrNominal, set: CrSet): String =
        "x:${section.id}/${header.code}/${nominal.identity}/${set.code}"
}

/** The short badge a header carries when the grid is flattened, so its band is still legible. */
val SECTION_ABBREVIATIONS: Map<String, String> = mapOf(
    "atl" to "ATL",
    "prod" to "PROD",
    "post" to "POST",
    "other" to "OTHER",
    "cont" to "CONT",
    CrSection.UNCODED_SECTION_ID to "NA",
    CrSection.CONTRACTUAL_SECTION_ID to "CI",
)

/** One rendered line of the worksheet, in order, with everything the row needs already resolved. */
sealed interface CrRow {
    /** Unique within one table — the lazy list's key. */
    val key: String

    data class Section(val section: CrSection, val open: Boolean) : CrRow {
        override val key: String get() = CrPaths.section(section)
    }

    /**
     * A header line. [showValues] is false while its nominals are on screen,
     * because a Total row beneath them carries the rollup — the figure shows
     * in exactly one place.
     */
    data class Header(
        val section: CrSection,
        val header: CrHeader,
        val open: Boolean,
        val figures: CrFigures,
        val showValues: Boolean,
        /** The section abbreviation, set only when the grid is flattened. */
        val badge: String? = null,
    ) : CrRow {
        override val key: String get() = CrPaths.header(section, header)
    }

    data class HeaderTotal(val section: CrSection, val header: CrHeader, val figures: CrFigures) : CrRow {
        override val key: String get() = CrPaths.header(section, header) + "#total"
    }

    data class Nominal(
        val section: CrSection,
        val header: CrHeader,
        val nominal: CrNominal,
        val open: Boolean,
        val figures: CrFigures,
    ) : CrRow {
        override val key: String get() = CrPaths.nominal(section, header, nominal)
    }

    data class Set(val section: CrSection, val header: CrHeader, val nominal: CrNominal, val set: CrSet) : CrRow {
        override val key: String get() = CrPaths.set(section, header, nominal, set)
    }

    /** "N lines · Filter · Sort" above a flattened grid, with its Clear. */
    data class ModeStrip(val lines: Int, val filter: CrLineFilter, val sort: CrSort) : CrRow {
        override val key: String get() = "mode-strip"
    }

    data class NoMatches(val query: String) : CrRow {
        override val key: String get() = "no-matches"
    }
}

/** How the worksheet is being looked at: the search, the toggles, and the three controls. */
data class CrTableSpec(
    val search: String = "",
    val toggles: TreeToggles = TreeToggles(),
    val viewMode: CrViewMode = CrViewMode.Headers,
    val filter: CrLineFilter = CrLineFilter.All,
    val sort: CrSort = CrSort(),
)

/** The rows to draw, the footer, and the two facts the chrome around the grid reads. */
data class CrTable(
    val rows: List<CrRow>,
    val grandTotal: CrFigures,
    /** A sort or a filter is on, so the grid lists header lines rather than the tree. */
    val flat: Boolean,
    val search: CrSearch,
)

/**
 * The longest figure the grid will print, in the grid's own spelling — what
 * its value columns are sized to, so no amount ever breaks across two lines.
 * Every figure row counts, not just the grand total: a variance in brackets
 * can be wider than the total above it.
 */
fun CrTable.widestFigure(symbol: String, decimals: Int): String {
    val figures = rows.mapNotNull { row ->
        when (row) {
            is CrRow.Header -> row.figures.takeIf { row.showValues }
            is CrRow.HeaderTotal -> row.figures
            is CrRow.Nominal -> row.figures
            else -> null
        }
    } + grandTotal
    return figures
        .flatMap { f -> CrColumn.entries.map { CrFormat.grid(f.value(it), symbol, it, decimals) } }
        .maxByOrNull { it.length }
        .orEmpty()
}

/**
 * The worksheet table — the web's `WorksheetTable` row loop.
 *
 * A search takes over: it always renders the pruned tree and pauses the sort
 * and filter until it is cleared. Otherwise a sort or a filter flattens the
 * grid to header lines, and neither leaves the tree. The grand total is the
 * whole report in every mode.
 */
fun buildWorksheetTable(sections: List<CrSection>, forecast: CrForecast, spec: CrTableSpec): CrTable {
    val search = CrSearch.build(sections, spec.search, withSets = true)
    val flat = !search.active && (spec.sort.active || spec.filter != CrLineFilter.All)
    val rows = if (flat) flatRows(sections, forecast, spec) else treeRows(sections, forecast, spec, search)
    return CrTable(rows = rows, grandTotal = forecast.grandTotal(sections), flat = flat, search = search)
}

private fun treeRows(
    sections: List<CrSection>,
    forecast: CrForecast,
    spec: CrTableSpec,
    search: CrSearch,
): List<CrRow> {
    val out = ArrayList<CrRow>()
    sections.forEach { section ->
        if (!search.isVisible(CrPaths.section(section))) return@forEach
        val open = search.active || spec.toggles.sectionOpen(section.id)
        out += CrRow.Section(section, open)
        if (open) section.headers.forEach { header -> appendHeader(out, section, header, forecast, spec, search) }
    }
    if (search.active && search.matches == 0) out += CrRow.NoMatches(spec.search.trim())
    return out
}

/**
 * A header, its nominals and its Total. The nominals show while the header is
 * open, whatever the header's own state is in Nominals or Set Codes mode, and
 * always while searching.
 */
@Suppress("LongParameterList") // One header, and everything that decides it.
private fun appendHeader(
    out: MutableList<CrRow>,
    section: CrSection,
    header: CrHeader,
    forecast: CrForecast,
    spec: CrTableSpec,
    search: CrSearch,
) {
    if (!search.isVisible(CrPaths.header(section, header))) return
    val figures = forecast.header(header)
    val headerOpen = search.active || spec.toggles.headerOpen(header.code)
    val showNominals = headerOpen || spec.viewMode != CrViewMode.Headers
    val nominalsShown = showNominals && header.nominals.isNotEmpty()
    out += CrRow.Header(section, header, headerOpen, figures, showValues = !nominalsShown)
    if (showNominals) header.nominals.forEach { appendNominal(out, section, header, it, forecast, spec, search) }
    if (nominalsShown) out += CrRow.HeaderTotal(section, header, figures)
}

@Suppress("LongParameterList") // One row, and everything that decides it.
private fun appendNominal(
    out: MutableList<CrRow>,
    section: CrSection,
    header: CrHeader,
    nominal: CrNominal,
    forecast: CrForecast,
    spec: CrTableSpec,
    search: CrSearch,
) {
    val path = CrPaths.nominal(section, header, nominal)
    if (!search.isVisible(path)) return
    val searching = search.active
    val open = if (searching) search.isOpen(path) else spec.toggles.nominalOpen(nominal.identity)
    out += CrRow.Nominal(section, header, nominal, open, forecast.nominal(nominal))
    val showSets = searching || spec.toggles.nominalOpen(nominal.identity) || spec.viewMode == CrViewMode.SetCodes
    if (!showSets) return
    nominal.sets
        .filter { set -> search.isVisible(CrPaths.set(section, header, nominal, set)) }
        .forEach { set -> out += CrRow.Set(section, header, nominal, set) }
}

/** One header line of the flattened grid, with the figures it sorts and filters on. */
private data class FlatItem(val section: CrSection, val header: CrHeader, val figures: CrFigures)

private fun flatRows(sections: List<CrSection>, forecast: CrForecast, spec: CrTableSpec): List<CrRow> {
    val all = sections.flatMap { section -> section.headers.map { FlatItem(section, it, forecast.header(it)) } }
    val items = sorted(filtered(all, sections, forecast, spec), spec.sort)
    val out = ArrayList<CrRow>()
    out += CrRow.ModeStrip(items.size, spec.filter, spec.sort)
    items.forEach { item -> appendFlatHeader(out, item, forecast, spec) }
    return out
}

/**
 * Over Budget keeps a header forecast over its own budget — or, while any
 * header is open, one holding a nominal that is. Active This Wk keeps a
 * header with any actuals, this period or to date.
 */
private fun filtered(
    items: List<FlatItem>,
    sections: List<CrSection>,
    forecast: CrForecast,
    spec: CrTableSpec,
): List<FlatItem> = when (spec.filter) {
    CrLineFilter.All -> items
    CrLineFilter.OverBudget -> {
        val anyHeaderOpen = sections.any { s -> s.headers.any { spec.toggles.headerOpen(it.code) } }
        items.filter { item ->
            item.figures.isOverBudget() ||
                (anyHeaderOpen && item.header.nominals.any { forecast.nominal(it).isOverBudget() })
        }
    }
    CrLineFilter.ActiveThisWeek -> items.filter { it.figures.atp > 0 || it.figures.atd > 0 }
}

private fun CrFigures.isOverBudget(): Boolean = bud > 0 && tv < 0

private fun sorted(items: List<FlatItem>, sort: CrSort): List<FlatItem> {
    val column = sort.column ?: return items
    return if (sort.direction == SortDirection.Descending) {
        items.sortedByDescending { it.figures.value(column) }
    } else {
        items.sortedBy { it.figures.value(column) }
    }
}

private fun appendFlatHeader(out: MutableList<CrRow>, item: FlatItem, forecast: CrForecast, spec: CrTableSpec) {
    val (section, header, figures) = item
    val open = spec.toggles.headerOpen(header.code)
    val nominalsShown = open && header.nominals.isNotEmpty()
    out += CrRow.Header(
        section = section,
        header = header,
        open = open,
        figures = figures,
        showValues = !nominalsShown,
        badge = SECTION_ABBREVIATIONS[section.id],
    )
    if (open) header.nominals.forEach { appendFlatNominal(out, section, header, it, forecast, spec) }
    if (nominalsShown) out += CrRow.HeaderTotal(section, header, figures)
}

@Suppress("LongParameterList") // One row, and everything that decides it.
private fun appendFlatNominal(
    out: MutableList<CrRow>,
    section: CrSection,
    header: CrHeader,
    nominal: CrNominal,
    forecast: CrForecast,
    spec: CrTableSpec,
) {
    val open = spec.toggles.nominalOpen(nominal.identity)
    out += CrRow.Nominal(section, header, nominal, open, forecast.nominal(nominal))
    if (!open && spec.viewMode != CrViewMode.SetCodes) return
    nominal.sets.forEach { out += CrRow.Set(section, header, nominal, it) }
}
