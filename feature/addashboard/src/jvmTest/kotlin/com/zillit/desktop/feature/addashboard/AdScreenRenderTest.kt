package com.zillit.desktop.feature.addashboard

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.feature.addashboard.domain.AdDayStatus
import com.zillit.desktop.feature.addashboard.domain.AdShootDay
import com.zillit.desktop.feature.addashboard.domain.AdViewer
import com.zillit.desktop.feature.addashboard.domain.Artiste
import com.zillit.desktop.feature.addashboard.domain.ArtisteStatus
import com.zillit.desktop.feature.addashboard.domain.AttendanceStatus
import com.zillit.desktop.feature.addashboard.domain.SupportingArtistDay
import com.zillit.desktop.feature.addashboard.ui.AdDestination
import com.zillit.desktop.feature.addashboard.ui.AdScreen
import com.zillit.desktop.feature.addashboard.ui.AdUiState
import kotlin.test.Test

/** Composes the real dashboard in each state, light and dark. */
@OptIn(ExperimentalTestApi::class)
class AdScreenRenderTest {

    private val ad = AdViewer(userId = "u1", canPost = true, ready = true)

    private fun state(
        destination: AdDestination = AdDestination.Today,
        dayStatus: AdDayStatus = AdDayStatus.InProgress,
        viewer: AdViewer = ad,
    ) = AdUiState(
        viewer = viewer,
        destination = destination,
        shootDate = 1_772_755_200_000,
        today = AdShootDay(
            id = "d1",
            shootDate = 1_772_755_200_000,
            dayNumber = 12,
            status = dayStatus,
            unitName = "Main unit",
        ),
        dayList = listOf(
            SupportingArtistDay(
                id = "s1",
                artisteId = "a1",
                artisteName = "Ada Lovelace",
                callTime = "07:00",
                attendance = AttendanceStatus.Present,
                signStatus = "typed",
            ),
            SupportingArtistDay(id = "s2", artisteId = "a2", artisteName = "Ravi Menon"),
        ),
        artistes = listOf(
            Artiste(id = "a1", name = "Ada Lovelace", refNumber = "SA-0042"),
            Artiste(id = "a2", name = "Ravi Menon", status = ArtisteStatus.Verified),
        ),
        shootDays = listOf(AdShootDay(id = "d1", shootDate = 1_772_755_200_000, dayNumber = 12)),
    )

    @Test
    fun `every section composes in both themes`() {
        listOf(false, true).forEach { dark ->
            AdDestination.entries.forEach { destination ->
                runComposeUiTest {
                    setContent {
                        ZillitTheme(darkTheme = dark) {
                            AdScreen(state = state(destination), onEvent = {})
                        }
                    }
                    onNodeWithText("AD dashboard").assertExists()
                }
            }
        }
    }

    @Test
    fun `the day leads with who is on it and who has signed`() {
        runComposeUiTest {
            setContent {
                ZillitTheme(darkTheme = false) { AdScreen(state = state(), onEvent = {}) }
            }
            onNodeWithText("ON THE CALL").assertExists()
            onNodeWithText("NOT YET SIGNED").assertExists()
            onNodeWithText("Ada Lovelace").assertExists()
            onNodeWithText("Typed signature").assertExists()
        }
    }

    /**
     * A submitted day says so once, at the top — a screen full of dead
     * controls with no explanation is the worse failure.
     */
    @Test
    fun `a submitted day explains itself and offers no controls`() {
        val sent = state(dayStatus = AdDayStatus.Submitted)

        runComposeUiTest {
            setContent {
                ZillitTheme(darkTheme = false) { AdScreen(state = sent, onEvent = {}) }
            }
            onNodeWithText(
                "This day has been submitted and can no longer be changed. Ask project to " +
                    "reopen it if something is wrong.",
            ).assertExists()
            onAllNodesWithText("Submit day").assertCountEquals(0)
            onAllNodesWithText("Add artistes").assertCountEquals(0)
        }
    }

    @Test
    fun `an open day offers the controls`() {
        runComposeUiTest {
            setContent {
                ZillitTheme(darkTheme = false) { AdScreen(state = state(), onEvent = {}) }
            }
            onNodeWithText("Submit day").assertExists()
            onNodeWithText("Add artistes").assertExists()
        }
    }

    /** A reader is told once rather than discovering it button by button. */
    @Test
    fun `a viewer without posting rights is told so, and keeps the controls`() {
        val reader = state(viewer = AdViewer(userId = "u1", canPost = false, ready = true))

        runComposeUiTest {
            setContent {
                ZillitTheme(darkTheme = false) { AdScreen(state = reader, onEvent = {}) }
            }
            onNodeWithText("You can see the roster and the day but not change them.").assertExists()
            // Still on screen: an open day's controls belong to the day, and
            // AdViewModel answers a press without the right by offering to ask
            // an administrator. Only a submitted day takes them away.
            onAllNodesWithText("Submit day").assertCountEquals(1)
        }
    }

    @Test
    fun `a submitted day takes the controls away from everyone`() {
        val locked = state(
            dayStatus = AdDayStatus.Submitted,
            viewer = AdViewer(userId = "u1", canPost = true, ready = true),
        )

        runComposeUiTest {
            setContent {
                ZillitTheme(darkTheme = false) { AdScreen(state = locked, onEvent = {}) }
            }
            // Not a rights question, so there is nothing to ask for: the day
            // is shut to admins too.
            onAllNodesWithText("Submit day").assertCountEquals(0)
        }
    }

    @Test
    fun `a viewer without the tool is refused`() {
        val blocked = AdUiState(viewer = AdViewer(userId = "u1", canView = false, ready = true))

        runComposeUiTest {
            setContent {
                ZillitTheme(darkTheme = false) { AdScreen(state = blocked, onEvent = {}) }
            }
            onNodeWithText("No access").assertExists()
        }
    }

    @Test
    fun `the register lists the roster with its references`() {
        runComposeUiTest {
            setContent {
                ZillitTheme(darkTheme = false) {
                    AdScreen(state = state(AdDestination.Register), onEvent = {})
                }
            }
            onNodeWithText("Ada Lovelace").assertExists()
            onNodeWithText("2 of 2").assertExists()
        }
    }

    @Test
    fun `an empty day says what to do about it`() {
        val empty = state().copy(dayList = emptyList())

        runComposeUiTest {
            setContent {
                ZillitTheme(darkTheme = false) { AdScreen(state = empty, onEvent = {}) }
            }
            onNodeWithText("Nobody on this day yet").assertExists()
            onNodeWithText("Add artistes from the register to build the call.").assertExists()
        }
    }
}
