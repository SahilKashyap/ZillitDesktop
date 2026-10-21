package com.zillit.desktop.feature.calls

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.feature.calls.ui.CallChatLine
import com.zillit.desktop.feature.calls.ui.CallChatPanel
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Composes the in-call chat for real.
 *
 * The panel keeps nothing — the lines go when the call does — so an address
 * dropped into it has to be clickable while the call is up or it is lost.
 * That is what is pinned here.
 */
@OptIn(ExperimentalTestApi::class)
class CallChatPanelRenderTest {

    @Test
    fun `an address in a line opens in the browser when clicked`() = runComposeUiTest {
        val opened = mutableListOf<String>()
        setContent { Panel("https://zillit.com/cs/4", opened) }

        onNodeWithText("https://zillit.com/cs/4").performMouseInput { click(center) }
        waitForIdle()

        assertEquals(listOf("https://zillit.com/cs/4"), opened)
    }

    @Test
    fun `a bare www address goes out over https`() = runComposeUiTest {
        val opened = mutableListOf<String>()
        setContent { Panel("www.zillit.com", opened) }

        onNodeWithText("www.zillit.com").performMouseInput { click(center) }
        waitForIdle()

        assertEquals(listOf("https://www.zillit.com"), opened)
    }

    @Test
    fun `a line with no address stays plain words and opens nothing`() = runComposeUiTest {
        val opened = mutableListOf<String>()
        setContent { Panel("one minute", opened) }

        onNodeWithText("one minute").performMouseInput { click(center) }
        waitForIdle()

        assertEquals(emptyList(), opened)
    }
}

/** The panel with one received line, its link opening into [opened]. */
@Composable
private fun Panel(text: String, opened: MutableList<String>) {
    ZillitTheme(darkTheme = false) {
        val handler = object : UriHandler {
            override fun openUri(uri: String) {
                opened += uri
            }
        }
        CompositionLocalProvider(LocalUriHandler provides handler) {
            CallChatPanel(
                lines = listOf(
                    CallChatLine(
                        id = "l1",
                        text = text,
                        name = "Aisha Khan",
                        fromSelf = false,
                        atMillis = 1_786_507_000_000L,
                    ),
                ),
                onSend = {},
                modifier = Modifier.size(PANEL_WIDTH, PANEL_HEIGHT),
            )
        }
    }
}

private val PANEL_WIDTH = 320.dp
private val PANEL_HEIGHT = 420.dp
