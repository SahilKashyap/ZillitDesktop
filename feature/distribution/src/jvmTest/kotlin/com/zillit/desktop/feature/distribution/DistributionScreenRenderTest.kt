package com.zillit.desktop.feature.distribution

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.filter
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onChildren
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.feature.distribution.domain.DistributionPerson
import com.zillit.desktop.feature.distribution.domain.DistributionSection
import com.zillit.desktop.feature.distribution.domain.DistributionUnit
import com.zillit.desktop.feature.distribution.domain.DistributionUser
import com.zillit.desktop.feature.distribution.domain.DistributionViewer
import com.zillit.desktop.feature.distribution.ui.DistributionEvent
import com.zillit.desktop.feature.distribution.ui.DistributionScreen
import com.zillit.desktop.feature.distribution.ui.DistributionUiState
import com.zillit.desktop.feature.distribution.ui.cellTag
import kotlin.test.Test
import kotlin.test.assertEquals

/** Composes the grid: the people down the side, the units across, a box per cell. */
@OptIn(ExperimentalTestApi::class)
class DistributionScreenRenderTest {

    private val crew = DistributionUser(
        userId = "u1",
        userName = "Aisha Khan",
        status = "accepted",
        units = listOf(
            DistributionUnit(unitId = "h1", unitName = "Bulletin", toEnabled = true, isHome = true),
            DistributionUnit(unitId = "h2", unitName = "Alerts", isHome = true),
            DistributionUnit(unitId = "t1", unitName = "Location", isTool = true),
        ),
    )

    private val outsider = DistributionUser(
        userId = "u2",
        userName = "Grip Hire",
        userType = "external",
        outsider = "outsider",
        units = listOf(DistributionUnit(unitId = "h1", unitName = "Bulletin", isHome = true)),
    )

    private val state = DistributionUiState(
        users = listOf(crew, outsider),
        people = mapOf(
            "u1" to DistributionPerson("u1", fullName = "Aisha Khan", designation = "Gaffer", status = "accepted"),
            "u2" to DistributionPerson("u2", fullName = "Grip Hire", email = "hire@grip.example", isExternal = true),
        ),
        viewer = DistributionViewer(canView = true, canPost = true, isAdmin = true, ready = true),
    )

    @Test
    fun `the grid shows people, the section's units, and the banners`() = runComposeUiTest {
        setContent { ZillitTheme { DistributionScreen(state = state, onEvent = {}) } }

        onNodeWithText("Distribution List").assertExists()
        onNodeWithText("Aisha Khan").assertExists()
        onNodeWithText("Gaffer").assertExists()
        onNodeWithText("Grip Hire").assertExists()
        onNodeWithText("hire@grip.example").assertExists()
        onNodeWithText("Outsider").assertExists()
        // Home is the opening section: its two units head the columns, the tool does not.
        onNodeWithText("Bulletin").assertExists()
        onNodeWithText("Alerts").assertExists()
        onNodeWithText("Location").assertDoesNotExist()
        // The web's pinned note and the admin-only listing-order hint.
        onAllNodesWithText("MUST READ").assertCountEquals(3)
        onNodeWithText("Click here").assertExists()
        onNodeWithText("1–2 of 2").assertExists()
    }

    @Test
    fun `a cell the server did not send is a dash, the rest are boxes that toggle`() = runComposeUiTest {
        val events = mutableListOf<DistributionEvent>()
        setContent { ZillitTheme { DistributionScreen(state = state, onEvent = events::add) } }

        // Aisha: Bulletin + Alerts; Grip Hire: Bulletin only — Alerts is a dash.
        onNodeWithTag(cellTag("u2", "h2")).onChildren().filter(hasText("—")).assertCountEquals(1)
        onNodeWithTag(cellTag("u2", "h1")).onChildren().filter(hasClickAction()).assertCountEquals(1)

        // Aisha is on Bulletin already: the click asks to take her off it.
        onNodeWithTag(cellTag("u1", "h1")).onChildren().filter(hasClickAction())[0].performClick()
        val toggle = events.filterIsInstance<DistributionEvent.Toggle>().single()
        assertEquals(DistributionEvent.Toggle("u1", "h1", enabled = false), toggle)
    }

    @Test
    fun `the tools section heads the columns with the tools`() = runComposeUiTest {
        setContent {
            ZillitTheme {
                DistributionScreen(state = state.copy(section = DistributionSection.Tools), onEvent = {})
            }
        }
        onNodeWithText("Location").assertExists()
        onNodeWithText("Bulletin").assertDoesNotExist()
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
        onNodeWithText("No access").assertExists()
        onAllNodesWithText("MUST READ").assertCountEquals(0)
    }
}
