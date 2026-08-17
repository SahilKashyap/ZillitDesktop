package com.zillit.desktop.core.remoteconfig

import com.zillit.desktop.core.config.AppConfig
import com.zillit.desktop.core.config.Environment
import com.zillit.desktop.core.config.ZillitService
import com.zillit.desktop.core.network.ApiClient
import com.zillit.desktop.core.network.HttpClientFactory
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The lazy fetch behind [RemoteConfigRepository.current].
 *
 * The at-open refresh failing used to strand the whole session without
 * storage credentials — "attachments are unavailable" until an app restart,
 * reported from the field as "cannot send media, cannot send email". These
 * pin the healing behaviour: a miss retries on next demand, a success is
 * never refetched, and racing callers share one request.
 */
class CurrentCredentialsTest {

    private var served = ""
    private var requests = 0

    private fun repository(): RemoteConfigRepositoryImpl {
        val engine = MockEngine {
            requests++
            if (served.isEmpty()) {
                respondError(HttpStatusCode.InternalServerError)
            } else {
                respond(served, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            }
        }
        return RemoteConfigRepositoryImpl(
            apiClient = ApiClient(
                // The production factory's JSON plugin, over the mock engine —
                // a bare HttpClient cannot decode the envelope at all.
                httpClient = HttpClient(engine) {
                    install(ContentNegotiation) { json(HttpClientFactory.json) }
                },
                headerProvider = { _, _, _ -> emptyMap() },
            ),
            config = AppConfig(
                environment = Environment.Develop,
                services = mapOf(ZillitService.Core to "https://example.test"),
                realtime = emptyMap(),
            ),
            decrypt = SecretDecryptor { com.zillit.desktop.core.common.ZillitResult.Success(it) },
        )
    }

    private val payload =
        """{"status":200,"message":"ok","data":{"aws_access_key":"AK","aws_secret_key":"SK"}}"""

    @Test
    fun `a failed first fetch heals on the next ask`() = runTest {
        val repo = repository()

        assertNull(repo.current(), "server down: nothing to hand out")

        served = payload
        val healed = repo.current()
        assertNotNull(healed, "the next demand should refetch")
        assertEquals("AK", healed.awsAccessKey)
    }

    @Test
    fun `a success is served from memory, not refetched`() = runTest {
        served = payload
        val repo = repository()

        repo.current()
        val before = requests
        repo.current()
        repo.current()

        assertEquals(before, requests, "the bundle arrived once; asking again is free")
    }

    @Test
    fun `racing callers share one request`() = runTest {
        served = payload
        val repo = repository()

        val results = listOf(
            async { repo.current() },
            async { repo.current() },
            async { repo.current() },
        ).awaitAll()

        assertEquals(1, requests, "single-flight: one fetch serves every racer")
        results.forEach { assertNotNull(it) }
    }

    @Test
    fun `clear forgets, and the next ask fetches again`() = runTest {
        served = payload
        val repo = repository()
        repo.current()

        repo.clear()
        val before = requests
        assertNotNull(repo.current())
        assertEquals(before + 1, requests)
    }
}
