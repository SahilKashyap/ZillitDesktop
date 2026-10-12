package com.zillit.desktop.feature.talentdeals

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.feature.talentdeals.domain.ExpenseDraft
import com.zillit.desktop.feature.talentdeals.domain.FeeBasis
import com.zillit.desktop.feature.talentdeals.domain.PaymentMode
import com.zillit.desktop.feature.talentdeals.domain.SagFacts
import com.zillit.desktop.feature.talentdeals.domain.SigningDay
import com.zillit.desktop.feature.talentdeals.domain.SigningRow
import com.zillit.desktop.feature.talentdeals.domain.StoredExpense
import com.zillit.desktop.feature.talentdeals.domain.StoredWithholding
import com.zillit.desktop.feature.talentdeals.domain.TalentAttachment
import com.zillit.desktop.feature.talentdeals.domain.TalentContract
import com.zillit.desktop.feature.talentdeals.domain.TalentCurrencies
import com.zillit.desktop.feature.talentdeals.domain.TalentCurrency
import com.zillit.desktop.feature.talentdeals.domain.TalentDealsViewer
import com.zillit.desktop.feature.talentdeals.domain.TalentFileSlot
import com.zillit.desktop.feature.talentdeals.domain.TalentTerritories
import com.zillit.desktop.feature.talentdeals.domain.TalentUnion
import com.zillit.desktop.feature.talentdeals.domain.TrancheDraft
import com.zillit.desktop.feature.talentdeals.domain.WithholdingType
import com.zillit.desktop.feature.talentdeals.ui.DetailState
import com.zillit.desktop.feature.talentdeals.ui.ExhibitState
import com.zillit.desktop.feature.talentdeals.ui.FileViewState
import com.zillit.desktop.feature.talentdeals.ui.FormState
import com.zillit.desktop.feature.talentdeals.ui.ReferenceState
import com.zillit.desktop.feature.talentdeals.ui.RegisterState
import com.zillit.desktop.feature.talentdeals.ui.TalentDealsDestination
import com.zillit.desktop.feature.talentdeals.ui.TalentDealsEvent
import com.zillit.desktop.feature.talentdeals.ui.TalentDealsScreen
import com.zillit.desktop.feature.talentdeals.ui.TalentDealsUiState
import com.zillit.desktop.feature.talentdeals.ui.TalentPdfPage
import com.zillit.desktop.feature.talentdeals.ui.TalentRoute
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Composes the real Talent Deals screen on every destination.
 *
 * Composing the actual screen catches what unit tests cannot: a page that
 * throws while composing, and a nested-scroll arrangement that crashes on an
 * unbounded constraint. (It cannot catch a layout that composes at zero size —
 * `assertExists` passes for those — so the first screenful is asserted with
 * `assertIsDisplayed`, and a section below the fold only with `assertExists`.)
 */
@OptIn(ExperimentalTestApi::class)
class TalentDealsRenderTest {

    private val poster = TalentDealsViewer(userId = "me", hasPostingAccess = true, hasViewAccess = true,
        hasDownloadAccess = true, rightsLoaded = true)
    private val reader = TalentDealsViewer(userId = "me", hasViewAccess = true, rightsLoaded = true)

    private val reference = ReferenceState(
        territories = TalentTerritories.wizard,
        currencies = TalentCurrencies(
            selected = listOf("GBP", "USD"),
            defaultCode = "GBP",
            catalogue = listOf(
                TalentCurrency("GBP", "Pound Sterling", "£", "United Kingdom"),
                TalentCurrency("USD", "US Dollar", "$", "United States"),
                TalentCurrency("EUR", "Euro", "€", "Eurozone"),
            ),
        ),
    )

    private val attachment = TalentAttachment(
        media = "store/1.pdf", bucket = "b", region = "r", name = "Long Form Agreement.pdf",
        contentType = "document", contentSubtype = "pdf",
    )

    private val contract = TalentContract(
        id = "c1",
        reference = "TC-482202",
        person = "Jane Doe",
        role = "Cast",
        status = "issued",
        longFormContract = attachment,
        territoryCode = "us",
        unionIdentifier = "sag-aftra",
        fee = 500000.0,
        currency = "USD",
        paymentMode = "escrow",
        feeBasis = "salary",
        withholding = StoredWithholding(applies = true, basis = WithholdingType.Percent, value = 20.0,
            authorised = true, authorisedRate = 8.0, certificate = attachment.copy(name = "HMRC.pdf")),
        schedule = com.zillit.desktop.feature.talentdeals.domain.StoredSchedule(
            cadence = "weekly",
            tranches = listOf(
                com.zillit.desktop.feature.talentdeals.domain.StoredTranche("Prep", 1767571200000L, 1770940800000L, 6.0,
                    40.0),
                com.zillit.desktop.feature.talentdeals.domain.StoredTranche("Shoot", 1771200000000L, 1780012800000L,
                    15.0, 60.0),
            ),
        ),
        expenses = listOf(StoredExpense("Travel", 4200.0, withhold = true, grossUp = true), StoredExpense("Per diem",
            900.0, withhold = false, grossUp = false)),
        autoTimeCards = true,
    )

