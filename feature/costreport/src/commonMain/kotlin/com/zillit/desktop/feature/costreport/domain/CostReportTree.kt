package com.zillit.desktop.feature.costreport.domain

import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str

/** The seven figures a wire line carries into the tree (`etc/efc/variance` are dropped, spec §4.1). */
data class CrLine(
    val atp: Double = 0.0,
    val atd: Double = 0.0,
    val po: Double = 0.0,
    val card: Double = 0.0,
    val cash: Double = 0.0,
    val pr: Double = 0.0,
    val budget: Double = 0.0,
) {
    operator fun plus(other: CrLine): CrLine = CrLine(
        atp = atp + other.atp,
        atd = atd + other.atd,
        po = po + other.po,
        card = card + other.card,
        cash = cash + other.cash,
        pr = pr + other.pr,
        budget = budget + other.budget,
    )

    val commits: Double get() = po + card + cash + pr

    companion object {
        val ZERO = CrLine()
    }
}

/** A `sub_category` row: actuals and commitments only, never a budget. */
data class CrSet(val code: String, val name: String, val account: String?, val line: CrLine) {
    val identity: String get() = account ?: code
}

/** A `category` row — what line items are coded to. */
data class CrNominal(
    val code: String,
    val name: String,
    /** The raw wire key when the row carries data; the code otherwise. */
    val account: String?,
    val line: CrLine,
    val sets: List<CrSet> = emptyList(),
) {
    /**
     * Row identity everywhere: keys, toggles, overrides, the ledger drill.
     *
     * The web's `rowAccountKey` — the code, except on rows that have none.
     * Every Non-Allocated and Contractual row shows `-` in its code cell, so
     * those are told apart by their internal bucket key instead; keying them
     * by the shared `-` opened one drawer (and typed one ETC) for all of them.
     */
    val identity: String get() = if (isBucket) account ?: code else code

    /** True for a sentinel or null-bucket row, which has no COA code to send anywhere. */
    val isBucket: Boolean get() = code == BUCKET_CODE

    /**
     * A Contractual Item: a budget line with no COA code and no ledger
     * behind it, so nothing on the row drills — unlike a Non-Allocated row,
     * which is opened precisely to find the transactions to re-code.
     */
    val isContractual: Boolean get() = account?.startsWith(CONTRACTUAL_KEY_PREFIX) == true

    /**
     * What the ledger is asked about: the real COA code with `.direct`
     * stripped, or a bucket's own key — the server files unallocated lines
     * under the same sentinel the row was built from.
     */
    val apiCode: String get() = if (isBucket) account.orEmpty() else code.removeSuffix(DIRECT_SUFFIX)

    val hasSets: Boolean get() = sets.isNotEmpty()

    companion object {
        const val BUCKET_CODE = "-"
        const val DIRECT_SUFFIX = ".direct"
        internal const val CONTRACTUAL_KEY_PREFIX = "__contractual__:"
    }
}

/**
 * A key a person should never read — every server sentinel and every bucket
 * this tree makes starts with `__`, and no COA code does. A mis-coded
 * downstream account ("art_4110") deliberately does not match: it stays
 * visible so it can be traced and re-coded at source.
 */
fun isInternalAccountKey(account: String?): Boolean = account?.startsWith("__") == true

/** A `section` row: the bold band a group of nominals sits under. */
data class CrHeader(val code: String, val name: String, val budget: Double, val nominals: List<CrNominal>)

/**
 * A `header` row: ABOVE THE LINE, PRODUCTION, …, then the two pseudo-sections
 * appended after them — CONTRACTUAL ITEMS and NON-ALLOCATED ITEMS.
 */
data class CrSection(val id: String, val sec: String, val headers: List<CrHeader>) {
    val isUncoded: Boolean get() = id == UNCODED_SECTION_ID

    val isContractual: Boolean get() = id == CONTRACTUAL_SECTION_ID

    companion object {
        const val UNCODED_SECTION_ID = "uncoded"
        const val CONTRACTUAL_SECTION_ID = "contractual"
    }
}

/** A wire line summed by its key, with the first-seen name. */
internal data class SummedLine(val key: String, val name: String?, val line: CrLine)

private const val NULL_BUCKET = "__unallocated__"
private const val SENTINEL_PREFIX = "__"

