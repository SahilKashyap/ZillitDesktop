package com.zillit.desktop.feature.home

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.permissions.ProjectPermissions
import com.zillit.desktop.core.permissions.ToolAccess
import com.zillit.desktop.feature.home.domain.ToolGroup
import com.zillit.desktop.feature.home.ui.HomeScreen
import com.zillit.desktop.feature.home.ui.HomeUiState
import kotlin.test.Test

/**
 * Composes the real grid, because the rules brought into line with the phones
 * are ones a unit test cannot see: a header is emitted by the lazy grid, the
 * tile's name is now an `AnnotatedString`, and the reorder control is an icon
 * with no text of its own.
 */
@OptIn(ExperimentalTestApi::class)
class ToolsGridRenderTest {

    private fun tool(identifier: String, name: String, group: String?) = ToolAccess(
        identifier = identifier,
        unitName = name,
        groupIdentifier = group,
        enabled = true,
        canView = true,
        isTool = true,
    )

    /** One group only — the case that used to lose its heading. */
    private val oneGroup = HomeUiState(
        permissions = ProjectPermissions(
            listOf(
                tool("timecard_tool", "Timecards", "accounts"),
                tool("payroll_tool", "Payroll", "accounts"),
                tool("invoices_tool", "Invoices", "accounts"),
            ),
        ),
        groups = listOf(ToolGroup("accounts", "Accounts")),
    )

    @Test
    fun `the customise entry shows for an admin and nobody else`() {
        runComposeUiTest {
            // Admin with the host offering a destination: the button is there.
            setContent {
                ZillitTheme {
                    HomeScreen(
                        state = oneGroup.copy(isAdmin = true),
                        onEvent = {},
                        onCustomiseTools = {},
                    )
                }
            }
            onNodeWithContentDescription("Customise tools").assertExists()
        }
        runComposeUiTest {
            // Not an admin: the same offer renders nothing — the phones gate
            // their button on `isAdmin` too (`Tools.kt:333`).
            setContent {
                ZillitTheme {
                    HomeScreen(state = oneGroup, onEvent = {}, onCustomiseTools = {})
                }
            }
            onAllNodesWithContentDescription("Customise tools").assertCountEquals(0)
        }
    }

    @Test
    fun `a single group still gets its heading, with a count`() {
        runComposeUiTest {
            setContent { ZillitTheme { HomeScreen(state = oneGroup, onEvent = {}) } }

            // The heading, and the count beside it. Both phones emit one header
            // per non-empty group with no single-group exception.
            onNodeWithText("Accounts").assertExists()
            onNodeWithText("3").assertExists()
        }
    }

    @Test
    fun `tiles are titled by the tool's own name`() {
        runComposeUiTest {
            setContent { ZillitTheme { HomeScreen(state = oneGroup, onEvent = {}) } }

            // Not "Timecard" from the identifier — "Timecards", as sent.
            onNodeWithText("Timecards").assertExists()
            onNodeWithText("Payroll").assertExists()
        }
    }

    @Test
    fun `the reorder control is offered even when one section is rendered`() {
        runComposeUiTest {
            setContent { ZillitTheme { HomeScreen(state = oneGroup, onEvent = {}) } }

            onNodeWithContentDescription("Reorder groups").assertExists()
        }
    }

    @Test
    fun `a production with no groups at all offers nothing to reorder`() {
        runComposeUiTest {
            setContent {
                ZillitTheme {
                    HomeScreen(state = oneGroup.copy(groups = emptyList()), onEvent = {})
                }
            }

            onAllNodesWithContentDescription("Reorder groups").assertCountEquals(0)
        }
    }

    @Test
    fun `the grid composes with badges, which decide the order`() {
        runComposeUiTest {
            setContent {
                ZillitTheme {
                    HomeScreen(
                        state = oneGroup,
                        onEvent = {},
                        toolBadges = mapOf("payroll_tool" to 120),
                    )
                }
            }

            // Uncapped on a tile, where both phones print the real number.
            onNodeWithText("120").assertExists()
            onAllNodesWithText("99+").assertCountEquals(0)
        }
    }
}
