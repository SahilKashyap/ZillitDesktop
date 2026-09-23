package com.zillit.desktop.feature.documentdistribution.domain

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * One page of the library listing.
 *
 * [total] and [dateCounts] come from the server rather than being counted here:
 * the listing is paged, so the client only ever holds a window and every "X of
 * Y" on screen would otherwise be a lie about the window's own size.
 */
data class LibraryPage(
    val documents: List<LibraryDocument>,
    val total: Int,
    /** `YYYY-MM-DD` → how many documents that day holds in full. */
    val dateCounts: Map<String, Int> = emptyMap(),
)

/** What the listing is asking for. */
data class LibraryQuery(
    val folderId: String? = null,
    val search: String = "",
    /** `YYYY-MM-DD` exact match on the production date, when filtering by day. */
    val documentDate: String? = null,
    val sort: LibrarySort = LibrarySort.NameAsc,
    val page: Int = 0,
    val limit: Int = PAGE_LIMIT,
) {
    companion object {
        /** Matches the web's `PAGE_LIMIT`, so both clients page identically. */
        const val PAGE_LIMIT = 50
    }
}

/**
 * A send, as the composer has it before the server accepts it.
 *
 * Holds the validation rather than the screen: the same rules apply to a send
 * started from the library, from a tool ("distribute to doc dist") and from a
 * duplicated past send, and three copies of them would disagree.
 */
data class NewDistribution(
    val subject: String,
    val bodyHtml: String,
    val to: List<Recipient>,
    val cc: List<Recipient> = emptyList(),
    val bcc: List<Recipient> = emptyList(),
    val folderId: String? = null,
    val listId: String? = null,
    /** Library documents, by id. */
    val attachmentIds: List<String> = emptyList(),
    /** One-shot uploads that were never catalogued, by id. */
    val ephemeralAttachmentIds: List<String> = emptyList(),
    /** Attachment id → the stamp to apply. Absent ids go out unstamped. */
    val watermarks: Map<String, WatermarkStyle> = emptyMap(),
    val replyTo: String? = null,
    /** The lists that fed this send and what each contributed (ZL-20299). */
    val listsUsed: List<ListUsed> = emptyList(),
    /** Combined size of every attachment, checked against [MAX_TOTAL_ATTACHMENT_BYTES]. */
    val totalBytes: Long = 0,
) {
    val totalAttachments: Int get() = attachmentIds.size + ephemeralAttachmentIds.size

    /**
     * The first reason this cannot be sent, or null.
     *
     * A missing subject is *not* one of them — the web sends "(no subject)" and
     * a coordinator re-issuing a call sheet under an empty subject is doing
     * something ordinary, not something to be blocked over.
     */
    @Suppress("ReturnCount") // One rule per return; merging them loses which failed.
    fun validationError(): String? {
        if (to.isEmpty()) return str(S.one_receipent_required)
        val bad = (to + cc + bcc).filterNot { isValidEmail(it.email) }
        if (bad.isNotEmpty()) {
            val addresses = bad.joinToString(", ") { it.email }
            return if (bad.size > 1) str(S.desktop_docdist_fix_invalid_email_addresses, addresses)
            else str(S.desktop_docdist_fix_invalid_email_address, addresses)
        }
        if (totalAttachments == 0) return str(S.desktop_docdist_attach_at_least_one_document)
        if (totalBytes > MAX_TOTAL_ATTACHMENT_BYTES) {
            return str(
                S.desktop_docdist_attachments_exceed_limit,
                formatBytes(MAX_TOTAL_ATTACHMENT_BYTES),
                formatBytes(totalBytes),
            )
        }
        return null
    }
}

/** Values the composer and the wire have to agree on. */
object NewDistributionDefaults {
    /**
     * What an empty subject is sent as.
     *
     * The server substitutes this itself, but sending it explicitly is what
     * makes the History row read identically on both clients — otherwise the
     * web shows "(no subject)" and this shows an empty cell for the same send.
     */
    const val NO_SUBJECT = "(no subject)"
}

/** Deliberately loose — the server is the authority; this only catches typos. */
internal fun String.looksLikeEmail(): Boolean {
    val at = indexOf('@')
    if (at <= 0 || at != lastIndexOf('@')) return false
    val domain = substring(at + 1)
    return domain.contains('.') && !domain.startsWith('.') && !domain.endsWith('.') &&
        none { it.isWhitespace() }
}

/**
 * Everything Document Distribution asks the server for.
 *
 * Mirrors the web's `documentDistribution/api.js` method for method, so a
 * change on either side is findable on the other. One interface rather than
 * five (folders, documents, lists, contacts, history): they are one service
 * with one authorisation model, and splitting them would mean five fakes in
 * every test for no gain.
 */