/** The server's marker on a budget line with no COA code — `line.section_id`. */
private const val CONTRACTUAL_MARKER = "__contractual__"

private val SECTION_LABEL_KEYS = mapOf(
    "atl" to S.desktop_above_the_line,
    "prod" to S.production,
    "post" to S.desktop_cr_post_production_caps,
    "other" to S.desktop_cr_other_costs_caps,
    "cont" to S.desktop_cr_contingency_caps,
)
private val CANONICAL_ORDER = listOf("atl", "prod", "post", "other", "cont")

private val SENTINEL_LABEL_KEYS = mapOf(
    "__uncoded__" to S.desktop_cr_card_unmatched,
    "__uncoded_budget__" to S.desktop_budget_unallocated,
    "__payroll_unallocated__" to S.desktop_payroll_unallocated,
    "__fringes_unallocated__" to S.desktop_fringes_unallocated,
    NULL_BUCKET to S.desktop_non_allocated_items,
)

private fun sentinelLabel(key: String): String? = SENTINEL_LABEL_KEYS[key]?.let { str(it) }

/**
 * The key a wire line is summed under: its account, or a named null bucket
 * (`__unallocated__:{name}`), or the nameless one.
 */
fun lineKey(line: CostLine): String {
    val account = line.account?.trim().orEmpty()
    if (account.isNotEmpty()) return account
    val name = line.name?.trim().orEmpty()
    return if (name.isEmpty()) NULL_BUCKET else "$NULL_BUCKET:$name"
}

/** Sums lines by [lineKey]; the first-seen name wins; insertion order is kept. */
internal fun sumLines(lines: List<CostLine>): LinkedHashMap<String, SummedLine> {
    val out = LinkedHashMap<String, SummedLine>()
    lines.forEach { line ->
        val key = lineKey(line)
        val figures = CrLine(line.atp, line.atd, line.po, line.card, line.cash, line.pr, line.budget)
        val existing = out[key]
        out[key] = if (existing == null) {
            SummedLine(key, line.name?.trim()?.takeIf { it.isNotEmpty() }, figures)
        } else {
            existing.copy(
                name = existing.name ?: line.name?.trim()?.takeIf { it.isNotEmpty() },
                line = existing.line + figures,
            )
        }
    }
    return out
}

/**
 * The COA as the four-level cost-report tree, with the wire lines summed
 * onto it (spec §4.1) — the web adapter's `buildSectionsData`.
 *
 * Two pseudo-sections follow the chart, in this order: CONTRACTUAL ITEMS, the
 * budget lines the live aggregator marks `section_id: "__contractual__"`
 * (fringes, contingency, bond, financing, insurance — expected, never an
 * error), then NON-ALLOCATED ITEMS for everything the chart does not know. The
 * split is the point: before the marker those budget lines landed in the red
 * Non-Allocated band and read as "your budget is mis-coded".
 */
fun buildSections(coa: List<CoaRow>, lines: List<CostLine>): List<CrSection> {
    val (contractualLines, coded) = lines.partition { it.sectionId == CONTRACTUAL_MARKER }
    val summed = sumLines(coded)
    val used = mutableSetOf<String>()
    val childrenOf: Map<String?, List<CoaRow>> = coa.groupBy { it.parentId }
        .mapValues { (_, rows) -> rows.sortedBy { it.code } }
    val roots = coa.filter { it.level == CoaLevel.Section }
        .sortedWith(compareBy<CoaRow> { canonicalRank(it.code) }.thenBy { it.code })
    val sections = roots.map { root ->
        CrSection(
            id = root.code,
            sec = SECTION_LABEL_KEYS[root.code.lowercase()]?.let { str(it).uppercase() }
                ?: root.name.ifBlank { root.code.uppercase() },
            headers = childrenOf[root.id].orEmpty().filter { it.level == CoaLevel.Header }
                .map { buildHeader(it, childrenOf, summed, used) },
        )
    }
    val rest = summed.filterKeys { it !in used }
    return sections + listOfNotNull(contractualSection(contractualLines), uncodedSection(rest))
}

/**
 * One row per contractual budget line, keyed by the line's own id (its name
 * when it has none) so two "Fringes — X" rows stay apart.
 */
