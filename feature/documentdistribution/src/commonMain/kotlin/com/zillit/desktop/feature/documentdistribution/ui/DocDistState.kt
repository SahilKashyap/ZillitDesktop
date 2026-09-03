package com.zillit.desktop.feature.documentdistribution.ui

import com.zillit.desktop.feature.documentdistribution.domain.Contact
import com.zillit.desktop.feature.documentdistribution.domain.DateGroup
import com.zillit.desktop.feature.documentdistribution.domain.Distribution
import com.zillit.desktop.feature.documentdistribution.domain.DistributionSender
import com.zillit.desktop.feature.documentdistribution.domain.DistributionList
import com.zillit.desktop.feature.documentdistribution.domain.DocDistViewer
import com.zillit.desktop.feature.documentdistribution.domain.EmailTemplate
import com.zillit.desktop.feature.documentdistribution.domain.LibraryDocument
import com.zillit.desktop.feature.documentdistribution.domain.LibraryFolder
import com.zillit.desktop.feature.documentdistribution.domain.PublishDraft
import com.zillit.desktop.feature.documentdistribution.domain.PublishTarget
import com.zillit.desktop.feature.documentdistribution.domain.PublishedFile
import com.zillit.desktop.feature.documentdistribution.domain.LibrarySort
import com.zillit.desktop.feature.documentdistribution.domain.Recipient
import com.zillit.desktop.feature.documentdistribution.domain.WatermarkStyle

/**
 * The composer, as the user has filled it in.
 *
 * Held in the screen state rather than in the dialog's own `remember` so a send
 * survives the window being switched away from and back — a coordinator
 * addressing forty people and losing it to a tab change is the single worst
 * thing this tool could do.
 */
data class ComposerState(
    val open: Boolean = false,
    val subject: String = "",
    val bodyHtml: String = "",
    val to: List<Recipient> = emptyList(),
    val cc: List<Recipient> = emptyList(),
    val bcc: List<Recipient> = emptyList(),
    /** Library documents chosen for this send. */
    val attachments: List<LibraryDocument> = emptyList(),
    /**
     * Which attachments are stamped.
     *
     * Every watermark-capable attachment starts selected — ops issue almost
     * everything watermarked, so the sender opts *out* per file rather than in.
     * That default is ZL-19547 and changing it changes what leaves a production.
     */
    val watermarked: Set<String> = emptySet(),
    val watermark: WatermarkStyle = WatermarkStyle(),
    val listId: String? = null,
    /** Free text in the recipient field, not yet turned into a chip. */
    val recipientDraft: String = "",
    val sending: Boolean = false,
) {
    /** Ids that will actually carry a stamp — capability and choice, both. */
    val effectiveWatermarks: Map<String, WatermarkStyle>
        get() = attachments
            .filter { it.isWatermarkable && it.id in watermarked }
            .associate { it.id to watermark }
}

/** A confirmation the user has to answer before something destructive happens. */
data class DocDistPrompt(
    val title: String,
    val message: String,
    val confirmLabel: String,
    val event: DocDistEvent,
)

/**
 * Everything the Document Distribution window renders.
 *
 * One state for five pages rather than five: the pages share the folder tree,
 * the selection and the composer, and splitting them would mean keeping three
 * copies of the current folder in step.
 */
data class DocDistUiState(
    val viewer: DocDistViewer = DocDistViewer(),
    val destination: DocDistDestination = DocDistDestination.Library,
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val error: String? = null,
    val notice: String? = null,

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
    val selectedDocumentIds: Set<String> = emptySet(),
    val selectedFolderIds: Set<String> = emptySet(),
    /** Non-null while the "Move items" dialog is open. */
    val moveTarget: MoveTargetState? = null,
    /** Non-null while the Publish dialog is open. */
    val publish: PublishState? = null,

    // -- other pages ------------------------------------------------------
    val history: List<Distribution> = emptyList(),
    val historySearch: String = "",
    /** The "Sent by" menu: everyone the history could have been sent by, the ids ticked, and the menu's own search. */
    val historySenders: List<DistributionSender> = emptyList(),
    val historySenderIds: Set<String> = emptySet(),
    val historySenderQuery: String = "",
    val historySenderMenuOpen: Boolean = false,
    val expandedDistributionId: String? = null,
    val lists: List<DistributionList> = emptyList(),
    val contacts: List<Contact> = emptyList(),
    val templates: List<EmailTemplate> = emptyList(),

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

    /**
     * The folders a move may land in, deepest-first paths flattened for a list.
     *
     * A folder being moved is excluded along with everything beneath it: the
     * service will happily reparent a folder into its own subtree, which
     * detaches that whole branch from the root and leaves its documents
     * reachable by nothing. The web offers every folder and lets it happen.
     */
    fun moveDestinations(): List<MoveDestination> {
        val byParent = folders.groupBy { it.parentId }
        val barred = mutableSetOf<String>()
        fun bar(id: String) {
            if (!barred.add(id)) return
            byParent[id].orEmpty().forEach { bar(it.id) }
        }
        selectedFolderIds.forEach(::bar)

        val out = mutableListOf<MoveDestination>()
        fun walk(parentId: String?, depth: Int) {
            if (depth > MAX_DEPTH) return
            byParent[parentId].orEmpty()
                .sortedBy { it.name.lowercase() }
                .forEach { folder ->
                    if (folder.id in barred) return@forEach
                    out += MoveDestination(folder.id, folder.name, depth)
                    walk(folder.id, depth + 1)
                }
        }
        walk(null, 0)
        return out
    }

    /**
     * Whether the Library root is off-limits as a move destination.
     *
     * A file must live inside a folder — both phones enforce it and say so
     * (web: "Files must be moved into a folder, not the root"; Android dims
     * the Root row and explains it up front so nobody thinks they have found
     * a bug). Folders alone may go to the root, which is how a top-level
     * folder is made.
     */
    val rootForbidden: Boolean get() = selectedDocumentIds.isNotEmpty()

    val hasMore: Boolean get() = documents.size < totalDocuments

    /** Whether the "view only" banner belongs on screen. */
    val showRestrictionBanner: Boolean get() = viewer.isRestricted

    private companion object {
        /** Deeper than any real library, shallow enough to stop a cycle dead. */
        const val MAX_DEPTH = 64
    }
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