    private val other = contract.copy(id = "c2", reference = "TC-482203", person = "Sam Poe", role = "Director",
        unionIdentifier = "dga", longFormContract = null)

    private fun sag() = SagFacts(
        workedMinutes = 600, mealLateness = listOf(2), mealPenalties = 1, overtime15 = 120,
        turnaroundMinutes = 570, turnaroundShort = true, forcedCall = true, consecutiveDay = 6,
    )

    private val exhibitDays = listOf(
        SigningDay(
            reportId = "r1",
            shootDate = 1767571200000L,
            columns = emptyList(),
            rows = listOf(
                SigningRow(cellId = "c", rowId = "a", personId = "u1", personType = "user", name = "Jane Doe",
                    sag = sag(), signed = true),
                SigningRow(cellId = "c", rowId = "a", personId = "a1", personType = "artiste", name = "Jane DOE",
                    signed = false),
            ),
        ),
    )

    private fun state(
        route: TalentRoute,
        viewer: TalentDealsViewer = poster,
        register: RegisterState = RegisterState(contracts = listOf(contract, other), loading = false),
        detail: DetailState = DetailState(contractId = "c1", contract = contract, loading = false,
            unionName = "Screen Actors Guild"),
        form: FormState = FormState(role = "Cast", reference = "TC-123456"),
        exhibit: ExhibitState = ExhibitState(),
        viewing: FileViewState? = null,
    ) = TalentDealsUiState(
        viewer = viewer, route = route, register = register, detail = detail, form = form, exhibit = exhibit,
        reference = reference, viewing = viewing,
    )

    /** The first node with this text is on screen (composed at a non-zero size). */
    private fun androidx.compose.ui.test.ComposeUiTest.shown(text: String, substring: Boolean = false,
        useUnmergedTree: Boolean = false) =
        onAllNodesWithText(text, substring = substring, useUnmergedTree = useUnmergedTree).onFirst().assertIsDisplayed()

    /** Some node has this text — for a section below the first screenful, which composes but is not displayed. */
    private fun androidx.compose.ui.test.ComposeUiTest.has(text: String, substring: Boolean = false,
        useUnmergedTree: Boolean = false) {
        assertTrue(
            onAllNodesWithText(text, substring = substring,
                useUnmergedTree = useUnmergedTree).fetchSemanticsNodes().isNotEmpty(),
            "expected a node reading '$text'",
        )
    }

    private fun show(state: TalentDealsUiState, onEvent: (TalentDealsEvent) -> Unit = {},
        check: androidx.compose.ui.test.ComposeUiTest.() -> Unit) {
        runComposeUiTest {
            setContent { ZillitTheme(darkTheme = false) { TalentDealsScreen(state = state, onEvent = onEvent) } }
            check()
        }
    }

    // -- the register ------------------------------------------------------------------

    @Test
    fun `the register lists the contracts with role, file and terms`() = show(state(TalentRoute.Register)) {
        shown("TALENT DEALS")
        shown("Jane Doe")
        shown("TC-482202")
        shown("Sam Poe")
        shown("Long Form Agreement.pdf")
        shown("Not uploaded")
        shown("Weekly · 2 tranches")
        shown("Add Contract")
    }

    @Test
    fun `a reader has no Add Contract and no remove chips`() = show(state(TalentRoute.Register, viewer = reader)) {
        shown("Jane Doe")
        onAllNodesWithText("Add Contract").assertCountEquals(0)
        onAllNodesWithText("Remove TC-482202").assertCountEquals(0)
    }

    @Test
    fun `a poster has a remove chip per row`() = show(state(TalentRoute.Register)) {
        shown("Jane Doe")
        // Icon buttons carry their label as a click label, not text; the rows themselves composed.
        shown("Sam Poe")
    }

