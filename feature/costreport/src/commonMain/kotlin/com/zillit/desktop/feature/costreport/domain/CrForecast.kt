package com.zillit.desktop.feature.costreport.domain

import kotlin.math.abs
import kotlin.math.max

/**
 * The accountant's typed forecast, keyed by row identity (`account ?: code` —
 * the web's `rowAccountKey`).
 *
 * Three maps because the three columns are edited independently and each
 * override *replaces* its derived value rather than adjusting it. A present
 * key wins **including zero**: typing 0 into ETC is a statement ("nothing
 * left to spend"), not a clear. Removing the key is the clear.
 */
data class CrOverrides(
    val etc: Map<String, Double> = emptyMap(),
    val efc: Map<String, Double> = emptyMap(),
    val vtp: Map<String, Double> = emptyMap(),
) {
    val isEmpty: Boolean get() = etc.isEmpty() && efc.isEmpty() && vtp.isEmpty()

    /** How many rows carry at least one override — the "N edited" chip. */
    val editedRows: Int get() = (etc.keys + efc.keys + vtp.keys).size

    fun set(column: CrColumn, key: String, value: Double?): CrOverrides = when (column) {
        CrColumn.Etc -> copy(etc = etc.withValue(key, value))
        CrColumn.Efc -> copy(efc = efc.withValue(key, value))
        CrColumn.Vtp -> copy(vtp = vtp.withValue(key, value))
        else -> this
    }

    fun valueOf(column: CrColumn, key: String): Double? = when (column) {
        CrColumn.Etc -> etc[key]
        CrColumn.Efc -> efc[key]
        CrColumn.Vtp -> vtp[key]
        else -> null
    }

    /**
     * Re-expressed into another display currency.
     *
     * The server converts every source amount before it reaches the client,
     * so budget and actuals arrive in the new currency — but a typed override
     * is still in the old one. Scaling by `rateTo / rateFrom` keeps a £1,000
     * ETC meaning a thousand pounds after the switch to dollars.
     */
    fun rescaled(factor: Double): CrOverrides = if (factor == 1.0) {
        this
    } else {
        CrOverrides(
            etc = etc.mapValues { it.value * factor },
            efc = efc.mapValues { it.value * factor },
            vtp = vtp.mapValues { it.value * factor },
        )
    }

    companion object {
        val NONE = CrOverrides()

        /** The three columns an accountant may type into; everything else is derived. */
        val EDITABLE = setOf(CrColumn.Etc, CrColumn.Efc, CrColumn.Vtp)
    }
}

private fun Map<String, Double>.withValue(key: String, value: Double?): Map<String, Double> =
    if (value == null) this - key else this + (key to value)

/**
 * The previous weekly snapshot's variance, frozen, for Variance This Period.
 *
 * Keyed `account|department`, exactly as the web builds it — including the two
 * rules the desktop's first port got wrong:
 *
 * - **Only nominal-level lines** when the snapshot carries levels at all. A
 *   snapshot that stores its header rollups beside the nominals would
 *   otherwise count the same variance twice into VTP.
 * - **Largest absolute value wins** on a duplicate key, rather than a sum.
 *   Legacy snapshots wrote the same nominal more than once; summing those
 *   reported a movement that never happened.
 */
data class VtpBaseline(val prevVarByKey: Map<String, Double>, val hasPrior: Boolean) {

    /** The frozen variance for a row: `code|department`, then `code|`, then 0. */
    fun prevVariance(identity: String, departmentId: String? = null): Double =
        prevVarByKey["$identity|${departmentId.orEmpty()}"] ?: prevVarByKey["$identity|"] ?: 0.0

    companion object {
        val NONE = VtpBaseline(emptyMap(), hasPrior = false)

        fun from(lines: List<CostLine>?): VtpBaseline {
            if (lines == null) return NONE
            val anyHasLevel = lines.any { !it.level.isNullOrBlank() }
            val out = LinkedHashMap<String, Double>()
            lines.forEach { line ->
                if (anyHasLevel && !line.level.isNullOrBlank() && line.level != NOMINAL_LEVEL) return@forEach
                val key = "${line.account.orEmpty()}|${line.department.orEmpty()}"
                val variance = line.variance ?: 0.0
                val existing = out[key]
                if (existing == null || abs(variance) > abs(existing)) out[key] = variance
            }
            return VtpBaseline(out, hasPrior = true)
        }

        private const val NOMINAL_LEVEL = "nominal"
    }
}

