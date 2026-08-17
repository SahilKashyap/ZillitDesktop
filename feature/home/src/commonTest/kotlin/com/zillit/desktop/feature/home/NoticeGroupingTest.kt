package com.zillit.desktop.feature.home

import com.zillit.desktop.feature.home.domain.BoardRow
import com.zillit.desktop.feature.home.domain.Notice
import com.zillit.desktop.feature.home.domain.toClockTime
import com.zillit.desktop.feature.home.domain.withDateSeparators
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Date separators on the board, matching Android's `ppDateTv` row.
 *
 * Grouping is done in the **viewer's** timezone. A call sheet posted at 23:30
 * local belongs to that day for the person reading it; grouping by UTC would
 * push it onto tomorrow for anyone west of Greenwich.
 */
class NoticeGroupingTest {

    private val utc = TimeZone.UTC

    /** 2026-08-03T12:00:00Z */
    private val noon = 1_785_758_400_000L
    private val day = 86_400_000L

    private fun notice(id: String, at: Long) =
        Notice(id = id, body = "b", authorName = "A", createdAtMillis = at)

    @Test
    fun `a separator precedes each new day`() {
        val rows = listOf(
            notice("a", noon - day),
            notice("b", noon),
            notice("c", noon + 60_000),
        ).withDateSeparators(todayMillis = noon, zone = utc)

        // Separator, a, separator, b, c
        assertEquals(5, rows.size)
        assertTrue(rows[0] is BoardRow.Separator)
        assertTrue(rows[2] is BoardRow.Separator)
        assertTrue(rows[3] is BoardRow.Post && rows[4] is BoardRow.Post)
    }

    @Test
    fun `posts on the same day share one separator`() {
        val rows = listOf(notice("a", noon), notice("b", noon + 3_600_000))
            .withDateSeparators(todayMillis = noon, zone = utc)

        assertEquals(1, rows.count { it is BoardRow.Separator })
    }

    @Test
    fun `today and yesterday are named rather than dated`() {
        val rows = listOf(notice("y", noon - day), notice("t", noon))
            .withDateSeparators(todayMillis = noon, zone = utc)

        assertEquals("Yesterday", (rows[0] as BoardRow.Separator).label)
        assertEquals("Today", (rows[2] as BoardRow.Separator).label)
    }

    @Test
    fun `older days get a date so they can be placed`() {
        val rows = listOf(notice("old", noon - day * 5))
            .withDateSeparators(todayMillis = noon, zone = utc)

        val label = (rows[0] as BoardRow.Separator).label
        assertTrue(label.contains("2026"), "expected a dated label, got: $label")
        assertTrue(!label.contains("Today") && !label.contains("Yesterday"))
    }

    @Test
    fun `an empty board has no separators`() {
        assertTrue(emptyList<Notice>().withDateSeparators(noon, utc).isEmpty())
    }

    @Test
    fun `the day boundary follows the viewer's timezone`() {
        // 23:30 UTC on the 3rd is 00:30 on the 4th in Berlin. The two zones must
        // disagree about which day it is, or the grouping is not local.
        val lateEvening = noon + (11 * 3_600_000) + 1_800_000

        val utcLabel = listOf(notice("x", lateEvening))
            .withDateSeparators(todayMillis = noon, zone = utc)
            .filterIsInstance<BoardRow.Separator>().single().label
        val berlinLabel = listOf(notice("x", lateEvening))
            .withDateSeparators(todayMillis = noon, zone = TimeZone.of("Europe/Berlin"))
            .filterIsInstance<BoardRow.Separator>().single().label

        assertEquals("Today", utcLabel)
        assertTrue(berlinLabel != utcLabel, "the two zones should disagree about the day")
    }

    @Test
    fun `clock time is zero padded`() {
        assertEquals("12:00", noon.toClockTime(utc))
        assertEquals("00:05", (noon - (12 * 3_600_000) + 300_000).toClockTime(utc))
    }

    @Test
    fun `a post with no timestamp shows no time rather than 1970`() {
        assertEquals("", 0L.toClockTime(utc))
    }
}
