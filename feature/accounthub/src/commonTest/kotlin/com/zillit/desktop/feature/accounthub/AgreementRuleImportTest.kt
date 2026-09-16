package com.zillit.desktop.feature.accounthub

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.config.AppConfig
import com.zillit.desktop.core.config.Environment
import com.zillit.desktop.core.config.ZillitService
import com.zillit.desktop.core.network.ApiClient
import com.zillit.desktop.core.network.HttpClientFactory
import com.zillit.desktop.core.permissions.ProjectPermissions
import com.zillit.desktop.core.permissions.ToolAccess
import com.zillit.desktop.feature.accounthub.data.AccountHubRepositoryImpl
import com.zillit.desktop.feature.accounthub.domain.AccountHubViewer
import com.zillit.desktop.feature.accounthub.domain.AgreementRuleImport
import com.zillit.desktop.feature.accounthub.domain.AgreementRuleRow
import com.zillit.desktop.feature.accounthub.domain.HubArea
import com.zillit.desktop.feature.accounthub.domain.PayDayKind
import com.zillit.desktop.feature.accounthub.domain.PayRateBasis
import com.zillit.desktop.feature.accounthub.domain.PayRateType
import com.zillit.desktop.feature.accounthub.domain.PayRuleKind
import com.zillit.desktop.feature.accounthub.domain.PayTrigger
import com.zillit.desktop.feature.accounthub.domain.UnionTerritories
import com.zillit.desktop.feature.accounthub.ui.AccountHubEvent
import com.zillit.desktop.feature.accounthub.ui.AccountHubViewModel
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.content.TextContent
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val SETTLE_TRIES = 200
private const val SETTLE_STEP_MILLIS = 5L

/**
 * A published agreement as the deal-memo service answers it: legacy rate
 * spellings, a penalty embedded in the OT block, an empty premiums block
 * serialised as `{}`, and a turnaround table.
 */
private const val AGREEMENT = """
{"status":1,"data":{"_identifier":"pact-tv","name":"PACT/BECTU TV Drama",
 "overtimes":{"rows":[
   {"id":"overtime","label":"OT after 10 hrs","multiplier":1.5,
    "triggers":[{"after":600,"day_type":null,"increment":null,"bdr_min":null}]},
   {"id":"overtime","label":"Camera OT","rate_type":"multiplier","rate_amount":"2",
    "triggers":[{"after":660,"camera":true}]},
   {"id":"meal_penalty","label":"Meal Penalty","flat":25,"triggers":[{"meal":true,"after":360}]}
 ]},
 "premiums":{},
 "turnaround":{"rows":[
   {"id":"bt","label":"Broken Turnaround","rate_type":"multiplier","rate_amount":1.5,"basis":"hour",
    "cap_type":"capped","cap_amount":120,"triggers":[{"less":660}]}
 ]},
 "allowances":{"rows":[{"id":"per_diem","label":"Per diem","flat":40}]}
}}
"""

private const val AGREEMENTS = """
{"status":1,"data":{"union":[{"_identifier":"pact-tv","name":"PACT/BECTU TV Drama","territory":"uk"}]}}
"""

/** The breakdown before the import: one overtime of the production's own. */
private const val BREAKDOWN = """
{"status":1,"data":{"value":{"overtimes":[{"id":"ot-1","label":"Existing OT","rate_type":"multiplier",
 "rate_amount":1.5,"basis":"hour","triggers":[{"after":600}]}],"premiums":[],"penalties":[]}}}
"""

