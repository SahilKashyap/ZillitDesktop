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
    val body: String = "",
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
 * Builds the draft for a reply or forward.
 *
 * One function for all three modes so they cannot drift apart. The rules are
 * conventional, and each is a thing users notice when it is wrong:
 *
 *  - **Reply** goes to the sender alone.
 *  - **Reply all** adds everyone who was on the message, minus [selfAddress] —
 *    replying to yourself is the classic mail-client embarrassment.
 *  - **Forward** addresses nobody; the user picks.
 *
 * The subject is prefixed only when it is not already, so a long thread does not
 * become `Re: Re: Re:`.
 */
fun EmailMessage.replyDraft(mode: ComposeMode, selfAddress: String = ""): OutgoingEmail {
    val sender = from.headerAddress()
    val everyone = (to + cc).map { it.headerAddress() }

    val recipients = when (mode) {
        ComposeMode.Reply -> listOf(sender)
        ComposeMode.ReplyAll -> (listOf(sender) + everyone).distinctAddresses(except = selfAddress)
        else -> emptyList()
    }

    return OutgoingEmail(
        to = recipients.filter { it.isNotBlank() },
        subject = subject.prefixedFor(mode),
        body = quotedFor(mode),
        // A forward starts a new conversation; a reply continues this one.
        references = if (mode == ComposeMode.Forward) emptyList() else listOf(id),
    )
}

private fun List<String>.distinctAddresses(except: String): List<String> =
    map { it.trim() }
        .filter { it.isNotBlank() && !it.equals(except.trim(), ignoreCase = true) }
        .distinctBy { it.lowercase() }

private fun String.prefixedFor(mode: ComposeMode): String {
    val prefix = when (mode) {
        ComposeMode.Forward -> "Fwd: "
        ComposeMode.Reply, ComposeMode.ReplyAll -> "Re: "
        ComposeMode.New -> return this
    }
    return if (startsWith(prefix, ignoreCase = true)) this else prefix + this
}

/**
 * The quoted original, below a blank line for the reply to be typed into.
 *
 * Plain text even when the original was HTML: quoting markup inside a composer
 * that cannot edit markup produces mail that renders as tag soup at the far end.
 */
private fun EmailMessage.quotedFor(mode: ComposeMode): String {
    if (mode == ComposeMode.New) return ""

    val plain = if (isHtml) htmlToPlainText(body) else body
    val header = if (mode == ComposeMode.Forward) {
        "---------- Forwarded message ----------\nFrom: $from\nSubject: $subject"
    } else {
        // No date: this module has no formatter, and "On <blank>, X wrote:" is
        // worse than the plain attribution every client falls back to.
        "$senderName wrote:"
    }

    return buildString {
        append("\n\n")
        append(header)
        append('\n')
        plain.lineSequence().forEach { line -> append("> ").append(line).append('\n') }
    }
}
