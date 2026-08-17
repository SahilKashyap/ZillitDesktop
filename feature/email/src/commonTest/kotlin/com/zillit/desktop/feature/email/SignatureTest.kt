package com.zillit.desktop.feature.email

import com.zillit.desktop.feature.email.data.readSignature
import com.zillit.desktop.feature.email.domain.ComposeMode
import com.zillit.desktop.feature.email.domain.EmailMessage
import com.zillit.desktop.feature.email.domain.EmailSignature
import com.zillit.desktop.feature.email.domain.RichText
import com.zillit.desktop.feature.email.domain.composedBody
import com.zillit.desktop.feature.email.domain.defaultFor
import com.zillit.desktop.feature.email.ui.ComposeEvent
import com.zillit.desktop.feature.email.ui.ComposeViewModel
import com.zillit.desktop.feature.email.ui.Composing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
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
 * Sign-offs on outgoing mail.
 *
 * The failure this design exists to prevent is a message ending in three copies
 * of someone's phone number — which is what happens when the signature lives in
 * the editor and every autosave has to find and remove the old one.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SignatureTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private val work = EmailSignature("s1", "Work", "<b>Aisha</b><br>Camera", useForNew = true)
    private val short = EmailSignature("s2", "Short", "Aisha", useForReply = true)

    private fun composer(server: FakeMailServer, mode: ComposeMode = ComposeMode.New) =
        ComposeViewModel(Composing(server, server, server, server), mode)

    // -- choosing ----------------------------------------------------------

    @Test
    fun `a new message uses the one marked for new mail`() {
        assertEquals(work, listOf(work, short).defaultFor(ComposeMode.New))
    }

    @Test
    fun `a reply uses the one marked for replies`() {
        assertEquals(short, listOf(work, short).defaultFor(ComposeMode.Reply))
        assertEquals(short, listOf(work, short).defaultFor(ComposeMode.Forward))
    }

    @Test
    fun `a single signature is used even when nothing is marked`() {
        // Someone with exactly one meant it to be used.
        val only = EmailSignature("s1", "Only", "Aisha")

        assertEquals(only, listOf(only).defaultFor(ComposeMode.New))
    }

    @Test
    fun `several unmarked signatures pick none`() {
        // Guessing between them would put the wrong sign-off on someone's mail.
        val a = EmailSignature("a", "A", "x")
        val b = EmailSignature("b", "B", "y")

        assertNull(listOf(a, b).defaultFor(ComposeMode.New))
    }

    @Test
    fun `no signatures means no signature`() {
        // Android falls back to a hardcoded "Sent from Android". Appending an
        // advertisement to a production's mail is not a default a port should
        // quietly introduce.
        assertNull(emptyList<EmailSignature>().defaultFor(ComposeMode.New))
    }

    // -- appending ---------------------------------------------------------

    @Test
    fun `the signature goes below the body`() {
        assertEquals("Hello<br><br>Aisha", composedBody("Hello", short))
    }

    @Test
    fun `no signature leaves the body alone`() {
        assertEquals("Hello", composedBody("Hello", null))
        assertEquals("Hello", composedBody("Hello", short.copy(body = "")))
    }

    @Test
    fun `an empty body is just the signature, with no leading blank lines`() {
        assertEquals("Aisha", composedBody("", short))
    }

    // -- through the composer ----------------------------------------------

    @Test
    fun `the chosen signature reaches the wire`() = runTest {
        val server = FakeMailServer().apply { storedSignatures = listOf(work) }
        val composer = composer(server)
        advanceUntilIdle()

        composer.onEvent(ComposeEvent.ToChanged("crew@prod.com"))
        composer.onEvent(ComposeEvent.BodyChanged(RichText.plain("Call is 6am")))
        composer.onEvent(ComposeEvent.Send)
        advanceUntilIdle()

        assertEquals("Call is 6am<br><br><b>Aisha</b><br>Camera", server.sent?.body)
    }

    @Test
    fun `editing never duplicates the signature`() = runTest {
        // The whole point of keeping it out of the editor.
        val server = FakeMailServer().apply { storedSignatures = listOf(work) }
        val composer = composer(server)
        advanceUntilIdle()

        composer.onEvent(ComposeEvent.ToChanged("crew@prod.com"))
        listOf("One", "One two", "One two three").forEach {
            composer.onEvent(ComposeEvent.BodyChanged(RichText.plain(it)))
        }
        composer.onEvent(ComposeEvent.Send)
        advanceUntilIdle()

        val body = server.sent?.body.orEmpty()
        assertEquals(1, Regex("Camera").findAll(body).count(), body)
        assertFalse(composer.state.value.body.text.contains("Aisha"), "it leaked into the editor")
    }

    @Test
    fun `switching signature replaces rather than appends`() = runTest {
        val server = FakeMailServer().apply { storedSignatures = listOf(work, short) }
        val composer = composer(server)
        advanceUntilIdle()

        composer.onEvent(ComposeEvent.ToChanged("crew@prod.com"))
        composer.onEvent(ComposeEvent.BodyChanged(RichText.plain("Hi")))
        composer.onEvent(ComposeEvent.SignatureChosen(short))
        composer.onEvent(ComposeEvent.Send)
        advanceUntilIdle()

        assertEquals("Hi<br><br>Aisha", server.sent?.body)
    }

    @Test
    fun `choosing none sends none`() = runTest {
        val server = FakeMailServer().apply { storedSignatures = listOf(work) }
        val composer = composer(server)
        advanceUntilIdle()

        composer.onEvent(ComposeEvent.ToChanged("crew@prod.com"))
        composer.onEvent(ComposeEvent.BodyChanged(RichText.plain("Hi")))
        composer.onEvent(ComposeEvent.SignatureChosen(null))
        composer.onEvent(ComposeEvent.Send)
        advanceUntilIdle()

        assertEquals("Hi", server.sent?.body)
    }

    @Test
    fun `a reply opens with the reply signature`() = runTest {
        val server = FakeMailServer().apply { storedSignatures = listOf(work, short) }
        val original = EmailMessage("m1", "t1", "Subject", "a@b.com")
        val composer = ComposeViewModel(
            Composing(server, server, server, server),
            ComposeMode.Reply,
            original,
        )
        advanceUntilIdle()

        assertEquals(short, composer.state.value.signature)
    }

    @Test
    fun `a signature that fails to load does not stop the composer`() = runTest {
        // Refusing to open because a sign-off list would not load is worse than
        // opening without one.
        val server = FakeMailServer()
        val composer = composer(server)
        advanceUntilIdle()

        composer.onEvent(ComposeEvent.ToChanged("crew@prod.com"))

        assertNull(composer.state.value.error)
        assertTrue(composer.state.value.canSend)
    }

    // -- reading -----------------------------------------------------------

    @Test
    fun `a signature reads with its usage flags`() {
        val row = readSignature(
            Json.parseToJsonElement(
                """{"_id":"s1","signature_title":"Work","signature_body":"<b>A</b>",
                   "use_for_new_email":true,"use_for_reply_and_forward":false}""",
            ),
        )!!

        assertEquals("Work", row.title)
        assertTrue(row.useForNew)
        assertFalse(row.useForReply)
    }

    @Test
    fun `a deleted signature is a tombstone, not a signature`() {
        // The server keeps them so other devices drop them too; showing one
        // resurrects something deleted elsewhere.
        assertNull(readSignature(Json.parseToJsonElement("""{"_id":"s1","deleted":1717484322120}""")))
    }

    @Test
    fun `a signature never prints its body`() {
        // It carries a phone number and an address, and these reach log files.
        val printed = work.toString()

        assertTrue(printed.contains("Work"))
        assertFalse(printed.contains("Camera"))
    }
}
