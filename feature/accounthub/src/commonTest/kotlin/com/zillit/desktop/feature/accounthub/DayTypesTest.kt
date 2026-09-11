package com.zillit.desktop.feature.accounthub

import com.zillit.desktop.feature.accounthub.domain.DayType
import com.zillit.desktop.feature.accounthub.domain.DayTypes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The project's day-type catalogue, which non-union deals copy at save time.
 *
 * The pay engine looks a day type up by its code, so the codes carry the
 * weight here: a duplicate means one of them is never found, and a renamed
 * `SWD` is a standard working day nothing recognises.
 */
class DayTypesTest {

    /** A fresh project shows the three, not an empty list. */
    @Test
    fun `an unsaved catalogue is seeded with the three standard days`() {
        val seeded = DayTypes.seeded(emptyList())

        assertEquals(listOf("SWD", "CWD", "SCWD"), seeded.map { it.dayType })
        assertEquals(600, seeded.first().workMinutes)
    }

    /**
     * A saved row wins over the seeded default of the same code.
     *
     * A production that shortened its standard day keeps the shorter one;
     * re-seeding over it would silently put ten hours back.
     */
    @Test
    fun `a saved standard day keeps its own figures`() {
        val seeded = DayTypes.seeded(
            listOf(DayType("SWD", "Standard Working Day", workMinutes = 540, mealBreakMinutes = 45)),
        )

        val swd = seeded.first { it.dayType == "SWD" }
        assertEquals(540, swd.workMinutes)
        assertEquals(45, swd.mealBreakMinutes)
        assertEquals(3, seeded.size)
    }

    /** Custom rows follow the three, in the order they were saved. */
    @Test
    fun `custom day types follow the standard ones`() {
        val seeded = DayTypes.seeded(
            listOf(
                DayType("NIGHT", "Night Shoot", workMinutes = 600),
                DayType("CWD", workMinutes = 540),
            ),
        )

        assertEquals(listOf("SWD", "CWD", "SCWD", "NIGHT"), seeded.map { it.dayType })
    }

    /**
     * Two rows cannot share a code.
     *
     * The engine looks a day type up by it, so a repeat means one of the two
     * is never found — and nothing on screen would say which.
     */
    @Test
    fun `a duplicate code is refused and named`() {
        val problem = DayTypes.problem(
            DayTypes.defaults + DayType("SWD", "My own", workMinutes = 480),
        )

        assertNotNull(problem)
        assertTrue(problem.contains("SWD"))
    }

    @Test
    fun `a day type with no code is refused`() {
        assertNotNull(DayTypes.problem(listOf(DayType("", workMinutes = 600))))
    }

    @Test
    fun `a working day must be given, and must fit inside one`() {
        assertNotNull(DayTypes.problem(listOf(DayType("SWD"))))
        assertNotNull(DayTypes.problem(listOf(DayType("SWD", workMinutes = 1441))))
        assertNull(DayTypes.problem(listOf(DayType("SWD", workMinutes = 1440))))
    }

    /**
     * A blank meal break and a zero one are different things.
     *
     * Unspecified versus an explicit "no formal break" — the penalty engine
     * reads them differently, so neither may be defaulted into the other.
     */
    @Test
    fun `an unspecified meal break is allowed, and is not zero`() {
        val unspecified = DayType("SWD", workMinutes = 600, mealBreakMinutes = null)
        val none = DayType("CWD", workMinutes = 540, mealBreakMinutes = 0)

        assertNull(DayTypes.problem(listOf(unspecified, none)))
        assertNull(unspecified.mealBreakMinutes)
        assertEquals(0, none.mealBreakMinutes)
    }

    @Test
    fun `a meal break longer than a day is refused`() {
        assertNotNull(
            DayTypes.problem(listOf(DayType("SWD", workMinutes = 600, mealBreakMinutes = 1441))),
        )
    }

    @Test
    fun `a catalogue over the limit is refused`() {
        val many = (1..DayTypes.MAX_ROWS + 1).map { DayType("D$it", workMinutes = 600) }

        assertNotNull(DayTypes.problem(many))
    }

    /** Default-ness is the code, which is what the engine reads. */
    @Test
    fun `the three standard codes are recognised as standard`() {
        assertTrue(DayTypes.isDefault(DayType("SWD")))
        assertTrue(DayTypes.isDefault(DayType("SCWD")))
        assertFalse(DayTypes.isDefault(DayType("NIGHT")))
    }

    @Test
    fun `a complete catalogue is accepted`() {
        assertNull(DayTypes.problem(DayTypes.defaults + DayType("NIGHT", "Night", workMinutes = 660)))
    }
}
