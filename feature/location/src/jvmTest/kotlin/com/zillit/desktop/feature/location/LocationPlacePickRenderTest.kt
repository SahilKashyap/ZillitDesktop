package com.zillit.desktop.feature.location

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.locationpicker.LocalLocationPicker
import com.zillit.desktop.core.locationpicker.LocationPicker
import com.zillit.desktop.core.locationpicker.PickedLocation
import com.zillit.desktop.core.locationpicker.PickerStart
import com.zillit.desktop.feature.location.domain.LatLng
import com.zillit.desktop.feature.location.ui.LocationEditor
import com.zillit.desktop.feature.location.ui.LocationEvent
import com.zillit.desktop.feature.location.ui.LocationScreen
import com.zillit.desktop.feature.location.ui.LocationUiState
import com.zillit.desktop.feature.location.ui.PlaceEditor
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Where a scouted location is, in the record form.
 *
 * The form used to ask for the place as a line of text and throw the pick's
 * coordinates away. It cannot any more: the Map tool draws the shortlisted
 * and final locations from `location: { lat, long }`, and the record's link
 * is the Google Maps link of that pin — so the pin is the field, and the
 * address it reads back only fills the Address box (web PRs #6517, #6521).
 */
@OptIn(ExperimentalTestApi::class)
class LocationPlacePickRenderTest {

    private class FakePicker(private val place: PickedLocation?) : LocationPicker {
        override suspend fun pick(
            initial: PickedLocation?,
            title: String,
            near: PickerStart?,
        ): PickedLocation? = place
    }

    private val aria = PickedLocation(
        name = "Aria Hotel",
        address = "12 Marine Drive, Mumbai",
        lat = 18.94,
        lng = 72.82,
    )

    private fun ComposeUiTest.editorDialog(
        picker: LocationPicker?,
        editor: LocationEditor,
        onEvent: (LocationEvent) -> Unit,
    ) {
        setContent {
            ZillitTheme {
                CompositionLocalProvider(LocalLocationPicker provides picker) {
                    LocationScreen(
                        state = LocationUiState(editor = editor),
                        onEvent = onEvent,
                        loadImage = { _, _ -> null },
                        resolveUser = { null },
                    )
                }
            }
        }
    }

    @Test
    fun `picking a place pins it and keeps its address`() = runComposeUiTest {
        val events = mutableListOf<LocationEvent>()
        editorDialog(FakePicker(aria), LocationEditor(location = "Marine Drive"), events::add)

        onNodeWithText("Search / pick on map").performClick()
        waitForIdle()

        val changed = events.filterIsInstance<LocationEvent.EditorChanged>().last()
        assertEquals(LatLng(18.94, 72.82), changed.editor.place.pin)
        assertEquals("12 Marine Drive, Mumbai", changed.editor.place.address)
        // The folder name is the gallery key; a map pin must not rewrite it.
        assertEquals("Marine Drive", changed.editor.location)
    }

    @Test
    fun `a cancelled pick leaves the place alone`() = runComposeUiTest {
        val events = mutableListOf<LocationEvent>()
        editorDialog(FakePicker(null), LocationEditor(address = "Pinewood"), events::add)

        onNodeWithText("Search / pick on map").performClick()
        waitForIdle()

        assertEquals(emptyList(), events.filterIsInstance<LocationEvent.EditorChanged>())
    }

    @Test
    fun `without a picker the place is still set by pasting a maps link`() = runComposeUiTest {
        // No embedded browser means no map, but a pasted link still carries
        // coordinates — the one way to pin a place on such a host.
        editorDialog(picker = null, editor = LocationEditor(), onEvent = {})

        onNodeWithText("Address").assertExists()
        onNodeWithText("Google Maps link").assertExists()
        onAllNodesWithText("Search / pick on map").assertCountEquals(0)
    }

    @Test
    fun `a saved pin opens the form on its link`() = runComposeUiTest {
        editorDialog(
            picker = null,
            editor = LocationEditor(place = PlaceEditor(pin = LatLng(18.94, 72.82))),
            onEvent = {},
        )

        // The link shown is always derived from the pin, never a stored URL.
        onNodeWithText("https://www.google.com/maps?q=18.94,72.82").assertExists()
    }

    @Test
    fun `an unpinned record shows the empty map box`() = runComposeUiTest {
        editorDialog(picker = null, editor = LocationEditor(), onEvent = {})
        onNodeWithText("Paste a Google Maps link below to place this location").assertExists()
    }
}
