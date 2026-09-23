package com.zillit.desktop.feature.boxschedule

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.locationpicker.LocalLocationPicker
import com.zillit.desktop.core.locationpicker.LocationPicker
import com.zillit.desktop.core.locationpicker.PickedLocation
import com.zillit.desktop.feature.boxschedule.domain.DiaryKind
import com.zillit.desktop.feature.boxschedule.ui.BoxScheduleEvent
import com.zillit.desktop.feature.boxschedule.ui.EntryEvent
import com.zillit.desktop.feature.boxschedule.ui.EntryForm
import com.zillit.desktop.feature.boxschedule.ui.pages.EntryFormSheet
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * An event's location is typed or picked on a map; a pick sends the line and
 * its coordinates together — the web's `locationLat` / `locationLng`.
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

    private val form = EntryForm(
        kind = DiaryKind.Event,
        startDate = DiarySamples.today,
        timezone = DiarySamples.zone.id,
    )

    @Test
    fun `picking a place writes its line and its coordinates`() = runComposeUiTest {
        val events = mutableListOf<BoxScheduleEvent>()
        setContent {
            ZillitTheme {
                CompositionLocalProvider(LocalLocationPicker provides FakePicker(aria)) {
                    EntryFormSheet(DiarySamples.state(), form, events::add)
                }
            }
        }
        waitForIdle()

        // "Pick on Map" is Android's wording for ce_pick_on_map; only the capital M is new.
        onNodeWithText("Pick on Map").performScrollTo().performClick()
        waitForIdle()

        assertEquals(
            EntryEvent.SetLocation("Aria Hotel, 12 Marine Drive, Mumbai", 18.94, 72.82),
            events.filterIsInstance<EntryEvent.SetLocation>().last(),
        )
    }

    @Test
    fun `a cancelled pick leaves the location alone`() = runComposeUiTest {
        val events = mutableListOf<BoxScheduleEvent>()
        setContent {
            ZillitTheme {
                CompositionLocalProvider(LocalLocationPicker provides FakePicker(null)) {
                    EntryFormSheet(DiarySamples.state(), form.copy(location = "Base camp"), events::add)
                }
            }
        }
        waitForIdle()

        // "Pick on Map" is Android's wording for ce_pick_on_map; only the capital M is new.
        onNodeWithText("Pick on Map").performScrollTo().performClick()
        waitForIdle()

        assertEquals(emptyList<EntryEvent.SetLocation>(), events.filterIsInstance<EntryEvent.SetLocation>())
    }

    @Test
    fun `with no picker wired the location is a plain typed field`() = runComposeUiTest {
        setContent { ZillitTheme { EntryFormSheet(DiarySamples.state(), form, onEvent = {}) } }
        waitForIdle()

        onNodeWithText("Add Location", ignoreCase = true).assertExists()
        onAllNodesWithText("Pick on Map").assertCountEquals(0)
    }
}
