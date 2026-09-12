package com.zillit.desktop.feature.accounthub

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.feature.accounthub.domain.AccountHubViewer
import com.zillit.desktop.feature.accounthub.domain.ApprovalConfig
import com.zillit.desktop.feature.accounthub.domain.ApprovalModule
import com.zillit.desktop.feature.accounthub.domain.ApprovalRule
import com.zillit.desktop.feature.accounthub.domain.ApprovalScope
import com.zillit.desktop.feature.accounthub.domain.ApprovalTier
import com.zillit.desktop.feature.accounthub.domain.HubArea
import com.zillit.desktop.feature.accounthub.domain.HubDepartment
import com.zillit.desktop.feature.accounthub.domain.HubNavigation
import com.zillit.desktop.feature.accounthub.domain.HubUser
import com.zillit.desktop.feature.accounthub.ui.AccountHubEvent
import com.zillit.desktop.feature.accounthub.ui.AccountHubScreen
import com.zillit.desktop.feature.accounthub.ui.AccountHubUiState
import com.zillit.desktop.feature.accounthub.ui.ApprovalBuilder
import com.zillit.desktop.feature.accounthub.ui.ApprovalsState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Approvers page and its level editor, composed for real.
 *
 * The editor packs a select, an amount field, buttons and chips into a
 * wrapping row inside a scrolling column, and the picker is a dialog with its
 * own scrolling list — the arrangements that fail at composition rather than
 * in review. Each control is also clicked, so an event wired to the wrong
 * level or rule fails here rather than on a production's chain.
 */
@OptIn(ExperimentalTestApi::class)
class ApproversRenderTest {

    private val viewer = AccountHubViewer(
        userId = "u1",
        isAccountant = true,
        canView = true,
        canPost = true,
        canDownload = true,
        ready = true,
        viewableTools = setOf(AccountHubViewer.TOOL_IDENTIFIER, "purchase_order_tool"),
    )

    private val users = listOf(
        HubUser("u1", "Asha Rao", departmentIdentifier = "department_accounts", designation = "Production Accountant"),
        HubUser("u2", "Ben Cole", departmentIdentifier = "department_accounts", designation = "Financial Controller"),
        HubUser("u3", "Cara Diaz", department = "Camera", designation = "Director of Photography"),
        HubUser("u4", "Dev Patel", department = "Art", designation = "Production Designer"),
    )

    private val camera = ApprovalConfig(
        id = "cfg-cam",
        scope = ApprovalScope.Department,
        departmentId = "d1",
        departmentName = "Camera",
        tiers = listOf(
            ApprovalTier(
                1,
                listOf(
                    ApprovalRule(ApprovalRule.DEFAULT, listOf("u3")),
                    ApprovalRule(ApprovalRule.AMOUNT, listOf("u1"), 10_000.0),
                ),
            ),
            ApprovalTier(2, listOf(ApprovalRule())),
        ),
    )

    private fun state(builder: ApprovalBuilder? = null) = AccountHubUiState(
        viewer = viewer,
        sections = HubNavigation.visibleTo(viewer),
        area = HubArea.Approvers,
        users = users,
        departmentList = listOf(HubDepartment("d0", "Art"), HubDepartment("d1", "Camera")),
        approvals = ApprovalsState(
            module = ApprovalModule.PurchaseOrders,
            loadedModule = ApprovalModule.PurchaseOrders,
            configs = listOf(
                ApprovalConfig(
                    id = "cfg-all",
                    scope = ApprovalScope.All,
                    tiers = listOf(ApprovalTier(1, listOf(ApprovalRule(ApprovalRule.DEFAULT, listOf("u2"))))),
                ),
                camera,
            ),
            candidateIds = setOf("u3", "u4"),
            builder = builder,
        ),
    )

    @Test
    fun `the page shows the default chain with its people and every department`() {
        runComposeUiTest {
            setContent {
                ZillitTheme(darkTheme = false) { AccountHubScreen(state = state(), onEvent = {}) }
            }
            onNodeWithText("Approver configuration").assertIsDisplayed()
            onNodeWithText("Baseline used by any department without a custom override below.")
                .performScrollTo()
                .assertIsDisplayed()
            onAllNodesWithText("Ben Cole").onFirst().performScrollTo().assertIsDisplayed()
            onNodeWithText("Custom").performScrollTo().assertIsDisplayed()
        }
    }

