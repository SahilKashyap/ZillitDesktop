package com.zillit.desktop.feature.drive

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.localization.localisedMessage
import com.zillit.desktop.feature.drive.domain.DriveItem
import com.zillit.desktop.feature.drive.domain.DriveItemKind
import com.zillit.desktop.feature.drive.domain.DriveInnerTab
import com.zillit.desktop.feature.drive.domain.DriveListing
import com.zillit.desktop.feature.drive.domain.DrivePermissions
import com.zillit.desktop.feature.drive.domain.DriveRef
import com.zillit.desktop.feature.drive.domain.DriveSection
import com.zillit.desktop.feature.drive.domain.DriveViewer
import com.zillit.desktop.feature.drive.domain.DriveZipLink
import com.zillit.desktop.feature.drive.domain.EditorSession
import com.zillit.desktop.feature.drive.domain.driveMessage
import com.zillit.desktop.feature.drive.ui.DriveEffect
import com.zillit.desktop.feature.drive.ui.DriveEvent
import com.zillit.desktop.feature.drive.ui.DriveViewModel
import com.zillit.desktop.feature.drive.ui.mergedWith
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
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
 * Moving out of a shared folder, saving several files as one .zip, and
 * opening a file in Zillit's own editors.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DriveMoveDownloadEditorTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private val admin = DriveViewer(
        userId = "u1", canView = true, canPost = true, canDownload = true, isAdmin = true, ready = true,
    )

    private fun file(id: String) = DriveItem(
        id = id,
        kind = DriveItemKind.File,
        name = "$id.pdf",
        extension = "pdf",
        permissions = DrivePermissions.Owner,
        hasExplicitPermissions = true,
        createdById = "someone-else",
    )

    private open class Repo : FakeDriveRepository() {
        val moved = mutableListOf<String>()
        val zipped = mutableListOf<List<String>>()
        val singles = mutableListOf<String>()
        val folderReads = mutableListOf<String>()
        var editorViewOnly: Boolean? = null
        var folderRows = DriveListing()
        var session = EditorSession(
            baseUrl = "https://drive.test/editor/sheet",
            wopiSrc = "https://wopi.test/f/f1",
            accessToken = "tok",
            viewOnly = true,
            canSwitchToEdit = true,
        )

        override suspend fun move(ref: DriveRef, targetFolderId: String?): ZillitResult<Unit> {
            moved += ref.id
            return ok()
        }

        override suspend fun bulkMove(refs: List<DriveRef>, targetFolderId: String?): ZillitResult<Unit> {
            moved += refs.map { it.id }
            return ok()
        }

        override suspend fun downloadUrl(fileId: String): ZillitResult<String> {
            singles += fileId
            return ZillitResult.Success("https://s3.test/$fileId")
        }

        override suspend fun filesZipLink(fileIds: List<String>): ZillitResult<DriveZipLink> {
            zipped += fileIds
            return ZillitResult.Success(DriveZipLink(token = "zt", fileName = "Drive files (2).zip", fileCount = 2))
        }

        override fun zipDownloadUrl(token: String): String = "https://drive.test/bulk/download-zip?token=$token"

        override suspend fun folderContents(folderId: String): ZillitResult<DriveListing> {
            folderReads += folderId
            return ZillitResult.Success(folderRows)
        }

        override suspend fun editorSession(fileId: String, viewOnly: Boolean): ZillitResult<EditorSession> {
            editorViewOnly = viewOnly
            return ZillitResult.Success(session)
        }
    }

    private fun model(repo: Repo, section: DriveSection = DriveSection.MyDrive): DriveViewModel {
        val vm = DriveViewModel(repository = repo, viewer = { admin })
        if (section == DriveSection.SharedWithMe) return vm
        vm.onEvent(DriveEvent.OpenSection(section))
        return vm
    }

    // -- moving -------------------------------------------------------------

    @Test
    fun `a move under Shared with me is refused without asking the server`() = runTest(dispatcher) {
        val repo = Repo().also { it.rows = listOf(file("f1")) }
        // The view model lands on Shared with me, which is where this rule bites.
        val vm = DriveViewModel(repository = repo, viewer = { admin })
        val toasts = mutableListOf<DriveEffect>()
        backgroundScope.launch { vm.effects.collect { toasts += it } }
        vm.start()
        runCurrent()
        assertEquals(DriveSection.SharedWithMe, vm.state.value.section)

        vm.onEvent(DriveEvent.MoveTo(listOf(DriveRef("f1", DriveItemKind.File)), "other"))
        runCurrent()

        assertTrue(repo.moved.isEmpty(), "nothing is sent: those items are in someone else's tree")
        assertTrue(toasts.any { it is DriveEffect.Failed }, "and the user is told why")
    }

    @Test
    fun `a move in My Drive still goes`() = runTest(dispatcher) {
        val repo = Repo().also { it.rows = listOf(file("f1")) }
        val vm = model(repo)
        vm.start()
        runCurrent()

        vm.onEvent(DriveEvent.MoveTo(listOf(DriveRef("f1", DriveItemKind.File)), "fold"))
        runCurrent()

        assertEquals(listOf("f1"), repo.moved)
    }

    @Test
    fun `the server's own word for a refusal is what is shown`() {
        // 403s are mapped to Forbidden, whose user-facing text is generic and
        // whose `technical` is where the server's key ended up.
        val refused = ZillitError.Forbidden("only_owner_can_move_out_of_folder")

        // Translated where the dictionary knows the key, and readable rather
        // than raw where it does not — but never the generic sentence, which
        // says nothing about who may move the item.
        assertEquals("only_owner_can_move_out_of_folder".localisedMessage(), refused.driveMessage())
        assertTrue(refused.driveMessage().contains("Move Out", ignoreCase = true))
        assertEquals(
            ZillitError.Forbidden().userMessage,
            ZillitError.Forbidden().driveMessage(),
            "a refusal that carried no key keeps the generic sentence",
        )
    }

    // -- downloading --------------------------------------------------------

    @Test
    fun `two or more selected files are saved as one zip`() = runTest(dispatcher) {
        val repo = Repo().also { it.rows = listOf(file("f1"), file("f2")) }
        val vm = model(repo)
        val effects = mutableListOf<DriveEffect>()
        backgroundScope.launch { vm.effects.collect { effects += it } }
        vm.start()
        runCurrent()
        // The root splits into Folders and Files; the files are on the second.
        vm.onEvent(DriveEvent.OpenInnerTab(DriveInnerTab.Files))
        vm.onEvent(DriveEvent.SelectAll(true))

        vm.onEvent(DriveEvent.DownloadSelection)
        runCurrent()

        assertEquals(listOf(listOf("f1", "f2")), repo.zipped)
        assertTrue(repo.singles.isEmpty(), "one address per file only ever saved the first")
        assertEquals(
            "https://drive.test/bulk/download-zip?token=zt",
            effects.filterIsInstance<DriveEffect.OpenUrl>().single().url,
            "the token is the authorisation, and it expires in two minutes",
        )
    }

    @Test
    fun `one selected file downloads as itself`() = runTest(dispatcher) {
        val repo = Repo().also { it.rows = listOf(file("f1")) }
        val vm = model(repo)
        vm.start()
        runCurrent()
        vm.onEvent(DriveEvent.OpenInnerTab(DriveInnerTab.Files))
        vm.onEvent(DriveEvent.ToggleSelection("f1"))

        vm.onEvent(DriveEvent.DownloadSelection)
        runCurrent()

        assertEquals(listOf("f1"), repo.singles)
        assertTrue(repo.zipped.isEmpty())
    }

    // -- showing what was just put in a folder -------------------------------

    @Test
    fun `opening a folder asks the folder itself and merges what it answers`() = runTest(dispatcher) {
        val folder = DriveItem(id = "fold", kind = DriveItemKind.Folder, name = "Team", createdById = "u1")
        val mine = file("f1")
        val theirs = file("f2").copy(parentFolderId = "fold")
        val repo = Repo().also {
            it.rows = listOf(folder, mine)
            // A file uploaded into this folder that is in no tab-wide list.
            it.folderRows = DriveListing(files = listOf(theirs))
        }
        val vm = model(repo)
        vm.start()
        runCurrent()

        vm.onEvent(DriveEvent.OpenFolder("fold"))
        runCurrent()

        assertEquals(listOf("fold"), repo.folderReads)
        assertEquals(
            setOf("f1", "f2"),
            vm.state.value.listing.files.map { it.id }.toSet(),
            "merged, never substituted: the listing keeps every row it had",
        )
    }

    @Test
    fun `the merge prefers the fresh row and keeps the rest`() {
        val stale = file("f1").copy(name = "old.pdf")
        val fresh = file("f1").copy(name = "new.pdf")
        val other = file("f2")

        val merged = listOf(stale, other).mergedWith(listOf(fresh, file("f3")))

        assertEquals(listOf("new.pdf", "f2.pdf", "f3.pdf"), merged.map { it.name })
        assertEquals(listOf(stale, other), listOf(stale, other).mergedWith(emptyList()))
    }

    // -- the editors --------------------------------------------------------

    @Test
    fun `a preview opens the editor read-only and offers Edit to someone who may`() = runTest(dispatcher) {
        val sheet = file("f1").copy(name = "Budget.xlsx", extension = "xlsx")
        val repo = Repo().also { it.rows = listOf(sheet) }
        val vm = model(repo)
        val effects = mutableListOf<DriveEffect>()
        backgroundScope.launch { vm.effects.collect { effects += it } }
        vm.start()
        runCurrent()

        vm.onEvent(DriveEvent.Preview(sheet))
        runCurrent()

        assertEquals(true, repo.editorViewOnly, "a preview asks for mode=view")
        val opened = effects.filterIsInstance<DriveEffect.OpenEditor>().single()
        assertTrue(opened.viewOnly)
        assertTrue(opened.canSwitchToEdit, "_permissions.canSwitchToEdit is what draws the app's Edit button")
        assertEquals("f1", opened.fileId)
        assertTrue(
            opened.url.startsWith("https://drive.test/editor/sheet?WOPISrc="),
            "loaded top-level, with the two credentials in the query: ${opened.url}",
        )
    }

    @Test
    fun `Edit reopens the same file without mode=view`() = runTest(dispatcher) {
        val sheet = file("f1").copy(name = "Budget.xlsx", extension = "xlsx")
        val repo = Repo().also { it.rows = listOf(sheet) }
        val vm = model(repo)
        vm.start()
        runCurrent()
        vm.onEvent(DriveEvent.Preview(sheet))
        runCurrent()

        repo.session = repo.session.copy(viewOnly = false, canSwitchToEdit = false)
        vm.onEvent(DriveEvent.SwitchEditorToEdit("f1"))
        runCurrent()

        assertEquals(false, repo.editorViewOnly, "Drive mints an editing token for the second open")
    }

    @Test
    fun `a file the editors cannot open says what the server said`() = runTest(dispatcher) {
        val repo = object : Repo() {
            override suspend fun editorSession(fileId: String, viewOnly: Boolean): ZillitResult<EditorSession> =
                ZillitResult.Failure(ZillitError.Http(status = 400, serverMessage = "file_too_large_to_edit"))
        }
        repo.rows = listOf(file("f1").copy(name = "Huge.xlsx", extension = "xlsx"))
        val vm = model(repo)
        val effects = mutableListOf<DriveEffect>()
        backgroundScope.launch { vm.effects.collect { effects += it } }
        vm.start()
        runCurrent()

        vm.onEvent(DriveEvent.OpenInEditor(repo.rows.first(), true))
        runCurrent()

        val failed = effects.filterIsInstance<DriveEffect.Failed>().single()
        assertTrue(failed.message.isNotBlank())
        assertTrue(effects.none { it is DriveEffect.OpenEditor }, "nothing is opened on a refusal")
    }
}
