package com.zillit.desktop.feature.calls

import com.zillit.desktop.feature.calls.data.livekit.CallBearer
import com.zillit.desktop.feature.calls.data.livekit.LiveKitApi
import com.zillit.desktop.feature.calls.data.livekit.signed
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * The calling backend's Bearer: the production's project token on every
 * request that has a production, healed once on a 401, never a device token
 * and never sent blank — and the socket-less actions saying who is acting.
 */
class CallBearerTest {

    private class Tokens(var current: String? = "t1", var renewed: String? = "t2") : CallBearer {
        val asked = mutableListOf<String>()
        val renewals = mutableListOf<Pair<String, String>>()
        override suspend fun tokenFor(projectId: String): String? = current.also { asked += projectId }
        override suspend fun renew(projectId: String, failed: String): String? = renewed.also {
            renewals += projectId to failed
        }
    }

    /** What [signed] sent, in order, and the status each attempt answered. */
    private class Wire(vararg statuses: Int) {
        private val answers = ArrayDeque(statuses.toList())
        val sent = mutableListOf<String?>()
        suspend fun send(token: String?): Int {
            sent += token
            return answers.removeFirstOrNull() ?: 200
        }
    }

    @Test
    fun `the production's token rides the request`() = runTest {
        val tokens = Tokens()
        val wire = Wire(200)
        tokens.signed("p1", { it }) { wire.send(it) }
        assertEquals(listOf<String?>("t1"), wire.sent)
        assertEquals(listOf("p1"), tokens.asked)
    }

    @Test
    fun `no production, no token mode, or no bearer at all send without one`() = runTest {
        val noProduction = Wire(200)
        Tokens().signed(" ", { it }) { noProduction.send(it) }
        assertEquals(listOf<String?>(null), noProduction.sent)

        val legacy = Wire(200)
        Tokens(current = null).signed("p1", { it }) { legacy.send(it) }
        assertEquals(listOf<String?>(null), legacy.sent)

        val none = Wire(200)
        (null as CallBearer?).signed("p1", { it }) { none.send(it) }
        assertEquals(listOf<String?>(null), none.sent)
    }

    @Test
    fun `a 401 renews the token and retries exactly once`() = runTest {
        val tokens = Tokens()
        val wire = Wire(401, 401)
        val answered = tokens.signed("p1", { it }) { wire.send(it) }
        assertEquals(listOf<String?>("t1", "t2"), wire.sent)
        assertEquals(listOf("p1" to "t1"), tokens.renewals)
        assertEquals(401, answered, "the retry's answer is the one reported")
    }

    @Test
    fun `a 401 without a token, or with nothing renewed, is not retried`() = runTest {
        val legacy = Wire(401)
        Tokens(current = null).signed("p1", { it }) { legacy.send(it) }
        assertEquals(1, legacy.sent.size)

        val dead = Wire(401)
        Tokens(renewed = null).signed("p1", { it }) { dead.send(it) }
        assertEquals(1, dead.sent.size)

        val same = Wire(401)
        Tokens(renewed = "t1").signed("p1", { it }) { same.send(it) }
        assertEquals(1, same.sent.size, "the same refused token must not be sent again")
    }

    // ── The actor in the body ───────────────────────────────────────────────

    private val http = LiveKitFakeHttp()
    private val api = LiveKitApi(http, "https://calls.test/api")

    private fun lastBody() = http.calls.last().second!!

    @Test
    fun `the socket-less actions say who is acting`() = runTest {
        api.ackRinging("c1", "p1", "u1")
        assertEquals("u1", lastBody()["userId"]?.jsonPrimitive?.content)
        api.hangup("c1", "p1", "u1")
        assertEquals("u1", lastBody()["userId"]?.jsonPrimitive?.content)
        api.hold("c1", on = true, projectId = "p1", userId = "u1")
        assertEquals("u1", lastBody()["userId"]?.jsonPrimitive?.content)
        api.hold("c1", on = false, projectId = "p1", userId = "u1")
        assertEquals("/v1/calls/c1/resume", http.paths().last())
        api.react("c1", "👍", "p1", "u1")
        assertEquals("u1", lastBody()["userId"]?.jsonPrimitive?.content)
        assertEquals("👍", lastBody()["emoji"]?.jsonPrimitive?.content)
    }

    @Test
    fun `a blank actor is left out rather than sent empty`() = runTest {
        api.hangup("c1", null, "")
        assertFalse(lastBody().containsKey("userId"))
        api.ackRinging("c1", null, null)
        assertNull(lastBody()["userId"])
    }
}
