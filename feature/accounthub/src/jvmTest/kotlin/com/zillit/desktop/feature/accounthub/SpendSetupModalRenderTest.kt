package com.zillit.desktop.feature.accounthub

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.feature.accounthub.domain.AccountHubViewer
import com.zillit.desktop.feature.accounthub.domain.BankAccount
import com.zillit.desktop.feature.accounthub.domain.CardProvider
import com.zillit.desktop.feature.accounthub.domain.Company
import com.zillit.desktop.feature.accounthub.domain.HubArea
import com.zillit.desktop.feature.accounthub.domain.HubDepartment
import com.zillit.desktop.feature.accounthub.domain.HubNavigation
import com.zillit.desktop.feature.accounthub.domain.HubUser
import com.zillit.desktop.feature.accounthub.domain.SpendCoordinator
import com.zillit.desktop.feature.accounthub.domain.SpendKind
import com.zillit.desktop.feature.accounthub.domain.SpendSettings
import com.zillit.desktop.feature.accounthub.domain.SpendTeamMember
import com.zillit.desktop.feature.accounthub.ui.AccountHubScreen
import com.zillit.desktop.feature.accounthub.ui.AccountHubUiState
import com.zillit.desktop.feature.accounthub.ui.SectionEdit
import com.zillit.desktop.feature.accounthub.ui.SetupModal
import com.zillit.desktop.feature.accounthub.ui.SetupModalState
import com.zillit.desktop.feature.accounthub.ui.SetupState
import com.zillit.desktop.feature.accounthub.ui.SpendDraft
import kotlin.test.Test

/**
 * The Card and Petty Cash Entry Setup modals composed, section by section,
 * against the web's `SharedSpendDetail`: seven sections, the card providers
 * against the cash custodian and range, and the two dialogs.
 */
@OptIn(ExperimentalTestApi::class)
class SpendSetupModalRenderTest {

    private val accountant = AccountHubViewer(
        userId = "u1",
        isAccountant = true,
        canView = true,
        canPost = true,
        canDownload = true,
        ready = true,
        viewableTools = setOf(AccountHubViewer.TOOL_IDENTIFIER),
    )

    private val users = listOf(
        HubUser(id = "u1", name = "Ann Accountant", designation = "Production Accountant", department = "Accounts",
            departmentIdentifier = "department_accounts", departmentId = "d-acc"),
        HubUser(id = "u2", name = "Ben Camera", designation = "Focus Puller", department = "Camera",
            departmentIdentifier = "department_camera", departmentId = "d1"),
    )

    private fun cardDoc() = SpendSettings(
        kind = SpendKind.Cards,
        team = listOf(
            SpendTeamMember("u1", postingLimit = null, canOverride = true, isSenior = true),
            SpendTeamMember("u2", postingLimit = 250.0),
        ),
        coordinators = listOf(SpendCoordinator("d1", listOf("u2"), codingRequired = true)),
        approval = mapOf("override_card_req" to true),
        providers = listOf(
            CardProvider(
                "p1",
                name = "Barclays Production",
                bankId = "b1",
                companyId = "co-1",
                custodianAccount = "1200",
            ),
        ),
    )

    private fun cashDoc() = SpendSettings(
        kind = SpendKind.Cash,
        custodianAccount = "1100",
        bsCodeFrom = "1000",
        bsCodeTo = "1999",
        coordinators = listOf(SpendCoordinator("d1", listOf("u2"), viewDepartmentFloats = true)),
    )

    private fun state(
        modal: SetupModal,
        section: String,
        doc: SpendSettings,
        draft: SpendDraft? = null,
    ) = AccountHubUiState(
        viewer = accountant,
        sections = HubNavigation.visibleTo(accountant),
        area = HubArea.ProductionSetup,
        users = users,
        departmentList = listOf(HubDepartment("d1", "Camera", "department_camera")),
        setup = SetupState(
            companies = SectionEdit(listOf(Company(id = "co-1", name = "Zillit Films", bankIds = listOf("b1")))),
            banks = listOf(BankAccount(id = "b1", name = "Barclays", currencyCode = "GBP", accountNumber = "20481234")),
            spendSetup = SectionEdit(doc),
            spendDraft = draft,
            modal = SetupModalState(modal, section),
        ),
    )

    private fun androidx.compose.ui.test.ComposeUiTest.show(state: AccountHubUiState) = setContent {
        ZillitTheme(darkTheme = false) {
            AccountHubScreen(state = state, onEvent = {}, canAttachAgreements = true, canOpenDocuments = true)
        }
    }

    @Test
    fun `the card modal lists the web's seven sections`() = runComposeUiTest {
        show(state(SetupModal.CardExpenses, "acct", cardDoc()))
        // The page's own tile carries the same words; the modal is composed last.
        onAllNodesWithText("Production Expense Cards Entry Setup").onLast().assertIsDisplayed()
        onAllNodesWithText("Accounts & Custodian").onFirst().assertIsDisplayed()
        onNodeWithText("Team & Posting Rights").assertIsDisplayed()
        onNodeWithText("Department Coordinators").assertIsDisplayed()
        onNodeWithText("Approval Overrides").assertIsDisplayed()
        onNodeWithText("Deduction Rules").assertIsDisplayed()
        onNodeWithText("Quick Codes").assertIsDisplayed()
        onNodeWithText("Auto-Assignment Rules").assertIsDisplayed()
    }

