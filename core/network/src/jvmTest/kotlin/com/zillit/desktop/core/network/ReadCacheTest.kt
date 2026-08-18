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
import java.net.ConnectException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.serializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The read cache around every GET: kept on a good answer, shown when the
 * server cannot be reached, never across productions, never for a refusal,
 * never for the reads that must stay live.
 */
class ReadCacheTest {

    private class MemoryCache : ReadCache {
        val rows = mutableMapOf<String, Pair<ReadScope, CachedRead>>()
        override suspend fun get(key: String): CachedRead? = rows[key]?.second
        override suspend fun put(key: String, scope: ReadScope, body: String, fetchedAt: Long) {
            rows[key] = scope to CachedRead(body, fetchedAt)
        }
    }

    private val cache = MemoryCache()
    private var scope: ReadScope? = ReadScope("u1", "p1")
    private var now = 1_000L

    private fun client(engine: MockEngine) = ApiClient(
        httpClient = HttpClient(engine) {
            expectSuccess = false
            install(ContentNegotiation) { json(HttpClientFactory.json) }
        },
        headerProvider = { _, _, _, _ -> emptyMap() },
        readCache = cache,
        readScope = { scope },
        nowMillis = { now },
    )

    private val jsonHeaders get() = headersOf(HttpHeaders.ContentType, "application/json")
    private val answering = MockEngine { respond("""{"status":200,"data":"hello"}""", headers = jsonHeaders) }
    private val unreachable = MockEngine { throw ConnectException("Failed to connect") }
    private val refusing = MockEngine { respondError(HttpStatusCode.Forbidden) }

    @Test
    fun `a good GET is kept and served again when the server cannot be reached`() = runTest {
        val online = client(answering).request(HttpVerb.Get, URL, String.serializer(), RequestModule.ProjectUser)
        assertEquals("hello", assertIs<ZillitResult.Success<String>>(online).data)
        assertEquals(1, cache.rows.size)

        val offline = client(unreachable).request(HttpVerb.Get, URL, String.serializer(), RequestModule.ProjectUser)
        assertEquals("hello", assertIs<ZillitResult.Success<String>>(offline).data, "the kept answer stands in")
    }

    @Test
    fun `another production never sees this one's answer`() = runTest {
        client(answering).request(HttpVerb.Get, URL, String.serializer(), RequestModule.ProjectUser)

        scope = ReadScope("u1", "p2")
        val other = client(unreachable).request(HttpVerb.Get, URL, String.serializer(), RequestModule.ProjectUser)
        assertIs<ZillitError.NoConnection>(assertIs<ZillitResult.Failure>(other).error)
    }

    @Test
    fun `a refusal is not offline and is never answered from the cache`() = runTest {
        client(answering).request(HttpVerb.Get, URL, String.serializer(), RequestModule.ProjectUser)

        val refused = client(refusing).request(HttpVerb.Get, URL, String.serializer(), RequestModule.ProjectUser)
        assertIs<ZillitError.Forbidden>(assertIs<ZillitResult.Failure>(refused).error)
    }

    @Test
    fun `the query string is part of what was asked`() = runTest {
        client(answering).request(
            HttpVerb.Get, URL, String.serializer(), RequestModule.ProjectUser,
            queryParameters = mapOf("status" to "open"),
        )
        val differentQuestion = client(unreachable).request(
            HttpVerb.Get, URL, String.serializer(), RequestModule.ProjectUser,
            queryParameters = mapOf("status" to "closed"),
        )
        assertIs<ZillitResult.Failure>(differentQuestion)
    }

    @Test
    fun `a read named with cacheAs is the same question whatever its URL says`() = runTest {
        client(answering).request(
            HttpVerb.Get, "$URL/1000/previous", String.serializer(), RequestModule.ProjectUser,
            options = CallOptions(cacheAs = "$URL/newest"),
        )
        val later = client(unreachable).request(
            HttpVerb.Get, "$URL/2000/previous", String.serializer(), RequestModule.ProjectUser,
            options = CallOptions(cacheAs = "$URL/newest"),
        )
        assertEquals("hello", assertIs<ZillitResult.Success<String>>(later).data, "a later 'now' finds the kept page")

        scope = ReadScope("u1", "p2")
        val other = client(unreachable).request(
            HttpVerb.Get, "$URL/2000/previous", String.serializer(), RequestModule.ProjectUser,
            options = CallOptions(cacheAs = "$URL/newest"),
        )
        assertIs<ZillitResult.Failure>(other, "still scoped to the production")
    }

    @Test
    fun `device and credential reads, and opted-out reads, stay live`() = runTest {
        client(answering).request(HttpVerb.Get, URL, String.serializer(), RequestModule.Device)
        client(answering).request(HttpVerb.Get, URL, String.serializer(), RequestModule.Configuration)
        client(answering).request(
            HttpVerb.Get, URL, String.serializer(), RequestModule.ProjectUser,
            options = CallOptions(readCache = false),
        )
        assertTrue(cache.rows.isEmpty(), "none of these may be kept")
    }

    @Test
    fun `nothing is kept before sign-in or without a store`() = runTest {
        scope = null
        client(answering).request(HttpVerb.Get, URL, String.serializer(), RequestModule.ProjectUser)
        assertTrue(cache.rows.isEmpty())
        assertNull(cache.rows[readKey(ReadScope("", ""), "", URL, emptyMap())])
    }

    @Test
    fun `writes are never kept`() = runTest {
        client(answering).request(HttpVerb.Post, URL, String.serializer(), RequestModule.ProjectUser)
        client(answering).request(HttpVerb.Put, URL, String.serializer(), RequestModule.ProjectUser)
        client(answering).request(HttpVerb.Delete, URL, String.serializer(), RequestModule.ProjectUser)
        assertTrue(cache.rows.isEmpty())
    }

    @Test
    fun `a POST that is really a read is kept only when it names itself`() = runTest {
        val name = "$URL/get-emails/inbox/1,2"
        client(answering).request(
            HttpVerb.Post, URL, String.serializer(), RequestModule.ProjectUser,
            options = CallOptions(cacheAs = name),
        )
        val offline = client(unreachable).request(
            HttpVerb.Post, URL, String.serializer(), RequestModule.ProjectUser,
            options = CallOptions(cacheAs = name),
        )
        assertEquals("hello", assertIs<ZillitResult.Success<String>>(offline).data)

        val put = client(answering).request(
            HttpVerb.Put, URL, String.serializer(), RequestModule.ProjectUser,
            options = CallOptions(cacheAs = "$URL/put"),
        )
        assertIs<ZillitResult.Success<String>>(put)
        assertEquals(1, cache.rows.size, "a PUT is a write whatever it calls itself")
    }

    private companion object {
        const val URL = "https://api.example.com/api/v2/thing"
    }
}
