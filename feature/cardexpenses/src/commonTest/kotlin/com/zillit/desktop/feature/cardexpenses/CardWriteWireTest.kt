package com.zillit.desktop.feature.cardexpenses

import com.zillit.desktop.core.config.AppConfig
import com.zillit.desktop.core.config.Environment
import com.zillit.desktop.core.config.ZillitService
import com.zillit.desktop.core.network.ApiClient
import com.zillit.desktop.core.network.HttpClientFactory
import com.zillit.desktop.feature.cardexpenses.data.CardRepositoryImpl
import com.zillit.desktop.feature.cardexpenses.domain.ApprovalOverrides
import com.zillit.desktop.feature.cardexpenses.domain.CardDetailsEdit
import com.zillit.desktop.feature.cardexpenses.domain.CardProvider
import com.zillit.desktop.feature.cardexpenses.domain.CardSettings
import com.zillit.desktop.feature.cardexpenses.domain.CardTeamMember
import com.zillit.desktop.feature.cardexpenses.domain.DepartmentCoordinator
import com.zillit.desktop.feature.cardexpenses.domain.DraftCardReceipt
import com.zillit.desktop.feature.cardexpenses.domain.NewCardRequest
import com.zillit.desktop.feature.cardexpenses.domain.ReceiptCategory
import com.zillit.desktop.feature.cardexpenses.domain.ReceiptCoding
import com.zillit.desktop.feature.cardexpenses.domain.SettingsSection
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
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The keys this service actually reads on a write.
 *
 * Every bug pinned here fails **silently**: the update allowlist drops a key
 * it does not recognise and answers `status: 1` regardless, so a card request
 * with no limit, a receipt batch with no coding and a settings page that saves
 * nothing all look exactly like success. There is no error to catch and no
 * response to inspect — only a wrong figure somebody finds a week later.
 *
 * Written against the web client, which is the authority on these names.
 */
class CardWriteWireTest {

    // -- cards ---------------------------------------------------------------

    /**
     * A request proposes a limit; it does not set one.
     *
     * `card_limit` is the **authorised** figure an accountant writes on
     * approval, and the create route does not accept it. Sending it produced a
     * card request carrying no limit at all.
     */
    @Test
    fun `a card request sends proposed_limit and justification`() = runTest {
        val (repo, sent) = repository()

        repo.requestCard(
            NewCardRequest(
                holderId = "user-1",
                proposedLimit = 2_500.0,
                currency = "GBP",
                departmentId = "dept-art",
                companyId = null,
                providerId = "provider-1",
                issuer = "Barclaycard",
                bsControlCode = "2100",
                justification = "Daily unit spend",
            ),
        )

        val body = sent.single()
        assertEquals(2_500.0, body["proposed_limit"]?.jsonPrimitive?.content?.toDouble())
        assertEquals("Daily unit spend", body["justification"]?.jsonPrimitive?.content)
        assertEquals("dept-art", body["department_id"]?.jsonPrimitive?.content)
        assertEquals("Barclaycard", body["card_issuer"]?.jsonPrimitive?.content)
        // The keys the route has no column for, and the two it owns itself.
        listOf("card_limit", "reason", "card_type", "status").forEach {
            assertFalse(it in body, "$it is not a create key")
        }
    }

    /**
     * An absent company is omitted; an explicitly cleared one is blanked.
     *
     * The server derives a company from the bank chain when the key is absent,
     * so omitting it and sending `""` mean different things — and the edit form
     * has to be able to clear a pin that was set.
     */
    @Test
    fun `an absent company is omitted on create and blanked on edit`() = runTest {
        val (repo, sent) = repository()

        repo.requestCard(
            NewCardRequest(
                holderId = "user-1",
                proposedLimit = 100.0,
                currency = null,
                departmentId = null,
                companyId = null,
                providerId = null,
                issuer = null,
                bsControlCode = null,
                justification = null,
            ),
        )
        assertFalse("company_id" in sent.single(), "an absent company is left out")

        sent.clear()
        repo.updateCardDetails("card-1", edit(companyId = ""))
        assertEquals("", sent.single()["company_id"]?.jsonPrimitive?.content)
    }

