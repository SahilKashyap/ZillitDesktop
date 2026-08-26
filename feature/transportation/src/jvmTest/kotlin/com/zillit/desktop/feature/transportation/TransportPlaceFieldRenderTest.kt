package com.zillit.desktop.feature.transportation

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.locationpicker.LocalLocationPicker
import com.zillit.desktop.core.locationpicker.LocationPicker
import com.zillit.desktop.core.locationpicker.PickedLocation
import com.zillit.desktop.feature.transportation.ui.RaiseEditor
import com.zillit.desktop.feature.transportation.ui.TransportEvent
import com.zillit.desktop.feature.transportation.ui.TransportUiState
import com.zillit.desktop.feature.transportation.ui.pages.TransportDialogs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A pickup request cannot be raised without coordinates: `POST request`
 * answers 406 `trip_request_passengers_required` when a passenger's
 * pickup/dropoff carries `lat`/`long: null`, which
 * `TransportViewModel.passengerProblem` refuses locally first.
 *
 * So the thing worth pinning is not that the field draws — it is that one pick
 * leaves the editor holding all three values that check reads, and that the
 * two map-less routes into them still exist when no picker is wired.
 */
@OptIn(ExperimentalTestApi::class)
class TransportPlaceFieldRenderTest {

    private val aria = PickedLocation(
        name = "Aria Hotel",
        address = "12 Marine Drive, Mumbai",
        lat = 18.94,
        lng = 72.82,
    )

    private class FakePicker(private val place: PickedLocation?) : LocationPicker {
        var openedWith: PickedLocation? = null
        var opened = 0

        override suspend fun pick(initial: PickedLocation?, title: String): PickedLocation? {
            opened++
            openedWith = initial
            return place
        }
    }

    private fun ComposeUiTest.raiseDialog(
        picker: LocationPicker?,
        editor: RaiseEditor,
        onEvent: (TransportEvent) -> Unit,
    ) {
        setContent {
            ZillitTheme {
                CompositionLocalProvider(LocalLocationPicker provides picker) {
                    TransportDialogs(TransportUiState(raise = editor), onEvent)
                }
            }
        }
    }

    @Test
    fun `picking a pickup fills its address and both coordinates`() = runComposeUiTest {
        val events = mutableListOf<TransportEvent>()
        raiseDialog(FakePicker(aria), RaiseEditor(), events::add)

        scrollDialogToBottom()
        onAllNodesWithText("Pick on map")[0].performClick()
        waitForIdle()

        val passenger = events.filterIsInstance<TransportEvent.RaiseChanged>().mapNotNull { it.passenger }.last()
        assertEquals("Aria Hotel, 12 Marine Drive, Mumbai", passenger.pickupAddress)
        assertEquals("18.94", passenger.pickupLat)
        assertEquals("72.82", passenger.pickupLng)
    }

    @Test
    fun `picking a drop-off fills the other end, leaving the pickup alone`() = runComposeUiTest {
        val events = mutableListOf<TransportEvent>()
        val started = RaiseEditor().let { it.copy(passenger = it.passenger.copy(pickupAddress = "Base camp")) }
        raiseDialog(FakePicker(aria), started, events::add)

        scrollDialogToBottom()
        onAllNodesWithText("Pick on map")[1].performClick()
        waitForIdle()

        val passenger = events.filterIsInstance<TransportEvent.RaiseChanged>().mapNotNull { it.passenger }.last()
        assertEquals("Aria Hotel, 12 Marine Drive, Mumbai", passenger.dropAddress)
        assertEquals("18.94", passenger.dropLat)
        assertEquals("72.82", passenger.dropLng)
        assertEquals("Base camp", passenger.pickupAddress)
    }

