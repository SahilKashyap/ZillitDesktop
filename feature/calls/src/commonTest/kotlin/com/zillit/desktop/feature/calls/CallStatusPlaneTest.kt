package com.zillit.desktop.feature.calls

import com.zillit.desktop.feature.calls.data.FirestoreCallStatusPlane
import com.zillit.desktop.feature.calls.data.PlaneEvent
import com.zillit.desktop.feature.calls.domain.CallSession
import com.zillit.desktop.feature.calls.domain.CallStatus
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The plane against a mock Firestore: what goes on the wire when we announce,
 * and what comes back off it when the roster moves. Shapes are the REST API's
 * typed-value encoding — `{fields:{x:{stringValue:"…"}}}` — not the SDK's.
 */
class CallStatusPlaneTest {

    private val session = CallSession(callUuid = "u1", selfUserId = "me")

    private fun plane(engine: MockEngine) = FirestoreCallStatusPlane(
        httpClient = HttpClient(engine),
        projectId = "test-project",
        apiKey = "test-key",
        selfDeviceId = { "my-device" },
        pollMillis = 1_000,
    )

    @Test
    fun `announceSelf patches our row with status, stamp, and mask`() = runTest {
        var captured: String? = null
        var url: String? = null
        var mask: List<String> = emptyList()
        val engine = MockEngine { request ->
            url = request.url.encodedPath
            mask = request.url.parameters.getAll("updateMask.fieldPaths").orEmpty()
            captured = (request.body as io.ktor.http.content.TextContent).text
            respond("{}", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }

        plane(engine).announceSelf(session, CallStatus.Ringing)

        assertTrue(url!!.endsWith("/documents/calls/u1/call_users/my-device"))
        val fields = Json.parseToJsonElement(captured!!).jsonObject["fields"]!!.jsonObject
        assertEquals(
            "ringing",
            fields["current_status"]!!.jsonObject["stringValue"]!!.jsonPrimitive.content,
        )
        assertEquals(
            "Desktop",
            fields["updated_from"]!!.jsonObject["stringValue"]!!.jsonPrimitive.content,
        )
        // Identity travels on the row, not only in its document id: the
        // roster reader here and on the phones drops a row without
        // `device_id`, so omitting it made this desktop invisible on the call.
        assertEquals(
            "my-device",
            fields["device_id"]!!.jsonObject["stringValue"]!!.jsonPrimitive.content,
        )
        assertEquals(
            "me",
            fields["user_id"]!!.jsonObject["stringValue"]!!.jsonPrimitive.content,
        )
        // The mask names exactly what we send — an unmasked patch would erase
        // every field the other platforms wrote on this row.
        assertEquals(
            setOf("current_status", "device_id", "user_id", "updated_from"),
            mask.toSet(),
        )
    }

    @Test
    fun `a row written without a known user still carries its device`() = runTest {
        var captured: String? = null
        val engine = MockEngine { request ->
            captured = (request.body as io.ktor.http.content.TextContent).text
            respond("{}", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }

        plane(engine).announceSelf(CallSession(callUuid = "u1"), CallStatus.Ringing)

        val fields = Json.parseToJsonElement(captured!!).jsonObject["fields"]!!.jsonObject
        assertEquals(
            "my-device",
            fields["device_id"]!!.jsonObject["stringValue"]!!.jsonPrimitive.content,
        )
        // Blank rather than an empty string on the row.
        assertTrue(fields["user_id"] == null)
    }

    @Test
    fun `announceCallEnded writes End Call on the call document`() = runTest {
        var captured: String? = null
        var url: String? = null
        val engine = MockEngine { request ->
            url = request.url.encodedPath
            captured = (request.body as io.ktor.http.content.TextContent).text
            respond("{}", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }

        plane(engine).announceCallEnded(session)

        assertTrue(url!!.endsWith("/documents/calls/u1"))
        val fields = Json.parseToJsonElement(captured!!).jsonObject["fields"]!!.jsonObject
        assertEquals(
            "End Call",
            fields["status"]!!.jsonObject["stringValue"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `watch reports roster statuses once each and the global ending`() =
        runTest(StandardTestDispatcher()) {
            val callDoc = """{"fields":{"status":{"stringValue":"in_call"}}}"""
            val endedDoc = """{"fields":{"status":{"stringValue":"End Call"}}}"""
            val users = """{"documents":[
                {"fields":{
                    "device_id":{"stringValue":"peer-dev"},
                    "user_id":{"stringValue":"peer"},
                    "current_status":{"stringValue":"ringing"},
                    "agora_uid":{"integerValue":"77"},
                    "screenShare":{"booleanValue":true},
                    "raise_hand":{"booleanValue":true},
                    "updated_from":{"stringValue":"Android"}}}]}"""
            var polls = 0
            val engine = MockEngine { request ->
                val path = request.url.encodedPath
                when {
                    path.endsWith("/call_users") -> respond(users, HttpStatusCode.OK)
                    else -> {
                        polls++
                        respond(if (polls >= 2) endedDoc else callDoc, HttpStatusCode.OK)
                    }
                }
            }

            val events = mutableListOf<PlaneEvent>()
            val job = launch { plane(engine).watch(session).take(3).toList(events) }
            advanceTimeBy(3_000)
            runCurrent()
            job.join()

            assertEquals(
                PlaneEvent.UserStatus("peer-dev", "peer", CallStatus.Ringing, "Android"),
                events[0],
            )
            // The row's live flags ride the same poll on their own event: a
            // hand goes up without the status moving, so a status-keyed
            // detector would never report it.
            assertEquals(
                PlaneEvent.UserFlags(
                    deviceId = "peer-dev",
                    userId = "peer",
                    agoraUid = 77,
                    sharing = true,
                    handRaised = true,
                ),
                events[1],
            )
            assertEquals(PlaneEvent.Ended("u1"), events[2])
        }

    @Test
    fun `the web's spelling of the sharing flag is read too`() =
        runTest(StandardTestDispatcher()) {
            val users = """{"documents":[
                {"fields":{
                    "device_id":{"stringValue":"peer-dev"},
                    "current_status":{"stringValue":"in_call"},
                    "screen_share":{"booleanValue":true}}}]}"""
            val engine = MockEngine { request ->
                if (request.url.encodedPath.endsWith("/call_users")) {
                    respond(users, HttpStatusCode.OK)
                } else {
                    respond("""{"fields":{}}""", HttpStatusCode.OK)
                }
            }

            val events = mutableListOf<PlaneEvent>()
            val job = launch { plane(engine).watch(session).take(2).toList(events) }
            advanceTimeBy(3_000)
            runCurrent()
            // take(2) completes on the pair this row produces, so the
            // collection finishes on its own rather than being cut short.
            job.join()

            val flags = events.filterIsInstance<PlaneEvent.UserFlags>().single()
            assertTrue(flags.sharing)
        }

    @Test
    fun `a peer's mute and camera are read off their row`() =
        runTest(StandardTestDispatcher()) {
            // On the Agora line this row is the only mute signal that exists
            // for somebody who was already muted before this device joined:
            // the SDK reports mute by publishing and unpublishing, which is a
            // change and never a standing state.
            val users = """{"documents":[
                {"fields":{
                    "device_id":{"stringValue":"peer-dev"},
                    "current_status":{"stringValue":"in_call"},
                    "isMute":{"booleanValue":true},
                    "has_video":{"booleanValue":false}}}]}"""
            val engine = MockEngine { request ->
                if (request.url.encodedPath.endsWith("/call_users")) {
                    respond(users, HttpStatusCode.OK)
                } else {
                    respond("""{"fields":{}}""", HttpStatusCode.OK)
                }
            }

            val events = mutableListOf<PlaneEvent>()
            val job = launch { plane(engine).watch(session).take(2).toList(events) }
            advanceTimeBy(3_000)
            runCurrent()
            job.join()

            val flags = events.filterIsInstance<PlaneEvent.UserFlags>().single()
            assertTrue(flags.muted)
            assertFalse(flags.hasVideo)
        }

    @Test
    fun `a row whose only change is the mute flag is still reported`() =
        runTest(StandardTestDispatcher()) {
            // The poller only emits when its diff key changes, so a flag left
            // out of that key is a flag that moves silently. Mute flipping on
            // its own is exactly the case the Agora line depends on.
            var polls = 0
            val engine = MockEngine { request ->
                if (request.url.encodedPath.endsWith("/call_users")) {
                    polls++
                    val muted = if (polls > 1) "true" else "false"
                    respond(
                        """{"documents":[
                            {"fields":{
                                "device_id":{"stringValue":"peer-dev"},
                                "current_status":{"stringValue":"in_call"},
                                "isMute":{"booleanValue":$muted}}}]}""",
                        HttpStatusCode.OK,
                    )
                } else {
                    respond("""{"fields":{}}""", HttpStatusCode.OK)
                }
            }

            val events = mutableListOf<PlaneEvent>()
            val job = launch { plane(engine).watch(session).take(3).toList(events) }
            advanceTimeBy(6_000)
            runCurrent()
            job.join()

            val flags = events.filterIsInstance<PlaneEvent.UserFlags>()
            assertEquals(listOf(false, true), flags.map { it.muted })
        }

    @Test
    fun `an auth refusal disables the plane instead of hammering it`() =
        runTest(StandardTestDispatcher()) {
            var requests = 0
            val engine = MockEngine {
                requests++
                respondError(HttpStatusCode.Forbidden)
            }
            val plane = plane(engine)

            plane.announceSelf(session, CallStatus.Ringing)
            val before = requests
            plane.announceSelf(session, CallStatus.InCall)
            plane.announceCallEnded(session)

            assertEquals(before, requests)
        }
}

