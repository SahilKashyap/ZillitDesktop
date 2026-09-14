package com.zillit.desktop.feature.email

import com.zillit.desktop.feature.email.domain.ComposeMode
import com.zillit.desktop.feature.email.domain.EmailMessage
import com.zillit.desktop.feature.email.domain.OutgoingEmail
import com.zillit.desktop.feature.email.domain.replyDraft
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
    fun `reply all answers the sender and copies everyone else but me`() {
        // The web's split (`ComposeModal.jsx`): the reply-to address in To,
        // the rest of the recipients in Cc. Replying to yourself is the
        // classic mail-client embarrassment, so I am left out.
        val draft = message.replyDraft(ComposeMode.ReplyAll, selfAddress = "me@prod.com")

        assertEquals(listOf("aisha@prod.com"), draft.to)
        assertEquals(listOf("crew@prod.com", "ad@prod.com"), draft.cc)
        assertFalse((draft.to + draft.cc).contains("me@prod.com"))
    }

    @Test
    fun `my own address is matched regardless of case`() {
        val draft = message.replyDraft(ComposeMode.ReplyAll, selfAddress = "ME@PROD.COM")

        assertFalse((draft.to + draft.cc).any { it.equals("me@prod.com", ignoreCase = true) })
    }

    @Test
    fun `reply all does not address anyone twice`() {
        val duplicated = message.copy(to = listOf("aisha@prod.com", "crew@prod.com", "crew@prod.com"))

        val draft = duplicated.replyDraft(ComposeMode.ReplyAll)

        assertEquals(listOf("aisha@prod.com"), draft.to)
        assertEquals(listOf("crew@prod.com", "ad@prod.com"), draft.cc, "the sender is not copied to themselves")
    }

    @Test
    fun `a reply to my own sent mail goes back to whoever I sent it to`() {
        // From Sent — or a message whose reply-to is me — the web addresses
        // the original recipients, minus me, rather than replying to myself.
        val mine = message.copy(from = "Me <me@prod.com>", replyTo = "me@prod.com", folderName = "Sent")

        val draft = mine.replyDraft(ComposeMode.Reply, selfAddress = "me@prod.com")

        assertEquals(listOf("crew@prod.com"), draft.to)
    }

    @Test
    fun `reply-to wins over the sender`() {
        val routed = message.copy(replyTo = "Production Office <office@prod.com>")

        assertEquals(listOf("office@prod.com"), routed.replyDraft(ComposeMode.Reply).to)
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
        val shouted = message.copy(subject = "RE: FW: Call sheet")

        assertEquals("Re: Call sheet", shouted.replyDraft(ComposeMode.Reply).subject)
        assertEquals("Fwd: Call sheet", shouted.replyDraft(ComposeMode.Forward).subject)
    }

    @Test
    fun `a reply and a forward both thread onto the conversation`() {
        // Without the reference the reply arrives as a new conversation in the
        // recipient's client — invisible to us, obvious to them. The web sends
        // the chain on a forward too, so the copy files beside the original.
        assertEquals(listOf("m1"), message.replyDraft(ComposeMode.Reply).references)
        assertEquals(listOf("m1"), message.replyDraft(ComposeMode.Forward).references)
        assertTrue(message.replyDraft(ComposeMode.New).references.isEmpty())
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
    fun `the original is quoted as html below the editor, the way the phones quote it`() {
        val html = message.copy(body = "<p>Call is <b>6am</b>.</p>", isHtml = true)

        val draft = html.replyDraft(ComposeMode.Reply, quotedDate = "Nov 14, 2023 at 10:13 PM")

        assertEquals("", draft.body, "the editor starts empty; the quote is its own block")
        assertTrue(draft.quotedHtml.contains("<blockquote"), "a reply quotes in a blockquote")
        assertTrue(draft.quotedHtml.contains("On Nov 14, 2023 at 10:13 PM, aisha@prod.com wrote:"))
        assertTrue(draft.quotedHtml.contains("<b>6am</b>"), "the original's markup is kept")
    }

    @Test
    fun `a plain-text original is escaped and its line breaks kept`() {
        val plain = message.copy(body = "Line one\nCall is <6am>", isHtml = false)

        val quoted = plain.replyDraft(ComposeMode.Reply).quotedHtml

        assertTrue(quoted.contains("Line one<br/>Call is &lt;6am&gt;"))
    }

    @Test
    fun `a forward carries the header block and the original's files`() {
        val withFiles = message.copy(
            attachments = listOf(
                com.zillit.desktop.feature.email.domain.EmailAttachment(id = "a1", fileName = "sheet.pdf"),
                com.zillit.desktop.feature.email.domain.EmailAttachment(
                    id = "a2",
                    fileName = "logo.png",
                    contentId = "logo",
                    contentDisposition = "inline",
                ),
            ),
            body = "<p>See attached <img src=\"cid:logo\"></p>",
            isHtml = true,
        )

        val draft = withFiles.replyDraft(ComposeMode.Forward)

        assertTrue(draft.quotedHtml.contains("Forwarded message"))
        assertTrue(draft.quotedHtml.contains("Subject: Call sheet for Tuesday"))
        assertEquals(listOf("a1"), draft.forwarded.map { it.id }, "inline pictures ride the body, not the list")
    }

    @Test
    fun `a new message starts empty`() {
        val draft = message.replyDraft(ComposeMode.New)

        assertTrue(draft.to.isEmpty())
        assertEquals("Call sheet for Tuesday", draft.subject, "unprefixed")
        assertEquals("", draft.body)
        assertEquals("", draft.quotedHtml)
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
}
