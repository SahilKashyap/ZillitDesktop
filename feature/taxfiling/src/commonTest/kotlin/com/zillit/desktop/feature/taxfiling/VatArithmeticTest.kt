package com.zillit.desktop.feature.taxfiling

import com.zillit.desktop.feature.taxfiling.domain.LedgerLine
import com.zillit.desktop.feature.taxfiling.domain.VatBox
import com.zillit.desktop.feature.taxfiling.domain.VatReturn
import com.zillit.desktop.feature.taxfiling.domain.ledgerCsv
import com.zillit.desktop.feature.taxfiling.domain.ledgerFileName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The two boxes HMRC checks against the rest, and the export beside them. */
class VatArithmeticTest {

    /** Box 3 is boxes 1 and 2 added, whatever the draft said it was. */
    @Test
    fun `box three is the sum of boxes one and two`() {
        val computed = VatReturn(
            mapOf(
                VatBox.DueOnSales to 900.0,
                VatBox.DueOnAcquisitions to 100.0,
                VatBox.TotalDue to 7.0,
            ),
        ).computed()

        assertEquals(1000.0, computed[VatBox.TotalDue])
    }

    /**
     * Box 5 is the *amount*, never a negative.
     *
     * HMRC takes the figure and infers the direction from boxes 3 and 4, so a
     * reclaim is filed as a positive number. Filing a negative is a return
     * rejected for a value out of range.
     */
    @Test
    fun `box five is the absolute difference`() {
        val reclaim = VatReturn(
            mapOf(VatBox.DueOnSales to 100.0, VatBox.ReclaimedOnPurchases to 400.0),
        ).computed()

        assertEquals(300.0, reclaim[VatBox.NetDue])
        assertFalse(reclaim.isPayable)
    }

    @Test
    fun `a return that owes money is payable`() {
        val owed = VatReturn(
            mapOf(VatBox.DueOnSales to 900.0, VatBox.ReclaimedOnPurchases to 250.0),
        ).computed()

        assertEquals(650.0, owed[VatBox.NetDue])
        assertTrue(owed.isPayable)
    }

    /** Empty boxes count as zero rather than dropping out of the arithmetic. */
    @Test
    fun `an empty return computes to zero rather than to nothing`() {
        val computed = VatReturn().computed()

        assertEquals(0.0, computed[VatBox.TotalDue])
        assertEquals(0.0, computed[VatBox.NetDue])
    }

    /** Boxes 3 and 5 are not offered for mapping; the other seven are. */
    @Test
    fun `the computed boxes are not mappable`() {
        assertEquals(7, VatBox.mappable.size)
        assertFalse(VatBox.mappable.any { it.computed })
        assertEquals(listOf(1, 2, 4, 6, 7, 8, 9), VatBox.mappable.map { it.number })
    }

    /** The slot a mapping is stored under is the box's number, not its name. */
    @Test
    fun `a box maps to its numbered slot`() {
        assertEquals("box1", VatBox.DueOnSales.slot)
        assertEquals("box9", VatBox.AcquisitionsExVat.slot)
        assertEquals(VatBox.NetDue, VatBox.byField("netVatDue"))
    }

    /**
     * A memo with a comma does not become two columns.
     *
     * Memos are free text an accountant typed, and quoting only when it looks
     * necessary is how an export silently gains a column halfway down.
     */
    @Test
    fun `the export quotes every cell and doubles inner quotes`() {
        val csv = ledgerCsv(
            listOf(
                LedgerLine(
                    box = "box1",
                    accountCode = "4000",
                    credit = 1200.0,
                    periodYear = 2026,
                    periodMonth = 4,
                    tracking = mapOf("dept" to "CAM"),
                    memo = """Invoice 12, "rush" fee""",
                ),
            ),
        )

        val lines = csv.trim().lines()
        assertEquals("\"Box\",\"Account code\",\"Debit\",\"Credit\",\"Period\",\"Tracking\",\"Memo\"", lines[0])
        assertTrue(lines[1].endsWith("\"Invoice 12, \"\"rush\"\" fee\""))
        assertTrue(lines[1].contains("\"2026-04\""))
        assertTrue(lines[1].contains("\"dept:CAM\""))
    }

    /** A period key that is not a filename does not become a path. */
    @Test
    fun `the export file name keeps only the period's letters and digits`() {
        assertEquals("vat-ledger_18A1_2026-09-10.csv", ledgerFileName("18A1", "2026-09-10"))
        assertEquals("vat-ledger_period_2026-09-10.csv", ledgerFileName("../..", "2026-09-10"))
    }
}
