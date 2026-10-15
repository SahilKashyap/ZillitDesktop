package com.zillit.desktop.feature.crewdood

import com.zillit.desktop.feature.crewdood.domain.DayCode
import com.zillit.desktop.feature.crewdood.domain.DoodBand
import com.zillit.desktop.feature.crewdood.domain.DoodBooking
import com.zillit.desktop.feature.crewdood.domain.DoodCalc
import com.zillit.desktop.feature.crewdood.domain.DoodCalendar
import com.zillit.desktop.feature.crewdood.domain.DoodCrewDepartment
import com.zillit.desktop.feature.crewdood.domain.DoodCrewUnit
import com.zillit.desktop.feature.crewdood.domain.DoodCrewUser
import com.zillit.desktop.feature.crewdood.domain.DoodDeal
import com.zillit.desktop.feature.crewdood.domain.DoodHoliday
import com.zillit.desktop.feature.crewdood.domain.DoodMasterDepartment
import com.zillit.desktop.feature.crewdood.domain.DoodMasterDesignation
import com.zillit.desktop.feature.crewdood.domain.DoodPhaseKind
import com.zillit.desktop.feature.crewdood.domain.DoodSources
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The model's rules, as §2 of the Crew DOOD handoff states them — and §8's
 * worked example end to end.
 */
class DoodCalcTest {

    private fun day(key: String) = LocalDate.parse(key)

    private fun april(dayOfMonth: Int) = LocalDate(2026, 4, dayOfMonth)

    private val saturdaySunday = DoodCalendar.Default

    @Suppress("LongParameterList") // A deal's whole shape, so each test names only what it changes.
    private fun deal(
        user: String,
        start: String?,
        end: String? = null,
        status: String = "active",
        lastPay: String? = null,
        id: String = "d-$user",
        crewName: String = "",
        designation: String = "",
        designationRefs: List<String> = emptyList(),
        departmentRefs: List<String> = emptyList(),
    ) = DoodDeal(
        id = id,
        status = status,
        userId = user,
        reference = "DM-$id",
        crewName = crewName,
        customDesignation = designation,
        designationRefs = designationRefs,
        departmentRefs = departmentRefs,
        start = start?.let(::day),
        end = end?.let(::day),
        lastPayDay = lastPay?.let(::day),
    )

    // ── Rows ────────────────────────────────────────────────────────────────

    @Test
    fun `crew list order survives, units merge, a person listed twice keeps one row`() {
        val camera = { users: List<DoodCrewUser> ->
            DoodCrewDepartment("Camera", listOf("cam"), users)
        }
        val units = listOf(
            DoodCrewUnit(
                listOf(
                    camera(listOf(DoodCrewUser("u1", "Ann", "DOP"), DoodCrewUser("u2", "Ben", "1st AC"))),
                    DoodCrewDepartment("Sound", listOf("snd"), listOf(DoodCrewUser("u3", "Cat", "Mixer"))),
                ),
            ),
            DoodCrewUnit(
                listOf(camera(listOf(DoodCrewUser("u4", "Dan", "2nd AC"), DoodCrewUser("u1", "Ann", "DOP")))),
            ),
        )
        val groups = DoodCalc.departmentsFromCrewList(units)
        assertEquals(listOf("Camera", "Sound"), groups.map { it.name })
        assertEquals(listOf("Ann", "Ben", "Dan"), groups[0].crew.map { it.name })
    }

    @Test
    fun `only active and deactivated deals book anyone`() {
        val deals = listOf(
            deal("u1", "2026-04-01", "2026-04-02"),
            deal("u2", "2026-04-01", "2026-04-02", status = "deactivated"),
            deal("u3", "2026-04-01", "2026-04-02", status = "issued"),
            deal("u4", "2026-04-01", "2026-04-02", status = "approved"),
            deal("u5", "2026-04-01", "2026-04-02", status = "completed"),
        )
        assertEquals(setOf("u1", "u2"), DoodCalc.bookingsFromDeals(deals).keys)
    }

    // ── Start and finish ────────────────────────────────────────────────────

    @Test
    fun `WF lands on the earlier of the last pay day and the end date`() {
        val bookings = DoodCalc.bookingsFromDeals(
            listOf(deal("u1", "2026-04-01", "2026-04-30", lastPay = "2026-04-10")),
        )
        assertEquals(day("2026-04-10"), bookings.getValue("u1").single().end)
    }

