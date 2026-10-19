package com.zillit.desktop.core.appupdate

import com.zillit.desktop.core.config.FirebaseConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The realtime stream against a faked Google.
 *
 * The contract these serve: **a publish is the only thing that may reach the
 * collector.** Keep-alives, framing punctuation, an echo of a version already
 * in hand and a refusal must all be silent, because every emission costs a
 * fetch and a flag re-read on every open desktop.
 */
class FirebaseFlagStreamTest {

    private val firebase = FirebaseConfig(
        projectId = "zillit-test",
        apiKey = "test-api-key",
        appId = "1:1234567890:android:abcdef",
    )

    // ---------------------------------------------------------------- framing

    @Test
    fun `an object is taken out of the array framing`() {
        val buffered = StringBuilder("""[{"latestTemplateVersionNumber":"42"}""")

        assertEquals("""{"latestTemplateVersionNumber":"42"}""", nextObject(buffered))
        assertEquals(0, buffered.length)
    }

    @Test
    fun `a half-arrived object waits for the rest of itself`() {
        val buffered = StringBuilder("""[{"latestTemplateVersion""")

        assertNull(nextObject(buffered))

        buffered.append("""Number":"42"},""")
        assertEquals("""{"latestTemplateVersionNumber":"42"}""", nextObject(buffered))
    }

    @Test
    fun `two objects in one flush are read one after the other`() {
        val buffered = StringBuilder("""[{"a":"1"},{"b":"2"}]""")

        assertEquals("""{"a":"1"}""", nextObject(buffered))
        assertEquals("""{"b":"2"}""", nextObject(buffered))
        assertNull(nextObject(buffered))
    }

    /** A `}` inside a string is not the end of the object. */
    @Test
    fun `a brace inside a value does not end the object`() {
        val buffered = StringBuilder("""{"error":"} not the end"}""")

        assertEquals("""{"error":"} not the end"}""", nextObject(buffered))
    }

    @Test
    fun `nested objects are taken whole`() {
        val buffered = StringBuilder("""{"error":{"code":403}}""")

        assertEquals("""{"error":{"code":403}}""", nextObject(buffered))
    }

    // --------------------------------------------------------------- messages

    @Test
    fun `a published version is an invalidation`() {
        assertEquals(
            Invalidation.Published(42),
            parseInvalidation("""{"latestTemplateVersionNumber":"42"}"""),
        )
    }

    /** The console's own tooling has been seen to send it unquoted. */
    @Test
    fun `an unquoted version is read too`() {
        assertEquals(
            Invalidation.Published(42),
            parseInvalidation("""{"latestTemplateVersionNumber":42}"""),
        )
    }

    @Test
    fun `an empty message is a keep-alive`() {
        assertNull(parseInvalidation("{}"))
    }

    @Test
    fun `a disabled feature is not retryable`() {
        assertEquals(Invalidation.Disabled, parseInvalidation("""{"featureDisabled":true}"""))
    }

    @Test
    fun `an error on the open stream reopens it`() {
        assertEquals(Invalidation.Broken, parseInvalidation("""{"error":{"code":500}}"""))
    }

    @Test
    fun `nonsense is not a publish`() {
        assertNull(parseInvalidation("not json at all"))
    }

    // ------------------------------------------------------------- addressing

    @Test
    fun `the project number comes out of the app id`() {
        assertEquals("1234567890", projectNumber("1:1234567890:android:abcdef"))
    }

    @Test
    fun `an app id of another shape switches the stream off`() {
        assertNull(projectNumber("nonsense"))
        assertNull(projectNumber("1:not-a-number:android:abcdef"))
    }

    @Test
    fun `the fetch response carries the template version`() {
        val payload = """{"entries":{"a":"1"},"state":"UPDATE","templateVersion":"42"}"""

        assertEquals(42, parseTemplateVersion(payload))
    }

    @Test
    fun `a fetch response without a version leaves it alone`() {
        assertNull(parseTemplateVersion("""{"entries":{"a":"1"},"state":"NO_CHANGE"}"""))
    }

    // --------------------------------------------------------------- the wire

    @Test
    fun `every published version reaches the collector in order`() = runTest {
        val stream = stream(lastKnown = 41) {
            respond(
                ByteReadChannel(
                    """[{"latestTemplateVersionNumber":"42"}""" + "\n" +
                        """,{"latestTemplateVersionNumber":"43"}]""",
                ),
                HttpStatusCode.OK,
            )
        }

        assertEquals(listOf(42L, 43L), stream.invalidations().take(2).toList())
    }

