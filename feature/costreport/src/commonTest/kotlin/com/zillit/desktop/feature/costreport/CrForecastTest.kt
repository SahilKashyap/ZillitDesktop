package com.zillit.desktop.feature.costreport

import com.zillit.desktop.feature.costreport.domain.CostLine
import com.zillit.desktop.feature.costreport.domain.CrColumn
import com.zillit.desktop.feature.costreport.domain.CrForecast
import com.zillit.desktop.feature.costreport.domain.CrHeader
import com.zillit.desktop.feature.costreport.domain.CrLine
import com.zillit.desktop.feature.costreport.domain.CrNominal
import com.zillit.desktop.feature.costreport.domain.CrOverrides
import com.zillit.desktop.feature.costreport.domain.CrSection
import com.zillit.desktop.feature.costreport.domain.VtpBaseline
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The worksheet maths, against the web's own `costReportModel.test.js`
 * fixtures — the same numbers in, the same numbers out. A worksheet whose
 * forecast disagrees with the web's by a pound is one nobody can use for a
 * cost report meeting.
 */
class CrForecastTest {

    private fun leaf(code: String, atp: Double = 0.0, atd: Double = 0.0, po: Double = 0.0, budget: Double = 0.0) =
        CrNominal(
            code = code,
            name = code,
            account = null,
            line = CrLine(atp = atp, atd = atd, po = po, budget = budget),
        )

    @Test
    fun `etc defaults to the budget left, clamped at zero`() {
        val math = CrForecast()
        assertEquals(50.0, math.etc("1", CrLine(atd = 100.0, po = 50.0), budget = 200.0))
        assertEquals(0.0, math.etc("1", CrLine(atd = 100.0, po = 150.0), budget = 200.0), "clamped")
    }

    /** Typing 0 is a statement, not a clear. */
    @Test
    fun `an etc override wins, zero included`() {
        assertEquals(42.0, CrForecast(CrOverrides(etc = mapOf("1" to 42.0))).etc("1", CrLine(), 200.0))
        assertEquals(0.0, CrForecast(CrOverrides(etc = mapOf("1" to 0.0))).etc("1", CrLine(), 200.0))
    }

    @Test
    fun `efc is actuals plus commits plus etc`() {
        assertEquals(155.0, CrForecast().efc("1", CrLine(atd = 100.0, po = 20.0, card = 5.0), etc = 30.0))
    }

    /** A negative ETC is a forecast overage; its magnitude is added. */
    @Test
    fun `efc adds the magnitude of a negative etc`() {
        assertEquals(130.0, CrForecast().efc("1", CrLine(atd = 100.0), etc = -30.0))
    }

    @Test
    fun `an efc override wins`() {
        assertEquals(999.0, CrForecast(CrOverrides(efc = mapOf("1" to 999.0))).efc("1", CrLine(atd = 100.0), 30.0))
    }

    @Test
    fun `vtp is zero with no prior snapshot`() {
        assertEquals(0.0, CrForecast().vtp("1", currentVariance = 500.0))
    }

    @Test
    fun `vtp is the movement since the prior snapshot, department key first`() {
        val baseline = VtpBaseline(mapOf("1100|art" to 300.0), hasPrior = true)
        assertEquals(200.0, CrForecast(baseline = baseline).vtp("1100", 500.0, departmentId = "art"))
    }

    @Test
    fun `vtp falls back to the department-less key`() {
        val baseline = VtpBaseline(mapOf("1100|" to 100.0), hasPrior = true)
        assertEquals(400.0, CrForecast(baseline = baseline).vtp("1100", 500.0, departmentId = "unknown"))
    }

    @Test
    fun `a vtp override wins`() {
        val math = CrForecast(CrOverrides(vtp = mapOf("1" to 7.0)), VtpBaseline(emptyMap(), hasPrior = true))
        assertEquals(7.0, math.vtp("1", 500.0))
    }

