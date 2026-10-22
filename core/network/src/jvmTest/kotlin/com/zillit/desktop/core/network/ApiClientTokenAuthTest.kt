package com.zillit.desktop.core.network

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
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

        override suspend fun bearerFor(module: RequestModule, projectId: String?, path: String): String? {
            asked += module to projectId
            return token
        }

        override suspend fun recoverFromUnauthorized(
            module: RequestModule,
            projectId: String?,
            path: String,
            failedToken: String,
        ): String? {
            recovered += failedToken
            return renewed
        }

        /**
         * Whether this route needs a production — set by the no-production
         * tests; the default keeps every other test's behaviour.
         */
        var refuses = false

        /**
         * The real rule's two halves, because the switch-window tests turn on
         * the second: a route that needs a production is refused only while
         * none is open (`TokenSessionManager.refusesWithNoProject`).
         */
        override suspend fun refusesWithNoProject(
            module: RequestModule,
            projectId: String?,
            verb: HttpVerb,
            path: String,
        ): Boolean = refuses && projectId.isNullOrBlank()
    }

    /** Remembers the scope each answer was filed under, which is the point of two tests below. */
    private class MemoryCache : ReadCache {
        val rows = mutableMapOf<String, ReadScope>()
        override suspend fun get(key: String): CachedRead? = null
        override suspend fun put(key: String, scope: ReadScope, body: String, fetchedAt: Long) {
            rows[key] = scope
        }
    }

    @Suppress("LongParameterList") // A fixture: each argument is one collaborator a test swaps.
    private fun client(
        engine: MockEngine,
        authenticator: RequestAuthenticator?,
        onUnauthorized: () -> Unit = {},
        readScope: () -> ReadScope? = { ReadScope(userId = "u", projectId = "p-open") },
        awaitProjectOpen: suspend () -> Boolean = { false },
        readCache: ReadCache? = null,
    ) = ApiClient(
        httpClient = HttpClient(engine) {
            install(ContentNegotiation) { json(HttpClientFactory.json) }
            expectSuccess = false
        },
        headerProvider = headerProvider,
        onUnauthorized = onUnauthorized,
        readCache = readCache,
        readScope = readScope,
        authenticator = authenticator,
        awaitProjectOpen = awaitProjectOpen,
    )

    private fun ok() = MockEngine { respond(OK_BODY, HttpStatusCode.OK, JSON) }

    @Test
    fun `a production's route with no production open never leaves the client`() = runTest {
        val engine = ok()
        val auth = FakeAuthenticator(token = "tok-1").also { it.refuses = true }
        var expired = 0

        val outcome = client(
            engine,
            auth,
            onUnauthorized = { expired++ },
            readScope = { ReadScope(userId = "u", projectId = "") },
        ).request(HttpVerb.Get, URL, String.serializer(), RequestModule.ProjectUser)

        assertTrue(engine.requestHistory.isEmpty(), "no request, no 401, no refresh")
        assertTrue(auth.asked.isEmpty(), "and no token minted for a call that was never sent")
        assertEquals(0, expired, "a refusal here is not a dead session")
        // The server's own answer, so every caller behaves as it does today.
        val error = (outcome as ZillitResult.Failure).error as ZillitError.Http
        assertEquals(401, error.status)
        assertEquals("libs_invalid_token_scope", error.serverMessage)
    }

    /**
     * The middle of a production switch: `onProjectOpened` runs with null
     * first and the new production second, and a call that lands between the
     * two used to be dropped where it stood.
     */
    @Test
    fun `a call caught between a switch's two legs waits and goes out under the production that opened`() = runTest {
        val engine = ok()
        val auth = FakeAuthenticator(token = "tok-next").also { it.refuses = true }
        var open: String? = null
        val cache = MemoryCache()

        val outcome = client(
            engine,
            auth,
            readScope = { ReadScope(userId = "u", projectId = open.orEmpty()) },
            awaitProjectOpen = {
                // What the switch's second leg does, from this call's point of view.
                open = "p-next"
                true
            },
            readCache = cache,
        ).request(HttpVerb.Get, URL, String.serializer(), RequestModule.ProjectUser)

        assertTrue(outcome.isSuccess, "$outcome")
        assertEquals(RequestModule.ProjectUser to "p-next", auth.asked.single(), "the production that opened")
        assertEquals("Bearer tok-next", engine.requestHistory.single().headers[HttpHeaders.Authorization])
        // The key is taken from the scope as it stands, so a wait that
        // happened after it would file this answer under the blank production
        // it was waiting out — and the next open would never find it.
        assertEquals(listOf("p-next"), cache.rows.values.map(ReadScope::projectId))
    }

    @Test
    fun `a call with no production coming is still refused once the wait is over`() = runTest {
        val engine = ok()
        val auth = FakeAuthenticator(token = "tok-1").also { it.refuses = true }
        var waits = 0

        val outcome = client(
            engine,
            auth,
            readScope = { ReadScope(userId = "u", projectId = "") },
            awaitProjectOpen = { waits++; false },
        ).request(HttpVerb.Get, URL, String.serializer(), RequestModule.ProjectUser)

        assertEquals(1, waits, "waited once, not once per retry")
        assertTrue(engine.requestHistory.isEmpty(), "still no request, no 401, no refresh")
        val error = (outcome as ZillitResult.Failure).error as ZillitError.Http
        assertEquals("libs_invalid_token_scope", error.serverMessage)
    }

    /**
     * A read that names no production is asking about whichever is open, so
     * answering it from the one that opens is no surprise. A write is not
     * like that: posted into the production being left, it would land in the
     * production being entered.
     */
    @Test
    fun `a write caught in the same window is refused rather than re-pointed`() = runTest {
        val engine = ok()
        val auth = FakeAuthenticator(token = "tok-next").also { it.refuses = true }
        var waits = 0

        val outcome = client(
            engine,
            auth,
            readScope = { ReadScope(userId = "u", projectId = "") },
            awaitProjectOpen = { waits++; true },
        ).request(HttpVerb.Post, URL, String.serializer(), RequestModule.ProjectUser)

        assertEquals(0, waits, "a write must not be held for a production it was not addressed to")
        assertTrue(engine.requestHistory.isEmpty())
        val error = (outcome as ZillitResult.Failure).error as ZillitError.Http
        assertEquals("libs_invalid_token_scope", error.serverMessage)
    }

    @Test
    fun `a device-level route does not wait for a production it does not need`() = runTest {
        val engine = ok()
        // `refuses = false` is the authenticator saying this route is reachable
        // with no production open — the production list's own reads.
        val auth = FakeAuthenticator(token = "tok-device")
        var waits = 0

        val outcome = client(
            engine,
            auth,
            readScope = { ReadScope(userId = "u", projectId = "") },
            awaitProjectOpen = { waits++; true },
        ).request(HttpVerb.Get, URL, String.serializer(), RequestModule.ProjectUser)

        assertTrue(outcome.isSuccess, "$outcome")
        assertEquals(0, waits, "the production list must not stall behind a production it is not opening")
    }

    @Test
    fun `a call naming its own production is never re-pointed at whatever opens next`() = runTest {
        val engine = ok()
        val auth = FakeAuthenticator(token = "tok-other").also { it.refuses = true }
        var waits = 0

        client(
            engine,
            auth,
            readScope = { ReadScope(userId = "u", projectId = "") },
            awaitProjectOpen = { waits++; true },
        ).request(
            HttpVerb.Get, URL, String.serializer(), RequestModule.ProjectUser,
            options = CallOptions(projectId = "p-other"),
        )

        assertEquals(0, waits)
        assertEquals(RequestModule.ProjectUser to "p-other", auth.asked.single())
    }

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

        val result = client(engine, auth, onUnauthorized = { signedOut++ })
            .request(HttpVerb.Get, URL, String.serializer())

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

        val result = client(engine, auth, onUnauthorized = { signedOut++ })
            .request(HttpVerb.Get, URL, String.serializer())

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
