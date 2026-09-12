package com.zillit.desktop.feature.taxfiling

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.test.hasSetTextAction
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.feature.taxfiling.domain.BoxMapping
import com.zillit.desktop.feature.taxfiling.domain.CoaCode
import com.zillit.desktop.feature.taxfiling.domain.FiledReturn
import com.zillit.desktop.feature.taxfiling.domain.FilingObligation
import com.zillit.desktop.feature.taxfiling.domain.LayerCode
import com.zillit.desktop.feature.taxfiling.domain.LayerSet
import com.zillit.desktop.feature.taxfiling.domain.TaxCompany
import com.zillit.desktop.feature.taxfiling.domain.TaxFiling
import com.zillit.desktop.feature.taxfiling.domain.TaxFilingRoute
import com.zillit.desktop.feature.taxfiling.domain.TaxRegistration
import com.zillit.desktop.feature.taxfiling.domain.VatBox
import com.zillit.desktop.feature.taxfiling.domain.VatReturn
import com.zillit.desktop.feature.taxfiling.ui.CatalogState
import com.zillit.desktop.feature.taxfiling.ui.MappingLookups
import com.zillit.desktop.feature.taxfiling.ui.RegistrationDraft
import com.zillit.desktop.feature.taxfiling.ui.ReturnState
import com.zillit.desktop.feature.taxfiling.ui.TaxFilingEvent
import com.zillit.desktop.feature.taxfiling.ui.TaxFilingScreen
import com.zillit.desktop.feature.taxfiling.ui.TaxFilingUiState
import com.zillit.desktop.feature.taxfiling.ui.TaxFilingView
import com.zillit.desktop.feature.taxfiling.ui.TaxToast
import com.zillit.desktop.feature.taxfiling.ui.TaxToastTone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Composes the real screen in every state it has, light and dark.
 *
 * Both themes because a colour defined in one and not the other is invisible
 * until somebody switches; every surface because a layout that throws — a
 * full-width child inside a row, a list inside a menu — takes the window down
 * rather than degrading. Menus are opened, because opening is where they crash.
 */
@OptIn(ExperimentalTestApi::class)
class TaxFilingScreenRenderTest {

    private val filing = TaxFilingRoute.Filing("GB", "mtd-vat")

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

    private val fulfilled = open.copy(periodKey = "17A4", start = "2025-10-01", end = "2025-12-31", status = "F")

    private fun registrations() = TaxFilingUiState(
        route = filing,
        companies = listOf(TaxCompany("co-1", "Zillit Films Ltd", "GB"), TaxCompany("co-2", "Second Unit Ltd", "GB")),
        registrations = listOf(
            registration,
            registration.copy(
                id = "reg-2",
                companyId = "co-3",
                companyName = "Night Unit Ltd",
                registrationNumber = "987654321",
                connected = false,
            ),
        ),
    )

    private fun returnView(returnState: ReturnState, lookups: MappingLookups = MappingLookups()) = TaxFilingUiState(
        route = filing,
        registrations = listOf(registration),
        view = TaxFilingView.Return,
        returnState = returnState.copy(registration = returnState.registration ?: registration),
        lookups = lookups,
    )

    private fun themes(block: (dark: Boolean) -> Unit) = listOf(false, true).forEach(block)

    // -- catalogue ------------------------------------------------------------------------

    @Test
    fun `the catalogue groups filings by the production's countries`() {
        val state = TaxFilingUiState(
            catalog = CatalogState(
                loaded = true,
                filings = listOf(
                    TaxFiling("GB", "United Kingdom", "🇬🇧", "VAT", "mtd-vat", "MTD VAT", "HMRC · MTD", "File."),
                    TaxFiling("IE", "Ireland", "", "VAT", "ros-vat", "ROS VAT", "Revenue", "File in Ireland."),
                ),
                companyCountries = setOf("GB"),
            ),
        )
        themes { dark ->
            runComposeUiTest {
                setContent { ZillitTheme(darkTheme = dark) { TaxFilingScreen(state, {}) } }
                onNodeWithText("MANAGEMENT").assertExists()
                onNodeWithText("YOUR COUNTRIES").assertExists()
                onNodeWithText("OTHER COUNTRIES").assertExists()
                onNodeWithText("ROS VAT").assertExists()
                // A filing with no flag shows its country code instead.
                onNodeWithText("IE").assertExists()
            }
        }
    }