    /**
     * The full edit carries the resubmit, and the narrow one must not.
     *
     * `status: pending` is read as a resubmit and wipes the card's collected
     * approvals. That is right on a request under review and destructive on a
     * live card, which is the whole reason the control-code correction is a
     * separate call.
     */
    @Test
    fun `only the full edit resubmits the card`() = runTest {
        val (repo, sent) = repository()

        repo.updateCardDetails("card-1", edit())
        val full = sent.single()
        assertEquals("pending", full["status"]?.jsonPrimitive?.content)
        assertEquals(1_500.0, full["card_limit"]?.jsonPrimitive?.content?.toDouble())
        assertEquals(400.0, full["balance"]?.jsonPrimitive?.content?.toDouble())

        sent.clear()
        repo.updateBsControlCode("card-1", " 2100 ")
        val narrow = sent.single()
        assertEquals(setOf("bs_control_code"), narrow.keys)
        assertEquals("2100", narrow["bs_control_code"]?.jsonPrimitive?.content)
    }

    // -- receipts ------------------------------------------------------------

    /**
     * The batch upload's coding key is `cost_code`, not `nominal_code`.
     *
     * The create route and the coding routes disagree about the name, and the
     * create route drops the one it does not know — so a receipt uploaded with
     * its code already known arrived uncoded.
     */
    @Test
    fun `a receipt batch carries the card, the currency and cost_code`() = runTest {
        val (repo, sent) = repository()
        val card = card()

        repo.submitReceipts(
            card = card,
            receipts = listOf(
                DraftCardReceipt(
                    description = "Batteries",
                    amount = "42.50",
                    date = 1_754_000_000_000,
                    category = ReceiptCategory.Equipment,
                    urgent = true,
                    requestTopUp = true,
                    costCode = "4100",
                    attachmentKey = "card-expenses/abc/receipt.jpg",
                ),
            ),
        )

        val receipt = (sent.single()["receipts"] as JsonArray).single().jsonObject
        assertEquals("card-1", receipt["card_id"]?.jsonPrimitive?.content)
        assertEquals("GBP", receipt["currency"]?.jsonPrimitive?.content)
        assertEquals("4100", receipt["cost_code"]?.jsonPrimitive?.content)
        assertEquals("equipment", receipt["category"]?.jsonPrimitive?.content)
        assertEquals(42.5, receipt["amount"]?.jsonPrimitive?.content?.toDouble())
        assertEquals("true", receipt["is_urgent"]?.jsonPrimitive?.content)
        assertEquals("true", receipt["request_top_up"]?.jsonPrimitive?.content)
        assertFalse("nominal_code" in receipt, "the create route does not know nominal_code")
    }

    /**
     * Every receipt-coding write names the card the receipt belongs to.
     *
     * The service refuses one that does not, and all three of the coding
     * queue's buttons go through the same body for that reason.
     */
    @Test
    fun `all three coding writes carry the card context`() = runTest {
        val (repo, sent) = repository()
        val coding = ReceiptCoding(
            nominalCode = " 4100 ",
            episode = "2",
            codeDescription = "Consumables",
            cardId = "card-1",
            currency = "GBP",
        )

        repo.updateReceiptCoding("r1", coding)
        repo.submitReceiptForApproval("r1", coding)
        repo.approveAndSubmitReceipt("r1", coding)

        assertEquals(3, sent.size)
        sent.forEach { body ->
            assertEquals("4100", body["nominal_code"]?.jsonPrimitive?.content)
            assertEquals("card-1", body["card_id"]?.jsonPrimitive?.content)
            assertEquals("GBP", body["currency"]?.jsonPrimitive?.content)
        }
    }

    /** A receipt with no card sends an explicit null, not a missing key. */
    @Test
    fun `a receipt with no card sends null rather than omitting it`() = runTest {
        val (repo, sent) = repository()

        repo.updateReceiptCoding("r1", ReceiptCoding(nominalCode = "4100"))

        assertEquals(JsonNull, sent.single()["card_id"])
        assertEquals(JsonNull, sent.single()["currency"])
    }