    @Test
    fun `a last pay day before the start is ignored`() {
        val bookings = DoodCalc.bookingsFromDeals(
            listOf(deal("u1", "2026-04-06", "2026-04-30", lastPay = "2026-04-01")),
        )
        assertEquals(day("2026-04-30"), bookings.getValue("u1").single().end)
    }

    @Test
    fun `no end date and no last pay day is open-ended`() {
        val bookings = DoodCalc.bookingsFromDeals(listOf(deal("u1", "2026-04-06")))
        assertNull(bookings.getValue("u1").single().end)
    }

    @Test
    fun `a deal with no start, or one that ends before it starts, is skipped`() {
        val deals = listOf(
            deal("u1", null, "2026-04-10"),
            deal("u2", "2026-04-10", "2026-04-01"),
        )
        assertTrue(DoodCalc.bookingsFromDeals(deals).isEmpty())
    }

    // ── Days off and holidays ───────────────────────────────────────────────

    @Test
    fun `nothing saved means Saturday and Sunday off, no holidays`() {
        val calendar = DoodCalc.calendarFrom(null, emptyList())
        assertEquals(setOf(0, 6), calendar.weeklyOff)
        assertTrue(calendar.holidays.isEmpty())
        assertTrue(calendar.isDayOff(day("2026-04-04")))
        assertTrue(calendar.isDayOff(day("2026-04-05")))
        assertTrue(!calendar.isDayOff(day("2026-04-06")))
    }

    @Test
    fun `a start on a day off moves to the next working day, a finish to the one before`() {
        val dates = DoodCalc.datesBetween(day("2026-04-03"), day("2026-04-08"))
        val cells = DoodCalc.cellsFor(
            listOf(DoodBooking(day("2026-04-04"), day("2026-04-05"))),
            dates,
            saturdaySunday,
        )
        // Saturday to Sunday is all days off: nothing shows at all.
        assertTrue(cells.all { it == DayCode.Blank })

        val longer = DoodCalc.cellsFor(
            listOf(DoodBooking(day("2026-04-04"), day("2026-04-07"))),
            dates,
            saturdaySunday,
        )
        assertEquals(
            listOf(DayCode.Blank, DayCode.Blank, DayCode.Blank, DayCode.Start, DayCode.Finish, DayCode.Blank),
            longer,
        )
    }

    @Test
    fun `a holiday inside a booking reads HOL and beats a weekly day off`() {
        val calendar = DoodCalc.calendarFrom(
            listOf(0, 6),
            listOf(DoodHoliday(day("2026-04-04"), "Founders Day")),
        )
        val dates = DoodCalc.datesBetween(day("2026-04-02"), day("2026-04-07"))
        val cells = DoodCalc.cellsFor(listOf(DoodBooking(day("2026-04-02"), day("2026-04-07"))), dates, calendar)
        assertEquals(
            listOf(DayCode.Start, DayCode.Work, DayCode.Hol, DayCode.Off, DayCode.Work, DayCode.Finish),
            cells,
        )
        assertEquals(4, DoodCalc.bookedDays(cells))
    }

    @Test
    fun `days off outside a booking stay blank`() {
        val dates = DoodCalc.datesBetween(day("2026-04-04"), day("2026-04-09"))
        val cells = DoodCalc.cellsFor(listOf(DoodBooking(day("2026-04-08"), day("2026-04-09"))), dates, saturdaySunday)
        assertEquals(listOf(DayCode.Blank, DayCode.Blank, DayCode.Blank, DayCode.Blank), cells.take(4))
    }

    @Test
    fun `one working day is SWF, even when days off shrink the deal to it`() {
        val dates = DoodCalc.datesBetween(day("2026-04-03"), day("2026-04-06"))
        val shrunk = DoodCalc.cellsFor(
            listOf(DoodBooking(day("2026-04-04"), day("2026-04-06"))),
            dates,
            saturdaySunday,
        )
        assertEquals(DayCode.Single, shrunk.last())
        val oneDay = DoodCalc.cellsFor(
            listOf(DoodBooking(day("2026-04-03"), day("2026-04-03"))),
            dates,
            saturdaySunday,
        )
        assertEquals(DayCode.Single, oneDay.first())
    }

    @Test
    fun `where two deals meet, the higher code wins`() {
        val dates = DoodCalc.datesBetween(day("2026-04-06"), day("2026-04-10"))
        val cells = DoodCalc.cellsFor(
            listOf(
                DoodBooking(day("2026-04-06"), day("2026-04-08")),
                DoodBooking(day("2026-04-08"), day("2026-04-10")),
            ),
            dates,
            saturdaySunday,
        )
        // Wednesday is both a finish and a start: it reads as the new start.
        assertEquals(DayCode.Start, cells[2])
    }

