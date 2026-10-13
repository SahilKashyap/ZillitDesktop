package com.zillit.desktop.feature.dealmemo

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.feature.dealmemo.domain.ApprovalTier
import com.zillit.desktop.feature.dealmemo.domain.ApprovalTierConfig
import com.zillit.desktop.feature.dealmemo.domain.DealDoc
import com.zillit.desktop.feature.dealmemo.domain.DealMemoMetadata
import com.zillit.desktop.feature.dealmemo.ui.DealMemoEvent
import com.zillit.desktop.feature.dealmemo.ui.DealMemoScreen
import com.zillit.desktop.feature.dealmemo.ui.DealMemoUiState
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The two places Deal Memo says that nobody can approve: the deal page's "Set
 * Approval Level" strip and All Deals' "Approvers not configured" banner —
 * composed for real, and each button clicked.
 */
@OptIn(ExperimentalTestApi::class)
class ApproverPromptRenderTest {

    private val awaiting = DealDoc(
        JsonObject(DealPageFixtures.deal.json + ("status" to JsonPrimitive("awaiting_approval"))),
    )

    private fun page(metadata: DealMemoMetadata): DealMemoUiState {
        val base = DealPageFixtures.dealPage()
        return base.copy(metadata = metadata, preview = base.preview?.copy(deal = awaiting))
    }

    private val covered = DealMemoMetadata(
        loaded = true,
        approvalTierConfigs = listOf(ApprovalTierConfig("all", tiers = listOf(ApprovalTier(1, listOf(listOf("u1")))))),
    )

    private fun ComposeUiTest.show(state: DealMemoUiState, onEvent: (DealMemoEvent) -> Unit) =
        setContent { ZillitTheme { DealMemoScreen(state = state, onEvent = onEvent) } }

    @Test
    fun `a deal awaiting approval with no approver offers Set Approval Level, and the button asks for the page`() =
        runComposeUiTest {
            val events = mutableListOf<DealMemoEvent>()
            show(page(DealMemoMetadata(loaded = true)), events::add)

            onNodeWithText("No approver configured for this department").assertIsDisplayed()
            onNodeWithText("Set Approval Level →").performClick()
            assertEquals(listOf<DealMemoEvent>(DealMemoEvent.OpenApprovers), events)
        }

    @Test
    fun `the strip stays away once a level has an approver`() = runComposeUiTest {
        show(page(covered)) {}
        onNodeWithText("No approver configured for this department").assertDoesNotExist()
    }

    @Test
    fun `the strip stays away until the metadata has answered`() = runComposeUiTest {
        show(page(DealMemoMetadata(loaded = false))) {}
        onNodeWithText("No approver configured for this department").assertDoesNotExist()
    }

    @Test
    fun `All Deals warns posting users when a department has nobody to approve, and Set opens the page`() =
        runComposeUiTest {
            val events = mutableListOf<DealMemoEvent>()
            val state = DealMemoFixtures.allDeals().let { it.copy(deals = it.deals.copy(tierConfigs = emptyList())) }
            show(state, events::add)

            onNodeWithText("Approvers not configured").assertIsDisplayed()
            onNodeWithText("Set").performClick()
            assertTrue(events.contains(DealMemoEvent.OpenApprovers), "Set asks for Deal Memo's own Approvers page")
        }

    @Test
    fun `All Deals says nothing before the configs are read, and nothing when they cover every deal`() =
        runComposeUiTest {
            val covers = ApprovalTierConfig("all", tiers = listOf(ApprovalTier(1, listOf(listOf("u1")))))
            val state = DealMemoFixtures.allDeals().let { it.copy(deals = it.deals.copy(tierConfigs = listOf(covers))) }
            show(state) {}

            onNodeWithText("Approvers not configured").assertDoesNotExist()
        }
}
