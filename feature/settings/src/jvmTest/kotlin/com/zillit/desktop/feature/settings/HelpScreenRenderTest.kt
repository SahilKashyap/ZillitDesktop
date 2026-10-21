package com.zillit.desktop.feature.settings

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.settings.ui.HelpScreen
import com.zillit.desktop.feature.settings.ui.ZILLIT_TOUR_URL
import com.zillit.desktop.feature.settings.ui.ZillitTourUnavailable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Zillit Tour card, and the window it opens.
 *
 * Every card carries an identically labelled *Open*, so the tour's is reached
 * by position — it is the first card, as it is the first row of iOS's guide.
 */
@OptIn(ExperimentalTestApi::class)
class HelpScreenRenderTest {

    @Test
    fun `the tour card opens the tour inside the app, not the browser`() = runComposeUiTest {
        val opened = mutableListOf<String>()
        var tours = 0
        setContent {
            ZillitTheme {
                HelpScreen(
                    onOpenExternal = { opened += it },
                    onContactSupport = {},
                    onOpenTour = { tours++ },
                )
            }
        }

        onNodeWithText(str(S.desktop_zillit_tour)).assertIsDisplayed()
        onNodeWithText(str(S.desktop_help_tour_blurb)).assertIsDisplayed()
        onAllNodesWithText(str(S.recce_open))[0].performClick()

        assertEquals(1, tours)
        assertTrue(opened.isEmpty(), "the tour must not be handed to the browser")
    }

    @Test
    fun `with no tour window, the card opens the hosted page instead`() = runComposeUiTest {
        val opened = mutableListOf<String>()
        setContent {
            ZillitTheme {
                HelpScreen(onOpenExternal = { opened += it }, onContactSupport = {}, onOpenTour = null)
            }
        }

        onAllNodesWithText(str(S.recce_open))[0].performClick()

        assertEquals(listOf(ZILLIT_TOUR_URL), opened)
    }

    @Test
    fun `a build with no embedded browser offers the tour's own address`() = runComposeUiTest {
        val opened = mutableListOf<String>()
        setContent {
            ZillitTheme {
                ZillitTourUnavailable(
                    reason = str(S.desktop_no_embedded_browser_tour),
                    onOpenInBrowser = { opened += ZILLIT_TOUR_URL },
                )
            }
        }

        onNodeWithText(str(S.desktop_no_embedded_browser_tour)).assertIsDisplayed()
        onNodeWithText(str(S.desktop_drive_open_in_browser)).performClick()

        assertEquals(listOf(ZILLIT_TOUR_URL), opened)
    }
}
