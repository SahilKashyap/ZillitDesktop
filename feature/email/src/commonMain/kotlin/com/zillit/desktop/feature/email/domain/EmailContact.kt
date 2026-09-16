package com.zillit.desktop.feature.email.domain

/** Where a suggestion came from, which decides how it is labelled and ranked. */
enum class ContactSource {
    /** Saved in the user's own address book. */
    Saved,

    /** Crew on this production. */
    ProjectUser,

    /**
     * A distribution group: one address that fans out to its members
     * (`imap-email-group`), offered by name like a person (web
     * `ComposeModal.jsx` `emailGroupsList`).
     */
    Group,
}

/**
 * Someone a message can be addressed to.
 *
 * [name] may be blank — a saved contact with no name, or a crew member who
 * asked to keep theirs private. The address is the only part that is required,
 * because it is the only part that is needed to send.
 */
data class EmailContact(
    val address: String,
    val name: String = "",
    val source: ContactSource = ContactSource.Saved,
    /** Department or company, shown as the second line to tell two Amits apart. */
    val subtitle: String = "",
    /** The crew member's id, for their picture; blank for a saved address. */
    val userId: String = "",
) {
    /** What the composer inserts: `Name <addr>`, or the bare address. */
    val asRecipient: String get() = if (name.isBlank()) address else "$name <$address>"

    val label: String get() = name.ifBlank { address }

    /** Never prints the address — a contact list is personal data. */
    override fun toString(): String = "EmailContact(source=$source, named=${name.isNotBlank()})"
}

/**
 * Suggestions for what is being typed.
 *
 * ## Ranking, and why it is not just "contains"
 *
 * Someone typing three characters wants the person whose name *starts* that
 * way, not the twelve people whose company name happens to contain it. So
 * prefix matches come first, and within those, saved contacts outrank crew —
 * an address you chose to save is a stronger signal than one that came with the
 * production.
 *
 * Matching is on the name, on each word of it (so "kha" finds "Aisha Khan")
 * and on the address.
 */
fun List<EmailContact>.suggestionsFor(
    query: String,
    /** Already on the message; offering them again is noise. */
    exclude: Collection<String> = emptyList(),
    limit: Int = SUGGESTION_LIMIT,
): List<EmailContact> {
    val term = query.trim().lowercase()
    if (term.isEmpty()) return emptyList()

    val taken = exclude.mapTo(mutableSetOf()) { it.headerAddress().lowercase() }

    return asSequence()
        .filterNot { it.address.lowercase() in taken }
        .mapNotNull { contact -> contact.rank(term)?.let { rank -> contact to rank } }
        // Stable within a rank: the source order is the server's, and a list
        // that reshuffles between keystrokes is unusable at speed.
        .sortedBy { (_, rank) -> rank }
        .map { (contact, _) -> contact }
        // One entry per address: crew who are also saved contacts appear once,
        // keeping whichever ranked higher.
        .distinctBy { it.address.lowercase() }
        .take(limit)
        .toList()
}

/** Lower is better. Null means no match. */
private fun EmailContact.rank(term: String): Int? {
    val name = name.lowercase()
    val address = address.lowercase()
    val sourceBias = if (source == ContactSource.Saved) 0 else 1

    return when {
        name.startsWith(term) -> NAME_PREFIX + sourceBias
        address.startsWith(term) -> ADDRESS_PREFIX + sourceBias
        name.split(' ').any { it.startsWith(term) } -> WORD_PREFIX + sourceBias
        name.contains(term) || address.contains(term) -> CONTAINS + sourceBias
        subtitle.lowercase().contains(term) -> SUBTITLE + sourceBias
        else -> null
    }
}

/**
 * The part of a recipient field currently being typed.
 *
 * The field holds a comma-separated list, so a suggestion has to replace only
 * the last entry — matching against the whole string would find nothing the
 * moment a second recipient is added.
 */
fun String.currentRecipientToken(): String = substringAfterLast(',').substringAfterLast(';').trim()

/**
 * Replaces the entry being typed with a chosen contact.
 *
 * Leaves a trailing `", "` so the next name can be typed straight away — the
 * thing that makes addressing four people feel quick rather than fiddly.
 */
fun String.completeRecipient(contact: EmailContact): String {
    val separator = maxOf(lastIndexOf(','), lastIndexOf(';'))
    val head = if (separator < 0) "" else substring(0, separator + 1) + " "
    return head + contact.asRecipient + ", "
}

private const val SUGGESTION_LIMIT = 8

// Two apart, so the source bias can never promote a weaker match over a
// stronger one — only break ties within the same kind of match.
private const val NAME_PREFIX = 0
private const val ADDRESS_PREFIX = 2
private const val WORD_PREFIX = 4
private const val CONTAINS = 6
private const val SUBTITLE = 8