    @Test
    fun `the register says why it is empty`() {
        show(state(TalentRoute.Register, register = RegisterState(loading = false))) {
            shown("No contracts yet. Upload one to get started.")
        }
        show(state(TalentRoute.Register, viewer = reader, register = RegisterState(loading = false))) {
            shown("No contracts yet.")
        }
        show(state(TalentRoute.Register, register = RegisterState(contracts = listOf(contract), loading = false,
            query = "zzz"))) {
            shown("No talents match that search.")
        }
    }

    @Test
    fun `the register has a loading state and an error state with Retry`() {
        show(state(TalentRoute.Register, register = RegisterState(loading = true))) {
            shown("TALENT DEALS")
        }
        var retried = false
        show(
            state(TalentRoute.Register, register = RegisterState(loading = false, error = "No internet connection.")),
            onEvent = { if (it == TalentDealsEvent.Retry) retried = true },
        ) {
            shown("No internet connection.")
            onNodeWithText("Try again").performClick()
        }
        assertTrue(retried)
    }

    @Test
    fun `a viewer without view_access gets the no-access state`() = show(
        state(TalentRoute.Register, viewer = TalentDealsViewer(rightsLoaded = true)),
    ) {
        shown("You don’t have access to Talent Deals.")
    }

    @Test
    fun `Add Contract and a row dispatch their events`() {
        val events = mutableListOf<TalentDealsEvent>()
        show(state(TalentRoute.Register), onEvent = { events += it }) {
            onNodeWithText("Add Contract").performClick()
            onNodeWithText("Jane Doe").performClick()
        }
        assertTrue(TalentDealsEvent.AddContract in events)
        assertTrue(TalentDealsEvent.OpenContract("c1") in events)
    }

    @Test
    fun `the role picker offers the three roles, and Continue waits for a pick`() {
        val events = mutableListOf<TalentDealsEvent>()
        show(state(TalentRoute.Register, register = RegisterState(contracts = listOf(contract), loading = false,
            rolePickerOpen = true)), { events += it }) {
            onNodeWithText("Which role is this contract for?".uppercase()).assertIsDisplayed()
            shown("Director")
            shown("Producer")
            shown("Add another role")
            onNodeWithText("Continue").performClick()
            assertTrue(events.none { it is TalentDealsEvent.PickRole })
            onAllNodesWithText("Cast").onLast().performClick()
            onNodeWithText("Continue").performClick()
        }
        assertEquals(TalentDealsEvent.PickRole("Cast"), events.filterIsInstance<TalentDealsEvent.PickRole>().single())
    }

    @Test
    fun `remove asks with the contract's reference and person`() = show(
        state(TalentRoute.Register, register = RegisterState(contracts = listOf(contract), loading = false,
            deleteTarget = contract)),
    ) {
        shown("Remove contract")
        shown("Remove TC-482202 — Jane Doe? The uploaded agreement stays in storage; the contract leaves this list.")
    }

    // -- one contract ------------------------------------------------------------------

    @Test
    fun `the contract page shows every section with the form's own figures`() = show(state(TalentRoute.View("c1"))) {
        shown("TC-482202")
        shown("Jane Doe")
        shown("Edit")
        has("CONTRACT")
        has("FEE")
        has("WITHHOLDING TAX")
        has("PAYMENT SCHEDULE")
        has("EXPENSES")
        has("Long Form Agreement.pdf")
        has("Fully escrowed")
        has("Salary only")
        has("Auto-generated")
        has("SAG - Screen Actors Guild - AFTRA")
        has("Weekly · 2 tranches · fully allocated")
        has("8%")
        has("View Exhibit G for penalties and turnarounds")
    }

    @Test
    fun `a reader has no Edit`() = show(state(TalentRoute.View("c1"), viewer = reader)) {
        shown("Jane Doe")
        onAllNodesWithText("Edit").assertCountEquals(0)
    }

    @Test
    fun `a contract page without a weekly term, withholding or expenses says so`() {
        val plain = contract.copy(
            withholding = StoredWithholding(),
            schedule = com.zillit.desktop.feature.talentdeals.domain.StoredSchedule(),
            expenses = emptyList(),
            unionIdentifier = "dga",
            role = "Director",
        )
        show(state(TalentRoute.View("c1"), detail = DetailState(contractId = "c1", contract = plain,
            loading = false))) {
            has("Does not apply")
            has("No weekly term")
            has("None")
            has("DGA - Directors Guild of America")
            onAllNodesWithText("View Exhibit G for penalties and turnarounds").assertCountEquals(0)
        }
    }

