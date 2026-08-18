package com.zillit.desktop.feature.costreport.domain

import kotlin.math.abs
import kotlin.math.max

/** The eleven numeric columns of the worksheet, for one row (spec §2.2, §4.3). */
data class CrFigures(
    val atp: Double = 0.0,
    val atd: Double = 0.0,
    val po: Double = 0.0,
    val card: Double = 0.0,
    val cash: Double = 0.0,
    val pr: Double = 0.0,
    val etc: Double = 0.0,
    val efc: Double = 0.0,
    val bud: Double = 0.0,
    val tv: Double = 0.0,
    val vtp: Double = 0.0,
) {
    val commits: Double get() = po + card + cash + pr

    /** Column-wise sum — the variance is *not* re-derived; callers do that. */
    operator fun plus(o: CrFigures): CrFigures = CrFigures(
        atp = atp + o.atp,
        atd = atd + o.atd,
        po = po + o.po,
        card = card + o.card,
        cash = cash + o.cash,
        pr = pr + o.pr,
        etc = etc + o.etc,
        efc = efc + o.efc,
        bud = bud + o.bud,
        tv = tv + o.tv,
        vtp = vtp + o.vtp,
    )

    fun value(column: CrColumn): Double = when (column) {
        CrColumn.Atp -> atp
        CrColumn.Atd -> atd
        CrColumn.Po -> po
        CrColumn.Card -> card
        CrColumn.Cash -> cash
        CrColumn.Pr -> pr
        CrColumn.Etc -> etc
        CrColumn.Efc -> efc
        CrColumn.Bud -> bud
        CrColumn.Tv -> tv
        CrColumn.Vtp -> vtp
    }

    companion object {
        val ZERO = CrFigures()
    }
}

/** How a nominal's figures are derived: the live rules, or the frozen snapshot's simpler ones. */
enum class FigureMode { Live, Snapshot }

/** A nominal's actuals: the sum of its sets when it has any, its own line otherwise. Budget is always its own. */
fun CrNominal.actuals(): CrLine =
    if (sets.isEmpty()) line else sets.fold(CrLine.ZERO) { acc, set -> acc + set.line }.copy(budget = line.budget)

/**
 * The live worksheet's per-row maths with empty override maps:
 * `etc = max(0, budget − atd − commits)`, `efc = atd + commits + |etc|`,
 * `tv = budget − efc`, `vtp = tv − prior variance` when a prior weekly
 * snapshot exists (else 0).
 */
fun liveFigures(nominal: CrNominal, priorVariance: Double?, hasPrior: Boolean): CrFigures {
    val a = nominal.actuals()
    val bud = nominal.line.budget
    val etc = max(0.0, bud - a.atd - a.commits)
    val efc = a.atd + a.commits + abs(etc)
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
        vtp = if (hasPrior) tv - (priorVariance ?: 0.0) else 0.0,
    )
}

/** The posted-snapshot table's rules: `efc = atd + commits`, `variance = budget − efc`; ATP/ETC/VTP unused. */
fun snapshotFigures(nominal: CrNominal): CrFigures {
    val a = nominal.actuals()
    val bud = nominal.line.budget
    val efc = a.atd + a.commits
    return CrFigures(
        atp = a.atp,
        atd = a.atd,
        po = a.po,
        card = a.card,
        cash = a.cash,
        pr = a.pr,
        etc = 0.0,
        efc = efc,
        bud = bud,
        tv = bud - efc,
        vtp = 0.0,
    )
}

/** Header = Σ nominals, except the budget is the header's own and `tv = bud − efc`. */
fun headerFigures(header: CrHeader, figuresOf: (CrNominal) -> CrFigures): CrFigures {
    val sum = header.nominals.fold(CrFigures.ZERO) { acc, n -> acc + figuresOf(n) }
    return sum.copy(bud = header.budget, tv = header.budget - sum.efc)
}

/** True when any nominal under [header] would need more than its budget (`etc` clamped at zero). */
fun headerHasEtcOver(header: CrHeader): Boolean = header.nominals.any { n ->
    val a = n.actuals()
    n.line.budget - a.atd - a.commits < 0
}

/** Σ headers of every section, all eleven columns; `tv = bud − efc`. */
fun grandTotal(sections: List<CrSection>, figuresOf: (CrNominal) -> CrFigures): CrFigures {
    val sum = sections.flatMap { it.headers }.fold(CrFigures.ZERO) { acc, h -> acc + headerFigures(h, figuresOf) }
    return sum.copy(tv = sum.bud - sum.efc)
}

/**
 * The prior weekly snapshot's variance by row identity — the VTP baseline.
 * Frozen `variance` where the server sent it, `budget − (atd + commits)`
 * otherwise, summed under the same key the tree uses.
 */
fun priorVarianceByKey(lines: List<CostLine>): Map<String, Double> {
    val out = LinkedHashMap<String, Double>()
    lines.forEach { line ->
        val variance = line.variance ?: (line.budget - (line.atd + line.po + line.card + line.cash + line.pr))
        out[lineKey(line)] = (out[lineKey(line)] ?: 0.0) + variance
    }
    return out
}

/** The figures function the worksheet uses for [mode]. */
fun figuresFor(mode: FigureMode, prior: Map<String, Double>, hasPrior: Boolean): (CrNominal) -> CrFigures =
    when (mode) {
        FigureMode.Live -> { n -> liveFigures(n, prior[n.identity], hasPrior) }
        FigureMode.Snapshot -> { n -> snapshotFigures(n) }
    }
