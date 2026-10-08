package com.zillit.desktop.feature.documentdistribution.domain

import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str

/**
 * "Merge PDFs to download or print" — what one run produces, planned.
 *
 * Ported from the web's `utils/mergeJobs.js` and the picker rules in
 * `MergePrintModal.jsx` (2026-10-06/07). Everything here is pure and knows
 * nothing of how the bytes are built: the server concatenates, this file only
 * says which documents go in, whose copies are stamped, and how the run is
 * described to the person pressing Download.
 */

/**
 * Only PDFs can be concatenated.
 *
 * Narrower than [canWatermark], which also takes images: a stamp can be burned
 * into a JPEG, a merge cannot take one. The row's own content type is trusted
 * only when it looks like a MIME type — and `application/octet-stream`, which
 * some uploads land as even for PDFs, defers to the file name instead of
 * counting as a rejection.
 */
fun LibraryDocument.isMergeable(): Boolean {
    val mime = contentType.orEmpty().lowercase().substringBefore(';').trim().takeIf { '/' in it }.orEmpty()
    if (mime.isNotEmpty() && mime !in GENERIC_BINARY) return mime == "application/pdf"
    return name.trim().endsWith(".pdf", ignoreCase = true)
}

private val GENERIC_BINARY = setOf("application/octet-stream", "binary/octet-stream")

/** Someone a merge can be made for — crew, an address-book contact, or both. */
data class MergePerson(
    val email: String,
    val name: String = "",
    /** The designation, for crew. */
    val job: String = "",
    val department: String = "",
    /** Blank for an address-book-only person, who has no account and no photo to resolve. */
    val userId: String = "",
    val isCrew: Boolean = false,
) {
    val displayName: String get() = name.ifBlank { email }

    internal fun searchText(): String = "$name $email $job $department".lowercase()

    /** An address-book person with no account: grouped under "Contacts", not a department. */
    val isContact: Boolean get() = !isCrew && userId.isBlank()
}

/** Everyone the picker can offer, with the signed-in person set apart. */
data class MergeDirectory(val me: MergePerson, val people: List<MergePerson>)

/**
 * Crew and saved contacts as one list, without the person running the merge.
 *
 * Crew alone is not the same set: a production's real crew mostly live in the
 * address book and may never sign in (a client with one other Zillit user saw
 * exactly one name, 2026-10-06). Merged by address, a contact's own fields
 * winning because the book is the hand-kept record of how someone reads — but
 * an empty one never erases what the crew record knows, and being a contact
 * does not stop someone being crew.
 *
 * The signed-in person has a panel of their own, so they are left out of the
 * list by user id *and* by address: a contact entry carries no id, and without
 * the second test the same person could be picked twice.
 *
 * A contact's department rides its `job` wire field (ZL-21622), so that is
 * where the picker reads it from.
 */
fun mergeDirectory(
    crew: List<DocDistCrewMember>,
    contacts: List<Contact>,
    viewerUserId: String,
    viewerEmail: String = "",
): MergeDirectory {
    val sendable = crew.sendableCrew()
    val byEmail = LinkedHashMap<String, MergePerson>()
    sendable.forEach { member ->
        byEmail[member.email.lowercase()] = MergePerson(
            email = member.email,
            name = member.name,
            job = member.job,
            department = member.department,
            userId = member.userId,
            isCrew = true,
        )
    }
    contacts.filter { it.email.isNotBlank() }.forEach { contact ->
        val key = contact.email.lowercase()
        val asCrew = byEmail[key]
        byEmail[key] = MergePerson(
            email = contact.email,
            name = contact.name.ifBlank { asCrew?.name.orEmpty() },
            job = asCrew?.job.orEmpty(),
            department = contact.jobTitle.ifBlank { asCrew?.department.orEmpty() },
            userId = asCrew?.userId.orEmpty(),
            isCrew = asCrew != null,
        )
    }
    // Looked up in the whole crew, not the sendable cut: the person pressing
    // the button is not hidden from their own dialog by a stale status.
    val record = viewerUserId.takeIf { it.isNotBlank() }?.let { id -> crew.firstOrNull { it.userId == id } }
    val me = MergePerson(
        email = record?.address?.takeIf { it.isNotBlank() } ?: viewerEmail,
        name = record?.name.orEmpty(),
        job = record?.job.orEmpty(),
        department = record?.department.orEmpty(),
        userId = viewerUserId,
        isCrew = record != null,
    )
    val myAddress = me.email.lowercase()
    val others = byEmail.values.filter { person ->
        !(person.userId.isNotBlank() && person.userId == viewerUserId) &&
            !(myAddress.isNotEmpty() && person.email.lowercase() == myAddress)
    }
    return MergeDirectory(me, others.sortedBy { it.displayName.lowercase() })
}

/** Which kind of person the picker lists. */
enum class MergeSource { All, Crew, Contacts }

/** The picker's rows after the source filter and the search. */
fun List<MergePerson>.filteredFor(source: MergeSource, search: String): List<MergePerson> {
    val q = search.trim().lowercase()
    return filter { person ->
        when (source) {
            MergeSource.All -> true
            MergeSource.Crew -> !person.isContact
            MergeSource.Contacts -> person.isContact
        } && (q.isEmpty() || person.searchText().contains(q))
    }
}

/** One heading in the picker and the people under it. */
data class MergeGroup(val key: String, val department: String, val isContacts: Boolean, val members: List<MergePerson>)

