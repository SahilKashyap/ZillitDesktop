package com.zillit.desktop.feature.recce

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.locationpicker.LocalLocationPicker
import com.zillit.desktop.core.locationpicker.LocationPicker
import com.zillit.desktop.core.locationpicker.PickedLocation
import com.zillit.desktop.feature.recce.domain.Recce
import com.zillit.desktop.feature.recce.domain.ReccePerson
import com.zillit.desktop.feature.recce.domain.RecceStatus
import com.zillit.desktop.feature.recce.domain.RecceStop
import com.zillit.desktop.feature.recce.domain.StopKind
import com.zillit.desktop.feature.recce.ui.DeleteTarget
import com.zillit.desktop.feature.recce.ui.RecceCounts
import com.zillit.desktop.feature.recce.ui.RecceEditor
import com.zillit.desktop.feature.recce.ui.RecceEvent
import com.zillit.desktop.feature.recce.ui.ReccePage
import com.zillit.desktop.feature.recce.ui.RecceScreen
import com.zillit.desktop.feature.recce.ui.RecceUiState
import com.zillit.desktop.feature.recce.ui.StopEditor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The three pages and their dialogs, rendered from fixtures. */
@OptIn(ExperimentalTestApi::class)
class RecceScreenRenderTest {

    private class FakePicker(private val place: PickedLocation?) : LocationPicker {
        var openedWith: PickedLocation? = null

        override suspend fun pick(initial: PickedLocation?, title: String): PickedLocation? {
            openedWith = initial
            return place
        }
    }

    private val aria = PickedLocation(
        name = "Aria Hotel",
        address = "12 Marine Drive, Mumbai",
        lat = 18.94,
        lng = 72.82,
    )

    private val bridges = Recce(
        id = "r1",
        uniqueId = "u1",
        title = "Stunt Recce — City Bridges",
        unit = "unit-1",
        dateMs = 1_772_755_200_000L,
        station = "Kings Cross Station",
        weather = "12°C – 18°C, light cloud",
        crewNote = "All crew to drive themselves to location independently",
        rdv = RecceStop(
            kind = StopKind.Rendezvous,
            timeMs = 1_772_784_000_000L,
            place = "Notes Coffee",
            w3w = "leaned.sushi.port",
        ),
        itinerary = listOf(
            RecceStop(
                kind = StopKind.Start,
                timeMs = 1_772_785_800_000L,
                place = "Millennium Bridge",
                lat = 51.5,
                long = -0.1,
                travel = "Tube from Temple",
            ),
            RecceStop(kind = StopKind.Lunch, timeMs = 1_772_794_800_000L, place = "Borough Market"),
            RecceStop(
                kind = StopKind.End,
                timeMs = 1_772_802_000_000L,
                place = "Tower Bridge",
                contact = "James - 0776",
            ),
        ),
        personnel = listOf(
            ReccePerson(name = "Aisha Khan", role = "1st AD", contact = "07700 900123", note = "Joining at Waterloo"),
        ),
        status = RecceStatus.Draft,
        version = 1,
    )

    private fun editor(rdv: StopEditor = StopEditor(), stop: StopEditor = StopEditor()) = RecceEditor(
        uniqueId = "u1",
        title = "Day one",
        dateYmd = "2026-08-25",
        rdv = rdv,
        stops = listOf(stop),
    )

    @Test
    fun `the list shows each row with its draft chip, and a row opens on click`() = runComposeUiTest {
        val events = mutableListOf<RecceEvent>()
        setContent {
            ZillitTheme {
                RecceScreen(
                    RecceUiState(recces = listOf(bridges), total = 1, counts = RecceCounts(1, 0, 1)),
                    events::add,
                )
            }
        }

        onNodeWithText("Stunt Recce — City Bridges").assertExists()
        onNodeWithText("Draft").assertExists()
        onNodeWithText("1–1 of 1").assertExists()
        onNodeWithText("Stunt Recce — City Bridges").performClick()
        assertEquals(RecceEvent.Open("r1"), events.last())

        onAllNodesWithContentDescription("Delete recce")[0].performClick()
        assertEquals(RecceEvent.Delete("r1"), events.last())
    }

    @Test
    fun `the detail draws every stop, the W3W chip and the crew`() = runComposeUiTest {
        setContent {
            ZillitTheme {
                RecceScreen(RecceUiState(route = ReccePage.Detail("r1"), selected = bridges), onEvent = {})
            }
        }

        onNodeWithText("Millennium Bridge").assertExists()
        onNodeWithText("Borough Market").assertExists()
        onNodeWithText("Tower Bridge").assertExists()
        onNodeWithText("leaned.sushi.port").assertExists()
        onNodeWithText("Tube from Temple").assertExists()
        onNodeWithText("Aisha Khan").assertExists()
        onNodeWithText("Joining at Waterloo").assertExists()
        onNodeWithText("2 locations").assertExists()
        onNodeWithText("Kings Cross Station").assertExists()
    }