/** The import of a union agreement's rules into the non-union breakdown. */
@OptIn(ExperimentalCoroutinesApi::class)
class AgreementRuleImportTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private val calls = MutableStateFlow<List<String>>(emptyList())
    private val payWrites = MutableStateFlow<List<JsonObject>>(emptyList())

    // -- classification ---------------------------------------------------------

    @Test
    fun `rows are classified by their trigger's signals, not their labels`() {
        fun trigger(row: AgreementRuleRow, kind: PayRuleKind = PayRuleKind.Overtimes) =
            AgreementRuleImport.classify(row, kind)

        // "Non-Camera OT" contains "camera"; only the flag says camera.
        assertEquals(
            PayTrigger(afterMinutes = 600),
            trigger(AgreementRuleRow(label = "Non-Camera OT", trigger = PayTrigger(afterMinutes = 600))),
        )
        assertEquals(
            PayTrigger(afterMinutes = 660, camera = true, incrementMinutes = 15),
            trigger(AgreementRuleRow(trigger = PayTrigger(afterMinutes = 660, camera = true))),
        )
        assertEquals(
            PayTrigger(mealCurtailed = true),
            trigger(AgreementRuleRow(trigger = PayTrigger(mealCurtailed = true))),
        )
        assertEquals(
            PayTrigger(meal = true, afterMinutes = 360),
            trigger(AgreementRuleRow(trigger = PayTrigger(meal = true, afterMinutes = 360))),
        )
        assertEquals(PayTrigger(lessMinutes = 660), trigger(AgreementRuleRow(trigger = PayTrigger(lessMinutes = 660))))
        assertEquals(
            PayTrigger(dayNumber = 7, consecutive = true, afterMinutes = 480),
            trigger(AgreementRuleRow(trigger = PayTrigger(dayNumber = 7, afterMinutes = 480))),
        )
        assertEquals(
            PayTrigger(dayNumber = 6, consecutive = true),
            trigger(AgreementRuleRow(trigger = PayTrigger(dayNumber = 6))),
        )
        assertEquals(
            PayTrigger(dayKinds = listOf(PayDayKind.BankHoliday)),
            trigger(AgreementRuleRow(trigger = PayTrigger(dayKinds = listOf(PayDayKind.BankHoliday)))),
        )
        assertEquals(
            PayTrigger(clock = true, afterMinutes = 0),
            trigger(AgreementRuleRow(trigger = PayTrigger(clock = true, afterMinutes = 0))),
        )
        assertEquals(
            PayTrigger(clock = true, beforeMinutes = 300),
            trigger(AgreementRuleRow(trigger = PayTrigger(clock = true, beforeMinutes = 300))),
        )
        // Unrecognised: the list's own default.
        assertEquals(PayTrigger(afterMinutes = 0), trigger(AgreementRuleRow()))
        assertEquals(PayTrigger(dayNumber = 6, consecutive = true), trigger(AgreementRuleRow(), PayRuleKind.Premiums))
        assertEquals(PayTrigger(meal = true, afterMinutes = 0), trigger(AgreementRuleRow(), PayRuleKind.Penalties))
    }

    @Test
    fun `penalties embedded in the OT block and turnarounds both land in penalties`() {
        val rules = AgreementRuleImport.project(
            overtimes = listOf(
                AgreementRuleRow(
                    id = "overtime", label = "OT", rateAmount = 1.5, trigger = PayTrigger(afterMinutes = 600),
                ),
                AgreementRuleRow(id = "rest_day_penalty", label = "Rest Day", rateAmount = 2.0),
            ),
            premiums = listOf(AgreementRuleRow(id = "night", label = "Night", rateAmount = 1.2)),
            turnarounds = listOf(AgreementRuleRow(id = "bt", label = "Broken Turnaround", rateAmount = 1.5)),
            salt = "t",
        )

        assertEquals(listOf("OT"), rules.overtimes.map { it.label })
        assertEquals(listOf("Night"), rules.premiums.map { it.label })
        assertEquals(listOf("Rest Day", "Broken Turnaround"), rules.penalties.map { it.label })
        assertEquals(4, rules.total)
        // Fresh, unique ids — a CBA publishes several rules under one id.
        assertEquals(4, (rules.overtimes + rules.premiums + rules.penalties).map { it.id }.toSet().size)
        assertTrue((rules.overtimes + rules.premiums + rules.penalties).none { it.isEnhancement })
        // A premium with no stated basis is per day; an overtime per hour.
        assertEquals(PayRateBasis.Day, rules.premiums.single().basis)
        assertEquals(PayRateBasis.Hour, rules.overtimes.single().basis)
    }

    @Test
    fun `the territory picker fails open`() {
        assertEquals(UnionTerritories.all, UnionTerritories.offered(null))
        assertEquals(UnionTerritories.all, UnionTerritories.offered(emptySet()))
        assertEquals(listOf("uk", "us"), UnionTerritories.offered(setOf("us", "uk")).map { it.id })
        // A territory already picked stays listed even when coverage says otherwise.
        assertEquals(listOf("uk", "fr"), UnionTerritories.offered(setOf("uk"), keep = "fr").map { it.id })
    }

    // -- the wire ----------------------------------------------------------------

    @Test
    fun `an agreement's rows are read with the engine's rate normalisation`() = runTest(dispatcher) {
        val repository = repository(::answer)

        val rules = (repository.unionAgreementRules("pact-tv") as ZillitResult.Success).data

        assertEquals(listOf("OT after 10 hrs", "Camera OT"), rules.overtimes.map { it.label })
        assertEquals(listOf("Meal Penalty", "Broken Turnaround"), rules.penalties.map { it.label })
        assertTrue(rules.premiums.isEmpty(), "an empty block serialised as {} reads as no rows")
        val ot = rules.overtimes[0]
        assertEquals(PayRateType.Multiplier, ot.rateType)
        assertEquals("1.5", ot.rateAmount, "the legacy `multiplier` spelling is the rate")
        assertEquals(PayTrigger(afterMinutes = 600), ot.triggers.single(), "padding stripped, the signal kept")
        assertEquals("2", rules.overtimes[1].rateAmount, "a numeric string is a number")
        val meal = rules.penalties[0]
        assertEquals(PayRateType.Flat, meal.rateType)
        assertEquals("25", meal.rateAmount)
        val turnaround = rules.penalties[1]
        assertTrue(turnaround.capped)
        assertEquals("120", turnaround.capAmount)
    }

    @Test
    fun `importing appends the rules and saves the breakdown at once`() = runTest(dispatcher) {
        val model = AccountHubViewModel(repository = repository(::answer), viewer = { viewer() })
        model.start()
        model.onEvent(AccountHubEvent.Open(HubArea.ProductionSetup))
        settle { model.state.value.setup.nonUnionPay.saved.overtimes.isNotEmpty() }

        model.onEvent(AccountHubEvent.OpenRuleImport)
        settle { model.state.value.setup.ruleImport?.covered != null }
        assertEquals(listOf("uk"), model.state.value.setup.ruleImport?.territories?.map { it.id })

        model.onEvent(AccountHubEvent.PickImportTerritory("uk"))
        settle { model.state.value.setup.ruleImport?.agreements?.isNotEmpty() == true }
        model.onEvent(AccountHubEvent.PickImportAgreement("pact-tv"))
        settle { model.state.value.setup.ruleImport?.rules != null }
        assertEquals(4, model.state.value.setup.ruleImport?.total)

        model.onEvent(AccountHubEvent.ConfirmRuleImport)
        settle { payWrites.value.isNotEmpty() && model.state.value.setup.ruleImport == null }

        val sent = payWrites.value.single()
        val overtimes = sent["overtimes"]!!.jsonArray.map { it.jsonObject }
        assertEquals(
            listOf("Existing OT", "OT after 10 hrs", "Camera OT"),
            overtimes.map { it["label"]?.jsonPrimitive?.content },
        )
        assertTrue(
            overtimes.drop(1).none { it["is_enhancement"]!!.jsonPrimitive.boolean },
            "non-union is multiplicative",
        )
        assertEquals(2, sent["penalties"]!!.jsonArray.size)
        assertNull(model.state.value.setup.ruleImport, "the dialog closes once the save lands")
        assertTrue(!model.state.value.setup.nonUnionPay.dirty, "the echo is the new baseline")
    }

    // -- harness --------------------------------------------------------------

    private fun answer(request: HttpRequestData): String {
        val path = request.url.encodedPath
        val method = request.method
        calls.update { it + "${method.value} $path" }
        return when {
            path.endsWith("/branches/covered-territories") -> """{"status":1,"data":["UK"]}"""
            path.endsWith("/deal-memo/agreements") -> AGREEMENTS
            path.endsWith("/deal-memo/agreements/pact-tv") -> AGREEMENT
            path.endsWith("/non-union-paybreakdown") && method == HttpMethod.Get -> BREAKDOWN
            path.endsWith("/non-union-paybreakdown") && method == HttpMethod.Patch -> {
                val body = (request.body as? TextContent)?.text.orEmpty()
                payWrites.update { it + Json.parseToJsonElement(body).jsonObject }
                """{"status":1,"data":{"value":$body}}"""
            }
            else -> """{"status":1,"data":{"value":[]}}"""
        }
    }

    private fun repository(answer: (HttpRequestData) -> String): AccountHubRepositoryImpl {
        val engine = MockEngine { request ->
            respond(answer(request), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        return AccountHubRepositoryImpl(
            apiClient = ApiClient(
                httpClient = HttpClientFactory.create({ ImportEngineFactory(engine) }),
                headerProvider = { _, _, _, _ -> emptyMap() },
            ),
            config = AppConfig(
                environment = Environment.Develop,
                services = ZillitService.entries.associateWith { "https://${it.name.lowercase()}.test" },
                realtime = emptyMap(),
            ),
        )
    }

    private fun viewer() = AccountHubViewer.from(
        ProjectPermissions(
            listOf(
                ToolAccess(
                    identifier = AccountHubViewer.TOOL_IDENTIFIER,
                    enabled = true,
                    canView = true,
                    canPost = true,
                    canDownload = true,
                ),
            ),
        ),
        "acc",
        isAccountant = true,
    )

    private suspend fun TestScope.settle(condition: () -> Boolean) {
        repeat(SETTLE_TRIES) {
            advanceUntilIdle()
            if (condition()) return
            withContext(Dispatchers.Default) { delay(SETTLE_STEP_MILLIS) }
        }
        advanceUntilIdle()
    }
}

private class ImportEngineFactory(private val engine: MockEngine) : HttpClientEngineFactory<MockEngineConfig> {
    override fun create(block: MockEngineConfig.() -> Unit): HttpClientEngine = engine
}
