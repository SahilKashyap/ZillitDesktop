package com.zillit.desktop.feature.cardexpenses

import com.zillit.desktop.feature.cardexpenses.data.ListOrEmpty
import com.zillit.desktop.feature.cardexpenses.data.ReceiptDto
import com.zillit.desktop.feature.cardexpenses.data.TransactionDto
import com.zillit.desktop.feature.cardexpenses.domain.CardCompany
import com.zillit.desktop.feature.cardexpenses.domain.CurrencyCascade
import com.zillit.desktop.feature.cardexpenses.domain.CardProvider
import com.zillit.desktop.feature.cardexpenses.domain.CardReference
import com.zillit.desktop.feature.cardexpenses.domain.CardStatus
import com.zillit.desktop.feature.cardexpenses.domain.CardType
import com.zillit.desktop.feature.cardexpenses.domain.CardViewer
import com.zillit.desktop.feature.cardexpenses.domain.ExpenseCard
import com.zillit.desktop.feature.cardexpenses.domain.ProviderIds
import com.zillit.desktop.feature.cardexpenses.domain.ScoreBands
import com.zillit.desktop.feature.cardexpenses.domain.TransactionFilters
import com.zillit.desktop.feature.cardexpenses.domain.duplicateRaw
import com.zillit.desktop.feature.cardexpenses.domain.matchRaw
import com.zillit.desktop.feature.cardexpenses.domain.offersManualMatch
import com.zillit.desktop.feature.cardexpenses.ui.ActivationDraft
import com.zillit.desktop.feature.cardexpenses.ui.CardDestination
import com.zillit.desktop.feature.cardexpenses.ui.CardEvent
import com.zillit.desktop.feature.cardexpenses.ui.CardExpensesViewModel
import com.zillit.desktop.feature.cardexpenses.ui.CardUiState
import com.zillit.desktop.feature.cardexpenses.ui.CardsArea
import com.zillit.desktop.feature.cardexpenses.ui.GbDate
import com.zillit.desktop.feature.cardexpenses.ui.ImportState
import com.zillit.desktop.feature.cardexpenses.ui.currencyOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val json = Json { ignoreUnknownKeys = true; isLenient = true }

