package com.zillit.desktop.core.designsystem.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.ScrollWheel
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * That the wheel override cannot break scrolling outright.
 *
 * ## What this can and cannot check
 *
 * The override only acts on events carrying an AWT `MouseWheelEvent`, because
 * that is the only place the operating system's lines-per-notch setting and the
 * precise-vs-stepped distinction are available. Compose's test injection
 * synthesises events without one, so these scrolls take the **fall-through**
 * path — which is exactly the property worth pinning: an event the override
 * does not recognise must reach the platform's own handling untouched rather
 * than being swallowed.
 *
 * The amplified path itself needs a real mouse and cannot be asserted here. Its
 * arithmetic is covered separately in `WheelScrollTest`.
 */
@OptIn(ExperimentalTestApi::class)
class WheelScrollFallbackTest {

    @Test
    fun `a scroll the override does not recognise still scrolls the list`() {
        runComposeUiTest {
            var state: androidx.compose.foundation.lazy.LazyListState? = null
            setContent {
                val listState = rememberLazyListState()
                state = listState
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .testTag("list")
                        .size(200.dp)
                        .then(rememberWheelScroll(listState)),
                ) {
                    items((1..100).toList()) { index ->
                        Box(Modifier.fillMaxWidth().height(40.dp).testTag("row-$index"))
                    }
                }
            }

            onNodeWithTag("list").performMouseInput {
                repeat(5) { scroll(3f, ScrollWheel.Vertical) }
            }
            waitForIdle()

            // Somewhere further down than it started. The distance is the
            // platform's business, not this test's — only that it moved.
            val moved = state?.let { it.firstVisibleItemIndex > 0 || it.firstVisibleItemScrollOffset > 0 }
            assertTrue(moved == true, "the list did not scroll at all")
        }
    }
}
