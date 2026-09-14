package com.zillit.desktop.feature.email.domain

/**
 * A message being written.
 *
 * Recipients are held as plain addresses; the wire format wraps each in
 * `{"email_address": …}`, which is the data layer's problem, not the composer's.
 */
data class OutgoingEmail(
    val to: List<String> = emptyList(),
    val cc: List<String> = emptyList(),
    val bcc: List<String> = emptyList(),
    val subject: String = "",
    /** What was typed: the editor's HTML, without the sign-off or the quote. */
    val body: String = "",
    /**
     * The message being answered or forwarded, already formatted the way the
     * web and Android quote it — an attribution line and a `<blockquote>`, or
     * the forwarded-message header. Kept out of [body] because the editor
     * cannot hold the original's markup without flattening it; it is joined
     * back on at the moment the message is serialised (see [composedBody]).
     */
    val quotedHtml: String = "",
    /**
     * The `References` chain, threading this reply onto its conversation.
     *
     * Sent back verbatim and space-joined, per RFC 5322. Dropping it makes the
     * reply arrive as a new conversation in the recipient's client — the bug is
     * invisible to us and obvious to them.
     */
    val references: List<String> = emptyList(),
    /**
     * The draft this was written as, if any.
     *
     * Sent as `email_draft_id` so the server deletes the draft once the mail
     * goes. Omitting it is the classic bug where sending leaves a copy behind
     * in Drafts forever.
     */
    val draftId: String? = null,
    /**
     * Files already in object storage.
     *
     * Descriptors, not bytes: the server fetches from the bucket, which is why
     * a message cannot be sent until every upload has finished.
     */
    val attachments: List<StoredFile> = emptyList(),
    /**
     * The original's files, carried along on a reply or forward.
     *
     * Both other clients re-attach everything that is not an inline picture
     * (web `setIngrainedAttachments(nonEmbeddedAttachments)`, Android
     * `ingrained = attachments.filter { !isInline }`) and send them as
     * `ingrained_attachment` — server-side copies the mail service already
     * holds, so nothing is re-uploaded. Removable one by one in the composer.
     */
    val forwarded: List<EmailAttachment> = emptyList(),
) {
    /**
     * Whether this is worth sending.
     *
     * A recipient is the only hard requirement. Empty subjects and empty bodies
     * are both things people legitimately send, and refusing them would be the
     * client inventing a rule the server does not have.
     */
    val canSend: Boolean get() = to.any { it.isValidEmail() }

    /** Never prints the body. */
    override fun toString(): String =
        "OutgoingEmail(to=${to.size}, cc=${cc.size}, bcc=${bcc.size}, chars=${body.length})"
}

/**
 * Deliberately permissive.
 *
 * The client's job is to catch the typo that would bounce, not to adjudicate
 * RFC 5322 — which permits quoted strings, comments and addresses no regex
 * should try to describe. The server is the authority.
 */
internal fun String.isValidEmail(): Boolean {
    val at = trim().indexOf('@')
    val dot = trim().lastIndexOf('.')
    return at > 0 && dot > at + 1 && dot < trim().length - 1 && !trim().contains(' ')
}

/** How the composer was opened, which decides who it is addressed to. */
enum class ComposeMode { New, Reply, ReplyAll, Forward }

/**
 * Builds the draft for a reply or forward — the web's `ComposeModal` rules,
 * one function for all three modes so they cannot drift apart:
 *
 *  - **Reply** goes to the message's reply-to address. A message *I* sent
 *    (from Sent, or whose reply-to is my own address) goes back to whoever I
 *    sent it to, minus me.
 *  - **Reply all** puts the reply-to address in To and everyone else who was
 *    on the message in Cc, minus [selfAddress] — replying to yourself is the
 *    classic mail-client embarrassment. A message I sent keeps its To and Cc.
 *  - **Forward** addresses nobody; the user picks.
 *
 * The subject is prefixed only when it is not already, so a long thread does
 * not become `Re: Re: Re:`. The original goes into [OutgoingEmail.quotedHtml]
 * in the shape both other clients send, and its files ride along as
 * [OutgoingEmail.forwarded].
 */
