package com.zillit.desktop.feature.email

import com.zillit.desktop.feature.email.domain.ComposeMode
import com.zillit.desktop.feature.email.ui.ComposerDeck
import com.zillit.desktop.feature.email.ui.ComposerWindow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The rules the dock enforces about what may be open at once.
 *
 * All of these are about not losing somebody's half-written mail, which is the
 * only thing a composer really has to get right.
 */
class ComposerDeckTest {

    private fun deck() = ComposerDeck()

    @Test
    fun `each new composer is its own, even replying twice to one message`() {
        // Two replies to the same mail is ordinary — a correction, or a second
        // thought sent to a different subset of the recipients. Folding the
        // second into the first would look like the click did nothing.
        val deck = deck()
        deck.open(ComposeMode.Reply, replyToId = "m1")
        deck.open(ComposeMode.Reply, replyToId = "m1")

        assertEquals(2, deck.state.value.size)
        assertEquals(2, deck.state.value.map { it.id }.toSet().size)
    }

    @Test
    fun `reopening a draft already open brings it back rather than duplicating it`() {
        // A draft is one thing on the server. Two composers editing it would
        // race each other's autosaves and the loser's typing would vanish.
        val deck = deck()
        deck.openDraft("d1")
        val first = deck.state.value.single().id
        deck.setWindow(first, ComposerWindow.Minimised)

        deck.openDraft("d1")

        val only = deck.state.value.single()
        assertEquals(first, only.id)
        assertEquals(ComposerWindow.Docked, only.window)
    }

    @Test
    fun `two different drafts open side by side`() {
        val deck = deck()
        deck.openDraft("d1")
        deck.openDraft("d2")
        assertEquals(listOf("d1", "d2"), deck.state.value.map { it.draftId })
    }

    @Test
    fun `expanding one minimises the rest rather than closing them`() {
        // The expanded composer fills the mailbox, so anything left standing
        // would be behind it and unreachable. They are unfinished mail, not
        // clutter, so they are minimised rather than discarded.
        val deck = deck()
        deck.open(ComposeMode.New, null)
        deck.open(ComposeMode.New, null)
        val (first, second) = deck.state.value

        deck.setWindow(second.id, ComposerWindow.Expanded)

        val after = deck.state.value.associateBy { it.id }
        assertEquals(ComposerWindow.Expanded, after.getValue(second.id).window)
        assertEquals(ComposerWindow.Minimised, after.getValue(first.id).window)
        assertEquals(2, deck.state.value.size)
    }

    @Test
    fun `opening a composer brings an expanded one back down`() {
        // Otherwise the new composer opens behind the full-screen one and the
        // user sees nothing happen.
        val deck = deck()
        deck.open(ComposeMode.New, null)
        val first = deck.state.value.single().id
        deck.setWindow(first, ComposerWindow.Expanded)

        deck.open(ComposeMode.New, null)

        assertTrue(deck.state.value.none { it.window == ComposerWindow.Expanded })
        assertEquals(ComposerWindow.Docked, deck.state.value.first().window)
    }

    @Test
    fun `minimising one leaves the others where they were`() {
        val deck = deck()
        deck.open(ComposeMode.New, null)
        deck.open(ComposeMode.New, null)
        val (first, second) = deck.state.value

        deck.setWindow(first.id, ComposerWindow.Minimised)

        val after = deck.state.value.associateBy { it.id }
        assertEquals(ComposerWindow.Minimised, after.getValue(first.id).window)
        assertEquals(ComposerWindow.Docked, after.getValue(second.id).window)
    }

    @Test
    fun `the newest composer is last, so it takes the corner`() {
        val deck = deck()
        deck.open(ComposeMode.New, null)
        deck.open(ComposeMode.Reply, replyToId = "m1")
        assertEquals(ComposeMode.Reply, deck.state.value.last().mode)
    }

    @Test
    fun `closing removes only the one asked for`() {
        val deck = deck()
        deck.open(ComposeMode.New, null)
        deck.open(ComposeMode.New, null)
        val (first, second) = deck.state.value

        deck.close(first.id)

        assertEquals(listOf(second.id), deck.state.value.map { it.id })
    }

    @Test
    fun `a reply carries the message it answers`() {
        val deck = deck()
        deck.open(ComposeMode.ReplyAll, replyToId = "m9")
        val composer = deck.state.value.single()
        assertEquals(ComposeMode.ReplyAll, composer.mode)
        assertEquals("m9", composer.replyToId)
        assertEquals(null, composer.draftId)
    }

    @Test
    fun `clearing closes everything`() {
        val deck = deck()
        deck.open(ComposeMode.New, null)
        deck.openDraft("d1")
        deck.clear()
        assertTrue(deck.state.value.isEmpty())
    }
}
