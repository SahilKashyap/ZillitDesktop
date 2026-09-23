package com.zillit.desktop.feature.purchaseorder

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.feature.purchaseorder.domain.AssetFilters
import com.zillit.desktop.feature.purchaseorder.domain.PoAssignmentRule
import com.zillit.desktop.feature.purchaseorder.domain.PoAttachment
import com.zillit.desktop.feature.purchaseorder.domain.PoDepartment
import com.zillit.desktop.feature.purchaseorder.domain.PoSettings
import com.zillit.desktop.feature.purchaseorder.domain.PoTeamMember
import com.zillit.desktop.feature.purchaseorder.domain.PoViewer
import com.zillit.desktop.feature.purchaseorder.domain.Vendor
import com.zillit.desktop.feature.purchaseorder.ui.PoDestination
import com.zillit.desktop.feature.purchaseorder.ui.PoSettingsState
import com.zillit.desktop.feature.purchaseorder.ui.PoUiState
import com.zillit.desktop.feature.purchaseorder.ui.PurchaseOrderScreen
import kotlin.test.Test

/**
 * The Settings tab composes with every card filled — the web's `POSettings`
 * copy, a stored rule, a terms document and an asset rule — and with nothing.
 */
@OptIn(ExperimentalTestApi::class)
class PoSettingsRenderTest {

    private val senior = PoViewer(
        userId = "u1",
        departmentIdentifier = "department_accounts",
        designationIdentifier = "designation_production_accountant_accounts",
    )

    private val filled = PoSettingsState(
        saved = PoSettings(numberPrefix = "QW"),
        edited = PoSettings(
            numberPrefix = "QW",
            termsDocument = PoAttachment(
                media = "po-terms/x/terms.pdf",
                name = "terms.pdf",
                bucket = "b",
                region = "r",
            ),
            assetFilters = AssetFilters(priceLow = "100", tags = listOf("Camera")),
        ),
        rules = listOf(
            PoAssignmentRule(
                id = "r1",
                departments = listOf("d1"),
                amountMin = "500",
                assignTo = "u1",
                persisted = true,
            ),
        ),
        savedRules = emptyList(),
        canAttachTerms = true,
        team = listOf(PoTeamMember("u1", "Jane Smith", "Production Accountant")),
        departments = listOf(PoDepartment("d1", "Camera")),
        tags = listOf("Camera", "Grip"),
    )

    private fun state(settings: PoSettingsState) = PoUiState(
        viewer = senior,
        destination = PoDestination.Settings,
        vendors = listOf(Vendor("v-1", "Panavision", "GBP", "4100")),
        settings = settings,
    )

    @Test
    fun `every card renders with the web's copy`() = runComposeUiTest {
        setContent { ZillitTheme(darkTheme = false) { PurchaseOrderScreen(state = state(filled), onEvent = {}) } }
        onNodeWithText("PO Issuance").assertIsDisplayed()
        onNodeWithText("Saves on upload").assertIsDisplayed()
        onNodeWithText("terms.pdf").assertIsDisplayed()
        onNodeWithText("Description Formatting").assertExists()
        onNodeWithText("DDMON → ITEM").assertExists()
        onNodeWithText("Rental & Split Settings").assertExists()
        onNodeWithText("Asset Register Rules").assertExists()
        onNodeWithText("Lines on a posted or closed PO matching total ≥ £100, and tagged Camera.").assertExists()
        onNodeWithText("Form configuration").assertExists()
        onNodeWithText("Auto-Assignment Rules").assertExists()
        onNodeWithText("Add rule").assertExists()
        // The rules and asset cards are dirty, so each offers its own Save.
        onAllNodesWithText("Save").assertCountEquals(2)
        onNodeWithText("Jane Smith (Production Accountant)").assertExists()
        onNodeWithContentDescription("Remove rule").assertExists()
    }

    @Test
    fun `an empty tab offers the first rule and the first document`() = runComposeUiTest {
        setContent {
            ZillitTheme(darkTheme = true) {
                PurchaseOrderScreen(state = state(PoSettingsState(canAttachTerms = true)), onEvent = {})
            }
        }
        onNodeWithText("Add attachment").assertIsDisplayed()
        onNodeWithText("No rules configured. Click \"Add Rule\" to auto-assign POs.").assertExists()
        onNodeWithText("Every line item on a posted or closed PO — no constraint set.").assertExists()
    }

    @Test
    fun `while loading only the banner and the spinner show`() = runComposeUiTest {
        setContent {
            ZillitTheme(darkTheme = false) {
                PurchaseOrderScreen(state = state(PoSettingsState(loading = true)), onEvent = {})
            }
        }
        onNodeWithText("PO Issuance").assertDoesNotExist()
    }
}
