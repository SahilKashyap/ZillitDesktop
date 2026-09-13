package com.zillit.desktop.feature.documentdistribution.ui

import com.zillit.desktop.feature.documentdistribution.domain.Contact
import com.zillit.desktop.feature.documentdistribution.domain.CsvContact
import com.zillit.desktop.feature.documentdistribution.domain.DateGroup
import com.zillit.desktop.feature.documentdistribution.domain.Distribution
import com.zillit.desktop.feature.documentdistribution.domain.DistributionList
import com.zillit.desktop.feature.documentdistribution.domain.DistributionSender
import com.zillit.desktop.feature.documentdistribution.domain.DocDistSignature
import com.zillit.desktop.feature.documentdistribution.domain.DocDistViewer
import com.zillit.desktop.feature.documentdistribution.domain.EmailTemplate
import com.zillit.desktop.feature.documentdistribution.domain.LibraryDocument
import com.zillit.desktop.feature.documentdistribution.domain.LibraryFolder
import com.zillit.desktop.feature.documentdistribution.domain.LibraryGrouping
import com.zillit.desktop.feature.documentdistribution.domain.LibrarySort
import com.zillit.desktop.feature.documentdistribution.domain.ListUsed
import com.zillit.desktop.feature.documentdistribution.domain.LocalFile
import com.zillit.desktop.feature.documentdistribution.domain.MAX_TOTAL_ATTACHMENT_BYTES
import com.zillit.desktop.feature.documentdistribution.domain.NewDistribution
import com.zillit.desktop.feature.documentdistribution.domain.PublishDraft
import com.zillit.desktop.feature.documentdistribution.domain.PublishTarget
import com.zillit.desktop.feature.documentdistribution.domain.PublishedFile
import com.zillit.desktop.feature.documentdistribution.domain.Recipient
import com.zillit.desktop.feature.documentdistribution.domain.WatermarkStyle
import com.zillit.desktop.feature.documentdistribution.domain.isValidEmail
import kotlinx.datetime.LocalDate

/** How the library draws its rows — the web's list / grid toggle. */
enum class LibraryView { List, Grid }

/** The composer's two stages: fill it in, then read it back before it goes. */
enum class ComposerStage { Compose, Preview }

/** The floating card shown while a batch of files goes up. */
data class UploadProgress(
    val stage: Stage,
    val name: String = "",
    val index: Int = 0,
    val total: Int = 0,
) {
    enum class Stage { Preparing, Uploading }

    val title: String
        get() = when (stage) {
            Stage.Preparing -> "Preparing files…"
            Stage.Uploading -> "Uploading ${index + 1} of $total"
        }
}

/** The composer, as the user has filled it in. Held in the screen state so a
 * send survives the window being switched away from and back. */
