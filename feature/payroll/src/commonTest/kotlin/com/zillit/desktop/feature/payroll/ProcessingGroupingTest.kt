package com.zillit.desktop.feature.payroll

import com.zillit.desktop.core.common.Money
import com.zillit.desktop.feature.payroll.domain.DaySummary
import com.zillit.desktop.feature.payroll.domain.DeductionLine
import com.zillit.desktop.feature.payroll.domain.OutstandingRow
import com.zillit.desktop.feature.payroll.domain.PayrollPerson
import com.zillit.desktop.feature.payroll.domain.PayrollTimecard
import com.zillit.desktop.feature.payroll.domain.TimecardStatus
import com.zillit.desktop.feature.payroll.ui.PayrollUiState
import com.zillit.desktop.feature.payroll.ui.ProcessingState
import com.zillit.desktop.feature.payroll.ui.ProcessingView
import com.zillit.desktop.feature.payroll.ui.processing.GridItem
import com.zillit.desktop.feature.payroll.ui.processing.groupedByDepartment
import com.zillit.desktop.feature.payroll.ui.processing.outstandingShownRows
import com.zillit.desktop.feature.payroll.ui.processing.processingSummary
import com.zillit.desktop.feature.payroll.ui.processing.ungrouped
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Processing's department headers, its Week Summary card, and Outstanding's search and order. */
class ProcessingGroupingTest {

    /** Monday 2026-08-03, midnight UTC. */
    private val week = 1_785_715_200_000L

    // -- the grouping -------------------------------------------------------------------------

    private data class Crew(val id: String, val department: String)

    private val crew = listOf(Crew("a", "camera"), Crew("b", "art"), Crew("c", "camera"), Crew("d", ""))

    @Test
    fun `crew sit under their department's header, departments in label order, rows in the order they came`() {
        val items = groupedByDepartment(
            rows = crew,
            key = { it.id },
            department = { it.department },
            label = { it.ifEmpty { "Unassigned" } },
            money = { group -> "£${group.size}" },
        )
        val shape = items.map { item ->
            when (item) {
                is GridItem.Header -> "${item.label}:${item.crew}:${item.money}"
                is GridItem.Entry -> item.row.id
            }
        }
        assertEquals(listOf("art:1:£1", "b", "camera:2:£2", "a", "c", "Unassigned:1:£1", "d"), shape)
        assertEquals(items.size, items.map { it.key }.distinct().size, "every line has its own key")
    }

    @Test
    fun `a view that states no money has headers without it, and one department has no headers`() {
        val items = groupedByDepartment(crew, { it.id }, { it.department }, { it }, money = null)
        assertTrue(items.filterIsInstance<GridItem.Header>().all { it.money == null })
        assertEquals(crew.map { it.id }, ungrouped(crew) { it.id }.map { (it as GridItem.Entry).row.id })
    }

    // -- Outstanding --------------------------------------------------------------------------

    private fun week(user: String, basic: Double, deductions: Double = 0.0) = PayrollTimecard(
        id = "$user-w",
        userId = user,
        status = TimecardStatus.Approved,
        weekStarting = week,
        currency = "GBP",
        basicPay = basic,
        deductions = if (deductions > 0) {
            listOf(DeductionLine("d", "Advance", "flat", deductions, deductions, null))
        } else {
            emptyList()
        },
    )

    private fun state(vararg users: Pair<String, String>, processing: ProcessingState) = PayrollUiState(
        viewer = controller,
        metadataLoaded = true,
        now = week + 2 * 86_400_000L,
        people = users.associate { (id, department) ->
            id to PayrollPerson(id, "Crew $id", department, "designation_gaffer")
        },
        processing = processing,
    )

    @Test
    fun `outstanding is heaviest by gross, not by what is left after deductions`() {
        // Net: big 100, small 300. Gross: big 400, small 300.
        val rows = OutstandingRow.of(listOf(week("small", 300.0), week("big", 400.0, deductions = 300.0)), week)
        assertEquals(listOf("big", "small"), rows.map { it.userId })
        assertEquals(listOf(400.0, 300.0), rows.map { it.gross })
        assertEquals(listOf(100.0, 300.0), rows.map { it.total })
    }

    @Test
    fun `outstanding search finds a crew member by their department's name`() {
        val ui = state(
            "u1" to "department_camera",
            "u2" to "department_art",
            processing = ProcessingState(
                weekStarting = week,
                view = ProcessingView.Outstanding,
                outstanding = listOf(week("u1", 100.0), week("u2", 200.0)),
            ),
        )
        assertEquals(setOf("u1", "u2"), ui.outstandingShownRows().map { it.userId }.toSet())
        val cameraName = ui.departmentOf("u1")
        val found = ui.copy(processing = ui.processing.copy(search = cameraName.uppercase())).outstandingShownRows()
        assertEquals(listOf("u1"), found.map { it.userId }, "'$cameraName' is a department, not a name or a role")
    }

    // -- the Week Summary card ----------------------------------------------------------------

    private fun worked(user: String, basic: Double, ots: Double, status: TimecardStatus) = PayrollTimecard(
        id = "$user-w",
        userId = user,
        status = status,
        weekStarting = week,
        currency = "GBP",
        basicPay = basic,
        daySummary = listOf(DaySummary(week, "SWD", basic, ots, 0.0, minutesWorked = 600)),
    )

    @Test
    fun `the week summary counts the crew, their net, the approved and the overtime`() {
        val ui = state(
            "u1" to "department_camera",
            "u2" to "department_art",
            processing = ProcessingState(
                weekStarting = week,
                view = ProcessingView.Weekly,
                timecards = listOf(
                    worked("u1", 400.0, 50.0, TimecardStatus.Approved),
                    worked("u2", 300.0, 0.0, TimecardStatus.Submitted),
                ),
            ),
        )
        val summary = ui.processingSummary()
        assertEquals(
            listOf(
                "2",
                Money.format(750.0, "GBP"),
                "1",
                Money.format(50.0, "GBP"),
            ),
            summary.lines.map { it.value },
        )
        assertEquals(4, summary.lines.size)
    }

    @Test
    fun `the day summary counts only the crew working that day and titles itself with the date`() {
        val monday = worked("u1", 400.0, 50.0, TimecardStatus.Approved)
        val offMonday = worked("u2", 300.0, 0.0, TimecardStatus.Approved).copy(
            daySummary = listOf(DaySummary(week + 86_400_000L, "SWD", 300.0, 0.0, 0.0)),
        )
        val ui = state(
            "u1" to "department_camera",
            "u2" to "department_art",
            processing = ProcessingState(
                weekStarting = week,
                view = ProcessingView.Daily,
                day = 0,
                timecards = listOf(monday, offMonday),
            ),
        )
        val summary = ui.processingSummary()
        assertEquals("1", summary.lines.first().value, "u2 did not work Monday")
        assertEquals(Money.format(450.0, "GBP"), summary.lines[1].value)
        assertTrue(summary.title.contains("03 Aug"), summary.title)
    }

    @Test
    fun `an empty week summarises to nothing rather than failing`() {
        val ui = state(processing = ProcessingState(weekStarting = week, view = ProcessingView.Weekly))
        val values = ui.processingSummary().lines.map { it.value }
        assertEquals("0", values.first())
        assertNull(values.firstOrNull { it.isBlank() })
    }
}
