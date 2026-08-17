package com.zillit.desktop.feature.email

import com.zillit.desktop.feature.email.domain.EmailSignature
import com.zillit.desktop.feature.email.domain.RichText
import com.zillit.desktop.feature.email.domain.TextMark
import com.zillit.desktop.feature.email.ui.SignatureEvent
import com.zillit.desktop.feature.email.ui.SignatureManagerViewModel
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
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Creating, editing and deleting sign-offs.
 *
 * The rule worth pinning is exclusivity: the composer picks the *first*
 * signature marked for a kind of message, so two marked the same way would make
 * which one wins depend on the server's ordering.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SignatureManagerTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private val work = EmailSignature("s1", "Work", "<b>Aisha</b>", useForNew = true)
    private val short = EmailSignature("s2", "Short", "Aisha")

    private fun manager(server: FakeMailServer) = SignatureManagerViewModel(server)

    private suspend fun loaded(server: FakeMailServer): SignatureManagerViewModel {
        val manager = manager(server)
        manager.onEvent(SignatureEvent.Load)
        return manager
    }

    // -- creating ----------------------------------------------------------

    @Test
    fun `a new signature is created with its formatting`() = runTest {
        val server = FakeMailServer()
        val manager = loaded(server)
        advanceUntilIdle()

        manager.onEvent(SignatureEvent.Edit(null))
        manager.onEvent(SignatureEvent.TitleChanged("Work"))
        manager.onEvent(
            SignatureEvent.BodyChanged(RichText.plain("Aisha").toggle(TextMark.Bold, 0, 5)),
        )
        manager.onEvent(SignatureEvent.Save)
        advanceUntilIdle()

        assertEquals(listOf("Work" to "<b>Aisha</b>"), server.createdSignatures)
        assertNull(manager.state.value.draft, "the editor should have closed")
    }

    @Test
    fun `a signature with no name cannot be saved`() = runTest {
        // It would be unpickable in the composer's menu.
        val server = FakeMailServer()
        val manager = loaded(server)
        advanceUntilIdle()

        manager.onEvent(SignatureEvent.Edit(null))
        manager.onEvent(SignatureEvent.BodyChanged(RichText.plain("Aisha")))

        assertFalse(manager.state.value.draft?.canSave == true)

        manager.onEvent(SignatureEvent.Save)
        advanceUntilIdle()

        assertTrue(server.createdSignatures.isEmpty())
    }

    @Test
    fun `the name is trimmed`() = runTest {
        val server = FakeMailServer()
        val manager = loaded(server)
        advanceUntilIdle()

        manager.onEvent(SignatureEvent.Edit(null))
        manager.onEvent(SignatureEvent.TitleChanged("  Work  "))
        manager.onEvent(SignatureEvent.BodyChanged(RichText.plain("A")))
        manager.onEvent(SignatureEvent.Save)
        advanceUntilIdle()

        assertEquals("Work", server.createdSignatures.single().first)
    }

    // -- editing -----------------------------------------------------------

    @Test
    fun `editing opens with the signature parsed back into a document`() = runTest {
        // Reopening must give an editable signature, not raw markup.
        val server = FakeMailServer().apply { storedSignatures = listOf(work) }
        val manager = loaded(server)
        advanceUntilIdle()

        manager.onEvent(SignatureEvent.Edit(work))

        val draft = manager.state.value.draft
        assertEquals("Work", draft?.title)
        assertEquals("Aisha", draft?.body?.text)
        assertTrue(draft?.body?.isApplied(TextMark.Bold, 0, 5) == true)
        assertFalse(draft?.isNew == true)
    }

    @Test
    fun `cancelling throws the edit away`() = runTest {
        val server = FakeMailServer().apply { storedSignatures = listOf(work) }
        val manager = loaded(server)
        advanceUntilIdle()

        manager.onEvent(SignatureEvent.Edit(work))
        manager.onEvent(SignatureEvent.TitleChanged("Renamed"))
        manager.onEvent(SignatureEvent.CancelEdit)
        advanceUntilIdle()

        assertNull(manager.state.value.draft)
        assertTrue(server.createdSignatures.isEmpty())
    }

    // -- usage -------------------------------------------------------------

    @Test
    fun `marking one for new mail unmarks the others`() = runTest {
        // The composer picks the first match, so two defaults would make the
        // winner depend on the server's ordering.
        val server = FakeMailServer().apply { storedSignatures = listOf(work, short) }
        val manager = loaded(server)
        advanceUntilIdle()

        manager.onEvent(SignatureEvent.UsageChanged(short, useForNew = true, useForReply = false))
        advanceUntilIdle()

        val forNew = manager.state.value.signatures.filter { it.useForNew }
        assertEquals(listOf("s2"), forNew.map { it.id })
        assertTrue(server.usageUpdates.any { it.first == "s1" && !it.second })
    }

    @Test
    fun `only signatures that had the flag are updated`() = runTest {
        // Sending a redundant update per signature turns one click into N
        // requests on an account with many.
        val server = FakeMailServer().apply {
            storedSignatures = listOf(work, short, EmailSignature("s3", "Third", "x"))
        }
        val manager = loaded(server)
        advanceUntilIdle()

        manager.onEvent(SignatureEvent.UsageChanged(short, useForNew = true, useForReply = false))
        advanceUntilIdle()

        assertEquals(setOf("s2", "s1"), server.usageUpdates.map { it.first }.toSet())
    }

    @Test
    fun `the flag shows immediately, before the server answers`() = runTest {
        val server = FakeMailServer().apply { storedSignatures = listOf(work, short) }
        val manager = loaded(server)
        advanceUntilIdle()

        manager.onEvent(SignatureEvent.UsageChanged(short, useForNew = true, useForReply = false))

        assertTrue(manager.state.value.signatures.first { it.id == "s2" }.useForNew)
    }

    // -- deleting ----------------------------------------------------------

    @Test
    fun `deleting asks first`() = runTest {
        val server = FakeMailServer().apply { storedSignatures = listOf(work) }
        val manager = loaded(server)
        advanceUntilIdle()

        manager.onEvent(SignatureEvent.AskDelete(work))
        advanceUntilIdle()

        assertEquals(work, manager.state.value.pendingDelete)
        assertTrue(server.deletedSignatures.isEmpty())
    }

    @Test
    fun `confirming deletes and drops it from the list`() = runTest {
        val server = FakeMailServer().apply { storedSignatures = listOf(work, short) }
        val manager = loaded(server)
        advanceUntilIdle()
        manager.onEvent(SignatureEvent.AskDelete(work))

        manager.onEvent(SignatureEvent.ConfirmDelete)
        advanceUntilIdle()

        assertEquals(listOf("s1"), server.deletedSignatures)
        assertEquals(listOf("s2"), manager.state.value.signatures.map { it.id })
    }

    @Test
    fun `cancelling deletes nothing`() = runTest {
        val server = FakeMailServer().apply { storedSignatures = listOf(work) }
        val manager = loaded(server)
        advanceUntilIdle()
        manager.onEvent(SignatureEvent.AskDelete(work))

        manager.onEvent(SignatureEvent.DismissDelete)
        advanceUntilIdle()

        assertTrue(server.deletedSignatures.isEmpty())
        assertNull(manager.state.value.pendingDelete)
        assertEquals(1, manager.state.value.signatures.size)
    }
}
