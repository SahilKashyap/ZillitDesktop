package com.zillit.desktop.feature.crewdood

import com.zillit.desktop.feature.crewdood.domain.DoodCalendar
import com.zillit.desktop.feature.crewdood.domain.DoodHoliday
import com.zillit.desktop.feature.crewdood.ui.CalendarDraft
import com.zillit.desktop.feature.crewdood.ui.rebase
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What the days off & holidays editor does when a calendar someone else saved
 * lands while it is open.
 *
 * The rule the web settled on: never silently throw away what is being typed
 * here, and never make an untouched editor look edited.
 */
class CalendarRebaseTest {

    private val saturdaySunday = CalendarDraft(weeklyOff = setOf(0, 6), holidays = emptyList())

    private fun draft(vararg weeklyOff: Int, holiday: String? = null) = CalendarDraft(
        weeklyOff = weeklyOff.toSet(),
        holidays = holiday?.let { listOf(DoodHoliday(LocalDate.parse(it), "Founders Day")) } ?: emptyList(),
    )

    @Test
    fun `the same calendar again moves nothing`() {
        val edited = draft(0, 1, 6)
        val rebased = rebase(draft = edited, base = saturdaySunday, saved = saturdaySunday)
        assertEquals(edited, rebased.draft)
        assertEquals(saturdaySunday, rebased.base)
        assertFalse(rebased.overwrite)
    }

    @Test
    fun `an untouched editor follows the calendar someone else saved`() {
        val theirs = draft(0, 5, 6)
        val rebased = rebase(draft = saturdaySunday, base = saturdaySunday, saved = theirs)
        assertEquals(theirs, rebased.draft)
        assertEquals(theirs, rebased.base)
        // Nothing was edited here, so Save has nothing to replace.
        assertFalse(rebased.overwrite)
        assertEquals(rebased.draft, rebased.base)
    }

    @Test
    fun `edits that already match theirs are our own save coming back`() {
        val theirs = draft(0, 5, 6)
        val rebased = rebase(draft = theirs, base = saturdaySunday, saved = theirs)
        assertEquals(theirs, rebased.draft)
        assertEquals(theirs, rebased.base)
        assertFalse(rebased.overwrite)
    }

    @Test
    fun `edits that differ are kept, and saving is known to replace theirs`() {
        val mine = draft(0, 1, 6)
        val theirs = draft(0, 5, 6)
        val rebased = rebase(draft = mine, base = saturdaySunday, saved = theirs)
        // The draft stands — a holiday half typed here is not thrown away.
        assertEquals(mine, rebased.draft)
        // The baseline is theirs, so Load theirs drops the edits for it...
        assertEquals(theirs, rebased.base)
        // ...and until then Save is still live, and says what it will do.
        assertTrue(rebased.overwrite)
        assertTrue(rebased.draft != rebased.base)
    }

    @Test
    fun `a holiday added here survives a calendar saved elsewhere`() {
        val mine = draft(0, 6, holiday = "2026-04-02")
        val theirs = draft(0, 5, 6)
        val rebased = rebase(draft = mine, base = saturdaySunday, saved = theirs)
        assertEquals(listOf(LocalDate.parse("2026-04-02")), rebased.draft.holidays.map { it.date })
        assertTrue(rebased.overwrite)
    }

    @Test
    fun `a stamp alone is not a change`() {
        // The same calendar with a new `updated_at` is the same calendar: the
        // draft is built from the two fields, and nothing moves.
        val stamped = CalendarDraft.of(DoodCalendar(weeklyOff = setOf(0, 6), updatedAt = 9_000L))
        assertEquals(saturdaySunday, stamped)
        val edited = draft(0, 1, 6)
        assertEquals(edited, rebase(draft = edited, base = saturdaySunday, saved = stamped).draft)
        assertFalse(rebase(draft = edited, base = saturdaySunday, saved = stamped).overwrite)
    }
}
