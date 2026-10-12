@file:Suppress("MaxLineLength") // Fixtures read best on one line.

package com.zillit.desktop.feature.selectstills

import com.zillit.desktop.feature.selectstills.data.FileStatus
import com.zillit.desktop.feature.selectstills.data.UploadItem
import com.zillit.desktop.feature.selectstills.data.queueRows
import com.zillit.desktop.feature.selectstills.domain.CappedSection
import com.zillit.desktop.feature.selectstills.domain.ZOOM_FIT
import com.zillit.desktop.feature.selectstills.domain.ZOOM_MAX
import com.zillit.desktop.feature.selectstills.domain.ZoomView
import com.zillit.desktop.feature.selectstills.domain.idsBetween
import com.zillit.desktop.feature.selectstills.domain.limitsList
import com.zillit.desktop.feature.selectstills.domain.limitsSummary
import com.zillit.desktop.feature.selectstills.domain.panBy
import com.zillit.desktop.feature.selectstills.domain.settle
import com.zillit.desktop.feature.selectstills.domain.zoomAt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The arithmetic behind the web's 8–9 October changes: the lightbox's zoom
 * (`lib/zoom.js`), a shift-click's run (`idsBetween`), an allowance behind an
 * (i) (`limitsList`), and the order an upload's files are listed in
 * (`queueRows`).
 */
class StillsSyncLogicTest {

    // -- zoom --------------------------------------------------------------------------

    @Test
    fun `zooming in keeps what is under the pointer where it is`() {
        val view = ZOOM_FIT.zoomAt(2f, 300f, 200f, 600f, 400f)
        assertEquals(ZoomView(2f, -300f, -200f), view)
        // The spot of the picture under the pointer is the same spot after.
        assertEquals(300f, (300f - view.x) / view.z)
        assertEquals(200f, (200f - view.y) / view.z)
    }

    @Test
    fun `the picture always covers its frame, so there is no gap to drag into`() {
        val zoomed = ZoomView(2f, 0f, 0f)
        // Dragged right and down past the top-left corner: held there.
        assertEquals(ZoomView(2f, 0f, 0f), zoomed.panBy(50f, 50f, 600f, 400f))
        // Dragged past the far corner: held at the frame's size times (1 - z).
        assertEquals(ZoomView(2f, -600f, -400f), zoomed.panBy(-5000f, -5000f, 600f, 400f))
    }

    @Test
    fun `zoom stays between the whole photo and six times it`() {
        assertEquals(ZOOM_MAX, ZOOM_FIT.zoomAt(50f, 0f, 0f, 600f, 400f).z)
        // Out past the whole photo is the whole photo, centred.
        assertEquals(ZOOM_FIT, ZoomView(2f, -100f, -100f).zoomAt(0.5f, 10f, 10f, 600f, 400f))
        assertTrue(!ZOOM_FIT.zoomed)
    }

    @Test
    fun `a frame with no size yet gives a view with no stray offset`() {
        assertEquals(ZoomView(3f, 0f, 0f), ZoomView(3f, 40f, -40f).settle(0f, 0f))
    }

    // -- a shift-click's run -----------------------------------------------------------

    @Test
    fun `a shift-click takes everything between, whichever way round`() {
        val ids = listOf("a", "b", "c", "d", "e")
        assertEquals(listOf("b", "c", "d"), idsBetween(ids, "b", "d"))
        assertEquals(listOf("b", "c", "d"), idsBetween(ids, "d", "b"))
        // The photo it started from has gone: just the one clicked.
        assertEquals(listOf("c"), idsBetween(ids, "gone", "c"))
        assertEquals(emptyList(), idsBetween(ids, "a", "gone"))
    }

    // -- an allowance behind an (i) ----------------------------------------------------

    @Test
    fun `only the capped sections are listed, in order, and 0 is a cap`() {
        assertEquals(
            listOf(CappedSection("1", 2), CappedSection("3", 0), CappedSection("6+", 1)),
            limitsList(mapOf("6+" to 1, "3" to 0, "1" to 2)),
        )
        assertEquals(emptyList(), limitsList(null))
        assertEquals("Solo 2 · 3 people 0", limitsSummary(mapOf("3" to 0, "1" to 2)) { key -> if (key.endsWith("_1")) "Solo" else "3 people" })
    }

    // -- an upload's files -------------------------------------------------------------

    private fun item(name: String, status: FileStatus, order: Int) =
        UploadItem(ref = name, uniqueId = name, order = order, name = name, size = 1, type = "image/jpeg", path = "/$name", status = status)

    @Test
    fun `what needs a person comes first, then what moves, then the latest arrived, then what waits`() {
        val items = listOf(
            item("1-done", FileStatus.Done, 1),
            item("2-uploaded", FileStatus.Uploaded, 2),
            item("3-moving", FileStatus.Uploading, 3),
            item("4-failed", FileStatus.Failed, 4),
            item("5-signing", FileStatus.Signing, 5),
            item("6-waiting", FileStatus.Waiting, 6),
            item("7-duplicate", FileStatus.Duplicate, 7),
            item("8-waiting", FileStatus.Waiting, 8),
        )
        assertEquals(
            listOf("4-failed", "7-duplicate", "3-moving", "5-signing", "2-uploaded", "1-done", "6-waiting", "8-waiting"),
            queueRows(items).map { it.name },
        )
    }

    @Test
    fun `every file is in the list, whatever its state`() {
        val items = FileStatus.entries.mapIndexed { index, status -> item("f$index", status, index) }
        assertEquals(items.map { it.name }.toSet(), queueRows(items).map { it.name }.toSet())
        assertEquals(items.size, queueRows(items).size)
    }
}
