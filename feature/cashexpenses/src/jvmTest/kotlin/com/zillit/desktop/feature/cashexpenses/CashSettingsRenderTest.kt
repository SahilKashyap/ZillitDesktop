package com.zillit.desktop.feature.cashexpenses

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.feature.cashexpenses.domain.AssigneeOption
import com.zillit.desktop.feature.cashexpenses.domain.CashAssignmentRule
import com.zillit.desktop.feature.cashexpenses.domain.CashDepartment
import com.zillit.desktop.feature.cashexpenses.domain.CashMetadata
import com.zillit.desktop.feature.cashexpenses.domain.CashSettings
import com.zillit.desktop.feature.cashexpenses.domain.CashTeamMember
import com.zillit.desktop.feature.cashexpenses.domain.CashViewer
import com.zillit.desktop.feature.cashexpenses.domain.DeductionRule
import com.zillit.desktop.feature.cashexpenses.domain.DeductionRuleEdit
import com.zillit.desktop.feature.cashexpenses.domain.DepartmentCoordinator
import com.zillit.desktop.feature.cashexpenses.domain.QuickCode
import com.zillit.desktop.feature.cashexpenses.domain.RuleProcess
import com.zillit.desktop.feature.cashexpenses.domain.RuleThreshold
import com.zillit.desktop.feature.cashexpenses.ui.CashDestination
import com.zillit.desktop.feature.cashexpenses.ui.CashExpensesScreen
import com.zillit.desktop.feature.cashexpenses.ui.CashUiState
import com.zillit.desktop.feature.cashexpenses.ui.CoordinatorPicker
import com.zillit.desktop.feature.cashexpenses.ui.SettingsUiState
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The Settings page composes every web section, in the web's order and words,
 * and its two dialogs draw over it.
 */
@OptIn(ExperimentalTestApi::class)
class CashSettingsRenderTest {

    private val senior = CashViewer(
        userId = "me",
        departmentIdentifier = "department_accounts",
        designationIdentifier = "designation_production_accountant_accounts",
        metadata = CashMetadata(isSenior = true),
    )

    private val settings = CashSettings(
        custodianAccount = "1200",
        teamMembers = listOf(
            CashTeamMember(
                userId = "u1",
                name = "Ada Lovelace",
                isSenior = false,
                canOverride = true,
                postingLimit = 0.0,
            ),
        ),
        departmentCoordinators = listOf(DepartmentCoordinator("d-cam", listOf("u2"), codingRequired = true)),
        deductionRules = listOf(
            DeductionRule(
                id = "fuel_deduction",
                title = "Fuel Deduction",
                thresholdValue = 20.0,
                triggerCodes = listOf("diesel"),
                systemDefault = true,
            ),
        ),
        quickCodes = listOf(QuickCode(name = "Fuel", nominalCode = "2400", keywords = listOf("fuel"), vat = 20.0)),
        assignmentRules = listOf(CashAssignmentRule(id = "r1", nominalCodes = listOf("2400"), persisted = true)),
    )

    private fun state(ui: SettingsUiState = SettingsUiState()) = CashUiState(
        viewer = senior,
        destination = CashDestination.Settings,
        settings = settings,
        settingsDraft = settings,
        departments = listOf(CashDepartment("d-cam", "Camera")),
        assignees = listOf(
            AssigneeOption(userId = "u1", fullName = "Ada Lovelace", department = "Accounts"),
            AssigneeOption(userId = "u2", fullName = "Grace Hopper", department = "Camera", departmentId = "d-cam"),
        ),
        settingsUi = ui,
    )

    @Test
    fun `every web section is on the page`() = runComposeUiTest {
        setContent { ZillitTheme(darkTheme = false) { CashExpensesScreen(state = state(), onEvent = {}) } }
        listOf(
            "Petty Cash Settings",
            "Float Accounts & Custodian",
            "Team & Posting Rights",
            "Department Coordinator Designations",
            "Approval & Override Settings",
            "Request Cap",
            "Reimbursement Settlement",
            "Deduction & Processing Rules",
            "Quick Codes — Auto-mapping",
            "Auto-Assignment Rules",
            // The rows' own words.
            "No access",
            "Submit to Senior",
            "Camera",
            "Grace Hopper",
            "Accountant Override — Float Requests",
            "Senior Sign-off Required",
            "Fuel Deduction",
            "20%",
            "AMOUNT MAX (£)",
        ).forEach { text ->
            assertTrue(
                onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty(),
                "missing: $text",
            )
        }
        // Nothing is dirty, so no section offers a Save.
        onAllNodesWithText("Save").assertCountEquals(0)
    }

    @Test
    fun `the coordinator picker offers the department's people`() = runComposeUiTest {
        val ui = SettingsUiState(coordPicker = CoordinatorPicker(row = 0, users = true, userIds = listOf("u2")))
        setContent { ZillitTheme(darkTheme = false) { CashExpensesScreen(state = state(ui), onEvent = {}) } }
        onNodeWithText("Select Users").assertExists()
        onNodeWithText("1 selected").assertExists()
        onNodeWithText("Done").assertExists()
    }

    @Test
    fun `the rule dialog reads a minimum for a review rule`() = runComposeUiTest {
        val edit = DeductionRuleEdit(
            DeductionRule(id = "custom_1", title = "Late travel", processType = RuleProcess.SeniorReview,
                thresholdType = RuleThreshold.MinAmount),
            index = null,
        )
        setContent {
            ZillitTheme(darkTheme = false) {
                CashExpensesScreen(state = state(SettingsUiState(ruleEditor = edit)), onEvent = {})
            }
        }
        onNodeWithText("THRESHOLD").assertExists()
        onNodeWithText("Min amount").assertExists()
        onNodeWithText("ADD FROM QUICK CODES").assertExists()
    }
}
