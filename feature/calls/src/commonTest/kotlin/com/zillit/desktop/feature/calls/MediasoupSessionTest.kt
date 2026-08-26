package com.zillit.desktop.feature.calls

import com.zillit.desktop.feature.calls.data.protoo.MediasoupJoin
import com.zillit.desktop.feature.calls.data.protoo.MediasoupNotification
import com.zillit.desktop.feature.calls.data.protoo.MediasoupPage
import com.zillit.desktop.feature.calls.data.protoo.MediasoupSession
import com.zillit.desktop.feature.calls.data.protoo.ProtooMessage
import com.zillit.desktop.feature.calls.data.protoo.ProtooPeer
import com.zillit.desktop.feature.calls.data.protoo.ProtooSignalling
import com.zillit.desktop.feature.calls.data.protoo.TurnCredentials
import com.zillit.desktop.feature.calls.data.protoo.IceServer
import com.zillit.desktop.feature.calls.data.protoo.parseProtoo
import com.zillit.desktop.feature.calls.data.protoo.protooAcceptFrame
import com.zillit.desktop.feature.calls.domain.CallEngineEvent
import com.zillit.desktop.feature.calls.domain.CallJoin
import com.zillit.desktop.feature.calls.domain.CallProvider
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The order a join happens in.
 *
 * Every one of these orderings is a production failure on some client: TURN
 * arriving after the transports is a call that works except on cellular; a
 * `join` before the receive transport is consumers offered into nowhere; a
 * `newConsumer` answered after the media work is a track the SFU has already
 * given up on. None of it can be seen without a live SFU, so it is pinned here.
 */
class MediasoupSessionTest {

    /** Records what the page was told, in order. */
    private class FakePage : MediasoupPage {
        val calls = mutableListOf<String>()
        val settled = mutableListOf<Triple<Long, Boolean, JsonObject>>()
        var iceServersJson: String = ""
        override suspend fun load(routerRtpCapabilities: JsonObject) { calls += "load" }
        override suspend fun createTransports(send: JsonObject, recv: JsonObject, iceServers: String) {
            calls += "createTransports"
            iceServersJson = iceServers
        }
        override suspend fun produceMic(deviceId: String) { calls += "produceMic" }
        override suspend fun produceCam(deviceId: String) { calls += "produceCam" }
        override suspend fun consume(params: JsonObject) { calls += "consume" }
        override suspend fun closeConsumer(consumerId: String) { calls += "closeConsumer" }
        override suspend fun settle(askId: Long, ok: Boolean, payload: JsonObject) {
            calls += "settle"
            settled += Triple(askId, ok, payload)
        }
        override suspend fun leave() { calls += "leave" }
    }

    private class FakeSignalling : ProtooSignalling {
        var dialled: String? = null
        var closed = false
        /** Invoked immediately, as the real socket does on its first attempt. */
        override fun dial(url: () -> String) { dialled = url() }
        override fun send(frame: String) = true
        override fun close(reason: String) { closed = true }
    }

    /** Answers every request with a canned reply, and records the order. */
    private class Wire {
        val methods = mutableListOf<String>()
        var replies: (String) -> JsonObject = { buildJsonObject { put("id", "x") } }
        lateinit var peer: ProtooPeer
        suspend fun send(frame: String) {
            val message = parseProtoo(frame) as? ProtooMessage.Request ?: return
            methods += message.method
            peer.onFrame(protooAcceptFrame(message.id, replies(message.method)))
        }
    }

    private val join = CallJoin(
        provider = CallProvider.Mediasoup,
        hasVideo = false,
        sfuHost = "sfu.zillit.com",
        roomId = "room-7",
        peerId = "user-9:device-3",
    )

    @Test
    fun `the dial url is built from the elected host, room and peer`() = runTest(StandardTestDispatcher()) {
        val signalling = FakeSignalling()
        val wire = Wire()
        val peer = ProtooPeer(backgroundScope, wire::send).also { wire.peer = it }
        val session = MediasoupSession(
            backgroundScope, peer, signalling, FakePage(),
            turn = { TurnCredentials(emptyList(), 600) }, emit = {},
        )

        session.dial(join)

        assertEquals("wss://sfu.zillit.com/?roomId=room-7&peerId=user-9:device-3", signalling.dialled)
    }