    @Test
    fun `an empty catalogue says so`() {
        runComposeUiTest {
            setContent { ZillitTheme { TaxFilingScreen(TaxFilingUiState(catalog = CatalogState(loaded = true)), {}) } }
            onNodeWithText("No tax filings available").assertExists()
        }
    }

    @Test
    fun `clicking a filing card opens that filing`() {
        val events = mutableListOf<TaxFilingEvent>()
        val gb = TaxFiling(country = "GB", countryName = "United Kingdom", key = "mtd-vat", title = "MTD VAT")
        val state = TaxFilingUiState(catalog = CatalogState(loaded = true, filings = listOf(gb)))
        runComposeUiTest {
            setContent { ZillitTheme { TaxFilingScreen(state, { events += it }) } }
            onNodeWithText("MTD VAT").performClick()
        }
        assertEquals(TaxFilingEvent.OpenFiling(gb), events.single())
    }

    @Test
    fun `a filing this client cannot render offers the way back`() {
        runComposeUiTest {
            setContent {
                ZillitTheme { TaxFilingScreen(TaxFilingUiState(route = TaxFilingRoute.Filing("FR", "tva")), {}) }
            }
            onNodeWithText("This tax filing isn’t available yet.").assertExists()
            onNodeWithText("Back to Tax Filing").assertExists()
        }
    }

    // -- registrations ----------------------------------------------------------------------

    @Test
    fun `the registrations list composes in both themes`() {
        themes { dark ->
            runComposeUiTest {
                setContent { ZillitTheme(darkTheme = dark) { TaxFilingScreen(registrations(), {}) } }
                onNodeWithText("Registered companies").assertExists()
                onNodeWithText("VRN 123 456 789").assertExists()
                onNodeWithText("Connected to HMRC").assertExists()
                onNodeWithText("Connect to HMRC").assertExists()
                onNodeWithText("required to file a return").assertExists()
                onAllNodesWithText("Open VAT return").assertCountEquals(2)
            }
        }
    }

    /** "Open VAT return" waits for HMRC, locked, as the web's does. */
    @Test
    fun `a registration not yet connected cannot be opened`() {
        val events = mutableListOf<TaxFilingEvent>()
        runComposeUiTest {
            setContent { ZillitTheme { TaxFilingScreen(registrations(), { events += it }) } }
            onAllNodesWithText("Open VAT return")[1].performClick()
            onAllNodesWithText("Open VAT return")[0].performClick()
        }
        assertEquals(listOf<TaxFilingEvent>(TaxFilingEvent.Open(registration)), events)
    }

    @Test
    fun `a consent in progress says where to finish it`() {
        runComposeUiTest {
            setContent { ZillitTheme { TaxFilingScreen(registrations().copy(connectingId = "reg-2"), {}) } }
            onNodeWithText("Connecting…").assertExists()
            onNodeWithText("Finish signing in to HMRC in your browser.").assertExists()
        }
    }

    @Test
    fun `no registrations shows the three steps`() {
        runComposeUiTest {
            setContent { ZillitTheme { TaxFilingScreen(TaxFilingUiState(route = filing), {}) } }
            onNodeWithText("No companies registered yet").assertExists()
            onNodeWithText("Connect to HMRC").assertExists()
            onNodeWithText("File VAT return").assertExists()
        }
    }

    /** The register dialog's company menu is opened, because that is where a menu crashes. */
    @Test
    fun `the register dialog offers only free companies`() {
        runComposeUiTest {
            setContent {
                ZillitTheme {
                    TaxFilingScreen(registrations().copy(draft = RegistrationDraft(registrationNumber = "1234")), {})
                }
            }
            onNodeWithText("Register a VAT number").assertExists()
            onNodeWithText("4/9").assertExists()
            onNodeWithText("Select a company…").performClick()
            onNodeWithText("Second Unit Ltd (GB)").assertExists()
            // Zillit Films Ltd already has a registration.
            onAllNodesWithText("Zillit Films Ltd (GB)").assertCountEquals(0)
            onNodeWithText("Register VRN").assertIsNotEnabled()
        }
    }

