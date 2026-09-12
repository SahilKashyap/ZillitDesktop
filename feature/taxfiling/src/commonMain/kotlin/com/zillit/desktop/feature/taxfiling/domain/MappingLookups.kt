package com.zillit.desktop.feature.taxfiling.domain

/**
 * What a box mapping is chosen from: the production's chart of accounts, its
 * tracking layers and its asset tags.
 *
 * None of these belong to the tax-filing service. They are the account hub's,
 * read here for the pickers — the web's `CoaCodeInput`, `TrackingCodesPicker`
 * and `useProjectAssetTags`.
 */

/** A postable account the ledger-codes picker offers. */
data class CoaCode(val code: String, val name: String = "")

object CoaCodes {

    private const val EXACT = 100
    private const val CODE_PREFIX = 80
    private const val NAME_PREFIX = 60
    private const val CODE_CONTAINS = 40
    private const val NAME_CONTAINS = 20

    /**
     * The picker's order for [query] — the web's `rankRows`.
     *
     * An exact code first, then codes that start with it, names that start
     * with it, codes containing it and names containing it; ties broken by the
     * code in numeric order. Nothing typed keeps the resting (numeric) order.
     */
    fun rank(rows: List<CoaCode>, query: String): List<CoaCode> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return rows
        return rows
            .map { it to score(it, q) }
            .filter { it.second > 0 }
            .sortedWith { a, b -> (b.second - a.second).takeIf { it != 0 } ?: compare(a.first.code, b.first.code) }
            .map { it.first }
    }

    private fun score(row: CoaCode, q: String): Int {
        val code = row.code.lowercase()
        val name = row.name.lowercase()
        return when {
            code == q -> EXACT
            code.startsWith(q) -> CODE_PREFIX
            name.startsWith(q) -> NAME_PREFIX
            code.contains(q) -> CODE_CONTAINS
            name.contains(q) -> NAME_CONTAINS
            else -> 0
        }
    }

    /**
     * Ascending by account code, as the chart itself sorts — the web's
     * `compareCoaCodeNumeric`.
     *
     * Numbers by value, so `2` comes before `1000`; a hyphenated budget code
     * files by its leading number; anything with a letter in it sorts after
     * every number, and those among themselves by text.
     */
    fun compare(a: String, b: String): Int {
        val left = a.trim()
        val right = b.trim()
        val na = numberOf(left)
        val nb = numberOf(right)
        return when {
            na != null && nb != null -> na.compareTo(nb).takeIf { it != 0 } ?: left.compareTo(right)
            na != null -> -1
            nb != null -> 1
            else -> left.compareTo(right)
        }
    }

    private val HYPHENATED = Regex("^(\\d+)[\\d-]*$")

    private fun numberOf(code: String): Double? {
        if (code.isEmpty()) return null
        code.toDoubleOrNull()?.takeIf { it.isFinite() }?.let { return it }
        return HYPHENATED.find(code)?.groupValues?.get(1)?.toDoubleOrNull()
    }

    /**
     * What may be typed into the code field: digits and hyphens, never a
     * leading hyphen — the web's filter, which keeps a code from reading as a
     * negative number and sorting above every real one.
     */
    fun sanitise(typed: String): String = typed.filter { it.isDigit() || it == '-' }.trimStart('-')
}

/** One tracking set — "Locations", "Episodes" — and the codes inside it. */
data class LayerSet(
    val id: String,
    val name: String = "",
    val prefix: String = "",
    /** `#RRGGBB`, the chip colour the set was given in the chart. */
    val color: String = "",
    val codes: List<LayerCode> = emptyList(),
) {
    fun codeFor(value: String): LayerCode? = codes.firstOrNull { it.code == value || it.id == value }
}

/** A pickable code within a set — a leaf, never a header. */
data class LayerCode(
    val id: String,
    val code: String,
    val label: String = "",
    val description: String = "",
) {
    /** `LOC-LON · London`. */
    val pickerLabel: String get() = if (label.isBlank()) code else "$code · $label"
}