    // ── The chart's window ──────────────────────────────────────────────────

    @Test
    fun `the window widens to whole Monday to Sunday weeks`() {
        val bookings = mapOf("u1" to listOf(DoodBooking(day("2026-04-01"), day("2026-04-08"))))
        val dates = DoodCalc.dateRange(bookings, emptyList(), day("2026-04-01"))
        assertEquals(day("2026-03-30"), dates.first())
        assertEquals(day("2026-04-12"), dates.last())
        assertEquals(14, dates.size)
    }

    @Test
    fun `nothing to show opens on this week plus three`() {
        // Today plus 27 days, then widened to whole weeks: from a Monday that
        // is exactly four weeks, and from any other day the widening adds the
        // rest of the week it lands in.
        val monday = DoodCalc.dateRange(emptyMap(), emptyList(), day("2026-04-06"))
        assertEquals(day("2026-04-06"), monday.first())
        assertEquals(28, monday.size)

        val midweek = DoodCalc.dateRange(emptyMap(), emptyList(), day("2026-04-08"))
        assertEquals(day("2026-04-06"), midweek.first())
        assertEquals(day("2026-05-10"), midweek.last())
    }

    @Test
    fun `a malformed end date cannot build more than the cap`() {
        val bookings = mapOf("u1" to listOf(DoodBooking(day("2026-04-01"), day("2126-04-01"))))
        assertEquals(DoodCalc.MAX_DAYS, DoodCalc.dateRange(bookings, emptyList(), day("2026-04-01")).size)
    }

    // ── Phases ──────────────────────────────────────────────────────────────

    @Test
    fun `shoot days are numbered, prep counts back in weeks and post counts on`() {
        val shootDays = listOf(day("2026-04-06"), day("2026-04-07"), day("2026-04-10"))
        val dates = DoodCalc.datesBetween(day("2026-03-30"), day("2026-04-19"))
        val phases = DoodCalc.phasesFor(dates, shootDays)
        val byDay = dates.zip(phases).toMap()
        assertEquals(DoodPhaseKind.Prep, byDay.getValue(day("2026-03-30"))?.kind)
        assertEquals(1, byDay.getValue(day("2026-03-30"))?.n)
        assertEquals(1, byDay.getValue(day("2026-04-06"))?.n)
        assertEquals(3, byDay.getValue(day("2026-04-10"))?.n)
        // A non-shoot day between the first and last stays in the Shoot band.
        assertEquals(DoodPhaseKind.Rest, byDay.getValue(day("2026-04-08"))?.kind)
        assertEquals(DoodBand.Shoot, byDay.getValue(day("2026-04-08"))?.band)
        assertEquals(DoodPhaseKind.Post, byDay.getValue(day("2026-04-13"))?.kind)
        assertEquals(1, byDay.getValue(day("2026-04-13"))?.n)
        assertEquals(2, byDay.getValue(day("2026-04-19"))?.n)
    }

    @Test
    fun `no production dates means no phases`() {
        val dates = DoodCalc.datesBetween(day("2026-04-06"), day("2026-04-10"))
        assertTrue(DoodCalc.phasesFor(dates, emptyList()).all { it == null })
    }

    // ── Weeks view ──────────────────────────────────────────────────────────

    @Test
    fun `a week counts against its working days and keeps the start`() {
        val dates = DoodCalc.datesBetween(day("2026-04-06"), day("2026-04-12"))
        val columns = DoodCalc.weekColumns(dates, DoodCalc.phasesFor(dates, emptyList()), saturdaySunday)
        val week = columns.single()
        assertEquals(5, week.workdays)
        val cells = DoodCalc.cellsFor(listOf(DoodBooking(day("2026-04-06"), day("2026-04-08"))), dates, saturdaySunday)
        val cell = DoodCalc.cellOf(cells, week)
        assertEquals(DayCode.Start, cell.code)
        assertEquals(3, cell.booked)
    }

    @Test
    fun `a week with nothing worked but inside a booking still says HOL`() {
        val calendar = DoodCalc.calendarFrom(
            listOf(0, 6),
            (6..10).map { DoodHoliday(april(it), "Shutdown") },
        )
        val dates = DoodCalc.datesBetween(day("2026-04-06"), day("2026-04-12"))
        val columns = DoodCalc.weekColumns(dates, DoodCalc.phasesFor(dates, emptyList()), calendar)
        val cells = DoodCalc.cellsFor(listOf(DoodBooking(day("2026-04-01"), day("2026-04-20"))), dates, calendar)
        val cell = DoodCalc.cellOf(cells, columns.single())
        assertEquals(DayCode.Hol, cell.code)
        assertEquals(0, cell.booked)
        assertEquals(0, columns.single().workdays)
    }

