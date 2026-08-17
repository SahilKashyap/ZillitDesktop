package com.zillit.desktop.feature.email.domain

import com.zillit.desktop.core.common.ZillitResult

/**
 * A sign-off appended to outgoing mail.
 *
 * [body] is HTML, like the message body itself — signatures are where people
 * put a job title in bold and a phone number, and storing them as plain text
 * would flatten exactly the thing they are for.
 */
data class EmailSignature(
    val id: String,
    val title: String,
    val body: String,
    /** Applied automatically to a new message. At most one should be set. */
    val useForNew: Boolean = false,
    /** Applied automatically to a reply or forward. */
    val useForReply: Boolean = false,
) {
    /** Never prints the body: a signature carries a phone number and an address. */
    override fun toString(): String = "EmailSignature(id=$id, title=$title)"
}

/**
 * Which signature a composer opens with.
 *
 * The rule, in order:
 *
 *  1. the one the user marked for this kind of message
 *  2. failing that, their only signature, if they have exactly one — someone
 *     with a single signature meant it to be used
 *  3. otherwise none
 *
 * Android falls back to a hardcoded "Sent from Android" when a user has no
 * signatures at all. That is not reproduced here: appending an advertisement to
 * a production's mail is a decision for whoever owns the product, not a default
 * a desktop port should quietly introduce.
 */
fun List<EmailSignature>.defaultFor(mode: ComposeMode): EmailSignature? {
    val marked = if (mode == ComposeMode.New) {
        firstOrNull { it.useForNew }
    } else {
        firstOrNull { it.useForReply }
    }

    return marked ?: singleOrNull()
}

/**
 * The body as it goes on the wire, with the signature below it.
 *
 * ## Why the signature is not in the editor
 *
 * The obvious implementation appends the signature to the body when the
 * composer opens. Then every autosave, every switch of signature and every
 * reopened draft has to find and remove the old one — and the classic failure
 * of every mail client that has tried it is a message ending in three copies of
 * someone's phone number.
 *
 * Keeping it separate until the moment of sending makes that impossible rather
 * than merely unlikely. The cost is that the editor does not show it, which is
 * what the preview under the composer is for.
 */
fun composedBody(body: String, signature: EmailSignature?): String {
    val sign = signature?.body?.takeIf { it.isNotBlank() } ?: return body
    if (body.isBlank()) return sign

    // Two breaks, matching what every client puts between a message and its
    // sign-off. Not a `<hr>`: some clients render it edge to edge.
    return "$body<br><br>$sign"
}

/** Creating, editing and deleting sign-offs. */
interface SignatureRepository {

    suspend fun signatures(): ZillitResult<List<EmailSignature>>

    suspend fun create(title: String, body: String): ZillitResult<EmailSignature>

    suspend fun update(id: String, title: String, body: String): ZillitResult<Unit>

    /** Sets which kinds of message this signature is applied to automatically. */
    suspend fun setUsage(id: String, useForNew: Boolean, useForReply: Boolean): ZillitResult<Unit>

    suspend fun delete(id: String): ZillitResult<Unit>
}
