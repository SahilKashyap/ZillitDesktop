package com.zillit.desktop.feature.drive.ui

import com.zillit.desktop.feature.drive.domain.ActivityCategory
import com.zillit.desktop.feature.drive.domain.DriveFileRequest
import com.zillit.desktop.feature.drive.domain.DriveInnerTab
import com.zillit.desktop.feature.drive.domain.DriveItem
import com.zillit.desktop.feature.drive.domain.DriveRef
import com.zillit.desktop.feature.drive.domain.DriveRole
import com.zillit.desktop.feature.drive.domain.DriveSection
import com.zillit.desktop.feature.drive.domain.DriveShareLink
import com.zillit.desktop.feature.drive.domain.DriveSortColumn
import com.zillit.desktop.feature.drive.domain.FileAccessLevel
import com.zillit.desktop.feature.drive.domain.LinkPermission
import com.zillit.desktop.feature.drive.domain.MyDriveFilter

/** Everything the user can do in the Drive. */
sealed interface DriveEvent {

    data object Refresh : DriveEvent
    data object ClearNotice : DriveEvent
    data object DismissPrompt : DriveEvent
    data object ConfirmPrompt : DriveEvent

    // -- navigation -------------------------------------------------------

    data class OpenSection(val section: DriveSection) : DriveEvent
    data class FilterMyDrive(val filter: MyDriveFilter) : DriveEvent
    data class OpenInnerTab(val tab: DriveInnerTab) : DriveEvent
    data class SetViewMode(val mode: DriveViewMode) : DriveEvent
    data class ShowTrash(val show: Boolean) : DriveEvent

    /** Null navigates to the section's root. */
    data class OpenFolder(val folderId: String?) : DriveEvent
    data object GoBack : DriveEvent
    data class OpenItem(val item: DriveItem) : DriveEvent

    data class SearchInput(val text: String) : DriveEvent
    /** The debounce elapsed: what is in the box becomes the query. */
    data object CommitSearch : DriveEvent
    data object ClearSearch : DriveEvent
    data class SortBy(val column: DriveSortColumn) : DriveEvent
    data class FilterByTag(val tagId: String?) : DriveEvent
    data class ShowFavouritesOnly(val on: Boolean) : DriveEvent

    data class ToggleSelection(val itemId: String) : DriveEvent
    data class SetSelected(val itemId: String, val selected: Boolean) : DriveEvent
    data class SelectAll(val selected: Boolean) : DriveEvent
    data object ClearSelection : DriveEvent

    /** The pointer-anchored menu, from a right-click or the ⋮ button. */
    data class OpenMenu(val item: DriveItem, val x: Float, val y: Float) : DriveEvent
    data object CloseMenu : DriveEvent

    /** A file's small preview for the grid and the name cell — fetched once, kept. */
    data class WantThumbnail(val item: DriveItem) : DriveEvent

    // -- acting on items --------------------------------------------------

    data class MoveTo(val refs: List<DriveRef>, val targetFolderId: String?) : DriveEvent
    /** Raises the confirmation; the delete itself happens on [ConfirmPrompt]. */
    data class RequestDelete(val refs: List<DriveRef>) : DriveEvent
    data class Delete(val refs: List<DriveRef>) : DriveEvent
    data class ToggleFavourite(val ref: DriveRef) : DriveEvent
    data class Download(val item: DriveItem) : DriveEvent
    data object DownloadSelection : DriveEvent
    /** The bare 24-hour link, straight to the clipboard ("Copy link"). */
    data class CopyLink(val item: DriveItem) : DriveEvent
    data class OpenInEditor(val item: DriveItem, val editable: Boolean) : DriveEvent
    data class Preview(val item: DriveItem) : DriveEvent
    data object ClosePreview : DriveEvent
    /** A preview this dialog cannot draw — hand its address to the browser instead. */
    data object OpenPreviewInBrowser : DriveEvent

    // -- upload drawer ----------------------------------------------------