    @Test
    fun `the card accounts pane is the providers editor, with no global custodian`() = runComposeUiTest {
        show(state(SetupModal.CardExpenses, "acct", cardDoc()))
        onAllNodesWithText("Card Providers").onLast().assertIsDisplayed()
        onNodeWithText("Barclays Production").assertIsDisplayed()
        onNodeWithText("Owns this bank (Production Setup → Companies).").assertIsDisplayed()
        onNodeWithText("Card Float Account from").assertIsDisplayed()
        onNodeWithText("Float custodian account").assertDoesNotExist()
    }

    @Test
    fun `the cash accounts pane is the custodian and the BS range`() = runComposeUiTest {
        show(state(SetupModal.PettyCash, "acct", cashDoc()))
        onAllNodesWithText("Petty Cash Entry Setup").onLast().assertIsDisplayed()
        onNodeWithText("Float custodian account").assertIsDisplayed()
        onNodeWithText("BS Code From").assertIsDisplayed()
        onNodeWithText("BS Code To").assertIsDisplayed()
        onAllNodesWithText("Card Providers").assertCountEquals(0)
    }

    @Test
    fun `the team pane shows each member's ceiling`() = runComposeUiTest {
        show(state(SetupModal.CardExpenses, "team", cardDoc()))
        onNodeWithText("Ann Accountant").assertIsDisplayed()
        onNodeWithText("Unlimited").assertIsDisplayed()
        onNodeWithText("250", substring = true).assertIsDisplayed()
        onNodeWithText("Senior").assertIsDisplayed()
    }

    @Test
    fun `the cash coordinators carry a view-floats column and the card ones do not`() = runComposeUiTest {
        show(state(SetupModal.PettyCash, "coord", cashDoc()))
        onNodeWithText("VIEW FLOATS").assertIsDisplayed()
    }

    @Test
    fun `the card coordinators have no view-floats column`() = runComposeUiTest {
        show(state(SetupModal.CardExpenses, "coord", cardDoc()))
        onNodeWithText("CODING").assertIsDisplayed()
        onAllNodesWithText("VIEW FLOATS").assertCountEquals(0)
    }

    @Test
    fun `each module has its own approval switches`() = runComposeUiTest {
        show(state(SetupModal.CardExpenses, "appr", cardDoc()))
        onNodeWithText("Accountant Override — Card Requests").assertIsDisplayed()
        onAllNodesWithText("Senior Sign-off Required").assertCountEquals(0)
    }

    @Test
    fun `the cash approvals include the senior sign-off`() = runComposeUiTest {
        show(state(SetupModal.PettyCash, "appr", cashDoc()))
        onNodeWithText("Accountant Override — Float Requests").assertIsDisplayed()
        onNodeWithText("Senior Sign-off Required").assertIsDisplayed()
    }

    @Test
    fun `a system deduction rule is tagged and cannot be removed`() = runComposeUiTest {
        show(state(SetupModal.CardExpenses, "rules", cardDoc()))
        onNodeWithText("Fuel — Personal Use Deduction").assertIsDisplayed()
        onAllNodesWithText("System").onFirst().assertIsDisplayed()
        onAllNodesWithText("DEDUCT").onFirst().assertIsDisplayed()
        onNodeWithText("20%").assertIsDisplayed()
    }

    @Test
    fun `quick codes are a table of label, nominal, keywords and rate`() = runComposeUiTest {
        show(state(SetupModal.CardExpenses, "codes", cardDoc()))
        onNodeWithText("KEYWORDS").assertIsDisplayed()
        onNodeWithText("DEDUCTION PERCENTAGE").assertIsDisplayed()
        onNodeWithText("Parking").assertIsDisplayed()
    }

    @Test
    fun `the team-member dialog opens over the modal`() = runComposeUiTest {
        val draft = SpendDraft.Member(null, SpendTeamMember())
        show(state(SetupModal.CardExpenses, "team", cardDoc(), draft))
        onNodeWithText("Add Team Member").assertIsDisplayed()
        onNodeWithText("Is senior").assertIsDisplayed()
        onNodeWithText("Can override").assertIsDisplayed()
    }

    @Test
    fun `the rule dialog locks a system rule's title`() = runComposeUiTest {
        val rule = cardDoc().deductionRules.first()
        show(state(SetupModal.CardExpenses, "rules", cardDoc(), SpendDraft.Rule(0, rule, "20")))
        onNodeWithText("Edit Rule (System)").assertIsDisplayed()
        onNodeWithText("Trigger codes").assertIsDisplayed()
        onNodeWithText("From Quick Codes").assertIsDisplayed()
    }
}
