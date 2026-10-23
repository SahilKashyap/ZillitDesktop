package com.zillit.desktop.feature.drive

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.drive.domain.DriveAccessEntry
import com.zillit.desktop.feature.drive.domain.DriveActivity
import com.zillit.desktop.feature.drive.domain.DriveComment
import com.zillit.desktop.feature.drive.domain.DriveItem
import com.zillit.desktop.feature.drive.domain.DriveItemKind
import com.zillit.desktop.feature.drive.domain.DriveListQuery
import com.zillit.desktop.feature.drive.domain.DriveListing
import com.zillit.desktop.feature.drive.domain.DriveRef
import com.zillit.desktop.feature.drive.domain.DriveRepository
import com.zillit.desktop.feature.drive.domain.DriveTag
import com.zillit.desktop.feature.drive.domain.DriveVersion
import com.zillit.desktop.feature.drive.domain.DriveVersionChanges
import com.zillit.desktop.feature.drive.domain.DriveVersionHistory
import com.zillit.desktop.feature.drive.domain.EditorSession
import com.zillit.desktop.feature.drive.domain.NewFileType
import com.zillit.desktop.feature.drive.domain.NewFolder
import com.zillit.desktop.feature.drive.domain.UploadPart
import com.zillit.desktop.feature.drive.domain.UploadRequest
import com.zillit.desktop.feature.drive.domain.UploadSession
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * A repository that answers everything with nothing, for tests to override
 * the one or two calls they care about. [rows] is what [listing] serves,
 * split into files and folders by kind; every listing call is counted.
 */