    @Test
    fun `the remove dialog repeats the web's warning`() {
        runComposeUiTest {
            setContent { ZillitTheme { TaxFilingScreen(registrations().copy(removing = registration), {}) } }
            onNodeWithText("Remove this registration?").assertExists()
            onNodeWithText("disconnects it from HMRC", substring = true).assertExists()
            onNodeWithText("Remove registration").assertExists()
        }
    }

    // -- the return ------------------------------------------------------------------------------

    @Test
    fun `a return with no period asks for one`() {
        runComposeUiTest {
            setContent {
                ZillitTheme { TaxFilingScreen(returnView(ReturnState(obligations = listOf(open, fulfilled))), {}) }
            }
            // The card's title and the picker's label, as the web has both.
            onAllNodesWithText("Obligation period").assertCountEquals(2)
            onNodeWithText("Select an obligation period").assertExists()
            onNodeWithText("Select an obligation…").performClick()
            onNodeWithText("2025-10-01 → 2025-12-31 · 17A4").assertExists()
            onAllNodesWithText("Due 2026-05-07").onFirst().assertExists()
        }
    }

    @Test
    fun `an open period shows the boxes beside the summary`() {
        val state = ReturnState(
            obligations = listOf(open),
            periodKey = "18A1",
            mappings = mapOf(
                VatBox.SalesExVat to BoxMapping(box = "box6", codes = listOf("4000", "4010", "4020")),
                VatBox.GoodsSuppliedExVat to BoxMapping(box = "box8", markZero = true),
            ),
            draft = VatReturn(
                mapOf(VatBox.DueOnSales to 1000.0, VatBox.ReclaimedOnPurchases to 250.0, VatBox.SalesExVat to 5000.0),
                periodKey = "18A1",
            ),
        )
        themes { dark ->
            runComposeUiTest {
                setContent { ZillitTheme(darkTheme = dark) { TaxFilingScreen(returnView(state), {}) } }
                onNodeWithText("Box mapping").assertExists()
                onNodeWithText("Return summary").assertExists()
                onNodeWithText("4000, 4010 +1", substring = true).assertExists()
                onNodeWithText("Forced to £0 — ledger ignored").assertExists()
                onNodeWithText("Net VAT to pay to HMRC").assertExists()
                onAllNodesWithText("£750.00").onFirst().assertExists()
                onNodeWithText("TO PAY").assertExists()
                onNodeWithText("Recalculate from ledger").assertExists()
                onNodeWithText("Submit to HMRC").assertExists()
            }
        }
    }

    /** An open box's four fields, and the code picker's list opened from typing. */
    @Test
    fun `an open box offers codes from the chart`() {
        val events = mutableListOf<TaxFilingEvent>()
        val state = ReturnState(obligations = listOf(open), periodKey = "18A1", expanded = setOf(VatBox.DueOnSales))
        val lookups = MappingLookups(
            loaded = true,
            coa = listOf(CoaCode("4000", "Sales"), CoaCode("4010", "Other income"), CoaCode("7000", "Travel")),
            layerSets = listOf(LayerSet("set-loc", "Locations", "LOC", "#3B82F6", listOf(LayerCode("n1", "LOC-LON")))),
            assetTags = listOf("VFX"),
        )
        runComposeUiTest {
            setContent { ZillitTheme { TaxFilingScreen(returnView(state, lookups), { events += it }) } }
            onNodeWithText("Ledger codes").assertExists()
            onNodeWithText("+ Layers").assertExists()
            onNodeWithText("Add tags…").assertExists()
            onNodeWithText("Mark zero — force this box to 0 (ignore the ledger)").assertExists()

            val codeField = onAllNodes(hasSetTextAction()).onFirst()
            codeField.performClick()
            codeField.performTextInput("40")
            onNodeWithText("Other income").assertExists()
            onNodeWithText("Other income").performClick()
        }
        val edit = events.filterIsInstance<TaxFilingEvent.EditMapping>().lastOrNull()
        assertEquals(listOf("4010"), edit?.mapping?.codes)
    }

