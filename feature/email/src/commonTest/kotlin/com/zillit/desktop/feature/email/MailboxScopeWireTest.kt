package com.zillit.desktop.feature.email

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.config.AppConfig
import com.zillit.desktop.core.config.Environment
import com.zillit.desktop.core.config.ZillitService
import com.zillit.desktop.core.network.ApiClient
import com.zillit.desktop.core.network.HttpClientFactory
import com.zillit.desktop.feature.email.data.EmailRepositoryImpl
import com.zillit.desktop.feature.email.data.MAILBOX_FLAG
import com.zillit.desktop.feature.email.data.MailboxDirectoryImpl
import com.zillit.desktop.feature.email.data.body
import com.zillit.desktop.feature.email.data.flagBody
import com.zillit.desktop.feature.email.data.query
import com.zillit.desktop.feature.email.data.readCrewContact
import com.zillit.desktop.feature.email.domain.ActiveMailbox
import com.zillit.desktop.feature.email.domain.MailboxIdentity
import com.zillit.desktop.feature.email.domain.MailboxKind
import com.zillit.desktop.feature.email.domain.MailboxScope
import com.zillit.desktop.feature.email.domain.OutgoingEmail
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The shared Accounts mailbox on the wire — the web's `withMailboxScope`:
 * `use_project_account_mailbox=true` on every call while it is active, and
 * nothing at all while the personal one is, so a build without a switcher
 * makes the requests it always made.
 */
class MailboxScopeWireTest {

    private val accounts = MailboxScope { true }

    @Test
    fun `the personal mailbox adds nothing`() {
        assertTrue(MailboxScope.Personal.query().isEmpty())
        assertNull(MailboxScope.Personal.flagBody())
        val payload = buildJsonObject { put("subject", "Hi") }
        assertEquals(payload, MailboxScope.Personal.body(payload))
    }

    @Test
    fun `the shared mailbox rides the query and the body`() {
        assertEquals(mapOf(MAILBOX_FLAG to true), accounts.query())
        assertEquals(JsonPrimitive(true), accounts.flagBody()?.get(MAILBOX_FLAG))
        val scoped = accounts.body(buildJsonObject { put("subject", "Hi") })
        assertEquals("Hi", scoped["subject"]?.jsonPrimitive?.content, "the payload's own fields survive")
        assertEquals(JsonPrimitive(true), scoped[MAILBOX_FLAG])
    }

    @Test
    fun `the switch is read per call, not once at construction`() = runTest {
        // One repository serves both mailboxes; the user flips between them.
        val active = ActiveMailbox()
        val (repo, requests) = repository(active)

        repo.folders()
        active.switch(MailboxKind.Accounts, MailboxIdentity(MailboxKind.Accounts, "accounts@prod.com"))
        repo.folders()
        repo.send(OutgoingEmail(to = listOf("crew@prod.com"), subject = "Hi", body = "Hi"))
        repo.emptyTrash()

        assertNull(requests[0].url.parameters[MAILBOX_FLAG], "the personal call carried the flag")
        assertEquals("true", requests[1].url.parameters[MAILBOX_FLAG])
        assertEquals("true", requests[2].url.parameters[MAILBOX_FLAG])
        assertEquals(JsonPrimitive(true), requests[2].json()?.get(MAILBOX_FLAG), "a write carries it in the body")
        assertEquals(JsonPrimitive(true), requests[3].json()?.get(MAILBOX_FLAG), "a bodiless write grows one")
    }

    @Test
    fun `the From header follows the active mailbox`() = runTest {
        val active = ActiveMailbox()
        val shared = MailboxIdentity(MailboxKind.Accounts, "accounts@prod.com", name = "Accounts")
        active.switch(MailboxKind.Accounts, shared)
        val (repo, requests) = repository(active)

        repo.send(OutgoingEmail(to = listOf("crew@prod.com"), subject = "Hi", body = "Hi"))

        assertEquals("Accounts <accounts@prod.com>", requests.single().json()?.get("from")?.jsonPrimitive?.content)
    }

