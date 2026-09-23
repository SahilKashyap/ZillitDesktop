package com.zillit.desktop.feature.calls

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.feature.calls.domain.CallPhase
import com.zillit.desktop.feature.calls.domain.CallProvider
import com.zillit.desktop.feature.calls.domain.CallSession
import com.zillit.desktop.feature.calls.ui.CallDock
import com.zillit.desktop.feature.calls.ui.CallEvent
import com.zillit.desktop.feature.calls.ui.CallMorePanel
import com.zillit.desktop.feature.calls.ui.CallUiState
import kotlin.test.Test
import kotlin.test.assertEquals

/** The web's control bar, control for control (`CallRoom.tsx:1619-1881`). */
@OptIn(ExperimentalTestApi::class)
class CallDockRenderTest {

    private fun state(
        micMuted: Boolean = false,
        cameraOn: Boolean = false,
        chatUnread: Int = 0,
    ) = CallUiState(
        phase = CallPhase.InCall,
        session = CallSession(callUuid = "c1", provider = CallProvider.LiveKit),
        micMuted = micMuted,
        cameraOn = cameraOn,
        chatUnread = chatUnread,
    )

    @Test
    fun `the bar carries the web's controls in its order`() = runComposeUiTest {
        setContent { ZillitTheme(darkTheme = true, animateThemeChange = false) { CallDock(state(), {}) } }

        listOf(
            "Audio settings", "Mute", "Video settings", "Turn camera on",
            "Raise Hand", "Present", "Send a reaction", "More options", "Leave call", "Chat",
        ).forEach { onNodeWithContentDescription(it).assertIsDisplayed() }
    }

    @Test
    fun `muted and camera-off read as such, and the unread count caps at nine`() = runComposeUiTest {
        setContent {
            ZillitTheme(darkTheme = true, animateThemeChange = false) {
                CallDock(state(micMuted = true, cameraOn = true, chatUnread = 14), {})
            }
        }

        onNodeWithContentDescription("Unmute").assertIsDisplayed()
        onNodeWithContentDescription("Turn camera off").assertIsDisplayed()
        onNodeWithText("9+").assertIsDisplayed()
    }

    @Test
    fun `the overflow button opens the panel, which holds recording and the window moves`() = runComposeUiTest {
        val events = mutableListOf<CallEvent>()
        setContent { ZillitTheme(darkTheme = true, animateThemeChange = false) { CallDock(state(), events::add) } }
        onNodeWithContentDescription("More options").performClick()
        assertEquals(listOf<CallEvent>(CallEvent.ToggleMore), events)

        events.clear()
        setContent {
            ZillitTheme(darkTheme = true, animateThemeChange = false) {
                CallMorePanel(state().copy(moreOpen = true), events::add)
            }
        }
        onNodeWithText("Start recording").assertIsDisplayed()
        onNodeWithText("Open in its own window").assertIsDisplayed()
        onNodeWithText("Start recording").performClick()

        assertEquals(listOf<CallEvent>(CallEvent.ToggleMore, CallEvent.ToggleRecording), events)
    }

    /** During a ring the bar stays put but its room verbs are inert. */
    @Test
    fun `a disconnected bar keeps every control but answers none of the room verbs`() = runComposeUiTest {
        val events = mutableListOf<CallEvent>()
        setContent {
            ZillitTheme(darkTheme = true, animateThemeChange = false) {
                CallDock(state().copy(phase = CallPhase.Outgoing), events::add, connected = false)
            }
        }

        onNodeWithContentDescription("Raise Hand").performClick()
        onNodeWithContentDescription("Mute").performClick()

        assertEquals(listOf<CallEvent>(CallEvent.ToggleMic), events)
    }
}
