package com.zillit.desktop.feature.calls

import com.zillit.desktop.feature.calls.data.FirestoreCallStatusPlane
import com.zillit.desktop.feature.calls.data.PlaneEvent
import com.zillit.desktop.feature.calls.domain.CallSession
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Switch here on Line 2 over the Firestore rows, both ways: the flag this
 * desktop raises on its other devices' rows, and the flag another device
 * raises that sends this desktop out of the call.
 */
class TakeoverSignalTest {

    private val session = CallSession(callUuid = "u1", selfUserId = "me")

    private fun plane(engine: MockEngine) = FirestoreCallStatusPlane(
        httpClient = HttpClient(engine),
        projectId = "test-project",
        apiKey = "test-key",
        selfDeviceId = { "my-device" },
        pollMillis = 1_000,
    )

    private fun row(device: String, user: String, takeover: Boolean?, from: String, status: String = "in_call") =
        """{"name":"projects/test-project/databases/(default)/documents/calls/u1/call_users/$device",
            "fields":{
              "device_id":{"stringValue":"$device"},
              "user_id":{"stringValue":"$user"},
              "current_status":{"stringValue":"$status"},
              ${takeover?.let { "\"joined_from_other_device\":{\"booleanValue\":$it}," }.orEmpty()}
              "updated_from":{"stringValue":"$from"}}}"""

    private fun users(vararg rows: String) = """{"documents":[${rows.joinToString(",")}]}"""

    @Test
    fun `only a foreign flag raised after the first look reads as a takeover`() =
        runTest(StandardTestDispatcher()) {
            val polls = listOf(
                // A flag left up by an earlier switch: the baseline, not a takeover.
                users(row("phone", "me", true, "Android"), row("ann-phone", "ann", false, "Android")),
                users(row("phone", "me", false, "Android"), row("ann-phone", "ann", false, "Android")),
                // Our own Switch here, echoed back: ours, so not foreign.
                users(row("phone", "me", true, "Desktop"), row("ann-phone", "ann", false, "Android")),
                // The phone took it back.
                users(row("phone", "me", true, "Android"), row("ann-phone", "ann", false, "Android")),
            )
            var poll = 0
            val engine = MockEngine { request ->
                val path = request.url.encodedPath
                if (path.endsWith("/call_users")) {
                    respond(polls[minOf(poll++, polls.lastIndex)], HttpStatusCode.OK)
                } else {
                    respond("""{"fields":{}}""", HttpStatusCode.OK)
                }
            }

            // The mock answers on Ktor's own threads, so wait for the event
            // itself; which poll it came on proves the first three said nothing.
            val takeover = plane(engine).watch(session).filterIsInstance<PlaneEvent.Takeover>().first()

            assertEquals(PlaneEvent.Takeover("phone", "me"), takeover)
            assertEquals(4, poll)
        }

    @Test
    fun `Switch here flags only our rows that another device holds in the call`() = runTest {
        val patched = mutableListOf<Pair<String, String>>()
        val engine = MockEngine { request ->
            if (request.method == HttpMethod.Patch) {
                patched += request.url.encodedPath to (request.body as io.ktor.http.content.TextContent).text
                respond("{}", HttpStatusCode.OK)
            } else {
                respond(
                    users(
                        row("phone", "me", false, "Android"),
                        row("my-device", "me", false, "Desktop"),
                        row("tablet", "me", false, "iOS", status = "leave"),
                        row("ann-phone", "ann", false, "Android"),
                    ),
                    HttpStatusCode.OK,
                )
            }
        }

        plane(engine).claimFromOtherDevices("u1", "me")

        assertEquals(1, patched.size)
        val (path, body) = patched.single()
        assertTrue(path.endsWith("/documents/calls/u1/call_users/phone"), path)
        val fields = Json.parseToJsonElement(body).jsonObject["fields"]!!.jsonObject
        assertEquals("true", fields["joined_from_other_device"]!!.jsonObject["booleanValue"]!!.jsonPrimitive.content)
        assertEquals("Desktop", fields["updated_from"]!!.jsonObject["stringValue"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a running Agora call is read off its document and roster`() = runTest {
        val engine = MockEngine { request ->
            if (request.url.encodedPath.endsWith("/call_users")) {
                respond(
                    users(row("ann-phone", "ann", null, "Android"), row("my-device", "me", null, "Desktop", "ringing")),
                    HttpStatusCode.OK,
                )
            } else {
                respond(
                    """{"fields":{"current_status":{"stringValue":"call_started"},
                        "agora_channel_name":{"stringValue":"ch"},"agora_token":{"stringValue":"tok"}}}""",
                    HttpStatusCode.OK,
                )
            }
        }

        val join = plane(engine).readJoin("u1", "me")!!

        assertEquals(false, join.ended)
        assertEquals("ch", join.channelName)
        assertEquals("tok", join.token)
    }

    @Test
    fun `an Agora call with nobody in it reads as over`() = runTest {
        val engine = MockEngine { request ->
            if (request.url.encodedPath.endsWith("/call_users")) {
                respond(users(row("ann-phone", "ann", null, "Android", status = "leave")), HttpStatusCode.OK)
            } else {
                respond("""{"fields":{"current_status":{"stringValue":"call_started"}}}""", HttpStatusCode.OK)
            }
        }

        assertEquals(true, plane(engine).readJoin("u1", "me")?.ended)
    }
}