    /**
     * The reconnect echo.
     *
     * The server answers our opening `lastKnownVersionNumber` immediately, so
     * the first message after every reconnect is usually the version we are
     * already on. Forwarding it would mean a fetch per reconnect for ever.
     */
    @Test
    fun `a version already in hand is not forwarded`() = runTest {
        val stream = stream(lastKnown = 42) {
            respond(
                ByteReadChannel(
                    """[{"latestTemplateVersionNumber":"42"},{"latestTemplateVersionNumber":"43"}]""",
                ),
                HttpStatusCode.OK,
            )
        }

        assertEquals(listOf(43L), stream.invalidations().take(1).toList())
    }

    /**
     * The real thing, byte for byte.
     *
     * What the production project actually answered on 2026-10-19 when told we
     * held version 1: an array opened, then the object pretty-printed across
     * three lines, and no close. A reader that parsed a line at a time saw a
     * `{`, a key and a `}` and made nothing of any of them.
     */
    @Test
    fun `the shape the real server sends is read`() = runTest {
        val stream = stream(lastKnown = 1) {
            respond(
                ByteReadChannel("[{\n  \"latestTemplateVersionNumber\": \"1083\"\n}"),
                HttpStatusCode.OK,
            )
        }

        assertEquals(listOf(1083L), stream.invalidations().take(1).toList())
    }

    @Test
    fun `keep-alives are not publishes`() = runTest {
        val stream = stream(lastKnown = 41) {
            respond(
                ByteReadChannel("""[{},{},{"latestTemplateVersionNumber":"42"}]"""),
                HttpStatusCode.OK,
            )
        }

        assertEquals(listOf(42L), stream.invalidations().take(1).toList())
    }

    /** A project that will not have us is dropped, not retried for the life of the process. */
    @Test
    fun `a refusal ends the stream rather than looping`() = runTest {
        val engine = MockEngine { respond(ByteReadChannel(""), HttpStatusCode.Forbidden) }

        val emitted = streamOn(engine, lastKnown = 41).invalidations().toList()

        assertEquals(emptyList(), emitted)
        assertEquals(1, engine.requestHistory.size)
    }

    @Test
    fun `a disabled project ends the stream`() = runTest {
        val engine = MockEngine { respond(ByteReadChannel("""[{"featureDisabled":true}]"""), HttpStatusCode.OK) }

        assertEquals(emptyList(), streamOn(engine, lastKnown = 41).invalidations().toList())
    }

    /** A server that is merely unwell is worth asking again. */
    @Test
    fun `a 500 is retried`() = runTest {
        var calls = 0
        val engine = MockEngine {
            calls++
            if (calls == 1) {
                respond(ByteReadChannel(""), HttpStatusCode.InternalServerError)
            } else {
                respond(ByteReadChannel("""[{"latestTemplateVersionNumber":"42"}]"""), HttpStatusCode.OK)
            }
        }

        assertEquals(listOf(42L), streamOn(engine, lastKnown = 41).invalidations().take(1).toList())
        assertEquals(2, calls)
    }

    @Test
    fun `an unconfigured project is simply silent`() = runTest {
        val engine = MockEngine { respond(ByteReadChannel(""), HttpStatusCode.OK) }
        val stream = FirebaseFlagStream(
            httpClient = client(engine),
            firebase = null,
            instanceId = { "instance" },
            lastKnownVersion = { 0 },
        )

        assertEquals(emptyList(), stream.invalidations().toList())
        assertTrue(engine.requestHistory.isEmpty())
    }

    @Test
    fun `the request is addressed by project number and carries the version in hand`() = runTest {
        val engine = MockEngine {
            respond(ByteReadChannel("""[{"latestTemplateVersionNumber":"42"}]"""), HttpStatusCode.OK)
        }

        streamOn(engine, lastKnown = 41).invalidations().take(1).toList()

        val request = engine.requestHistory.single()
        assertTrue(
            request.url.toString().contains("/v1/projects/1234567890/namespaces/firebase:streamFetchInvalidations"),
            "addressed by project number, not project id: ${request.url}",
        )
        val body = (request.body as TextContent).text
        assertTrue(body.contains(""""lastKnownVersionNumber":"41""""), body)
    }

    private fun stream(lastKnown: Long, handler: MockRequestHandler) =
        streamOn(MockEngine(handler), lastKnown)

    private fun streamOn(engine: MockEngine, lastKnown: Long) = FirebaseFlagStream(
        httpClient = client(engine),
        firebase = firebase,
        instanceId = { "instance" },
        lastKnownVersion = { lastKnown },
    )

    private fun client(engine: MockEngine) = HttpClient(engine) { install(HttpTimeout) }
}