    @Test
    fun `the directory reads both mailboxes off their records`() = runTest {
        val directory = MailboxDirectoryImpl(
            apiClient = client { request ->
                if (request.url.encodedPath.endsWith("user/profile")) {
                    """{"mail_box_detail":{"email_address":"me@prod.com","conversation_view":"true"},
                        "bcc":[{"email_address":"log@prod.com"}]}""".enveloped()
                } else {
                    """{"accounts_mail_box_detail":{"email_address":"accounts@prod.com","name":"Accounts"}}"""
                        .enveloped()
                }
            },
            config = config(),
            projectId = { "p1" },
            userName = { "Sahil" },
        )

        val personal = (directory.personal() as ZillitResult.Success).data
        assertEquals("me@prod.com", personal?.address)
        assertEquals("Sahil", personal?.name, "a nameless mailbox is named after its owner")
        assertEquals(true, personal?.conversationView)
        assertEquals(listOf("log@prod.com"), personal?.bccPresets)

        val shared = (directory.accounts() as ZillitResult.Success).data
        assertEquals(MailboxKind.Accounts, shared?.kind)
        assertEquals("Accounts <accounts@prod.com>", shared?.fromHeader())
        assertNull(shared?.conversationView, "never set is not the same as off")
    }

    @Test
    fun `an empty accounts record means no shared mailbox here`() = runTest {
        val directory = MailboxDirectoryImpl(
            apiClient = client { """{"accounts_mail_box_detail":{}}""".enveloped() },
            config = config(),
            projectId = { "p1" },
        )
        assertNull((directory.accounts() as ZillitResult.Success).data)
    }

    @Test
    fun `crew rows become suggestions by the web's rules`() {
        fun row(json: String) = readCrewContact(Json.parseToJsonElement(json), viewerIsAdmin = false)
        fun adminRow(json: String) = readCrewContact(Json.parseToJsonElement(json), viewerIsAdmin = true)

        val accepted = row("""{"status":"accepted","full_name":"Aisha","designation_name":"1st AD",
            "mail_box_detail":{"email_address":"a@prod.com"}}""")
        assertEquals("a@prod.com", accepted?.address)
        assertEquals("Aisha", accepted?.name)
        assertEquals("1st AD", accepted?.subtitle)

        val pending = """{"status":"pending","mail_box_detail":{"email_address":"p@prod.com"}}"""
        assertNull(row(pending), "pending is hidden")
        assertNull(row("""{"status":"accepted"}"""), "no mailbox, nothing to address")
        val private =
            """{"status":"accepted","keep_name_private":true,"mail_box_detail":{"email_address":"q@prod.com"}}"""
        assertNull(row(private), "a private name is hidden from non-admins")
        assertEquals("q@prod.com", adminRow(private)?.address)
        val signing =
            """{"status":"pending","signing_required":true,"mail_box_detail":{"email_address":"p@prod.com"}}"""
        assertEquals("p@prod.com", adminRow(signing)?.address, "an admin sees the pending member still to sign")
    }

    // -- plumbing ------------------------------------------------------------

    private fun repository(scope: MailboxScope): Pair<EmailRepositoryImpl, MutableList<HttpRequestData>> {
        val requests = mutableListOf<HttpRequestData>()
        val api = client { request ->
            requests += request
            "[]".enveloped()
        }
        val repo = EmailRepositoryImpl(
            apiClient = api,
            config = config(),
            scope = scope,
            fromHeader = { (scope as? ActiveMailbox)?.fromHeader().orEmpty() },
        )
        return repo to requests
    }

    private fun client(answer: (HttpRequestData) -> String): ApiClient {
        val engine = MockEngine { request ->
            respond(answer(request), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        return ApiClient(
            httpClient = HttpClientFactory.create({ ScopeMockEngineFactory(engine) }),
            headerProvider = { _, _, _, _ -> emptyMap() },
        )
    }

    private fun config() = AppConfig(
        environment = Environment.Develop,
        services = ZillitService.entries.associateWith { "https://${it.name.lowercase()}.test" },
        realtime = emptyMap(),
    )

    private fun HttpRequestData.json(): JsonObject? =
        (body as? TextContent)?.text?.let { Json.parseToJsonElement(it) as? JsonObject }

    /** The service's `{status, data}` envelope around [this] payload. */
    private fun String.enveloped(): String = """{"status":1,"message":"ok","messageElements":[],"data":$this}"""
}

private class ScopeMockEngineFactory(private val engine: MockEngine) : HttpClientEngineFactory<MockEngineConfig> {
    override fun create(block: MockEngineConfig.() -> Unit): HttpClientEngine = engine
}
