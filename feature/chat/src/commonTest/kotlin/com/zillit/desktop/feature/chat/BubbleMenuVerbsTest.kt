package com.zillit.desktop.feature.chat

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.chat.data.ChatRepository
import com.zillit.desktop.feature.chat.data.editEnvelope
import com.zillit.desktop.feature.chat.data.readByFrom
import com.zillit.desktop.feature.chat.domain.ChatAttachment
import com.zillit.desktop.feature.chat.domain.ChatComposerRules
import com.zillit.desktop.feature.chat.domain.ChatLocation
import com.zillit.desktop.feature.chat.domain.ChatMessage
import com.zillit.desktop.feature.chat.domain.ChatTranslator
import com.zillit.desktop.feature.chat.domain.CrewContact
import com.zillit.desktop.feature.chat.domain.ForwardTarget
import com.zillit.desktop.feature.chat.domain.GroupRoom
import com.zillit.desktop.feature.chat.domain.ReadByReport
import com.zillit.desktop.feature.chat.ui.ChatEvent
import com.zillit.desktop.feature.chat.ui.ChatViewModel
import com.zillit.desktop.feature.chat.ui.FORWARDED
import com.zillit.desktop.feature.chat.ui.TRANSLATED
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The bubble menu's verbs beyond Reply/Copy/Delete — Edit, Forward, Read by,
 * Translate — as the web's `MyMessage`/`SenderMessage` menus do them:
 * the edit clock and its admin exception, the edit wire and the ack it
 * takes, a forward as one plain send per destination, the readers
 * answer's shape, and Translate's gate on the two languages.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BubbleMenuVerbsTest {

    private val dispatcher = StandardTestDispatcher()
    private val aisha = CrewContact(userId = "u-aisha", fullName = "Aisha Khan")
    private val room = GroupRoom(id = "room-1", name = "Camera")

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    /** The fake with the menu's two new repository verbs, recording what they took. */
    private class VerbsFake(
        val base: FakeChatRepository = FakeChatRepository(),
        var editRefusal: String? = null,
        var readers: ReadByReport = ReadByReport(),
    ) : ChatRepository by base {
        val rewrites = mutableListOf<Triple<String, String, Boolean>>()
        val socketEdits = MutableSharedFlow<ChatMessage>(extraBufferCapacity = 4)
        override val edits: Flow<ChatMessage> get() = socketEdits

        override suspend fun editMessage(messageId: String, body: String, isGroup: Boolean): ZillitResult<ChatMessage> {
            rewrites += Triple(messageId, body, isGroup)
            editRefusal?.let { return ZillitResult.Failure(ZillitError.Unknown(it)) }
            return ZillitResult.Success(
                ChatMessage(
                    id = messageId, uniqueId = "w-$messageId", senderId = "me", receiverId = "u-aisha",
                    body = "", timestampMillis = 1L, isMine = true, isEdited = true,
                ),
            )
        }

        override suspend fun readBy(messageId: String): ZillitResult<ReadByReport> = ZillitResult.Success(readers)
    }

    private class FakeTranslator(
        private val project: String,
        private val device: String,
        private val answer: String = "Bonjour",
    ) : ChatTranslator {
        val asked = mutableListOf<Pair<String, String>>()
        override fun projectLanguage(): String = project
        override fun deviceLanguage(): String = device
        override suspend fun translate(text: String, toLanguage: String): ZillitResult<String> {
            asked += text to toLanguage
            return ZillitResult.Success(answer)
        }
    }

    private fun viewModel(
        repository: ChatRepository,
        now: Long = NOW,
        admin: Boolean = false,
        translator: ChatTranslator? = null,
    ) = ChatViewModel(
        repository = repository,
        nowMillis = { now },
        newUniqueId = { "unique-${counter++}" },
        isAdmin = { admin },
        translator = translator,
    )

    private var counter = 1

    private fun mine(id: String, body: String, createdAt: Long = NOW - MINUTE, attachment: ChatAttachment? = null) =
        ChatMessage(
            id = id, uniqueId = "w-$id", senderId = "me", receiverId = "u-aisha",
            body = body, timestampMillis = createdAt, isMine = true, attachment = attachment,
        )

    private fun theirs(id: String, body: String) = ChatMessage(
        id = id, uniqueId = "w-$id", senderId = "u-aisha", receiverId = "me",
        body = body, timestampMillis = NOW - MINUTE, isMine = false,
    )

    private fun ChatViewModel.openDm() {
        onEvent(ChatEvent.OpenThread(aisha))
    }

    // -- the rule --------------------------------------------------------------

    @Test
    fun `the rewrite window is two hours, lifted for an admin`() {
        assertTrue(ChatComposerRules.canRewrite(createdMillis = 0L, nowMillis = 2 * HOUR, isAdmin = false))
        assertFalse(ChatComposerRules.canRewrite(createdMillis = 0L, nowMillis = 2 * HOUR + 1, isAdmin = false))
        assertTrue(ChatComposerRules.canRewrite(createdMillis = 0L, nowMillis = 30 * HOUR, isAdmin = true))
    }

    // -- the wire --------------------------------------------------------------

    @Test
    fun `the edit emit carries the id and the cipher twice over`() {
        val envelope = editEnvelope("srv-1", "cafe01")
        assertEquals("srv-1", envelope["_id"]!!.jsonPrimitive.content)
        assertEquals("cafe01", envelope["message"]!!.jsonPrimitive.content)
        assertEquals("cafe01", envelope["message_translation"]!!.jsonPrimitive.content)
    }

    @Test
    fun `the readers answer is read from data, stamps and flags alike`() {
        val report = readByFrom(
            Json.parseToJsonElement(
                """{"status":1,"data":{
                    "message_read_by":[{"userId":"u1","read_time":1700000000000,"delivered":0}],
                    "message_unread_by":[{"userId":"u2","delivered":1699999999000},{"userId":"u3","delivered":false}]
                }}""",
            ),
        )
        assertEquals(listOf("u1"), report.read.map { it.userId })
        assertEquals(1700000000000L, report.read.single().readAtMillis)
        // A literal zero is kept — the web prints "Today" for it.
        assertEquals(0L, report.read.single().deliveredAtMillis)
        assertEquals(listOf(true, false), report.unread.map { it.isDelivered })
    }

    // -- edit ------------------------------------------------------------------

    @Test
    fun `Edit opens on the line's words and Save rewrites it on the ack`() = runTest(dispatcher) {
        val repository = VerbsFake()
        val model = viewModel(repository)
        model.openDm()
        advanceUntilIdle()
        model.onEvent(ChatEvent.Arrived(mine("srv-1", "Parking at the church")))

        model.onEvent(ChatEvent.StartEdit("srv-1"))
        assertEquals("Parking at the church", model.currentState.editDraft)
        model.onEvent(ChatEvent.EditDraftChanged("Parking at the school"))
        model.onEvent(ChatEvent.SubmitEdit)
        advanceUntilIdle()

        assertEquals(Triple("srv-1", "Parking at the school", false), repository.rewrites.single())
        val line = model.currentState.messages.single()
        assertEquals("Parking at the school", line.body)
        assertTrue(line.isEdited)
        assertNull(model.currentState.editing, "the dialog closes on Save")
    }

    @Test
    fun `unchanged words close the dialog without a round trip`() = runTest(dispatcher) {
        val repository = VerbsFake()
        val model = viewModel(repository)
        model.openDm()
        advanceUntilIdle()
        model.onEvent(ChatEvent.Arrived(mine("srv-1", "Same")))

        model.onEvent(ChatEvent.StartEdit("srv-1"))
        model.onEvent(ChatEvent.EditDraftChanged("  Same "))
        model.onEvent(ChatEvent.SubmitEdit)
        advanceUntilIdle()

        assertTrue(repository.rewrites.isEmpty())
        assertNull(model.currentState.editing)
    }

    @Test
    fun `a refused edit leaves the words alone and says why`() = runTest(dispatcher) {
        val repository = VerbsFake(editRefusal = "cnc_message_not_found")
        val model = viewModel(repository)
        model.openDm()
        advanceUntilIdle()
        model.onEvent(ChatEvent.Arrived(mine("srv-1", "Before")))

        model.onEvent(ChatEvent.StartEdit("srv-1"))
        model.onEvent(ChatEvent.EditDraftChanged("After"))
        model.onEvent(ChatEvent.SubmitEdit)
        advanceUntilIdle()

        assertEquals("Before", model.currentState.messages.single().body)
        assertNotNull(model.currentState.error)
    }

    @Test
    fun `the clock refuses Edit and Delete after two hours, unless admin`() = runTest(dispatcher) {
        val repository = VerbsFake()
        val old = mine("srv-old", "Yesterday", createdAt = NOW - 3 * HOUR)

        val crew = viewModel(repository)
        crew.openDm()
        advanceUntilIdle()
        crew.onEvent(ChatEvent.Arrived(old))
        crew.onEvent(ChatEvent.StartEdit("srv-old"))
        assertNull(crew.currentState.editing)
        assertEquals(ChatComposerRules.REWRITE_WINDOW_CLOSED, crew.currentState.error)
        crew.onEvent(ChatEvent.DismissError)
        crew.onEvent(ChatEvent.Delete("srv-old"))
        advanceUntilIdle()
        assertEquals(1, crew.currentState.messages.size, "the line stays")
        assertEquals(ChatComposerRules.REWRITE_WINDOW_CLOSED, crew.currentState.error)

        val admin = viewModel(repository, admin = true)
        admin.openDm()
        advanceUntilIdle()
        admin.onEvent(ChatEvent.Arrived(old))
        admin.onEvent(ChatEvent.StartEdit("srv-old"))
        assertNotNull(admin.currentState.editing, "an admin's clock never runs out")
    }

    @Test
    fun `an edit from the socket changes the line in place`() = runTest(dispatcher) {
        val repository = VerbsFake()
        val model = viewModel(repository)
        model.openDm()
        advanceUntilIdle()
        model.onEvent(ChatEvent.Arrived(theirs("srv-9", "Where are the vans?")))

        repository.socketEdits.emit(theirs("srv-9", "Where are the trucks?").copy(isEdited = true))
        advanceUntilIdle()

        val line = model.currentState.messages.single()
        assertEquals("Where are the trucks?", line.body)
        assertTrue(line.isEdited)
        assertEquals(1, model.currentState.messages.size, "not appended as a new line")
    }

    // -- forward ---------------------------------------------------------------

    @Test
    fun `Forward sends one plain copy per destination, file and place included`() = runTest(dispatcher) {
        val repository = VerbsFake()
        val model = viewModel(repository)
        model.openDm()
        advanceUntilIdle()
        val file = ChatAttachment(media = "chat/one.pdf", name = "one.pdf", contentType = "application/pdf")
        model.onEvent(ChatEvent.Arrived(theirs("srv-2", "The schedule").copy(attachment = file)))

        model.onEvent(ChatEvent.StartForward("srv-2"))
        assertEquals("srv-2", model.currentState.forwarding?.id)
        model.onEvent(
            ChatEvent.ForwardTo(
                listOf(ForwardTarget("room-1", isGroup = true), ForwardTarget("u-bob", isGroup = false)),
            ),
        )
        advanceUntilIdle()

        assertNull(model.currentState.forwarding, "the picker closes")
        val copies = repository.base.delivered
        assertEquals(listOf("room-1", "u-bob"), copies.map { it.receiverId })
        assertTrue(copies.all { it.body == "The schedule" && it.attachment == file })
        assertEquals(2, copies.map { it.uniqueId }.distinct().size, "each copy has its own unique id")
        assertEquals(FORWARDED, model.currentState.info)
    }

    @Test
    fun `a forwarded place keeps its pin`() = runTest(dispatcher) {
        val repository = VerbsFake()
        val model = viewModel(repository)
        model.openDm()
        advanceUntilIdle()
        val place = ChatLocation(address = "Pinewood", lat = 51.5, lng = -0.6)
        model.onEvent(ChatEvent.Arrived(theirs("srv-3", "Pinewood").copy(location = place)))

        model.onEvent(ChatEvent.StartForward("srv-3"))
        model.onEvent(ChatEvent.ForwardTo(listOf(ForwardTarget("u-bob", isGroup = false))))
        advanceUntilIdle()

        assertEquals(place, repository.base.delivered.single().location)
    }

    // -- read by ---------------------------------------------------------------

    @Test
    fun `Read by opens loading and fills with the server's lists`() = runTest(dispatcher) {
        val repository = VerbsFake(
            readers = ReadByReport(
                read = listOf(com.zillit.desktop.feature.chat.domain.ReadByRow("u-aisha", readAtMillis = NOW)),
                unread = listOf(com.zillit.desktop.feature.chat.domain.ReadByRow("u-bob")),
            ),
        )
        val model = viewModel(repository)
        model.onEvent(ChatEvent.OpenGroup(room))
        advanceUntilIdle()
        model.onEvent(ChatEvent.Arrived(mine("srv-4", "Call time 6am").copy(receiverId = "room-1", isGroup = true)))

        model.onEvent(ChatEvent.ShowReadBy("srv-4"))
        assertTrue(model.currentState.readBy?.isLoading == true)
        advanceUntilIdle()

        val view = assertNotNull(model.currentState.readBy)
        assertEquals(listOf("u-aisha"), view.report?.read?.map { it.userId })
        assertEquals(listOf("u-bob"), view.report?.unread?.map { it.userId })
        model.onEvent(ChatEvent.DismissReadBy)
        assertNull(model.currentState.readBy)
    }

    // -- translate -------------------------------------------------------------

    @Test
    fun `Translate is offered only when the production and the computer disagree`() = runTest(dispatcher) {
        val same = viewModel(VerbsFake(), translator = FakeTranslator(project = "en", device = "en"))
        same.onEvent(ChatEvent.RefreshRecents)
        advanceUntilIdle()
        assertFalse(same.currentState.translateOffered)

        val differ = viewModel(VerbsFake(), translator = FakeTranslator(project = "fr", device = "en"))
        differ.onEvent(ChatEvent.RefreshRecents)
        advanceUntilIdle()
        assertTrue(differ.currentState.translateOffered)

        val none = viewModel(VerbsFake())
        none.onEvent(ChatEvent.RefreshRecents)
        advanceUntilIdle()
        assertFalse(none.currentState.translateOffered, "no translator, no item")
    }

    @Test
    fun `Translate keeps the answer beside the line and Show original takes it back`() = runTest(dispatcher) {
        val translator = FakeTranslator(project = "fr", device = "en", answer = "Hello")
        val model = viewModel(VerbsFake(), translator = translator)
        model.openDm()
        advanceUntilIdle()
        model.onEvent(ChatEvent.Arrived(theirs("srv-5", "Bonjour")))

        model.onEvent(ChatEvent.Translate("srv-5"))
        advanceUntilIdle()

        assertEquals("Bonjour" to "en", translator.asked.single(), "translated INTO the computer's language")
        assertEquals("Hello", model.currentState.translations["srv-5"])
        assertEquals("Bonjour", model.currentState.messages.single().body, "the original stays")
        assertEquals(TRANSLATED, model.currentState.info)

        model.onEvent(ChatEvent.ShowOriginal("srv-5"))
        assertNull(model.currentState.translations["srv-5"])
    }

    private companion object {
        const val MINUTE = 60_000L
        const val HOUR = 60 * MINUTE
        const val NOW = 1_700_000_000_000L
    }
}
