package com.zillit.desktop.feature.costreport.domain

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
    /** Row identity everywhere: keys, maps, toggles. */
    val identity: String get() = account ?: code

    /** True for a sentinel or null-bucket row, which has no COA code to send anywhere. */
    val isBucket: Boolean get() = code == BUCKET_CODE

    /** What the API is asked about: the real COA code, `.direct` stripped. */
    val apiCode: String get() = code.removeSuffix(DIRECT_SUFFIX)

    val hasSets: Boolean get() = sets.isNotEmpty()

    companion object {
        const val BUCKET_CODE = "-"
        const val DIRECT_SUFFIX = ".direct"
    }
}

/** A `section` row: the bold band a group of nominals sits under. */
data class CrHeader(val code: String, val name: String, val budget: Double, val nominals: List<CrNominal>)

/** A `header` row: ABOVE THE LINE, PRODUCTION, …, and the appended NON-ALLOCATED ITEMS. */
data class CrSection(val id: String, val sec: String, val headers: List<CrHeader>) {
    val isUncoded: Boolean get() = id == UNCODED_SECTION_ID

    companion object {
        const val UNCODED_SECTION_ID = "uncoded"
    }
}

/** A wire line summed by its key, with the first-seen name. */
internal data class SummedLine(val key: String, val name: String?, val line: CrLine)

private const val NULL_BUCKET = "__unallocated__"
private const val SENTINEL_PREFIX = "__"

private val SECTION_LABELS = mapOf(
    "atl" to "ABOVE THE LINE",
    "prod" to "PRODUCTION",
    "post" to "POST PRODUCTION",
    "other" to "OTHER COSTS",
    "cont" to "CONTINGENCY",
)
private val CANONICAL_ORDER = listOf("atl", "prod", "post", "other", "cont")

private val SENTINEL_LABELS = mapOf(
    "__uncoded__" to "Card — Unmatched Transactions",
    "__uncoded_budget__" to "Budget — Unallocated",
    "__payroll_unallocated__" to "Payroll — Unallocated",
    "__fringes_unallocated__" to "Fringes — Unallocated",
    NULL_BUCKET to "Non-Allocated Items",
)

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
 * onto it (spec §4.1). Anything the chart does not know is appended as the
 * NON-ALLOCATED ITEMS pseudo-section.
 */
fun buildSections(coa: List<CoaRow>, lines: List<CostLine>): List<CrSection> {
    val summed = sumLines(lines)
    val used = mutableSetOf<String>()
    val childrenOf: Map<String?, List<CoaRow>> = coa.groupBy { it.parentId }
        .mapValues { (_, rows) -> rows.sortedBy { it.code } }
    val roots = coa.filter { it.level == CoaLevel.Section }
        .sortedWith(compareBy<CoaRow> { canonicalRank(it.code) }.thenBy { it.code })
    val sections = roots.map { root ->
        CrSection(
            id = root.code,
            sec = SECTION_LABELS[root.code.lowercase()] ?: root.name.ifBlank { root.code.uppercase() },
            headers = childrenOf[root.id].orEmpty().filter { it.level == CoaLevel.Header }
                .map { buildHeader(it, childrenOf, summed, used) },
        )
    }
    val rest = summed.filterKeys { it !in used }
    return if (rest.isEmpty()) sections else sections + uncodedSection(rest)
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
    val direct = summed[row.code]?.takeIf { used.add(row.code) }?.let {
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

private fun uncodedSection(rest: Map<String, SummedLine>): CrSection {
    val nominals = rest.map { (key, data) ->
        val bucket = key.startsWith(SENTINEL_PREFIX)
        CrNominal(
            code = if (bucket) CrNominal.BUCKET_CODE else key,
            name = data.name ?: SENTINEL_LABELS[key] ?: if (bucket) SENTINEL_LABELS.getValue(NULL_BUCKET) else key,
            account = key,
            line = data.line,
        )
    }
    val header = CrHeader(
        code = "NA",
        name = "Non-Allocated Items",
        budget = nominals.sumOf { it.line.budget },
        nominals = nominals,
    )
    return CrSection(id = CrSection.UNCODED_SECTION_ID, sec = "NON-ALLOCATED ITEMS", headers = listOf(header))
}