    @Test
    fun `a call with no elected host fails instead of dialling nowhere`() = runTest(StandardTestDispatcher()) {
        val signalling = FakeSignalling()
        val wire = Wire()
        val peer = ProtooPeer(backgroundScope, wire::send).also { wire.peer = it }
        val events = mutableListOf<CallEngineEvent>()
        val session = MediasoupSession(
            backgroundScope, peer, signalling, FakePage(),
            turn = { TurnCredentials(emptyList(), 600) }, emit = { events += it },
        )

        session.dial(join.copy(sfuHost = ""))
        runCurrent()
        advanceUntilIdle()

        assertEquals(null, signalling.dialled)
        assertTrue(events.any { it is CallEngineEvent.Failed }, "expected a Failed, got $events")
    }

    /**
     * The whole ordering, in one assertion. Both transports are asked for
     * before `join`, and TURN reaches the page with them.
     */
    @Test
    fun `the join sequence asks in the right order`() = runTest(StandardTestDispatcher()) {
        val page = FakePage()
        val wire = Wire()
        val peer = ProtooPeer(backgroundScope, wire::send).also { wire.peer = it }
        var turnFetched = false
        val session = MediasoupSession(
            backgroundScope, peer, FakeSignalling(), page,
            turn = {
                turnFetched = true
                TurnCredentials(listOf(IceServer(listOf("turn:t:1"), "u", "c")), 600)
            },
            emit = {},
        )

        val work = launch { session.join("Vivek", microphoneId = "", withVideo = false) }
        runCurrent()
        // The page reports its capabilities part-way through, as it does live.
        session.onPageLoaded(buildJsonObject { put("codecs", "…") }, null)
        advanceUntilIdle()
        work.join()

        assertEquals(
            listOf(
                MediasoupJoin.GET_ROUTER_CAPABILITIES,
                MediasoupJoin.CREATE_TRANSPORT,
                MediasoupJoin.CREATE_TRANSPORT,
                MediasoupJoin.JOIN,
            ),
            wire.methods,
            "join must come after BOTH transports, or consumers are offered into nowhere",
        )
        assertEquals(listOf("load", "createTransports", "produceMic"), page.calls)
        assertTrue(turnFetched, "relays must be fetched before the transports are built")
        assertTrue("turn:t:1" in page.iceServersJson, "the relays must reach the page")
    }

    /**
     * The SFU abandons a `newConsumer` it gets no answer to. The accept has to
     * precede the browser's consume(), which is slow.
     */
    @Test
    fun `newConsumer is accepted before the page is asked to consume`() = runTest(StandardTestDispatcher()) {
        val page = FakePage()
        val sent = mutableListOf<String>()
        val peer = ProtooPeer(backgroundScope, { sent += it })
        val session = MediasoupSession(
            backgroundScope, peer, FakeSignalling(), page,
            turn = { TurnCredentials(emptyList(), 600) }, emit = {},
        )

        session.onServerRequest(
            ProtooMessage.Request(55, MediasoupNotification.NEW_CONSUMER, buildJsonObject { put("id", "c1") }),
        )
        runCurrent()
        advanceUntilIdle()

        // The accept is FIRST, before the consume and before the resume that
        // follows it — the SFU gives up on an unanswered request sooner than
        // the browser finishes consuming.
        assertTrue(sent.first().contains("\"ok\":true"), "the accept must go out first: ${sent.first()}")
        assertEquals(listOf("consume"), page.calls)
    }

    @Test
    fun `an unknown server request is refused rather than ignored`() = runTest(StandardTestDispatcher()) {
        val sent = mutableListOf<String>()
        val peer = ProtooPeer(backgroundScope, { sent += it })
        val session = MediasoupSession(
            backgroundScope, peer, FakeSignalling(), FakePage(),
            turn = { TurnCredentials(emptyList(), 600) }, emit = {},
        )

        session.onServerRequest(ProtooMessage.Request(56, "mystery", JsonObject(emptyMap())))
        runCurrent()
        advanceUntilIdle()

        assertTrue(sent.single().contains("unknown method: mystery"), sent.single())
    }

