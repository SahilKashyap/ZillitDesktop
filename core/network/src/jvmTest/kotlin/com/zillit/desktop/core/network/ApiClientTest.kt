package com.zillit.desktop.core.network

import com.zillit.desktop.core.common.ZillitError
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ApiClientTest {

    @Serializable
    private data class Project(val id: String, val name: String, val archived: Boolean = false)

    @Test
    fun `decodes the data field of a success envelope`() = runTest {
        val client = clientReturning("""{"status":200,"message":"ok","data":{"id":"p1","name":"Feature Film"}}""")

        val result = client.request(HttpVerb.Get, URL, Project.serializer())

        assertEquals(Project("p1", "Feature Film"), result.getOrNull())
    }

    @Test
    fun `a null for a non-nullable field falls back to the default rather than failing`() = runTest {
        // The Android client added coerceInputValues after single nulls
        // (userPresetId, location, callType) failed whole list parses, which
        // repositories caught and turned into silently empty screens.
        val client = clientReturning("""{"status":200,"data":{"id":"p1","name":"F","archived":null}}""")

        assertEquals(false, client.request(HttpVerb.Get, URL, Project.serializer()).getOrNull()?.archived)
    }

    @Test
    fun `unknown server fields are ignored`() = runTest {
        val client = clientReturning("""{"status":200,"data":{"id":"p1","name":"F","brand_new":{"x":1}}}""")

        assertTrue(client.request(HttpVerb.Get, URL, Project.serializer()).isSuccess)
    }

    @Test
    fun `a 200 with no status field is a success, not a refusal`() = runTest {
        // The invoices analytics pair answers `{data: …}` with no `status`.
        // Callers read `status == 1`, so the screen showed
        // "Something went wrong (200)" over a body full of real figures.
        val client = clientReturning("""{"data":{"id":"p1","name":"Feature Film"}}""")

        val result = client.envelope(HttpVerb.Get, URL)

        assertEquals(1, result.getOrNull()?.status)
        assertEquals(Project("p1", "Feature Film"), client.request(HttpVerb.Get, URL, Project.serializer()).getOrNull())
    }

    @Test
    fun `a stated refusal is still a refusal`() = runTest {
        val client = clientReturning("""{"status":0,"message":"no currencies configured","data":{}}""")

        assertEquals(0, client.envelope(HttpVerb.Get, URL).getOrNull()?.status)
    }

    @Test
    fun `401 maps to Unauthorized`() = runTest {
        val client = clientWith(MockEngine { respondError(HttpStatusCode.Unauthorized) })

        assertIs<ZillitError.Unauthorized>(client.request(HttpVerb.Get, URL, Project.serializer()).errorOrNull())
    }

    @Test
    fun `403 maps to Forbidden`() = runTest {
        val client = clientWith(MockEngine { respondError(HttpStatusCode.Forbidden) })

        assertIs<ZillitError.Forbidden>(client.request(HttpVerb.Get, URL, Project.serializer()).errorOrNull())
    }

    @Test
    fun `server message on an error response is surfaced to the user`() = runTest {
        val client = clientWith(
            MockEngine {
                respond(
                    content = """{"status":422,"message":"Project code already in use","data":{}}""",
                    status = HttpStatusCode.UnprocessableEntity,
                    headers = jsonHeaders,
                )
            },
        )

        assertEquals(
            "Project code already in use",
            client.request(HttpVerb.Post, URL, Project.serializer()).errorOrNull()?.userMessage,
        )
    }

    @Test
    fun `a malformed body fails as Serialization rather than crashing`() = runTest {
        val client = clientReturning("""{"status":200,"data":{"id":123,"name":[]}}""")

        assertIs<ZillitError.Serialization>(client.request(HttpVerb.Get, URL, Project.serializer()).errorOrNull())
    }

    @Test
    fun `a missing data field is reported rather than returning an empty object`() = runTest {
        val client = clientReturning("""{"status":200,"message":"done"}""")

        assertIs<ZillitError.Serialization>(client.request(HttpVerb.Get, URL, Project.serializer()).errorOrNull())
    }

    @Test
    fun `module headers are attached to the request`() = runTest {
        var seen = emptyMap<String, String>()
        val engine = MockEngine { request ->
            seen = request.headers.entries().associate { it.key to it.value.first() }
            respond(content = SIMPLE_BODY, headers = jsonHeaders)
        }
        val client = ApiClient(
            httpClient = httpClient(engine),
            headerProvider = { module, _, _, _ -> mapOf(ZillitHeaders.MODULE_DATA to module.name) },
        )

        client.request(HttpVerb.Get, URL, Project.serializer(), module = RequestModule.Chat)

        assertEquals(RequestModule.Chat.name, seen[ZillitHeaders.MODULE_DATA])
    }

    @Test
    fun `query parameters are appended and nulls dropped`() = runTest {
        var url = ""
        val engine = MockEngine { request ->
            url = request.url.toString()
            respond(content = SIMPLE_BODY, headers = jsonHeaders)
        }

        clientWith(engine).request(
            verb = HttpVerb.Get,
            url = URL,
            serializer = Project.serializer(),
            queryParameters = mapOf("page" to 2, "search" to null, "unit" to "u1"),
        )

        assertTrue(url.contains("page=2"), url)
        assertTrue(url.contains("unit=u1"), url)
        assertTrue(!url.contains("search"), "null query parameters must be dropped: $url")
    }

    @Test
    fun `each verb reaches the server unchanged`() = runTest {
        HttpVerb.entries.forEach { verb ->
            var method = ""
            val engine = MockEngine { request ->
                method = request.method.value
                respond(content = SIMPLE_BODY, headers = jsonHeaders)
            }

            clientWith(engine).request(verb, URL, Project.serializer())

            assertEquals(verb.name.uppercase(), method)
        }
    }

    // -- helpers ----------------------------------------------------------

    private val jsonHeaders get() = headersOf(HttpHeaders.ContentType, "application/json")

    private fun httpClient(engine: MockEngine) = HttpClient(engine) {
        expectSuccess = false
        install(ContentNegotiation) { json(HttpClientFactory.json) }
    }

    private fun clientReturning(body: String): ApiClient =
        clientWith(MockEngine { respond(content = body, headers = jsonHeaders) })

    private fun clientWith(engine: MockEngine, onUnauthorized: () -> Unit = {}): ApiClient =
        ApiClient(
            httpClient = httpClient(engine),
            headerProvider = { _, _, _, _ -> emptyMap() },
            onUnauthorized = onUnauthorized,
        )

    // -- what a failed call carries out -----------------------------------

    @Test
    fun `a failure carries its substitutions, not just its message`() = runTest {
        // The message is a key whose translation has a blank in it; the values
        // for those blanks ride the same envelope. Reading only the message
        // leaves the reader looking at `{{status}}`.
        val client = clientWith(
            MockEngine {
                respond(
                    content = """{"status":0,"message":"timecard_cannot_edit_status",""" +
                        """"messageElements":[{"search":"{{status}}","replacer":"paid"}],"data":{}}""",
                    status = HttpStatusCode.BadRequest,
                    headers = jsonHeaders,
                )
            },
        )

        val failure = client.envelope(HttpVerb.Get, URL).errorOrNull()

        val http = assertIs<ZillitError.Http>(failure)
        assertEquals("timecard_cannot_edit_status", http.serverMessage)
        assertEquals(1, http.messageElements.size)
        assertEquals("{{status}}", http.messageElements.first().search)
        assertEquals("paid", http.messageElements.first().replacer)
    }

    @Test
    fun `a numeric replacer does not cost the whole error`() = runTest {
        // The server sends counts unquoted. A strict decode would fail the
        // envelope and the user would get a generic "something went wrong".
        val client = clientWith(
            MockEngine {
                respond(
                    content = """{"status":0,"message":"too_many","messageElements":""" +
                        """[{"search":"{{count}}","replacer":7}],"data":{}}""",
                    status = HttpStatusCode.BadRequest,
                    headers = jsonHeaders,
                )
            },
        )

        val http = assertIs<ZillitError.Http>(client.envelope(HttpVerb.Get, URL).errorOrNull())

        assertEquals("too_many", http.serverMessage)
        assertEquals("7", http.messageElements.first().replacer)
    }

    @Test
    fun `a failure with no substitutions reports an empty list, not null`() = runTest {
        val client = clientWith(
            MockEngine {
                respond(
                    content = """{"status":0,"message":"plain_failure","messageElements":[],"data":{}}""",
                    status = HttpStatusCode.BadRequest,
                    headers = jsonHeaders,
                )
            },
        )

        val http = assertIs<ZillitError.Http>(client.envelope(HttpVerb.Get, URL).errorOrNull())

        assertEquals(emptyList(), http.messageElements)
    }

    // -- reporting a dead session -----------------------------------------

    @Test
    fun `a 401 is reported once, so the app can react to it`() = runTest {
        var reports = 0
        val client = clientWith(MockEngine { respondError(HttpStatusCode.Unauthorized) }) { reports++ }

        client.request(HttpVerb.Get, URL, Project.serializer())

        assertEquals(1, reports)
    }

    @Test
    fun `only 401 is reported`() = runTest {
        // A 403 is "not allowed to do this", not "we do not know you". Reporting
        // it would sign the user out for opening a screen they lack rights to.
        var reports = 0
        val client = clientWith(MockEngine { respondError(HttpStatusCode.Forbidden) }) { reports++ }

        client.request(HttpVerb.Get, URL, Project.serializer())

        assertEquals(0, reports)
    }

    @Test
    fun `the call that checks the session does not report its own rejection`() = runTest {
        // Otherwise the check raises the signal that asked for it, and the
        // question re-asks itself forever.
        var reports = 0
        val client = clientWith(MockEngine { respondError(HttpStatusCode.Unauthorized) }) { reports++ }

        val result = client.envelope(HttpVerb.Get, URL, options = CallOptions(reportUnauthorized = false))

        assertIs<ZillitError.Unauthorized>(result.errorOrNull())
        assertEquals(0, reports, "the check must not re-raise the signal it is answering")
    }

    // -- what a refusal says in the log ------------------------------------------------

    /**
     * A 406 is the server refusing the identity a call carried, and this
     * client already knows when it carried none — so the line that reports the
     * refusal says so. The field alternative was thirty-three lines of
     * `406 /api/v2/configuration` (2026-10-12), in a production log with no
     * INFO in it to cross-reference.
     */
    @Test
    fun `a 406 names whichever half of the identity was missing`() {
        assertEquals(
            " — moduledata named no production and no person",
            blankPairNote(406, ReadScope(userId = "", projectId = "")),
        )
        assertEquals(" — moduledata named no person", blankPairNote(406, ReadScope(userId = "", projectId = "p1")))
        assertEquals(" — moduledata named no production", blankPairNote(406, ReadScope(userId = "u1", projectId = "")))
        assertEquals(" — no session context to send", blankPairNote(406, null))
    }

    /**
     * Silence when there is nothing to add: a 406 with both halves present is
     * the server refusing something this client cannot see, and guessing at it
     * in the log would send the next reader after the wrong thing.
     */
    @Test
    fun `a 406 with a full identity, and every other status, adds nothing`() {
        assertEquals("", blankPairNote(406, ReadScope(userId = "u1", projectId = "p1")))
        assertEquals("", blankPairNote(401, ReadScope(userId = "", projectId = "")))
        assertEquals("", blankPairNote(403, ReadScope(userId = "", projectId = "")))
        assertEquals("", blankPairNote(503, null))
    }

    /**
     * `libs_invalid_token_scope` is the server refusing the credential, and
     * which credential went out is the whole of the explanation. Without it,
     * a week of those refusals (2026-10-21) read as a token minted for one
     * production while the request named another; they were project-scoped
     * reads firing with nothing open, on the device token.
     */
    @Test
    fun `a refusal names the credential it rode`() {
        assertEquals(" — on moduledata", credentialNote(401, token = null, projectId = "p1"))
        assertEquals(
            " — on the device token, which no production's route accepts",
            credentialNote(401, token = "tok", projectId = null),
        )
        assertEquals(
            " — on the device token, which no production's route accepts",
            credentialNote(401, token = "tok", projectId = ""),
        )
        assertEquals(
            " — on the token for production …06d432",
            credentialNote(403, token = "tok", projectId = "678108b934a30d92ea06d432"),
        )
    }

    /**
     * Only where the credential is the question. A 404 or a 500 rode a
     * credential the server accepted, and naming it there would put a reader
     * onto the one thing that was not wrong.
     */
    @Test
    fun `a status the credential does not explain adds nothing`() {
        assertEquals("", credentialNote(406, token = "tok", projectId = null))
        assertEquals("", credentialNote(404, token = null, projectId = "p1"))
        assertEquals("", credentialNote(500, token = "tok", projectId = "p1"))
    }

    /**
     * A refusal carrying no key did not come from this backend, and the line
     * now says so. 155 of these (two machines, 2026-10-14 to 10-19) sat beside
     * refusals that did carry one and read as the same fault.
     */
    @Test
    fun `a refusal with no key says the answer was not this API's`() {
        assertEquals(" — the answer could not be read at all", envelopeNote(null, null))
        assertEquals(" — an empty answer, with no envelope in it", envelopeNote(null, ""))
        assertEquals(" — 18 bytes that are not this API's envelope", envelopeNote(null, "<html>unauthorized"))
        assertEquals(" — 18 bytes that are not this API's envelope", envelopeNote("", "<html>unauthorized"))
    }

    /** Silent when the server named its own failure: the key is the diagnosis. */
    @Test
    fun `a refusal the server explained adds nothing`() {
        assertEquals("", envelopeNote("libs_invalid_token_scope", """{"message":"libs_invalid_token_scope"}"""))
        assertEquals("", envelopeNote("libs_module_data_invalid", null))
    }

    /** Short ids are not padded, and nothing longer than the tail is printed. */
    @Test
    fun `an id is named by its tail only`() {
        assertEquals("abc", "abc".idTail())
        assertEquals("abcdef", "abcdef".idTail())
        assertEquals("…bcdefg", "abcdefg".idTail())
    }

    private companion object {
        const val URL = "https://api.example.com/project"
        const val SIMPLE_BODY = """{"status":200,"data":{"id":"p1","name":"F"}}"""
    }
}
