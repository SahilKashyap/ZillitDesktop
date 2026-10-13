package com.zillit.desktop.feature.accounthub

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
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
import com.zillit.desktop.feature.accounthub.domain.HubUser
import com.zillit.desktop.feature.accounthub.ui.AccountHubEvent
import com.zillit.desktop.feature.accounthub.ui.AccountHubUiState
import com.zillit.desktop.feature.accounthub.ui.ApprovalsState
import com.zillit.desktop.feature.accounthub.ui.pages.ApproversPage
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The Approvers page as Deal Memo hosts it: the rail gone, the Deal Memo chain
 * only, a way back, and Edit offered to the people the host vouches for.
 */
@OptIn(ExperimentalTestApi::class)
class DealMemoApproversRenderTest {

    private fun viewer(hostEdit: Boolean) = AccountHubViewer(
        userId = "u2",
        isAccountant = false,
        canView = false,
        canPost = true,
        ready = true,
        approvalsHostEdit = hostEdit,
    )

    private fun state(hostEdit: Boolean) = AccountHubUiState(
        viewer = viewer(hostEdit),
        area = HubArea.Approvers,
        users = listOf(HubUser("u1", "Asha Rao", departmentIdentifier = "department_accounts")),
        approvals = ApprovalsState(
            module = ApprovalModule.DealMemo,
            loadedModule = ApprovalModule.DealMemo,
            configs = listOf(
                ApprovalConfig(
                    id = "cfg-all",
                    module = ApprovalModule.DealMemo,
                    scope = ApprovalScope.All,
                    tiers = listOf(ApprovalTier(1, listOf(ApprovalRule(ApprovalRule.DEFAULT, listOf("u1"))))),
                ),
            ),
        ),
    )

    @Test
    fun `hosted, the page has no module rail and leads with a way back`() = runComposeUiTest {
        var backs = 0
        setContent {
            ZillitTheme(darkTheme = false) { ApproversPage(state(hostEdit = true), onEvent = {}, onBack = { backs++ }) }
        }
        onNodeWithText("Modules").assertDoesNotExist()
        onNodeWithText("Purchase Orders").assertDoesNotExist()
        onNodeWithText("DEAL MEMO").assertIsDisplayed()
        onNodeWithContentDescription("Back").performClick()
        onNodeWithText("DEAL MEMO").performClick()
        assertEquals(2, backs)
    }

    @Test
    fun `a vouched-for posting user is offered Edit on the default chain`() {
        val events = mutableListOf<AccountHubEvent>()
        runComposeUiTest {
            setContent {
                ZillitTheme(darkTheme = false) {
                    ApproversPage(state(hostEdit = true), onEvent = { events += it }, onBack = {})
                }
            }
            onNodeWithText("Edit").performScrollTo().performClick()
        }
        assertEquals(listOf<AccountHubEvent>(AccountHubEvent.EditDefaultApprovals), events)
    }

    @Test
    fun `without the host's word the same viewer sees the chain read-only`() = runComposeUiTest {
        setContent {
            ZillitTheme(darkTheme = false) { ApproversPage(state(hostEdit = false), onEvent = {}, onBack = {}) }
        }
        onNodeWithText("Edit").assertDoesNotExist()
    }

    @Test
    fun `the console's page keeps its rail`() = runComposeUiTest {
        setContent {
            ZillitTheme(darkTheme = false) { ApproversPage(state(hostEdit = false), onEvent = {}) }
        }
        onNodeWithText("Modules").assertIsDisplayed()
    }
}
