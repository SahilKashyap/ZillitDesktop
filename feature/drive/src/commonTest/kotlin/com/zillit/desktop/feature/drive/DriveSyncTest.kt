package com.zillit.desktop.feature.drive

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.feature.drive.data.DRIVE_SYNC_EVENTS
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.drive.domain.DriveAccessEntry
import com.zillit.desktop.feature.drive.domain.DriveActivity
import com.zillit.desktop.feature.drive.domain.DriveComment
import com.zillit.desktop.feature.drive.domain.DriveCrumb
import com.zillit.desktop.feature.drive.domain.DriveItem
import com.zillit.desktop.feature.drive.domain.DriveItemKind
import com.zillit.desktop.feature.drive.domain.DrivePage
import com.zillit.desktop.feature.drive.domain.DriveQuery
import com.zillit.desktop.feature.drive.domain.DriveRef
import com.zillit.desktop.feature.drive.domain.DriveRepository
import com.zillit.desktop.feature.drive.domain.DriveTag
import com.zillit.desktop.feature.drive.domain.DriveVersion
import com.zillit.desktop.feature.drive.domain.DriveViewer
import com.zillit.desktop.feature.drive.domain.StorageUsage
import com.zillit.desktop.feature.drive.domain.UploadPart
import com.zillit.desktop.feature.drive.domain.UploadRequest
import com.zillit.desktop.feature.drive.domain.UploadSession
import com.zillit.desktop.feature.drive.ui.DriveViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A drive delete pulse refetches the open destination once — the web's
 * refetch on `drive_file_deleted`/`drive_folder_deleted`/`drive_bulk_deleted`
 * (`DriveManagement.jsx:1591-1596`, ZL-18490) as a targeted reload.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DriveSyncTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    @Suppress("TooManyFunctions") // One override per repository operation.
    private class FakeRepo(override val refreshes: Flow<Unit>) : DriveRepository {
        var browseLoads = 0
        override suspend fun contents(query: DriveQuery): ZillitResult<DrivePage> {
            browseLoads++
            return ZillitResult.Success(DrivePage(emptyList(), total = 0))
        }
        override suspend fun item(id: String, kind: DriveItemKind): ZillitResult<DriveItem> = unused()
        override suspend fun createFolder(name: String, parentId: String?, description: String):
            ZillitResult<DriveItem> = unused()
        override suspend fun rename(ref: DriveRef, name: String, description: String?) = ok()
        override suspend fun move(ref: DriveRef, targetFolderId: String?) = ok()
        override suspend fun delete(ref: DriveRef) = ok()
        override suspend fun bulkDelete(refs: List<DriveRef>) = ok()
        override suspend fun bulkMove(refs: List<DriveRef>, targetFolderId: String?) = ok()
        override suspend fun bulkDownloadUrls(fileIds: List<String>) =
            ZillitResult.Success(emptyList<String>())
        override suspend fun downloadUrl(fileId: String) = ZillitResult.Success("u")
        override suspend fun previewUrl(fileId: String) = ZillitResult.Success("u")
        override suspend fun streamUrl(fileId: String) = ZillitResult.Success("u")
        override suspend fun shareLink(fileId: String) = ZillitResult.Success("u")
        override suspend fun editorUrl(fileId: String, editable: Boolean) = ZillitResult.Success("u")
        override suspend fun initiateUpload(request: UploadRequest): ZillitResult<UploadSession> = unused()
        override suspend fun completeUpload(uploadId: String, parts: List<UploadPart>):
            ZillitResult<DriveItem> = unused()
        override suspend fun abortUpload(uploadId: String) = ok()
        override suspend fun remainingParts(uploadId: String) =
            ZillitResult.Success(emptyList<UploadPart>())
        override suspend fun trash() = ZillitResult.Success(emptyList<DriveItem>())
        override suspend fun restore(ref: DriveRef) = ok()
        override suspend fun purge(ref: DriveRef) = ok()
        override suspend fun emptyTrash() = ok()
        override suspend fun toggleFavourite(ref: DriveRef) = ok()
        override suspend fun favourites() = ZillitResult.Success(emptyList<DriveItem>())
        override suspend fun favouriteIds() = ZillitResult.Success(emptySet<String>())
        override suspend fun access(ref: DriveRef) = ZillitResult.Success(emptyList<DriveAccessEntry>())
        override suspend fun updateAccess(ref: DriveRef, entries: List<DriveAccessEntry>, applyToChildren: Boolean) =
            ok()
        override suspend fun storage() = ZillitResult.Success(StorageUsage())
        override suspend fun activity(itemId: String?) = ZillitResult.Success(emptyList<DriveActivity>())
        override suspend fun comments(fileId: String) = ZillitResult.Success(emptyList<DriveComment>())
        override suspend fun addComment(fileId: String, text: String, parentId: String?) = ok()
        override suspend fun deleteComment(commentId: String) = ok()
        override suspend fun tags() = ZillitResult.Success(emptyList<DriveTag>())
        override suspend fun createTag(name: String, color: String) = ok()
        override suspend fun deleteTag(tagId: String) = ok()
        override suspend fun itemTags(ref: DriveRef) = ZillitResult.Success(emptyList<DriveTag>())
        override suspend fun assignTag(tagId: String, ref: DriveRef) = ok()
        override suspend fun removeTag(tagId: String, ref: DriveRef) = ok()
        override suspend fun versions(fileId: String) = ZillitResult.Success(emptyList<DriveVersion>())
        override suspend fun versionDownloadUrl(fileId: String, versionId: String) = ZillitResult.Success("u")
        override suspend fun restoreVersion(fileId: String, versionId: String) = ok()

        private fun ok() = ZillitResult.Success(Unit)
        private fun <T> unused(): ZillitResult<T> = ZillitResult.Failure(ZillitError.Unknown("unused"))
    }

    // -- which wire events are subscribed ----------------------------------

    /**
     * The list the browse view reloads on.
     *
     * The names are the wire's, not the underscore aliases the web's
     * components listen to — those are re-emits and exist only inside
     * `listenerSocket.js`. Getting one wrong is silent: the subscription
     * simply never fires and the list quietly goes stale.
     */
    @Test
    fun `the browse list reloads on every change another client can announce`() {
        val names = DRIVE_SYNC_EVENTS.map { it.value }

        assertTrue("drive:file:added" in names, "a colleague's upload must appear")
        assertTrue("drive:folder:created" in names)
        assertTrue("drive:file:updated" in names && "drive:folder:updated" in names)
        assertTrue("drive:folder:moved" in names)
        assertTrue("drive:file:deleted" in names && "drive:folder:deleted" in names)
        assertTrue("drive:bulk:deleted" in names)
    }

    /**
     * A file shared with this user belongs in "Shared with me" straight away.
     *
     * The web only badges these, which is why they were left out — but the
     * web has no shared listing to be wrong about. iOS reloads on them like
     * any other structural change (`HomeViewModel.swift:2018`).
     */
    @Test
    fun `sharing reloads the list`() {
        val names = DRIVE_SYNC_EVENTS.map { it.value }

        assertTrue("drive:file:shared" in names)
        assertTrue("drive:folder:shared" in names)
    }

    /** Comments belong to the open details panel, not to the listing. */
    @Test
    fun `comments do not reload the list`() {
        val names = DRIVE_SYNC_EVENTS.map { it.value }

        assertTrue(names.none { it.startsWith("drive:comment") })
    }

    @Test
    fun `every subscribed name is a drive event, spelt in wire form`() {
        val names = DRIVE_SYNC_EVENTS.map { it.value }

        assertEquals(names.size, names.toSet().size, "no duplicate subscriptions")
        assertTrue(names.all { it.startsWith("drive:") }, "no underscore aliases")
        assertTrue(names.none { it.contains('_') })
    }

    @Test
    fun `a delete pulse reloads the open destination once`() = runTest(dispatcher) {
        val events = MutableSharedFlow<Unit>()
        val repo = FakeRepo(refreshes = events)
        val model = DriveViewModel(
            repository = repo,
            viewer = { DriveViewer(userId = "u1", ready = true) },
        )

        model.start()
        runCurrent()
        assertEquals(1, repo.browseLoads, "start loads the browser once")

        events.emit(Unit)
        runCurrent()
        assertEquals(2, repo.browseLoads, "the pulse re-runs exactly one load")

        model.onProjectChanged()
        runCurrent()
        assertEquals(3, repo.browseLoads)

        events.emit(Unit)
        runCurrent()
        assertEquals(4, repo.browseLoads, "a project switch must not stack a second collector")
    }
}
