package com.zillit.desktop.feature.documentdistribution.domain

/**
 * A member of the production, as the host hands them over: already
 * translated, not yet filtered. The module decides who may be offered
 * documents — see [sendableCrew].
 */
data class DocDistCrewMember(
    val userId: String,
    val name: String,
    /** Their project mailbox, which production mail should reach. */
    val mailboxAddress: String? = null,
    /** The personal address most members never set. */
    val email: String? = null,
    /** Their designation, translated — the second line a suggestion wears. */
    val job: String = "",
    /** "accepted", "approved", "pending", "left", "removed", "rejected", or null. */
    val status: String? = null,
) {
    /** Mailbox first, personal address as the fallback — the web's `resolveUserEmail`. */
    val address: String
        get() = mailboxAddress?.trim()?.takeIf { it.isNotEmpty() } ?: email?.trim().orEmpty()
}

/** An address the composer's To / Cc / Bcc offer: a saved contact, a crew member, or both. */
data class AddressSuggestion(
    val email: String,
    val name: String = "",
    val job: String = "",
    /** Tagged "Crew" so a member is never mistaken for someone already in the address book. */
    val isCrew: Boolean = false,
) {
    val displayName: String get() = name.ifBlank { email }
}

/**
 * The crew who may be offered documents (web `useProjectCrew`, ZL-21622).
 *
 * People who left, were removed or are still pending are dropped — the
 * film-tools rule — and so is anyone who declined the invite. A member with
 * no address to send to has no place in a recipient list either.
 */
fun List<DocDistCrewMember>.sendableCrew(): List<AddressSuggestion> {
    val seen = HashSet<String>()
    return asSequence()
        .filter { it.status !in HIDDEN_STATUSES }
        .map { it to it.address }
        .filter { (_, address) -> address.isNotEmpty() && isValidEmail(address) }
        .map { (member, address) -> AddressSuggestion(address, member.name, member.job, isCrew = true) }
        .filter { seen.add(it.email.lowercase()) }
        .toList()
}

/**
 * Saved contacts and crew, one row per address (web 3695bf36c).
 *
 * Merged per field: a contact's name and department win when it has them —
 * they were entered on purpose — but an empty one never erases what the crew
 * record knows, or the member stops being findable by name and the typed name
 * lands in the field as an invalid recipient. Being in the address book as
 * well does not stop someone being crew.
 */
fun addressSuggestions(contacts: List<Contact>, crew: List<AddressSuggestion>): List<AddressSuggestion> {
    val byEmail = LinkedHashMap<String, AddressSuggestion>()
    crew.forEach { byEmail[it.email.lowercase()] = it }
    contacts.filter { it.email.isNotBlank() }.forEach { contact ->
        val key = contact.email.lowercase()
        val asCrew = byEmail[key]
        byEmail[key] = AddressSuggestion(
            email = contact.email,
            name = contact.name.ifBlank { asCrew?.name.orEmpty() },
            job = contact.jobTitle.ifBlank { asCrew?.job.orEmpty() },
            isCrew = asCrew != null,
        )
    }
    return byEmail.values.toList()
}

private val HIDDEN_STATUSES = setOf("left", "removed", "pending", "rejected")
