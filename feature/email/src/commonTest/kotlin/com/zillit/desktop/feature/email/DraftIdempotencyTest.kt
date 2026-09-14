package com.zillit.desktop.feature.email

import com.zillit.desktop.feature.email.domain.ComposeMode
import com.zillit.desktop.feature.email.domain.EmailDraft
import com.zillit.desktop.feature.email.domain.RichText
import com.zillit.desktop.feature.email.ui.ComposeEvent
import com.zillit.desktop.feature.email.ui.RecipientField
import com.zillit.desktop.feature.email.ui.ComposeViewModel
import com.zillit.desktop.feature.email.ui.Composing
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
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The `unique_id` a compose session sends with its draft create.
 *
 * A create whose answer is lost leaves the composer without a draft id, so the
 * next autosave creates again. The server folds a repeated key onto the first
 * record, which only works if the client sends the *same* key on the retry —
 * every case here is about when the key stays and when it changes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DraftIdempotencyTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun composer(server: FakeMailServer, deps: Composing = Composing(server, server, server, server)) =
        ComposeViewModel(deps, ComposeMode.New, null, null)

    private fun type(composer: ComposeViewModel, body: String) {
        composer.onEvent(recipientsTyped(RecipientField.To, "crew@prod.com"))
        composer.onEvent(ComposeEvent.BodyChanged(RichText.plain(body)))
    }

    @Test
    fun `a create carries the composer's key`() = runTest {
        val server = FakeMailServer()
        val composer = composer(server, Composing(server, server, server, server, newDraftKey = { "key-1" }))

        type(composer, "Hello")
        advanceUntilIdle()

        assertEquals(listOf("key-1"), server.savedDraftKeys)
    }

    @Test
    fun `a retried create sends the same key, not a fresh one`() = runTest {
        // The point of the key. A new one per attempt would be a new draft per
        // attempt on the server — the duplicate the key exists to prevent.
        var minted = 0
        val server = FakeMailServer().apply { draftSaveFails = true }
        val composer = composer(server, Composing(server, server, server, server, newDraftKey = { "key-${++minted}" }))

        type(composer, "First try")
        advanceUntilIdle()

        server.draftSaveFails = false
        composer.onEvent(ComposeEvent.BodyChanged(RichText.plain("Second try")))
        advanceUntilIdle()

        assertEquals(listOf("key-1", "key-1"), server.savedDraftKeys, "the retry changed its key")
        assertEquals(1, minted, "a key was minted per attempt rather than per composer")
    }

    @Test
    fun `once the create has answered, later saves update that id`() = runTest {
        val server = FakeMailServer()
        val composer = composer(server)

        type(composer, "One")
        advanceUntilIdle()
        composer.onEvent(ComposeEvent.BodyChanged(RichText.plain("Two")))
        advanceUntilIdle()
        composer.onEvent(ComposeEvent.BodyChanged(RichText.plain("Three")))
        advanceUntilIdle()

        assertEquals(1, server.savedDraftKeys.size, "created more than once")
        assertEquals(listOf("draft-1", "draft-1"), server.updatedDrafts.map { it.first })
    }

    @Test
    fun `two composers have two keys`() = runTest {
        // With the default generator, so the shape a host gets without wiring
        // anything is the one under test: a shared key would make the server
        // fold every new message onto the first one written.
        val server = FakeMailServer()
        val first = composer(server)
        val second = composer(server)

        type(first, "One message")
        type(second, "Another message")
        advanceUntilIdle()

        assertEquals(2, server.savedDraftKeys.size)
        assertNotEquals(server.savedDraftKeys[0], server.savedDraftKeys[1])
        assertTrue(server.savedDraftKeys.all { it.isNotBlank() && it.length <= 128 })
    }

    @Test
    fun `a reopened draft never creates, so sends no key`() = runTest {
        val server = FakeMailServer()
        val composer = ComposeViewModel(
            Composing(server, server, server, server),
            ComposeMode.New,
            null,
            EmailDraft(id = "d1", to = listOf("crew@prod.com"), body = "Saved earlier"),
        )

        composer.onEvent(ComposeEvent.BodyChanged(RichText.plain("Saved earlier, and more")))
        advanceUntilIdle()

        assertTrue(server.savedDraftKeys.isEmpty())
        assertEquals(listOf("d1"), server.updatedDrafts.map { it.first })
    }
}