data class ComposerState(
    val open: Boolean = false,
    val stage: ComposerStage = ComposerStage.Compose,
    /** The folder being distributed, when the send started from one. */
    val folder: LibraryFolder? = null,
    val subject: String = "",
    /** The body as edited — plain text with the HTML the templates carry flattened. */
    val body: String = "",
    val to: List<Recipient> = emptyList(),
    val cc: List<Recipient> = emptyList(),
    val bcc: List<Recipient> = emptyList(),
    /** Free text in each address field, not yet turned into a chip. */
    val toInput: String = "",
    val ccInput: String = "",
    val bccInput: String = "",
    val showCcBcc: Boolean = false,
    /** Library documents and device uploads chosen for this send, in send order. */
    val attachments: List<LibraryDocument> = emptyList(),
    /**
     * Which attachments are stamped. Every watermark-capable attachment starts
     * selected — the sender opts *out* per file (ZL-19547).
     */
    val watermarked: Set<String> = emptySet(),
    val watermark: WatermarkStyle = WatermarkStyle(),
    /** The wizard's draft while it is open; null when closed. */
    val wizardDraft: WatermarkStyle? = null,
    /** The attachment whose stamped preview is open. */
    val watermarkPreview: LibraryDocument? = null,
    val listId: String? = null,
    /** Which lists were applied and what each added (ZL-20299). */
    val listsUsed: List<ListUsed> = emptyList(),
    val listMenuOpen: Boolean = false,
    val templateMenuOpen: Boolean = false,
    val signatureMenuOpen: Boolean = false,
    val signatures: List<DocDistSignature> = emptyList(),
    /**
     * The sign-off appended beneath the body at send time — kept apart from
     * the text so its HTML survives a text editor. Null when the body already
     * carries one (a duplicated send) or the user removed it.
     */
    val signature: DocDistSignature? = null,
    /** True once a duplicated body brought its own sign-off, so the auto-insert stays off. */
    val signatureSuppressed: Boolean = false,
    val uploading: UploadProgress? = null,
    /** Files that would breach the size cap, awaiting the user's pick. */
    val oversize: OversizeChooser? = null,
    val sending: Boolean = false,
) {
    /** Ids that will actually carry a stamp — capability and choice, both. */
    val effectiveWatermarks: Map<String, WatermarkStyle>
        get() = attachments
            .filter { it.isWatermarkable && it.id in watermarked }
            .associate { it.id to watermark }

    val stampedCount: Int get() = attachments.count { it.isWatermarkable && it.id in watermarked }

    val totalBytes: Long get() = attachments.sumOf { it.sizeBytes }

    val overSizeLimit: Boolean get() = totalBytes > MAX_TOTAL_ATTACHMENT_BYTES

    val invalidTo: List<Recipient> get() = to.filterNot { isValidEmail(it.email) }
    val invalidCc: List<Recipient> get() = cc.filterNot { isValidEmail(it.email) }
    val invalidBcc: List<Recipient> get() = bcc.filterNot { isValidEmail(it.email) }

    val title: String get() = if (folder != null) "Distribute folder" else "Compose email"

    /** The draft as the domain validates it; [problem] is what blocks Send. */
    fun draft(replyTo: String?, folderId: String?): NewDistribution = NewDistribution(
        subject = subject,
        bodyHtml = body,
        to = to,
        cc = cc,
        bcc = bcc,
        folderId = folderId,
        listId = listId,
        attachmentIds = attachments.filterNot { it.isEphemeral }.map { it.id },
        ephemeralAttachmentIds = attachments.filter { it.isEphemeral }.map { it.id },
        watermarks = effectiveWatermarks,
        replyTo = replyTo,
        listsUsed = listsUsed,
        totalBytes = totalBytes,
    )

    val problem: String? get() = draft(null, null).validationError()
}

/** A device selection that breaches the cap: the user chooses which to keep. */
data class OversizeChooser(
    val files: List<LocalFile>,
    val baseBytes: Long,
    val selected: Set<Int>,
) {
    val selectedBytes: Long
        get() = baseBytes + files.filterIndexed { index, _ -> index in selected }.sumOf { it.sizeBytes }
    val over: Boolean get() = selectedBytes > MAX_TOTAL_ATTACHMENT_BYTES
}

/** The "New folder" / "Edit folder" form. [folderId] null means create. */
data class FolderEditorState(
    val folderId: String? = null,
    val parent: LibraryFolder? = null,
    val name: String = "",
    val description: String = "",
    /** `YYYY-MM-DD`; mandatory on create because the library groups by it. */
    val folderDate: String = "",
    val saving: Boolean = false,
) {
    val isNew: Boolean get() = folderId == null
    val canSave: Boolean get() = name.isNotBlank() && (!isNew || folderDate.isNotBlank()) && !saving
}

/** The in-app preview: the document, its bytes as they arrive, and how they render. */
data class PreviewState(
    val document: LibraryDocument,
    val loading: Boolean = true,
    /** The raw bytes — an image draws these; a download saves them. */
    val bytes: ByteArray? = null,
    /** Rasterised PDF pages as PNG bytes. */
    val pages: List<ByteArray> = emptyList(),
    /** A vCard's text. */
    val text: String? = null,
    val error: String? = null,
    val downloading: Boolean = false,
) {
    override fun equals(other: Any?): Boolean = other is PreviewState && other.document.id == document.id &&
        other.loading == loading && other.error == error && other.downloading == downloading &&
        other.pages.size == pages.size && (other.bytes?.size ?: 0) == (bytes?.size ?: 0) && other.text == text

    override fun hashCode(): Int = document.id.hashCode()
}

