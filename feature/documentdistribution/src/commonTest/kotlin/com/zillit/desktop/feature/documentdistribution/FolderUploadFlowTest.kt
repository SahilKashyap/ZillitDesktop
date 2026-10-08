package com.zillit.desktop.feature.documentdistribution

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.documentdistribution.domain.DocDistHost
import com.zillit.desktop.feature.documentdistribution.domain.DocDistViewer
import com.zillit.desktop.feature.documentdistribution.domain.FolderEntry
import com.zillit.desktop.feature.documentdistribution.domain.LibraryFolder
import com.zillit.desktop.feature.documentdistribution.domain.LibraryDocument
import com.zillit.desktop.feature.documentdistribution.domain.LocalFolderTree
import com.zillit.desktop.feature.documentdistribution.ui.DocDistEvent
import com.zillit.desktop.feature.documentdistribution.ui.DocDistViewModel
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.LocalDate

/** Upload a folder, and drop one at the root: from the pick to the last file. */
@OptIn(ExperimentalCoroutinesApi::class)
class FolderUploadFlowTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private class Repo : FakeDocDistRepository(MutableSharedFlow()) {
        override suspend fun folders(): ZillitResult<List<LibraryFolder>> =
            ZillitResult.Success(listOf(LibraryFolder(id = "f1", name = "Week 1", folderDate = "2026-09-01")))
    }

    private class Host(var picked: LocalFolderTree? = null) : DocDistHost {
        override suspend fun pickFolder(): LocalFolderTree? = picked
        override suspend fun pdfThumbnail(pdf: ByteArray): ByteArray? = "jpeg".encodeToByteArray()
    }

    private fun entry(path: String, type: String = "application/pdf", bytes: ByteArray? = ByteArray(3)) =
        FolderEntry(path, type, sizeBytes = 3) { bytes }

    private fun vm(repo: Repo, host: Host, canPost: Boolean = true) = DocDistViewModel(
        repository = repo,
        viewer = { DocDistViewer(userId = "u1", ready = true, canPost = canPost) },
        today = { LocalDate(2026, 10, 7) },
        host = host,
    ).also { it.start() }

    private fun TestScope.started(repo: Repo, host: Host): DocDistViewModel {
        val model = vm(repo, host)
        runCurrent()
        return model
    }

    private fun TestScope.inFolder(repo: Repo, host: Host): DocDistViewModel =
        started(repo, host).also {
            it.onEvent(DocDistEvent.OpenFolder("f1"))
            runCurrent()
        }

    // -- picking and confirming -----------------------------------------------------------------------

    @Test
    fun `a picked folder is planned and confirmed before anything is sent`() = runTest(dispatcher) {
        val repo = Repo()
        val host = Host(LocalFolderTree(listOf(entry("Docs/a.pdf"), entry("Docs/Sub/b.pdf"))))
        val model = started(repo, host)

        model.onEvent(DocDistEvent.PickAndUploadFolder)
        runCurrent()

        val pending = assertNotNull(model.state.value.folderUpload)
        assertEquals(listOf("Docs", "Docs/Sub"), pending.plan.folders)
        assertEquals(2, pending.plan.fileCount)
        assertTrue(repo.createdFolders.isEmpty() && repo.uploads.isEmpty(), "nothing is sent until the user confirms")
        // At the root: no parent, and today stands in for a parent's date.
        assertNull(pending.parentId)
        assertEquals("2026-10-07", pending.date)
    }

    @Test
    fun `cancelling sends nothing and closes the dialog`() = runTest(dispatcher) {
        val repo = Repo()
        val model = started(repo, Host(LocalFolderTree(listOf(entry("Docs/a.pdf")))))
        model.onEvent(DocDistEvent.PickAndUploadFolder)
        runCurrent()

        model.onEvent(DocDistEvent.CancelFolderUpload)

        assertNull(model.state.value.folderUpload)
        assertTrue(repo.createdFolders.isEmpty() && repo.uploads.isEmpty())
    }

    @Test
    fun `an unsupported file is listed as skipped and never uploaded`() = runTest(dispatcher) {
        val repo = Repo()
        val tree = LocalFolderTree(listOf(entry("Docs/a.pdf"), entry("Docs/run.exe", "application/octet-stream")))
        val model = started(repo, Host(tree))
        model.onEvent(DocDistEvent.PickAndUploadFolder)
        runCurrent()

        assertEquals(listOf("Docs/run.exe"), model.state.value.folderUpload?.rejected?.map { it.relativePath })
        model.onEvent(DocDistEvent.ConfirmFolderUpload)
        runCurrent()

        assertEquals(listOf("a.pdf"), repo.uploads.map { it.name })
    }

    @Test
    fun `folders are created parent first and each file lands in the folder that holds it`() = runTest(dispatcher) {
        val repo = Repo()
        val tree = LocalFolderTree(listOf(entry("Docs/a.pdf"), entry("Docs/Sub/b.pdf"), entry("Docs/Sub/c.pdf")))
        val model = started(repo, Host(tree))
        model.onEvent(DocDistEvent.PickAndUploadFolder)
        runCurrent()
        model.onEvent(DocDistEvent.ConfirmFolderUpload)
        runCurrent()

        assertEquals(listOf("Docs" to null, "Sub" to "folder-Docs"), repo.createdFolders.map { it.name to it.parentId })
        assertEquals(
            listOf("a.pdf" to "folder-Docs", "b.pdf" to "folder-Sub", "c.pdf" to "folder-Sub"),
            repo.uploads.map { it.name to it.folderId },
        )
        assertNull(model.state.value.upload, "the progress card goes away when it is done")
        assertEquals("Uploaded 3 files into 2 folders", model.state.value.notice)
    }

    @Test
    fun `every request of one action carries the same batch and the full total`() = runTest(dispatcher) {
        val repo = Repo()
        val tree = LocalFolderTree(listOf(entry("A/1.pdf"), entry("A/B/2.pdf")))
        val model = started(repo, Host(tree))
        model.onEvent(DocDistEvent.PickAndUploadFolder)
        runCurrent()
        model.onEvent(DocDistEvent.ConfirmFolderUpload)
        runCurrent()

        val ids = (repo.createdFolders.map { it.batchId } + repo.uploads.map { it.batchId }).toSet()
        assertEquals(1, ids.size)
        // 2 folders + 2 files.
        assertEquals(setOf(4), (repo.createdFolders.map { it.batchTotal } + repo.uploads.map { it.batchTotal }).toSet())
    }

    @Test
    fun `the date chosen in the dialog is on every folder`() = runTest(dispatcher) {
        val repo = Repo()
        val model = started(repo, Host(LocalFolderTree(listOf(entry("A/1.pdf"), entry("A/B/2.pdf")))))
        model.onEvent(DocDistEvent.PickAndUploadFolder)
        runCurrent()
        model.onEvent(DocDistEvent.EditFolderUploadDate("2026-12-25"))
        model.onEvent(DocDistEvent.ConfirmFolderUpload)
        runCurrent()

        assertEquals(setOf("2026-12-25"), repo.createdFolders.map { it.date }.toSet())
        assertEquals(setOf("2026-12-25"), repo.uploads.map { it.date }.toSet())
    }

    @Test
    fun `inside a folder the new tree goes under it, dated like it`() = runTest(dispatcher) {
        val repo = Repo()
        val host = Host(LocalFolderTree(listOf(entry("Docs/a.pdf"))))
        val model = inFolder(repo, host)
        model.onEvent(DocDistEvent.PickAndUploadFolder)
        runCurrent()

        val pending = assertNotNull(model.state.value.folderUpload)
        assertEquals("f1", pending.parentId)
        assertEquals("2026-09-01", pending.date)
        assertEquals("Week 1", pending.parentLabel)
        model.onEvent(DocDistEvent.ConfirmFolderUpload)
        runCurrent()
        assertEquals("f1", repo.createdFolders.single().parentId)
    }

    // -- failures ----------------------------------------------------------------------------------------

    @Test
    fun `a folder that fails takes its whole subtree off the total at once`() = runTest(dispatcher) {
        val repo = Repo().also {
            it.folderOutcome = { name ->
                if (name == "A") {
                    ZillitResult.Failure(ZillitError.Validation("name taken"))
                } else {
                    ZillitResult.Success("folder-$name")
                }
            }
        }
        val tree = LocalFolderTree(listOf(entry("A/1.pdf"), entry("A/B/2.pdf"), entry("Z/3.pdf")))
        val model = started(repo, Host(tree))
        model.onEvent(DocDistEvent.PickAndUploadFolder)
        runCurrent()
        model.onEvent(DocDistEvent.ConfirmFolderUpload)
        runCurrent()

        // Plan: folders A, Z, A/B + files 1, 2, 3 = 6 requests.
        // A fails carrying 6; its subtree (B, 1.pdf, 2.pdf) and itself come off at once → 2.
        assertEquals(listOf("A" to 6, "Z" to 2), repo.createdFolders.map { it.name to it.batchTotal })
        // A/B is never attempted, and neither are A's files; Z's file carries the corrected total.
        assertEquals(listOf("3.pdf" to 2), repo.uploads.map { it.name to it.batchTotal })
    }

    @Test
    fun `a failed upload lowers the total for the files after it`() = runTest(dispatcher) {
        val repo = Repo().also {
            it.uploadOutcome = { file ->
                if (file.name == "a.pdf") {
                    ZillitResult.Failure(ZillitError.Validation("too big"))
                } else {
                    ZillitResult.Success(LibraryDocument("d", file.name))
                }
            }
        }
        val model = started(repo, Host(LocalFolderTree(listOf(entry("F/a.pdf"), entry("F/b.pdf")))))
        model.onEvent(DocDistEvent.PickAndUploadFolder)
        runCurrent()
        model.onEvent(DocDistEvent.ConfirmFolderUpload)
        runCurrent()

        // 1 folder + 2 files = 3; a.pdf failed, so b.pdf promises 2.
        assertEquals(listOf("a.pdf" to 3, "b.pdf" to 2), repo.uploads.map { it.name to it.batchTotal })
        // One file went up, into one folder.
        assertEquals("Uploaded 1 file into 1 folder", model.state.value.notice)
    }

    @Test
    fun `a file that can no longer be read is skipped and dropped from the total`() = runTest(dispatcher) {
        val repo = Repo()
        val tree = LocalFolderTree(listOf(entry("F/gone.pdf", bytes = null), entry("F/b.pdf")))
        val model = started(repo, Host(tree))
        model.onEvent(DocDistEvent.PickAndUploadFolder)
        runCurrent()
        model.onEvent(DocDistEvent.ConfirmFolderUpload)
        runCurrent()

        assertEquals(listOf("b.pdf" to 2), repo.uploads.map { it.name to it.batchTotal })
    }

    // -- covers --------------------------------------------------------------------------------------------

    @Test
    fun `a pdf is uploaded with a cover, a spreadsheet is not`() = runTest(dispatcher) {
        val repo = Repo()
        val tree = LocalFolderTree(
            listOf(
                entry("F/a.pdf"),
                entry("F/sheet.xlsx", "application/vnd.ms-excel"),
                entry("F/pic.png", "image/png"),
            ),
        )
        val model = started(repo, Host(tree))
        model.onEvent(DocDistEvent.PickAndUploadFolder)
        runCurrent()
        model.onEvent(DocDistEvent.ConfirmFolderUpload)
        runCurrent()

        assertEquals(
            listOf("a.pdf" to true, "sheet.xlsx" to false, "pic.png" to false),
            repo.uploads.map { it.name to it.hadThumbnail },
        )
    }

    // -- dropping ---------------------------------------------------------------------------------------------

    @Test
    fun `a folder dropped at the root is planned like a picked one`() = runTest(dispatcher) {
        val model = started(Repo(), Host())
        model.onEvent(DocDistEvent.DropFolder(LocalFolderTree(listOf(entry("Docs/a.pdf")))))
        runCurrent()
        assertEquals(listOf("Docs"), model.state.value.folderUpload?.plan?.folders)
    }

    @Test
    fun `loose files at the root are refused, with no dialog and no upload`() = runTest(dispatcher) {
        val repo = Repo()
        val model = started(repo, Host())
        model.onEvent(DocDistEvent.DropFolder(LocalFolderTree(listOf(entry("a.pdf")))))
        runCurrent()
        assertNull(model.state.value.folderUpload)
        assertTrue(repo.uploads.isEmpty())
    }

    @Test
    fun `loose files dropped with a folder are part of the same plan`() = runTest(dispatcher) {
        val model = inFolder(Repo(), Host())
        model.onEvent(DocDistEvent.DropFolder(LocalFolderTree(listOf(entry("Docs/a.pdf"), entry("loose.pdf")))))
        runCurrent()
        val plan = assertNotNull(model.state.value.folderUpload).plan
        assertEquals(listOf("Docs"), plan.folders)
        assertEquals(2, plan.fileCount)
    }

    @Test
    fun `loose files dropped into a folder upload straight away without confirmation`() = runTest(dispatcher) {
        val repo = Repo()
        val model = inFolder(repo, Host())
        model.onEvent(DocDistEvent.DropFolder(LocalFolderTree(listOf(entry("a.pdf"), entry("b.pdf")))))
        runCurrent()

        assertNull(model.state.value.folderUpload)
        assertEquals(listOf("a.pdf" to "f1", "b.pdf" to "f1"), repo.uploads.map { it.name to it.folderId })
        assertEquals(setOf(2), repo.uploads.map { it.batchTotal }.toSet())
    }

    @Test
    fun `an empty dropped folder is created`() = runTest(dispatcher) {
        val repo = Repo()
        val model = started(repo, Host())
        model.onEvent(DocDistEvent.DropFolder(LocalFolderTree(emptyList(), setOf("Empty"))))
        runCurrent()
        model.onEvent(DocDistEvent.ConfirmFolderUpload)
        runCurrent()
        assertEquals(listOf("Empty"), repo.createdFolders.map { it.name })
    }

    @Test
    fun `without posting rights nothing is planned`() = runTest(dispatcher) {
        val repo = Repo()
        val model = vm(repo, Host(LocalFolderTree(listOf(entry("Docs/a.pdf")))), canPost = false)
        runCurrent()
        model.onEvent(DocDistEvent.PickAndUploadFolder)
        runCurrent()
        assertNull(model.state.value.folderUpload)
    }

    @Test
    fun `a cancelled folder dialog plans nothing`() = runTest(dispatcher) {
        val model = started(Repo(), Host(picked = null))
        model.onEvent(DocDistEvent.PickAndUploadFolder)
        runCurrent()
        assertNull(model.state.value.folderUpload)
    }

}