    /** Sending a receipt on without coding sends no body at all. */
    @Test
    fun `submitting for approval without coding sends nothing`() = runTest {
        val (repo, sent) = repository()

        repo.submitReceiptForApproval("r1", coding = null)

        assertTrue(sent.isEmpty(), "an empty body is no body")
    }

    // -- statements ----------------------------------------------------------

    /**
     * A currency nobody stated is left out, not blanked.
     *
     * An empty string is a claim about the statement; omitting the key lets
     * the server apply the project default, which is what "I did not say"
     * means.
     */
    @Test
    fun `a statement import omits an unstated currency`() = runTest {
        val (repo, sent) = repository()

        repo.importStatement("card-expenses/abc/march.csv", currency = null)
        assertEquals(setOf("attachment"), sent.single().keys)

        sent.clear()
        repo.importStatement("card-expenses/abc/march.csv", currency = "EUR")
        assertEquals("EUR", sent.single()["currency"]?.jsonPrimitive?.content)
    }

    // -- settings ------------------------------------------------------------

    /**
     * Each section writes its own key and nothing else.
     *
     * A PATCH merges, so a section-sized body is what stops two accountants
     * with the page open from overwriting each other in a part of the document
     * neither of them touched.
     */
    @Test
    fun `each settings section sends only its own key`() = runTest {
        val (repo, sent) = repository()
        val settings = settings()

        val expected = mapOf(
            SettingsSection.Team to "team_members",
            SettingsSection.Coordinators to "department_coordinators",
            SettingsSection.Overrides to "approval_override",
            SettingsSection.Providers to "card_providers",
            SettingsSection.RequestCap to "request_cap",
        )
        expected.forEach { (section, key) ->
            sent.clear()
            repo.updateSettings(section, settings)
            assertEquals(setOf(key), sent.single().keys, "${section.label} writes one key")
        }
    }

    /**
     * The five keys an earlier build wrote are not columns on the row.
     *
     * `auto_match_enabled`, `auto_match_threshold`, `duplicate_detection`,
     * `personal_spend_detection` and `default_card_limit` were dropped by the
     * allowlist every time, and the page said "Settings saved" regardless.
     */
    @Test
    fun `the five invented settings keys are gone`() = runTest {
        val (repo, sent) = repository()

        SettingsSection.entries.forEach { repo.updateSettings(it, settings()) }

        val everyKey = sent.flatMap { it.keys }.toSet()
        listOf(
            "auto_match_enabled",
            "auto_match_threshold",
            "duplicate_detection",
            "personal_spend_detection",
            "default_card_limit",
            "coding_required",
        ).forEach { assertFalse(it in everyKey, "$it is not a settings column") }
    }

    /**
     * An unlimited poster is null and a blocked one is zero.
     *
     * Collapsing the two is the bug that silently takes posting away from
     * somebody who was meant to have it without a ceiling, so the key is
     * always present and carries whichever it is.
     */
    @Test
    fun `a posting limit tells unlimited from blocked`() = runTest {
        val (repo, sent) = repository()

        repo.updateSettings(
            SettingsSection.Team,
            settings().copy(
                teamMembers = listOf(
                    CardTeamMember(userId = "u1", postingLimit = null),
                    CardTeamMember(userId = "u2", postingLimit = 0.0),
                    CardTeamMember(userId = "u3", postingLimit = 500.0),
                ),
            ),
        )

        val members = (sent.single()["team_members"] as JsonArray).map { it.jsonObject }
        assertEquals(JsonNull, members[0]["posting_limit"])
        assertEquals(0.0, members[1]["posting_limit"]?.jsonPrimitive?.content?.toDouble())
        assertEquals(500.0, members[2]["posting_limit"]?.jsonPrimitive?.content?.toDouble())
    }

    /** A senior is unlimited and can override, whatever the row said. */
    @Test
    fun `a senior is normalised before it is sent`() = runTest {
        val (repo, sent) = repository()

        repo.updateSettings(
            SettingsSection.Team,
            settings().copy(
                teamMembers = listOf(
                    CardTeamMember(userId = "u1", postingLimit = 500.0, canOverride = false, isSenior = true),
                ),
            ),
        )

        val member = (sent.single()["team_members"] as JsonArray).single().jsonObject
        assertEquals(JsonNull, member["posting_limit"])
        assertEquals("true", member["can_override"]?.jsonPrimitive?.content)
    }