/** "Download with watermark" for one document. */
data class WatermarkDownloadState(
    val document: LibraryDocument,
    val line1: String = "CONFIDENTIAL",
    val line2: String = "",
    val style: WatermarkStyle = WatermarkStyle(),
    /** The file rendered behind the stamp — the first PDF page, or the image itself. */
    val previewImage: ByteArray? = null,
    val previewLoading: Boolean = false,
    val downloading: Boolean = false,
) {
    /** Two lines joined by a newline, which every engine splits on. */
    val stampText: String
        get() = listOf(line1, line2).map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")
            .ifBlank { "CONFIDENTIAL" }

    override fun equals(other: Any?): Boolean = other is WatermarkDownloadState && other.document.id == document.id &&
        other.line1 == line1 && other.line2 == line2 && other.style == style &&
        other.previewLoading == previewLoading &&
        other.downloading == downloading && (other.previewImage?.size ?: 0) == (previewImage?.size ?: 0)

    override fun hashCode(): Int = document.id.hashCode()
}

/** The three-step "watermark and download a zip" flow. */
data class WatermarkBatchState(
    val documents: List<LibraryDocument>,
    val style: WatermarkStyle = WatermarkStyle(),
    val recipients: List<Recipient> = emptyList(),
    val recipientInput: String = "",
    val listMenuOpen: Boolean = false,
    val downloading: Boolean = false,
) {
    val canDownload: Boolean get() = documents.isNotEmpty() && recipients.isNotEmpty() && !downloading
}

/** Which flow opened the library picker, so its confirm lands in the right place. */
enum class PickerPurpose { Composer, Batch }

/** "Attach documents": the whole library, a folder filter, a search, and the ticks. */
data class DocumentPickerState(
    val purpose: PickerPurpose,
    val loading: Boolean = true,
    val documents: List<LibraryDocument> = emptyList(),
    val folderId: String? = null,
    val search: String = "",
    val selected: Set<String> = emptySet(),
)

/** A confirmation the user has to answer before something destructive happens. */
data class DocDistPrompt(
    val title: String,
    val message: String,
    val confirmLabel: String,
    val event: DocDistEvent,
)

/** One send opened from History: the full record, and the "save as list" ask. */
data class HistoryDetailState(
    val id: String,
    val distribution: Distribution? = null,
    val loading: Boolean = true,
    /** Non-null while the "create list from recipients" dialog is open. */
    val saveListName: String? = null,
    val savingList: Boolean = false,
)

/** A parsed CSV awaiting confirmation in the list editor. */
data class CsvImportState(val fileName: String = "", val rows: List<CsvContact> = emptyList()) {
    val importable: List<CsvContact> get() = rows.filter { it.valid && !it.duplicate }
}

/** One list open for editing — the web's `DistributionListDetail`. */
data class ListDetailState(
    val listId: String,
    val name: String,
    val description: String = "",
    val recipients: List<Recipient> = emptyList(),
    val emailInput: String = "",
    val nameInput: String = "",
    val jobInput: String = "",
    val csv: CsvImportState? = null,
    val saving: Boolean = false,
    val removing: Boolean = false,
)

/** The composer's "Create new list" — a list built without leaving the send. */
data class ListEditorState(
    val name: String = "",
    val description: String = "",
    val recipients: List<Recipient> = emptyList(),
    val emailInput: String = "",
    val nameInput: String = "",
    val jobInput: String = "",
    val csv: CsvImportState? = null,
    val saving: Boolean = false,
)

/** The add / edit contact form. [originalEmail] blank means add. */
data class ContactEditorState(
    val originalEmail: String = "",
    val name: String = "",
    val email: String = "",
    val job: String = "",
    val listIds: List<String> = emptyList(),
    val saving: Boolean = false,
) {
    val isNew: Boolean get() = originalEmail.isBlank()
    val emailChanged: Boolean get() = !isNew && !email.trim().equals(originalEmail, ignoreCase = true)
}

/** The template editor, shared by the Templates page and the composer's "save current". */
data class TemplateEditorState(
    val templateId: String = "",
    val name: String = "",
    val description: String = "",
    val subject: String = "",
    val body: String = "",
    val saving: Boolean = false,
    /** Opened from the composer: on save, select it there. */
    val fromComposer: Boolean = false,
) {
    val isNew: Boolean get() = templateId.isBlank()
}

/**
 * Everything the Document Distribution window renders.
 *
 * One state for five pages rather than five: the pages share the folder tree,
 * the selection and the composer, and splitting them would mean keeping three
 * copies of the current folder in step.
 */
