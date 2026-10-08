package com.zillit.desktop.feature.documentdistribution

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.documentdistribution.domain.DocDistHost
import com.zillit.desktop.feature.documentdistribution.domain.DocDistViewer
import com.zillit.desktop.feature.documentdistribution.domain.LibraryDocument
import com.zillit.desktop.feature.documentdistribution.domain.LibraryPage
import com.zillit.desktop.feature.documentdistribution.domain.LibraryQuery
import com.zillit.desktop.feature.documentdistribution.ui.DocDistEvent
import com.zillit.desktop.feature.documentdistribution.ui.DocDistViewModel
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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

/** The PDF preview draws its pages one at a time, so page 1 is up before the rest are done. */
@OptIn(ExperimentalCoroutinesApi::class)
class PdfPreviewFlowTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private class Repo : FakeDocDistRepository(MutableSharedFlow()) {
        override suspend fun documents(query: LibraryQuery): ZillitResult<LibraryPage> = ZillitResult.Success(
            LibraryPage(listOf(LibraryDocument("d1", "a.pdf", contentType = "application/pdf")), total = 1),
        )
        override suspend fun documentBytes(document: LibraryDocument): ZillitResult<ByteArray> =
            ZillitResult.Success("%PDF-fake".encodeToByteArray())
    }

    private class Host(
        private val pages: Int,
        private val failFrom: Int = Int.MAX_VALUE,
    ) : DocDistHost {
        val drawn = mutableListOf<Pair<Int, Int>>()
        var allAtOnce = 0
        override suspend fun pdfPageCount(pdf: ByteArray): Int = pages
        override suspend fun renderPdfPage(pdf: ByteArray, page: Int, widthPx: Int): ByteArray? {
            if (page >= failFrom) return null
            drawn += page to widthPx
            return byteArrayOf(page.toByte())
        }
        override fun renderPdfPages(pdf: ByteArray, targetWidthPx: Int): ZillitResult<List<ByteArray>> {
            allAtOnce++
            return ZillitResult.Success(listOf(byteArrayOf(1), byteArrayOf(2)))
        }
    }

    private fun TestScope.opened(host: Host): DocDistViewModel {
        val model = DocDistViewModel(
            repository = Repo(),
            viewer = { DocDistViewer(userId = "u1", ready = true) },
            today = { LocalDate(2026, 10, 8) },
            host = host,
        )
        model.start()
        runCurrent()
        model.onEvent(DocDistEvent.OpenDocument("d1"))
        runCurrent()
        return model
    }

    @Test
    fun `the page count is known up front and every page arrives in order`() = runTest(dispatcher) {
        val model = opened(Host(pages = 4))
        val preview = assertNotNull(model.state.value.preview)
        assertEquals(4, preview.pageCount)
        assertEquals(listOf<Byte>(1, 2, 3, 4), preview.pages.map { it[0] })
        assertEquals(false, preview.loading)
    }

    @Test
    fun `pages are drawn sharp enough for a high-density screen`() = runTest(dispatcher) {
        val host = Host(pages = 2)
        opened(host)
        assertEquals(setOf(1800), host.drawn.map { it.second }.toSet())
    }

    @Test
    fun `a page that cannot be drawn stops the rest rather than leaving a hole`() = runTest(dispatcher) {
        val model = opened(Host(pages = 5, failFrom = 3))
        val preview = assertNotNull(model.state.value.preview)
        assertEquals(5, preview.pageCount)
        assertEquals(2, preview.pages.size)
    }

    @Test
    fun `closing the preview stops the drawing`() = runTest(dispatcher) {
        val host = Host(pages = 50)
        val model = DocDistViewModel(
            repository = Repo(),
            viewer = { DocDistViewer(userId = "u1", ready = true) },
            today = { LocalDate(2026, 10, 8) },
            host = host,
        )
        model.start()
        runCurrent()
        model.onEvent(DocDistEvent.OpenDocument("d1"))
        model.onEvent(DocDistEvent.ClosePreview)
        runCurrent()
        assertNull(model.state.value.preview)
        assertEquals(true, host.drawn.size < 50)
    }

    @Test
    fun `a host that cannot count draws every page at once, as before`() = runTest(dispatcher) {
        val host = Host(pages = 0)
        val model = opened(host)
        val preview = assertNotNull(model.state.value.preview)
        assertEquals(1, host.allAtOnce)
        assertEquals(2, preview.pages.size)
        assertEquals(0, preview.pageCount)
    }
}