    @Test
    fun `a week whose only booked day is a one-day booking shows SWF`() {
        val dates = DoodCalc.datesBetween(day("2026-04-06"), day("2026-04-12"))
        val columns = DoodCalc.weekColumns(dates, DoodCalc.phasesFor(dates, emptyList()), saturdaySunday)
        val cells = DoodCalc.cellsFor(listOf(DoodBooking(day("2026-04-07"), day("2026-04-07"))), dates, saturdaySunday)
        assertEquals(DayCode.Single, DoodCalc.cellOf(cells, columns.single()).code)
    }

    // ── Holidays, as the editor handles them ────────────────────────────────

    @Test
    fun `consecutive days with the same name group into one row`() {
        val runs = DoodCalc.groupHolidays(
            listOf(
                DoodHoliday(day("2026-12-24"), "Christmas"),
                DoodHoliday(day("2026-12-25"), "Christmas"),
                DoodHoliday(day("2026-12-26"), "Christmas"),
                DoodHoliday(day("2026-12-28"), "Christmas"),
                DoodHoliday(day("2026-04-02"), "Founders Day"),
            ),
        )
        assertEquals(3, runs.size)
        assertEquals("Founders Day", runs[0].name)
        assertEquals(3, runs[1].dates.size)
        assertEquals(day("2026-12-28"), runs[2].start)
    }

    @Test
    fun `a range is capped at a year`() {
        val dates = DoodCalc.datesBetween(day("2026-01-01"), day("2030-01-01"))
        assertEquals(DoodCalc.MAX_HOLIDAY_RANGE, dates.size)
        assertTrue(DoodCalc.datesBetween(day("2026-04-10"), day("2026-04-01")).isEmpty())
    }

    @Test
    fun `a duplicated holiday keeps the name it was first given`() {
        val calendar = DoodCalc.calendarFrom(
            listOf(0),
            listOf(DoodHoliday(day("2026-04-02"), "Founders Day"), DoodHoliday(day("2026-04-02"), "Something else")),
        )
        assertEquals("Founders Day", calendar.holidays.getValue(day("2026-04-02")))
    }

    // ── Externals ───────────────────────────────────────────────────────────

    @Test
    fun `someone with a booking deal but no crew-list row lands at the end of their department`() {
        val units = listOf(
            DoodCrewUnit(
                listOf(
                    DoodCrewDepartment("Camera", listOf("cam"), listOf(DoodCrewUser("u1", "Ann", "DOP"))),
                ),
            ),
        )
        val directory = listOf(
            DoodMasterDepartment("Camera", listOf("cam", "department_camera"), emptyList()),
            DoodMasterDepartment(
                "Sound",
                listOf("snd"),
                listOf(DoodMasterDesignation("Boom Operator", listOf("desig-boom"))),
            ),
        )
        val deals = listOf(
            deal(
                user = "",
                start = "2026-04-06",
                end = "2026-04-10",
                id = "d-zed",
                crewName = "Zed",
                designation = "Steadicam",
                departmentRefs = listOf("department_camera"),
            ),
            deal(
                user = "",
                start = "2026-04-06",
                end = "2026-04-10",
                id = "d-bo",
                crewName = "Bo",
                designationRefs = listOf("desig-boom"),
                departmentRefs = listOf("snd"),
            ),
            deal(user = "", start = "2026-04-06", id = "d-nn", crewName = "Nil"),
        )
        val model = DoodCalc.buildDood(
            sources = DoodSources(units = units, deals = deals, directory = directory),
            today = day("2026-04-06"),
            noDepartment = "No department",
        )
        assertEquals(listOf("Camera", "Sound", "No department"), model.departments.map { it.name })
        assertEquals(listOf("Ann", "Zed"), model.departments[0].crew.map { it.name })
        assertEquals("Steadicam", model.departments[0].crew[1].role)
        assertEquals("Boom Operator", model.departments[1].crew.single().role)
        assertEquals("Nil", model.departments[2].crew.single().name)
    }

