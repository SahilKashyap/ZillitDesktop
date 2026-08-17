package com.zillit.desktop.feature.productionreport

import com.zillit.desktop.feature.productionreport.domain.ReportTime
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The wall-clock wire — a transcription of the web's pinned quirk
 * (`wallClockWire.test.js`): writes are timezone-free strings, legacy epochs
 * fold to LOCAL wall clock, and the codec never invents a value.
 */
class ReportTimeTest {

    private val kolkata = TimeZone.of("Asia/Kolkata")

    @Test
    fun `valid clocks pass and single digits zero-pad`() {
        assertEquals("06:30", ReportTime.toWireTime("06:30"))
        assertEquals("06:30", ReportTime.toWireTime("6:30"))
        assertEquals("23:59", ReportTime.toWireTime("23:59"))
    }

    @Test
    fun `junk becomes empty, never a guess`() {
        listOf("", "25:00", "12:60", "O/C", "Per HOD", "6", "noon").forEach { junk ->
            assertEquals("", ReportTime.toWireTime(junk), "for input '$junk'")
        }
    }

    @Test
    fun `legacy epochs fold to the local wall clock`() {
        // 2025-08-14 06:30 IST as epoch ms.
        val epoch = 1_755_133_200_000L.toString()
        assertEquals("06:30", ReportTime.toWireTime(epoch, kolkata))
    }

    @Test
    fun `codec is idempotent`() {
        val once = ReportTime.toWireTime("6:30")
        assertEquals(once, ReportTime.toWireTime(once))
        val date = ReportTime.toWireDate("2026-07-16")
        assertEquals(date, ReportTime.toWireDate(date))
    }

    @Test
    fun `dates pass or fold and slashes are rejected`() {
        assertEquals("2026-07-16", ReportTime.toWireDate("2026-07-16"))
        assertEquals("", ReportTime.toWireDate("16/07/2026"))
        // 2025-08-14 23:30 IST — must not roll to the next day.
        assertEquals("2025-08-14", ReportTime.toWireDate("1755194400000", kolkata))
    }

    @Test
    fun `in-out cells are mode-prefixed`() {
        assertEquals("Per HOD", ReportTime.encodeInOut("per hod"))
        assertEquals("O/C", ReportTime.encodeInOut("o/c"))
        assertEquals("Time:06:30", ReportTime.encodeInOut("6:30"))
        assertEquals("Other:ask the gaffer", ReportTime.encodeInOut("ask the gaffer"))
        assertEquals("", ReportTime.encodeInOut("  "))
        assertEquals("06:30", ReportTime.displayInOut("Time:06:30"))
        assertEquals("ask the gaffer", ReportTime.displayInOut("Other:ask the gaffer"))
        assertEquals("Per HOD", ReportTime.displayInOut("Per HOD"))
    }

    @Test
    fun `legacy Time-epoch entries decode`() {
        val epoch = 1_755_133_200_000L
        assertEquals("06:30", ReportTime.displayInOut("Time:$epoch", kolkata))
        assertEquals("Time:06:30", ReportTime.encodeInOut("Time:$epoch", kolkata))
    }
}