@Suppress("LongParameterList") // One field per thing on screen; a state is a record.
data class DocDistUiState(
    val viewer: DocDistViewer = DocDistViewer(),
    /** Today as the view model last saw it — for "Today ·" headings and default dates. */
    val today: LocalDate = EPOCH_DAY,
    val destination: DocDistDestination = DocDistDestination.Library,
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
    /** "Preparing documents…" — a cross-folder fetch is in flight. */
    val busy: String? = null,

    // -- library ----------------------------------------------------------
    val folders: List<LibraryFolder> = emptyList(),
    val currentFolderId: String? = null,
    val documents: List<LibraryDocument> = emptyList(),
    val dateCounts: Map<String, Int> = emptyMap(),
    val totalDocuments: Int = 0,
    val groups: List<DateGroup> = emptyList(),
    val search: String = "",
    /** `YYYY-MM-DD` when filtering to one production day. */
    val dateFilter: String? = null,
    val sort: LibrarySort = LibrarySort.NameAsc,
    val view: LibraryView = LibraryView.List,
    val infoBannerDismissed: Boolean = false,
    val selectedDocumentIds: Set<String> = emptySet(),
    val selectedFolderIds: Set<String> = emptySet(),
    val dragHover: Boolean = false,
    val upload: UploadProgress? = null,
    val folderEditor: FolderEditorState? = null,
    /** Non-null while the "Move items" dialog is open. */
    val moveTarget: MoveTargetState? = null,
    /** Non-null while the Publish dialog is open. */
    val publish: PublishState? = null,
    val preview: PreviewState? = null,
    val watermarkDownload: WatermarkDownloadState? = null,
    val watermarkBatch: WatermarkBatchState? = null,
    val picker: DocumentPickerState? = null,

    // -- history ----------------------------------------------------------
    val history: List<Distribution> = emptyList(),
    val historyTotal: Int = 0,
    val historyLoadingMore: Boolean = false,
    val historySearch: String = "",
    /** The "Sent by" menu: everyone the history could have been sent by, the ids ticked, and the menu's own search. */
    val historySenders: List<DistributionSender> = emptyList(),
    val historySenderIds: Set<String> = emptySet(),
    val historySenderQuery: String = "",
    val historySenderMenuOpen: Boolean = false,
    val expandedDistributionId: String? = null,
    val historyDetail: HistoryDetailState? = null,

    // -- lists ------------------------------------------------------------
    val lists: List<DistributionList> = emptyList(),
    val listsSearch: String = "",
    /** Non-null while the inline "New list" row is open. */
    val newListName: String? = null,
    val creatingList: Boolean = false,
    val listDetail: ListDetailState? = null,
    val exportingListId: String? = null,
    val listEditor: ListEditorState? = null,

    // -- address book -----------------------------------------------------
    val contacts: List<Contact> = emptyList(),
    val contactsSearch: String = "",
    val selectedContactEmail: String? = null,
    val contactEditor: ContactEditorState? = null,
    /** The sends the address book matches contacts against. */
    val contactDistributions: List<Distribution> = emptyList(),
    val loadingContactEmails: Boolean = false,
    val viewingEmail: Distribution? = null,
    /** The list a contact is being added to or removed from, while that write is in flight. */
    val contactListBusyId: String? = null,

    // -- templates --------------------------------------------------------
    val templates: List<EmailTemplate> = emptyList(),
    val templateEditor: TemplateEditorState? = null,

    val composer: ComposerState = ComposerState(),
    val prompt: DocDistPrompt? = null,
) {

    /** The pages this viewer may open, in tab order. */
    val destinations: List<DocDistDestination>
        get() = DocDistDestination.entries.filter { it.visibleTo(viewer) }

    val currentFolder: LibraryFolder?
        get() = currentFolderId?.let { id -> folders.firstOrNull { it.id == id } }

    /** Folders directly inside the open one — the root's children when at root. */
    val subfolders: List<LibraryFolder>
        get() = folders.filter { it.parentId == currentFolderId }.sortedBy { it.name.lowercase() }

    /**
     * The subfolders after the search and date filter — folders load whole,
     * so both apply here (documents come back filtered from the server).
     */
    val visibleSubfolders: List<LibraryFolder>
        get() {
            val q = search.trim().lowercase()
            return subfolders.filter { folder ->
                (q.isEmpty() || folder.name.lowercase().contains(q)) &&
                    (dateFilter == null || folder.folderDate == dateFilter)
            }
        }

    val hasActiveFilter: Boolean get() = search.isNotBlank() || dateFilter != null

    /**
     * The listing as drawn: one flat alphabetical run under a name sort, or
     * date buckets under a date sort with folders first in each — the web's
     * `grouped`. Folders bucket by their production date, documents by theirs.
     */
    val rows: List<LibraryRowGroup>
        get() {
            val folders = visibleSubfolders
            if (!sort.groupsByDate) {
                val direction = if (sort == LibrarySort.NameAsc) 1 else -1
                val items = (folders.map { LibraryRow.Folder(it) } + documents.map { LibraryRow.File(it) })
                    .sortedWith { a, b -> direction * a.name.compareTo(b.name, ignoreCase = true) }
                return if (items.isEmpty()) emptyList() else listOf(
                    LibraryRowGroup(key = null, heading = null, items = items),
                )
            }
            val folderBuckets = folders.groupBy { it.folderDate }
            val documentBuckets = groups.associateBy { it.key }
            val keys = (folderBuckets.keys + documentBuckets.keys).distinct().sortedWith(
                compareBy<String> { it.isBlank() }.thenComparator { a, b ->
                    if (sort == LibrarySort.DateAsc) a.compareTo(b) else b.compareTo(a)
                },
            )
            return keys.map { key ->
                val bucketFolders = folderBuckets[key].orEmpty().map { LibraryRow.Folder(it) }
                val bucket = documentBuckets[key]
                val files = bucket?.documents.orEmpty().map { LibraryRow.File(it) }
                val heading = bucket?.heading ?: LibraryGrouping.heading(key, today)
                val total = bucket?.let { it.total + bucketFolders.size }
                LibraryRowGroup(key = key, heading = heading, items = bucketFolders + files, total = total)
            }
        }

    val isEmptyListing: Boolean get() = visibleSubfolders.isEmpty() && documents.isEmpty()

    /**
     * The path from the root to the open folder.
     *
     * Walked from the child upward against a depth cap: the tree comes from a
     * server, and a row whose parent chain loops back on itself would otherwise
     * hang the window rather than draw a wrong breadcrumb.
     */
    val breadcrumb: List<LibraryFolder>
        get() {
            val byId = folders.associateBy { it.id }
            val chain = mutableListOf<LibraryFolder>()
            var cursor = currentFolder
            while (cursor != null && chain.size < MAX_DEPTH) {
                chain.add(0, cursor)
                cursor = cursor.parentId?.let(byId::get)
            }
            return chain
        }

    val selectedDocuments: List<LibraryDocument>
        get() = documents.filter { it.id in selectedDocumentIds }

    val selectionCount: Int get() = selectedDocumentIds.size + selectedFolderIds.size

    /** Every id on screen, for "select all". */
    val visibleIds: List<String>
        get() = visibleSubfolders.map { it.id } + documents.map { it.id }

    val allSelected: Boolean
        get() = visibleIds.isNotEmpty() && visibleIds.all { it in selectedDocumentIds || it in selectedFolderIds }

    /** A folder plus every folder beneath it, for the cross-folder actions. */
    fun folderWithDescendants(rootId: String): Set<String> {
        val byParent = folders.groupBy { it.parentId }
        val out = mutableSetOf<String>()
        fun walk(id: String) {
            if (!out.add(id)) return
            byParent[id].orEmpty().forEach { walk(it.id) }
        }
        walk(rootId)
        return out
    }

    /** The folder tree flattened with depth, for pickers. */
    fun folderRows(): List<MoveDestination> {
        val byParent = folders.groupBy { it.parentId }
        val out = mutableListOf<MoveDestination>()
        fun walk(parentId: String?, depth: Int) {
            if (depth > MAX_DEPTH) return
            byParent[parentId].orEmpty().sortedBy { it.name.lowercase() }.forEach { folder ->
                out += MoveDestination(folder.id, folder.name, depth)
                walk(folder.id, depth + 1)
            }
        }
        walk(null, 0)
        return out
    }

    /**
     * The folders a move may land in, deepest-first paths flattened for a list.
     *
     * A folder being moved is excluded along with everything beneath it: the
     * service will happily reparent a folder into its own subtree, which
     * detaches that whole branch from the root and leaves its documents
     * reachable by nothing. The web offers every folder and lets it happen.
     */
    fun moveDestinations(): List<MoveDestination> {
        val barred = selectedFolderIds.flatMap { folderWithDescendants(it) }.toSet()
        return folderRows().filter { it.id !in barred }
    }

    /**
     * Whether the Library root is off-limits as a move destination.
     *
     * A file must live inside a folder — both phones enforce it and say so.
     * Folders alone may go to the root, which is how a top-level folder is made.
     */
    val rootForbidden: Boolean get() = selectedDocumentIds.isNotEmpty()

    val hasMore: Boolean get() = documents.size < totalDocuments

    val historyHasMore: Boolean get() = history.size < historyTotal

    /** Whether the "view only" banner belongs on screen. */
    val showRestrictionBanner: Boolean get() = viewer.isRestricted

    val selectedContact: Contact?
        get() = selectedContactEmail?.let { email ->
            contacts.firstOrNull { it.email.equals(email, ignoreCase = true) }
        }

    /** Contacts after the search box, by name — the address book's sidebar. */
    val visibleContacts: List<Contact>
        get() {
            val q = contactsSearch.trim().lowercase()
            return contacts.filter { c ->
                q.isEmpty() || c.email.lowercase().contains(q) || c.name.lowercase().contains(q) ||
                    c.lists.any { it.name.lowercase().contains(q) }
            }.sortedBy { it.displayName.lowercase() }
        }

    val visibleLists: List<DistributionList>
        get() {
            val q = listsSearch.trim().lowercase()
            return if (q.isEmpty()) lists else lists.filter { it.name.lowercase().contains(q) }
        }

    private companion object {
        /** Deeper than any real library, shallow enough to stop a cycle dead. */
        const val MAX_DEPTH = 64
    }
}