    @Test
    fun `the delete confirm names the recce`() = runComposeUiTest {
        val events = mutableListOf<RecceEvent>()
        setContent {
            ZillitTheme {
                RecceScreen(
                    RecceUiState(
                        recces = listOf(bridges),
                        deleteTarget = DeleteTarget("r1", "Stunt Recce — City Bridges"),
                    ),
                    events::add,
                )
            }
        }
        waitForIdle()

        onNodeWithText("Delete recce?").assertExists()
        onNodeWithText("Delete").performClick()
        assertEquals(RecceEvent.ConfirmDelete, events.last())
    }

    @Test
    fun `picking the rendezvous fills its name, address and both coordinates`() = runComposeUiTest {
        val events = mutableListOf<RecceEvent>()
        setContent {
            ZillitTheme {
                CompositionLocalProvider(LocalLocationPicker provides FakePicker(aria)) {
                    RecceScreen(RecceUiState(route = ReccePage.Form(null), editor = editor()), events::add)
                }
            }
        }

        onAllNodesWithText("Search / pick on map")[RDV].performClick()
        waitForIdle()

        val rdv = events.filterIsInstance<RecceEvent.EditorChanged>().mapNotNull { it.rdv }.last()
        assertEquals("12 Marine Drive, Mumbai", rdv.address)
        assertEquals(18.94, rdv.lat)
        assertEquals(72.82, rdv.long)
        // The venue names a place that had none; one already named keeps its name.
        assertEquals("Aria Hotel", rdv.place)
    }

    @Test
    fun `a stop already named is not renamed by looking up its address`() = runComposeUiTest {
        val events = mutableListOf<RecceEvent>()
        val picker = FakePicker(aria)
        setContent {
            ZillitTheme {
                CompositionLocalProvider(LocalLocationPicker provides picker) {
                    RecceScreen(
                        RecceUiState(
                            route = ReccePage.Form(null),
                            editor = editor(stop = StopEditor(place = "Unit base")),
                        ),
                        events::add,
                    )
                }
            }
        }

        onAllNodesWithText("Search / pick on map")[FIRST_STOP].performScrollTo().performClick()
        waitForIdle()

        val changed = events.filterIsInstance<RecceEvent.StopChanged>().last()
        assertEquals(0, changed.index)
        assertEquals("Unit base", changed.stop.place)
        assertEquals("12 Marine Drive, Mumbai", changed.stop.address)
        assertEquals(18.94, changed.stop.lat)
    }

    @Test
    fun `the map opens where the stop already is`() = runComposeUiTest {
        val picker = FakePicker(aria)
        val placed = StopEditor(place = "Unit base", address = "Pinewood", lat = 1.5, long = -2.25)
        setContent {
            ZillitTheme {
                CompositionLocalProvider(LocalLocationPicker provides picker) {
                    RecceScreen(RecceUiState(route = ReccePage.Form(null), editor = editor(rdv = placed)), onEvent = {})
                }
            }
        }

        onAllNodesWithText("Move / search on map")[RDV].performClick()
        waitForIdle()

        assertEquals(PickedLocation("Unit base", "Pinewood", 1.5, -2.25), picker.openedWith)
    }

    @Test
    fun `without a map the pasted link is the way in, and it still shows the copy affordance`() = runComposeUiTest {
        setContent {
            ZillitTheme { RecceScreen(RecceUiState(route = ReccePage.Form(null), editor = editor()), onEvent = {}) }
        }

        onAllNodesWithText("Search / pick on map").assertCountEquals(0)
        onAllNodesWithText("Google Maps link", useUnmergedTree = true).assertCountEquals(2)
        assertTrue(onAllNodesWithText("Copy", useUnmergedTree = true).fetchSemanticsNodes().size >= 2)
    }

    @Test
    fun `a maps link typed one character at a time still lands the pin where the link says`() =
        runComposeUiTest {
            val events = mutableListOf<RecceEvent>()
            setContent {
                ZillitTheme { RecceScreen(RecceUiState(route = ReccePage.Form(null), editor = editor()), events::add) }
            }

            // Editable fields in form order: title, date, RDV time, RDV place, then the RDV maps link.
            val field = onAllNodes(hasSetTextAction())[MAPS_LINK_FIELD]
            field.performClick()
            // `@51.5,-0` already parses as a pin; the text must survive that and keep taking keystrokes.
            "https://www.google.com/maps/@51.5074,-0.1278,15z".forEach { field.performTextInput(it.toString()) }
            waitForIdle()

            val rdv = events.filterIsInstance<RecceEvent.EditorChanged>().mapNotNull { it.rdv }.last()
            assertEquals(51.5074, rdv.lat)
            assertEquals(-0.1278, rdv.long)
        }

    private companion object {
        /** The rendezvous section is drawn before the itinerary. */
        const val RDV = 0
        const val FIRST_STOP = 1
        const val MAPS_LINK_FIELD = 4
    }
}
