package com.zillit.desktop.feature.distribution

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.feature.distribution.domain.DistributionUnit
import com.zillit.desktop.feature.distribution.domain.DistributionUser
import com.zillit.desktop.feature.distribution.domain.DistributionViewer
import com.zillit.desktop.feature.distribution.ui.DistributionScreen
import com.zillit.desktop.feature.distribution.ui.DistributionUiState
import kotlin.test.Test

/** Composes the roster and the picked user's unit switches. */
@OptIn(ExperimentalTestApi::class)
class DistributionScreenRenderTest {

    private val crew = DistributionUser(
        userId = "u1",
        userName = "Aisha Khan",
        status = "accepted",
        units = listOf(
            DistributionUnit(unitId = "h1", unitName = "Bulletin", toEnabled = true, isHome = true),
            DistributionUnit(unitId = "t1", unitName = "Location", isTool = true),
        ),
    )

    private val outsider = DistributionUser(
        userId = "u2",
        userName = "Grip Hire",
        userType = "external",
        outsider = "outsider",
    )

    private val state = DistributionUiState(
        users = listOf(crew, outsider),
        viewer = DistributionViewer(canView = true, canPost = true, ready = true),
    )

    @Test
    fun `the roster lists crew and outsiders, the panel shows the section's units`() = runComposeUiTest {
        setContent { ZillitTheme { DistributionScreen(state = state, onEvent = {}) } }

        // Twice: the roster row and the panel's heading — she is selected.
        onAllNodesWithText("Aisha Khan").assertCountEquals(2)
        onNodeWithText("Grip Hire").assertExists()
        onNodeWithText("Outsider").assertExists()
        // The first listed user is auto-selected; Home is the opening section.
        onNodeWithText("Bulletin").assertExists()
        onNodeWithText("DISTRIBUTION").assertExists()
        onNodeWithText("TO").assertExists()
    }

    @Test
    fun `no view right and no admin means a closed door`() = runComposeUiTest {
        setContent {
            ZillitTheme {
                DistributionScreen(
                    state = DistributionUiState(viewer = DistributionViewer(canView = false, ready = true)),
                    onEvent = {},
                )
            }
        }
        onNodeWithText("You don't have access to the Distribution List.").assertExists()
    }
}