    /** The page cannot proceed without an answer, success or failure. */
    @Test
    fun `a page ask is answered from the sfu, and a refusal is passed back`() =
        runTest(StandardTestDispatcher()) {
            val page = FakePage()
            val wire = Wire()
            val peer = ProtooPeer(backgroundScope, wire::send).also { wire.peer = it }
            val session = MediasoupSession(
                backgroundScope, peer, FakeSignalling(), page,
                turn = { TurnCredentials(emptyList(), 600) }, emit = {},
            )

            wire.replies = { buildJsonObject { put("id", "producer-1") } }
            session.onPageAsk(7, MediasoupJoin.PRODUCE, buildJsonObject { put("kind", "audio") })
            runCurrent()
            advanceUntilIdle()

            val (askId, ok, payload) = page.settled.single()
            assertEquals(7L, askId)
            assertTrue(ok)
            assertEquals("producer-1", payload["id"]?.jsonPrimitive?.content)
        }

    /**
     * A rejoin changes the device half of a peer id but not the user half, so
     * the same person must keep the same tile rather than appearing as new.
     */
    @Test
    fun `a peer keeps its uid across a rejoin`() = runTest(StandardTestDispatcher()) {
        val events = mutableListOf<CallEngineEvent>()
        val peer = ProtooPeer(backgroundScope, { })
        val session = MediasoupSession(
            backgroundScope, peer, FakeSignalling(), FakePage(),
            turn = { TurnCredentials(emptyList(), 600) }, emit = { events += it },
        )

        session.onNotification(
            ProtooMessage.Notification(
                MediasoupNotification.ACTIVE_SPEAKER,
                buildJsonObject { put("peerId", "user-9:device-3") },
            ),
        )
        session.onNotification(
            ProtooMessage.Notification(
                MediasoupNotification.ACTIVE_SPEAKER,
                buildJsonObject { put("peerId", "user-9:device-3-r-a1b2c3d4") },
            ),
        )
        runCurrent()
        advanceUntilIdle()

        val speakers = events.filterIsInstance<CallEngineEvent.ActiveSpeakers>()
        assertEquals(2, speakers.size)
        assertEquals(
            speakers[0].uids,
            speakers[1].uids,
            "a rejoin must not make an existing peer look like somebody new",
        )
    }
}

/**
 * The two things the preflight audit caught, pinned so they cannot come back.
 *
 * Both were silent: a consumer nobody resumes forwards no packets, and a
 * camera published on an audio call lights the user's camera indicator for no
 * reason. Neither shows up as an error anywhere.
 */
class MediasoupMediaTest {

    private class FakePage : com.zillit.desktop.feature.calls.data.protoo.MediasoupPage {
        val calls = mutableListOf<String>()
        override suspend fun load(routerRtpCapabilities: JsonObject) { calls += "load" }
        override suspend fun createTransports(send: JsonObject, recv: JsonObject, iceServers: String) {
            calls += "createTransports"
        }
        override suspend fun produceMic(deviceId: String) { calls += "produceMic" }
        override suspend fun produceCam(deviceId: String) { calls += "produceCam" }
        override suspend fun consume(params: JsonObject) { calls += "consume" }
        override suspend fun closeConsumer(consumerId: String) { calls += "closeConsumer" }
        override suspend fun settle(askId: Long, ok: Boolean, payload: JsonObject) { calls += "settle" }
        override suspend fun leave() { calls += "leave" }
    }

    private class Signalling : com.zillit.desktop.feature.calls.data.protoo.ProtooSignalling {
        override fun dial(url: () -> String) = Unit
        override fun send(frame: String) = true
        override fun close(reason: String) = Unit
    }

