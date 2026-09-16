package com.zillit.desktop.feature.pagedistribution.ui

import com.zillit.desktop.feature.pagedistribution.domain.CountRow
import com.zillit.desktop.feature.pagedistribution.domain.DistDocument
import com.zillit.desktop.feature.pagedistribution.domain.DistFolder
import com.zillit.desktop.feature.pagedistribution.domain.DistributionTab
import com.zillit.desktop.feature.pagedistribution.domain.DistributionTool
import com.zillit.desktop.feature.pagedistribution.domain.DistributionViewer
import com.zillit.desktop.feature.pagedistribution.domain.ListMode
import com.zillit.desktop.feature.pagedistribution.domain.PageColour
import com.zillit.desktop.feature.pagedistribution.domain.PdfPageImage
import com.zillit.desktop.feature.pagedistribution.domain.ScheduleType
import com.zillit.desktop.feature.pagedistribution.domain.TabKind
import com.zillit.desktop.feature.pagedistribution.domain.UploadDraft

/**
 * The upload dialog — the picked PDF plus what the web's DocumentModal asks.
 *
 * Plain data-class equality on purpose: the state flow conflates on
 * `equals`, and an override that compared the file name alone made every
 * keystroke in the form an "equal" state the flow dropped — the dialog
 * could not be typed into. `bytes` compares by identity, which is cheap and
 * right: the same picked file is the same array.
 */
data class UploadEditor(
    val fileName: String,
    val bytes: ByteArray,
    /** The document being replaced on a single-list tab; null creates. */
    val replaces: String? = null,
    val dateYmd: String = "",
    val episode: String = "",
    val sceneNumber: String = "",
    val pageNumber: String = "",
    val colour: PageColour = PageColour.White,
    val scheduleType: ScheduleType? = null,
    val name: String = "",
    val nameFromPick: Boolean = false,
    val saving: Boolean = false,
) {
    fun toDraft() = UploadDraft(
        fileName = fileName,
        bytes = bytes,
        dateYmd = dateYmd,
        episode = episode,
        sceneNumber = sceneNumber,
        pageNumber = pageNumber,
        colour = colour,
        scheduleType = scheduleType,
        name = name,
        nameFromPick = nameFromPick,
        replaces = replaces,
    )
}

/** A folder opened over the grid: its documents, paged by `created`. */
data class OpenFolder(
    val folder: DistFolder,
    val documents: List<DistDocument> = emptyList(),
    val loadingMore: Boolean = false,
    val exhausted: Boolean = false,
)

data class PdfView(
    val document: DistDocument,
    val pages: List<PdfPageImage> = emptyList(),
    val loading: Boolean = true,
)

data class CountsView(
    val document: DistDocument,
    val rows: List<CountRow> = emptyList(),
    /** Download tallies, else view tallies. */
    val downloads: Boolean,
    val loading: Boolean = true,
)

data class MoveEditor(val document: DistDocument, val target: DistFolder? = null, val saving: Boolean = false)

