package com.zillit.desktop.feature.boxschedule

import androidx.compose.runtime.CompositionLocalProvider
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
import com.zillit.desktop.feature.boxschedule.ui.BoxScheduleEvent
import com.zillit.desktop.feature.boxschedule.ui.BoxScheduleUiState
import com.zillit.desktop.feature.boxschedule.ui.DiaryEditor
import com.zillit.desktop.feature.boxschedule.ui.pages.DiaryEditorDialog
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A diary event's Location is picked on a map, and only the line travels.
 *
 * `eventWire` (BoxScheduleRepositoryImpl.kt:364) sends `location` as a string
 * and carries no coordinate fields, so the pick's numbers are deliberately
 * dropped. The web's boxScheduleV2 modal does send `locationLat`/`locationLng`
 * beside it (`CreateEventModal.jsx:540`) — carrying those is a wire change,
 * pinned by BoxScheduleSyncTest, not a form change.
 */
@OptIn(ExperimentalTestApi::class)
class DiaryLocationPickRenderTest {

    private class FakePicker(private val place: PickedLocation?) : LocationPicker {
        override suspend fun pick(initial: PickedLocation?, title: String): PickedLocation? = place
    }

    private val aria = PickedLocation(
        name = "Aria Hotel",
        address = "12 Marine Drive, Mumbai",
        lat = 18.94,
        lng = 72.82,
    )

    @Test
    fun `picking a place writes its line into the editor`() = runComposeUiTest {
        val events = mutableListOf<BoxScheduleEvent>()
        setContent {
            ZillitTheme {
                CompositionLocalProvider(LocalLocationPicker provides FakePicker(aria)) {
                    DiaryEditorDialog(BoxScheduleUiState(), DiaryEditor(dateText = "2026-08-25"), events::add)
                }
            }
        }

        onNodeWithText("Pick on map").performClick()
        waitForIdle()

        val changed = events.filterIsInstance<BoxScheduleEvent.DiaryChanged>().mapNotNull { it.location }.last()
        assertEquals("Aria Hotel, 12 Marine Drive, Mumbai", changed)
    }

    @Test
    fun `a cancelled pick leaves the location alone`() = runComposeUiTest {
        val events = mutableListOf<BoxScheduleEvent>()
        setContent {
            ZillitTheme {
                CompositionLocalProvider(LocalLocationPicker provides FakePicker(null)) {
                    DiaryEditorDialog(BoxScheduleUiState(), DiaryEditor(location = "Base camp"), events::add)
                }
            }
        }

        onNodeWithText("Pick on map").performClick()
        waitForIdle()

        assertEquals(emptyList(), events.filterIsInstance<BoxScheduleEvent.DiaryChanged>())
    }

    @Test
    fun `with no picker wired the location is still a plain typed field`() = runComposeUiTest {
        setContent { ZillitTheme { DiaryEditorDialog(BoxScheduleUiState(), DiaryEditor(), onEvent = {}) } }

        onNodeWithText("Location").assertExists()
        onAllNodesWithText("Pick on map").assertCountEquals(0)
    }
}