private fun contractualSection(lines: List<CostLine>): CrSection? {
    if (lines.isEmpty()) return null
    val byKey = LinkedHashMap<String, SummedLine>()
    lines.forEach { line ->
        val key = CrNominal.CONTRACTUAL_KEY_PREFIX + (line.id ?: line.name.orEmpty())
        val figures = CrLine(line.atp, line.atd, line.po, line.card, line.cash, line.pr, line.budget)
        val existing = byKey[key]
        byKey[key] = SummedLine(
            key = key,
            name = existing?.name ?: line.name?.trim()?.takeIf { it.isNotEmpty() }
                ?: str(S.desktop_cr_contractual_item),
            line = (existing?.line ?: CrLine.ZERO) + figures,
        )
    }
    val nominals = byKey.values.map { CrNominal(CrNominal.BUCKET_CODE, it.name.orEmpty(), it.key, it.line) }
    val header = CrHeader(
        code = "CI",
        name = str(S.desktop_cr_contractual_items),
        budget = nominals.sumOf { it.line.budget },
        nominals = nominals,
    )
    return CrSection(
        id = CrSection.CONTRACTUAL_SECTION_ID,
        sec = str(S.desktop_cr_contractual_items_caps),
        headers = listOf(header),
    )
}

/** atl, prod, post, other, cont first, in that order; everything else after, by code. */
private fun canonicalRank(code: String): Int =
    CANONICAL_ORDER.indexOf(code.lowercase()).let { if (it < 0) CANONICAL_ORDER.size else it }

private fun buildHeader(
    row: CoaRow,
    childrenOf: Map<String?, List<CoaRow>>,
    summed: Map<String, SummedLine>,
    used: MutableSet<String>,
): CrHeader {
    val nominals = childrenOf[row.id].orEmpty().filter { it.level == CoaLevel.Nominal }
        .map { buildNominal(it, childrenOf, summed, used) }
    // Only when something was actually coded to the header: a zero line there
    // is the aggregator echoing the chart, not an entry anybody made.
    val direct = summed[row.code]?.takeIf { it.line != CrLine.ZERO && used.add(row.code) }?.let {
        CrNominal(
            code = row.code + CrNominal.DIRECT_SUFFIX,
            name = "${row.name} — direct entries",
            account = row.code,
            line = it.line,
        )
    }
    val all = listOfNotNull(direct) + nominals
    return CrHeader(code = row.code, name = row.name, budget = all.sumOf { it.line.budget }, nominals = all)
}

private fun buildNominal(
    row: CoaRow,
    childrenOf: Map<String?, List<CoaRow>>,
    summed: Map<String, SummedLine>,
    used: MutableSet<String>,
): CrNominal {
    val own = summed[row.code]?.takeIf { used.add(row.code) }
    val sets = childrenOf[row.id].orEmpty().filter { it.level == CoaLevel.Set }.map { setRow ->
        val data = summed[setRow.code]?.takeIf { used.add(setRow.code) }
        CrSet(
            code = setRow.code,
            name = setRow.name,
            account = data?.let { setRow.code },
            line = (data?.line ?: CrLine.ZERO).copy(budget = 0.0),
        )
    }
    // A nominal with sets is a sum of them; only its budget is its own.
    val line = if (sets.isEmpty()) own?.line ?: CrLine.ZERO else CrLine(budget = own?.line?.budget ?: 0.0)
    return CrNominal(code = row.code, name = row.name, account = own?.let { row.code }, line = line, sets = sets)
}

private fun uncodedSection(rest: Map<String, SummedLine>): CrSection? {
    if (rest.isEmpty()) return null
    val nominals = rest.map { (key, data) ->
        val bucket = key.startsWith(SENTINEL_PREFIX)
        CrNominal(
            code = if (bucket) CrNominal.BUCKET_CODE else key,
            name = data.name ?: sentinelLabel(key)
                ?: if (bucket) str(SENTINEL_LABEL_KEYS.getValue(NULL_BUCKET)) else key,
            account = key,
            line = data.line,
        )
    }.sortedBy { it.code }
    val header = CrHeader(
        code = "NA",
        name = str(S.desktop_non_allocated_items),
        budget = nominals.sumOf { it.line.budget },
        nominals = nominals,
    )
    return CrSection(
        id = CrSection.UNCODED_SECTION_ID,
        sec = str(S.desktop_non_allocated_items).uppercase(),
        headers = listOf(header),
    )
}
