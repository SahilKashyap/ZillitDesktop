package com.zillit.desktop.feature.sides

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.feature.sides.domain.SidesViewer
import com.zillit.desktop.feature.sides.ui.SidesDestination
import com.zillit.desktop.feature.sides.ui.SidesScreen
import com.zillit.desktop.feature.sides.ui.SidesUiState
import kotlin.test.Test

/** Composes the real sides screen at each destination, in both themes. */
@OptIn(ExperimentalTestApi::class)
class SidesScreenRenderTest {

    @Test
    fun `each destination composes in both themes`() {
        SidesDestination.entries.forEach { destination ->
            listOf(false, true).forEach { dark ->
                runComposeUiTest {
                    setContent {
                        ZillitTheme(darkTheme = dark) {
                            SidesScreen(
                                state = SidesUiState(
                                    viewer = SidesViewer(canView = true),
                                    destination = destination,
                                ),
                                onEvent = {},
                            )
                        }
                    }
                }
            }
        }
    }

    /** The history sheet is its own layout over the list. */
    @Test
    fun `the history panel composes`() {
        runComposeUiTest {
            setContent {
                ZillitTheme(darkTheme = false) {
                    SidesScreen(
                        state = SidesUiState(viewer = SidesViewer(canView = true), showHistory = true),
                        onEvent = {},
                    )
                }
            }
        }
    }
}
