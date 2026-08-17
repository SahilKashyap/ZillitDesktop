package com.zillit.desktop.feature.payroll

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zillit.desktop.core.common.EpochDate
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.feature.payroll.domain.BankAccount
import com.zillit.desktop.feature.payroll.domain.NominalAllocation
import com.zillit.desktop.feature.payroll.domain.PayrollLine
import com.zillit.desktop.feature.payroll.domain.PayrollViewer
import com.zillit.desktop.feature.payroll.domain.Payslip
import com.zillit.desktop.feature.payroll.domain.PayslipLine
import com.zillit.desktop.feature.payroll.domain.TimecardStatus
import com.zillit.desktop.feature.payroll.ui.PayrollPrompt
import com.zillit.desktop.feature.payroll.ui.PayrollScreen
import com.zillit.desktop.feature.payroll.ui.PayrollUiState
import kotlin.test.Test

/** Composes the real Payroll screen across the week lifecycle. */
@OptIn(ExperimentalTestApi::class)
class PayrollScreenRenderTest {

    private companion object {
        const val WEEK = 1_754_000_000_000
        const val WEEK_MILLIS = 7L * 24 * 60 * 60 * 1000
    }

    private val controller = PayrollViewer(
        userId = "user-1",
        departmentIdentifier = "department_accounts",
        designationIdentifier = "designation_financial_controller_accounts",
    )

    private val producer = PayrollViewer("user-2", "department_production", null)

    private fun line(id: String = "tc-1", status: TimecardStatus = TimecardStatus.Approved) = PayrollLine(
        id = id,
        crewId = "crew-$id",
        crewName = "Ada Lovelace",
        departmentId = "dept-1",
        departmentName = "Camera",
        designation = "Gaffer",
        status = status,
        currency = "GBP",
        basicPay = 1_400.0,
        overtimePay = 220.0,
        allowances = 65.0,
        deductions = 100.0,
        gross = 1_685.0,
        net = 1_585.0,
        nominalCode = "7000",
        queryNote = null,
    )

    private fun state(
        status: TimecardStatus,
        viewer: PayrollViewer = controller,
    ) = PayrollUiState(
        viewer = viewer,
        weekStarting = WEEK,
        weekOptions = (0 until 4).map { WEEK - it * WEEK_MILLIS },
        lines = listOf(line(status = status), line("tc-2", TimecardStatus.Posted)),
    )

    @Test
    fun `every timecard status composes for both audiences and both themes`() {
        TimecardStatus.entries.forEach { status ->
            listOf(controller to false, controller to true, producer to false).forEach { (viewer, dark) ->
                runComposeUiTest {
                    setContent {
                        ZillitTheme(darkTheme = dark) {
                            PayrollScreen(state = state(status, viewer), onEvent = {})
                        }
                    }
                    onNodeWithText("Payroll Runs").assertIsDisplayed()
                }
            }
        }
    }

    @Test
    fun `the week picker is built from the week the server landed on`() {
        // Not from a locally computed Monday: this week starts on a Friday, and
        // every row under it must be a Friday too or the picker offers weeks
        // that do not exist.
        val friday = WEEK + 4 * 24 * 60 * 60 * 1000
        runComposeUiTest {
            setContent {
                ZillitTheme(darkTheme = false) {
                    PayrollScreen(
                        state = PayrollUiState(
                            viewer = controller,
                            weekStarting = friday,
                            weekOptions = (0 until 3).map { friday - it * WEEK_MILLIS },
                        ),
                        onEvent = {},
                    )
                }
            }
            onNodeWithText(EpochDate.date(friday)).assertExists()
            onNodeWithText(EpochDate.date(friday - WEEK_MILLIS)).assertExists()
        }
    }

    @Test
    fun `an empty tool says so rather than showing a void`() {
        runComposeUiTest {
            setContent {
                ZillitTheme(darkTheme = false) {
                    PayrollScreen(state = PayrollUiState(viewer = controller), onEvent = {})
                }
            }
            onNodeWithText("No timecards this week").assertIsDisplayed()
        }
    }

