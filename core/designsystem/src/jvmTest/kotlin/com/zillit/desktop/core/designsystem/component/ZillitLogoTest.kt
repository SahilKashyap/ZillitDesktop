package com.zillit.desktop.core.designsystem.component

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zillit.desktop.core.designsystem.ZillitTheme
import kotlin.test.Test

/**
 * The mark is a bundled file, and a file is the one part of a composable that
 * can be missing at runtime while the code compiles: a renamed or unpackaged
 * drawable paints nothing and the launch splash goes blank. So this renders it
 * rather than asserting on the call.
 */
@OptIn(ExperimentalTestApi::class)
class ZillitLogoTest {

    @Test
    fun `the logo paints`() = runComposeUiTest {
        setContent {
            ZillitTheme(darkTheme = true, animateThemeChange = false) { ZillitLogo() }
        }
        // The drawable is loaded off the composition, so the first frame has
        // nothing in it.
        waitForIdle()

        onNodeWithContentDescription("Zillit").assertIsDisplayed()
    }
}
