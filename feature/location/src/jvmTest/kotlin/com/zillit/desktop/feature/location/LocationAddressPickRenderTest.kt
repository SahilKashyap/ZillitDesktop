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
import com.zillit.desktop.feature.location.ui.LocationEditor
import com.zillit.desktop.feature.location.ui.LocationEvent
import com.zillit.desktop.feature.location.ui.LocationScreen
import com.zillit.desktop.feature.location.ui.LocationUiState
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The scouting library's Address is picked on a map, and only the line
 * travels.
 *
 * This tool's body is `address` and nothing else — `putBase`
 * (LocationWire.kt:88), matching the web's `createModal`
 * (`commonFunctionForFilmTools.js:296-311`) — so the pick's coordinates are
 * dropped rather than sent under keys the server has never been asked for.
 * (The 406-on-null-lat/lng trap belongs to Transportation, not here.)
 */
@OptIn(ExperimentalTestApi::class)
class LocationAddressPickRenderTest {

    private class FakePicker(private val place: PickedLocation?) : LocationPicker {
        override suspend fun pick(initial: PickedLocation?, title: String): PickedLocation? = place
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
    fun `picking a place writes its line into the editor`() = runComposeUiTest {
        val events = mutableListOf<LocationEvent>()
        editorDialog(FakePicker(aria), LocationEditor(location = "Marine Drive"), events::add)

onNodeWithText("Pick on map").performClick()
        waitForIdle()

        val changed = events.filterIsInstance<LocationEvent.EditorChanged>().last()
        assertEquals("Aria Hotel, 12 Marine Drive, Mumbai", changed.editor.address)
        // The folder name is the gallery key; a map pin must not rewrite it.
        assertEquals("Marine Drive", changed.editor.location)
    }

    @Test
    fun `a cancelled pick leaves the address alone`() = runComposeUiTest {
        val events = mutableListOf<LocationEvent>()
        editorDialog(FakePicker(null), LocationEditor(address = "Pinewood"), events::add)

onNodeWithText("Pick on map").performClick()
        waitForIdle()

        assertEquals(emptyList(), events.filterIsInstance<LocationEvent.EditorChanged>())
    }

    @Test
    fun `with no picker wired the address is still a plain typed field`() = runComposeUiTest {
        editorDialog(picker = null, editor = LocationEditor(), onEvent = {})

onNodeWithText("Address").assertExists()
        onAllNodesWithText("Pick on map").assertCountEquals(0)
    }

}