    /** Clearing the request ceiling sends null rather than dropping the key. */
    @Test
    fun `a cleared request cap is sent as null`() = runTest {
        val (repo, sent) = repository()

        repo.updateSettings(SettingsSection.RequestCap, settings().copy(requestCap = null))

        assertEquals(JsonNull, sent.single()["request_cap"])
    }

    /** A provider with no name is dropped rather than saved blank. */
    @Test
    fun `an unnamed provider is not saved`() = runTest {
        val (repo, sent) = repository()

        repo.updateSettings(
            SettingsSection.Providers,
            settings().copy(
                providers = listOf(CardProvider("provider-1", "Barclaycard"), CardProvider("provider-2", "  ")),
            ),
        )

        val providers = (sent.single()["card_providers"] as JsonArray).map { it.jsonObject }
        assertEquals(1, providers.size)
        assertEquals("Barclaycard", providers.single()["name"]?.jsonPrimitive?.content)
    }

    // -- transactions --------------------------------------------------------

    @Test
    fun `a bulk delete sends the ids the server names them by`() = runTest {
        val (repo, sent) = repository()

        repo.bulkDeleteTransactions(listOf("t1", "t2"))

        val ids = (sent.single()["transactionIds"] as JsonArray).map { it.jsonPrimitive.content }
        assertEquals(listOf("t1", "t2"), ids)
    }

    /** A delete carries no body, so nothing is recorded for it. */
    @Test
    fun `a card delete sends no body`() = runTest {
        val (repo, sent) = repository()

        repo.deleteCard("card-1")

        assertTrue(sent.isEmpty())
        assertNull(sent.firstOrNull())
    }

    // -- fixtures ------------------------------------------------------------

    private fun edit(companyId: String = "company-1") = CardDetailsEdit(
        limit = 1_500.0,
        balance = 400.0,
        currency = "GBP",
        providerId = "provider-1",
        issuer = "Barclaycard",
        companyId = companyId,
        bsControlCode = "2100",
        justification = "Daily unit spend",
    )

    private fun settings() = CardSettings(
        teamMembers = listOf(CardTeamMember(userId = "u1", postingLimit = 500.0)),
        coordinators = listOf(DepartmentCoordinator("dept-art", listOf("u2"), codingRequired = true)),
        overrides = ApprovalOverrides(overrideReceipts = true),
        providers = listOf(CardProvider("provider-1", "Barclaycard")),
        requestCap = 5_000.0,
    )

    private fun card() = com.zillit.desktop.feature.cardexpenses.domain.ExpenseCard(
        id = "card-1",
        holderId = "user-1",
        holderName = "Ada Lovelace",
        departmentId = "dept-art",
        companyId = null,
        status = com.zillit.desktop.feature.cardexpenses.domain.CardStatus.Active,
        type = com.zillit.desktop.feature.cardexpenses.domain.CardType.Physical,
        lastFour = "4821",
        issuer = "Barclaycard",
        providerId = "provider-1",
        currency = "GBP",
        limit = 2_000.0,
        monthlyLimit = null,
        balance = 1_240.0,
        receiptsCommit = 380.0,
        bsControlCode = "2100",
        proposedLimit = 2_000.0,
        justification = "Daily unit spend",
        requestedBy = "user-1",
        rejectedBy = null,
        rejectionReason = null,
        createdAt = null,
    )

    private fun repository(): Pair<CardRepositoryImpl, MutableList<JsonObject>> {
        val sent = mutableListOf<JsonObject>()
        val engine = MockEngine { request: HttpRequestData ->
            (request.body as? TextContent)?.text?.let { sent += Json.parseToJsonElement(it) as JsonObject }
            respond(
                """{"status":1,"data":{}}""",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val repo = CardRepositoryImpl(
            apiClient = ApiClient(
                httpClient = HttpClientFactory.create({ CardMockEngineFactory(engine) }),
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

private class CardMockEngineFactory(private val engine: MockEngine) :
    HttpClientEngineFactory<MockEngineConfig> {
    override fun create(block: MockEngineConfig.() -> Unit): HttpClientEngine = engine
}