    @Test
    fun `a cancelled pick changes nothing`() = runComposeUiTest {
        val events = mutableListOf<TransportEvent>()
        val picker = FakePicker(null)
        raiseDialog(picker, RaiseEditor(), events::add)

        scrollDialogToBottom()
        onAllNodesWithText("Pick on map")[0].performClick()
        waitForIdle()

        assertEquals(1, picker.opened)
        assertTrue(events.filterIsInstance<TransportEvent.RaiseChanged>().none { it.passenger != null })
    }

    @Test
    fun `the map opens where the place already is`() = runComposeUiTest {
        val events = mutableListOf<TransportEvent>()
        val picker = FakePicker(aria)
        val placed = RaiseEditor().let {
            it.copy(passenger = it.passenger.copy(pickupAddress = "Base camp", pickupLat = "1.5", pickupLng = "-2.25"))
        }
        raiseDialog(picker, placed, events::add)

        scrollDialogToBottom()
        onAllNodesWithText("Pick on map")[0].performClick()
        waitForIdle()

        assertEquals(PickedLocation("", "Base camp", 1.5, -2.25), picker.openedWith)
    }

    @Test
    fun `a pasted Google Maps link still fills the coordinates`() = runComposeUiTest {
        // The desktop's original route into the numbers, and the only one a
        // host with no maps key has besides typing them.
        val events = mutableListOf<TransportEvent>()
        raiseDialog(FakePicker(aria), RaiseEditor(), events::add)

        scrollDialogToBottom()
        // Date, Time, then the two addresses — the selects carry no text action.
        onAllNodes(hasSetTextAction())[PICKUP_FIELD]
            .performTextInput("https://www.google.com/maps/@18.94,72.82,17z")
        waitForIdle()

        val passenger = events.filterIsInstance<TransportEvent.RaiseChanged>().mapNotNull { it.passenger }.last()
        assertEquals("18.94", passenger.pickupLat)
        assertEquals("72.82", passenger.pickupLng)
    }

    @Test
    fun `with no picker wired the coordinate fields are open by themselves`() = runComposeUiTest {
        // Offline, or a host without a maps key: the trio the dialog always
        // showed is still there, so the only workflow that ever existed here
        // never becomes unreachable.
        raiseDialog(picker = null, editor = RaiseEditor(), onEvent = {})

        scrollDialogToBottom()
        onAllNodesWithText("Pick on map").assertCountEquals(0)
        // The dialog merges its descendants, so the labels are only separate
        // nodes in the unmerged tree.
        onAllNodesWithText("Lat", useUnmergedTree = true).assertCountEquals(2)
        onAllNodesWithText("Lng", useUnmergedTree = true).assertCountEquals(2)
    }

    @Test
    fun `with a picker wired the coordinates read back, and stay editable on request`() = runComposeUiTest {
        // One field and a line of numbers instead of a trio of boxes — but the
        // boxes are one click away, for a place the map cannot find.
        val events = mutableListOf<TransportEvent>()
        val known = RaiseEditor().let {
            it.copy(passenger = it.passenger.copy(pickupLat = "18.94", pickupLng = "72.82"))
        }
        raiseDialog(FakePicker(aria), known, events::add)

        scrollDialogToBottom()
        onNodeWithText("18.94, 72.82").assertExists()
        onAllNodesWithText("Lat", useUnmergedTree = true).assertCountEquals(0)

        onAllNodesWithText("Enter coordinates")[0].performClick()
        onAllNodesWithText("Lat", useUnmergedTree = true).assertCountEquals(1)
    }

    /**
     * Scrolls the dialog body down. A node below the fold is fully clipped and
     * reports zero bounds, which defeats `performScrollTo` and lands taps on
     * the scrim — drive the body's own scroll action instead.
     */
    private fun ComposeUiTest.scrollDialogToBottom() {
        onNode(hasScrollAction()).performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, SCROLL_TO_END) }
    }

    private companion object {
        const val SCROLL_TO_END = 10_000f

        /** Date, Time, pickup address, drop-off address — the selects take no text. */
        const val PICKUP_FIELD = 2
    }
}
