package com.zillit.desktop.feature.accounthub

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.feature.accounthub.domain.AccountHubViewer
import com.zillit.desktop.feature.accounthub.domain.AgreementDocument
import com.zillit.desktop.feature.accounthub.domain.HubArea
import com.zillit.desktop.feature.accounthub.domain.HubNavigation
import com.zillit.desktop.feature.accounthub.domain.PurchaseOrderSetup
import com.zillit.desktop.feature.accounthub.ui.AccountHubScreen
import com.zillit.desktop.feature.accounthub.ui.AccountHubUiState
import com.zillit.desktop.feature.accounthub.ui.SectionEdit
import com.zillit.desktop.feature.accounthub.ui.SetupModal
import com.zillit.desktop.feature.accounthub.ui.SetupModalState
import com.zillit.desktop.feature.accounthub.ui.SetupState
import kotlin.test.Test

/**
 * The Purchase Order Entry Setup modal, section by section, against the web's
 * `POSetupDetail`: three sections, three formats, the split type always on
 * offer, and a terms document that is viewed or replaced, never removed.
 */
@OptIn(ExperimentalTestApi::class)
class PoSetupModalRenderTest {

    private val accountant = AccountHubViewer(
        userId = "u1",
        isAccountant = true,
        canView = true,
        canPost = true,
        canDownload = true,
        ready = true,
        viewableTools = setOf(AccountHubViewer.TOOL_IDENTIFIER),
    )

    private fun state(section: String, po: PurchaseOrderSetup = PurchaseOrderSetup()) = AccountHubUiState(
        viewer = accountant,
        sections = HubNavigation.visibleTo(accountant),
        area = HubArea.ProductionSetup,
        setup = SetupState(
            poSetup = SectionEdit(po),
            modal = SetupModalState(SetupModal.PurchaseOrders, section),
        ),
    )

    private val terms = AgreementDocument(name = "terms.pdf", media = "m", bucket = "b", region = "r")

    @Test
    fun `the modal has the web's three sections and offers its three formats`() = runComposeUiTest {
        setContent {
            ZillitTheme(darkTheme = false) {
                AccountHubScreen(
                    state = state("format"),
                    onEvent = {},
                    canAttachAgreements = true,
                    canOpenDocuments = true,
                )
            }
        }
        onAllNodesWithText("Description Format").onFirst().assertIsDisplayed()
        onNodeWithText("Rental & Split").assertIsDisplayed()
        onNodeWithText("Issuance").assertIsDisplayed()
        onNodeWithText("Form Configuration").assertDoesNotExist()
        onNodeWithText("Auto-Assignment Rules").assertDoesNotExist()
        onNodeWithText("DDMON → ITEM").assertIsDisplayed()
        onNodeWithText("DDMM → ITEM").assertIsDisplayed()
        onNodeWithText("ITEM → DDMON").assertIsDisplayed()
        onNodeWithText("e.g. 03MAR ALEXA MINI LF HIRE").assertIsDisplayed()
        onNodeWithText("Custom").assertDoesNotExist()
    }

    @Test
    fun `the split type is offered even while auto-split is off`() = runComposeUiTest {
        setContent {
            ZillitTheme(darkTheme = false) {
                AccountHubScreen(
                    state = state("rental", PurchaseOrderSetup(autoSplitRentals = false)),
                    onEvent = {},
                    canAttachAgreements = true,
                    canOpenDocuments = true,
                )
            }
        }
        onNodeWithText("Default split type").assertIsDisplayed()
        onNodeWithText("Four Week").assertIsDisplayed()
        onNodeWithText("Require effective date").assertIsDisplayed()
        onAllNodesWithText("Always").onFirst().assertIsDisplayed()
    }

    @Test
    fun `issuance shows the prefix and a terms document that is viewed or changed`() = runComposeUiTest {
        setContent {
            ZillitTheme(darkTheme = false) {
                AccountHubScreen(
                    state = state("issuance", PurchaseOrderSetup(numberPrefix = "QW", termsDocument = terms)),
                    onEvent = {},
                    canAttachAgreements = true,
                    canOpenDocuments = true,
                )
            }
        }
        onNodeWithText("PO number prefix").assertIsDisplayed()
        onNodeWithText(PurchaseOrderSetup.PREFIX_HINT).assertIsDisplayed()
        onNodeWithText("Terms and Conditions document").assertIsDisplayed()
        onNodeWithText("terms.pdf").assertIsDisplayed()
        onNodeWithText("Attached").assertIsDisplayed()
        onAllNodesWithText("View").onFirst().assertIsDisplayed()
        onNodeWithText("Change").assertIsDisplayed()
        onNodeWithText("PDF, DOC or DOCX · max 10MB").assertIsDisplayed()
        onNodeWithText("Remove").assertDoesNotExist()
        onNodeWithText("Upload PDF").assertDoesNotExist()
    }

    @Test
    fun `without a document the block offers Add attachment`() = runComposeUiTest {
        setContent {
            ZillitTheme(darkTheme = false) {
                AccountHubScreen(
                    state = state("issuance"),
                    onEvent = {},
                    canAttachAgreements = true,
                    canOpenDocuments = true,
                )
            }
        }
        onNodeWithText("Add attachment").assertIsDisplayed()
        onNodeWithText("Attached").assertDoesNotExist()
    }
}