    @Test
    fun `the layers picker lists each set`() {
        val state = ReturnState(obligations = listOf(open), periodKey = "18A1", expanded = setOf(VatBox.DueOnSales))
        val lookups = MappingLookups(
            loaded = true,
            layerSets = listOf(
                LayerSet("set-loc", "Locations", "LOC", "#3B82F6", listOf(LayerCode("n1", "LOC-LON", "London"))),
            ),
        )
        runComposeUiTest {
            setContent { ZillitTheme { TaxFilingScreen(returnView(state, lookups), {}) } }
            onNodeWithText("+ Layers").performClick()
            onNodeWithText("LOCATIONS").assertExists()
            onNodeWithText("— none —").performClick()
            onNodeWithText("LOC-LON · London").assertExists()
        }
    }

    @Test
    fun `a fulfilled period shows its receipt and nothing to file`() {
        val state = ReturnState(
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
        themes { dark ->
            runComposeUiTest {
                setContent { ZillitTheme(darkTheme = dark) { TaxFilingScreen(returnView(state), {}) } }
                onNodeWithText("Filed return").assertExists()
                onNodeWithText("891614-1", substring = true).assertExists()
                onNodeWithText("Box mapping").assertDoesNotExist()
                onNodeWithText("Submit to HMRC").assertDoesNotExist()
            }
        }
    }

    @Test
    fun `a period fulfilled elsewhere says there are no figures here`() {
        val state = ReturnState(obligations = listOf(fulfilled), periodKey = "17A4")
        runComposeUiTest {
            setContent { ZillitTheme { TaxFilingScreen(returnView(state), {}) } }
            onNodeWithText("It wasn’t filed from here", substring = true).assertExists()
        }
    }

    /** The confirmation shows HMRC's declaration and the figures it would file. */
    @Test
    fun `the submit confirmation shows the declaration and the boxes`() {
        val state = ReturnState(
            obligations = listOf(open),
            periodKey = "18A1",
            draft = VatReturn(mapOf(VatBox.DueOnSales to 1000.0), periodKey = "18A1"),
            confirmingSubmit = true,
        )
        runComposeUiTest {
            setContent { ZillitTheme { TaxFilingScreen(returnView(state), {}) } }
            onNodeWithText("Submit this VAT return to HMRC?").assertExists()
            onNodeWithText("making a legal declaration", substring = true).assertExists()
            onNodeWithText("can't be submitted again", substring = true).assertExists()
        }
    }

    /** A stale draft still shows, but says it must be recalculated. */
    @Test
    fun `a stale draft asks to be recalculated`() {
        val state = ReturnState(
            obligations = listOf(open),
            periodKey = "18A1",
            draft = VatReturn(mapOf(VatBox.DueOnSales to 1000.0), periodKey = "18A1"),
            draftStale = true,
        )
        runComposeUiTest {
            setContent { ZillitTheme { TaxFilingScreen(returnView(state), {}) } }
            onNodeWithText("Mapping changed — recalculate before submitting.").assertExists()
            onNodeWithText("Submit to HMRC").assertIsNotEnabled()
        }
    }

    // -- chrome ----------------------------------------------------------------------------------

    @Test
    fun `the breadcrumb names the return's company`() {
        val events = mutableListOf<TaxFilingEvent>()
        runComposeUiTest {
            setContent { ZillitTheme { TaxFilingScreen(returnView(ReturnState()), { events += it }) } }
            onNodeWithText("TAX FILING").assertExists()
            onNodeWithText("MTD VAT").performClick()
        }
        assertTrue(TaxFilingEvent.BackToRegistrations in events)
    }

    @Test
    fun `a toast is shown at the foot of the surface`() {
        val toast = TaxToast("Registered Zillit Films Ltd", TaxToastTone.Success)
        runComposeUiTest {
            setContent { ZillitTheme { TaxFilingScreen(registrations(), {}, toast = toast) } }
            onNodeWithText("Registered Zillit Films Ltd").assertExists()
        }
    }

    /** Without a machine description the screen says so before any work is done. */
    @Test
    fun `an installation that cannot describe itself says so at the top`() {
        runComposeUiTest {
            setContent { ZillitTheme { TaxFilingScreen(registrations().copy(canReachAuthority = false), {}) } }
            onNodeWithText("cannot send HMRC the machine details", substring = true).assertExists()
        }
    }
}
