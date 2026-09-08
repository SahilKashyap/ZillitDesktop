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
import kotlinx.serialization.builtins.serializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Token mode on the one path every REST call takes: which credential goes
 * out, and what a 401 becomes — one retry with a renewed token, never a
 * second rotation, and only the final answer reaches the sign-out signal.
 */
class ApiClientTokenAuthTest {

    private val headerProvider = object : RequestHeaderProvider {
        override suspend fun headersFor(
            module: RequestModule,
            bodyJson: String?,
            projectId: String?,
            userId: String?,
        ): Map<String, String> = mapOf("moduledata" to "legacy:${module.name}", "bodyhash" to "h")

        override suspend fun plainHeaders(): Map<String, String> = mapOf("timezone" to "Europe/London")
    }

    private class FakeAuthenticator(var token: String?, var renewed: String? = null) : RequestAuthenticator {
        val asked = mutableListOf<Pair<RequestModule, String?>>()
        val recovered = mutableListOf<String>()

        override suspend fun bearerFor(module: RequestModule, projectId: String?): String? {
            asked += module to projectId
            return token
        }

        override suspend fun recoverFromUnauthorized(
            module: RequestModule,
            projectId: String?,
            failedToken: String,
        ): String? {
            recovered += failedToken
            return renewed
        }
    }

    private fun client(engine: MockEngine, authenticator: RequestAuthenticator?, onUnauthorized: () -> Unit = {}) =
        ApiClient(
            httpClient = HttpClient(engine) {
                install(ContentNegotiation) { json(HttpClientFactory.json) }
                expectSuccess = false
            },
            headerProvider = headerProvider,
            onUnauthorized = onUnauthorized,
            readScope = { ReadScope(userId = "u", projectId = "p-open") },
            authenticator = authenticator,
        )

    private fun ok() = MockEngine { respond(OK_BODY, HttpStatusCode.OK, JSON) }

    @Test
    fun `token mode sends a Bearer and no moduledata`() = runTest {
        val engine = ok()
        val auth = FakeAuthenticator(token = "tok-1")

        client(engine, auth).request(HttpVerb.Get, URL, String.serializer(), RequestModule.ProjectUser)

        val sent = engine.requestHistory.single().headers
        assertEquals("Bearer tok-1", sent[HttpHeaders.Authorization])
        assertNull(sent["moduledata"], "the server reads one credential or the other, never both")
        assertNull(sent["bodyhash"])
        assertEquals("Europe/London", sent["timezone"])
        assertEquals(RequestModule.ProjectUser to "p-open", auth.asked.single(), "scoped to the open project")
    }

    @Test
    fun `a call the authenticator declines goes out with moduledata as before`() = runTest {
        val engine = ok()

        client(engine, FakeAuthenticator(token = null)).request(HttpVerb.Get, URL, String.serializer())

        val sent = engine.requestHistory.single().headers
        assertNull(sent[HttpHeaders.Authorization])
        assertEquals("legacy:Default", sent["moduledata"])
    }

    @Test
    fun `a call about another production asks for that production's token`() = runTest {
        val engine = ok()
        val auth = FakeAuthenticator(token = "tok-other")

        client(engine, auth).request(
            HttpVerb.Get, URL, String.serializer(), RequestModule.ProjectUser,
            options = CallOptions(projectId = "p-other", userId = "me-there"),
        )

        assertEquals(RequestModule.ProjectUser to "p-other", auth.asked.single())
    }

    @Test
    fun `a 401 is retried once with the renewed token, and the retry's answer is the one reported`() = runTest {
        var attempts = 0
        val engine = MockEngine {
            attempts++
            if (attempts == 1) respondError(HttpStatusCode.Unauthorized) else respond(OK_BODY, HttpStatusCode.OK, JSON)
        }
        val auth = FakeAuthenticator(token = "tok-old", renewed = "tok-new")
        var signedOut = 0

        val result = client(engine, auth) { signedOut++ }.request(HttpVerb.Get, URL, String.serializer())

        assertTrue(result.isSuccess, "$result")
        assertEquals(listOf("tok-old"), auth.recovered)
        assertEquals(2, engine.requestHistory.size)
        assertEquals("Bearer tok-new", engine.requestHistory.last().headers[HttpHeaders.Authorization])
        assertEquals(0, signedOut, "an expired token that healed is not a lost session")
    }

    @Test
    fun `a 401 the session cannot recover from is reported once, unretried`() = runTest {
        val engine = MockEngine { respondError(HttpStatusCode.Unauthorized) }
        val auth = FakeAuthenticator(token = "tok-old", renewed = null)
        var signedOut = 0

        val result = client(engine, auth) { signedOut++ }.request(HttpVerb.Get, URL, String.serializer())

        assertIs<ZillitError.Unauthorized>(result.errorOrNull())
        assertEquals(1, engine.requestHistory.size)
        assertEquals(1, signedOut)
    }

    private companion object {
        const val URL = "https://api.test/api/v2/thing"
        const val OK_BODY = """{"status":1,"message":"ok","data":"fine"}"""
        val JSON = headersOf(HttpHeaders.ContentType, "application/json")
    }
}
