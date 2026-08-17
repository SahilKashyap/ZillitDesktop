package com.zillit.desktop.feature.home

import com.zillit.desktop.feature.home.data.videoThumbnailJpeg
import kotlin.test.Test
import kotlin.test.assertNull

/**
 * The failure half of poster extraction — the half that must never throw.
 *
 * A real-frame test wants a real MP4 fixture; what a unit can pin is the
 * contract that matters to the post: undecodable input costs the poster, not
 * the upload.
 */
class VideoThumbnailTest {

    @Test
    fun `not a video means no poster, never a throw`() {
        assertNull(videoThumbnailJpeg(ByteArray(0)))
        assertNull(videoThumbnailJpeg(ByteArray(64) { (it * 3).toByte() }))
        assertNull(videoThumbnailJpeg("definitely a text file".encodeToByteArray()))
    }
}
