package com.zillit.desktop.feature.email

import com.zillit.desktop.feature.email.data.InMemoryMailboxCache
import com.zillit.desktop.feature.email.data.Mailbox
import com.zillit.desktop.feature.email.domain.EmailDraft
import com.zillit.desktop.feature.email.domain.EmailFolder
import com.zillit.desktop.feature.email.ui.EmailEffect
import com.zillit.desktop.feature.email.ui.EmailEvent
import com.zillit.desktop.feature.email.ui.EmailViewModel
import com.zillit.desktop.feature.email.ui.FolderEditor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Who gets the reading pane when a composer is standing in it.
 *
 * The complaint this answers: with the composer up, clicking another message
 * did nothing — the composer covers the pane, so the mail opened behind it and
 * stayed invisible. The web's answer is that every list card goes through
 * `handleFetchEmail`, which strips `tab=compose-mail`; the composer unmounts
 * and its autosaved draft is in Drafts. These are the rules on this side of
 * that: the user's click hands the pane over, a sync's auto-open does not.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ComposerHandoverTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun mailbox(server: FakeMailServer) = EmailViewModel(
        mailbox = Mailbox(server, InMemoryMailboxCache()),
        repository = server,
        draftRepository = server,
        folderEditor = FolderEditor(server),
        nowMillis = { 1_000L },
    )

    @Test
    fun `clicking another message hands the pane over to it`() = runTest(dispatcher) {
        val server = FakeMailServer()
        val mailbox = mailbox(server)
        val effects = mutableListOf<EmailEffect>()
        val collector = launch { mailbox.effects.toList(effects) }
        mailbox.onEvent(EmailEvent.Load)
        advanceUntilIdle()
        effects.clear()

        mailbox.onEvent(EmailEvent.SelectMessage("m1"))
        advanceUntilIdle()

        assertTrue(
            effects.contains(EmailEffect.CloseComposer),
            "the message would have opened behind the composer, where nobody can see it",
        )
        assertEquals("m1", mailbox.state.value.openRowId)
        collector.cancel()
    }

    @Test
    fun `the auto-open leaves a composer alone`() = runTest(dispatcher) {
        // A full sync finishing mid-compose must not close the message being
        // written — the web guards the same case in `useEmailListData`.
        val server = FakeMailServer()
        val mailbox = mailbox(server)
        val effects = mutableListOf<EmailEffect>()
        val collector = launch { mailbox.effects.toList(effects) }

        mailbox.onEvent(EmailEvent.Load)
        advanceUntilIdle()

        assertEquals("m3", mailbox.state.value.openRowId, "the newest row opens on its own")
        assertFalse(effects.contains(EmailEffect.CloseComposer), "a sync closed the composer")
        collector.cancel()
    }

    @Test
    fun `reopening the first row after a composer closes does not close another`() = runTest(dispatcher) {
        // `OpenFirst` is what the pane falls back to once a composer closes;
        // it must not ask for a second close and take a newly opened one with it.
        val server = FakeMailServer()
        val mailbox = mailbox(server)
        val effects = mutableListOf<EmailEffect>()
        val collector = launch { mailbox.effects.toList(effects) }
        mailbox.onEvent(EmailEvent.Load)
        advanceUntilIdle()
        effects.clear()

        mailbox.onEvent(EmailEvent.OpenFirst)
        advanceUntilIdle()

        assertFalse(effects.contains(EmailEffect.CloseComposer))
        collector.cancel()
    }

    @Test
    fun `a draft row is left to the deck`() = runTest(dispatcher) {
        // A draft opens *in* the composer, and the deck replaces the one in the
        // pane itself. Closing first would save this draft and then reopen it
        // from a list that has not been re-read, which is how typing gets lost.
        val server = FakeMailServer()
        server.storedDrafts = listOf(EmailDraft(id = "d1", subject = "Half written"))
        val mailbox = mailbox(server)
        val effects = mutableListOf<EmailEffect>()
        val collector = launch { mailbox.effects.toList(effects) }
        mailbox.onEvent(EmailEvent.Load)
        advanceUntilIdle()
        mailbox.onEvent(EmailEvent.SelectFolder(EmailFolder.DRAFTS))
        advanceUntilIdle()
        effects.clear()

        mailbox.onEvent(EmailEvent.SelectMessage("d1"))
        advanceUntilIdle()

        assertEquals(listOf(EmailEffect.OpenDraft("d1")), effects.toList())
        collector.cancel()
    }
}
