package com.zillit.desktop.feature.callsheet

import com.zillit.desktop.feature.callsheet.domain.CallSheetViewer
import com.zillit.desktop.feature.callsheet.ui.ListEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The PDF preview's own Download, and the right it asks for (ZL-21947).
 *
 * The preview is the only way a call sheet's PDF leaves this tool, and until
 * 2026-10-22 nothing asked for a right on the way out — the action did not
 * exist and the handler had no gate. These are the two halves of that fix.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SheetPdfDownloadTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun before() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun after() = Dispatchers.resetMain()

    /** An author who may post but was never granted the download right. */
    private val noDownload = Samples.author

    /** The same author with `callsheet_tool.download_access` issued. */
    private val mayDownload = Samples.author.copy(canDownload = true)

    @Test
    fun `the right fails closed, so an unanswered rights list cannot hand out the file`() {
        // The one way a PDF leaves this tool is the preview, so an unknown
        // right must not permit the save — the same rule canPost follows.
        assertEquals(false, CallSheetViewer().canDownload)
        assertEquals(false, noDownload.canDownload)
    }

    @Test
    fun `a refused Download closes the preview and says so, rather than saving`() = runTest(dispatcher) {
        val harness = SheetHarness(dispatcher)
        val model = harness.start(this, viewer = noDownload)

        model.onEvent(ListEvent.View(Samples.row("s1", "Day 1")))
        settle()
        assertNotNull(model.state.value.pdf, "the preview opens: reading is not downloading")

        model.onEvent(ListEvent.DownloadPdf)
        settle()

        // The preview closes FIRST: the web found its own admin-request dialog
        // opening behind the viewer, where the user saw nothing happen.
        assertNull(model.state.value.pdf, "the preview closes so the request is not hidden behind it")
        val said = harness.toasts.lastOrNull()
        assertNotNull(said, "a refused press has to say something")
        assertTrue(said.isError)
        assertTrue(
            said.message.contains("download", ignoreCase = true),
            "the refusal names the right it wants: ${said.message}",
        )
    }

    @Test
    fun `with the right, Download saves the open PDF and keeps the preview up`() = runTest(dispatcher) {
        val harness = SheetHarness(dispatcher)
        val model = harness.start(this, viewer = mayDownload)

        model.onEvent(ListEvent.View(Samples.row("s1", "Day 1")))
        settle()
        val opened = assertNotNull(model.state.value.pdf)
        assertNotNull(opened.bytes, "the viewer holds the bytes it drew")

        model.onEvent(ListEvent.DownloadPdf)
        settle()

        // Saved, and the reader is left where they were — a download is not a
        // reason to take the document away.
        assertNotNull(model.state.value.pdf, "the preview stays open on a permitted save")
        val said = assertNotNull(harness.toasts.lastOrNull())
        assertEquals(false, said.isError, "the save reports success: ${said.message}")
    }
}
