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
    override suspend fun editorUrl(fileId: String, editable: Boolean): ZillitResult<String> = ZillitResult.Success("u")
    override suspend fun initiateUpload(request: UploadRequest): ZillitResult<UploadSession> = unused()
    override suspend fun completeUpload(
        uploadId: String,
        parts: List<UploadPart>,
        fileName: String,
        description: String,
    ): ZillitResult<DriveItem> = unused()
    override suspend fun abortUpload(uploadId: String): ZillitResult<Unit> = ok()
    override suspend fun remainingParts(uploadId: String): ZillitResult<List<UploadPart>> =
        ZillitResult.Success(emptyList())
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
        applyToChildren: Boolean,
    ): ZillitResult<Unit> = ok()
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

    protected fun ok(): ZillitResult<Unit> = ZillitResult.Success(Unit)
    protected fun <T> unused(): ZillitResult<T> = ZillitResult.Failure(ZillitError.Unknown("unused"))
}