fun EmailMessage.replyDraft(
    mode: ComposeMode,
    selfAddress: String = "",
    /** The reading pane's timestamp for the attribution line; blank leaves the date out. */
    quotedDate: String = mailFullTimeLabel(receivedAtMillis),
): OutgoingEmail {
    val me = selfAddress.trim()
    val replyAddress = replyAddress
    val sentByMe = folderName.equals(EmailFolder.SENT, ignoreCase = true) ||
        (me.isNotBlank() && replyAddress.equals(me, ignoreCase = true))
    val toAddresses = to.map { it.headerAddress() }
    val ccAddresses = cc.map { it.headerAddress() }

    val recipients: Pair<List<String>, List<String>> = when (mode) {
        ComposeMode.Reply ->
            if (sentByMe) toAddresses.distinctAddresses(except = me) to emptyList()
            else listOf(replyAddress) to emptyList()
        ComposeMode.ReplyAll ->
            if (sentByMe) {
                toAddresses.distinctAddresses(except = "") to ccAddresses.distinctAddresses(except = "")
            } else {
                listOf(replyAddress) to (toAddresses + ccAddresses).distinctAddresses(except = me)
                    .filterNot { it.equals(replyAddress, ignoreCase = true) }
            }
        else -> emptyList<String>() to emptyList()
    }

    return OutgoingEmail(
        to = recipients.first.filter { it.isNotBlank() },
        cc = recipients.second,
        subject = subject.prefixedFor(mode),
        quotedHtml = quotedFor(mode, quotedDate),
        // Continuing the conversation means the WHOLE chain, not just this
        // message: sending only this id threaded a reply to the first message
        // and lost every later one into a new conversation (QA: "replying
        // generates a new mail"). Both phones and the web send the parent's
        // chain — for a forward as well, so the forwarded copy still files
        // beside the original in a threaded client.
        references = if (mode == ComposeMode.New) emptyList() else replyReferences,
        forwarded = if (mode == ComposeMode.New) emptyList() else listedAttachments,
    )
}

private fun List<String>.distinctAddresses(except: String): List<String> =
    map { it.trim() }
        .filter { it.isNotBlank() && !(except.isNotBlank() && it.equals(except.trim(), ignoreCase = true)) }
        .distinctBy { it.lowercase() }

private fun String.prefixedFor(mode: ComposeMode): String {
    val bare = trim().replace(PREFIX_PATTERN, "").trim()
    return when (mode) {
        ComposeMode.Forward -> "Fwd: $bare"
        ComposeMode.Reply, ComposeMode.ReplyAll -> "Re: $bare"
        ComposeMode.New -> this
    }
}

/** A leading `Re:`/`Fwd:`/`FW:`, in any case, possibly repeated. */
private val PREFIX_PATTERN = Regex("^(\\s*(re|fwd|fw)\\s*:\\s*)+", RegexOption.IGNORE_CASE)

/**
 * The quoted original, as HTML, in the shape the web (`formatEmailBody`,
 * the forward block in `ComposeModal.jsx`) and Android
 * (`buildReplyQuotedHtml`, `buildForwardQuotedHtml`) both send — so a reply
 * from here reads exactly like one from a phone at the far end.
 *
 * Plain-text originals get their line breaks made explicit first; the web's
 * `getEmailBody` does the same (`text.replace(/\n/g, '<br/>')`).
 */
private fun EmailMessage.quotedFor(mode: ComposeMode, date: String): String {
    if (mode == ComposeMode.New) return ""
    val original = if (isHtml) body else body.escapeHtml().replace("\n", "<br/>")

    return if (mode == ComposeMode.Forward) {
        buildString {
            append("<br/><br/><div class=\"zl-quoted-trail\">")
            append("-------Forwarded message-------<br/>")
            append("From: ").append(replyAddress.escapeHtml()).append("<br/>")
            if (date.isNotBlank()) append("Date: ").append(date.escapeHtml()).append("<br/>")
            append("Subject: ").append(subject.escapeHtml()).append("<br/>")
            append("To: ").append(to.joinToString(", ").escapeHtml())
            if (cc.isNotEmpty()) append("<br/>Cc: ").append(cc.joinToString(", ").escapeHtml())
            append("<br/><br/>")
            append(original)
            append("</div>")
        }
    } else {
        val on = if (date.isNotBlank()) "On $date, " else ""
        "<br><div style=\"color: #666666;\">${on.escapeHtml()}${replyAddress.escapeHtml()} wrote:</div>" +
            "<blockquote style=\"margin: 0 0 0 0.8em; border-left: 2px solid #ccc; padding-left: 1em; " +
            "color: #666666;\">$original</blockquote>"
    }
}

/** The five characters that would otherwise be read as markup. */
internal fun String.escapeHtml(): String = buildString(length) {
    for (char in this@escapeHtml) {
        when (char) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\'' -> append("&#39;")
            else -> append(char)
        }
    }
}
