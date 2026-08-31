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
)

/**
 * Everything the Drive asks the server for.
 *
 * Mirrors the web's `api/driveApi/driveApi.js` route for route. **The paths in
 * that file, not the ones in `docs/Drive_Requirements.md`** — the two disagree
 * in five places (versions, file access, bulk, trash restore, favourites) and
 * the client is what actually runs. Each is noted at the method it affects.
 *
 * One interface rather than eight, because they are one service with one
 * authorisation model, and splitting them means eight fakes in every test.
 */
@Suppress("TooManyFunctions") // One suspend fun per server operation; see detekt.yml.
interface DriveRepository {

    /**
     * A pulse per delete another client announced — file, folder or bulk
     * (`DriveManagement.jsx:1591-1596`, which refetches the current view;
     * deletes run even before its own-events guard, ZL-18490). The listener
     * reloads the open destination so a row the user can no longer see, or
     * a corrected trash count, lands without a manual refresh. Defaulted
     * empty for tests and hosts without a socket.
     */
    val refreshes: Flow<Unit> get() = emptyFlow()

    // -- browsing ----------------------------------------------------------

    /**
     * Files and folders of one location, merged and paged.
     *
     * `GET /drive/folders/contents` rather than the two separate list routes:
     * one call returns both, already ordered against the same sort, which is
     * the only way a "name A–Z" listing can interleave correctly. The separate
     * `/files` and `/folders` routes exist and are what the web mostly uses,
     * at the cost of sorting two lists against each other on the client.
     */
    suspend fun contents(query: DriveQuery): ZillitResult<DrivePage>

    suspend fun item(id: String, kind: DriveItemKind): ZillitResult<DriveItem>


    // -- mutations ---------------------------------------------------------

    suspend fun createFolder(name: String, parentId: String?, description: String = ""):
        ZillitResult<DriveItem>

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

    /** A public link. The server fixes the expiry at 24 hours. */
    suspend fun shareLink(fileId: String): ZillitResult<String>

    /**
     * The open file requests on one folder
     * (`GET /v2/drive/folders/{id}/file-requests`).
     */
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

    suspend fun favourites(): ZillitResult<List<DriveItem>>

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

    // -- metadata ----------------------------------------------------------

    suspend fun storage(): ZillitResult<StorageUsage>

    suspend fun activity(itemId: String?): ZillitResult<List<DriveActivity>>

    suspend fun comments(fileId: String): ZillitResult<List<DriveComment>>

    suspend fun addComment(
        fileId: String,
        text: String,
        parentId: String? = null,
    ): ZillitResult<Unit>

    suspend fun deleteComment(commentId: String): ZillitResult<Unit>

    suspend fun tags(): ZillitResult<List<DriveTag>>

    suspend fun createTag(name: String, color: String): ZillitResult<Unit>

    suspend fun deleteTag(tagId: String): ZillitResult<Unit>

    /** The tags already on one item — the project list says nothing about which are applied. */
    suspend fun itemTags(ref: DriveRef): ZillitResult<List<DriveTag>>

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