    /** A failed read replaces the chains with its reason and a Retry, never with "Not configured". */
    @Test
    fun `a failed read offers a retry instead of empty chains`() {
        val events = mutableListOf<AccountHubEvent>()
        val failed = state().let { base ->
            base.copy(
                approvals = base.approvals.copy(
                    loadedModule = null,
                    configs = emptyList(),
                    loadError = "Approval tiers are unavailable.",
                ),
            )
        }
        runComposeUiTest {
            setContent {
                ZillitTheme(darkTheme = false) { AccountHubScreen(state = failed, onEvent = { events += it }) }
            }
            onNodeWithText("Approval tiers are unavailable.").performScrollTo().assertIsDisplayed()
            onAllNodesWithText("Default Approval Levels").assertCountEquals(0)
            onNodeWithText("Retry").performScrollTo().performClick()
        }
        assertEquals(listOf<AccountHubEvent>(AccountHubEvent.ReloadApprovalConfigs), events)
    }

    @Test
    fun `the editor addresses the level and rule each control belongs to`() {
        val events = mutableListOf<AccountHubEvent>()
        runComposeUiTest {
            setContent {
                ZillitTheme(darkTheme = false) {
                    AccountHubScreen(state = state(ApprovalBuilder(camera, camera)), onEvent = { events += it })
                }
            }
            onNodeWithText("Camera — Approval Levels").assertIsDisplayed()
            onNodeWithText("Applicable for all currencies. No exchange rates applied.").assertIsDisplayed()
            // A rail above, between and below two levels.
            onAllNodesWithContentDescription("Insert level here").assertCountEquals(3)

            onNodeWithContentDescription("Remove Asha Rao").performScrollTo().performClick()
            onAllNodesWithText("Add Users")[1].performScrollTo().performClick()
            onAllNodesWithText("Add more")[1].performScrollTo().performClick()
            onAllNodesWithContentDescription("Remove this rule").onFirst().performScrollTo().performClick()
            onAllNodesWithContentDescription("Insert level here")[2].performScrollTo().performClick()
            onNodeWithText("Save changes").performClick()
        }
        assertEquals(
            listOf(
                AccountHubEvent.RemoveApprover(tier = 1, rule = 1, userId = "u1"),
                AccountHubEvent.OpenApproverPicker(tier = 1, rule = 1),
                AccountHubEvent.AddApprovalRule(tier = 2),
                AccountHubEvent.RemoveApprovalRule(tier = 1, rule = 0),
                AccountHubEvent.InsertApprovalLevel(position = 2),
                AccountHubEvent.SaveApprovalConfig,
            ),
            events,
        )
    }

    /**
     * Everyone already on the level is Added and cannot be ticked; ticking
     * someone else only stages them, and the button counts who is staged.
     */
    @Test
    fun `the picker stages people and refuses anyone already on the level`() {
        val events = mutableListOf<AccountHubEvent>()
        val picking = ApprovalBuilder(camera, camera, pickerTier = 1, pickerRule = 1, picked = listOf("u4"))
        runComposeUiTest {
            setContent {
                ZillitTheme(darkTheme = false) {
                    AccountHubScreen(state = state(picking), onEvent = { events += it })
                }
            }
            onNodeWithText("Add Approvers — Level 1").assertIsDisplayed()
            // Asha (amount rule) and Cara (default rule) are both on level 1.
            onAllNodesWithText("Added").assertCountEquals(2)
            onNodeWithText("Add 1 user").assertIsEnabled()

            // The last "Asha Rao" is the picker's row; the first is her chip behind the dialog.
            onAllNodesWithText("Asha Rao").onLast().performClick()
            onNodeWithText("Ben Cole").performClick()
            onNodeWithText("Add 1 user").performClick()
        }
        assertTrue(AccountHubEvent.ToggleApproverPick("u1") !in events, "an Added row does not toggle")
        assertEquals(
            listOf(AccountHubEvent.ToggleApproverPick("u2"), AccountHubEvent.AddPickedApprovers),
            events.filter { it is AccountHubEvent.ToggleApproverPick || it == AccountHubEvent.AddPickedApprovers },
        )
    }
}
