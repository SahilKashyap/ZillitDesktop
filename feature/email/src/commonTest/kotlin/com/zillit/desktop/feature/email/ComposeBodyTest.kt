package com.zillit.desktop.feature.email

import com.zillit.desktop.feature.email.domain.ComposeMode
import com.zillit.desktop.feature.email.domain.EmailDraft
import com.zillit.desktop.feature.email.domain.EmailMessage
import com.zillit.desktop.feature.email.domain.RichText
import com.zillit.desktop.feature.email.domain.TextMark
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
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What actually goes on the wire as the body.
 *
 * `imap-send` takes HTML. The composer therefore has to serialise — and the
 * plain-text body it sent before this existed lost every line break at the far
 * end, because HTML collapses whitespace.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ComposeBodyTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun composer(
        server: FakeMailServer,
        replyTo: EmailMessage? = null,
        editing: EmailDraft? = null,
    ) = ComposeViewModel(Composing(server, server, server, server), ComposeMode.New, replyTo, editing)

    private fun ComposeViewModel.write(body: RichText) {
        onEvent(ComposeEvent.ToChanged("crew@prod.com"))
        onEvent(ComposeEvent.BodyChanged(body))
    }

    @Test
    fun `line breaks survive the send`() = runTest {
        // The bug: a plain body posted as HTML arrives as one paragraph.
        val server = FakeMailServer()
        val composer = composer(server)

        composer.write(RichText.plain("Call is 6am.\nBring the revised sides."))
        composer.onEvent(ComposeEvent.Send)
        advanceUntilIdle()

        assertEquals("Call is 6am.<br>Bring the revised sides.", server.sent?.body)
    }

    @Test
    fun `formatting reaches the wire as tags`() = runTest {
        val server = FakeMailServer()
        val composer = composer(server)

        composer.write(RichText.plain("Call sheet").toggle(TextMark.Bold, 5, 10))
        composer.onEvent(ComposeEvent.Send)
        advanceUntilIdle()

        assertEquals("Call <b>sheet</b>", server.sent?.body)
    }

    @Test
    fun `a body with nothing special in it is sent unchanged`() = runTest {
        // Serialising must not mangle ordinary text into escaped soup.
        val server = FakeMailServer()
        val composer = composer(server)

        composer.write(RichText.plain("Just a note"))
        composer.onEvent(ComposeEvent.Send)
        advanceUntilIdle()

        assertEquals("Just a note", server.sent?.body)
    }

    @Test
    fun `a subject with markup characters is not turned into html`() = runTest {
        // Only the body is HTML. Escaping the subject would show the user
        // `&amp;` in their own sent mail.
        val server = FakeMailServer()
        val composer = composer(server)

        composer.write(RichText.plain("x"))
        composer.onEvent(ComposeEvent.SubjectChanged("Props & costume <urgent>"))
        composer.onEvent(ComposeEvent.Send)
        advanceUntilIdle()

        assertEquals("Props & costume <urgent>", server.sent?.subject)
    }

    @Test
    fun `a quoted reply keeps its line breaks`() = runTest {
        val server = FakeMailServer()
        val original = EmailMessage(
            id = "m1",
            threadId = "t1",
            subject = "Call sheet",
            from = "Aisha <a@prod.com>",
            body = "Line one\nLine two",
        )
        val composer = ComposeViewModel(Composing(server, server, server, server), ComposeMode.Reply, original)

        composer.onEvent(ComposeEvent.ToChanged("a@prod.com"))
        composer.onEvent(ComposeEvent.Send)
        advanceUntilIdle()

        val body = server.sent?.body.orEmpty()
        assertTrue(body.contains("<br>"), "the quoted original collapsed into one line")
        assertTrue(body.contains("&gt; Line one"), body)
    }

    @Test
    fun `a saved draft round-trips its formatting`() = runTest {
        // Reopening must give back an editable document, not raw markup.
        val server = FakeMailServer()
        val composer = composer(server)
        composer.write(RichText.plain("Call sheet").toggle(TextMark.Bold, 5, 10))
        composer.onEvent(ComposeEvent.Closing)
        advanceUntilIdle()

        val saved = server.savedDrafts.single()
        val reopened = composer(server, editing = EmailDraft(id = "d1", body = saved.body))

        assertEquals("Call sheet", reopened.state.value.body.text)
        assertTrue(reopened.state.value.body.isApplied(TextMark.Bold, 5, 10))
    }

    @Test
    fun `an empty body sends empty, not an empty tag`() = runTest {
        val server = FakeMailServer()
        val composer = composer(server)

        composer.onEvent(ComposeEvent.ToChanged("crew@prod.com"))
        composer.onEvent(ComposeEvent.Send)
        advanceUntilIdle()

        assertEquals("", server.sent?.body)
    }
}