/** The small web-parity gaps closed together, each pinned to the web line it copies. */
@OptIn(ExperimentalCoroutinesApi::class)
class CardLowGapsTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    // -- the currency cascade (`resolveCurrency.js`) ------------------------------

    @Test
    fun `a currency falls from the record to its transaction, its card, the default, then GBP`() {
        assertEquals("EUR", CurrencyCascade.resolve("EUR", "USD", "JPY", "CAD"))
        assertEquals("USD", CurrencyCascade.resolve(null, "USD", "JPY", "CAD"))
        assertEquals("JPY", CurrencyCascade.resolve("", " ", "JPY", "CAD"))
        assertEquals("CAD", CurrencyCascade.resolve(null, null, null, "CAD"))
        assertEquals("GBP", CurrencyCascade.resolve(null, null, null, null), "a symbol always prints")
        assertEquals("GBP", CurrencyCascade.resolve(null, null, null, ""))
    }

    @Test
    fun `a row that names a card but no currency prints the card's, else the production's`() {
        val state = CardUiState(
            viewer = viewer(),
            destination = CardDestination.TopUpQueue,
            cardCurrencies = mapOf("c1" to "EUR"),
            cardsArea = CardsArea(reference = CardReference(defaultCurrency = "USD")),
        )

        assertEquals("EUR", state.currencyOf("c1", null))
        assertEquals("GBP", state.currencyOf("c1", "GBP"), "the record's own wins")
        assertEquals("USD", state.currencyOf("unknown", null), "no such card: the production default")
        assertEquals("USD", state.currencyOf(null, null))
    }

    @Test
    fun `a receipt and a transaction read transaction_currency when currency is absent`() {
        val receipt = json.decodeFromString(
            ReceiptDto.serializer(),
            """{"id":"r1","amount":"5","transaction_currency":"usd"}""",
        ).toDomain() ?: error("no receipt")
        val transaction = json.decodeFromString(
            TransactionDto.serializer(),
            """{"id":"t1","amount":"5","transaction_currency":"EUR"}""",
        ).toDomain() ?: error("no transaction")

        assertNull(receipt.currency, "writes still echo the receipt's own currency")
        assertEquals("USD", CardUiState(viewer = viewer(), destination = CardDestination.History).currencyOf(receipt))
        assertEquals("EUR", transaction.transactionCurrency)
    }

    @Test
    fun `the card catalogue is read at start for its currencies`() = runTest(dispatcher) {
        val repository = FakeCardRepository(cards = listOf(card("c1", currency = "EUR")))
        val vm = CardExpensesViewModel(repository = repository, today = { TODAY }, viewer = { viewer() })
        vm.start()
        advanceUntilIdle()

        assertEquals(mapOf("c1" to "EUR"), vm.state.value.cardCurrencies)
    }

    // -- scores are thresholded raw (`ReceiptInboxPage.jsx:362,436,463`) -------------

    @Test
    fun `a score is judged on the fraction, not on the rounded percentage`() {
        val almost = json.decodeFromString(
            ReceiptDto.serializer(),
            """{"id":"r1","match_score":"0.795","duplicate_score":"0.846","personal_score":"0.003"}""",
        ).toDomain() ?: error("no receipt")

        assertEquals(80, almost.matchScore, "0.795 prints 80…")
        assertFalse(ScoreBands.isStrong(almost.matchRaw ?: 0.0), "…yet is not green")
        assertTrue(ScoreBands.isFair(almost.matchRaw ?: 0.0))
        assertEquals(85, almost.duplicateScore, "0.846 prints 85…")
        assertFalse(ScoreBands.isCertain(almost.duplicateRaw ?: 0.0), "…yet is only a possible duplicate")
        assertEquals(0, almost.personalScore, "0.003 prints 0%, which the web still shows")
        assertTrue((almost.personalFraction ?: 0.0) > 0.0)
        assertTrue(ScoreBands.isCertain(0.85) && ScoreBands.isStrong(0.8))
    }

    @Test
    fun `a score already a percentage is taken as one, and zero is no score`() {
        val receipt = json.decodeFromString(
            ReceiptDto.serializer(),
            """{"id":"r1","match_score":"92","duplicate_score":"0"}""",
        ).toDomain() ?: error("no receipt")

        assertEquals(0.92, receipt.matchFraction)
        assertNull(receipt.duplicateFraction)
    }

    // -- dates and codes ------------------------------------------------------------

    @Test
    fun `a receipt keeps its own date apart from the created_at fallback`() {
        val row = json.decodeFromString(
            ReceiptDto.serializer(),
            """{"id":"r1","created_at":"1754006400000"}""",
        ).toDomain() ?: error("no receipt")

        assertNull(row.ownDate, "the approval queue and the detail print a dash")
        assertEquals(1_754_006_400_000, row.date, "the list rows fall back to created_at")
    }

    @Test
    fun `dates print the way the web's en-GB build does`() {
        // Midday UTC is the same calendar day in every zone from UTC-11 to UTC+11.
        val noon = 1_754_049_600_000L
        assertEquals("", GbDate.date(null))
        val printed = GbDate.date(noon)
        assertTrue(Regex("""\d{2} [A-Z][a-z]{2,3} \d{4}""").matches(printed), printed)
        assertTrue(Regex("""\d{2} [A-Z][a-z]{2,3} \d{4}, \d{2}:\d{2}""").matches(GbDate.dateTime(noon)))
        assertEquals("", GbDate.dateTime(0))
    }

    @Test
    fun `the code column reads code as well as nominal_code`() {
        val coded = json.decodeFromString(ReceiptDto.serializer(), """{"id":"r1","code":"4100"}""").toDomain()
        val both = json.decodeFromString(
            ReceiptDto.serializer(),
            """{"id":"r2","code":"4100","nominal_code":"4200"}""",
        ).toDomain()

        assertEquals("4100", coded?.nominalCode)
        assertEquals("4200", both?.nominalCode, "nominal_code is the row's own when both come")
    }

    // -- the Receipt Inbox menu (`ReceiptInboxPage.jsx:593-597`) ----------------------

    @Test
    fun `manual match is offered on unmatched, suggested, or a row with no status and no transaction`() {
        fun row(wire: String) = json.decodeFromString(ReceiptDto.serializer(), wire).toDomain()!!

        assertTrue(row("""{"id":"a","match_status":"unmatched"}""").offersManualMatch)
        assertTrue(row("""{"id":"a","match_status":"suggested_match"}""").offersManualMatch)
        assertTrue(row("""{"id":"a"}""").offersManualMatch)
        assertFalse(row("""{"id":"a","transaction_id":"t1"}""").offersManualMatch, "no status, but already linked")
        assertFalse(row("""{"id":"a","match_status":"matched"}""").offersManualMatch)
    }

    // -- All Transactions' dates (`transactionQuery.js:182-205`) -----------------------

    @Test
    fun `done clamps both ends to today and drops a malformed one`() {
        val today = "2026-10-12"
        val typed = TransactionFilters(from = "2026-12-01", to = "2027-01-05")

        val applied = typed.withDateRules(today)

        assertEquals("2026-10-12", applied.from, "a future From would return nothing")
        assertEquals("2026-10-12", applied.to)
        assertEquals("", TransactionFilters(from = "15000-01-01", to = "x").withDateRules(today).from)
        assertEquals(typed.copy(untilToday = true), typed.copy(untilToday = true).withDateRules(today))
    }

    @Test
    fun `a To of today or later is neither sent nor counted`() {
        val today = TransactionFilters.todayIso()
        assertEquals(0, TransactionFilters(to = "2999-01-01").count)
        assertTrue(TransactionFilters(to = "2999-01-01").query().isEmpty())
        assertEquals(0, TransactionFilters(to = today).count)
        assertEquals("2020-01-31T23:59:59Z", TransactionFilters(to = "2020-01-31").query()["to"])
        assertEquals(1, TransactionFilters(to = "2020-01-31").count)
    }

    @Test
    fun `typing a future From into the filter panel is clamped when applied`() = runTest(dispatcher) {
        val vm = CardExpensesViewModel(
            repository = FakeCardRepository(),
            today = { TODAY },
            viewer = { viewer() },
        )
        vm.start()
        advanceUntilIdle()

        vm.onEvent(CardEvent.SetTransactionFilters(TransactionFilters(from = "2999-01-01")))
        advanceUntilIdle()

        assertEquals("2025-08-01", vm.state.value.transactionFilters.from)
    }

    // -- leaving Import Statement ------------------------------------------------------

    @Test
    fun `leaving the import page drops the statement but keeps the currencies on offer`() {
        val import = ImportState(selected = setOf("a"), importedCurrency = "EUR", currenciesLoading = false)

        val left = import.leaving()

        assertTrue(left.selected.isEmpty())
        assertEquals("", left.importedCurrency)
        assertNull(left.result)
        assertEquals(import.currencies, left.currencies)
    }

    // -- activation pins the bank-chain company (`CardRegisterPage.jsx:477-481, 758-760`) --

    @Test
    fun `a card that already has a provider is pinned to the company owning its bank`() = runTest(dispatcher) {
        val repository = FakeCardRepository(
            cards = listOf(card("c1", status = CardStatus.Approved, providerId = "p1", issuer = "bank-1")),
        )
        val vm = CardExpensesViewModel(
            repository = repository,
            today = { TODAY },
            reference = {
                CardReference(
                    companies = listOf(
                        CardCompany("co-a", "A", listOf("bank-0")),
                        CardCompany("co-b", "B", listOf("bank-1")),
                    ),
                )
            },
            viewer = { viewer() },
        )
        vm.start()
        advanceUntilIdle()
        vm.onEvent(CardEvent.Open(CardDestination.CardRegister))
        advanceUntilIdle()

        vm.onEvent(CardEvent.OpenActivation("c1"))
        vm.onEvent(CardEvent.EditActivation(ActivationDraft("c1", CardType.Physical, "4000 1234 5678 9010")))
        vm.onEvent(CardEvent.SubmitActivation)
        advanceUntilIdle()

        assertEquals("co-b", repository.activations.single().companyId)
    }

    // -- provider ids (`CardProvidersEditor.jsx:36`) -----------------------------------

    @Test
    fun `a new provider gets a prov_ uuid id`() {
        val first = ProviderIds.mint()
        assertTrue(Regex("""prov_[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}""").matches(first), first)
        assertTrue(first != ProviderIds.mint())
        assertEquals("p", CardProvider(id = "p", name = "").id)
    }

    // -- an empty list arrives as {} (`lib/cardExpenses.js:22-35`) ----------------------

    @Test
    fun `a list answered as an empty object reads as empty`() {
        val serializer = ListOrEmpty(ReceiptDto.serializer())

        assertEquals(0, json.decodeFromString(serializer, "{}").size)
        assertEquals(1, json.decodeFromString(serializer, """[{"id":"r1"}]""").size)
        assertEquals(1, json.decodeFromString(ListSerializer(ReceiptDto.serializer()), """[{"id":"r1"}]""").size)
    }

    // -- fixtures ----------------------------------------------------------------------

    private fun viewer() = CardViewer(
        userId = "acc-1",
        departmentIdentifier = "department_accounts",
        designationIdentifier = "designation_production_accountant_accounts",
    )

    private fun card(
        id: String,
        currency: String = "GBP",
        status: CardStatus = CardStatus.Active,
        providerId: String? = null,
        issuer: String? = null,
    ) = ExpenseCard(
        id = id,
        holderId = "crew-1",
        holderName = "",
        departmentId = "d-art",
        companyId = null,
        status = status,
        type = CardType.Physical,
        lastFour = null,
        issuer = issuer,
        providerId = providerId,
        currency = currency,
        limit = 500.0,
        monthlyLimit = 500.0,
        balance = 500.0,
        receiptsCommit = 0.0,
        bsControlCode = "2100",
        proposedLimit = null,
        justification = null,
        requestedBy = "crew-1",
        rejectedBy = null,
        rejectionReason = null,
        createdAt = null,
    )

    private companion object {
        const val TODAY = 1_754_006_400_000L
    }
}
