package com.zillit.desktop.feature.calls

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.feature.calls.domain.CallDirection
import com.zillit.desktop.feature.calls.domain.CallPhase
import com.zillit.desktop.feature.calls.domain.CallProvider
import com.zillit.desktop.feature.calls.domain.CallSession
import com.zillit.desktop.feature.calls.ui.CallOverlay
import com.zillit.desktop.feature.calls.ui.CallUiState
import kotlin.test.Test

/**
 * The overlay over the project list: it carries a running call's bar, but
 * leaves an incoming ring to the floating card, which already rings there.
 */
@OptIn(ExperimentalTestApi::class)
class CallOverlayRingRenderTest {

    private val ringing = CallUiState(
        phase = CallPhase.Incoming,
        session = CallSession(
            callUuid = "c1",
            provider = CallProvider.Agora,
            direction = CallDirection.Incoming,
            callerName = "Vivek Mishra",
        ),
    )

    @Test
    fun `the workspace overlay rings`() = runComposeUiTest {
        setContent { ZillitTheme { CallOverlay(state = ringing, onEvent = {}) } }
        onNodeWithText("Vivek Mishra").assertExists()
    }

    @Test
    fun `the project list overlay leaves the ring to the floating card`() = runComposeUiTest {
        setContent { ZillitTheme { CallOverlay(state = ringing, onEvent = {}, showsIncomingRing = false) } }
        onNodeWithText("Vivek Mishra").assertDoesNotExist()
    }
}
