package com.zillit.desktop.feature.home

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.network.HttpClientFactory
import com.zillit.desktop.feature.home.data.NewNoticeDto
import com.zillit.desktop.feature.home.data.readNotice
import com.zillit.desktop.feature.home.data.toDto
import com.zillit.desktop.feature.home.domain.GeoPoint
import com.zillit.desktop.feature.home.domain.HomeFeedRepository
import com.zillit.desktop.feature.home.domain.HomeRealtimeEvent
import com.zillit.desktop.feature.home.domain.HomeUnit
import com.zillit.desktop.feature.home.domain.Notice
import com.zillit.desktop.feature.home.domain.NoticeComment
import com.zillit.desktop.feature.home.domain.NoticeKind
import com.zillit.desktop.feature.home.domain.NoticeSendState
import com.zillit.desktop.feature.home.domain.ReadBy
import com.zillit.desktop.feature.home.domain.UploadedNoticeMedia
import com.zillit.desktop.feature.home.domain.applyRealtime
import com.zillit.desktop.feature.home.ui.HomeFeedEvent
import com.zillit.desktop.feature.home.ui.HomeFeedViewModel
import com.zillit.desktop.feature.home.ui.MediaCapture
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Sharing a place on a notice board.
 *
 * ## The wire this pins, and the reading behind it
 *
 * `message_type: "location"` is real on the **board** endpoint, not only on
 * C&C. The board's own POST body model declares the field
 * (`HomeChatRequest.location: LocationInfo?`,
 * `bottomNav/home/models/HomeChatRequest.kt:31-32`), the board's read model
 * declares it back (`HomeChatInfo.location`, `:153-154`), Home's own view
 * model writes the literal (`HomeVm.kt:930`, `messageType = "location"`) and
 * so does Catering's — one of the boards this engine also drives
 * (`bottomNav/tools/viewmodel/CateringVm.kt:708`). Android persists it
 * (`databases/HomeChatDBManager.kt:1201`, `LocationInfoDb`). The web's
 * unit-chat — the boards' own family, not C&C — sends it
 * (`components/unit-chat/UnitChatMessageBox.jsx:1013-1016`), renders it
 * (`UnitChatMessage.jsx:948` → `message-types/LocationMessage.jsx`) and lists
 * it in search (`UnitChatSearchDrawer.jsx:361`).
 *
 * The object itself is thin: `{lat, long}` on iOS
 * (`ChatAPIModel.swift:184-197`) and the web, plus `address` on Android
 * (`HomeChatRequest.kt:233-234`). **No client sends a name**, which is why the
 * label also travels in the body — the phones encrypt the address into
 * `message` (`HomeVm.kt:919-928`).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NoticeLocationTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun read(json: String) = readNotice(Json.parseToJsonElement(json)) { "decrypted:$it" }

    // -- the post body ------------------------------------------------------

    /**
     * The exact bytes a shared location puts on the wire.
     *
     * Pinned against the client's own serialiser rather than a local `Json {}`
     * so a change to `explicitNulls` or `encodeDefaults` fails here rather
     * than in QA.
     */
    @Test
    fun `a location post's body is lat, long and address, and nothing invented`() {
        val body = HttpClientFactory.json.encodeToString(
            NewNoticeDto.serializer(),
            NewNoticeDto(
                unitId = "u1",
                message = "CIPHER",
                messageTranslation = "CIPHER",
                messageType = NoticeKind.Location.wire,
                uniqueId = "local-1",
                messageGroup = 1_700_000_000_000,
                location = GeoPoint(18.94, 72.82, "Aria Hotel, 12 Marine Drive").toDto(),
            ),
        )

        assertEquals(
            """{"unit_id":"u1","message":"CIPHER","message_translation":"CIPHER",""" +
                """"message_type":"location","unique_id":"local-1",""" +
                """"message_group":1700000000000,""" +
                """"location":{"lat":18.94,"long":72.82,"address":"Aria Hotel, 12 Marine Drive"}}""",
            body,
        )
    }

    /**
     * `long`, never `lng` — the wire's spelling on all three clients — and a
     * blank label is left off rather than sent as null. iOS and the web post
     * exactly this two-key object.
     */
    @Test
    fun `an unnamed point sends the two keys the phones agree on`() {
        assertEquals(
            """{"lat":18.94,"long":72.82}""",
            HttpClientFactory.json.encodeToString(GeoPoint(18.94, 72.82).toDto()),
        )
    }

    // -- reading it back ----------------------------------------------------

    /**
     * Android's shape: the point, its address, and three fields describing the
     * PNG it screenshotted (`imageLink`, `height`, `width`). The desktop makes
     * no such file and reads past them.
     */
    @Test
    fun `a post from the phones reads its address off the location object`() {
        val notice = read(
            """{"_id":"n1","message":"CIPHER","message_type":"location",
               "location":{"lat":18.94,"long":72.82,"address":"Aria Hotel, 12 Marine Drive",
               "imageLink":"/data/user/0/map.png","height":1,"width":1}}""",
        )!!

        assertEquals(NoticeKind.Location, notice.kind)
        assertEquals(18.94, notice.location?.lat)
        assertEquals(72.82, notice.location?.long)
        assertEquals("Aria Hotel, 12 Marine Drive", notice.location?.address)
        assertEquals("Aria Hotel", notice.location?.name)
        assertEquals("12 Marine Drive", notice.location?.detail)
    }

    /**
     * The web and iOS send the point alone and put the label in the body. The
     * body is then the address — which is also what the desktop's own echo
     * looks like when the picker gave no name.
     */
    @Test
    fun `a post that named the place only in its body still shows an address`() {
        val notice = read(
            """{"_id":"n1","message":"12 Marine Drive, Mumbai","message_type":"location",
               "location":{"lat":18.94,"long":72.82}}""",
        )!!

        // `decryptBody` is identity-with-a-prefix here; the point is that the
        // body reached the location, not what the cipher was.
        assertEquals("decrypted:12 Marine Drive, Mumbai", notice.location?.address)
        // …and the card does not then print the same line twice.
        assertFalse(notice.showsBody)
    }

    /** The desktop's own echo: what we posted, read back exactly. */
    @Test
    fun `the desktop's echo round-trips its own address`() {
        val notice = read(
            """{"_id":"n1","message":"CIPHER","message_type":"location","unique_id":"local-1",
               "location":{"lat":18.94,"long":72.82,"address":"Aria Hotel, 12 Marine Drive"}}""",
        )!!

        assertEquals("Aria Hotel, 12 Marine Drive", notice.location?.address)
        // The wire's address wins over the body — a caption must never
        // overwrite the place's own name.
        assertEquals("decrypted:CIPHER", notice.body)
        assertTrue(notice.showsBody)
    }

    /** A location reply keeps the same reading — replies carry places too. */
    @Test
    fun `a reply carrying a location reads it the same way`() {
        val notice = read(
            """{"_id":"n1","message":"CIPHER","comments":[
               {"_id":"c1","message":"CIPHER","message_type":"location",
                "location":{"lat":18.94,"long":72.82,"address":"Base Camp"}}]}""",
        )!!

        val reply = notice.comments.single()
        assertEquals(NoticeKind.Location, reply.kind)
        assertEquals("Base Camp", reply.location?.address)
        assertEquals("Base Camp", reply.location?.name)
        assertEquals("", reply.location?.detail)
    }

    /** A point that will not read costs its pin, not the post. */
    @Test
    fun `a location object missing half its point degrades to no location`() {
        assertNull(read("""{"_id":"n1","message_type":"location","location":{"lat":18.94}}""")!!.location)
    }

    /** The link the card opens — the URL the web builds, character for character. */
    @Test
    fun `the maps url is the web's`() {
        assertEquals(
            "https://www.google.com/maps?q=18.94,72.82",
            GeoPoint(18.94, 72.82, "Aria Hotel").mapsUrl,
        )
    }

    // -- the echo -----------------------------------------------------------

    /**
     * The board's own socket echo of a location post, arriving *after* the
     * POST answered. The optimistic card is already the server's copy by
     * then, so the echo matches on `_id` and replaces.
     */
    @Test
    fun `a location echoed back after the post lands does not double the row`() = runTest(dispatcher) {
        val board = LocationBoard()
        val model = viewModel(board)

        model.onEvent(HomeFeedEvent.AttachLocation(POINT))
        advanceUntilIdle()
        model.onEvent(HomeFeedEvent.Send)
        advanceUntilIdle()

        val echoed = board.saved.single()
        model.onEvent(
            HomeFeedEvent.Realtime(HomeRealtimeEvent.NoticeAdded("u1", echoed.copy(localId = null))),
        )
        advanceUntilIdle()

        assertEquals(1, model.currentState.notices.size)
        assertEquals(POINT, model.currentState.notices.single().location)
    }

    /**
     * The same echo arriving *before* the POST answered — the order the media
     * path made routine and the one that crashed the board. The echo carries
     * no `unique_id`, so it appends; the POST response then collapses the two
     * rows into the one post they always were.
     */
    @Test
    fun `a location echoed back mid-flight does not double the row`() = runTest(dispatcher) {
        val board = LocationBoard(hold = true)
        val model = viewModel(board)

        model.onEvent(HomeFeedEvent.AttachLocation(POINT))
        advanceUntilIdle()
        model.onEvent(HomeFeedEvent.Send)
        runCurrent()

        val server = Notice(
            id = "srv-1",
            body = "Aria Hotel, 12 Marine Drive",
            authorName = "You",
            kind = NoticeKind.Location,
            location = POINT,
        )
        model.onEvent(HomeFeedEvent.Realtime(HomeRealtimeEvent.NoticeAdded("u1", server)))
        runCurrent()

        board.gate.complete(server.copy(localId = "local-1"))
        advanceUntilIdle()

        assertEquals(
            1,
            model.currentState.notices.count { it.id == "srv-1" },
            "one place shared, one card — a duplicate id here is the LazyColumn crash",
        )
    }

    /** The merge itself, without a view model: our own post, our own id. */
    @Test
    fun `the merge replaces an optimistic location rather than appending it`() {
        val optimistic = Notice(
            id = "local-1",
            body = "Aria Hotel",
            authorName = "You",
            kind = NoticeKind.Location,
            location = POINT,
            sendState = NoticeSendState.Sending,
            localId = "local-1",
        )
        val server = optimistic.copy(id = "srv-1", sendState = NoticeSendState.Sent)

        val merged = listOf(optimistic)
            .applyRealtime(HomeRealtimeEvent.NoticeAdded("u1", server), selectedUnitId = "u1")

        assertEquals(listOf("srv-1"), merged.map { it.id })
    }

    // -- picking one ---------------------------------------------------------

    /**
     * The composer's Share location, end to end: one pick, one post.
     *
     * With **no map image** — the ordinary desktop case, since `staticMap`
     * answers null on any production without a Google key. The point is the
     * post; the picture never was.
     */
    @Test
    fun `picking a location posts exactly one notice`() = runTest(dispatcher) {
        val board = LocationBoard()
        val model = viewModel(board)

        model.onEvent(HomeFeedEvent.AttachLocation(POINT))
        advanceUntilIdle()

        assertEquals(POINT, model.currentState.draft.location)
        assertNull(model.currentState.draft.media, "no key, no map image — and none is needed")

        model.onEvent(HomeFeedEvent.Send)
        advanceUntilIdle()

        assertEquals(listOf(POINT), board.postedLocations)
        val card = model.currentState.notices.single()
        assertEquals(NoticeKind.Location, card.kind)
        assertEquals(NoticeSendState.Sent, card.sendState)
        // The address became the body, as it does on both phones — the only
        // field iOS and the web read a label out of.
        assertEquals("Aria Hotel, 12 Marine Drive", board.postedBodies.single())
    }

    /**
     * A caption must not cost the pin.
     *
     * Found live on 2026-08-25: typing a caption over a picked place rebuilt
     * the draft as `NoticeDraft(text, media)`, which kept the map image and
     * dropped the location — so the post reached the server as
     * `message_type: "image"` with no point, and rendered on every client as
     * a plain picture of a map. The words a crew member adds are the reason
     * to share a place, so this is the ordinary path, not an edge case.
     */
    @Test
    fun `a typed caption keeps the shared place`() = runTest(dispatcher) {
        val board = LocationBoard()
        val model = viewModel(board)

        model.onEvent(HomeFeedEvent.AttachLocation(POINT))
        advanceUntilIdle()
        model.onEvent(HomeFeedEvent.DraftChanged("Unit base for tomorrow"))
        advanceUntilIdle()

        assertEquals(POINT, model.currentState.draft.location, "typing must not strip the pin")

        model.onEvent(HomeFeedEvent.Send)
        advanceUntilIdle()

        assertEquals(listOf(POINT), board.postedLocations)
        assertEquals(NoticeKind.Location, model.currentState.notices.single().kind)
        // The caption wins over the address, as it does on the phones.
        assertEquals("Unit base for tomorrow", board.postedBodies.single())
    }

    /** No posting rights, no location — the same gate the words go through. */
    @Test
    fun `a reader cannot attach a location`() = runTest(dispatcher) {
        val board = LocationBoard(canPost = false)
        val model = viewModel(board)

        model.onEvent(HomeFeedEvent.AttachLocation(POINT))
        advanceUntilIdle()

        assertNull(model.currentState.draft.location)
        assertTrue(model.currentState.notices.isEmpty())
    }

    private fun viewModel(board: LocationBoard) = HomeFeedViewModel(
        repository = board,
        nowMillis = { 1000 },
        newLocalId = { "local-1" },
        isAdmin = { false },
        // No `staticMap`: the default answers null, which is what a
        // production without a Google Maps key does.
        media = MediaCapture(),
    ).also {
        it.onEvent(HomeFeedEvent.Load)
        dispatcher.scheduler.advanceUntilIdle()
    }

    /** A board that records what was posted, and can stall the POST. */
    private class LocationBoard(
        private val canPost: Boolean = true,
        private val hold: Boolean = false,
    ) : HomeFeedRepository {
        val gate = CompletableDeferred<Notice>()
        val postedLocations = mutableListOf<GeoPoint>()
        val postedBodies = mutableListOf<String>()
        val saved = mutableListOf<Notice>()

        override suspend fun loadUnits() = ZillitResult.Success(
            listOf(HomeUnit("u1", "general_tool", "general_label", canView = true, canPost = canPost)),
        )

        override suspend fun loadNotices(unitId: String, beforeMillis: Long) =
            ZillitResult.Success(emptyList<Notice>())

        override suspend fun postNotice(
            unitId: String,
            text: String,
            localId: String,
            attachment: UploadedNoticeMedia?,
            location: GeoPoint?,
        ): ZillitResult<Notice> {
            if (hold) return ZillitResult.Success(gate.await())
            postedBodies += text
            location?.let { postedLocations += it }
            return ZillitResult.Success(
                Notice(
                    id = "srv-${postedBodies.size}",
                    body = text,
                    authorName = "You",
                    kind = if (location != null) NoticeKind.Location else NoticeKind.Text,
                    location = location,
                    localId = localId,
                ).also { saved += it },
            )
        }

        override suspend fun postComment(noticeId: String, unitId: String, text: String) =
            ZillitResult.Success(emptyList<NoticeComment>())

        override suspend fun editComment(noticeId: String, commentId: String, text: String) =
            ZillitResult.Success(null as NoticeComment?)

        override suspend fun deleteComment(noticeId: String, commentId: String) =
            ZillitResult.Success(Unit)

        override suspend fun editNotice(noticeId: String, text: String) =
            ZillitResult.Success(null as Notice?)

        override suspend fun deleteNotice(noticeId: String) = ZillitResult.Success(Unit)

        override suspend fun readBy(noticeId: String) = ZillitResult.Success(ReadBy())

        override suspend fun notifyUnread(unitId: String, noticeId: String) =
            ZillitResult.Success(Unit)

        override suspend fun forwardNotice(unitId: String, notice: Notice, localId: String) =
            ZillitResult.Success(Unit)

        override suspend fun setPinned(notice: Notice, unitId: String, pinned: Boolean) =
            ZillitResult.Success(Unit)
    }

    private companion object {
        val POINT = GeoPoint(18.94, 72.82, "Aria Hotel, 12 Marine Drive")
    }
}
