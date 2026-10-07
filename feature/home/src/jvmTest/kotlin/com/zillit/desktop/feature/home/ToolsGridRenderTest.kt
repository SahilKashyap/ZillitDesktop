package com.zillit.desktop.feature.home

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.permissions.ProjectPermissions
import com.zillit.desktop.core.permissions.ToolAccess
import com.zillit.desktop.feature.home.domain.ToolGroup
import com.zillit.desktop.feature.home.ui.HomeEvent
import com.zillit.desktop.feature.home.ui.HomeScreen
import com.zillit.desktop.feature.home.ui.HomeUiState
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Composes the real page against the web's Film Tools (`FilmTools.jsx`): the
 * header's controls, the admin notice, a section's glyph heading and
 * subheading, the tile cards, the ⓘ, and the organize mode.
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

    private val accounts = HomeUiState(
        permissions = ProjectPermissions(
            listOf(
                tool("timecard_tool", "Timecards", "group_accounts_payroll"),
                tool("payroll_tool", "Payroll", "group_accounts_payroll"),
                tool("purchase_order_tool", "Purchase Orders", "group_accounts_payroll"),
            ),
        ),
        groups = listOf(ToolGroup("group_accounts_payroll", "Accounts / Payroll", id = "g1", systemDefined = true)),
    )

    @Test
    fun `the header carries the title, the search and Customize order`() {
        runComposeUiTest {
            setContent { ZillitTheme { HomeScreen(state = accounts, onEvent = {}) } }

            onNodeWithText("Film Tools").assertExists()
            onNodeWithText("Customize order").assertExists()
            // Manage Tool Groups waits on the Remote Config switch.
            onAllNodesWithText("Manage Tool Groups").assertCountEquals(0)
        }
    }

    @Test
    fun `an admin is told tools are chosen, then granted - and nobody else is`() {
        runComposeUiTest {
            setContent {
                ZillitTheme {
                    HomeScreen(
                        state = accounts.copy(isAdmin = true),
                        onEvent = {},
                        onCustomiseTools = {},
                        onOpenPermissionGrid = {},
                    )
                }
            }
            onNodeWithText("Tools are selectable and permission-based.").assertExists()
            onAllNodesWithText("Click Here").assertCountEquals(2)
            onNodeWithText("to grant viewing and posting permissions for Tools.").assertExists()
        }
        runComposeUiTest {
            setContent {
                ZillitTheme {
                    HomeScreen(state = accounts, onEvent = {}, onCustomiseTools = {}, onOpenPermissionGrid = {})
                }
            }
            onAllNodesWithText("Click Here").assertCountEquals(0)
        }
    }

    @Test
    fun `a default group wears its subheading, and tiles their own names`() {
        runComposeUiTest {
            setContent { ZillitTheme { HomeScreen(state = accounts, onEvent = {}) } }

            onNodeWithText("Accounts / Payroll").assertExists()
            onNodeWithText("Accounting, expenses & reporting").assertExists()
            onNodeWithText("Timecards").assertExists()
            onNodeWithText("Payroll").assertExists()
        }
    }

    @Test
    fun `a badge past ninety-nine reads 99+, as the web caps it`() {
        runComposeUiTest {
            setContent {
                ZillitTheme { HomeScreen(state = accounts, onEvent = {}, toolBadges = mapOf("payroll_tool" to 120)) }
            }
            onNodeWithText("99+").assertExists()
            onAllNodesWithText("120").assertCountEquals(0)
        }
    }

    @Test
    fun `the info icon explains a tool, links to more, and Open goes into it`() {
        val events = mutableListOf<HomeEvent>()
        runComposeUiTest {
            setContent { ZillitTheme { HomeScreen(state = accounts, onEvent = { events += it }) } }

            onNodeWithContentDescription("About Purchase Orders").performClick()
            onNodeWithText("Raise purchase orders, upload invoices", substring = true).assertExists()
            onNodeWithText("More").assertExists()
            onNodeWithText("watch video").assertExists()

            onNodeWithText("Open").performClick()
            waitForIdle()
            assertTrue(events.any { it is HomeEvent.OpenTool }, "Open opened the tool")
        }
    }

    @Test
    fun `a personal production shows no info icon`() {
        runComposeUiTest {
            setContent { ZillitTheme { HomeScreen(state = accounts, onEvent = {}, isPersonalProject = true) } }
            onNodeWithText("Timecards").assertExists()
            assertTrue(
                runCatching { onNodeWithContentDescription("About Timecards").assertExists() }.isFailure,
                "no ⓘ on a personal production",
            )
        }
    }

    @Test
    fun `a search that matches nothing says so and offers to clear`() {
        runComposeUiTest {
            setContent { ZillitTheme { HomeScreen(state = accounts, onEvent = {}) } }

            onNode(hasSetTextAction()).performTextInput("zzz")
            waitForIdle()
            onNodeWithText("No tools match “zzz”").assertExists()
            onNodeWithText("Clear search").assertExists()
        }
    }

    @Test
    fun `organizing offers the hint, a new group, renames and an empty target`() {
        val organizing = accounts.copy(
            isAdmin = true,
            canOrganize = true,
            organizing = true,
            groups = accounts.groups + ToolGroup("custom_x", "Props", id = "g2"),
        )
        runComposeUiTest {
            setContent { ZillitTheme { HomeScreen(state = organizing, onEvent = {}) } }

            onNodeWithText("Done").assertExists()
            onNodeWithText("Drag a tool onto a group to move it. Click a heading to rename it.").assertExists()
            onNodeWithText("Add new group").assertExists()
            onAllNodesWithText("Edit Group name").assertCountEquals(2)
            // The custom group holds nothing yet, and stays as a drop target.
            onNodeWithText("Props").assertExists()
            onNodeWithText("Drag a tool here").assertExists()
            onNodeWithContentDescription("Delete").assertExists()
        }
    }
}
