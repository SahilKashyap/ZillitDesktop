package com.zillit.desktop.feature.drive.domain

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/** A file or folder named by id and kind — what every bulk route takes. */
data class DriveRef(val id: String, val kind: DriveItemKind)

/** What the upload drawer asks the server to open a session for. */
data class UploadRequest(
    val fileName: String,
    val sizeBytes: Long,
    val mimeType: String,
    val folderId: String?,
    val description: String = "",
    /** Per-user file grants chosen before the upload (`file_access`). */
    val fileAccess: List<DriveAccessEntry> = emptyList(),
)

/**
 * Everything the Drive asks the server for.
 *
 * Mirrors the web's `api/driveApi` clients route for route. **The paths in
 * those files, not the ones in `docs/Drive_Requirements.md`** — the two
 * disagree in five places (versions, file access, bulk, trash restore,
 * favourites) and the client is what actually runs. Each is noted at the
 * method it affects.
 *
 * One interface rather than eight, because they are one service with one
 * authorisation model, and splitting them means eight fakes in every test.
 * Methods added after the first port carry defaults so an older fake still
 * compiles; a real repository overrides every one.
 */
@Suppress("TooManyFunctions") // One suspend fun per server operation; see detekt.yml.
interface DriveRepository {

    /**
     * A pulse per change another client announced — file, folder, bulk or
     * share (`DriveManagement.jsx` socket handlers). The listener reloads the
     * open scope so a row the user can no longer see, or one just shared
     * with them, lands without a manual refresh. Defaulted empty for tests
     * and hosts without a socket.
     */
    val refreshes: Flow<Unit> get() = emptyFlow()

    // -- browsing ----------------------------------------------------------

    /**
     * Every file and folder of one scope — `GET /drive/files` and
     * `GET /drive/folders` with the same `quick_filter`, as the web's
     * `fetchDriveData` issues them together. Narrowing to a folder is the
     * caller's; see [DriveListQuery].
     */
    suspend fun listing(query: DriveListQuery): ZillitResult<DriveListing>

    suspend fun item(id: String, kind: DriveItemKind): ZillitResult<DriveItem>

    // -- mutations ---------------------------------------------------------

    suspend fun createFolder(folder: NewFolder): ZillitResult<DriveItem>

    /**
     * Renames and re-describes. [description] null leaves the description
     * alone; the endpoint clears a field it is sent as an empty string.
     */
    suspend fun rename(ref: DriveRef, name: String, description: String?): ZillitResult<Unit>

    suspend fun move(ref: DriveRef, targetFolderId: String?): ZillitResult<Unit>

    /** Soft delete — the item goes to the trash with a `deleted_on` stamp. */
    suspend fun delete(ref: DriveRef): ZillitResult<Unit>

    /**
     * Bulk soft delete, capped at 100 by the server.
     *
     * Every item is permission-checked individually server-side, so this can
     * partially succeed; callers should refetch rather than assume.
     */
    suspend fun bulkDelete(refs: List<DriveRef>): ZillitResult<Unit>

    suspend fun bulkMove(refs: List<DriveRef>, targetFolderId: String?): ZillitResult<Unit>

    /** Presigned URLs for a selection, for a multi-file download. */
    suspend fun bulkDownloadUrls(fileIds: List<String>): ZillitResult<List<String>>

    // -- reading a file ----------------------------------------------------

    /** A presigned URL that expires in an hour. Requires download rights. */
    suspend fun downloadUrl(fileId: String): ZillitResult<String>

    /** A presigned URL for viewing only — needs view rights, not download. */
    suspend fun previewUrl(fileId: String): ZillitResult<String>

    /** A presigned URL suitable for seeking, for video and audio. */
    suspend fun streamUrl(fileId: String): ZillitResult<String>

    /** A bare presigned public link. The server fixes the expiry at 24 hours. */
    suspend fun shareLink(fileId: String): ZillitResult<String>

    // -- email share links -------------------------------------------------

    /** The live links on one file (`GET /files/{id}/email-share-links`). */
    suspend fun shareLinks(fileId: String): ZillitResult<List<DriveShareLink>> =
        ZillitResult.Success(emptyList())

    /**
     * Makes a tracked link, emailing it when [DriveShareLinkDraft.recipients]
     * is non-empty (`POST /files/{id}/email-share-link`). **Not**
     * `/share-link` — that older route answers a presigned S3 URL instead and
     * silently swallowed the web's calls until it was renamed.
     */
    suspend fun createShareLink(fileId: String, draft: DriveShareLinkDraft): ZillitResult<DriveShareLink> =
        ZillitResult.Failure(ZillitError.Unknown("share links are not wired"))

    suspend fun revokeShareLink(linkId: String): ZillitResult<Unit> =
        ZillitResult.Failure(ZillitError.Unknown("share links are not wired"))

    // -- file requests -----------------------------------------------------

    /** The open file requests on one folder (`GET /v2/drive/folders/{id}/file-requests`). */
    suspend fun fileRequests(folderId: String): ZillitResult<List<DriveFileRequest>> =
        ZillitResult.Success(emptyList())

    /** Opens a new one (`POST /v2/drive/file-requests`), answering its link. */
    suspend fun createFileRequest(draft: DriveFileRequestDraft): ZillitResult<DriveFileRequest> =
        ZillitResult.Failure(ZillitError.Unknown("file requests are not wired"))

    /** Closes one for good (`POST /v2/drive/file-requests/{id}/revoke`). */
    suspend fun revokeFileRequest(requestId: String): ZillitResult<Unit> =
        ZillitResult.Failure(ZillitError.Unknown("file requests are not wired"))