    /** Every consumer arrives paused; without this the call is silent. */
    @Test
    fun `a new consumer is resumed after it is consumed`() = runTest(StandardTestDispatcher()) {
        val sentMethods = mutableListOf<String>()
        lateinit var peer: ProtooPeer
        peer = ProtooPeer(backgroundScope, send = { frame ->
            val request = com.zillit.desktop.feature.calls.data.protoo.parseProtoo(frame)
                    as? ProtooMessage.Request ?: return@ProtooPeer
            sentMethods += request.method
            peer.onFrame(
                com.zillit.desktop.feature.calls.data.protoo.protooAcceptFrame(request.id, JsonObject(emptyMap())),
            )
        })
        val session = MediasoupSession(
            backgroundScope, peer, Signalling(), FakePage(),
            turn = { TurnCredentials(emptyList(), 600) }, emit = {},
        )

        session.onServerRequest(
            ProtooMessage.Request(
                77,
                MediasoupNotification.NEW_CONSUMER,
                buildJsonObject { put("id", "consumer-1"); put("peerId", "u:d") },
            ),
        )
        runCurrent()
        advanceUntilIdle()

        assertTrue(
            MediasoupJoin.RESUME_CONSUMER in sentMethods,
            "a consumer nobody resumes forwards no packets: $sentMethods",
        )
    }

    @Test
    fun `an audio call does not publish a camera`() = runTest(StandardTestDispatcher()) {
        val page = FakePage()
        lateinit var peer: ProtooPeer
        peer = ProtooPeer(backgroundScope, send = { frame ->
            val request = com.zillit.desktop.feature.calls.data.protoo.parseProtoo(frame)
                    as? ProtooMessage.Request ?: return@ProtooPeer
            peer.onFrame(
                com.zillit.desktop.feature.calls.data.protoo.protooAcceptFrame(request.id, JsonObject(emptyMap())),
            )
        })
        val session = MediasoupSession(
            backgroundScope, peer, Signalling(), page,
            turn = { TurnCredentials(emptyList(), 600) }, emit = {},
        )

        val work = launch { session.join("Vivek", microphoneId = "", withVideo = false) }
        runCurrent()
        session.onPageLoaded(buildJsonObject { put("codecs", "…") }, null)
        advanceUntilIdle()
        work.join()

        assertTrue("produceMic" in page.calls, page.calls.toString())
        assertTrue(
            "produceCam" !in page.calls,
            "publishing a camera on an audio call lights the user's camera light for nothing",
        )
    }

    @Test
    fun `a video call publishes the camera too`() = runTest(StandardTestDispatcher()) {
        val page = FakePage()
        lateinit var peer: ProtooPeer
        peer = ProtooPeer(backgroundScope, send = { frame ->
            val request = com.zillit.desktop.feature.calls.data.protoo.parseProtoo(frame)
                    as? ProtooMessage.Request ?: return@ProtooPeer
            peer.onFrame(
                com.zillit.desktop.feature.calls.data.protoo.protooAcceptFrame(request.id, JsonObject(emptyMap())),
            )
        })
        val session = MediasoupSession(
            backgroundScope, peer, Signalling(), page,
            turn = { TurnCredentials(emptyList(), 600) }, emit = {},
        )

        val work = launch { session.join("Vivek", microphoneId = "", withVideo = true) }
        runCurrent()
        session.onPageLoaded(buildJsonObject { put("codecs", "…") }, null)
        advanceUntilIdle()
        work.join()

        assertTrue("produceCam" in page.calls, page.calls.toString())
    }
}

/**
 * What happens when the signalling socket comes back.
 *
 * The failure this guards against is asymmetric and therefore easy to miss in
 * testing: rejoining under the previous peer id makes the SFU evict that peer
 * and announce `peerClosed`, and the REMOTE — which never lost its connection
 * — reads that as everyone leaving and ends the call. The side that
 * reconnected sees nothing wrong.
 */
class MediasoupReconnectTest {

    /** Records every URL the socket was asked to dial, in order. */
    private class RedialSignalling : com.zillit.desktop.feature.calls.data.protoo.ProtooSignalling {
        var provider: (() -> String)? = null
        val dialled = mutableListOf<String>()
        override fun dial(url: () -> String) {
            provider = url
            dialled += url()
        }
        override fun send(frame: String) = true
        override fun close(reason: String) = Unit
        /** What the real socket does on a redial: ask again. */
        fun redial() { provider?.let { dialled += it() } }
    }

