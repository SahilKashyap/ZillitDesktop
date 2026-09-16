package com.zillit.desktop.feature.drive

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.drive.domain.DriveCrumb
import com.zillit.desktop.feature.drive.domain.DriveItem
import com.zillit.desktop.feature.drive.domain.DriveItemKind
import com.zillit.desktop.feature.drive.domain.DriveRef
import com.zillit.desktop.feature.drive.domain.DriveTag
import com.zillit.desktop.feature.drive.domain.DriveViewer
import com.zillit.desktop.feature.drive.ui.DetailsState
import com.zillit.desktop.feature.drive.ui.DriveEvent
import com.zillit.desktop.feature.drive.ui.DriveViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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

    private class FakeRepo : FakeDriveRepository() {
        var projectTags = mutableListOf(DriveTag("t1", "Approved"), DriveTag("t2", "Legal"))
        var applied = mutableListOf<DriveTag>()
        val assigned = mutableListOf<Pair<String, String>>()
        val removed = mutableListOf<Pair<String, String>>()
        val created = mutableListOf<String>()
        var itemTagReads = 0
        var assignFails = false
        val versionUrlsAsked = mutableListOf<Pair<String, String>>()
        var renames = 0

        override suspend fun tags() = ZillitResult.Success(projectTags.toList())

        override suspend fun itemTags(ref: DriveRef): ZillitResult<List<DriveTag>> {
            itemTagReads++
            return ZillitResult.Success(applied.toList())
        }

        /** Answers no body, as the live service does: the new tag is found by name. */
        override suspend fun createTag(name: String, color: String): ZillitResult<DriveTag?> {
            created += name
            projectTags += DriveTag("new-${created.size}", name)
            return ZillitResult.Success(null)
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

        override suspend fun rename(ref: DriveRef, name: String, description: String?): ZillitResult<Unit> {
            renames++
            return ok()
        }

        override suspend fun versionDownloadUrl(fileId: String, versionId: String): ZillitResult<String> {
            versionUrlsAsked += fileId to versionId
            return ZillitResult.Success("u")
        }
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
    /**
     * The folder trail, rebuilt from the folders' own ancestry — the whole
     * scope is in hand, so a folder reached by any route gets its full trail
     * (ZL-20182), and the root clears it.
     */
    @Test
    fun `the trail is the folder's ancestry, and the root clears it`() = runTest(dispatcher) {
        val repo = FakeRepo()
        repo.rows = listOf(folder("f1", "Scripts"), folder("f2", "Drafts", parent = "f1"))
        val vm = model(repo)
        vm.start()
        runCurrent()

        vm.onEvent(DriveEvent.OpenFolder("f1"))
        runCurrent()
        assertEquals(listOf("Scripts"), vm.state.value.breadcrumb.map { it.name })

        vm.onEvent(DriveEvent.OpenFolder("f2"))
        runCurrent()
        assertEquals(listOf("Scripts", "Drafts"), vm.state.value.breadcrumb.map { it.name })

        // Clicking a crumb already on the trail walks back to it.
        vm.onEvent(DriveEvent.OpenFolder("f1"))
        runCurrent()
        assertEquals(listOf("Scripts"), vm.state.value.breadcrumb.map { it.name })

        // And the root clears it.
        vm.onEvent(DriveEvent.OpenFolder(null))
        runCurrent()
        assertEquals(emptyList(), vm.state.value.breadcrumb)
    }

    private fun folder(id: String, name: String, parent: String? = null) =
        DriveItem(
            id = id,
            name = name,
            kind = DriveItemKind.Folder,
            parentFolderId = parent,
            uploadedById = "u1",
            createdById = "u1",
        )

    /**
     * Renaming goes through the edit drawer, which refuses to open without
     * posting rights (ZL-18294) — so nothing reaches the server.
     */
    @Test
    fun `someone who may not edit cannot rename`() = runTest(dispatcher) {
        val repo = FakeRepo()
        repo.rows = listOf(folder("f1", "Scripts"))
        val vm = model(repo, canPost = false)
        vm.start()
        runCurrent()

        vm.onEvent(DriveEvent.OpenEdit(folder("f1", "Scripts")))
        vm.onEvent(DriveEvent.EditName("Drafts"))
        vm.onEvent(DriveEvent.SubmitEdit)
        runCurrent()

        assertEquals(0, repo.renames, "a rename went through without the right")
        assertEquals(null, vm.state.value.edit, "the edit drawer must not open for a reader")
    }

    /** With the right, the drawer strips a file's extension for editing and puts it back on save. */
    @Test
    fun `a file is renamed without its extension, which is restored on save`() = runTest(dispatcher) {
        val repo = FakeRepo()
        repo.rows = listOf(file)
        val vm = model(repo)
        vm.start()
        runCurrent()

        vm.onEvent(DriveEvent.OpenEdit(file))
        assertEquals("Scene 12", vm.state.value.edit?.name)

        vm.onEvent(DriveEvent.EditName("Scene 13"))
        vm.onEvent(DriveEvent.SubmitEdit)
        runCurrent()

        assertEquals(1, repo.renames)
        assertEquals(null, vm.state.value.edit, "the drawer closes on success")
    }
}
