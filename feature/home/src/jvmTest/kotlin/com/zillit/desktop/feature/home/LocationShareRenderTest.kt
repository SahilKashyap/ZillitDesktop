package com.zillit.desktop.feature.home

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.locationpicker.LocalLocationPicker
import com.zillit.desktop.core.locationpicker.LocationPicker
import com.zillit.desktop.core.locationpicker.PickedLocation
import com.zillit.desktop.feature.home.domain.GeoPoint
import com.zillit.desktop.feature.home.domain.HomeUnit
import com.zillit.desktop.feature.home.domain.Notice
import com.zillit.desktop.feature.home.domain.NoticeComment
import com.zillit.desktop.feature.home.domain.NoticeKind
import com.zillit.desktop.feature.home.ui.HomeFeedEvent
import com.zillit.desktop.feature.home.ui.HomeFeedScreen
import com.zillit.desktop.feature.home.ui.HomeFeedUiState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Sharing and reading a place, on the board itself.
 *
 * Composed rather than asserted in the model because both halves of this
 * feature are only real on screen: a composer button wired to a picker that
 * never opens, and a card whose text the bubble draws twice, are both
 * invisible to a unit test.
 */
@OptIn(ExperimentalTestApi::class)
class LocationShareRenderTest {

    private val unit = HomeUnit(
        id = "u1",
        identifier = "general_tool",
        unitName = "general_label",
        canView = true,
        canPost = true,
    )

    private val place = GeoPoint(18.94, 72.82, "Aria Hotel, 12 Marine Drive, Mumbai")

    private fun board(vararg posts: Notice) = HomeFeedUiState(
        units = listOf(unit),
        selectedUnitId = unit.id,
        notices = posts.toList(),
        currentUserId = "me",
        nowMillis = 1_700_000_000_000,
    )

    /** A picker that always answers, so the composer's click has somewhere to go. */
    private class FakePicker(private val place: PickedLocation?) : LocationPicker {
        var titleAsked: String? = null
        override suspend fun pick(initial: PickedLocation?, title: String): PickedLocation? {
            titleAsked = title
            return place
        }
    }

    @Test
    fun `the composer's pin opens the shared picker and attaches what it returns`() = runComposeUiTest {
        val picker = FakePicker(
            PickedLocation(name = "Aria Hotel", address = "12 Marine Drive, Mumbai", lat = 18.94, lng = 72.82),
        )
        val events = mutableListOf<HomeFeedEvent>()

        setContent {
            ZillitTheme {
                CompositionLocalProvider(LocalLocationPicker provides picker) {
                    HomeFeedScreen(state = board(), onEvent = { events += it })
                }
            }
        }

        onNodeWithContentDescription("Share a location").performClick()
        waitForIdle()

        val attached = events.filterIsInstance<HomeFeedEvent.AttachLocation>().single()
        assertEquals(18.94, attached.point.lat)
        // `lng` on the picker, `long` on the wire — the rename happens here.
        assertEquals(72.82, attached.point.long)
        // Name and address joined into the one line the wire can carry.
        assertEquals("Aria Hotel, 12 Marine Drive, Mumbai", attached.point.address)
        assertEquals("Share a location", picker.titleAsked)
    }

    /**
     * No picker wired — a host without a map, or this test without the
     * provider. The pin still works, through the typed form.
     */
    @Test
    fun `without a picker the pin falls back to the typed form`() = runComposeUiTest {
        setContent { ZillitTheme { HomeFeedScreen(state = board(), onEvent = {}) } }

        onNodeWithContentDescription("Share a location").performClick()

        onNodeWithText("Attach location").assertExists()
    }

    @Test
    fun `a shared place shows its name, address, point and the way to Maps`() = runComposeUiTest {
        val opened = mutableListOf<GeoPoint>()
        val post = Notice(
            id = "n1",
            // The body IS the address on a bare location share, on every client.
            body = "Aria Hotel, 12 Marine Drive, Mumbai",
            authorName = "Sam",
            authorId = "them",
            kind = NoticeKind.Location,
            location = place,
        )

        setContent {
            ZillitTheme {
                HomeFeedScreen(state = board(post), onEvent = {}, onOpenLocation = { opened += it })
            }
        }

        onNodeWithText("Aria Hotel").assertExists()
        onNodeWithText("12 Marine Drive, Mumbai").assertExists()
        onNodeWithText("18.94, 72.82").assertExists()

        // The guarded external-URL launcher the board threads in — nothing
        // here reaches for a browser itself.
        onNodeWithText("Open in Maps").performClick()
        assertEquals(listOf(place), opened)
    }

    /**
     * The address must not be printed twice — once by the card, once as the
     * post's body — which is what a bare share sends, since the body is where
     * the phones put the address.
     */
    @Test
    fun `a bare share does not print its address twice`() = runComposeUiTest {
        val bare = Notice(
            id = "n1",
            body = "Aria Hotel, 12 Marine Drive, Mumbai",
            authorName = "Sam",
            kind = NoticeKind.Location,
            location = place,
        )

        setContent { ZillitTheme { HomeFeedScreen(state = board(bare), onEvent = {}) } }

        // The card splits the line in two, so the joined body — drawn as its
        // own paragraph — would be a third node nobody asked for.
        onAllNodesWithText("Aria Hotel, 12 Marine Drive, Mumbai").assertCountEquals(0)
        onNodeWithText("Aria Hotel").assertExists()
        onNodeWithText("12 Marine Drive, Mumbai").assertExists()
    }

    /** A caption the crew member actually typed is not the address, and shows. */
    @Test
    fun `a caption shows beside the card`() = runComposeUiTest {
        val captioned = Notice(
            id = "n1",
            body = "park the trucks on the far side",
            authorName = "Sam",
            kind = NoticeKind.Location,
            location = place,
        )

        setContent { ZillitTheme { HomeFeedScreen(state = board(captioned), onEvent = {}) } }

        onNodeWithText("park the trucks on the far side").assertExists()
        onNodeWithText("Aria Hotel").assertExists()
    }

    /** Replying to a place still works — the thread is the point of a board. */
    @Test
    fun `a location post keeps its replies`() = runComposeUiTest {
        val post = Notice(
            id = "n1",
            body = "Aria Hotel, 12 Marine Drive, Mumbai",
            authorName = "Sam",
            kind = NoticeKind.Location,
            location = place,
            comments = listOf(NoticeComment(id = "c1", body = "on my way", authorId = "them")),
        )

        setContent { ZillitTheme { HomeFeedScreen(state = board(post), onEvent = {}) } }

        onNodeWithText("on my way").assertExists()
        assertTrue(post.showsBody.not(), "the card already says the address")
    }
}
