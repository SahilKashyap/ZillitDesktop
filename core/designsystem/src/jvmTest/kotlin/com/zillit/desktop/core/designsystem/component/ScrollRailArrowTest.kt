package com.zillit.desktop.core.designsystem.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A click on a rail arrow must move the list a full step.
 *
 * The regression this pins: the step used to run inside the press-watching
 * effect, which is cancelled the instant the button releases — so a click
 * animated for one frame and stopped, indistinguishable on screen from a
 * dead button. Only a click-then-measure test catches that; the rail
 * rendered perfectly all along.
 */
@OptIn(ExperimentalTestApi::class)
class ScrollRailArrowTest {

    @Test
    fun `an arrow click scrolls a real distance, not a cancelled frame`() = runComposeUiTest {
        var state by mutableStateOf<LazyListState?>(null)
        setContent {
            ZillitTheme(darkTheme = false) {
                val list = rememberLazyListState().also { state = it }
                Box(Modifier.size(300.dp)) {
                    LazyColumn(state = list, modifier = Modifier.fillMaxSize()) {
                        items(count = 200) { Box(Modifier.height(24.dp)) }
                    }
                    ZillitScrollRail(list, Modifier.align(Alignment.CenterEnd))
                }
            }
        }

        onNodeWithContentDescription("Scroll down").performClick()
        waitForIdle()

        val moved = state!!.firstVisibleItemIndex * 24 + state!!.firstVisibleItemScrollOffset
        // A full step is three wheel lines; the cancelled-frame bug moved
        // a couple of pixels. Anything past half a step is a real nudge.
        assertTrue(moved > 24, "arrow click moved only ${moved}px")
    }

    @Test
    fun `the up arrow comes back the same distance`() = runComposeUiTest {
        var state by mutableStateOf<LazyListState?>(null)
        setContent {
            ZillitTheme(darkTheme = false) {
                val list = rememberLazyListState().also { state = it }
                Box(Modifier.size(300.dp)) {
                    LazyColumn(state = list, modifier = Modifier.fillMaxSize()) {
                        items(count = 200) { Box(Modifier.height(24.dp)) }
                    }
                    ZillitScrollRail(list, Modifier.align(Alignment.CenterEnd))
                }
            }
        }

        onNodeWithContentDescription("Scroll down").performClick()
        waitForIdle()
        val down = state!!.firstVisibleItemIndex * 24 + state!!.firstVisibleItemScrollOffset
        onNodeWithContentDescription("Scroll up").performClick()
        waitForIdle()
        val back = state!!.firstVisibleItemIndex * 24 + state!!.firstVisibleItemScrollOffset

        assertTrue(down > 24, "down step moved only ${down}px")
        assertTrue(back < down / 2, "up step barely returned: ${back}px of ${down}px")
    }
}
