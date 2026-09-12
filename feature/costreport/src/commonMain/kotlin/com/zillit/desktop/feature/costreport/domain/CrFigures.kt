package com.zillit.desktop.feature.costreport.domain

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

/** A nominal's actuals: the sum of its sets when it has any, its own line otherwise. Budget is always its own. */
fun CrNominal.actuals(): CrLine =
    if (sets.isEmpty()) line else sets.fold(CrLine.ZERO) { acc, set -> acc + set.line }.copy(budget = line.budget)