    /**
     * The document editor's configuration for [fileId].
     *
     * Returns the URL to load. The editor itself is an embedded browser
     * surface, which this module does not own — see `DriveEffect.OpenEditor`.
     */
    suspend fun editorUrl(fileId: String, editable: Boolean): ZillitResult<String>

    // -- uploads -----------------------------------------------------------

    /**
     * Opens a multipart upload and returns its presigned parts.
     *
     * The server decides the chunk size; [UploadPlan] predicts it so the client
     * can slice the file the same way. A mismatch is rejected by S3 as a
     * signature failure, not a size failure — see [UploadPlan].
     */
    suspend fun initiateUpload(request: UploadRequest): ZillitResult<UploadSession>

    /** Assembles the object and creates the file record. Idempotent. */
    suspend fun completeUpload(
        uploadId: String,
        parts: List<UploadPart>,
        fileName: String = "",
        description: String = "",
    ): ZillitResult<DriveItem>

    suspend fun abortUpload(uploadId: String): ZillitResult<Unit>

    /** Presigned URLs for the parts not yet uploaded, so a session can resume. */
    suspend fun remainingParts(uploadId: String): ZillitResult<List<UploadPart>>

    // -- trash -------------------------------------------------------------

    suspend fun trash(): ZillitResult<List<DriveItem>>

    /**
     * Puts one item back where it was.
     *
     * The route is `POST /drive/trash/{type}/{id}/restore` — **type in the
     * path**, unlike the requirements document's `/trash/{id}/restore`. Sending
     * the shorter form 404s.
     */
    suspend fun restore(ref: DriveRef): ZillitResult<Unit>

    suspend fun purge(ref: DriveRef): ZillitResult<Unit>

    suspend fun emptyTrash(): ZillitResult<Unit>

    // -- favourites --------------------------------------------------------

    /** One route for both directions — the server flips whatever it finds. */
    suspend fun toggleFavourite(ref: DriveRef): ZillitResult<Unit>

    /**
     * Just the ids, for drawing the star on a listing.
     *
     * A separate lightweight route (NFR-01.8): the full favourites list is a
     * page of items, and fetching it to decide which of fifty rows show a
     * filled star is a page of payload for fifty booleans.
     */
    suspend fun favouriteIds(): ZillitResult<Set<String>>

    // -- sharing -----------------------------------------------------------

    suspend fun access(ref: DriveRef): ZillitResult<List<DriveAccessEntry>>

    /**
     * Replaces the access list for one item.
     *
     * Replaces rather than patches, matching the endpoint: an entry omitted
     * from [entries] has its access revoked. Callers must send the full list.
     *
     * [applyToChildren] only means anything for a folder — the server inherits
     * the roles down every descendant (FR-05.8), which on a large tree is not
     * instant.
     */
    suspend fun updateAccess(
        ref: DriveRef,
        entries: List<DriveAccessEntry>,
        applyToChildren: Boolean = false,
    ): ZillitResult<Unit>

    /**
     * Who may be given access at all — the ids with view rights on the drive
     * tool (`access/users?toolIdentifier=drive_tool&viewing_access=true`,
     * ZL-18292). Null means "could not tell"; the caller then offers the
     * whole crew rather than nobody.
     */
    suspend fun viewAccessUserIds(): ZillitResult<Set<String>?> = ZillitResult.Success(null)

    // -- metadata ----------------------------------------------------------

    /** The trail for one item — the details panel's timeline. */
    suspend fun activity(itemId: String?): ZillitResult<List<DriveActivity>>

    /** One page of the whole drive's trail, newest first — the Activity Log drawer. */
    suspend fun activityPage(limit: Int, offset: Int): ZillitResult<DriveActivityPage> =
        activity(null).let { result ->
            when (result) {
                is ZillitResult.Success -> ZillitResult.Success(DriveActivityPage(result.data, result.data.size))
                is ZillitResult.Failure -> ZillitResult.Failure(result.error)
            }
        }

    suspend fun comments(fileId: String): ZillitResult<List<DriveComment>>

    suspend fun addComment(
        fileId: String,
        text: String,
        parentId: String? = null,
    ): ZillitResult<Unit>

    suspend fun updateComment(commentId: String, text: String): ZillitResult<Unit> =
        ZillitResult.Failure(ZillitError.Unknown("comment edits are not wired"))

    suspend fun deleteComment(commentId: String): ZillitResult<Unit>

    suspend fun tags(): ZillitResult<List<DriveTag>>

    /** Creates a tag; the answer carries its id when the service sends one. */
    suspend fun createTag(name: String, color: String): ZillitResult<DriveTag?>

    suspend fun deleteTag(tagId: String): ZillitResult<Unit>

    /** The tags already on one item — the project list says nothing about which are applied. */
    suspend fun itemTags(ref: DriveRef): ZillitResult<List<DriveTag>>

    /** Every item carrying [tagId], for the header's tag filter (`items-by-tag`). */
    suspend fun itemsByTag(tagId: String): ZillitResult<Set<String>> = ZillitResult.Success(emptySet())

    suspend fun assignTag(tagId: String, ref: DriveRef): ZillitResult<Unit>

    suspend fun removeTag(tagId: String, ref: DriveRef): ZillitResult<Unit>

    // -- versions ----------------------------------------------------------

    /** Newest first. Every save through the editor adds one (FR-08.1). */
    suspend fun versions(fileId: String): ZillitResult<List<DriveVersion>>

    suspend fun versionDownloadUrl(fileId: String, versionId: String): ZillitResult<String>

    /**
     * Puts an old version's content back on the live key.
     *
     * Not destructive: the server snapshots the current state first, so a
     * restore can itself be undone by restoring the snapshot it made (FR-08.7).
     */
    suspend fun restoreVersion(fileId: String, versionId: String): ZillitResult<Unit>
}
