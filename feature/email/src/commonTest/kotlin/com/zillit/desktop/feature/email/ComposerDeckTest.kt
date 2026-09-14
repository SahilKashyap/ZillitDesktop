package com.zillit.desktop.feature.email

import com.zillit.desktop.feature.email.domain.ComposeMode
import com.zillit.desktop.feature.email.domain.EmailMessage
import com.zillit.desktop.feature.email.ui.ComposeViewModel
import com.zillit.desktop.feature.email.ui.ComposerDeck
import com.zillit.desktop.feature.email.ui.ComposerWindow
import com.zillit.desktop.feature.email.ui.Composing
import com.zillit.desktop.feature.email.ui.OpenComposer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The rules the deck enforces about what may be open at once.
 *
 * All of these are about not losing somebody's half-written mail, which is the
 * only thing a composer really has to get right.
 */
class ComposerDeckTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private val server = FakeMailServer()
    private val built = mutableListOf<OpenComposer>()

    private fun deck() = ComposerDeck(
        factory = { composer ->
            built += composer
            ComposeViewModel(Composing(server, server, server, server), composer.mode, composer.replyTo)
        },
    )

    private val message = EmailMessage(id = "m1", threadId = "m1", subject = "Hi", from = "a@b.com")

    @Test
    fun `each new composer is its own, even replying twice to one message`() {
        // Two replies to the same mail is ordinary — a correction, or a second
        // thought sent to a different subset of the recipients. Both are
        // built; the second takes the pane, the first is closed saving.
        val deck = deck()
        deck.open(ComposeMode.Reply, replyTo = message)
        deck.open(ComposeMode.Reply, replyTo = message)

        assertEquals(2, built.size)
        assertEquals(2, built.map { it.id }.toSet().size)
    }

    @Test
    fun `one composer stands in the pane, opening another replaces it`() {
        // The web has one inline composer; a second replaces it. The first
        // is closed saving, so nothing typed into it is lost.
        val deck = deck()
        val first = deck.open(ComposeMode.New, replyTo = null)
        val second = deck.open(ComposeMode.New, replyTo = null)

        assertEquals(listOf(second.id), deck.state.value.map { it.id })
        assertNull(deck.viewModel(first.id), "the replaced composer's view model is retired")
        assertNotNull(deck.viewModel(second.id))
        assertEquals(second, deck.inline)
    }

    @Test
    fun `reopening a draft already open brings it back rather than duplicating it`() {
        // A draft is one thing on the server. Two composers editing it would
        // race each other's autosaves and the loser's typing would vanish.
        val deck = deck()
        val first = deck.openDraft("d1")
        deck.popOut(first.id)

        val again = deck.openDraft("d1")

        assertEquals(first.id, again.id)
        assertEquals(1, deck.state.value.size)
    }

    @Test
    fun `a popped-out composer keeps its view model and stays beside a new inline one`() {
        val deck = deck()
        val first = deck.open(ComposeMode.New, replyTo = null)
        val viewModel = deck.viewModel(first.id)
        deck.popOut(first.id)

        val second = deck.open(ComposeMode.New, replyTo = null)

        assertEquals(ComposerWindow.PoppedOut, deck.state.value.first { it.id == first.id }.window)
        assertSame(viewModel, deck.viewModel(first.id), "popping out must not rebuild the composer")
        assertEquals(second, deck.inline)
        assertEquals(2, deck.state.value.size)
    }

    @Test
    fun `a popped-out composer answers to its route`() {
        val deck = deck()
        val composer = deck.open(ComposeMode.New, replyTo = null)
        deck.popOut(composer.id)
        assertEquals(composer.id, deck.atRoute("/email/compose/${composer.id}")?.id)
        assertEquals(ComposerWindow.PoppedOut, deck.atRoute("/email/compose/${composer.id}")?.window)
        assertNull(deck.atRoute("/email/compose/nope"))
    }

    @Test
    fun `closing removes only the one asked for`() {
        val deck = deck()
        val first = deck.open(ComposeMode.New, replyTo = null)
        deck.popOut(first.id)
        val second = deck.open(ComposeMode.New, replyTo = null)

        deck.close(first.id)

        assertEquals(listOf(second.id), deck.state.value.map { it.id })
        assertNull(deck.viewModel(first.id))
    }

    @Test
    fun `a reply carries the message it answers`() {
        val deck = deck()
        deck.open(ComposeMode.ReplyAll, replyTo = message)
        val composer = deck.state.value.single()
        assertEquals(ComposeMode.ReplyAll, composer.mode)
        assertEquals("m1", composer.replyToId)
        assertEquals(null, composer.draftId)
    }

    @Test
    fun `clearing closes everything`() {
        val deck = deck()
        val first = deck.open(ComposeMode.New, replyTo = null)
        deck.popOut(first.id)
        deck.openDraft("d1")
        deck.clear()
        assertTrue(deck.state.value.isEmpty())
        assertNull(deck.viewModel(first.id))
    }

    @Test
    fun `an untouched composer closing saves nothing`() {
        // Closing saves — but only a composer somebody typed into. An empty
        // one opened and closed must not litter the Drafts folder.
        val deck = deck()
        val composer = deck.open(ComposeMode.New, replyTo = null)
        deck.close(composer.id)
        dispatcher.scheduler.advanceUntilIdle()
        assertTrue(server.savedDrafts.isEmpty())
    }
}
