package com.zillit.desktop.feature.documentdistribution

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.documentdistribution.domain.Contact
import com.zillit.desktop.feature.documentdistribution.domain.DocDistCrewMember
import com.zillit.desktop.feature.documentdistribution.domain.DocDistHost
import com.zillit.desktop.feature.documentdistribution.domain.DocDistViewer
import com.zillit.desktop.feature.documentdistribution.domain.LibraryDocument
import com.zillit.desktop.feature.documentdistribution.domain.LibraryPage
import com.zillit.desktop.feature.documentdistribution.domain.LibraryQuery
import com.zillit.desktop.feature.documentdistribution.ui.DocDistEvent
import com.zillit.desktop.feature.documentdistribution.ui.DocDistViewModel
import com.zillit.desktop.feature.documentdistribution.ui.MergeAction
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

/** "Merge PDFs to download or print": the dialog's flow, from selection to saved file. */
@OptIn(ExperimentalCoroutinesApi::class)
class MergeFlowTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private class Repo : FakeDocDistRepository(MutableSharedFlow()) {
        override suspend fun documents(query: LibraryQuery): ZillitResult<LibraryPage> = ZillitResult.Success(
            LibraryPage(
                listOf(
                    LibraryDocument("d1", "a.pdf", contentType = "application/pdf"),
                    LibraryDocument("d2", "b.pdf", contentType = "application/pdf"),
                    LibraryDocument("d3", "sheet.xlsx", contentType = "application/vnd.ms-excel"),
                ),
                total = 3,
            ),
        )
        override suspend fun contacts(): ZillitResult<List<Contact>> =
            ZillitResult.Success(listOf(Contact("vendor@y.com", name = "Vendor")))
    }

    private class Host : DocDistHost {
        val saved = mutableListOf<Pair<String, ByteArray>>()
        val printed = mutableListOf<String>()
        val joins = mutableListOf<List<ByteArray>>()
        var printFails = false
        override fun crew() = listOf(
            DocDistCrewMember("u1", "Me", mailboxAddress = "me@x.com", department = "Production"),
            DocDistCrewMember("u2", "Rory", mailboxAddress = "rory@x.com", department = "Camera"),
        )
        override suspend fun saveToDownloads(fileName: String, bytes: ByteArray): ZillitResult<String> {
            saved += fileName to bytes
            return ZillitResult.Success("/Downloads/$fileName")
        }
        override suspend fun joinPdfs(parts: List<ByteArray>): ZillitResult<ByteArray> {
            joins += parts
            return ZillitResult.Success("%PDF-joined".encodeToByteArray())
        }
        override suspend fun openForPrinting(fileName: String, bytes: ByteArray): ZillitResult<Unit> {
            if (printFails) return ZillitResult.Failure(ZillitError.Validation("no viewer"))
            printed += fileName
            return ZillitResult.Success(Unit)
        }
    }

    private fun vm(repo: Repo, host: Host, canDownload: Boolean = true) = DocDistViewModel(
        repository = repo,
        viewer = { DocDistViewer(userId = "u1", userEmail = "me@x.com", ready = true, canDownload = canDownload) },
        today = { LocalDate(2026, 10, 7) },
        host = host,
    ).also { it.start() }

    /** Opens the dialog over the two PDFs and the spreadsheet. */
    private fun TestScope.opened(repo: Repo, host: Host, canDownload: Boolean = true): DocDistViewModel {
        val model = vm(repo, host, canDownload)
        runCurrent()
        listOf("d1", "d2", "d3").forEach { model.onEvent(DocDistEvent.ToggleDocument(it)) }
        model.onEvent(DocDistEvent.OpenMerge)
        runCurrent()
        return model
    }

    @Test
    fun `the dialog opens on the whole selection, with nobody chosen`() = runTest(dispatcher) {
        val model = opened(Repo(), Host())
        val merge = assertNotNull(model.state.value.merge)
        assertEquals(3, merge.documents.size)
        assertTrue(merge.picked.isEmpty())
        assertEquals(false, merge.includeSelf)
        // On by default, so opting out is the deliberate act.
        assertEquals(true, merge.watermarkSelf)
        assertEquals("Vendor", model.state.value.contacts.single().name)
    }

    @Test
    fun `without download rights it does not open`() = runTest(dispatcher) {
        val model = opened(Repo(), Host(), canDownload = false)
        assertNull(model.state.value.merge)
    }

    @Test
    fun `crew copies go in one request and are saved under the watermarked name`() = runTest(dispatcher) {
        val repo = Repo()
        val host = Host()
        val model = opened(repo, host)

        model.onEvent(DocDistEvent.MergeToggle("rory@x.com"))
        model.onEvent(DocDistEvent.MergeToggle("VENDOR@y.com"))
        model.onEvent(DocDistEvent.RunMerge(MergeAction.Download))
        runCurrent()

        val call = repo.merges.single()
        // The spreadsheet is left out; the PDFs keep listing order.
        assertEquals(listOf("d1", "d2"), call.documentIds)
        assertEquals(listOf("rory@x.com", "vendor@y.com"), call.recipients?.map { it.email })
        assertEquals(listOf("Rory", "Vendor"), call.recipients?.map { it.watermarkText })
        // The project's settings have loaded, so the appearance rides along.
        assertNotNull(call.style)
        assertEquals("documents-watermarked-2026-10-07.pdf", host.saved.single().first)
        assertNull(model.state.value.merge)
    }

    @Test
    fun `your stamped copy leads the recipients`() = runTest(dispatcher) {
        val repo = Repo()
        val model = opened(repo, Host())

        model.onEvent(DocDistEvent.MergeIncludeSelf(true))
        model.onEvent(DocDistEvent.MergeToggle("rory@x.com"))
        model.onEvent(DocDistEvent.RunMerge(MergeAction.Download))
        runCurrent()

        assertEquals(listOf("me@x.com", "rory@x.com"), repo.merges.single().recipients?.map { it.email })
    }

    @Test
    fun `a clean copy plus crew copies is two requests joined, the clean one first`() = runTest(dispatcher) {
        val repo = Repo()
        val host = Host()
        val model = opened(repo, host)

        model.onEvent(DocDistEvent.MergeIncludeSelf(true))
        model.onEvent(DocDistEvent.MergeWatermarkSelf(false))
        model.onEvent(DocDistEvent.MergeToggle("rory@x.com"))
        model.onEvent(DocDistEvent.RunMerge(MergeAction.Download))
        runCurrent()

        assertEquals(2, repo.merges.size)
        assertNull(repo.merges[0].recipients)
        assertEquals(listOf("rory@x.com"), repo.merges[1].recipients?.map { it.email })
        assertEquals(1, host.joins.size)
        assertEquals(2, host.joins.single().size)
        assertEquals("%PDF-joined", host.saved.single().second.decodeToString())
    }

    @Test
    fun `a clean copy alone is a plain merge`() = runTest(dispatcher) {
        val repo = Repo()
        val host = Host()
        val model = opened(repo, host)

        model.onEvent(DocDistEvent.MergeIncludeSelf(true))
        model.onEvent(DocDistEvent.MergeWatermarkSelf(false))
        model.onEvent(DocDistEvent.RunMerge(MergeAction.Download))
        runCurrent()

        assertNull(repo.merges.single().recipients)
        assertEquals("documents-merged-2026-10-07.pdf", host.saved.single().first)
    }

    @Test
    fun `nothing happens until somebody is chosen`() = runTest(dispatcher) {
        val repo = Repo()
        val model = opened(repo, Host())

        model.onEvent(DocDistEvent.RunMerge(MergeAction.Download))
        runCurrent()

        assertTrue(repo.merges.isEmpty())
        assertNotNull(model.state.value.merge)
    }

    @Test
    fun `a refusal stays in the dialog so the run can be retried`() = runTest(dispatcher) {
        val repo = Repo().also { it.mergeResult = ZillitResult.Failure(ZillitError.Validation("Too big")) }
        val model = opened(repo, Host())

        model.onEvent(DocDistEvent.MergeToggle("rory@x.com"))
        model.onEvent(DocDistEvent.RunMerge(MergeAction.Download))
        runCurrent()

        val merge = assertNotNull(model.state.value.merge)
        assertEquals("Too big", merge.error)
        assertNull(merge.busy)
        assertEquals(setOf("rory@x.com"), merge.picked)
    }

    @Test
    fun `print opens the viewer from a temporary file and closes the dialog`() = runTest(dispatcher) {
        val repo = Repo()
        val host = Host()
        val model = opened(repo, host)

        model.onEvent(DocDistEvent.MergeToggle("rory@x.com"))
        model.onEvent(DocDistEvent.RunMerge(MergeAction.Print))
        runCurrent()

        assertEquals(listOf("documents-watermarked-2026-10-07.pdf"), host.printed)
        assertTrue(host.saved.isEmpty())
        assertNull(model.state.value.merge)
    }

    @Test
    fun `a viewer that will not open is reported in the dialog`() = runTest(dispatcher) {
        val host = Host().also { it.printFails = true }
        val model = opened(Repo(), host)

        model.onEvent(DocDistEvent.MergeToggle("rory@x.com"))
        model.onEvent(DocDistEvent.RunMerge(MergeAction.Print))
        runCurrent()

        assertEquals("no viewer", model.state.value.merge?.error)
    }

    @Test
    fun `select all shown ticks what the filter leaves, not the people it hides`() = runTest(dispatcher) {
        val model = opened(Repo(), Host())

        model.onEvent(DocDistEvent.MergeSearch("vend"))
        model.onEvent(DocDistEvent.MergeToggleShown(true))
        assertEquals(setOf("vendor@y.com"), model.state.value.merge?.picked)

        model.onEvent(DocDistEvent.MergeSearch(""))
        model.onEvent(DocDistEvent.MergeToggleShown(true))
        assertEquals(setOf("vendor@y.com", "rory@x.com"), model.state.value.merge?.picked)

        model.onEvent(DocDistEvent.MergeToggleShown(false))
        assertTrue(model.state.value.merge?.picked.orEmpty().isEmpty())
    }

    @Test
    fun `closing clears the dialog`() = runTest(dispatcher) {
        val model = opened(Repo(), Host())
        model.onEvent(DocDistEvent.CloseMerge)
        assertNull(model.state.value.merge)
    }
}