    data object OpenUpload : DriveEvent
    data object CloseUpload : DriveEvent
    /** Asks the host for files; they arrive as [AddUploadFiles]. */
    data object PickUploadFiles : DriveEvent
    /** Asks the host for a folder; its files arrive with relative paths. */
    data object PickUploadFolder : DriveEvent
    /** The widget's attach sheet — the picker filtered to one kind, straight to the queue. */
    data class PickFilesOf(val kind: com.zillit.desktop.core.media.PreviewKind) : DriveEvent
    data class AddUploadFiles(val files: List<PickedFile>) : DriveEvent
    data class RemoveUploadFile(val path: String) : DriveEvent
    data object ClearUploadFiles : DriveEvent
    data object ClearUnsupported : DriveEvent
    data class UploadDestination(val pickExisting: Boolean, val folderId: String?) : DriveEvent
    data class UploadDescription(val text: String) : DriveEvent
    data object ToggleUploadDetails : DriveEvent
    data object ToggleUploadAccess : DriveEvent
    data class UploadAccess(val access: AccessDraft) : DriveEvent
    data object SubmitUpload : DriveEvent

    /** Files dropped onto the page from the OS — the permissions step comes first. */
    data class DropFiles(val files: List<PickedFile>) : DriveEvent
    data class DropAccess(val access: AccessDraft) : DriveEvent
    data class ConfirmDrop(val withAccess: Boolean) : DriveEvent
    data object CancelDrop : DriveEvent

    data class CancelUpload(val uploadId: String) : DriveEvent
    data class RemoveUpload(val uploadId: String) : DriveEvent
    data object ClearFinishedUploads : DriveEvent
    data object ToggleOperations : DriveEvent

    // -- create folder ----------------------------------------------------

    data object OpenNewFolder : DriveEvent
    data object CloseNewFolder : DriveEvent
    data class NewFolderName(val text: String) : DriveEvent
    data class NewFolderDescription(val text: String) : DriveEvent
    data class NewFolderDestination(val pickExisting: Boolean, val folderId: String?) : DriveEvent
    data class NewFolderAccess(val access: AccessDraft) : DriveEvent
    data object SubmitNewFolder : DriveEvent

    // -- edit info --------------------------------------------------------

    data class OpenEdit(val item: DriveItem) : DriveEvent
    data object CloseEdit : DriveEvent
    data class EditName(val text: String) : DriveEvent
    data class EditDescription(val text: String) : DriveEvent
    data object SubmitEdit : DriveEvent

    // -- share ------------------------------------------------------------

    data class OpenShare(val item: DriveItem) : DriveEvent
    data object CloseShare : DriveEvent
    data class ShareTabTo(val tab: ShareTab) : DriveEvent
    data class ShareAccess(val access: AccessDraft) : DriveEvent
    data object SubmitShare : DriveEvent
    data class ShareLinkRecipients(val text: String) : DriveEvent
    data class ShareLinkPermission(val permission: LinkPermission) : DriveEvent
    data class ShareLinkExpiry(val millis: Long) : DriveEvent
    data class ShareLinkMaxViews(val views: Int) : DriveEvent
    data class ShareLinkMessage(val text: String) : DriveEvent
    data object GenerateShareLink : DriveEvent
    data class CopyShareLink(val link: DriveShareLink) : DriveEvent
    data class RevokeShareLink(val link: DriveShareLink) : DriveEvent

    // -- move to ----------------------------------------------------------

    data class OpenMoveTo(val items: List<DriveItem>) : DriveEvent
    data object OpenMoveSelection : DriveEvent
    data object CloseMoveTo : DriveEvent
    data class PickMoveTarget(val folderId: String?) : DriveEvent
    data object ConfirmMove : DriveEvent

    // -- file requests ---------------------------------------------------

    /** Opens the panel for a folder — the folder is where the files will land. */
    data class OpenFileRequests(val folder: DriveItem) : DriveEvent
    data object OpenFileRequestsHere : DriveEvent
    data object CloseFileRequests : DriveEvent
    data class FileRequestTitle(val text: String) : DriveEvent
    data class FileRequestDescription(val text: String) : DriveEvent
    data class FileRequestExpiry(val days: Int) : DriveEvent
    data class FileRequestRequireName(val on: Boolean) : DriveEvent
    data class FileRequestRequireEmail(val on: Boolean) : DriveEvent
    data object SubmitFileRequest : DriveEvent
    data class CopyFileRequest(val request: DriveFileRequest) : DriveEvent
    data class RevokeFileRequest(val request: DriveFileRequest) : DriveEvent