    @Test
    fun `the contract page has loading and error faces`() {
        show(state(TalentRoute.View("c1"), detail = DetailState(contractId = "c1", loading = true))) {
            shown("TALENT DEALS")
        }
        show(state(TalentRoute.View("c1"), detail = DetailState(contractId = "c1", loading = false,
            error = "Contract not found."))) {
            shown("Contract not found.")
        }
    }

    @Test
    fun `Exhibit G opens on the contract page with the day, flags, picker, grid and priced day`() = show(
        state(TalentRoute.View("c1"), exhibit = ExhibitState(open = true, days = exhibitDays)),
    ) {
        has("Hide Exhibit G")
        has("Exhibit G")
        has("Mon 05 Jan")
        has("Signed")
        has("2 rows on this sheet match")
        has("Turnaround 9h 30m — short")
        has("Day total")
        has("Performer’s signature")
    }

    @Test
    fun `Exhibit G shows its loading, error and empty faces`() {
        show(state(TalentRoute.View("c1"), exhibit = ExhibitState(open = true, loading = true))) {
            has("Exhibit G")
        }
        show(state(TalentRoute.View("c1"), exhibit = ExhibitState(open = true, error = "No such performer"))) {
            has("No such performer")
        }
        show(state(TalentRoute.View("c1"), exhibit = ExhibitState(open = true))) {
            has("No days worked yet")
            has("Daily time report")
        }
    }

    @Test
    fun `the file viewer shows pages, an unsupported notice, or the server's words`() {
        val pages = listOf(TalentPdfPage(1, ByteArray(0), 10, 10))
        show(state(TalentRoute.View("c1"), viewing = FileViewState(attachment, loading = false, pages = pages))) {
            has("Long Form Agreement.pdf", useUnmergedTree = true)
            has("Download")
            has("Close")
        }
        show(state(TalentRoute.View("c1"), viewer = reader, viewing = FileViewState(attachment, loading = false,
            unsupported = true))) {
            has("This file type can’t be previewed here.")
            onAllNodesWithText("Download").assertCountEquals(0)
        }
        show(state(TalentRoute.View("c1"), viewing = FileViewState(attachment, loading = false,
            error = "Could not read file"))) {
            has("Could not read file")
        }
    }

    // -- the form ----------------------------------------------------------------------

    @Test
    fun `Add Contract is titled by its role and carries the four sections`() = show(state(TalentRoute.New("Cast"))) {
        shown("Cast")
        shown("Save Contract")
        has("CONTRACT UPLOAD")
        has("UNION")
        has("PAYMENT SCHEDULE")
        has("EXPENSES")
        has("Choose contract file")
        has("— Select Territory —")
        has("Reference TC-123456")
        has("Click here if fully escrowed")
        has("Click here if first day payment")
        has("Click here if withholding tax applies")
        has("Click here if payment has to be made weekly basis per contract")
        has("Click here if expenses are paid alongside the fee")
        // The role's own guild is offered before any territory is picked.
        has("Select union…")
    }

    @Test
    fun `an attempted save marks every outstanding field and counts them`() = show(
        state(TalentRoute.New("Cast"), form = FormState(role = "Cast", reference = "TC-1", attempted = true)),
    ) {
        has("Enter a name")
        has("Attach the contract")
        has("Select the territory")
        has("Select the union")
        has("Enter the fee")
        has("Choose escrowed or first day payment")
        shown("6 fields need attention")
    }

    @Test
    fun `a single outstanding field is counted in the singular`() {
        val form = FormState(
            role = "Cast",
            reference = "TC-1",
            attempted = true,
            form = com.zillit.desktop.feature.talentdeals.domain.TalentForm(
                person = "Jane", contractFile = TalentFileSlot.Stored(attachment), territory = "us",
                union = "sag-aftra",
                fee = "", paymentMode = PaymentMode.Escrow, feeBasis = FeeBasis.Salary,
            ),
        )
        show(state(TalentRoute.New("Cast"), form = form)) {
            shown("1 field needs attention")
            has("Enter the fee")
        }
    }