@Suppress("TooManyFunctions") // One suspend fun per server operation; see detekt.yml.
interface DocDistRepository {

    /**
     * A pulse per realtime mutation another client announced, naming the
     * destination whose listing went stale — the web's screens refresh
     * independently (library via `useDocumentDistribution.js:132-138`,
     * history via `HistoryDrawer.jsx:726-741`, presets and templates via
     * their managers), so the kind travels with the pulse and the view
     * model reloads only what is on screen. Defaulted empty for tests and
     * hosts without a socket.
     */
    val refreshes: Flow<DocDistRefresh> get() = emptyFlow()

    // -- library -----------------------------------------------------------

    /**
     * Every folder, in one call.
     *
     * Not paged, and deliberately so: the breadcrumb trail and the move-to
     * picker both need the *whole* tree to resolve a parent chain, and a paged
     * folder list makes an ancestor lookup a sequence of round trips. Folder
     * counts are small in practice — the web does the same.
     */
    suspend fun folders(): ZillitResult<List<LibraryFolder>>

    /** [folderDate] is `YYYY-MM-DD` and mandatory: the library groups by it on every view. */
    suspend fun createFolder(
        name: String,
        parentId: String?,
        description: String = "",
        folderDate: String? = null,
    ): ZillitResult<Unit>

    suspend fun updateFolder(folderId: String, name: String, description: String): ZillitResult<Unit>

    suspend fun deleteFolder(folderId: String): ZillitResult<Unit>

    suspend fun moveFolders(folderIds: List<String>, parentId: String?): ZillitResult<Unit>

    suspend fun documents(query: LibraryQuery): ZillitResult<LibraryPage>

    /**
     * Every document inside [folderIds], one high-limit query each.
     *
     * For the actions that act on a folder — distribute, publish, watermark —
     * where the paged listing cannot be relied on to hold the folder's rows.
     * De-duplicated, because a folder can appear twice in the input.
     */
    suspend fun documentsInFolders(folderIds: Collection<String>): ZillitResult<List<LibraryDocument>>

    /** The whole library, flat, for the composer's picker. */
    suspend fun allDocuments(): ZillitResult<List<LibraryDocument>>

    /** Library documents by id, for re-hydrating a past send's attachments. */
    suspend fun documentsByIds(ids: List<String>): ZillitResult<List<LibraryDocument>>

    /** Device uploads by id, for the same — rows the janitor swept are simply absent. */
    suspend fun ephemeralByIds(ids: List<String>): ZillitResult<List<LibraryDocument>>

    /**
     * Puts a file into the library.
     *
     * S3 productions PUT the bytes to storage and register them with
     * `documents/from-s3`; LOCAL ones multipart to `documents`. Either way the
     * caller gets the catalogued row back.
     */
    suspend fun uploadDocument(
        file: LocalFile,
        folderId: String?,
        documentDate: String?,
    ): ZillitResult<LibraryDocument>

    /** A one-shot composer attachment — never catalogued, swept after the send. */
    suspend fun uploadEphemeral(file: LocalFile): ZillitResult<LibraryDocument>

    suspend fun deleteEphemeral(attachmentId: String): ZillitResult<Unit>

    /** The document's bytes, for the in-app preview and for saving a copy. */
    suspend fun documentBytes(document: LibraryDocument): ZillitResult<ByteArray>

    /** One document stamped by the server with [text] in [style]. */
    suspend fun watermarkedCopy(
        documentId: String,
        text: String,
        style: WatermarkStyle,
    ): ZillitResult<ByteArray>

    /** A zip, one folder per recipient, each holding their personalised copies. */
    suspend fun watermarkedZip(
        documentIds: List<String>,
        recipients: List<ZipRecipient>,
        style: WatermarkStyle,
    ): ZillitResult<ByteArray>

    suspend fun deleteDocument(documentId: String): ZillitResult<Unit>

    // -- watermark settings ------------------------------------------------

    /**
     * The production's shared Size / Colour / Opacity defaults, as another
     * client just saved them (`document_distribution:watermark_settings:updated`).
     * The event carries the whole settings object, so the cache is replaced
     * and nothing is refetched. A save from this very device is filtered out
     * before it gets here — its PUT already answered the same object.
     */
    val watermarkSettingsUpdates: Flow<WatermarkSettings> get() = emptyFlow()

    /** Always answers for a project member: the built-in values until someone saves. */
    suspend fun watermarkSettings(): ZillitResult<WatermarkSettings>