/** A stand-in until the view model stamps the real day; never shown as a heading. */
private val EPOCH_DAY = LocalDate(2000, 1, 1)

/** One row of the listing — a folder or a document, drawn alike. */
sealed interface LibraryRow {
    val id: String
    val name: String

    data class Folder(val folder: LibraryFolder) : LibraryRow {
        override val id: String get() = folder.id
        override val name: String get() = folder.name
    }

    data class File(val document: LibraryDocument) : LibraryRow {
        override val id: String get() = document.id
        override val name: String get() = document.name
    }
}

/** A date bucket of the listing; [heading] null means the flat, unbucketed run. */
data class LibraryRowGroup(
    val key: String?,
    val heading: String?,
    val items: List<LibraryRow>,
    /** The server's full count for the date, plus the folders shown — null when unknown. */
    val total: Int? = null,
) {
    /** "12 of 80 items" when the server told us the day's total, else a plain count. */
    val countLabel: String
        get() = if (total != null && items.any { it is LibraryRow.File }) "${items.size} of $total items"
        else "${items.size} item" + if (items.size == 1) "" else "s"
}

/** The open "Move items" dialog: what is being moved, and where to. */
data class MoveTargetState(val destinationId: String? = null, val saving: Boolean = false)

/** One row in the destination picker. [depth] is its indent under the root. */
data class MoveDestination(val id: String, val name: String, val depth: Int)

/**
 * The Publish dialog: which destination, and the fields that destination
 * demands. [alreadyPublished] is loaded per category and is what turns the
 * add/replace choice on — a first publish has nothing to replace.
 */
data class PublishState(
    val target: PublishTarget? = null,
    val draft: PublishDraft = PublishDraft(),
    /** The documents going out, for the dialog's file list. */
    val files: List<LibraryDocument> = emptyList(),
    val folder: LibraryFolder? = null,
    val alreadyPublished: List<PublishedFile> = emptyList(),
    val loadingPublished: Boolean = false,
    val saving: Boolean = false,
) {
    /** Add / replace is offered only where the destination republishes *and* something is there. */
    val offersMode: Boolean
        get() = target?.republishable == true && !loadingPublished && alreadyPublished.isNotEmpty()

    fun problem(isTelevision: Boolean): String? {
        val chosen = target ?: return "Choose a destination"
        val effective = if (offersMode) draft else draft.copy(replaceChatIds = emptyList())
        return chosen.problem(effective, isTelevision)
    }
}
