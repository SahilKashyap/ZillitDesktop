package com.zillit.desktop.feature.chat

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.zillit.desktop.feature.chat.ui.PosterMemory
import com.zillit.desktop.feature.chat.ui.PosterState
import com.zillit.desktop.feature.chat.ui.posterFrame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A poster tile's size is settled before its bytes arrive, so a thumbnail
 * landing mid-scroll changes pixels and never positions — the reversed
 * thread used to jump as bubbles grew from chip to picture.
 */
class PosterFrameTest {

    @Test
    fun `the wire's aspect is fitted into the tile's long edge`() {
        assertEquals(DpSize(240.dp, 180.dp), posterFrame(4000, 3000, portraitDefault = false))
        assertEquals(DpSize(180.dp, 240.dp), posterFrame(600, 800, portraitDefault = false))
        assertEquals(DpSize(240.dp, 240.dp), posterFrame(100, 100, portraitDefault = true))
    }

    @Test
    fun `a panorama keeps a readable short edge`() {
        val frame = posterFrame(4000, 400, portraitDefault = false)
        assertEquals(240.dp, frame.width)
        assertTrue(frame.height >= 96.dp, "short edge was ${frame.height}")
    }

    @Test
    fun `a poster miss outlives the row that learned it`() {
        // A row scrolled away and back used to start at Loading — a full tile
        // that collapsed to the chip one fetch later, which at the end of the
        // thread moved every row. The thread's memory answers at once.
        val memory = PosterMemory()
        assertNull(memory.known("s3/clip.mp4"), "never fetched: the tile waits")

        memory.keep("s3/clip.mp4", image = null)

        assertEquals(PosterState.Done(null), memory.known("s3/clip.mp4"))
        memory.keep("", image = null)
        assertNull(memory.known(""), "a file not yet in storage is never settled")
    }

    @Test
    fun `unknown dimensions take a page or a picture shape`() {
        val page = posterFrame(0, 0, portraitDefault = true)
        val picture = posterFrame(0, 0, portraitDefault = false)
        assertTrue(page.height > page.width, "a document stands upright")
        assertTrue(picture.width > picture.height, "a picture lies flat")
        assertEquals(page.width, picture.height)
    }
}
