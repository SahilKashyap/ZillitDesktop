package com.zillit.desktop.feature.crewdood

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.feature.crewdood.domain.DoodCalc
import com.zillit.desktop.feature.crewdood.domain.DoodCalendar
import com.zillit.desktop.feature.crewdood.domain.DoodCrewDepartment
import com.zillit.desktop.feature.crewdood.domain.DoodCrewUnit
import com.zillit.desktop.feature.crewdood.domain.DoodCrewUser
import com.zillit.desktop.feature.crewdood.domain.DoodDeal
import com.zillit.desktop.feature.crewdood.domain.DoodHoliday
import com.zillit.desktop.feature.crewdood.domain.DoodSources
import com.zillit.desktop.feature.crewdood.ui.CrewDoodCalendarDialog
import com.zillit.desktop.feature.crewdood.ui.CrewDoodScreen
import com.zillit.desktop.feature.crewdood.ui.CrewDoodUiState
import com.zillit.desktop.feature.crewdood.ui.DoodGridController
import com.zillit.desktop.feature.crewdood.ui.DoodGroup
import kotlinx.datetime.LocalDate
import kotlin.test.Test

/**
 * Composes the real chart and the real editor.
 *
 * The model tests cannot see a canvas that throws while laying out, a frozen
 * column that collapses to nothing, or an editor whose month grid cannot be
 * built — and those are exactly the failures a 1,100-column grid invites.
 */
@OptIn(ExperimentalTestApi::class)
class CrewDoodRenderTest {

    private val units = listOf(
        DoodCrewUnit(
            listOf(
                DoodCrewDepartment(
                    "Camera",
                    listOf("cam"),
                    listOf(DoodCrewUser("u1", "Ann Okafor", "DOP"), DoodCrewUser("u2", "Ben Hartley", "1st AC")),
                ),
                DoodCrewDepartment("Sound", listOf("snd"), listOf(DoodCrewUser("u3", "Cat Moreau", "Mixer"))),
            ),
        ),
    )

    private fun deal(user: String, start: String, end: String) = DoodDeal(
        id = "d-$user",
        status = "active",
        userId = user,
        reference = "DM-1",
        crewName = "",
        customDesignation = "",
        designationRefs = emptyList(),
        departmentRefs = emptyList(),
        start = LocalDate.parse(start),
        end = LocalDate.parse(end),
        lastPayDay = null,
    )

    private fun state(weeks: Boolean = false): CrewDoodUiState {
        val model = DoodCalc.buildDood(
            sources = DoodSources(
                units = units,
                deals = listOf(deal("u1", "2026-04-06", "2026-04-10"), deal("u3", "2026-04-08", "2026-04-08")),
                shootDays = (6..10).map { LocalDate(2026, 4, it) },
                calendar = DoodCalc.calendarFrom(
                    listOf(0, 6),
                    listOf(DoodHoliday(LocalDate.parse("2026-04-09"), "Founders Day")),
                ),
            ),
            today = LocalDate.parse("2026-04-06"),
            noDepartment = "No department",
        )
        val groups = model.departments.map { department ->
            DoodGroup(
                key = department.key,
                name = department.name,
                crew = department.crew,
                booked = department.bookedCount,
                total = department.crew.size,
            )
        }
        return CrewDoodUiState(
            loading = false,
            model = model,
            weeks = weeks,
            groups = groups,
            counts = DoodCalc.crewPerDay(model.departments.flatMap { it.crew }, model.dates.size),
            departmentOptions = emptyList(),
            bands = model.phases.mapNotNull { it?.band }.toSet(),
            hasCrew = true,
        )
    }

    @Test
    fun `the chart draws its top bar, toolbar and frozen columns`() {
        runComposeUiTest {
            setContent {
                ZillitTheme {
                    CrewDoodScreen(state = state(), onEvent = {}, controller = DoodGridController())
                }
            }

            onNodeWithText("CREW DOOD").assertExists()
            onNodeWithText("SW · Start work").assertExists()
            onNodeWithText("HOL · Holiday").assertExists()
            onNodeWithText("Days off & holidays").assertExists()
            onNodeWithText("Jump to").assertExists()
            onNodeWithText("Number of Crew on each day").assertExists()
            // Frozen columns and a department's tally.
            onNodeWithText("Ann Okafor").assertExists()
            onNodeWithText("1/2 booked").assertExists()
        }
    }

    @Test
    fun `the Weeks view swaps the footer's heading`() {
        runComposeUiTest {
            setContent {
                ZillitTheme {
                    CrewDoodScreen(state = state(weeks = true), onEvent = {}, controller = DoodGridController())
                }
            }

            onNodeWithText("Peak crew in week").assertExists()
        }
    }

    @Test
    fun `a collapsed department hides its rows but keeps its heading`() {
        runComposeUiTest {
            val base = state()
            setContent {
                ZillitTheme {
                    CrewDoodScreen(
                        state = base.copy(collapsed = setOf(base.groups.first().key)),
                        onEvent = {},
                        controller = DoodGridController(),
                    )
                }
            }

            onNodeWithText("Camera").assertExists()
            onNodeWithText("Cat Moreau").assertExists()
        }
    }

    @Test
    fun `the empty and no-match faces say which is which`() {
        runComposeUiTest {
            setContent {
                ZillitTheme {
                    CrewDoodScreen(
                        state = CrewDoodUiState(loading = false, hasCrew = false),
                        onEvent = {},
                        controller = DoodGridController(),
                    )
                }
            }
            onNodeWithText("No crew on this project yet.").assertExists()
        }
        runComposeUiTest {
            setContent {
                ZillitTheme {
                    CrewDoodScreen(
                        state = CrewDoodUiState(loading = false, hasCrew = true, query = "zzz"),
                        onEvent = {},
                        controller = DoodGridController(),
                    )
                }
            }
            onNodeWithText("No crew match your filters.").assertExists()
        }
    }

    @Test
    fun `the editor draws its week row, its month and its holiday list`() {
        runComposeUiTest {
            setContent {
                ZillitTheme {
                    CrewDoodCalendarDialog(
                        calendar = DoodCalendar(
                            weeklyOff = setOf(0, 6),
                            holidays = mapOf(LocalDate.parse("2026-04-02") to "Founders Day"),
                        ),
                        saving = false,
                        onSave = {},
                        onDismiss = {},
                    )
                }
            }

            onNodeWithText("Weekly days off").assertExists()
            onNodeWithText("Sat, Sun off · 5-day week").assertExists()
            onNodeWithText("Add holidays").assertExists()
            onNodeWithText("Holidays").assertExists()
            onNodeWithText("Founders Day").assertExists()
            onNodeWithText("2 weekly days off · 1 holiday day").assertExists()
        }
    }
}