data class DistributionUiState(
    val tool: DistributionTool,
    val viewer: DistributionViewer = DistributionViewer(),
    val mode: ListMode = ListMode.Live,
    val activeTabKey: String = tool.tabs.first().key,
    val loading: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
    /** A single-list tab's documents. */
    val documents: List<DistDocument> = emptyList(),
    /** A folder tab's headers. */
    val folders: List<DistFolder> = emptyList(),
    val openFolder: OpenFolder? = null,
    val searchScene: String = "",
    val searchEpisode: String = "",
    val searchColour: PageColour? = null,
    /** Search results, grouped by the folder key on show; null when not searching. */
    val searchResults: List<DistDocument>? = null,
    val upload: UploadEditor? = null,
    val pdf: PdfView? = null,
    val counts: CountsView? = null,
    val move: MoveEditor? = null,
    val confirmDelete: DistDocument? = null,
    val confirmPublish: DistDocument? = null,
    /**
     * Unread per folder, keyed by the folder key — the web's
     * `toolsUnitBadges[dod_label].data[unit == item.name].unread` on every
     * D.O.D folder card. Empty for the tools whose folders carry no badge.
     */
    val folderUnread: Map<String, Int> = emptyMap(),
    /**
     * Unread per tab, keyed by the tab key — the web's `toolsUnitBadges`
     * chips on the tab strip (`schedule_distribution_label`,
     * `schedule_distribution_pages_tool_label`, `schedule_oneline_label`).
     */
    val tabUnread: Map<String, Int> = emptyMap(),
) {
    val activeTab: DistributionTab get() = tool.tabs.firstOrNull { it.key == activeTabKey } ?: tool.tabs.first()
    val isFolderTab: Boolean get() = activeTab.kind is TabKind.Folders
    val isDod: Boolean get() = tool.toolIdentifier == DistributionTool.ScheduleDod.toolIdentifier
    val isSchedule: Boolean get() = tool.toolIdentifier == DistributionTool.ScheduleDistribution.toolIdentifier
    val isScript: Boolean get() = tool.toolIdentifier == DistributionTool.ScriptDistribution.toolIdentifier

    /** Single-list documents, oldest first — the web's `a.created - b.created`. */
    val sortedDocuments: List<DistDocument> get() = documents.sortedBy { it.createdMs }

    /** The folder names the D.O.D upload dialog offers. */
    val folderNames: List<String> get() = folders.map { it.key }.filter { it.isNotBlank() }.distinct()

    val isSearching: Boolean get() = searchScene.isNotBlank() || searchEpisode.isNotBlank() || searchColour != null
}

sealed interface DistributionEvent {
    data class SelectTab(val key: String) : DistributionEvent
    data object ToggleHistory : DistributionEvent
    data object Refresh : DistributionEvent

    data class OpenFolder(val key: String) : DistributionEvent
    data object CloseFolder : DistributionEvent
    data object LoadMore : DistributionEvent

    data class SearchChanged(val scene: String? = null, val episode: String? = null) : DistributionEvent
    data class SearchColour(val colour: PageColour?) : DistributionEvent
    data object RunSearch : DistributionEvent
    data object ClearSearch : DistributionEvent

    /** Opens the OS picker; [replaces] carries the document a single-list upload replaces. */
    data class PickPdf(val replaces: DistDocument? = null) : DistributionEvent
    data class PdfPicked(val fileName: String, val bytes: ByteArray, val replaces: DistDocument?) : DistributionEvent

    /**
     * Files dropped onto the page from the OS — the web's body-level `drop`
     * listener (`DoD.jsx:242-289`): the first file opens the upload dialog
     * when it is a PDF, anything else is refused with the web's message.
     */
    data class FilesDropped(val files: List<Pair<String, ByteArray>>) : DistributionEvent
    data class UploadChanged(
        val dateYmd: String? = null,
        val episode: String? = null,
        val sceneNumber: String? = null,
        val pageNumber: String? = null,
        val colour: PageColour? = null,
        val scheduleType: ScheduleType? = null,
        val name: String? = null,
        val nameFromPick: Boolean? = null,
    ) : DistributionEvent
    data object SubmitUpload : DistributionEvent
    data object CancelUpload : DistributionEvent

    data class View(val document: DistDocument) : DistributionEvent
    data object CloseViewer : DistributionEvent
    data class Download(val document: DistDocument) : DistributionEvent
    data class Delete(val document: DistDocument) : DistributionEvent
    data object ConfirmDelete : DistributionEvent
    data object CancelDelete : DistributionEvent
    data class ShowCounts(val document: DistDocument, val downloads: Boolean) : DistributionEvent
    data object CloseCounts : DistributionEvent
    data class Move(val document: DistDocument) : DistributionEvent
    data class MoveTarget(val folder: DistFolder) : DistributionEvent
    data object ConfirmMove : DistributionEvent
    data object CancelMove : DistributionEvent
    data class Publish(val document: DistDocument) : DistributionEvent
    data object ConfirmPublish : DistributionEvent
    data object CancelPublish : DistributionEvent
    data object DismissError : DistributionEvent
}

sealed interface DistributionEffect {
    data class Notice(val text: String) : DistributionEffect
    data class PickPdf(val replaces: DistDocument?) : DistributionEffect
}
