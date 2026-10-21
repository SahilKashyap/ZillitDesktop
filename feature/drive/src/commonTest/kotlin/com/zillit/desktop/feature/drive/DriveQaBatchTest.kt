package com.zillit.desktop.feature.drive

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.drive.domain.DriveInnerTab
import com.zillit.desktop.feature.drive.domain.DriveItem
import com.zillit.desktop.feature.drive.domain.DriveItemKind
import com.zillit.desktop.feature.drive.domain.DriveListing
import com.zillit.desktop.feature.drive.domain.DrivePermissions
import com.zillit.desktop.feature.drive.domain.DriveSection
import com.zillit.desktop.feature.drive.domain.DriveViewer
import com.zillit.desktop.feature.drive.domain.DriveZipLink
import com.zillit.desktop.feature.drive.ui.DriveEffect
import com.zillit.desktop.feature.drive.ui.DriveEvent
import com.zillit.desktop.feature.drive.ui.DriveUiState
import com.zillit.desktop.feature.drive.ui.DriveViewModel
import com.zillit.desktop.feature.drive.ui.PreviewState
import com.zillit.desktop.feature.drive.ui.pages.menuEntries
import com.zillit.desktop.core.designsystem.component.ZillitMenuEntry
import com.zillit.desktop.feature.drive.domain.PreviewKind
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
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse

/**
 * The web's October release-bug batch, on this client: a folder downloaded as
 * one .zip, the files left out of a download named rather than counted, a
 * folder the server refuses stepping back to the section root, "Open in
 * browser" asking the same two download gates as the Download button beside
 * it, and Info staying on the menu for a view-only recipient.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DriveQaBatchTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private val viewer = DriveViewer(
        userId = "u1",
        canView = true,
        canPost = true,
        canDownload = true,
        ready = true,
    )

    private fun file(id: String, permissions: DrivePermissions = DrivePermissions.Owner) = DriveItem(
        id = id,
        kind = DriveItemKind.File,
        name = "$id.pdf",
        extension = "pdf",
        permissions = permissions,
        hasExplicitPermissions = true,
        createdById = "u1",
    )

    private fun folder(id: String, permissions: DrivePermissions = DrivePermissions.Owner) = DriveItem(
        id = id,
        kind = DriveItemKind.Folder,
        name = id,
        permissions = permissions,
        hasExplicitPermissions = true,
        createdById = "u1",
    )

    private open class Repo : FakeDriveRepository() {
        val folderZips = mutableListOf<String>()
        val fileZips = mutableListOf<List<String>>()
        val singles = mutableListOf<String>()
        var folderRefused = false

        override suspend fun folderZipLink(folderId: String): ZillitResult<DriveZipLink> {
            folderZips += folderId
            return ZillitResult.Success(DriveZipLink(token = "ft", fileName = "$folderId.zip"))
        }

        override fun folderZipDownloadUrl(token: String): String =
            "https://drive.test/folders/download-zip?token=$token"

        override suspend fun filesZipLink(fileIds: List<String>): ZillitResult<DriveZipLink> {
            fileZips += fileIds
            return ZillitResult.Success(DriveZipLink(token = "zt"))
        }

        override fun zipDownloadUrl(token: String): String = "https://drive.test/bulk/download-zip?token=$token"

        override suspend fun downloadUrl(fileId: String): ZillitResult<String> {
            singles += fileId
            return ZillitResult.Success("https://s3.test/$fileId")
        }

        override suspend fun folderContents(folderId: String): ZillitResult<DriveListing> =
            if (folderRefused) {
                ZillitResult.Failure(ZillitError.Forbidden("no_access_to_folder"))
            } else {
                ZillitResult.Success(DriveListing())
            }
    }

    private fun model(repo: Repo): DriveViewModel {
        val vm = DriveViewModel(repository = repo, viewer = { viewer })
        vm.onEvent(DriveEvent.OpenSection(DriveSection.MyDrive))
        return vm
    }

    // -- a folder as one zip ------------------------------------------------

    @Test
    fun `a folder downloads as one zip, redeemed at once`() = runTest(dispatcher) {
        val repo = Repo()
        val vm = model(repo)
        val effects = mutableListOf<DriveEffect>()
        backgroundScope.launch { vm.effects.collect { effects += it } }
        vm.start()
        runCurrent()

        vm.onEvent(DriveEvent.DownloadFolderZip(folder("day-3")))
        runCurrent()

        assertEquals(listOf("day-3"), repo.folderZips)
        assertEquals(
            "https://drive.test/folders/download-zip?token=ft",
            effects.filterIsInstance<DriveEffect.OpenUrl>().single().url,
        )
    }

    @Test
    fun `a folder this person may not download is refused here, not at the server`() = runTest(dispatcher) {
        val repo = Repo()
        val vm = model(repo)
        val effects = mutableListOf<DriveEffect>()
        backgroundScope.launch { vm.effects.collect { effects += it } }
        vm.start()
        runCurrent()

        vm.onEvent(DriveEvent.DownloadFolderZip(folder("locked", DrivePermissions.ViewOnly)))
        runCurrent()

        assertTrue(repo.folderZips.isEmpty())
        assertTrue(effects.any { it is DriveEffect.Failed })
    }

    // -- the files left out -------------------------------------------------

    @Test
    fun `one file plus one left out still zips, so the zip can name what was left out`() = runTest(dispatcher) {
        val repo = Repo().also {
            it.rows = listOf(file("f1"), file("f2", DrivePermissions.ViewOnly))
        }
        val vm = model(repo)
        vm.start()
        runCurrent()
        vm.onEvent(DriveEvent.OpenInnerTab(DriveInnerTab.Files))
        vm.onEvent(DriveEvent.SelectAll(true))

        vm.onEvent(DriveEvent.DownloadSelection)
        runCurrent()

        assertEquals(listOf(listOf("f1")), repo.fileZips, "the one allowed file goes as a zip")
        assertTrue(repo.singles.isEmpty(), "not as itself: the `_skipped.txt` has to go somewhere")
        assertTrue(
            vm.state.value.notice?.contains("f2.pdf") == true,
            "and the file left out is named, not counted: ${vm.state.value.notice}",
        )
    }

    @Test
    fun `nothing left out downloads the single file as itself`() = runTest(dispatcher) {
        val repo = Repo().also { it.rows = listOf(file("f1")) }
        val vm = model(repo)
        vm.start()
        runCurrent()
        vm.onEvent(DriveEvent.OpenInnerTab(DriveInnerTab.Files))
        vm.onEvent(DriveEvent.SelectAll(true))

        vm.onEvent(DriveEvent.DownloadSelection)
        runCurrent()

        assertEquals(listOf("f1"), repo.singles)
        assertTrue(repo.fileZips.isEmpty())
    }

    // -- a folder the server refuses ----------------------------------------

    @Test
    fun `a refused folder says so and steps back to the section root`() = runTest(dispatcher) {
        val repo = Repo().also {
            it.rows = listOf(folder("shut"))
            it.folderRefused = true
        }
        val vm = model(repo)
        val effects = mutableListOf<DriveEffect>()
        backgroundScope.launch { vm.effects.collect { effects += it } }
        vm.start()
        runCurrent()

        vm.onEvent(DriveEvent.OpenFolder("shut"))
        runCurrent()

        assertNull(vm.state.value.folderId, "back at the root, still inside the production")
        assertTrue(effects.any { it is DriveEffect.Failed }, "and told why")
    }

    // -- the preview's way out ---------------------------------------------

    @Test
    fun `Open in browser asks the file's own download right`() = runTest(dispatcher) {
        val repo = Repo()
        val vm = model(repo)
        val effects = mutableListOf<DriveEffect>()
        backgroundScope.launch { vm.effects.collect { effects += it } }
        vm.start()
        runCurrent()

        val viewOnly = file("f9", DrivePermissions.ViewOnly)
        vm.update {
            copy(preview = PreviewState(item = viewOnly, kind = PreviewKind.Document, loading = false, url = "u"))
        }
        vm.onEvent(DriveEvent.OpenPreviewInBrowser)
        runCurrent()

        assertTrue(effects.none { it is DriveEffect.OpenUrl }, "handing the address over IS the download")
        assertTrue(effects.any { it is DriveEffect.Failed })
    }

    // -- the row menu -------------------------------------------------------

    @Test
    fun `a view-only recipient keeps Info, and gets nothing that writes`() {
        val shared = DriveItem(
            id = "s1",
            kind = DriveItemKind.File,
            name = "sides.pdf",
            extension = "pdf",
            permissions = DrivePermissions(canView = true),
            hasExplicitPermissions = true,
            createdById = "someone-else",
        )
        val labels = menuEntries(shared, DriveUiState(viewer = viewer)) { }
            .filterIsInstance<ZillitMenuEntry.Action>()
            .map { it.label }

        assertTrue(labels.any { it.equals("Info", ignoreCase = true) }, "Info is read-only: $labels")
        assertFalse(labels.any { it.contains("Share", ignoreCase = true) })
        assertFalse(labels.any { it.contains("Delete", ignoreCase = true) })
    }

    @Test
    fun `a folder offers Download as ZIP to whoever may download it`() {
        val labels = menuEntries(folder("day-3"), DriveUiState(viewer = viewer)) { }
            .filterIsInstance<ZillitMenuEntry.Action>()
            .map { it.label }
        assertTrue(labels.any { it.contains("ZIP", ignoreCase = true) }, labels.toString())

        val locked = menuEntries(folder("locked", DrivePermissions.ViewOnly), DriveUiState(viewer = viewer)) { }
            .filterIsInstance<ZillitMenuEntry.Action>()
            .map { it.label }
        assertFalse(locked.any { it.contains("ZIP", ignoreCase = true) }, locked.toString())
    }

    @Test
    fun `a markdown file offers Edit file and its history, never the live editor`() {
        val notes = DriveItem(
            id = "m1",
            kind = DriveItemKind.File,
            name = "notes.md",
            extension = "md",
            permissions = DrivePermissions.Owner,
            hasExplicitPermissions = true,
            createdById = "u1",
        )
        val labels = menuEntries(notes, DriveUiState(viewer = viewer)) { }
            .filterIsInstance<ZillitMenuEntry.Action>()
            .map { it.label }

        assertTrue(labels.any { it.equals("Edit file", ignoreCase = true) }, labels.toString())
        assertTrue(labels.any { it.contains("Version history", ignoreCase = true) }, labels.toString())
        assertFalse(labels.any { it.contains("in editor", ignoreCase = true) }, labels.toString())
    }
}
