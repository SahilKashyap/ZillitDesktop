package com.zillit.desktop.feature.email

import com.zillit.desktop.feature.email.domain.ComposeMode
import com.zillit.desktop.feature.email.domain.RichText
import com.zillit.desktop.feature.email.ui.ComposeEvent
import com.zillit.desktop.feature.email.ui.RecipientField
import com.zillit.desktop.feature.email.ui.ComposeViewModel
import com.zillit.desktop.feature.email.ui.Composing
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Saves that overlap.
 *
 * These are the cases QA surfaced: a `JobCancellationException` on the draft
 * endpoint, from a debounce cancelling a request that was already in flight.
 * Cancelling a *create* loses the id the server assigned, and the next save
 * then creates a second draft — so the user's Drafts folder fills with copies
 * of one message.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DraftRaceTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun composer(server: FakeMailServer) =
        ComposeViewModel(Composing(server, server, server, server), ComposeMode.New, null, null)

    @Test
    fun `typing during an in-flight create does not produce a second draft`() = runTest {
        val server = FakeMailServer()
        val composer = composer(server)

        // Hold the create open, so the next edit lands while it is unfinished.
        val gate = CompletableDeferred<Unit>()
        server.draftGate = gate

        composer.onEvent(recipientsTyped(RecipientField.To, "crew@prod.com"))
        composer.onEvent(ComposeEvent.BodyChanged(RichText.plain("First")))
        advanceUntilIdle()

        composer.onEvent(ComposeEvent.BodyChanged(RichText.plain("First, then more")))
        advanceUntilIdle()

        server.draftGate = null
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(1, server.savedDrafts.size, "the draft was created twice")
        assertEquals(
            listOf("draft-1"),
            server.updatedDrafts.map { it.first },
            "the second edit should have updated the first draft",
        )
    }

    @Test
    fun `sending while a create is in flight leaves no orphan draft`() = runTest {
        // The send carries whatever draft id exists. A create finishing
        // afterwards would leave a draft the send never named, and nothing
        // would ever clear it.
        val server = FakeMailServer()
        val composer = composer(server)

        val gate = CompletableDeferred<Unit>()
        server.draftGate = gate

        composer.onEvent(recipientsTyped(RecipientField.To, "crew@prod.com"))
        composer.onEvent(ComposeEvent.BodyChanged(RichText.plain("Ready")))
        advanceUntilIdle()

        composer.onEvent(ComposeEvent.Send)
        advanceUntilIdle()

        server.draftGate = null
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(1, server.savedDrafts.size, "a second draft was created around the send")
        assertTrue(server.updatedDrafts.isEmpty(), "a save landed after the send")
    }

    @Test
    fun `closing while a save is in flight still records the final text`() = runTest {
        val server = FakeMailServer()
        val composer = composer(server)

        val gate = CompletableDeferred<Unit>()
        server.draftGate = gate

        composer.onEvent(recipientsTyped(RecipientField.To, "crew@prod.com"))
        composer.onEvent(ComposeEvent.BodyChanged(RichText.plain("Half")))
        advanceUntilIdle()

        composer.onEvent(ComposeEvent.BodyChanged(RichText.plain("Half, then the rest")))
        composer.onEvent(ComposeEvent.Closing)
        advanceUntilIdle()

        server.draftGate = null
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(
            "Half, then the rest",
            server.updatedDrafts.last().second.body,
            "the last thing typed was not saved",
        )
    }
}