    /** The web's `computeHeader` fixture, figure for figure. */
    @Test
    fun `a header rolls up its nominals with its own budget`() {
        val header = CrHeader(
            code = "1100",
            name = "Cast",
            budget = 300.0,
            nominals = listOf(
                leaf("1100.10", atp = 10.0, atd = 100.0, po = 20.0, budget = 200.0),
                leaf("1100.20", atp = 5.0, atd = 50.0, budget = 100.0),
            ),
        )
        val r = CrForecast().header(header)
        assertEquals(15.0, r.atp)
        assertEquals(150.0, r.atd)
        assertEquals(20.0, r.po)
        assertEquals(300.0, r.bud)
        assertEquals(130.0, r.etc, "80 + 50")
        assertEquals(300.0, r.efc, "200 + 100")
        assertEquals(0.0, r.tv)
    }

    /** An override edits the row it names and the totals above it, nothing else. */
    @Test
    fun `an override moves its header and the grand total`() {
        val header = CrHeader("1100", "Cast", 300.0, listOf(leaf("1100.10", atd = 100.0, budget = 300.0)))
        val sections = listOf(CrSection("s", "ATL", listOf(header)))

        val before = CrForecast().grandTotal(sections)
        val after = CrForecast(CrOverrides(etc = mapOf("1100.10" to 500.0))).grandTotal(sections)

        assertEquals(300.0, before.efc, "100 actual + 200 budget left")
        assertEquals(600.0, after.efc, "100 actual + 500 typed")
        assertEquals(-300.0, after.tv, "now forecast over")
    }

    @Test
    fun `over-budget headers are the ones forecast to finish over`() {
        val over = CrHeader("A", "Over", 100.0, listOf(leaf("A.1", atd = 90.0, budget = 100.0)))
        val fine = CrHeader("B", "Fine", 100.0, listOf(leaf("B.1", atd = 10.0, budget = 100.0)))
        val sections = listOf(CrSection("s", "S", listOf(over, fine)))
        val math = CrForecast(CrOverrides(etc = mapOf("A.1" to 50.0)))

        assertEquals(listOf("A"), math.overBudgetHeaders(sections).map { it.first.code })
    }

    /** A typed negative ETC is the overage the cell tints for — not merely over budget. */
    @Test
    fun `an etc overage is a negative etc, not an over-budget line`() {
        val nominal = leaf("1", atd = 500.0, budget = 100.0)
        assertFalse(CrForecast().hasEtcOverage(nominal), "clamped at zero, however far over")
        assertTrue(CrForecast(CrOverrides(etc = mapOf("1" to -20.0))).hasEtcOverage(nominal))
    }

    /**
     * The baseline keeps nominal lines and the largest variance per key.
     *
     * The first desktop port summed every line under its account, so a
     * snapshot storing header rollups beside the nominals counted VTP twice.
     */
    @Test
    fun `the vtp baseline skips non-nominal levels and keeps the largest variance`() {
        val baseline = VtpBaseline.from(
            listOf(
                CostLine(account = "1100", variance = 300.0, level = "nominal"),
                CostLine(account = "1100", variance = -900.0, level = "header"),
                CostLine(account = "1100", variance = 50.0, level = "nominal"),
            ),
        )
        assertEquals(300.0, baseline.prevVariance("1100"), "header rollup ignored; 300 beats 50")
        assertTrue(baseline.hasPrior)
    }

    @Test
    fun `setting and clearing an override`() {
        val set = CrOverrides.NONE.set(CrColumn.Etc, "1", 0.0)
        assertEquals(0.0, set.valueOf(CrColumn.Etc, "1"))
        assertEquals(1, set.editedRows)
        assertTrue(set.set(CrColumn.Etc, "1", null).isEmpty, "null clears")
        assertEquals(set, set.set(CrColumn.Atd, "1", 5.0), "a derived column is never an override")
    }

    /** Typed figures follow a currency switch; the server already converts the rest. */
    @Test
    fun `overrides rescale with the display currency`() {
        val ov = CrOverrides(etc = mapOf("1" to 1_000.0), vtp = mapOf("2" to -50.0))
        val usd = ov.rescaled(1.25)
        assertEquals(1_250.0, usd.etc["1"])
        assertEquals(-62.5, usd.vtp["2"])
    }
}
