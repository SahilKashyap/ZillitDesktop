package com.zillit.desktop.feature.documentdistribution.ui

import com.zillit.desktop.feature.documentdistribution.domain.Contact
import com.zillit.desktop.feature.documentdistribution.domain.DateGroup
import com.zillit.desktop.feature.documentdistribution.domain.Distribution
import com.zillit.desktop.feature.documentdistribution.domain.DistributionList
import com.zillit.desktop.feature.documentdistribution.domain.DocDistViewer
import com.zillit.desktop.feature.documentdistribution.domain.EmailTemplate
import com.zillit.desktop.feature.documentdistribution.domain.LibraryDocument
import com.zillit.desktop.feature.documentdistribution.domain.LibraryFolder
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

    // -- other pages ------------------------------------------------------
    val history: List<Distribution> = emptyList(),
    val historySearch: String = "",
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

    val hasMore: Boolean get() = documents.size < totalDocuments

    /** Whether the "view only" banner belongs on screen. */
    val showRestrictionBanner: Boolean get() = viewer.isRestricted

    private companion object {
        /** Deeper than any real library, shallow enough to stop a cycle dead. */
        const val MAX_DEPTH = 64
    }
}