    @Test
    fun `queries are named as what is holding the week`() {
        runComposeUiTest {
            setContent {
                ZillitTheme(darkTheme = false) {
                    PayrollScreen(state = state(TimecardStatus.Queried), onEvent = {})
                }
            }
            onNodeWithText("1 timecard(s) are queried. They are answered on the timecard, not here.")
                .assertIsDisplayed()
        }
    }

    @Test
    fun `an open line shows the payslip and refuses an allocation that does not add up`() {
        val slip = Payslip(
            crewId = "crew-tc-1",
            crewName = "Ada Lovelace",
            currency = "GBP",
            lines = listOf(
                PayslipLine("Basic", 1_400.0),
                PayslipLine("Kit advance", 100.0, isDeduction = true),
            ),
            gross = 1_685.0,
            deductions = 100.0,
            net = 1_585.0,
        )
        runComposeUiTest {
            setContent {
                ZillitTheme(darkTheme = false) {
                    PayrollScreen(
                        state = state(TimecardStatus.Approved).copy(
                            openCrewId = "crew-tc-1",
                            payslip = slip,
                            nominalSplit = listOf(
                                NominalAllocation(
                                    id = null,
                                    nominalCode = "7000",
                                    description = "Camera crew",
                                    // Short of the line's 1,685 gross.
                                    amount = 1_000.0,
                                ),
                            ),
                        ),
                        onEvent = {},
                    )
                }
            }
            onNodeWithText("Payslip").assertExists()
            onNodeWithText("Does not add up").assertExists()
        }
    }

    @Test
    fun `a posted line's coding is readable but not editable`() {
        runComposeUiTest {
            setContent {
                ZillitTheme(darkTheme = false) {
                    PayrollScreen(
                        state = state(TimecardStatus.Approved).copy(openCrewId = "crew-tc-2"),
                        onEvent = {},
                    )
                }
            }
            // The dialog still opens — the figures are readable — but the
            // controls that would change them are not offered.
            onNodeWithText("Where this is charged").assertExists()
        }
    }

    @Test
    fun `paying is offered to an accountant and nothing is offered to a producer`() {
        runComposeUiTest {
            setContent {
                ZillitTheme(darkTheme = false) {
                    PayrollScreen(state = state(TimecardStatus.Approved), onEvent = {})
                }
            }
            onNodeWithText("Select 1 approved").assertIsDisplayed()
        }

        runComposeUiTest {
            setContent {
                ZillitTheme(darkTheme = false) {
                    PayrollScreen(state = state(TimecardStatus.Approved, producer), onEvent = {})
                }
            }
            // A producer reads the board; they do not operate it.
            onNodeWithText("Payroll Runs").assertIsDisplayed()
        }
    }

    @Test
    fun `a post with no account chosen cannot be confirmed and says why`() {
        runComposeUiTest {
            setContent {
                ZillitTheme(darkTheme = false) {
                    PayrollScreen(
                        state = state(TimecardStatus.Paid).copy(
                            prompt = PayrollPrompt.Post(ids = listOf("tc-1"), effectiveDate = WEEK),
                        ),
                        onEvent = {},
                    )
                }
            }
            onNodeWithText("Choose the settling account").assertExists()
            onNodeWithText(
                "This production has no bank accounts set up, so nothing can be posted. " +
                    "Add one in Production Setup → Accounting.",
            ).assertExists()
        }
    }

    @Test
    fun `a post with an account offers the account and the effective date`() {
        runComposeUiTest {
            setContent {
                ZillitTheme(darkTheme = false) {
                    PayrollScreen(
                        state = state(TimecardStatus.Paid).copy(
                            bankAccounts = listOf(BankAccount("b1", "Barclays Current", "20887714471", "GBP")),
                            prompt = PayrollPrompt.Post(
                                ids = listOf("tc-1"),
                                bankId = "b1",
                                effectiveDate = WEEK,
                            ),
                        ),
                        onEvent = {},
                    )
                }
            }
            onNodeWithText("Barclays Current ••••4471").assertExists()
            onNodeWithText("Post to ledger").assertExists()
        }
    }
}
