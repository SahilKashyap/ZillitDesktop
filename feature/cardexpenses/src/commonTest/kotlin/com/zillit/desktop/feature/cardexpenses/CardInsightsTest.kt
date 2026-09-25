package com.zillit.desktop.feature.cardexpenses

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.config.AppConfig
import com.zillit.desktop.core.config.Environment
import com.zillit.desktop.core.config.ZillitService
import com.zillit.desktop.core.network.ApiClient
import com.zillit.desktop.core.network.HttpClientFactory
import com.zillit.desktop.feature.cardexpenses.data.CardRepositoryImpl
import com.zillit.desktop.feature.cardexpenses.domain.AlertFilter
import com.zillit.desktop.feature.cardexpenses.domain.AlertSeverity
import com.zillit.desktop.feature.cardexpenses.domain.AlertText
import com.zillit.desktop.feature.cardexpenses.domain.AnalyticsMath
import com.zillit.desktop.feature.cardexpenses.domain.CardAlert
import com.zillit.desktop.feature.cardexpenses.domain.CardAssignmentRule
import com.zillit.desktop.feature.cardexpenses.domain.CardBank
import com.zillit.desktop.feature.cardexpenses.domain.CardCompany
import com.zillit.desktop.feature.cardexpenses.domain.CardHubSource
import com.zillit.desktop.feature.cardexpenses.domain.CardPerson
import com.zillit.desktop.feature.cardexpenses.domain.CardProvider
import com.zillit.desktop.feature.cardexpenses.domain.CardSettings
import com.zillit.desktop.feature.cardexpenses.domain.CardTeamMember
import com.zillit.desktop.feature.cardexpenses.domain.CardTopUp
import com.zillit.desktop.feature.cardexpenses.domain.CardViewer
import com.zillit.desktop.feature.cardexpenses.domain.DepartmentCoordinator
import com.zillit.desktop.feature.cardexpenses.domain.FundRequestDraft
import com.zillit.desktop.feature.cardexpenses.domain.FundRouting
import com.zillit.desktop.feature.cardexpenses.domain.RequestCap
import com.zillit.desktop.feature.cardexpenses.domain.RequestCapBasis
import com.zillit.desktop.feature.cardexpenses.domain.RequestCapProblem
import com.zillit.desktop.feature.cardexpenses.domain.SettingsSection
import com.zillit.desktop.feature.cardexpenses.domain.TopUpBoard
import com.zillit.desktop.feature.cardexpenses.domain.ownerOf
import com.zillit.desktop.feature.cardexpenses.domain.problem
import com.zillit.desktop.feature.cardexpenses.ui.CardDestination
import com.zillit.desktop.feature.cardexpenses.ui.CardEvent
import com.zillit.desktop.feature.cardexpenses.ui.CardExpensesViewModel
import com.zillit.desktop.feature.cardexpenses.ui.InsightsEvent
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.content.TextContent
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Top-Up To Do, Smart Alerts, Analytics, Settings and Fund Requests against
 * the web (`TopUpToDoPage.jsx`, `SmartAlertsPage.jsx`, `AnalyticsPage.jsx`,
 * `SettingsPage.jsx`, `RequestFundsModal.jsx`): the wire bodies, the derived
 * rules, and the handlers that fire without a confirmation.
 */
class CardInsightsTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    // -- wire ----------------------------------------------------------------------

    /** Cash float top-ups ride the same route; a row with no type is a card's (`:141`). */
    @Test
    fun `the funding queue keeps card top-ups only`() = runTest {
        val (repo, _) = repository(
            """[{"id":"a","status":"pending"},{"id":"b","entity_type":"card"},{"id":"c","entity_type":"float"}]""",
        )
        val rows = assertIs<ZillitResult.Success<List<CardTopUp>>>(repo.topUps()).data
        assertEquals(listOf("a", "b"), rows.map { it.id })
    }

    @Test
    fun `a top-up row reads the fields the page prints`() = runTest {
        val (repo, _) = repository(
            """[{"id":"a","upload_type":"urgent","note":"Half now","receipt_merchant":"Shell",
               "receipt_amount":"42.10","bs_control_code":"2100","issued_amount":150,"entity_id":"e1"}]""",
        )
        val row = assertIs<ZillitResult.Success<List<CardTopUp>>>(repo.topUps()).data.single()
        assertTrue(row.urgent)
        assertEquals("Half now", row.note)
        assertEquals("Shell", row.receiptMerchant)
        assertEquals(42.10, row.receiptAmount)
        assertEquals("2100", row.bsControlCode)
        assertEquals(150.0, row.issuedAmount)
    }

    /** `completeTopup(t.id, {})` — an empty object, not no body (`:181`). */
    @Test
    fun `mark topped up sends an empty object`() = runTest {
        val (repo, calls) = repository()
        repo.completeTopUp("t1")
        assertTrue(calls.single().first.endsWith("/topups/t1/complete"))
        assertEquals(JsonObject(emptyMap()), calls.single().second)
    }

    /** `resolveAlert(id, {note})` — sent even empty (`SmartAlertsPage.jsx:144-148`). */
    @Test
    fun `resolving an alert always sends the note`() = runTest {
        val (repo, calls) = repository()
        repo.resolveAlert("a1", "")
        assertEquals("", calls.single().second["note"]?.jsonPrimitive?.content)
    }

    /** `sanitizeCardProviders` drops only empty rows; a bank without a name yet is kept. */
    @Test
    fun `a provider with a bank but no name is still saved`() = runTest {
        val (repo, calls) = repository()
        repo.updateSettings(
            SettingsSection.Providers,
            CardSettings(
                providers = listOf(
                    CardProvider("p1", "", bankId = "bank-1"),
                    CardProvider("p2", ""),
                ),
            ),
        )
        val rows = (calls.single().second["card_providers"] as JsonArray).map { it.jsonObject }
        assertEquals(listOf("p1"), rows.map { it["id"]?.jsonPrimitive?.content })
    }

    @Test
    fun `analytics reads the web's summary and holder figures`() = runTest {
        val (repo, calls) = repository(
            """{"summary":{"total_spend":600,"total_cards":4,"top_department_id":"d1",
               "avg_import_to_coded":2.5,"receipts_missing_pct":12,"auto_reconciled_pct":80},
               "by_holder":[{"user_id":"u1","card_limit":"500","balance":"200","count":3}]}""",
        )
        val analytics = assertIs<ZillitResult.Success<com.zillit.desktop.feature.cardexpenses.domain.CardAnalytics>>(
            repo.analytics(null, null),
        ).data
        assertFalse(calls.single().first.contains("start="), "no window, as the web")
        assertEquals(4, analytics.totalCards)
        assertEquals("d1", analytics.topDepartmentId)
        assertEquals(2.5, analytics.avgImportToCoded)
        assertEquals(80.0, analytics.autoReconciledPct)
        assertEquals(500.0, analytics.byHolder.single().cardLimit)
        assertEquals(200.0, analytics.byHolder.single().balance)
    }

    @Test
    fun `settings read the card rules the hub files under the tool`() = runTest {
        val (repo, _) = repository(
            """{"assignment_rules":[{"id":"r1","departments":"[\"d1\"]","nominal_codes":["2100"],
               "amount_min":"250","target_user_id":"u9","is_active":false,"priority":2}]}""",
        )
        val rule = assertIs<ZillitResult.Success<CardSettings>>(repo.settings()).data.assignmentRules.single()
        assertEquals(CardAssignmentRule("r1", listOf("d1"), listOf("2100"), "250", "u9", false, 2, true), rule)
    }

    // -- rules -----------------------------------------------------------------------

    @Test
    fun `pending top-ups are urgent first, then oldest first`() {
        val rows = listOf(
            topUp("new", createdAt = 30),
            topUp("old", createdAt = 10),
            topUp("urgent", createdAt = 50, uploadType = "urgent"),
            topUp("done", createdAt = 1, status = "completed"),
        )
        assertEquals(listOf("urgent", "old", "new"), TopUpBoard.pending(rows).map { it.id })
        assertEquals(listOf("done"), TopUpBoard.done(rows).map { it.id })
    }

    @Test
    fun `the over-limit explainer spells out the headroom`() {
        val alert = TopUpBoard.limitAlert(topUp("t", limit = 500.0, balance = 450.0), 100.0)
        assertEquals(550.0, alert.after)
        assertEquals(50.0, alert.over)
        assertEquals(50.0, alert.headroom)
        assertEquals(0.0, TopUpBoard.limitAlert(topUp("t", limit = 500.0, balance = 600.0), 1.0).headroom)
    }

    @Test
    fun `alert prose names people and hides the ids it cannot name`() {
        val people = listOf(CardPerson("6a2becfdf0a26d2a1b2c3d4e", "Ada Lovelace", designation = "Accountant"))
        val text = "Receipt by 6a2becfdf0a26d2a1b2c3d4e matches one by 0123456789abcdef01234567"
        assertEquals(
            "Receipt by Ada Lovelace (Accountant) matches one by an unknown user",
            AlertText.scrub(text, people, "an unknown user"),
        )
        assertTrue(AlertText.isIdLike("#6c3e22b"))
        assertFalse(AlertText.isIdLike("INV-204"))
    }

    @Test
    fun `the type chips filter by the engine's types`() {
        val alerts = listOf(
            alert("a", type = "Cross-Card Duplicate"),
            alert("b", type = "Spending Limit"),
            alert("c", status = CardAlert.RESOLVED),
        )
        assertEquals(listOf("a"), AlertFilter.Duplicate.apply(alerts).map { it.id })
        assertEquals(listOf("b"), AlertFilter.Velocity.apply(alerts).map { it.id })
        assertEquals(listOf("c"), AlertFilter.Resolved.apply(alerts).map { it.id })
    }

    @Test
    fun `the web's cash-flow and budget formulas`() {
        val weeks = AnalyticsMath.weeks(totalSpend = 1_000.0, postedTotal = 300.0)
        assertEquals(listOf(100.0, 140.0, 180.0, null), weeks.map { it.actual })
        assertEquals(listOf(250.0, 275.0, 325.0, 300.0), weeks.map { it.projected })
        val phases = AnalyticsMath.phases(totalSpend = 0.0, postedTotal = 0.0)
        assertEquals(listOf(12_400.0, 45_000.0, 8_000.0), phases.map { it.budget })
    }

    @Test
    fun `the request cap refuses a cap that would block everything`() {
        assertEquals(RequestCapProblem.Amount, RequestCap(enabled = true).problem())
        assertEquals(
            RequestCapProblem.Multiplier,
            RequestCap(enabled = true, basis = RequestCapBasis.WeeklySalary, maxAmount = 100.0, salaryMultiplier = 0.0)
                .problem(),
        )
        assertNull(RequestCap(enabled = false).problem())
        assertNull(RequestCap(enabled = true, maxAmount = 100.0).problem())
    }

    @Test
    fun `a bank's owner is the company that lists it`() {
        val companies = listOf(CardCompany("c1", "Prod Co", listOf("b1")), CardCompany("c2", "Other"))
        assertEquals("c1", companies.ownerOf("b1")?.id)
        assertNull(companies.ownerOf("b2"))
    }

    /** Pay into ⇄ Fund account fill each other only when the answer is unambiguous. */
    @Test
    fun `fund routing fills the other side only when one answer fits`() {
        val providers = listOf(
            CardProvider("p1", "Amex", bankId = "b1", custodianAccount = "2100"),
            CardProvider("p2", "Visa", bankId = "b1", custodianAccount = "2200"),
            CardProvider("p3", "Monzo", bankId = "b2", custodianAccount = "2300"),
        )
        val banks = listOf(CardBank("b1", "Barclays"), CardBank("b2", "Monzo"), CardBank("b3", "Unbound"))
        assertEquals(listOf("b1", "b2"), FundRouting.providerBanks(banks, providers).map { it.id })
        assertEquals("", FundRouting.pickBank(FundRequestDraft(), "b1", providers).fundAccount, "two codes: no guess")
        assertEquals("2300", FundRouting.pickBank(FundRequestDraft(), "b2", providers).fundAccount)
        assertEquals("b1", FundRouting.pickAccount(FundRequestDraft(), "2200", providers).bankId)
        val options = FundRouting.options(providers, typed = "9999", unnamed = "Unnamed")
        assertTrue(options.last().custom && options.last().code == "9999")
    }

    // -- handlers --------------------------------------------------------------------

    /** Mark Topped Up fires on the press — no confirmation (`:442`). */
    @Test
    fun `mark topped up needs no confirmation`() = runTest(dispatcher) {
        val repository = FakeCardRepository(topUps = listOf(topUp("t1", limit = 1_000.0, balance = 100.0)))
        val vm = viewModel(repository)
        vm.onEvent(CardEvent.Open(CardDestination.TopUpQueue))
        advanceUntilIdle()
        vm.onEvent(InsightsEvent.MarkToppedUp("t1"))
        advanceUntilIdle()
        assertNull(vm.state.value.prompt)
        assertTrue("completeTopUp" in repository.calls)
    }

    @Test
    fun `an over-limit top-up opens the explainer instead`() = runTest(dispatcher) {
        val repository = FakeCardRepository(topUps = listOf(topUp("t1", limit = 500.0, balance = 450.0)))
        val vm = viewModel(repository)
        vm.onEvent(CardEvent.Open(CardDestination.TopUpQueue))
        advanceUntilIdle()
        vm.onEvent(InsightsEvent.MarkToppedUp("t1"))
        advanceUntilIdle()
        assertNotNull(vm.state.value.insights.limitAlert)
        assertFalse("completeTopUp" in repository.calls)
    }

    @Test
    fun `the partial dialog opens with the row's own amount`() = runTest(dispatcher) {
        val vm = viewModel(FakeCardRepository(topUps = listOf(topUp("t1"))))
        vm.onEvent(CardEvent.Open(CardDestination.TopUpQueue))
        advanceUntilIdle()
        vm.onEvent(InsightsEvent.OpenPartial("t1"))
        assertEquals("300", vm.state.value.insights.partial?.amount)
    }

    /** Investigate patches the row in place, no reload (`SmartAlertsPage.jsx:157-165`). */
    @Test
    fun `investigating an alert patches the row`() = runTest(dispatcher) {
        val repository = FakeCardRepository(alerts = listOf(alert("a1")))
        val vm = viewModel(repository)
        vm.onEvent(CardEvent.Open(CardDestination.Alerts))
        advanceUntilIdle()
        vm.onEvent(InsightsEvent.Investigate("a1"))
        advanceUntilIdle()
        assertTrue("investigateAlert" in repository.calls)
        assertEquals(CardAlert.INVESTIGATING, vm.state.value.alerts.single().status)
    }

    @Test
    fun `a blank resolution note still resolves`() = runTest(dispatcher) {
        val repository = FakeCardRepository(alerts = listOf(alert("a1")))
        val vm = viewModel(repository)
        vm.onEvent(CardEvent.Open(CardDestination.Alerts))
        advanceUntilIdle()
        vm.onEvent(InsightsEvent.OpenResolve("a1"))
        vm.onEvent(InsightsEvent.ConfirmResolve)
        advanceUntilIdle()
        assertTrue("resolveAlert" in repository.calls)
        assertEquals(CardAlert.RESOLVED, vm.state.value.alerts.single().status)
        assertNull(vm.state.value.insights.resolve)
    }

    /** Coordinators flag each row, and nothing is sent (`SettingsPage.jsx:356-369`). */
    @Test
    fun `incomplete coordinator rows are flagged inline`() = runTest(dispatcher) {
        val repository = FakeCardRepository()
        val vm = viewModel(repository)
        vm.onEvent(CardEvent.Open(CardDestination.Settings))
        advanceUntilIdle()
        vm.onEvent(CardEvent.EditSettings(CardSettings(coordinators = listOf(DepartmentCoordinator("")))))
        vm.onEvent(CardEvent.SaveSettings(SettingsSection.Coordinators))
        advanceUntilIdle()
        assertEquals(setOf("0_dept", "0_users"), vm.state.value.insights.coordinatorErrors.keys)
        assertFalse("updateSettings" in repository.calls)
    }

    /** Add Member writes the team at once; there is no section Save (`persistTeam`). */
    @Test
    fun `adding a team member saves the team at once`() = runTest(dispatcher) {
        val repository = FakeCardRepository()
        val vm = viewModel(repository)
        vm.onEvent(CardEvent.Open(CardDestination.Settings))
        advanceUntilIdle()
        vm.onEvent(InsightsEvent.OpenMemberEditor(null))
        val editor = assertNotNull(vm.state.value.insights.memberEditor)
        vm.onEvent(InsightsEvent.EditMember(editor.copy(member = CardTeamMember("u1", postingLimit = null))))
        vm.onEvent(InsightsEvent.SaveMember)
        advanceUntilIdle()
        assertTrue("updateSettings" in repository.calls)
        assertEquals(listOf("u1"), vm.state.value.settings?.teamMembers?.map { it.userId })
        assertNull(vm.state.value.insights.memberEditor)
    }

    @Test
    fun `saving rules writes each through the hub`() = runTest(dispatcher) {
        val saved = mutableListOf<CardAssignmentRule>()
        val hub = object : CardHubSource {
            override suspend fun saveAssignmentRule(rule: CardAssignmentRule): ZillitResult<CardAssignmentRule> {
                saved += rule
                return ZillitResult.Success(rule.copy(id = "server-${saved.size}", persisted = true))
            }
        }
        val vm = CardExpensesViewModel(repository = FakeCardRepository(), hub = hub, viewer = { senior }).also {
            it.start()
            dispatcher.scheduler.advanceUntilIdle()
        }
        vm.onEvent(CardEvent.Open(CardDestination.Settings))
        advanceUntilIdle()
        vm.onEvent(InsightsEvent.AddRule)
        vm.onEvent(InsightsEvent.SaveRules)
        advanceUntilIdle()
        assertEquals(1, saved.size)
        assertFalse(saved.single().persisted)
        assertEquals(listOf("server-1"), vm.state.value.settings?.assignmentRules?.map { it.id })
    }

    // -- fixtures ----------------------------------------------------------------------

    private val senior = CardViewer(
        userId = "senior-1",
        departmentIdentifier = "department_accounts",
        designationIdentifier = "designation_production_accountant",
    )

    private fun viewModel(repository: FakeCardRepository) =
        CardExpensesViewModel(repository = repository, viewer = { senior }).also {
            it.start()
            dispatcher.scheduler.advanceUntilIdle()
        }

    private fun topUp(
        id: String,
        createdAt: Long = 1L,
        status: String = "pending",
        uploadType: String? = null,
        limit: Double? = null,
        balance: Double? = null,
    ) = CardTopUp(
        id = id,
        cardId = "c1",
        cardLastFour = "4821",
        holderId = "crew-1",
        holderName = "",
        amount = 300.0,
        currency = "GBP",
        method = "restore",
        status = status,
        createdAt = createdAt,
        cardLimit = limit,
        cardBalance = balance,
        uploadType = uploadType,
    )

    private fun alert(id: String, type: String? = null, status: String = CardAlert.ACTIVE) = CardAlert(
        id = id,
        title = "Alert $id",
        description = null,
        severity = AlertSeverity.High,
        status = status,
        type = type,
        savings = null,
        at = null,
    )

    private fun repository(answer: String = "{}"): Pair<CardRepositoryImpl, MutableList<Pair<String, JsonObject>>> {
        val calls = mutableListOf<Pair<String, JsonObject>>()
        val engine = MockEngine { request ->
            val body = (request.body as? TextContent)?.text?.let { Json.parseToJsonElement(it) as JsonObject }
            calls += request.url.toString() to (body ?: JsonObject(emptyMap()))
            respond(
                """{"status":1,"data":$answer}""",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val repo = CardRepositoryImpl(
            apiClient = ApiClient(
                httpClient = HttpClientFactory.create({ InsightsEngineFactory(engine) }),
                headerProvider = { _, _, _, _ -> emptyMap() },
            ),
            config = AppConfig(
                environment = Environment.Develop,
                services = ZillitService.entries.associateWith { "https://${it.name.lowercase()}.test" },
                realtime = emptyMap(),
            ),
        )
        return repo to calls
    }
}

private class InsightsEngineFactory(private val engine: MockEngine) : HttpClientEngineFactory<MockEngineConfig> {
    override fun create(block: MockEngineConfig.() -> Unit): HttpClientEngine = engine
}