    @Test
    fun `the weekly term opens the tranche table and the allocation bar, expenses their own`() {
        val form = FormState(
            role = "Cast",
            reference = "TC-1",
            attempted = true,
            unions = listOf(TalentUnion("1", "sag-aftra", "Screen Actors Guild - AFTRA")),
            form = com.zillit.desktop.feature.talentdeals.domain.TalentForm(
                person = "Jane", contractFile = TalentFileSlot.Stored(attachment), territory = "us",
                union = "sag-aftra", fee = "100000", currency = "USD",
                paymentMode = PaymentMode.Escrow, feeBasis = FeeBasis.Salary,
                withholding = true, withholdingValue = "20", withholdingAuthorised = true, authorisedRate = "8",
                weekly = true,
                tranches = listOf(TrancheDraft("t1", "Prep", "2026-01-05", "2026-01-11", "1", "40")),
                hasExpenses = true,
                expenses = listOf(ExpenseDraft("e1", "Travel", "100", withhold = true, grossUp = true)),
                autoTimeCards = true,
            ),
        )
        show(state(TalentRoute.New("Cast"), form = form)) {
            has("PAYMENT TRANCHE")
            has("Add tranche")
            has("Auto-generate time cards")
            has("Allocate the whole fee across the tranches")
            has("Add expense")
            has("Click here if a reduced rate has been pre-authorised")
            has("The authorised rate replaces the contract rate on the fee and on every expense below.")
            has("View Exhibit G for penalties and turnarounds")
            // The breakdown works the tax out at the authorised rate.
            has("8% pre-authorised")
            has("Cost to production", substring = true)
        }
    }

    @Test
    fun `Exhibit G opens on the form under the weekly term`() {
        val form = FormState(
            role = "Cast", reference = "TC-1", lookupName = "Jane Doe",
            form = com.zillit.desktop.feature.talentdeals.domain.TalentForm(person = "Jane Doe", union = "sag-aftra",
                weekly = true, tranches = listOf(TrancheDraft("t", "Prep"))),
        )
        show(state(TalentRoute.New("Cast"), form = form, exhibit = ExhibitState(open = true, days = exhibitDays))) {
            has("Hide Exhibit G")
            has("Mon 05 Jan")
        }
        show(state(TalentRoute.New("Cast"), form = form.copy(lookupName = ""), exhibit = ExhibitState(open = true))) {
            has("Enter the talent’s name above to find their Exhibit G days.")
        }
    }

    @Test
    fun `the explanation under expenses says why there is no tax yet`() {
        val form = FormState(
            role = "Cast", reference = "TC-1",
            form = com.zillit.desktop.feature.talentdeals.domain.TalentForm(hasExpenses = true,
                expenses = listOf(ExpenseDraft("e", "Travel", "10"))),
        )
        show(state(TalentRoute.New("Cast"), form = form)) {
            has("Tick withholding tax above to work the tax out on these expenses.")
        }
        show(state(TalentRoute.New("Cast"), form = form.copy(form = form.form.copy(withholding = true)))) {
            has("Enter the withholding rate above to apply tax to these expenses (20% is the UK basic rate).")
        }
    }

    @Test
    fun `Edit shows its silhouette while the row loads, then the saved form`() {
        show(state(TalentRoute.Edit("c1"), form = FormState(contractId = "c1", loadingRow = true))) {
            shown("TALENT DEALS")
        }
        show(state(TalentRoute.Edit("c1"), form = FormState(role = "Cast", reference = "TC-482202", contractId = "c1",
            form = savedForm()))) {
            shown("Save Changes")
            shown("TC-482202")
            has("Jane Doe", substring = true)
            has("Long Form Agreement.pdf")
        }
    }

    private fun savedForm() = com.zillit.desktop.feature.talentdeals.domain.TalentForm(
        person = "Jane Doe", contractFile = TalentFileSlot.Stored(attachment), territory = "us",
        union = "sag-aftra", fee = "500000",
    )

    @Test
    fun `saving shows its progress and is disabled`() = show(
        state(TalentRoute.New("Cast"), form = FormState(role = "Cast", reference = "TC-1", saving = true)),
    ) {
        shown("Saving…")
    }

    @Test
    fun `every destination composes for a poster, and the posting ones are refused to a reader by the gate`() {
        TalentDealsDestination.entries.forEach { destination ->
            val route = when (destination) {
                TalentDealsDestination.Register -> TalentRoute.Register
                TalentDealsDestination.View -> TalentRoute.View("c1")
                TalentDealsDestination.New -> TalentRoute.New("Cast")
                TalentDealsDestination.Edit -> TalentRoute.Edit("c1")
            }
            show(state(route)) { shown("TALENT DEALS") }
            assertEquals(!destination.requiresPost, destination.visibleTo(reader))
        }
    }

    @Test
    fun `every destination composes in dark mode too`() {
        listOf(TalentRoute.Register, TalentRoute.View("c1"), TalentRoute.New("Cast")).forEach { route ->
            runComposeUiTest {
                setContent { ZillitTheme(darkTheme = true) { TalentDealsScreen(state = state(route), onEvent = {}) } }
                shown("TALENT DEALS")
            }
        }
    }
}
