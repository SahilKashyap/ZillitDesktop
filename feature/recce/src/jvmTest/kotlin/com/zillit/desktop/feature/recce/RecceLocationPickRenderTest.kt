package com.zillit.desktop.feature.recce

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.locationpicker.LocalLocationPicker
import com.zillit.desktop.core.locationpicker.LocationPicker
import com.zillit.desktop.core.locationpicker.PickedLocation
import com.zillit.desktop.feature.recce.ui.RecceEditor
import com.zillit.desktop.feature.recce.ui.RecceEvent
import com.zillit.desktop.feature.recce.ui.RecceUiState
import com.zillit.desktop.feature.recce.ui.StopEditor
import com.zillit.desktop.feature.recce.ui.pages.RecceFormPage
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A recce stop's coordinates are wire fields — `lat` and, longitude spelled
 * `long`, on `rdv` and on every `itinerary[]` entry
 * (`RecceRepositoryImpl.kt:159-176`) — so a pick has to land in them, not just
 * in the address line.
 */
@OptIn(ExperimentalTestApi::class)
class RecceLocationPickRenderTest {

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

    private fun editor(rdv: StopEditor = StopEditor(), stop: StopEditor = StopEditor()) = RecceEditor(
        uniqueId = "u1",
        title = "Day one",
        dateYmd = "2026-08-25",
        rdv = rdv,
        stops = listOf(stop),
    )

    @Test
    fun `picking the rendezvous fills its address, name and both coordinates`() = runComposeUiTest {
        val events = mutableListOf<RecceEvent>()
        setContent {
            ZillitTheme {
                CompositionLocalProvider(LocalLocationPicker provides FakePicker(aria)) {
                    RecceFormPage(RecceUiState(editor = editor()), events::add, editing = false)
                }
            }
        }

        onAllNodesWithText("Pick on map")[RDV].performClick()
        waitForIdle()

        val rdv = events.filterIsInstance<RecceEvent.EditorChanged>().mapNotNull { it.rdv }.last()
        assertEquals("12 Marine Drive, Mumbai", rdv.address)
        assertEquals("18.94", rdv.latText)
        assertEquals("72.82", rdv.lngText)
        // The venue names a place that had none; one already named keeps its name.
        assertEquals("Aria Hotel", rdv.place)
    }

    @Test
    fun `a stop already named is not renamed by looking up its address`() = runComposeUiTest {
        val events = mutableListOf<RecceEvent>()
        setContent {
            ZillitTheme {
                CompositionLocalProvider(LocalLocationPicker provides FakePicker(aria)) {
                    RecceFormPage(
                        RecceUiState(editor = editor(stop = StopEditor(place = "Unit base"))),
                        events::add,
                        editing = false,
                    )
                }
            }
        }

        onAllNodesWithText("Pick on map")[FIRST_STOP].performClick()
        waitForIdle()

        val changed = events.filterIsInstance<RecceEvent.StopChanged>().last()
        assertEquals(0, changed.index)
        assertEquals("Unit base", changed.stop.place)
        assertEquals("12 Marine Drive, Mumbai", changed.stop.address)
        assertEquals("18.94", changed.stop.latText)
        assertEquals("72.82", changed.stop.lngText)
    }

    @Test
    fun `the map opens where the stop already is`() = runComposeUiTest {
        val picker = FakePicker(aria)
        val placed = StopEditor(place = "Unit base", address = "Pinewood", latText = "1.5", lngText = "-2.25")
        setContent {
            ZillitTheme {
                CompositionLocalProvider(LocalLocationPicker provides picker) {
                    RecceFormPage(RecceUiState(editor = editor(rdv = placed)), onEvent = {}, editing = false)
                }
            }
        }

        onAllNodesWithText("Pick on map")[RDV].performClick()
        waitForIdle()

        assertEquals(PickedLocation("Unit base", "Pinewood", 1.5, -2.25), picker.openedWith)
    }

    @Test
    fun `the map-less routes into the coordinates survive`() = runComposeUiTest {
        // Typed numbers and a pasted Google Maps link are the only way in for a
        // host with no picker, and they stay whether or not one is wired.
        setContent {
            ZillitTheme { RecceFormPage(RecceUiState(editor = editor()), onEvent = {}, editing = false) }
        }

        onAllNodesWithText("Pick on map").assertCountEquals(0)
        onAllNodesWithText("Latitude", useUnmergedTree = true).assertCountEquals(2)
        onAllNodesWithText("Longitude", useUnmergedTree = true).assertCountEquals(2)
        onAllNodesWithText("Paste a Google Maps link", useUnmergedTree = true).assertCountEquals(2)
    }

    private companion object {
        /** The rendezvous section is drawn before the itinerary. */
        const val RDV = 0
        const val FIRST_STOP = 1
    }
}