    private class Page : com.zillit.desktop.feature.calls.data.protoo.MediasoupPage {
        val calls = mutableListOf<String>()
        override suspend fun load(routerRtpCapabilities: JsonObject) { calls += "load" }
        override suspend fun createTransports(send: JsonObject, recv: JsonObject, iceServers: String) {
            calls += "createTransports"
        }
        override suspend fun produceMic(deviceId: String) { calls += "produceMic" }
        override suspend fun produceCam(deviceId: String) { calls += "produceCam" }
        override suspend fun consume(params: JsonObject) { calls += "consume" }
        override suspend fun closeConsumer(consumerId: String) { calls += "closeConsumer" }
        override suspend fun settle(askId: Long, ok: Boolean, payload: JsonObject) { calls += "settle" }
        override suspend fun leave() { calls += "leave" }
    }

    private val join = CallJoin(
        provider = CallProvider.Mediasoup,
        hasVideo = false,
        sfuHost = "sfu.zillit.com",
        roomId = "room-7",
        peerId = "user-9:device-3",
    )

    @Test
    fun `every dial uses a different peer id, and the identity never moves`() =
        runTest(StandardTestDispatcher()) {
            val signalling = RedialSignalling()
            val session = MediasoupSession(
                backgroundScope, ProtooPeer(backgroundScope, { }), signalling, Page(),
                turn = { TurnCredentials(emptyList(), 600) }, emit = {},
            )

            session.dial(join)
            repeat(3) { signalling.redial() }

            assertEquals(4, signalling.dialled.size)
            assertEquals(
                signalling.dialled.size,
                signalling.dialled.toSet().size,
                "a repeated peer id evicts the peer and ends the remote's call: ${signalling.dialled}",
            )
            // The first dial uses the invited id verbatim; only redials freshen it.
            assertTrue(signalling.dialled.first().endsWith("peerId=user-9:device-3"), signalling.dialled.first())
            // The user half is what remotes bind a tile to and must survive.
            signalling.dialled.forEach { url ->
                assertTrue("peerId=user-9:" in url, "the identity must not move: $url")
            }
        }

    /**
     * A mediasoup device can be loaded once, so a rejoin needs a new
     * everything — resuming into the old session would join nothing.
     */
    @Test
    fun `a rejoin tears the page's media down first`() = runTest(StandardTestDispatcher()) {
        val page = Page()
        val session = MediasoupSession(
            backgroundScope, ProtooPeer(backgroundScope, { }), RedialSignalling(), page,
            turn = { TurnCredentials(emptyList(), 600) }, emit = {},
        )

        session.resetForRejoin()
        runCurrent()

        assertEquals(listOf("leave"), page.calls)
    }

    /** After a reset the latch must be open, or the rejoin silently does nothing. */
    @Test
    fun `a reset lets join run again`() = runTest(StandardTestDispatcher()) {
        val page = Page()
        lateinit var peer: ProtooPeer
        peer = ProtooPeer(backgroundScope, send = { frame ->
            val request = com.zillit.desktop.feature.calls.data.protoo.parseProtoo(frame)
                    as? ProtooMessage.Request ?: return@ProtooPeer
            peer.onFrame(
                com.zillit.desktop.feature.calls.data.protoo.protooAcceptFrame(request.id, JsonObject(emptyMap())),
            )
        })
        val session = MediasoupSession(
            backgroundScope, peer, RedialSignalling(), page,
            turn = { TurnCredentials(emptyList(), 600) }, emit = {},
        )

        val first = launch { session.join("Vivek", "", withVideo = false) }
        runCurrent()
        session.onPageLoaded(buildJsonObject { put("codecs", "…") }, null)
        advanceUntilIdle()
        first.join()
        val afterFirst = page.calls.count { it == "load" }

        session.resetForRejoin()
        val second = launch { session.join("Vivek", "", withVideo = false) }
        runCurrent()
        session.onPageLoaded(buildJsonObject { put("codecs", "…") }, null)
        advanceUntilIdle()
        second.join()

        assertTrue(
            page.calls.count { it == "load" } > afterFirst,
            "the join latch must reopen, or a recovered socket joins nothing: ${page.calls}",
        )
    }
}
