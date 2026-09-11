package com.zillit.desktop.feature.taxfiling

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.feature.taxfiling.domain.DraftDiagnostics
import com.zillit.desktop.feature.taxfiling.domain.FiledReturn
import com.zillit.desktop.feature.taxfiling.domain.FilingObligation
import com.zillit.desktop.feature.taxfiling.domain.TaxCompany
import com.zillit.desktop.feature.taxfiling.domain.TaxFiling
import com.zillit.desktop.feature.taxfiling.domain.TaxRegistration
import com.zillit.desktop.feature.taxfiling.domain.VatBox
import com.zillit.desktop.feature.taxfiling.domain.VatReturn
import com.zillit.desktop.feature.taxfiling.ui.RegistrationDraft
import com.zillit.desktop.feature.taxfiling.ui.ReturnState
import com.zillit.desktop.feature.taxfiling.ui.TaxFilingScreen
import com.zillit.desktop.feature.taxfiling.ui.TaxFilingUiState
import com.zillit.desktop.feature.taxfiling.ui.TaxFilingView
import kotlin.test.Test

/**
 * Composes the real screen in every state it has, light and dark.
 *
 * Both themes because a colour defined in one and not the other is invisible
 * until somebody switches; every surface because a layout that throws — a
 * full-width child inside a row, a list inside a menu — takes the window down
 * rather than degrading.
 */
@OptIn(ExperimentalTestApi::class)
class TaxFilingScreenRenderTest {

    private val registration = TaxRegistration(
        id = "reg-1",
        companyId = "co-1",
        companyName = "Zillit Films Ltd",
        registrationNumber = "123456789",
        filingFrequency = "quarterly",
        connected = true,
    )

    private val open = FilingObligation(
        periodKey = "18A1",
        start = "2026-01-01",
        end = "2026-03-31",
        due = "2026-05-07",
        status = "O",
    )

    private val fulfilled = open.copy(periodKey = "17A4", status = "F", received = "2026-01-30")

    private fun registrations() = TaxFilingUiState(
        filings = listOf(
            TaxFiling(
                countryName = "United Kingdom",
                title = "Making Tax Digital for VAT",
                description = "File a company's quarterly VAT return straight from the ledger.",
            ),
        ),
        companies = listOf(TaxCompany("co-1", "Zillit Films Ltd")),
        registrations = listOf(registration, registration.copy(id = "reg-2", connected = false)),
    )

    private fun returnView(state: ReturnState) =
        TaxFilingUiState(view = TaxFilingView.Return, returnState = state)

    @Test
    fun `the registrations list composes in both themes`() {
        listOf(false, true).forEach { dark ->
            runComposeUiTest {
                setContent { ZillitTheme(darkTheme = dark) { TaxFilingScreen(registrations()) {} } }
                onNodeWithText("Tax filing").assertExists()
                // Two rows, one per registration: the same company appears twice.
                onAllNodesWithText("Zillit Films Ltd", substring = true).assertCountEquals(2)
                onNodeWithText("Not authorised", substring = true).assertExists()
            }
        }
    }

    /** The dialog's dropdown is opened, because that is where a menu crashes. */
    @Test
    fun `the registration dialog opens its company menu`() {
        runComposeUiTest {
            setContent {
                ZillitTheme {
                    TaxFilingScreen(registrations().copy(draft = RegistrationDraft())) {}
                }
            }
            onNodeWithText("Add a VAT registration").assertExists()
            onNodeWithText("Choose a company").performClick()
            // The two list rows plus the menu entry the click revealed.
            onAllNodesWithText("Zillit Films Ltd", substring = true).assertCountEquals(3)
        }
    }

    @Test
    fun `the return surface composes with a draft`() {
        val state = ReturnState(
            registration = registration,
            obligations = listOf(open),
            periodKey = "18A1",
            draft = VatReturn(
                mapOf(VatBox.DueOnSales to 1000.0, VatBox.ReclaimedOnPurchases to 250.0),
                periodKey = "18A1",
            ),
        )

        listOf(false, true).forEach { dark ->
            runComposeUiTest {
                setContent { ZillitTheme(darkTheme = dark) { TaxFilingScreen(returnView(state)) {} } }
                onNodeWithText("The nine boxes").assertExists()
                // The stat tile draws its label in capitals.
                onNodeWithText("TO PAY HMRC").assertExists()
                onNodeWithText("File with HMRC").assertExists()
            }
        }
    }

    /** The period picker is a menu too, and it is the one with nine entries. */
    @Test
    fun `the period picker opens`() {
        val state = ReturnState(
            registration = registration,
            obligations = listOf(open, fulfilled),
            periodKey = "18A1",
        )

        runComposeUiTest {
            setContent { ZillitTheme { TaxFilingScreen(returnView(state)) {} } }
            onNodeWithText("2026-01-01 to 2026-03-31").performClick()
            onNodeWithText("2026-01-01 to 2026-03-31 · filed").assertExists()
        }
    }

    @Test
    fun `an all-zero draft explains itself`() {
        val state = ReturnState(
            registration = registration,
            obligations = listOf(open),
            periodKey = "18A1",
            draft = VatReturn(periodKey = "18A1"),
            diagnostics = DraftDiagnostics(rowsInScope = 0, nullCompanyRows = 4),
        )

        runComposeUiTest {
            setContent { ZillitTheme { TaxFilingScreen(returnView(state)) {} } }
            onNodeWithText("Every box came back at zero", substring = true).assertExists()
        }
    }

    @Test
    fun `a fulfilled period shows its receipt and no submit`() {
        val state = ReturnState(
            registration = registration,
            obligations = listOf(fulfilled),
            periodKey = "17A4",
            filed = listOf(
                FiledReturn(
                    periodKey = "17A4",
                    values = mapOf(VatBox.DueOnSales to 800.0),
                    reference = "891614-1",
                    processedAt = "2026-02-01T09:00:00Z",
                ),
            ),
        )

        runComposeUiTest {
            setContent { ZillitTheme { TaxFilingScreen(returnView(state)) {} } }
            onNodeWithText("Filed return").assertExists()
            onNodeWithText("HMRC receipt 891614-1", substring = true).assertExists()
            onNodeWithText("The nine boxes").assertDoesNotExist()
        }
    }

    /** The confirmation repeats the figures, because that is what it is for. */
    @Test
    fun `the submit confirmation shows the boxes it would file`() {
        val state = ReturnState(
            registration = registration,
            obligations = listOf(open),
            periodKey = "18A1",
            draft = VatReturn(mapOf(VatBox.DueOnSales to 1000.0), periodKey = "18A1"),
            confirmingSubmit = true,
        )

        runComposeUiTest {
            setContent { ZillitTheme { TaxFilingScreen(returnView(state)) {} } }
            onNodeWithText("File this return with HMRC?").assertExists()
            onNodeWithText("Box 1 · VAT due on sales").assertExists()
            onNodeWithText("cannot be filed again", substring = true).assertExists()
        }
    }

    /** Without a machine description the screen says so before any work is done. */
    @Test
    fun `an installation that cannot describe itself says so at the top`() {
        runComposeUiTest {
            setContent {
                ZillitTheme {
                    TaxFilingScreen(registrations().copy(canReachAuthority = false)) {}
                }
            }
            onNodeWithText("cannot send HMRC the machine details", substring = true).assertExists()
        }
    }
}