@Suppress("TooManyFunctions") // One override per repository operation.
internal open class FakeDriveRepository(
    override val refreshes: Flow<Unit> = MutableSharedFlow(),
) : DriveRepository {
    var browseLoads = 0
    var lastQuery: DriveListQuery? = null
    var rows = emptyList<DriveItem>()

    override suspend fun listing(query: DriveListQuery): ZillitResult<DriveListing> {
        browseLoads++
        lastQuery = query
        return ZillitResult.Success(
            DriveListing(files = rows.filterNot { it.isFolder }, folders = rows.filter { it.isFolder }),
        )
    }

    override suspend fun item(id: String, kind: DriveItemKind): ZillitResult<DriveItem> = unused()
    override suspend fun createFolder(folder: NewFolder): ZillitResult<DriveItem> = unused()
    override suspend fun createNewFile(
        type: NewFileType,
        name: String,
        folderId: String?,
    ): ZillitResult<DriveItem> = unused()
    override suspend fun rename(ref: DriveRef, name: String, description: String?): ZillitResult<Unit> = ok()
    override suspend fun move(ref: DriveRef, targetFolderId: String?): ZillitResult<Unit> = ok()
    override suspend fun delete(ref: DriveRef): ZillitResult<Unit> = ok()
    override suspend fun bulkDelete(refs: List<DriveRef>): ZillitResult<Unit> = ok()
    override suspend fun bulkMove(refs: List<DriveRef>, targetFolderId: String?): ZillitResult<Unit> = ok()
    override suspend fun bulkDownloadUrls(fileIds: List<String>): ZillitResult<List<String>> =
        ZillitResult.Success(emptyList())
    override suspend fun downloadUrl(fileId: String): ZillitResult<String> = ZillitResult.Success("u")
    override suspend fun previewUrl(fileId: String): ZillitResult<String> = ZillitResult.Success("u")
    override suspend fun streamUrl(fileId: String): ZillitResult<String> = ZillitResult.Success("u")
    override suspend fun shareLink(fileId: String): ZillitResult<String> = ZillitResult.Success("u")
    override suspend fun editorSession(fileId: String, viewOnly: Boolean): ZillitResult<EditorSession> =
        ZillitResult.Success(
            EditorSession(
                baseUrl = "https://editor.test/sheet",
                wopiSrc = "https://wopi.test/f/$fileId",
                accessToken = "t",
            ),
        )
    override suspend fun initiateUpload(request: UploadRequest): ZillitResult<UploadSession> = unused()
    override suspend fun completeUpload(
        uploadId: String,
        parts: List<UploadPart>,
        fileName: String,
        description: String,
    ): ZillitResult<DriveItem> = unused()
    override suspend fun abortUpload(uploadId: String): ZillitResult<Unit> = ok()
    override suspend fun resumeUpload(uploadId: String, sizeBytes: Long): ZillitResult<UploadSession> = unused()
    override suspend fun trash(): ZillitResult<List<DriveItem>> = ZillitResult.Success(emptyList())
    override suspend fun restore(ref: DriveRef): ZillitResult<Unit> = ok()
    override suspend fun purge(ref: DriveRef): ZillitResult<Unit> = ok()
    override suspend fun emptyTrash(): ZillitResult<Unit> = ok()
    override suspend fun toggleFavourite(ref: DriveRef): ZillitResult<Unit> = ok()
    override suspend fun favouriteIds(): ZillitResult<Set<String>> = ZillitResult.Success(emptySet())
    override suspend fun access(ref: DriveRef): ZillitResult<List<DriveAccessEntry>> = ZillitResult.Success(emptyList())
    override suspend fun updateAccess(
        ref: DriveRef,
        entries: List<DriveAccessEntry>,
    ): ZillitResult<String> = ZillitResult.Success("folder_access_updated")
    override suspend fun activity(itemId: String?): ZillitResult<List<DriveActivity>> =
        ZillitResult.Success(emptyList())
    override suspend fun comments(fileId: String): ZillitResult<List<DriveComment>> = ZillitResult.Success(emptyList())
    override suspend fun addComment(fileId: String, text: String, parentId: String?): ZillitResult<Unit> = ok()
    override suspend fun deleteComment(commentId: String): ZillitResult<Unit> = ok()
    override suspend fun tags(): ZillitResult<List<DriveTag>> = ZillitResult.Success(emptyList())
    override suspend fun createTag(name: String, color: String): ZillitResult<DriveTag?> = ZillitResult.Success(null)
    override suspend fun deleteTag(tagId: String): ZillitResult<Unit> = ok()
    override suspend fun itemTags(ref: DriveRef): ZillitResult<List<DriveTag>> = ZillitResult.Success(emptyList())
    override suspend fun assignTag(tagId: String, ref: DriveRef): ZillitResult<Unit> = ok()
    override suspend fun removeTag(tagId: String, ref: DriveRef): ZillitResult<Unit> = ok()
    override suspend fun versions(fileId: String): ZillitResult<List<DriveVersion>> = ZillitResult.Success(emptyList())
    override suspend fun versionDownloadUrl(fileId: String, versionId: String): ZillitResult<String> =
        ZillitResult.Success("u")
    override suspend fun restoreVersion(fileId: String, versionId: String): ZillitResult<Unit> = ok()
    var history: DriveVersionHistory? = null
    var historyLoads = 0
    var changes: DriveVersionChanges? = null
    var namedVersion: Pair<String, String>? = null

    override suspend fun versionHistory(fileId: String): ZillitResult<DriveVersionHistory> {
        historyLoads++
        return history?.let { ZillitResult.Success(it) } ?: unused()
    }

    override suspend fun versionChanges(
        fileId: String,
        versionId: String,
    ): ZillitResult<DriveVersionChanges> = changes?.let { ZillitResult.Success(it) } ?: unused()

    override suspend fun versionPreview(fileId: String, versionId: String): ZillitResult<EditorSession> =
        ZillitResult.Success(
            EditorSession(
                baseUrl = "https://editor.test/sheet",
                wopiSrc = "https://wopi.test/f/$fileId/v/$versionId",
                accessToken = "t",
                viewOnly = true,
            ),
        )

    override suspend fun nameVersion(fileId: String, versionId: String, name: String): ZillitResult<Unit> {
        namedVersion = versionId to name
        return ok()
    }

    protected fun ok(): ZillitResult<Unit> = ZillitResult.Success(Unit)
    protected fun <T> unused(): ZillitResult<T> = ZillitResult.Failure(ZillitError.Unknown("unused"))
}
