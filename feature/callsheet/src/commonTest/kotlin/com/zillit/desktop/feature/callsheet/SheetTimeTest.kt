package com.zillit.desktop.feature.callsheet

import com.zillit.desktop.feature.callsheet.domain.SheetTime
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The epoch codec, pinned in a fixed zone so the numbers are the same on
 * every machine that runs this.
 */
class SheetTimeTest {

    private val kolkata = TimeZone.of("Asia/Kolkata")

    /** 2026-09-13T00:00 IST. */
    private val day = 1_789_237_800_000L

    @Test
    fun `a time cell shows the local clock of its epoch and anything else as written`() {
        val nine = SheetTime.encodeClock(9, 30, sheetDateMs = day, nowMs = 0L, zone = kolkata)
        assertEquals("09:30", SheetTime.clockOf(nine, kolkata))
        assertEquals(9 to 30, SheetTime.clockParts(nine, kolkata))
        assertEquals("O/C", SheetTime.clockOf("O/C", kolkata))
        assertEquals("12", SheetTime.clockOf("12", kolkata), "a small number is a number, not an epoch")
        assertNull(SheetTime.clockParts("", kolkata))
    }

    @Test
    fun `a time is anchored to the sheet's own day, and to today without one`() {
        val onSheetDay = SheetTime.encodeClock(6, 0, sheetDateMs = day, nowMs = 0L, zone = kolkata).toLong()
        assertEquals(LocalDate(2026, 9, 13), SheetTime.localDate(onSheetDay, kolkata))
        val tomorrow = day + 86_400_000L
        val onToday = SheetTime.encodeClock(6, 0, sheetDateMs = null, nowMs = tomorrow + 3_600_000L, zone = kolkata)
        assertEquals(LocalDate(2026, 9, 14), SheetTime.localDate(onToday.toLong(), kolkata))
    }

    @Test
    fun `dates are local midnight and show as 05 Mar 2025`() {
        val midnight = SheetTime.encodeDate(LocalDate(2025, 3, 5), kolkata)
        assertEquals("05 Mar 2025", SheetTime.dateOf(midnight.toString(), kolkata))
        assertEquals(LocalDate(2025, 3, 5), SheetTime.dateParts(midnight.toString(), kolkata))
        assertEquals(midnight, SheetTime.todayMidnight(midnight + 50_000_000L, kolkata))
        assertEquals("Wednesday 5th March, 2025", SheetTime.headerDate(midnight, kolkata))
        assertEquals("", SheetTime.headerDate(null, kolkata))
    }

    @Test
    fun `ordinal suffixes follow English`() {
        assertEquals("Monday 1st September, 2025", SheetTime.longDate(LocalDate(2025, 9, 1)))
        assertEquals("Tuesday 2nd September, 2025", SheetTime.longDate(LocalDate(2025, 9, 2)))
        assertEquals("Wednesday 3rd September, 2025", SheetTime.longDate(LocalDate(2025, 9, 3)))
        assertEquals("Thursday 11th September, 2025", SheetTime.longDate(LocalDate(2025, 9, 11)))
        assertEquals("Monday 22nd September, 2025", SheetTime.longDate(LocalDate(2025, 9, 22)))
    }

    @Test
    fun `the In column keeps its four modes and reads a Time epoch as a clock`() {
        val time = SheetTime.encodeInTime(7, 5, sheetDateMs = day, nowMs = 0L, zone = kolkata)
        assertTrue(time.startsWith("Time:"))
        assertEquals("07:05", SheetTime.inDisplay(time, kolkata))
        assertEquals(7 to 5, SheetTime.inClockParts(time, kolkata))
        assertEquals(SheetTime.InMode.Time, SheetTime.inModeOf(time))
        assertEquals(SheetTime.InMode.PerHod, SheetTime.inModeOf("Per HOD"))
        assertEquals(SheetTime.InMode.OnCall, SheetTime.inModeOf("O/C"))
        assertEquals("late", SheetTime.inDisplay("Other:late", kolkata))
        assertEquals("Time", SheetTime.inDisplay("Time:", kolkata), "a mode picked without a clock reads as its label")
        assertEquals(8 to 15, SheetTime.inClockParts("Time:08:15", kolkata), "a legacy clock still reads")
        assertEquals("", SheetTime.inValueFor(SheetTime.InMode.None))
        assertEquals("Other:", SheetTime.inValueFor(SheetTime.InMode.Other))
    }

    @Test
    fun `the script strip prints an epoch as a long date-time and text as written`() {
        val at = SheetTime.encodeClock(9, 5, sheetDateMs = day, nowMs = 0L, zone = kolkata)
        assertEquals("Sunday 13th September, 2026, 9:05 AM", SheetTime.stripDateTime(at, kolkata))
        assertEquals("v1.4 (12 Mar)", SheetTime.stripDateTime("v1.4 (12 Mar)", kolkata))
    }

    @Test
    fun `leaving a time or date column folds epochs to text once`() {
        val at = SheetTime.encodeClock(18, 45, sheetDateMs = day, nowMs = 0L, zone = kolkata)
        assertEquals("18:45", SheetTime.convertOnTypeChange(at, from = "time", to = "text", zone = kolkata))
        val midnight = SheetTime.encodeDate(LocalDate(2026, 9, 13), kolkata).toString()
        val folded = SheetTime.convertOnTypeChange(midnight, from = "date", to = "text", zone = kolkata)
        assertEquals("13 Sept 2026", folded)
        assertEquals(at, SheetTime.convertOnTypeChange(at, from = "time", to = "date", zone = kolkata))
        assertEquals("abc", SheetTime.convertOnTypeChange("abc", from = "time", to = "text", zone = kolkata))
        assertFalse(SheetTime.isEpoch("123"))
        assertTrue(SheetTime.isEpoch(at))
    }
}
