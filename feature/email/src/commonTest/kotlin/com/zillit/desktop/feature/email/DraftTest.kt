package com.zillit.desktop.feature.email

import com.zillit.desktop.feature.email.data.InMemoryMailboxCache
import com.zillit.desktop.feature.email.data.Mailbox
import com.zillit.desktop.feature.email.data.readDraft
import com.zillit.desktop.feature.email.domain.ComposeMode
import com.zillit.desktop.feature.email.domain.RichText
import com.zillit.desktop.feature.email.domain.EmailDraft
import com.zillit.desktop.feature.email.domain.EmailFolder
import com.zillit.desktop.feature.email.domain.isWorthSaving
import com.zillit.desktop.feature.email.domain.toSummary
import com.zillit.desktop.feature.email.ui.ComposeEvent
import com.zillit.desktop.feature.email.ui.ComposeViewModel
import com.zillit.desktop.feature.email.ui.Composing
import com.zillit.desktop.feature.email.ui.EmailEvent
import com.zillit.desktop.feature.email.ui.EmailViewModel
import com.zillit.desktop.feature.email.ui.FolderEditor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
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
 * Saving what has been written but not sent.
 *
 * Most of these are about *timing*: a save that fires too often costs a request
 * per keystroke, one that fires too late loses the last sentence, and one that
 * fires after a send resurrects a draft the server just deleted.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DraftTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun composer(server: FakeMailServer, editing: EmailDraft? = null) =
        ComposeViewModel(Composing(server, server, server, server), ComposeMode.New, null, editing)

    private fun type(composer: ComposeViewModel, body: String) {
        composer.onEvent(ComposeEvent.ToChanged("crew@prod.com"))
        composer.onEvent(ComposeEvent.BodyChanged(RichText.plain(body)))
    }

    // -- autosave ----------------------------------------------------------

    @Test
    fun `typing does not save until it stops`() = runTest {
        // One request per keystroke would put a round trip behind every letter.
        val server = FakeMailServer()
        val composer = composer(server)

        type(composer, "Call is at 6")
        advanceTimeBy(500)

        assertTrue(server.savedDrafts.isEmpty(), "saved while still typing")
    }

    @Test
    fun `a pause saves once`() = runTest {
        val server = FakeMailServer()
        val composer = composer(server)

        type(composer, "Call is at 6am")
        advanceUntilIdle()

        assertEquals(1, server.savedDrafts.size)
        assertEquals("Call is at 6am", server.savedDrafts.single().body)
    }

    @Test
    fun `the second save updates the first draft rather than making another`() = runTest {
        // Without this the Drafts folder fills with one copy per pause.
        val server = FakeMailServer()
        val composer = composer(server)

        type(composer, "First")
        advanceUntilIdle()
        composer.onEvent(ComposeEvent.BodyChanged(RichText.plain("First, then more")))
        advanceUntilIdle()

        assertEquals(1, server.savedDrafts.size, "only the first save creates")
        assertEquals(listOf("draft-1"), server.updatedDrafts.map { it.first })
        assertEquals("First, then more", server.updatedDrafts.single().second.body)
    }

    @Test
    fun `an empty composer opened and closed saves nothing`() = runTest {
        // Closing saves, so without the emptiness check this would litter the
        // Drafts folder every time someone opened the composer by mistake.
        val server = FakeMailServer()
        val composer = composer(server)

        composer.onEvent(ComposeEvent.Closing)
        advanceUntilIdle()

        assertTrue(server.savedDrafts.isEmpty())
    }

    @Test
    fun `closing saves immediately rather than waiting out the debounce`() = runTest {
        val server = FakeMailServer()
        val composer = composer(server)

        type(composer, "Half a thought")
        // No time passes: the debounce has not fired.
        composer.onEvent(ComposeEvent.Closing)
        advanceUntilIdle()

        assertEquals(1, server.savedDrafts.size, "the last edit was lost")
        assertEquals("Half a thought", server.savedDrafts.single().body)
    }

    @Test
    fun `a subject alone is worth saving`() = runTest {
        val server = FakeMailServer()
        val composer = composer(server)

        composer.onEvent(ComposeEvent.SubjectChanged("Tomorrow"))
        advanceUntilIdle()

        assertEquals(1, server.savedDrafts.size)
    }

    @Test
    fun `an autosave failure is silent`() = runTest {
        // An error banner over a message someone is still writing is worse than
        // a save that quietly retries on the next pause.
        val server = FakeMailServer().apply { draftSaveFails = true }
        val composer = composer(server)

        type(composer, "Something")
        advanceUntilIdle()

        assertNull(composer.state.value.error)
        assertFalse(composer.state.value.isDraftSaved)
    }

    // -- send and discard --------------------------------------------------

    @Test
    fun `sending carries the draft id so the server clears it`() = runTest {
        // Without this the sent mail leaves a copy in Drafts forever.
        val server = FakeMailServer()
        val composer = composer(server)

        type(composer, "Ready")
        advanceUntilIdle()
        composer.onEvent(ComposeEvent.Send)
        advanceUntilIdle()

        assertEquals("draft-1", server.sent?.draftId)
    }

    @Test
    fun `a pending autosave cannot resurrect a sent draft`() = runTest {
        val server = FakeMailServer()
        val composer = composer(server)

        type(composer, "Ready")
        advanceUntilIdle()
        // Edit, then send before the debounce fires.
        composer.onEvent(ComposeEvent.BodyChanged(RichText.plain("Ready now")))
        composer.onEvent(ComposeEvent.Send)
        advanceUntilIdle()

        assertTrue(
            server.updatedDrafts.isEmpty(),
            "a save landed after the send and recreated the draft",
        )
    }

    @Test
    fun `discarding deletes the draft`() = runTest {
        val server = FakeMailServer()
        val composer = composer(server)

        type(composer, "Never mind")
        advanceUntilIdle()
        composer.onEvent(ComposeEvent.Discard)
        advanceUntilIdle()

        assertEquals(listOf("draft-1"), server.deletedDraftIds)
    }

    @Test
    fun `a discarded draft is not saved again on close`() = runTest {
        val server = FakeMailServer()
        val composer = composer(server)

        type(composer, "Never mind")
        advanceUntilIdle()
        composer.onEvent(ComposeEvent.Discard)
        composer.onEvent(ComposeEvent.Closing)
        advanceUntilIdle()

        assertEquals(1, server.savedDrafts.size, "closing recreated the discarded draft")
        assertTrue(server.updatedDrafts.isEmpty())
    }

    // -- reopening ---------------------------------------------------------

    @Test
    fun `reopening a draft edits it rather than forking a new one`() = runTest {
        val server = FakeMailServer()
        val existing = EmailDraft(
            id = "draft-99",
            to = listOf("crew@prod.com"),
            cc = listOf("ad@prod.com"),
            subject = "Call sheet",
            body = "Draft body",
        )
        val composer = composer(server, editing = existing)

        composer.onEvent(ComposeEvent.BodyChanged(RichText.plain("Draft body, revised")))
        advanceUntilIdle()

        assertTrue(server.savedDrafts.isEmpty(), "it forked instead of updating")
        assertEquals("draft-99", server.updatedDrafts.single().first)
    }

    @Test
    fun `a reopened draft shows its Cc rather than hiding it`() = runTest {
        val existing = EmailDraft(id = "d", to = listOf("a@b.com"), cc = listOf("c@d.com"))

        val composer = composer(FakeMailServer(), editing = existing)

        assertTrue(composer.state.value.showsCopyFields, "the Cc would look lost")
        assertEquals("c@d.com", composer.state.value.ccText)
        assertTrue(composer.state.value.isDraftSaved)
    }

    // -- the Drafts folder -------------------------------------------------

    @Test
    fun `the Drafts folder loads from the draft store, not the uid sync`() = runTest {
        // Drafts are not IMAP. Syncing the IMAP Drafts folder by uid would show
        // an empty folder while the drafts sat somewhere else entirely.
        val server = FakeMailServer()
        server.storedDrafts = listOf(EmailDraft(id = "d1", subject = "Half written"))
        val mailbox = EmailViewModel(
            mailbox = Mailbox(server, InMemoryMailboxCache()),
            repository = server,
            draftRepository = server,
            folderEditor = FolderEditor(server),
            nowMillis = { 1_000L },
        )
        mailbox.onEvent(EmailEvent.Load)
        advanceUntilIdle()

        mailbox.onEvent(EmailEvent.SelectFolder(EmailFolder.DRAFTS))
        advanceUntilIdle()

        assertEquals(listOf("d1"), mailbox.state.value.messages.map { it.id })
        assertTrue(mailbox.state.value.isViewingDrafts)
    }

    @Test
    fun `a draft socket event reloads the Drafts folder`() = runTest {
        val server = FakeMailServer()
        val mailbox = EmailViewModel(
            mailbox = Mailbox(server, InMemoryMailboxCache()),
            repository = server,
            draftRepository = server,
            folderEditor = FolderEditor(server),
            nowMillis = { 1_000L },
        )
        mailbox.onEvent(EmailEvent.Load)
        advanceUntilIdle()
        mailbox.onEvent(EmailEvent.SelectFolder(EmailFolder.DRAFTS))
        advanceUntilIdle()

        server.storedDrafts = listOf(EmailDraft(id = "new", subject = "From another window"))
        mailbox.onEvent(
            EmailEvent.Realtime(com.zillit.desktop.feature.email.domain.EmailRealtimeEvent.DraftsChanged),
        )
        advanceUntilIdle()

        assertEquals(listOf("new"), mailbox.state.value.messages.map { it.id })
    }

    // -- domain ------------------------------------------------------------

    @Test
    fun `a draft row shows its recipients, since it has no sender`() = runTest {
        val summary = EmailDraft(id = "d", to = listOf("a@b.com", "c@d.com")).toSummary()

        assertEquals("a@b.com, c@d.com", summary.from)
        assertTrue(summary.isRead, "something you wrote cannot be unread")
    }

    @Test
    fun `a draft with nothing in it shows placeholders rather than blanks`() = runTest {
        val summary = EmailDraft(id = "d").toSummary()

        assertEquals("(no subject)", summary.subject)
        assertEquals("(no recipient)", summary.from)
    }

    @Test
    fun `emptiness is judged on recipients, subject and body`() {
        assertFalse(com.zillit.desktop.feature.email.domain.OutgoingEmail().isWorthSaving)
        assertTrue(
            com.zillit.desktop.feature.email.domain.OutgoingEmail(subject = "x").isWorthSaving,
        )
        assertTrue(com.zillit.desktop.feature.email.domain.OutgoingEmail(body = "x").isWorthSaving)
        assertTrue(
            com.zillit.desktop.feature.email.domain.OutgoingEmail(to = listOf("a@b.com")).isWorthSaving,
        )
    }

    @Test
    fun `a draft orders by when it was last edited`() {
        // A draft touched this morning belongs above one started last week.
        val row = readDraft(
            Json.parseToJsonElement(
                """{"_id":"d1","subject":"s","created_at":100,"updated_at":900}""",
            ),
        )!!

        assertEquals(900, row.updatedAtMillis)
    }

    @Test
    fun `a never-edited draft falls back to when it was created`() {
        val row = readDraft(Json.parseToJsonElement("""{"_id":"d1","created_at":100}"""))!!

        assertEquals(100, row.updatedAtMillis)
    }

    @Test
    fun `draft recipients read from wrapped objects`() {
        // Drafts come back in the same shape they were sent in.
        val row = readDraft(
            Json.parseToJsonElement(
                """{"_id":"d1","to":[{"email_address":"a@b.com"}],"bcc":[{"email_address":"c@d.com"}]}""",
            ),
        )!!

        assertEquals(listOf("a@b.com"), row.to)
        assertEquals(listOf("c@d.com"), row.bcc)
    }
}