/**
 * The worksheet's maths for one run — the web's `costReportModel.js`, rule for
 * rule, with the override maps it had and the desktop's first port did not.
 *
 * Pure: the same inputs always give the same figures, which is what lets a
 * version, an export and the screen agree.
 */
class CrForecast(
    private val overrides: CrOverrides = CrOverrides.NONE,
    private val baseline: VtpBaseline = VtpBaseline.NONE,
) {

    /**
     * ETC: an override wins (zero included); otherwise the budget left,
     * `max(0, budget − actuals − commits)`.
     */
    fun etc(key: String, a: CrLine, budget: Double): Double =
        overrides.etc[key] ?: max(0.0, budget - a.atd - a.commits)

    /**
     * EFC: an override wins; otherwise actuals + commits + ETC. A **negative**
     * ETC (only reachable by typing one) is a forecast overage, so its
     * magnitude is added — the web's own rule, which reads as "this line will
     * cost more than it has already committed".
     */
    fun efc(key: String, a: CrLine, etc: Double): Double =
        overrides.efc[key] ?: (a.atd + a.commits + if (etc < 0) abs(etc) else etc)

    /**
     * VTP: an override wins; with no prior weekly snapshot there is no
     * movement to report; otherwise this variance less the frozen one.
     */
    fun vtp(key: String, currentVariance: Double, departmentId: String? = null): Double = when {
        overrides.vtp.containsKey(key) -> overrides.vtp.getValue(key)
        !baseline.hasPrior -> 0.0
        else -> currentVariance - baseline.prevVariance(key, departmentId)
    }

    /** One nominal's eleven figures. */
    fun nominal(nominal: CrNominal): CrFigures {
        val a = nominal.actuals()
        val key = nominal.identity
        val bud = nominal.line.budget
        val etc = etc(key, a, bud)
        val efc = efc(key, a, etc)
        val tv = bud - efc
        return CrFigures(
            atp = a.atp,
            atd = a.atd,
            po = a.po,
            card = a.card,
            cash = a.cash,
            pr = a.pr,
            etc = etc,
            efc = efc,
            bud = bud,
            tv = tv,
            vtp = vtp(key, tv),
        )
    }

    /** A header: Σ nominals, but the budget is the header's own and `tv = bud − efc`. */
    fun header(header: CrHeader): CrFigures {
        val sum = header.nominals.fold(CrFigures.ZERO) { acc, n -> acc + nominal(n) }
        return sum.copy(bud = header.budget, tv = header.budget - sum.efc)
    }

    /** Σ every header, with `tv` re-derived so it stays internally consistent. */
    fun grandTotal(sections: List<CrSection>): CrFigures {
        val sum = sections.flatMap { it.headers }.fold(CrFigures.ZERO) { acc, h -> acc + header(h) }
        return sum.copy(tv = sum.bud - sum.efc)
    }

    /** A section's total — the band under its heading. */
    fun section(section: CrSection): CrFigures {
        val sum = section.headers.fold(CrFigures.ZERO) { acc, h -> acc + header(h) }
        return sum.copy(tv = sum.bud - sum.efc)
    }

    /**
     * Whether a nominal's ETC is negative — a typed overage.
     *
     * The web tints the cell for this and only this. It is not "over budget",
     * which is [overBudgetHeaders]' question.
     */
    fun hasEtcOverage(nominal: CrNominal): Boolean = nominal(nominal).etc < 0

    /**
     * Headers forecast to finish over budget (`tv < 0`) — the web's
     * `getOverageRows`, and what the "N over" badge on the Live CR tab counts.
     */
    fun overBudgetHeaders(sections: List<CrSection>): List<Pair<CrHeader, CrFigures>> =
        sections.flatMap { it.headers }
            .map { it to header(it) }
            .filter { (_, figures) -> figures.tv < 0 }

    /** The figures function the grid draws with. */
    val figures: (CrNominal) -> CrFigures get() = ::nominal
}
