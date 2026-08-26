package com.zillit.desktop.feature.chat

import com.zillit.desktop.core.locationpicker.PickedLocation
import com.zillit.desktop.core.sync.InMemoryDraftStore
import com.zillit.desktop.core.sync.InMemoryOutboxStore
import com.zillit.desktop.core.sync.OfflineSupport
import com.zillit.desktop.core.sync.SyncEngine
import com.zillit.desktop.core.sync.SyncHandlerRegistry
import com.zillit.desktop.core.sync.SyncScope
import com.zillit.desktop.feature.chat.data.ChatSendHandler
import com.zillit.desktop.feature.chat.data.readChatMessage
import com.zillit.desktop.feature.chat.data.sendEnvelope
import com.zillit.desktop.feature.chat.domain.ChatAttachment
import com.zillit.desktop.feature.chat.domain.ChatLocation
import com.zillit.desktop.feature.chat.domain.ChatSendState
import com.zillit.desktop.feature.chat.domain.CrewContact
import com.zillit.desktop.feature.chat.ui.ChatEvent
import com.zillit.desktop.feature.chat.ui.ChatViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.assertContentEquals
import kotlinx.coroutines.test.advanceUntilIdle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Sharing a place in C&C — `message_type: "location"`, the one real chat kind
 * the desktop could neither send nor draw.
 *
 * The wire truth these pin, with its authority:
 *  - the literal is `"location"` — Android `CommonApis.kt:1886` chooses it with
 *    `if (location != null) "".messageTypeOrContentTypeProvider(true)`, whose
 *    `true` branch returns `Constants.LOCATION` (`Extension.kt:602-603`,
 *    `Constants.kt:713`);
 *  - the place rides TOP LEVEL as `location`, beside `attachment`, never
 *    inside it (`ChatAndGroupRequestModelHandler.kt:42`, web
 *    `cncUtil.js:312-315`);
 *  - its longitude is spelled `long` (`HomeChatRequest.kt:231-232`);
 *  - the body is a real encrypted body carrying the place's description
 *    (`MediaExtension.kt:308-322` → `ChatAndGroupVM.kt:605-615`), which both
 *    phones then hide when drawing (`HoldersViewhandler.kt:359,371`).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatLocationTest {

    private val dispatcher = StandardTestDispatcher()
    private val aisha = CrewContact(userId = "u-aisha", fullName = "Aisha Khan")

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private val aria = PickedLocation(
        name = "Aria Hotel",
        address = "12 Rajpath Marg, New Delhi",
        lat = 28.6129,
        lng = 77.2295,
    )

    // -- the envelope, field for field --------------------------------------------

    @Test
    fun `a shared place sends message_type location with the place at top level`() {
        val envelope = sendEnvelope(
            projectId = "p1",
            uniqueId = "u-loc",
            senderId = "me",
            receiverId = "you",
            cipherBody = "cipher",
            nowMillis = 42L,
            location = ChatLocation(address = aria.address, lat = aria.lat, lng = aria.lng),
        )

        assertEquals("location", envelope["message_type"]!!.jsonPrimitive.content)
        val place = envelope["location"] as JsonObject
        assertEquals("28.6129", place["lat"]!!.jsonPrimitive.content)
        // `long`, not `lng` — Zillit's spelling, HomeChatRequest.kt:231-232.
        assertEquals("77.2295", place["long"]!!.jsonPrimitive.content)
        assertNull(place["lng"], "the wire has no lng key")
        assertEquals("12 Rajpath Marg, New Delhi", place["address"]!!.jsonPrimitive.content)

        // Everything else stays the plain-message envelope, and the body is a
        // body: encrypted like any other, not folded into the place object.
        assertEquals("private", envelope["type"]!!.jsonPrimitive.content)
        assertEquals("cnc_section", envelope["chat_tool"]!!.jsonPrimitive.content)
        assertEquals("cipher", envelope["message"]!!.jsonPrimitive.content)
        // No map raster to upload, so no attachment object is invented for one.
        assertNull(envelope["attachment"], "this client sends no map screenshot")
    }

    @Test
    fun `the place beats an attachment when naming the kind, as Android decides it`() {
        // CommonApis.kt:1886 tests `location != null` FIRST and only then the
        // attachment's content type — a phone-sent place carries BOTH.
        val envelope = sendEnvelope(
            projectId = "p", uniqueId = "u", senderId = "me", receiverId = "you",
            cipherBody = "", nowMillis = 1L,
            attachment = ChatAttachment(media = "chat/map.png", name = "map.png", contentType = "image/png"),
            location = ChatLocation(lat = 1.5, lng = 2.5),
        )
        assertEquals("location", envelope["message_type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a quoted place still sends the parent id as a string`() {
        val envelope = sendEnvelope(
            projectId = "p", uniqueId = "u", senderId = "me", receiverId = "you",
            cipherBody = "c", nowMillis = 1L,
            replyToId = "656565656565656565656565",
            location = ChatLocation(lat = 1.0, lng = 2.0),
        )
        assertEquals("656565656565656565656565", envelope["reply"]!!.jsonPrimitive.content)
    }

    // -- the read path, in every shape the wire produces ---------------------------

    @Test
    fun `a phone's location reads - place at top level, screenshot in attachment`() {
        // Android's full LocationInfo (HomeChatRequest.kt:228-239) plus the map
        // screenshot it uploads as the message's attachment.
        val message = readChatMessage(
            Json.parseToJsonElement(
                """{"_id":"m1","unique_id":"u1","sender":"u-aisha","receiver":"me",
                   "message":"hex:12 Rajpath Marg, New Delhi","created":9,
                   "message_type":"location",
                   "location":{"lat":28.6129,"long":77.2295,
                     "address":"12 Rajpath Marg, New Delhi",
                     "imageLink":"/data/user/0/…/1758.png","height":300,"width":225},
                   "attachment":{"media":"p1/chat/1758.png","name":"1758.png",
                     "content_type":"image","bucket":"b","region":"r"}}""",
            ),
            myUserId = "me",
            decrypt = { it.removePrefix("hex:").takeIf { _ -> it.startsWith("hex:") } },
        )!!

        val place = assertNotNull(message.location)
        assertEquals(28.6129, place.lat)
        assertEquals(77.2295, place.lng)
        assertEquals("12 Rajpath Marg, New Delhi", place.address)
        // The body is a real body — the address, decrypted like any other.
        assertEquals("12 Rajpath Marg, New Delhi", message.body)
        // The screenshot stays where it was; the card shows it as the map.
        assertEquals("p1/chat/1758.png", message.attachment?.media)
        assertEquals("https://www.google.com/maps?q=28.6129,77.2295", place.mapsUrl)
    }

    @Test
    fun `a browser's location reads - the pin alone, no address`() {
        // The web sends `{lat, long}` and nothing else (cncUtil.js:312-315).
        val message = readChatMessage(
            Json.parseToJsonElement(
                """{"_id":"m2","sender":"u-web","receiver":"me","created":1,
                   "message_type":"location","location":{"lat":51.5,"long":-0.12}}""",
            ),
            myUserId = "me",
            decrypt = { null },
        )!!
        val place = assertNotNull(message.location)
        assertEquals(51.5, place.lat)
        assertEquals(-0.12, place.lng)
        assertEquals("", place.address, "no address on the wire, none invented")
    }

    @Test
    fun `our own echo reads back exactly what we sent`() {
        val envelope = sendEnvelope(
            projectId = "p1", uniqueId = "u-loc", senderId = "me", receiverId = "you",
            cipherBody = "hex:Aria Hotel", nowMillis = 42L,
            location = ChatLocation(address = aria.address, lat = aria.lat, lng = aria.lng),
        )
        // The server hands the saved row back wrapped in {success, detail}.
        val echo = readChatMessage(
            Json.parseToJsonElement("""{"success":true,"detail":$envelope}"""),
            myUserId = "me",
            decrypt = { it.removePrefix("hex:").takeIf { _ -> it.startsWith("hex:") } },
        )!!
        assertEquals(ChatLocation(aria.address, aria.lat, aria.lng), echo.location)
        assertEquals("Aria Hotel", echo.body)
        assertTrue(echo.isMine)
    }

    @Test
    fun `a message with no real pin is not a location`() {
        fun read(json: String) = readChatMessage(
            Json.parseToJsonElement(json),
            myUserId = "me",
            decrypt = { null },
        )?.location

        // The web stamps an EMPTY location object onto every reply payload
        // (cncUtil.js:380) — a text reply must not become a map card.
        assertNull(read("""{"_id":"a","sender":"s","receiver":"me","location":{}}"""))
        // Android will not draw a 0/0 pin either (ChatAndGroupPage.kt:2598).
        assertNull(read("""{"_id":"b","sender":"s","receiver":"me","location":{"lat":0,"long":0}}"""))
        assertNull(read("""{"_id":"c","sender":"s","receiver":"me","message":"words"}"""))
        // Half a pair is no pin.
        assertNull(read("""{"_id":"d","sender":"s","receiver":"me","location":{"lat":12.0}}"""))
    }

    @Test
    fun `coordinates read whether they arrive as numbers or as quoted strings`() {
        val quoted = readChatMessage(
            Json.parseToJsonElement(
                """{"_id":"m","sender":"s","receiver":"me",
                   "location":{"lat":"19.076","long":"72.8777"}}""",
            ),
            myUserId = "me",
            decrypt = { null },
        )!!.location
        assertEquals(ChatLocation("", 19.076, 72.8777), quoted)
    }

    // -- the view model: picking a place sends one message -------------------------

    private fun viewModel(
        repository: FakeChatRepository,
        support: OfflineSupport? = null,
        staticMap: suspend (Double, Double) -> ByteArray? = { _, _ -> null },
        uploadMedia: suspend (String, String, ByteArray, (Int) -> Unit) -> ChatAttachment? =
            { _, _, _, _ -> null },
    ) = ChatViewModel(
        repository = repository,
        nowMillis = { NOW },
        newUniqueId = { "unique-${repository.sent.size}" },
        offline = support,
        uploadMedia = uploadMedia,
        staticMap = staticMap,
    )

    @Test
    fun `picking a location sends exactly one message, carrying the place`() = runTest(dispatcher) {
        val repository = FakeChatRepository()
        val model = viewModel(repository)
        model.onEvent(ChatEvent.OpenThread(aisha))
        runCurrent()

        model.onEvent(ChatEvent.ShareLocation(aria))
        runCurrent()

        assertEquals(1, repository.sent.size, "one pick, one message")
        val sent = repository.delivered.single()
        assertEquals(ChatLocation(aria.address, aria.lat, aria.lng), sent.location)
        // No draft was typed, so the label is the whole place — name AND
        // street. The server keeps only `lat`/`long` from the location
        // object and drops its `address` (seen live, 2026-08-25), so the
        // encrypted body is the only part of the address that survives a
        // reload; a name alone would leave "Aria Hotel" and two numbers.
        assertEquals("Aria Hotel, 12 Rajpath Marg, New Delhi", sent.body)
        assertNull(sent.attachment, "no map raster to upload from here")

        // And the bubble on screen says the same thing.
        val bubble = model.currentState.messages.single()
        assertEquals(ChatSendState.Sent, bubble.sendState)
        assertEquals(aria.address, bubble.location?.address)
        // The shelf marks the row with a pin, not a paperclip.
        assertEquals(
            "📍 Aria Hotel, 12 Rajpath Marg, New Delhi",
            model.currentState.previews[aisha.userId],
        )
    }

    @Test
    fun `a line already typed becomes the shared place's label, and the composer clears`() =
        runTest(dispatcher) {
            val repository = FakeChatRepository()
            val model = viewModel(repository)
            model.onEvent(ChatEvent.OpenThread(aisha))
            runCurrent()

            model.onEvent(ChatEvent.DraftChanged("Park round the back"))
            model.onEvent(ChatEvent.ShareLocation(aria))
            runCurrent()

            assertEquals(1, repository.sent.size)
            assertEquals("Park round the back", repository.delivered.single().body)
            assertEquals("", model.currentState.draft)
        }

    @Test
    fun `a place is not sent to someone who has left the production`() = runTest(dispatcher) {
        val repository = FakeChatRepository()
        val model = viewModel(repository)
        model.onEvent(ChatEvent.OpenThread(aisha.copy(hasLeft = true)))
        runCurrent()

        model.onEvent(ChatEvent.ShareLocation(aria))
        runCurrent()

        assertTrue(repository.sent.isEmpty(), "Android's userActive gate holds for places too")
        assertNotNull(model.currentState.error)
    }

    @Test
    fun `a quoted place carries the parent reference like any other reply`() = runTest(dispatcher) {
        val repository = FakeChatRepository()
        val model = viewModel(repository)
        model.onEvent(ChatEvent.OpenThread(aisha))
        runCurrent()
        model.onEvent(
            ChatEvent.Arrived(
                com.zillit.desktop.feature.chat.domain.ChatMessage(
                    id = "srv-9", uniqueId = "w-9", senderId = "u-aisha", receiverId = "me",
                    body = "Where do we park?", timestampMillis = NOW - 1, isMine = false,
                ),
            ),
        )
        model.onEvent(ChatEvent.StartReply("srv-9"))
        model.onEvent(ChatEvent.ShareLocation(aria))
        runCurrent()

        val bubble = model.currentState.messages.last()
        assertEquals("srv-9", bubble.replyTo?.messageId)
        assertEquals(aria.address, bubble.location?.address)
        assertNull(model.currentState.replyTo, "the reply bar cleared with the send")
    }

    // -- offline: a place queues and replays like words ----------------------------

    private val online = MutableStateFlow(true)
    private val outbox = InMemoryOutboxStore()

    private fun TestScope.support(repository: FakeChatRepository): OfflineSupport {
        var seq = 0L
        val engine = SyncEngine(
            store = outbox,
            handlers = SyncHandlerRegistry(listOf(ChatSendHandler(repository))),
            online = online,
            currentScope = { SyncScope("me", "p1") },
            scope = backgroundScope,
            nowMillis = { NOW },
            newId = { "op-${seq++}" },
        )
        engine.start()
        return OfflineSupport(engine, InMemoryDraftStore(), online) { SyncScope("me", "p1") }
    }

    @Test
    fun `offline, a shared place waits in the outbox and goes with its place intact`() =
        runTest(dispatcher) {
            val repository = FakeChatRepository(socketDown = true)
            val support = support(repository)
            val model = viewModel(repository, support)
            model.onEvent(ChatEvent.OpenThread(aisha))
            runCurrent()
            online.value = false
            runCurrent()

            model.onEvent(ChatEvent.ShareLocation(aria))
            runCurrent()

            val queued = model.currentState.messages.single()
            assertEquals(ChatSendState.Queued, queued.sendState, "the clock, not a failure")
            assertEquals(aria.address, queued.location?.address, "the bubble keeps its card")
            assertTrue(repository.sent.isEmpty(), "nothing tried the socket")
            assertEquals(1, outbox.all().size)

            // A fresh view model over the same outbox — the reopened thread
            // restores the bubble AS A PLACE, not as a bare line of words.
            val reopened = viewModel(repository, support)
            reopened.onEvent(ChatEvent.OpenThread(aisha))
            runCurrent()
            assertEquals(aria.address, reopened.currentState.messages.single().location?.address)

            // The network comes back and the queue drains, place and all.
            repository.socketDown = false
            online.value = true
            runCurrent()

            assertEquals(1, repository.sent.size)
            assertEquals(
                ChatLocation(aria.address, aria.lat, aria.lng),
                repository.delivered.single().location,
            )
        }

    /**
     * A shared place travels with a picture of itself.
     *
     * The phones snapshot their own map and upload it beside the location
     * (`mapView/MapsActivity.kt:205-224`), and their bubbles draw that
     * picture — so a location sent without one arrives on Android and the
     * web as an empty image frame. The boards already post the same Static
     * Maps image; chat now matches them.
     */
    @Test
    fun `a shared place carries its map picture`() = runTest(dispatcher) {
        val repository = FakeChatRepository()
        val png = byteArrayOf(8, 9, 10)
        var uploaded: ByteArray? = null
        val model = viewModel(
            repository,
            staticMap = { lat, lng ->
                // Asked for the place that was actually picked.
                assertEquals(aria.lat, lat)
                assertEquals(aria.lng, lng)
                png
            },
            uploadMedia = { name, type, bytes, _ ->
                uploaded = bytes
                ChatAttachment(media = "chat/$name", name = name, contentType = type)
            },
        )
        model.onEvent(ChatEvent.OpenThread(aisha))
        runCurrent()

        model.onEvent(ChatEvent.ShareLocation(aria))
        advanceUntilIdle()

        assertContentEquals(png, uploaded, "the fetched map is what gets uploaded")
        val sent = repository.delivered.single()
        assertEquals("chat/location-map.png", sent.attachment?.media)
        // The point still rides beside the picture — the picture is not the
        // message, the place is.
        assertEquals(ChatLocation(aria.address, aria.lat, aria.lng), sent.location)
    }

    /**
     * The picture is best-effort; the place is not.
     *
     * No Maps key, no network, a refused upload — none of these may cost the
     * place. It goes either way, exactly as it did before there was a
     * picture at all.
     */
    @Test
    fun `a failed map still sends the place`() = runTest(dispatcher) {
        val repository = FakeChatRepository()
        val model = viewModel(
            repository,
            staticMap = { _, _ -> error("no key on this production") },
        )
        model.onEvent(ChatEvent.OpenThread(aisha))
        runCurrent()

        model.onEvent(ChatEvent.ShareLocation(aria))
        advanceUntilIdle()

        val sent = repository.delivered.single()
        assertNull(sent.attachment, "no picture, and no pretending there is one")
        assertEquals(ChatLocation(aria.address, aria.lat, aria.lng), sent.location)
    }

    private companion object {
        const val NOW = 1_786_507_000_000L
    }
}
