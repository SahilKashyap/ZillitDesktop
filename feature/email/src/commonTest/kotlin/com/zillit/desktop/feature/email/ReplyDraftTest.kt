package com.zillit.desktop.feature.email

import com.zillit.desktop.feature.email.domain.ComposeMode
import com.zillit.desktop.feature.email.domain.EmailMessage
import com.zillit.desktop.feature.email.domain.OutgoingEmail
import com.zillit.desktop.feature.email.domain.replyDraft
import com.zillit.desktop.feature.email.ui.toAddresses
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Who a reply is addressed to.
 *
 * Every rule here is one a user notices immediately when it is wrong — mail
 * sent to the wrong people cannot be taken back.
 */
class ReplyDraftTest {

    private val message = EmailMessage(
        id = "m1",
        threadId = "t1",
        subject = "Call sheet for Tuesday",
        from = "Aisha Khan <aisha@prod.com>",
        to = listOf("crew@prod.com", "me@prod.com"),
        cc = listOf("ad@prod.com"),
        body = "Call is 6am.",
        receivedAtMillis = 1_700_000_000_000,
    )

    @Test
    fun `reply goes to the sender alone`() {
        val draft = message.replyDraft(ComposeMode.Reply, selfAddress = "me@prod.com")

        assertEquals(listOf("aisha@prod.com"), draft.to)
    }

    @Test
    fun `reply all includes everyone but me`() {
        // Replying to yourself is the classic mail-client embarrassment.
        val draft = message.replyDraft(ComposeMode.ReplyAll, selfAddress = "me@prod.com")

        assertEquals(listOf("aisha@prod.com", "crew@prod.com", "ad@prod.com"), draft.to)
        assertFalse(draft.to.contains("me@prod.com"))
    }

    @Test
    fun `my own address is matched regardless of case`() {
        val draft = message.replyDraft(ComposeMode.ReplyAll, selfAddress = "ME@PROD.COM")

        assertFalse(draft.to.any { it.equals("me@prod.com", ignoreCase = true) })
    }

    @Test
    fun `reply all does not address anyone twice`() {
        val duplicated = message.copy(to = listOf("aisha@prod.com", "crew@prod.com"))

        val draft = duplicated.replyDraft(ComposeMode.ReplyAll)

        assertEquals(draft.to.distinct(), draft.to)
    }

    @Test
    fun `forward addresses nobody`() {
        val draft = message.replyDraft(ComposeMode.Forward)

        assertTrue(draft.to.isEmpty(), "the user picks who a forward goes to")
        assertFalse(draft.canSend, "and it cannot be sent until they do")
    }

    @Test
    fun `subjects are prefixed once, not once per reply`() {
        val first = message.replyDraft(ComposeMode.Reply)
        val second = message.copy(subject = first.subject).replyDraft(ComposeMode.Reply)

        assertEquals("Re: Call sheet for Tuesday", first.subject)
        assertEquals("Re: Call sheet for Tuesday", second.subject, "no Re: Re:")
    }

    @Test
    fun `an existing prefix is recognised whatever its case`() {
        val shouted = message.copy(subject = "RE: Call sheet")

        assertEquals("RE: Call sheet", shouted.replyDraft(ComposeMode.Reply).subject)
    }

    @Test
    fun `a reply threads onto the conversation and a forward starts a new one`() {
        // Without the reference the reply arrives as a new conversation in the
        // recipient's client — invisible to us, obvious to them.
        assertEquals(listOf("m1"), message.replyDraft(ComposeMode.Reply).references)
        assertTrue(message.replyDraft(ComposeMode.Forward).references.isEmpty())
    }

    @Test
    fun `a reply carries the whole chain, not just the message it answers`() {
        // The bug this pins: replying to the third message in a thread sent
        // only that message's id, so the recipient — who threads on the FIRST
        // reference — filed the reply as a new conversation. Both phones and
        // the web send the parent's chain with the parent appended.
        val third = message.copy(id = "m3", references = listOf("m1", "m2"))

        assertEquals(
            listOf("m1", "m2", "m3"),
            third.replyDraft(ComposeMode.Reply).references,
        )
        // The root stays first: that is what every client threads on.
        assertEquals("m1", third.replyDraft(ComposeMode.ReplyAll).references.first())
    }

    @Test
    fun `a chain that already names this message does not name it twice`() {
        // Some servers include the message's own id in its references. Sending
        // it twice is malformed, and a duplicate root confuses threading.
        val odd = message.copy(id = "m3", references = listOf("m1", "m3"))

        assertEquals(listOf("m1", "m3"), odd.replyDraft(ComposeMode.Reply).references)
    }

    @Test
    fun `the original is quoted, and html is flattened first`() {
        val html = message.copy(body = "<p>Call is <b>6am</b>.</p>", isHtml = true)

        val body = html.replyDraft(ComposeMode.Reply).body

        assertTrue(body.contains("> "), "the original should be quoted")
        assertFalse(body.contains("<p>"), "quoting markup into a plain-text composer is tag soup")
        assertTrue(body.contains("6am"))
    }

    @Test
    fun `a new message starts empty`() {
        val draft = message.replyDraft(ComposeMode.New)

        assertTrue(draft.to.isEmpty())
        assertEquals("Call sheet for Tuesday", draft.subject, "unprefixed")
        assertEquals("", draft.body)
    }

    @Test
    fun `sending needs a plausible recipient`() {
        assertFalse(OutgoingEmail(to = listOf("not-an-address")).canSend)
        assertFalse(OutgoingEmail(to = listOf("a@b")).canSend, "no dot after the @")
        assertFalse(OutgoingEmail(to = emptyList(), subject = "hi").canSend)
        assertTrue(OutgoingEmail(to = listOf("a@b.com")).canSend)
    }

    @Test
    fun `an empty subject and body are still sendable`() {
        // People send both. Refusing would be the client inventing a rule the
        // server does not have.
        assertTrue(OutgoingEmail(to = listOf("a@b.com"), subject = "", body = "").canSend)
    }

    @Test
    fun `recipient fields split on commas and semicolons, not whitespace`() {
        assertEquals(listOf("a@b.com", "c@d.com"), "a@b.com, c@d.com".toAddresses())
        assertEquals(listOf("a@b.com", "c@d.com"), "a@b.com; c@d.com".toAddresses())
        // A pasted `Aisha Khan <a@b.com>` must not shatter on the space —
        // it is ONE recipient, reduced to its bare address.
        assertEquals(listOf("a@b.com"), "Aisha Khan <a@b.com>".toAddresses())
    }

    @Test
    fun `a recipient picked from suggestions is a valid, bare address`() {
        // The picker inserts the friendly form and a trailing separator; the
        // Send button read this as invalid because of the spaces, so picking
        // a contact the recommended way left Send disabled.
        val typed = "Vivek Mishra <vivek@zillit.com>, "
        assertEquals(listOf("vivek@zillit.com"), typed.toAddresses())
        assertTrue(OutgoingEmail(to = typed.toAddresses()).canSend)
    }

    @Test
    fun `undecorated and half-decorated entries pass through untouched`() {
        assertEquals(listOf("a@b.com"), "a@b.com".toAddresses())
        // A dangling bracket is not a decoration; keep it visible so the
        // validity check can reject it rather than silently repairing it.
        assertEquals(listOf("broken <a@b.com"), "broken <a@b.com".toAddresses())
    }

    @Test
    fun `blank recipient input yields nothing rather than one empty address`() {
        assertTrue("  ,  ; ".toAddresses().isEmpty())
    }
}
