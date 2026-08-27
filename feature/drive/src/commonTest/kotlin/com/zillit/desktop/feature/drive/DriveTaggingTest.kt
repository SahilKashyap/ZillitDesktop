package com.zillit.desktop.feature.drive

import com.zillit.desktop.core.common.ZillitError
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
import com.zillit.desktop.feature.drive.ui.DetailsState
import com.zillit.desktop.feature.drive.ui.DriveEvent
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
 * Putting a tag on a file, and taking it off.
 *
 * The browse list has filtered by tag since this tool shipped, but until now
 * nothing could apply one — so the filters only matched tags made on a phone
 * or the web. These hold the missing half in place.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DriveTaggingTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    /**
     * Uploaded by the viewer, so item-level rights resolve to Owner.
     *
     * `may` needs the tool right *and* the item grant, and a bare DriveItem
     * carries view-only permissions — a download would be refused on the item
     * even for a viewer the production allows to download.
     */
    private val file = DriveItem(
        id = "f1",
        name = "Scene 12.pdf",
        kind = DriveItemKind.File,
        uploadedById = "u1",
    )

    private class FakeRepo(override val refreshes: Flow<Unit> = MutableSharedFlow()) : DriveRepository {
        var browseLoads = 0
        var projectTags = mutableListOf(DriveTag("t1", "Approved"), DriveTag("t2", "Legal"))
        var applied = mutableListOf<DriveTag>()
        val assigned = mutableListOf<Pair<String, String>>()
        val removed = mutableListOf<Pair<String, String>>()
        val created = mutableListOf<String>()
        var itemTagReads = 0
        var assignFails = false
        val versionUrlsAsked = mutableListOf<Pair<String, String>>()

        override suspend fun contents(query: DriveQuery): ZillitResult<DrivePage> {
            browseLoads++
            return ZillitResult.Success(DrivePage(emptyList(), total = 0))
        }

        override suspend fun tags() = ZillitResult.Success(projectTags.toList())

        override suspend fun itemTags(ref: DriveRef): ZillitResult<List<DriveTag>> {
            itemTagReads++
            return ZillitResult.Success(applied.toList())
        }

        override suspend fun createTag(name: String, color: String): ZillitResult<Unit> {
            created += name
            projectTags += DriveTag("new-${created.size}", name)
            return ok()
        }

        override suspend fun assignTag(tagId: String, ref: DriveRef): ZillitResult<Unit> {
            if (assignFails) return ZillitResult.Failure(ZillitError.Unknown("nope"))
            assigned += tagId to ref.id
            projectTags.firstOrNull { it.id == tagId }?.let { applied += it }
            return ok()
        }

        override suspend fun removeTag(tagId: String, ref: DriveRef): ZillitResult<Unit> {
            removed += tagId to ref.id
            applied.removeAll { it.id == tagId }
            return ok()
        }

        override suspend fun item(id: String, kind: DriveItemKind): ZillitResult<DriveItem> = unused()
        override suspend fun breadcrumb(folderId: String) = ZillitResult.Success(emptyList<DriveCrumb>())
        override suspend fun createFolder(name: String, parentId: String?, description: String):
            ZillitResult<DriveItem> = unused()
        override suspend fun rename(ref: DriveRef, name: String, description: String?) = ok()
        override suspend fun move(ref: DriveRef, targetFolderId: String?) = ok()
        override suspend fun delete(ref: DriveRef) = ok()
        override suspend fun bulkDelete(refs: List<DriveRef>) = ok()
        override suspend fun bulkMove(refs: List<DriveRef>, targetFolderId: String?) = ok()
        override suspend fun bulkDownloadUrls(fileIds: List<String>) = ZillitResult.Success(emptyList<String>())
        override suspend fun downloadUrl(fileId: String) = ZillitResult.Success("u")
        override suspend fun previewUrl(fileId: String) = ZillitResult.Success("u")
        override suspend fun streamUrl(fileId: String) = ZillitResult.Success("u")
        override suspend fun shareLink(fileId: String) = ZillitResult.Success("u")
        override suspend fun editorUrl(fileId: String, editable: Boolean) = ZillitResult.Success("u")
        override suspend fun initiateUpload(request: UploadRequest): ZillitResult<UploadSession> = unused()
        override suspend fun completeUpload(uploadId: String, parts: List<UploadPart>):
            ZillitResult<DriveItem> = unused()
        override suspend fun abortUpload(uploadId: String) = ok()
        override suspend fun remainingParts(uploadId: String) = ZillitResult.Success(emptyList<UploadPart>())
        override suspend fun trash() = ZillitResult.Success(emptyList<DriveItem>())
        override suspend fun restore(ref: DriveRef) = ok()
        override suspend fun purge(ref: DriveRef) = ok()
        override suspend fun emptyTrash() = ok()
        override suspend fun toggleFavourite(ref: DriveRef) = ok()
        override suspend fun favourites() = ZillitResult.Success(emptyList<DriveItem>())
        override suspend fun favouriteIds() = ZillitResult.Success(emptySet<String>())
        override suspend fun access(ref: DriveRef) = ZillitResult.Success(emptyList<DriveAccessEntry>())
        override suspend fun updateAccess(
            ref: DriveRef,
            entries: List<DriveAccessEntry>,
            applyToChildren: Boolean,
        ) = ok()
        override suspend fun storage() = ZillitResult.Success(StorageUsage())
        override suspend fun activity(itemId: String?) = ZillitResult.Success(emptyList<DriveActivity>())
        override suspend fun comments(fileId: String) = ZillitResult.Success(emptyList<DriveComment>())
        override suspend fun addComment(fileId: String, text: String, parentId: String?) = ok()
        override suspend fun deleteComment(commentId: String) = ok()
        override suspend fun deleteTag(tagId: String) = ok()
        override suspend fun versions(fileId: String) = ZillitResult.Success(emptyList<DriveVersion>())
        override suspend fun versionDownloadUrl(
            fileId: String,
            versionId: String,
        ): ZillitResult<String> {
            versionUrlsAsked += fileId to versionId
            return ZillitResult.Success("u")
        }
        override suspend fun restoreVersion(fileId: String, versionId: String) = ok()

        private fun ok() = ZillitResult.Success(Unit)
        private fun <T> unused(): ZillitResult<T> = ZillitResult.Failure(ZillitError.Unknown("unused"))
    }

    private fun model(repo: FakeRepo, canPost: Boolean = true) = DriveViewModel(
        repository = repo,
        viewer = { DriveViewer(userId = "u1", ready = true, canPost = canPost, canView = true) },
    )

    @Test
    fun `opening a file reads the tags already on it`() = runTest(dispatcher) {
        val repo = FakeRepo()
        repo.applied += DriveTag("t1", "Approved")
        val vm = model(repo)
        vm.start()
        runCurrent()

        vm.onEvent(DriveEvent.ShowDetails(file))
        runCurrent()

        assertEquals(listOf("Approved"), vm.state.value.details.tags.map { it.name })
    }

    @Test
    fun `assigning a tag sends the item's own reference and re-reads its tags`() = runTest(dispatcher) {
        val repo = FakeRepo()
        val vm = model(repo)
        vm.start()
        runCurrent()
        vm.onEvent(DriveEvent.ShowDetails(file))
        runCurrent()
        val readsAfterOpen = repo.itemTagReads

        vm.onEvent(DriveEvent.AssignTag("t2"))
        runCurrent()

        assertEquals(listOf("t2" to "f1"), repo.assigned)
        assertEquals(readsAfterOpen + 1, repo.itemTagReads, "the panel re-reads just this item")
        assertEquals(listOf("Legal"), vm.state.value.details.tags.map { it.name })
    }

    /** The browse list stays where it is — a reader mid-scroll is not thrown back. */
    @Test
    fun `a tag change does not reload the browse list`() = runTest(dispatcher) {
        val repo = FakeRepo()
        val vm = model(repo)
        vm.start()
        runCurrent()
        vm.onEvent(DriveEvent.ShowDetails(file))
        runCurrent()
        val loadsBefore = repo.browseLoads

        vm.onEvent(DriveEvent.AssignTag("t1"))
        runCurrent()

        assertEquals(loadsBefore, repo.browseLoads)
    }

    @Test
    fun `removing a tag takes it off the open item`() = runTest(dispatcher) {
        val repo = FakeRepo()
        repo.applied += DriveTag("t1", "Approved")
        val vm = model(repo)
        vm.start()
        runCurrent()
        vm.onEvent(DriveEvent.ShowDetails(file))
        runCurrent()

        vm.onEvent(DriveEvent.RemoveTag("t1"))
        runCurrent()

        assertEquals(listOf("t1" to "f1"), repo.removed)
        assertTrue(vm.state.value.details.tags.isEmpty())
    }

    @Test
    fun `a new tag is created, found by name and applied in one gesture`() = runTest(dispatcher) {
        val repo = FakeRepo()
        val vm = model(repo)
        vm.start()
        runCurrent()
        vm.onEvent(DriveEvent.ShowDetails(file))
        runCurrent()

        vm.onEvent(DriveEvent.TagDraft("  Night shoot  "))
        vm.onEvent(DriveEvent.CreateAndAssignTag)
        runCurrent()

        assertEquals(listOf("Night shoot"), repo.created, "the name is trimmed before sending")
        assertEquals(listOf("Night shoot"), vm.state.value.details.tags.map { it.name })
        assertEquals("", vm.state.value.details.tagDraft, "the box empties on submit")
    }

    /** Two tags of the same name is not an error the service prevents; the newest is the one just made. */
    @Test
    fun `a duplicate name resolves to the tag just created`() = runTest(dispatcher) {
        val repo = FakeRepo()
        repo.projectTags += DriveTag("old", "Approved")
        val vm = model(repo)
        vm.start()
        runCurrent()
        vm.onEvent(DriveEvent.ShowDetails(file))
        runCurrent()

        vm.onEvent(DriveEvent.TagDraft("Approved"))
        vm.onEvent(DriveEvent.CreateAndAssignTag)
        runCurrent()

        assertEquals(listOf("new-1" to "f1"), repo.assigned, "not the pre-existing 'old'")
    }

    @Test
    fun `an empty name creates nothing`() = runTest(dispatcher) {
        val repo = FakeRepo()
        val vm = model(repo)
        vm.start()
        runCurrent()
        vm.onEvent(DriveEvent.ShowDetails(file))
        runCurrent()

        vm.onEvent(DriveEvent.TagDraft("   "))
        vm.onEvent(DriveEvent.CreateAndAssignTag)
        runCurrent()

        assertTrue(repo.created.isEmpty())
    }

    @Test
    fun `a reader cannot tag, and nothing is sent`() = runTest(dispatcher) {
        val repo = FakeRepo()
        val vm = model(repo, canPost = false)
        vm.start()
        runCurrent()
        vm.onEvent(DriveEvent.ShowDetails(file))
        runCurrent()

        vm.onEvent(DriveEvent.AssignTag("t1"))
        vm.onEvent(DriveEvent.RemoveTag("t1"))
        vm.onEvent(DriveEvent.TagDraft("Nope"))
        vm.onEvent(DriveEvent.CreateAndAssignTag)
        runCurrent()

        assertTrue(repo.assigned.isEmpty() && repo.removed.isEmpty() && repo.created.isEmpty())
    }

    @Test
    fun `with nothing open a tag event is ignored`() = runTest(dispatcher) {
        val repo = FakeRepo()
        val vm = model(repo)
        vm.start()
        runCurrent()

        vm.onEvent(DriveEvent.AssignTag("t1"))
        runCurrent()

        assertTrue(repo.assigned.isEmpty())
    }

    /** A failed assign clears the busy flag, or the panel's controls stay dead. */
    @Test
    fun `a refused assign leaves the controls usable`() = runTest(dispatcher) {
        val repo = FakeRepo()
        repo.assignFails = true
        val vm = model(repo)
        vm.start()
        runCurrent()
        vm.onEvent(DriveEvent.ShowDetails(file))
        runCurrent()

        vm.onEvent(DriveEvent.AssignTag("t1"))
        runCurrent()

        assertTrue(!vm.state.value.details.tagsBusy, "busy must not stick after a failure")
    }

    // -- version downloads ---------------------------------------------------

    @Test
    fun `downloading an old version asks for that version's url`() = runTest(dispatcher) {
        val repo = FakeRepo()
        val vm = model(repo)
        vm.start()
        runCurrent()

        vm.onEvent(DriveEvent.DownloadVersion(file, "v2"))
        runCurrent()

        assertEquals(listOf("f1" to "v2"), repo.versionUrlsAsked)
    }

    /**
     * The same right as the live file. An earlier version reachable by
     * someone who cannot download the current one is the hole the web closed
     * in ZL-18294.
     */
    @Test
    fun `a viewer without download rights gets no version url`() = runTest(dispatcher) {
        val repo = FakeRepo()
        val vm = DriveViewModel(
            repository = repo,
            viewer = {
                DriveViewer(userId = "u1", ready = true, canView = true, canDownload = false)
            },
        )
        vm.start()
        runCurrent()

        vm.onEvent(DriveEvent.DownloadVersion(file, "v2"))
        runCurrent()

        assertTrue(repo.versionUrlsAsked.isEmpty())
    }

    @Test
    fun `the add menu offers only tags not already on the item`() {
        val details = DetailsState(item = file, tags = listOf(DriveTag("t1", "Approved")))
        val all = listOf(DriveTag("t1", "Approved"), DriveTag("t2", "Legal"))

        assertEquals(listOf("Legal"), details.unapplied(all).map { it.name })
    }

    @Test
    fun `with no tags applied the whole project list is on offer`() {
        val all = listOf(DriveTag("t1", "Approved"), DriveTag("t2", "Legal"))

        assertEquals(all, DetailsState(item = file).unapplied(all))
    }
}
