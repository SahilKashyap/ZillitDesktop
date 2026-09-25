package com.zillit.desktop.feature.cardexpenses

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.config.AppConfig
import com.zillit.desktop.core.config.Environment
import com.zillit.desktop.core.config.ZillitService
import com.zillit.desktop.core.network.ApiClient
import com.zillit.desktop.core.network.HttpClientFactory
import com.zillit.desktop.feature.cardexpenses.data.CardRepositoryImpl
import com.zillit.desktop.feature.cardexpenses.domain.ApprovalTier
import com.zillit.desktop.feature.cardexpenses.domain.CapSource
import com.zillit.desktop.feature.cardexpenses.domain.CapVerdict
import com.zillit.desktop.feature.cardexpenses.domain.CardAction
import com.zillit.desktop.feature.cardexpenses.domain.CardApproval
import com.zillit.desktop.feature.cardexpenses.domain.CardBadge
import com.zillit.desktop.feature.cardexpenses.domain.CardChain
import com.zillit.desktop.feature.cardexpenses.domain.CardCurrency
import com.zillit.desktop.feature.cardexpenses.domain.CardDetailsEdit
import com.zillit.desktop.feature.cardexpenses.domain.CardMetadata
import com.zillit.desktop.feature.cardexpenses.domain.CardNumbers
import com.zillit.desktop.feature.cardexpenses.domain.CardPerson
import com.zillit.desktop.feature.cardexpenses.domain.CardReference
import com.zillit.desktop.feature.cardexpenses.domain.CardSearch
import com.zillit.desktop.feature.cardexpenses.domain.CardServerNote
import com.zillit.desktop.feature.cardexpenses.domain.CardStatus
import com.zillit.desktop.feature.cardexpenses.domain.CardType
import com.zillit.desktop.feature.cardexpenses.domain.CardViewer
import com.zillit.desktop.feature.cardexpenses.domain.ChainStepStatus
import com.zillit.desktop.feature.cardexpenses.domain.CompactAmount
import com.zillit.desktop.feature.cardexpenses.domain.DealRate
import com.zillit.desktop.feature.cardexpenses.domain.ExpenseCard
import com.zillit.desktop.feature.cardexpenses.domain.RequestCap
import com.zillit.desktop.feature.cardexpenses.domain.RequestCapBasis
import com.zillit.desktop.feature.cardexpenses.domain.RequestCapGuard
import com.zillit.desktop.feature.cardexpenses.domain.TierConfig
import com.zillit.desktop.feature.cardexpenses.domain.TierRule
import com.zillit.desktop.feature.cardexpenses.ui.CardDestination
import com.zillit.desktop.feature.cardexpenses.ui.CardEditDraft
import com.zillit.desktop.feature.cardexpenses.ui.CardEvent
import com.zillit.desktop.feature.cardexpenses.ui.CardExpensesViewModel
import com.zillit.desktop.feature.cardexpenses.ui.CardsEvent
import com.zillit.desktop.feature.cardexpenses.ui.CrewCardDraft
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.content.TextContent
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The register, the card detail and the Card tab against the web: the bodies
 * the card writes send, and the rules the tiles and forms are drawn from.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CardRegisterParityTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    // -- the wire ---------------------------------------------------------------

    /** A cardholder sets only these five (`UserCardsPage.jsx:202-208`); the rest is the accounts team's. */
    @Test
    fun `a crew request sends exactly the crew's keys`() = runTest {
        val (repo, sent) = repository()
        repo.cardAction(CardAction.CrewRequest("user-2", "dept-1", 750.0, " Recce fuel ", "GBP"))

        val body = sent.single()
        assertEquals(setOf("user_id", "department_id", "proposed_limit", "justification", "currency"), body.keys)
        assertEquals("Recce fuel", body.text("justification"))
    }

    /** The crew's re-submit returns the card to the accounts team, not to the chain. */
    @Test
    fun `a crew re-submit goes back to requested`() = runTest {
        val (repo, sent) = repository()
        repo.cardAction(CardAction.CrewEdit("card-1", 900.0, "More days", "EUR"))

        val body = sent.single()
        assertEquals(setOf("card_limit", "status", "justification", "currency"), body.keys)
        assertEquals("requested", body.text("status"))
    }

    /** Both the details edit and the control-code fix say who made them (`constants.js:142`). */
    @Test
    fun `details and control-code edits carry updated_by`() = runTest {
        val (repo, sent) = repository()
        repo.cardAction(
            CardAction.EditDetails(
                "card-1",
                CardDetailsEdit(1_000.0, 800.0, "GBP", "p1", "bank-1", "", "2100", "Unit spend"),
                updatedBy = "user-1",
            ),
        )
        repo.cardAction(CardAction.BsCode("card-1", " 2200 ", updatedBy = "user-1"))

        assertEquals("user-1", sent[0].text("updated_by"))
        assertEquals("pending", sent[0].text("status"))
        assertEquals(setOf("bs_control_code", "updated_by"), sent[1].keys)
        assertEquals("2200", sent[1].text("bs_control_code"))
    }

    /** The server's own message is what the toast says. */
    @Test
    fun `a write answers with the server's message`() = runTest {
        val (repo, _) = repository("""{"status":1,"message":"card_suspended_successfully","data":{}}""")
        val result = repo.cardAction(CardAction.Suspend("card-1"))
        val note = assertIs<ZillitResult.Success<CardServerNote>>(result).data
        assertEquals("card_suspended_successfully", note.key)
    }

    /** A `status: 0` inside a 200 is a refusal, with the server's reason. */
    @Test
    fun `a status-0 write is a failure`() = runTest {
        val (repo, _) = repository("""{"status":0,"message":"card_number_in_use","data":{}}""")
        val result = repo.cardAction(CardAction.AssignPhysical("card-1", "4000 1234 5678 9010"))
        val error = assertIs<ZillitError.Http>(assertIs<ZillitResult.Failure>(result).error)
        assertEquals("card_number_in_use", error.serverMessage)
    }

    // -- the rules --------------------------------------------------------------

    @Test
    fun `a live card on its virtual number only is digital active`() {
        val digital = card(CardStatus.Active).copy(digitalCardNumber = "4000123456789010")
        assertTrue(digital.isDigitalActive)
        assertFalse(digital.copy(physicalCardNumber = "4111111111111111").isDigitalActive)
        assertFalse(digital.copy(status = CardStatus.Suspended).isDigitalActive)
        assertEquals(CardBadge.DigitalActive, CardBadge.of(digital, totalTiers = 0, isAccountant = true))
    }

    /** `lib/cardSpend.js`: the server's spend when the row has it; a top-up breaks the derivation. */
    @Test
    fun `spent is the server's figure where it is given`() {
        val card = card(CardStatus.Active).copy(limit = 5_000.0, balance = 4_958.44)
        assertEquals(41.56, card.spent, 0.001)
        assertEquals(81.56, card.copy(serverSpent = 81.56).spent, 0.001)
        assertEquals(0.0, card.copy(serverSpent = 0.0).spent)
    }

    /** The holder the tile prints, and the card fields it prints — never the card's department id. */
    @Test
    fun `search matches the holder's department and designation, not the card's department id`() {
        val card = card(CardStatus.Active).copy(departmentId = "a1b2c3", bsControlCode = "2100", lastFour = "4821")
        val holder = CardPerson("user-2", "Ada Lovelace", "First Assistant Director", "Production", "d1")
        assertTrue(CardSearch.matches(card, "assistant", holder, null))
        assertTrue(CardSearch.matches(card, "production", holder, null))
        assertTrue(CardSearch.matches(card, "barclay", holder, "Barclaycard"))
        assertTrue(CardSearch.matches(card, "2100", holder, null))
        assertFalse(CardSearch.matches(card, "a1b2", holder, null))
    }

    @Test
    fun `the chain walk marks signed, next and waiting levels`() {
        val configs = listOf(
            TierConfig(
                scope = "all",
                departmentId = null,
                tiers = listOf(
                    ApprovalTier(1, listOf(TierRule("default", null, listOf("a")))),
                    ApprovalTier(2, listOf(TierRule("default", null, listOf("b")))),
                    ApprovalTier(3, listOf(TierRule("default", null, listOf("c")))),
                ),
            ),
        )
        val card = card(CardStatus.Pending).copy(approvals = listOf(CardApproval("a", 1, approvedAt = 7L)))
        val steps = CardChain.steps(card, configs)
        assertEquals(
            listOf(ChainStepStatus.Approved, ChainStepStatus.Current, ChainStepStatus.Waiting),
            steps.map { it.status },
        )
        assertEquals(7L, steps.first().approvedAt)
        assertEquals(listOf("b"), steps[1].approverIds)
        assertTrue(CardChain.needsApprovalLevel(card, emptyList()))
        assertFalse(CardChain.needsApprovalLevel(card, configs))
    }

    /** A flat cap blocks over it; a weekly cap with no deal falls back to the flat ceiling. */
    @Test
    fun `the request cap resolves and converts to the default`() {
        val reference = CardReference(defaultCurrency = "GBP", currencies = listOf(CardCurrency("EUR", 1.2)))
        val flat = RequestCap(enabled = true, maxAmount = 1_000.0)
        val verdict = RequestCapGuard.verdict(flat, null, "EUR", reference)
        assertEquals(CapVerdict.Enforced(1_000.0, CapSource.Max), verdict)
        assertTrue(RequestCapGuard.exceeds(verdict, 1_300.0, "EUR", reference))
        assertFalse(RequestCapGuard.exceeds(verdict, 1_200.0, "EUR", reference))

        val weekly = flat.copy(basis = RequestCapBasis.WeeklySalary, salaryMultiplier = 2.0)
        assertEquals(CapSource.Fallback, RequestCapGuard.resolve(weekly, null).source)
        assertEquals(1_600.0, RequestCapGuard.resolve(weekly, DealRate(800.0, "GBP")).amount)
        assertIs<CapVerdict.Unenforceable>(RequestCapGuard.verdict(flat, null, "USD", reference))
        assertEquals(CapVerdict.NoCap, RequestCapGuard.verdict(RequestCap(), null, "GBP", reference))
    }

    @Test
    fun `a mix of currencies converts to the default, one currency keeps its own`() {
        val reference = CardReference(defaultCurrency = "GBP", currencies = listOf(CardCurrency("EUR", 2.0)))
        val mixed = reference.total(listOf(100.0 to "GBP", 100.0 to "EUR", 10.0 to "USD"))
        assertEquals(160.0, mixed.amount, 0.001)
        assertEquals("GBP", mixed.currency)
        assertTrue(mixed.unrated)
        val single = reference.total(listOf(100.0 to "EUR", 50.0 to "EUR"))
        assertEquals(150.0, single.amount)
        assertEquals("EUR", single.currency)
        assertEquals("1.25K", CompactAmount.of(1_250.0))
        assertEquals("2.9M", CompactAmount.of(2_900_000.0))
    }

    @Test
    fun `a card number is sixteen digits in fours`() {
        val digits = CardNumbers.digits("4000-1234 5678 9010 99")
        assertEquals("4000123456789010", digits)
        assertEquals("4000 1234 5678 9010", CardNumbers.grouped(digits))
        assertEquals(12, CardNumbers.remaining("4000"))
        assertEquals("•••• •••• •••• 9010", CardNumbers.masked(digits))
    }

    /** The edit seeds from `monthly_limit`, and the balance moves against it (`CardRegisterPage.jsx:569-577`). */
    @Test
    fun `the edit form seeds from the monthly limit`() {
        val card = card(CardStatus.Pending).copy(limit = 0.0, monthlyLimit = 1_500.0, balance = 1_500.0)
        val draft = CardEditDraft.of(card)
        assertEquals("1500", draft.limit)
        assertEquals(1_500.0, draft.currentLimit)
        assertEquals(2_000.0, draft.copy(limit = "2000").newBalance)
    }

    // -- the handlers -----------------------------------------------------------

    /** Approve acts at once, as on the web — no confirmation between the click and the write. */
    @Test
    fun `the chain's approver approves at once`() = runTest(dispatcher) {
        val repository = FakeCardRepository(
            cards = listOf(card(CardStatus.Pending)),
            metadata = CardMetadata(
                tierConfigs = listOf(
                    TierConfig(
                        scope = "all",
                        departmentId = null,
                        tiers = listOf(ApprovalTier(1, listOf(TierRule("default", null, listOf("user-1"))))),
                    ),
                ),
            ),
        )
        val vm = viewModel(repository, accountant)
        vm.onEvent(CardEvent.Open(CardDestination.CardRegister))
        advanceUntilIdle()

        vm.onEvent(CardsEvent.Approve("c1"))
        advanceUntilIdle()

        assertTrue("approveCard" in repository.calls)
        assertNull(vm.state.value.prompt)
    }

    /** Suspend is an accountant's, on a live card only. */
    @Test
    fun `suspend is refused on a card that is not live`() = runTest(dispatcher) {
        val repository = FakeCardRepository(cards = listOf(card(CardStatus.Pending)))
        val vm = viewModel(repository, accountant)
        vm.onEvent(CardEvent.Open(CardDestination.CardRegister))
        advanceUntilIdle()

        vm.onEvent(CardsEvent.Suspend("c1"))
        advanceUntilIdle()

        assertTrue("suspendCard" !in repository.calls)
    }

    /** Assign Physical opens only on a digital-active card, and sends only sixteen digits. */
    @Test
    fun `assign physical needs a digital-active card and sixteen digits`() = runTest(dispatcher) {
        val repository = FakeCardRepository(
            cards = listOf(card(CardStatus.Active).copy(digitalCardNumber = "4000123456789010")),
        )
        val vm = viewModel(repository, accountant)
        vm.onEvent(CardEvent.Open(CardDestination.CardRegister))
        advanceUntilIdle()

        vm.onEvent(CardsEvent.AskAssignPhysical("c1"))
        vm.onEvent(CardsEvent.EditPhysicalNumber("4111 1111 1111"))
        vm.onEvent(CardsEvent.ConfirmAssignPhysical)
        advanceUntilIdle()
        assertTrue("assignPhysicalCard" !in repository.calls)

        vm.onEvent(CardsEvent.EditPhysicalNumber("4111 1111 1111 1111"))
        vm.onEvent(CardsEvent.ConfirmAssignPhysical)
        advanceUntilIdle()
        assertTrue("assignPhysicalCard" in repository.calls)
        assertNull(vm.state.value.cardsArea.assignPhysical)
    }

    /** A crew request over the cap is refused before anything is sent. */
    @Test
    fun `a crew request over the project cap is refused`() = runTest(dispatcher) {
        val repository = FakeCardRepository(
            metadata = CardMetadata(requestCap = RequestCap(enabled = true, maxAmount = 500.0)),
        )
        val vm = viewModel(repository, crew)
        vm.onEvent(CardEvent.Open(CardDestination.MyCards))
        advanceUntilIdle()

        vm.onEvent(CardsEvent.OpenCrewRequest)
        vm.onEvent(CardsEvent.EditCrewRequest(CrewCardDraft(limit = "900", currency = "GBP", justification = "Fuel")))
        vm.onEvent(CardsEvent.SubmitCrewRequest)
        advanceUntilIdle()
        assertTrue("requestCard" !in repository.calls)

        vm.onEvent(CardsEvent.EditCrewRequest(CrewCardDraft(limit = "400", currency = "GBP", justification = "Fuel")))
        vm.onEvent(CardsEvent.SubmitCrewRequest)
        advanceUntilIdle()
        assertTrue("requestCard" in repository.calls)
        assertNull(vm.state.value.cardsArea.crewRequest)
    }

    // -- helpers ----------------------------------------------------------------

    private val accountant = CardViewer(
        userId = "user-1",
        departmentIdentifier = "department_accounts",
        designationIdentifier = "designation_production_accountant",
    )

    private val crew = CardViewer(
        userId = "user-2",
        departmentIdentifier = "department_camera",
        designationIdentifier = null,
    )

    private fun viewModel(repository: FakeCardRepository, viewer: CardViewer) =
        CardExpensesViewModel(repository = repository, viewer = { viewer }).also {
            it.start()
            dispatcher.scheduler.advanceUntilIdle()
        }

    private fun card(status: CardStatus) = ExpenseCard(
        id = "c1",
        holderId = "crew-1",
        holderName = "",
        departmentId = "d-art",
        companyId = null,
        status = status,
        type = CardType.Physical,
        lastFour = null,
        issuer = null,
        providerId = null,
        currency = "GBP",
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

    private fun JsonObject.text(key: String): String? = this[key]?.jsonPrimitive?.content

    private fun repository(
        reply: String = """{"status":1,"data":{}}""",
    ): Pair<CardRepositoryImpl, MutableList<JsonObject>> {
        val sent = mutableListOf<JsonObject>()
        val engine = MockEngine { request: HttpRequestData ->
            (request.body as? TextContent)?.text?.let { sent += Json.parseToJsonElement(it) as JsonObject }
            respond(reply, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val repo = CardRepositoryImpl(
            apiClient = ApiClient(
                httpClient = HttpClientFactory.create({ RegisterMockEngineFactory(engine) }),
                headerProvider = { _, _, _, _ -> emptyMap() },
            ),
            config = AppConfig(
                environment = Environment.Develop,
                services = ZillitService.entries.associateWith { "https://${it.name.lowercase()}.test" },
                realtime = emptyMap(),
            ),
        )
        return repo to sent
    }
}

private class RegisterMockEngineFactory(private val engine: MockEngine) :
    HttpClientEngineFactory<MockEngineConfig> {
    override fun create(block: MockEngineConfig.() -> Unit): HttpClientEngine = engine
}