    /**
     * Saves the fields in [patch]; the rest keep their value. Answers the
     * settings after the save. An empty patch is refused by the server
     * (`watermark_settings_required`), so callers skip it.
     */
    suspend fun updateWatermarkSettings(patch: WatermarkSettingsPatch): ZillitResult<WatermarkSettings>

    suspend fun moveDocuments(documentIds: List<String>, folderId: String?): ZillitResult<Unit>

    /**
     * A time-limited URL for reading one document's bytes.
     *
     * Returned rather than the bytes themselves: previews and downloads both
     * want the file, and pulling megabytes through the API client to hand them
     * straight back out again would double the transfer for no benefit.
     */
    /**
     * A URL the OS browser can open for this document.
     *
     * Takes the document rather than its id: where the bytes live is on the
     * row the listing already returned, and a second round trip to ask the
     * server would hit a route that does not exist.
     */
    suspend fun documentUrl(document: LibraryDocument): ZillitResult<String>

    // -- distribution lists ------------------------------------------------

    suspend fun lists(): ZillitResult<List<DistributionList>>

    /** Returns the created list, id and all, so the composer can select it at once. */
    suspend fun createList(
        name: String,
        recipients: List<Recipient>,
        description: String = "",
    ): ZillitResult<DistributionList>

    /**
     * Replaces a list's membership — the endpoint replaces rather than
     * patches. A null [name] leaves the name alone, which is how the address
     * book edits membership without knowing the list's other fields.
     */
    suspend fun updateList(
        listId: String,
        name: String?,
        recipients: List<Recipient>,
        description: String? = null,
    ): ZillitResult<Unit>

    suspend fun deleteList(listId: String): ZillitResult<Unit>

    /** The server's CSV of a list's members (`name,email,job`). */
    suspend fun exportList(listId: String): ZillitResult<ByteArray>

    // -- address book ------------------------------------------------------

    suspend fun contacts(): ZillitResult<List<Contact>>

    suspend fun saveContact(contact: Contact): ZillitResult<Unit>

    suspend fun deleteContact(email: String): ZillitResult<Unit>

    // -- templates ---------------------------------------------------------

    suspend fun templates(): ZillitResult<List<EmailTemplate>>

    suspend fun saveTemplate(template: EmailTemplate): ZillitResult<Unit>

    suspend fun deleteTemplate(templateId: String): ZillitResult<Unit>

    // -- sending and history -----------------------------------------------

    suspend fun send(distribution: NewDistribution): ZillitResult<Unit>

    /**
     * @param senderIds the "Sent by" filter — ZL-21138: sent as `sent_by=id1,id2`,
     *   which the backend treats as OR; empty omits the param (absence is "All").
     */
    suspend fun history(
        page: Int,
        search: String,
        senderIds: Set<String> = emptySet(),
        /** Rows per page; the server caps it at 200. */
        limit: Int = HISTORY_PAGE_LIMIT,
    ): ZillitResult<HistoryPage>

    /**
     * Every distinct sender across the project's history, for the "Sent by"
     * menu. The endpoint is not shipped everywhere; a failure is an empty
     * list and the caller falls back to the senders it can read off the rows.
     */
    suspend fun senders(): ZillitResult<List<DistributionSender>>

    suspend fun distribution(id: String): ZillitResult<Distribution>

    /**
     * Refreshes open status for a set of sent copies.
     *
     * Lives on the **email** service rather than this one — `send` hands back a
     * `unique_id` per recipient and the open pixel is logged there. A caller
     * that asks doc-dist for it gets a 404, which is the kind of thing worth
     * saying once here rather than rediscovering.
     */
    suspend fun openStatus(uniqueIds: List<String>): ZillitResult<Map<String, DeliveryStatus>>

    // -- publishing --------------------------------------------------------

    suspend fun publicationCategories(): ZillitResult<List<PublicationCategory>>

    /** What is currently published under [category]; empty means a first publish. */
    suspend fun publishedFiles(category: String): ZillitResult<List<PublishedFile>>

    /**
     * Publishes into [category]. What [draft] must carry differs by
     * destination — see [PublishTarget], which also validates it.
     */
    suspend fun publish(category: String, draft: PublishDraft): ZillitResult<Unit>
}

/** The History page size — the web's, and the server's default. */
const val HISTORY_PAGE_LIMIT = 50

/** The most the server hands back in one page; the address book asks for it all at once. */
const val HISTORY_MAX_LIMIT = 200

/** One recipient of a watermark zip, with their stamp already rendered. */
data class ZipRecipient(val name: String, val email: String, val watermarkText: String)

/** The stale listing a socket event names; see [DocDistRepository.refreshes]. */
enum class DocDistRefresh { Library, History, Lists, Templates }