    @Test
    fun `one external with several deals is one row`() {
        val deals = listOf(
            deal(user = "", start = "2026-04-06", end = "2026-04-07", id = "a", crewName = "Zed"),
            deal(user = "", start = "2026-04-09", end = "2026-04-10", id = "b", crewName = "Zed"),
        )
        val model = DoodCalc.buildDood(
            sources = DoodSources(deals = deals),
            today = day("2026-04-06"),
            noDepartment = "No department",
        )
        val person = model.departments.single().crew.single()
        assertEquals("Zed", person.name)
        assertEquals(4, person.days)
    }

    // ── The handoff's worked example ────────────────────────────────────────

    @Suppress("LongMethod") // §8 of the handoff, in one test: five rows, the window, the bands, the counts.
    @Test
    fun `the handoff's worked example, end to end`() {
        val units = listOf(
            DoodCrewUnit(
                listOf(
                    DoodCrewDepartment(
                        "Camera",
                        listOf("cam"),
                        listOf(
                            DoodCrewUser("u1", "Ann", "DOP"),
                            DoodCrewUser("u2", "Ben", "1st AC"),
                            DoodCrewUser("u3", "Dee", "2nd AC"),
                        ),
                    ),
                    DoodCrewDepartment("Sound", listOf("snd"), listOf(DoodCrewUser("u4", "Cat", "Mixer"))),
                ),
            ),
        )
        val deals = listOf(
            deal("u1", "2026-04-01", "2026-04-08"),
            deal("u2", "2026-04-01", "2026-04-30", status = "deactivated", lastPay = "2026-04-01"),
            deal("u3", "2026-04-01", "2026-04-10", status = "issued"),
            deal(
                user = "",
                start = "2026-04-06",
                end = "2026-04-10",
                id = "d-zed",
                crewName = "Zed",
                designation = "Steadicam",
                departmentRefs = listOf("cam"),
            ),
            deal("u4", "2026-04-06", "2026-04-10"),
        )
        val calendar = DoodCalc.calendarFrom(
            listOf(0, 6),
            listOf(DoodHoliday(day("2026-04-02"), "Founders Day")),
        )
        val sources = DoodSources(
            units = units,
            deals = deals,
            shootDays = (6..10).map(::april),
            calendar = calendar,
        )
        val model = DoodCalc.buildDood(sources, today = day("2026-04-01"), noDepartment = "No department")

        // The chart: Mon 30 Mar – Sun 12 Apr.
        assertEquals(day("2026-03-30"), model.dates.first())
        assertEquals(day("2026-04-12"), model.dates.last())

        val byName = model.departments.flatMap { it.crew }.associateBy { it.name }
        assertEquals(listOf("Ann", "Ben", "Dee", "Zed"), model.departments[0].crew.map { it.name })

        val ann = byName.getValue("Ann")
        assertEquals(
            listOf(
                DayCode.Blank, DayCode.Blank,
                DayCode.Start, DayCode.Hol, DayCode.Work, DayCode.Off, DayCode.Off,
                DayCode.Work, DayCode.Work, DayCode.Finish,
                DayCode.Blank, DayCode.Blank, DayCode.Blank, DayCode.Blank,
            ),
            ann.cells,
        )
        assertEquals(5, ann.days)
        assertEquals(DayCode.Single, byName.getValue("Ben").cells[2])
        assertEquals(1, byName.getValue("Ben").days)
        assertEquals(0, byName.getValue("Dee").days)
        assertEquals(5, byName.getValue("Zed").days)
        assertEquals(5, byName.getValue("Cat").days)

        // Bands: 30 Mar – 5 Apr Prep W1, 6–10 Apr Shoot Day 1–5, 11–12 Apr Post W1.
        val phases = model.dates.zip(model.phases).toMap()
        assertEquals(DoodPhaseKind.Prep, phases.getValue(day("2026-03-30"))?.kind)
        assertEquals(1, phases.getValue(day("2026-04-06"))?.n)
        assertEquals(5, phases.getValue(day("2026-04-10"))?.n)
        assertEquals(DoodPhaseKind.Post, phases.getValue(day("2026-04-11"))?.kind)

        // Number of Crew on each day.
        assertEquals(
            listOf(0, 0, 2, 0, 1, 0, 0, 3, 3, 3, 2, 2, 0, 0),
            DoodCalc.crewPerDay(model.departments.flatMap { it.crew }, model.dates.size),
        )
    }

    @Test
    fun `runs collapse equal neighbours`() {
        val runs = DoodCalc.runs(listOf("a", "a", "b", "a"))
        assertEquals(3, runs.size)
        assertEquals(2, runs[0].count)
        assertEquals(3, runs[2].from)
    }
}