/**
 * The list in two tiers: the crew under their departments, then everyone who
 * exists only in the address book under "Contacts".
 *
 * Departments run alphabetically so one is always in the same place; people
 * with none fall into [otherLabel], pinned just above the contacts, which are
 * pinned last of all.
 */
fun List<MergePerson>.groupedForMerge(otherLabel: String, contactsLabel: String): List<MergeGroup> {
    val groups = LinkedHashMap<String, MutableList<MergePerson>>()
    forEach { person ->
        val key = when {
            person.isContact -> CONTACTS_KEY
            person.department.isNotBlank() -> person.department.trim()
            else -> OTHER_KEY
        }
        groups.getOrPut(key) { mutableListOf() } += person
    }
    fun rank(key: String) = when (key) {
        CONTACTS_KEY -> 2
        OTHER_KEY -> 1
        else -> 0
    }
    return groups.map { (key, members) ->
        MergeGroup(
            key = key,
            department = when (key) {
                CONTACTS_KEY -> contactsLabel
                OTHER_KEY -> otherLabel
                else -> key
            },
            isContacts = key == CONTACTS_KEY,
            members = members,
        )
    }.sortedWith(compareBy({ rank(it.key) }, { it.department.lowercase() }))
}

private const val CONTACTS_KEY = "\u0000contacts"
private const val OTHER_KEY = "\u0000other"

/**
 * What one run builds.
 *
 * Crew copies are always stamped with their own name — that is the watermark's
 * whole job, tracing a leak back to who held the file. Your own copy is the one
 * you have a say over: [plainCopy] is a clean merge for you (printing, reference),
 * otherwise you are simply the first stamped recipient.
 *
 * ⚠ Which is why one run can need two server calls: `watermark-merged` stamps
 * everything it is given recipients for, so a clean copy cannot ride along with
 * stamped ones. Asked for both, the halves are built separately and joined,
 * the clean one first — it is the one you will look at.
 */
data class MergePlan(
    /** The PDFs that go in, in listing order. */
    val documents: List<LibraryDocument>,
    /** What was selected but cannot be combined, so the dialog can name it. */
    val skipped: List<LibraryDocument>,
    /** A clean (unstamped) copy for the signed-in person. */
    val plainCopy: Boolean,
    /** Everyone getting a stamped copy, the signed-in person first when theirs is stamped. */
    val stamped: List<MergePerson>,
    /** People getting a copy, the signed-in person included whether or not theirs is stamped. */
    val recipientCount: Int,
) {
    val documentCount: Int get() = documents.size
    val copyCount: Int get() = (if (plainCopy) documentCount else 0) + stamped.size * documentCount

    /** True when there is something to build. */
    val isReady: Boolean get() = documentCount > 0 && copyCount > 0

    /** Two calls and a join. */
    val needsJoin: Boolean get() = plainCopy && stamped.isNotEmpty()
}

fun buildMergePlan(
    selection: List<LibraryDocument>,
    me: MergePerson,
    includeSelf: Boolean,
    watermarkSelf: Boolean,
    chosen: List<MergePerson>,
): MergePlan {
    val (mergeable, skipped) = selection.partition { it.isMergeable() }
    val named = chosen.filter { it.email.isNotBlank() || it.name.isNotBlank() }
    val mine = includeSelf && (me.email.isNotBlank() || me.name.isNotBlank())
    return MergePlan(
        documents = mergeable,
        skipped = skipped,
        plainCopy = mine && !watermarkSelf,
        stamped = (if (mine && watermarkSelf) listOf(me) else emptyList()) + named,
        recipientCount = (if (mine) 1 else 0) + named.size,
    )
}

/** "4 documents x 2 recipients": the multiplication is what stops a 400-page file built by accident. */
fun MergePlan.summary(): String {
    if (documentCount == 0) return ""
    val docs = str(
        if (documentCount == 1) S.desktop_dist_document_count_one else S.dd_publish_n_documents,
        documentCount,
    )
    if (recipientCount == 0) return docs
    val people = str(
        if (recipientCount == 1) S.desktop_docdist_merge_one_recipient else S.dd_n_recipients,
        recipientCount,
    )
    return str(S.desktop_docdist_merge_summary, docs, people)
}

/** The merged file's name. Dated, so repeat runs don't collide. */
fun mergedFileName(withCrew: Boolean, folderName: String?, isoDate: String): String {
    // Trailing separators go as well as illegal runs: "A/B:C*?" must not leave a dangling dash.
    val base = (folderName ?: "documents")
        .replace(Regex("[\\\\/:*?\"<>|]+"), "-")
        .trim { it == '-' || it.isWhitespace() }
        .ifEmpty { "documents" }
    return if (withCrew) "$base-watermarked-$isoDate.pdf" else "$base-merged-$isoDate.pdf"
}

/**
 * The server's `watermark-merged` body for the stamped half.
 *
 * Each recipient's text is rendered here, so the server only stamps the final
 * string; "CONFIDENTIAL" stands in for a style that renders blank.
 */
fun MergePlan.stampedRecipients(style: WatermarkStyle): List<ZipRecipient> = stamped.map { person ->
    ZipRecipient(
        name = person.name,
        email = person.email,
        watermarkText = style.render(listOf(Recipient(email = person.email, name = person.name)))
            .ifBlank { "CONFIDENTIAL" },
    )
}
