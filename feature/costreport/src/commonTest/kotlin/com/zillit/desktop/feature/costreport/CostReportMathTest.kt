package com.zillit.desktop.feature.costreport

import com.zillit.desktop.feature.costreport.domain.CostLine
import com.zillit.desktop.feature.costreport.domain.CrColumn
import com.zillit.desktop.feature.costreport.domain.CrFormat
import com.zillit.desktop.feature.costreport.domain.CrLine
import com.zillit.desktop.feature.costreport.domain.CrNominal
import com.zillit.desktop.feature.costreport.domain.CrSet
import com.zillit.desktop.feature.costreport.domain.FigureMode
import com.zillit.desktop.feature.costreport.domain.buildSections
import com.zillit.desktop.feature.costreport.domain.currentWeek
import com.zillit.desktop.feature.costreport.domain.figuresFor
import com.zillit.desktop.feature.costreport.domain.grandTotal
import com.zillit.desktop.feature.costreport.domain.headerFigures
import com.zillit.desktop.feature.costreport.domain.headerHasEtcOver
import com.zillit.desktop.feature.costreport.domain.liveFigures
import com.zillit.desktop.feature.costreport.domain.priorVarianceByKey
import com.zillit.desktop.feature.costreport.domain.snapshotFigures
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CostReportMathTest {

    private fun nominal(budget: Double, atd: Double = 0.0, po: Double = 0.0, card: Double = 0.0) =
        CrNominal("4000", "Test", "4000", CrLine(atd = atd, po = po, card = card, budget = budget))

    @Test
    fun etcIsRemainingBudgetSoVarianceIsZeroUntilOverspent() {
        val f = liveFigures(nominal(budget = 1000.0, atd = 300.0, po = 200.0), priorVariance = null, hasPrior = false)
        assertEquals(500.0, f.etc)
        assertEquals(1000.0, f.efc)
        assertEquals(0.0, f.tv)
        assertEquals(0.0, f.vtp)
    }

    @Test
    fun overspendClampsEtcAtZeroAndGoesNegativeOnVariance() {
        val f = liveFigures(nominal(budget = 1000.0, atd = 900.0, po = 300.0), priorVariance = null, hasPrior = false)
        assertEquals(0.0, f.etc)
        assertEquals(1200.0, f.efc)
        assertEquals(-200.0, f.tv)
    }

    @Test
    fun vtpIsMovementAgainstThePriorWeeklySnapshot() {
        val n = nominal(budget = 1000.0, atd = 900.0, po = 300.0)
        assertEquals(-200.0 - (-50.0), liveFigures(n, priorVariance = -50.0, hasPrior = true).vtp)
        assertEquals(-200.0, liveFigures(n, priorVariance = null, hasPrior = true).vtp)
        assertEquals(0.0, liveFigures(n, priorVariance = -50.0, hasPrior = false).vtp)
    }

    @Test
    fun nominalWithSetsSumsThemForActuals() {
        val n = CrNominal(
            "2110", "Crew", "2110", CrLine(budget = 5000.0),
            sets = listOf(
                CrSet("2110-01", "DOP", "2110-01", CrLine(atd = 1200.0, po = 300.0)),
                CrSet("2110-02", "Focus", "2110-02", CrLine(atd = 400.0, card = 100.0)),
            ),
        )
        val f = liveFigures(n, null, false)
        assertEquals(1600.0, f.atd)
        assertEquals(400.0, f.commits)
        assertEquals(3000.0, f.etc)
        assertEquals(5000.0, f.efc)
        assertEquals(5000.0, f.bud)
    }

    @Test
    fun snapshotFiguresIgnoreEtc() {
        val f = snapshotFigures(nominal(budget = 1000.0, atd = 300.0, po = 200.0))
        assertEquals(500.0, f.efc)
        assertEquals(500.0, f.tv)
        assertEquals(0.0, f.etc)
    }

    @Test
    fun headerSumsNominalsButKeepsItsOwnBudgetAndRederivesVariance() {
        val sections = buildSections(COA, LINES)
        val camera = sections.first { it.id == "prod" }.headers.single()
        val figures = headerFigures(camera, figuresFor(FigureMode.Live, emptyMap(), false))
        assertEquals(5900.0, figures.bud)
        assertEquals(20.0 + 1600.0, figures.atd)
        assertEquals(300.0 + 100.0 + 80.0, figures.commits)
        // No nominal is over budget, so EFC = budget and variance is zero.
        assertEquals(5900.0, figures.efc)
        assertEquals(0.0, figures.tv)
        assertFalse(headerHasEtcOver(camera))
    }

    @Test
    fun grandTotalSumsEveryHeaderIncludingUncoded() {
        val sections = buildSections(COA, LINES)
        val total = grandTotal(sections, figuresFor(FigureMode.Live, emptyMap(), false))
        assertEquals(1200.0 + 5900.0 + 77.0, total.bud)
        // Uncoded card (33) and orphan (12) have no budget: their EFC is pure spend, so the total goes over by 45.
        assertEquals(-45.0, total.tv)
        assertEquals(total.bud - total.efc, total.tv)
    }

    @Test
    fun priorVarianceUsesFrozenVarianceOrFallsBackToBudgetLessSpend() {
        val prior = priorVarianceByKey(
            listOf(
                CostLine("1110", budget = 100.0, atd = 20.0, variance = -5.0),
                CostLine("1110", department = "b", budget = 0.0, atd = 10.0, variance = -1.0),
                CostLine("2120", budget = 100.0, atd = 30.0, po = 10.0),
                CostLine(null, name = "Fringes", budget = 10.0),
            ),
        )
        assertEquals(-6.0, prior["1110"])
        assertEquals(60.0, prior["2120"])
        assertEquals(10.0, prior["__unallocated__:Fringes"])
    }

    @Test
    fun worksheetFormattingFollowsWsFmt() {
        assertEquals("—", CrFormat.grid(null, "£", CrColumn.Atd))
        assertEquals("—", CrFormat.grid(0.0, "£", CrColumn.Atd))
        assertEquals("£0", CrFormat.grid(0.0, "£", CrColumn.Bud))
        assertEquals("£1,234", CrFormat.grid(1234.4, "£", CrColumn.Atd))
        assertEquals("−£1,234", CrFormat.grid(-1234.4, "£", CrColumn.Atd))
        assertEquals("(£1,234)", CrFormat.grid(-1234.0, "£", CrColumn.Tv))
        assertEquals("(£12)", CrFormat.grid(-12.0, "£", CrColumn.Vtp))
        assertEquals("—", CrFormat.grid(0.4, "£", CrColumn.Atd))
        assertEquals("£1,234.56", CrFormat.money(1234.56, "£"))
        assertEquals("−£1,234.56", CrFormat.money(-1234.56, "£"))
        assertEquals("(£1,234.56)", CrFormat.variance(-1234.56, "£"))
        assertEquals("↑ £12.00 vs last", CrFormat.delta(12.0, "£"))
        assertEquals("↓ £12.00 vs last", CrFormat.delta(-12.0, "£"))
    }

    @Test
    fun weekRunsMondayToSundayWithIsoStyleNumbering() {
        val zone = TimeZone.of("Europe/London")
        // Wednesday 20 May 2026 (a Wednesday), 15:00 local.
        val wed = LocalDateTime(2026, 5, 20, 15, 0)
        val nowMs = wed.toInstant(zone).toEpochMilliseconds()
        val week = currentWeek(nowMs, zone)
        assertEquals(LocalDate(2026, 5, 18), week.monday)
        assertEquals(LocalDate(2026, 5, 24), week.sunday)
        assertEquals("Wk 21 · w/e 24 May 2026", week.label)
        assertTrue(week.startMs < nowMs && nowMs < week.endMs)
        assertEquals(7 * 24 * 3_600_000L - 1, week.endMs - week.startMs)
        // A Sunday belongs to the week that began the previous Monday.
        val sunMs = LocalDateTime(2026, 5, 24, 23, 30).toInstant(zone).toEpochMilliseconds()
        assertEquals(LocalDate(2026, 5, 18), currentWeek(sunMs, zone).monday)
    }
}
