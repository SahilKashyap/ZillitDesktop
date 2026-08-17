package com.zillit.desktop.feature.drive.ui

import com.zillit.desktop.feature.drive.domain.DriveAccessEntry
import com.zillit.desktop.feature.drive.domain.DriveGrouping
import com.zillit.desktop.feature.drive.domain.DriveItem
import com.zillit.desktop.feature.drive.domain.DriveQuickFilter
import com.zillit.desktop.feature.drive.domain.DriveRef
import com.zillit.desktop.feature.drive.domain.DriveSort

/** Everything the user can do in the Drive. */
sealed interface DriveEvent {

    data object Refresh : DriveEvent
    data object ClearNotice : DriveEvent
    data object DismissPrompt : DriveEvent
    data object ConfirmPrompt : DriveEvent

    data class Open(val destination: DriveDestination) : DriveEvent
    data object ToggleViewMode : DriveEvent

    // -- browsing ---------------------------------------------------------

    /** Null navigates to the drive root. */
    data class OpenFolder(val folderId: String?) : DriveEvent
    data class OpenItem(val item: DriveItem) : DriveEvent
    data object LoadMore : DriveEvent

    data class Search(val text: String) : DriveEvent
    data class SortBy(val sort: DriveSort) : DriveEvent
    data class GroupBy(val grouping: DriveGrouping) : DriveEvent
    data class Filter(val filter: DriveQuickFilter) : DriveEvent
    data class FilterByTag(val tagId: String?) : DriveEvent

    data class ToggleSelection(val itemId: String) : DriveEvent
    data class SelectAll(val selected: Boolean) : DriveEvent
    data object ClearSelection : DriveEvent

    // -- acting on items --------------------------------------------------

    data class CreateFolder(val name: String) : DriveEvent
    data class Rename(val ref: DriveRef, val name: String, val description: String?) : DriveEvent
    data class MoveTo(val refs: List<DriveRef>, val targetFolderId: String?) : DriveEvent
    /** Raises the confirmation; the delete itself happens on [ConfirmPrompt]. */
    data class RequestDelete(val refs: List<DriveRef>) : DriveEvent
    data class Delete(val refs: List<DriveRef>) : DriveEvent
    data class ToggleFavourite(val ref: DriveRef) : DriveEvent
    data class Download(val item: DriveItem) : DriveEvent
    data object DownloadSelection : DriveEvent
    data class ShareLink(val item: DriveItem) : DriveEvent
    data class OpenInEditor(val item: DriveItem, val editable: Boolean) : DriveEvent

    // -- details panel ----------------------------------------------------

    data class ShowDetails(val item: DriveItem?) : DriveEvent
    data class CommentDraft(val text: String) : DriveEvent
    data object PostComment : DriveEvent
    data class DeleteComment(val commentId: String) : DriveEvent
    data class RestoreVersion(val fileId: String, val versionId: String) : DriveEvent
    data class UpdateAccess(
        val ref: DriveRef,
        val entries: List<DriveAccessEntry>,
        val applyToChildren: Boolean,
    ) : DriveEvent

    // -- uploads ----------------------------------------------------------

    /** Asks the host to show a file picker; the chosen files come back as [Upload]. */
    data object PickFiles : DriveEvent
    data class Upload(val files: List<PickedFile>) : DriveEvent
    data class CancelUpload(val uploadId: String) : DriveEvent
    data object ClearFinishedUploads : DriveEvent

    // -- trash ------------------------------------------------------------

    data class Restore(val ref: DriveRef) : DriveEvent
    data class Purge(val ref: DriveRef) : DriveEvent
    data object RequestEmptyTrash : DriveEvent
    data object EmptyTrash : DriveEvent
}

/**
 * A file the host picked, before it has been read.
 *
 * Carries a [path] rather than bytes: a 10 GB upload cannot be held in memory,
 * and the uploader streams it a chunk at a time. Common code has no file API,
 * so the reading happens on the host side of [DriveEffect.ReadChunk].
 */
data class PickedFile(
    val path: String,
    val name: String,
    val sizeBytes: Long,
    val mimeType: String,
)

/** One-shot things the window does rather than renders. */
sealed interface DriveEffect {
    data class Failed(val message: String) : DriveEffect

    /** Hand a URL to the OS — a download, a preview, a share link. */
    data class OpenUrl(val url: String) : DriveEffect

    /** Open the document editor for a file, in an embedded browser surface. */
    data class OpenEditor(val url: String, val fileName: String) : DriveEffect

    /** Put a share link on the clipboard. */
    data class CopyToClipboard(val text: String, val label: String) : DriveEffect

    /** Ask the host to show a file picker. */
    data object PickFiles : DriveEffect
}