    // -- activity log -----------------------------------------------------

    data object OpenActivityLog : DriveEvent
    data object CloseActivityLog : DriveEvent
    data class FilterActivity(val category: ActivityCategory?) : DriveEvent
    data object LoadMoreActivity : DriveEvent
    data object RefreshActivity : DriveEvent

    // -- details panel ----------------------------------------------------

    data class ShowDetails(val item: DriveItem?) : DriveEvent
    data class CommentDraft(val text: String) : DriveEvent
    data object PostComment : DriveEvent
    data class StartEditComment(val commentId: String, val text: String) : DriveEvent
    data class EditCommentText(val text: String) : DriveEvent
    data object SaveComment : DriveEvent
    data object CancelEditComment : DriveEvent
    data class DeleteComment(val commentId: String) : DriveEvent
    data class RequestRestoreVersion(val fileId: String, val versionId: String) : DriveEvent
    data class RestoreVersion(val fileId: String, val versionId: String) : DriveEvent
    data class DownloadVersion(val item: DriveItem, val versionId: String) : DriveEvent
    data class TagDraft(val text: String) : DriveEvent
    data class AssignTag(val tagId: String) : DriveEvent
    data class RemoveTag(val tagId: String) : DriveEvent
    data object CreateAndAssignTag : DriveEvent

    // -- trash ------------------------------------------------------------

    data class Restore(val ref: DriveRef) : DriveEvent
    data class RequestPurge(val item: DriveItem) : DriveEvent
    data class Purge(val ref: DriveRef) : DriveEvent
    data object RequestEmptyTrash : DriveEvent
    data object EmptyTrash : DriveEvent
}

/**
 * A file the host picked, before it has been read.
 *
 * Carries a [path] rather than bytes: a 10 GB upload cannot be held in memory,
 * and the uploader streams it a chunk at a time. Common code has no file API,
 * so the reading happens on the host side.
 *
 * [relativePath] is set for files that came from a folder pick or drop —
 * `photos/day-1/a.jpg` — and is what lets the upload recreate the folder
 * structure server-side (`ensureFolderTreeForUpload`). Blank for a loose file.
 */
data class PickedFile(
    val path: String,
    val name: String,
    val sizeBytes: Long,
    val mimeType: String,
    val relativePath: String = "",
) {
    val extension: String get() = name.substringAfterLast('.', "").lowercase()

    /** `photos/day-1` for `photos/day-1/a.jpg`; blank for a loose file. */
    val relativeDirectory: String
        get() = relativePath.trim('/').substringBeforeLast('/', "")
}

/** One-shot things the window does rather than renders. */
sealed interface DriveEffect {
    data class Failed(val message: String) : DriveEffect

    /** Hand a URL to the OS — a download, a share link. */
    data class OpenUrl(val url: String) : DriveEffect

    /** Open the document editor for a file, in an embedded browser surface. */
    data class OpenEditor(val url: String, val fileName: String) : DriveEffect

    /**
     * Play a video or audio file at a presigned address, in the app's own
     * browser surface. Separate from [OpenUrl] so a host with an embedded
     * browser keeps the presigned link inside the app.
     */
    data class OpenMedia(val url: String, val title: String) : DriveEffect

    /** Put text on the clipboard. */
    data class CopyToClipboard(val text: String, val label: String) : DriveEffect

    /** Ask the host to show a file picker. */
    data object PickFiles : DriveEffect

    /** Ask the host to show a folder picker; every file beneath comes back with a relative path. */
    data object PickFolder : DriveEffect

    /** As [PickFiles], filtered to one kind from the attach sheet. */
    data class PickFilesOf(val kind: com.zillit.desktop.core.media.PreviewKind) : DriveEffect
}
