package com.zillit.desktop.core.media

import kotlin.test.Test
import kotlin.test.assertEquals

/** The one Int every upload seam passes, read back as a phase and a percent. */
class UploadProgressTest {

    @Test
    fun `preparing, compressing and uploading read back apart`() {
        assertEquals(UploadProgress.Phase.Preparing, UploadProgress.phase(UploadProgress.PREPARING))
        assertEquals(UploadProgress.Phase.Compressing, UploadProgress.phase(UploadProgress.compressing(40)))
        assertEquals(40, UploadProgress.percent(UploadProgress.compressing(40)))
        assertEquals(UploadProgress.Phase.Uploading, UploadProgress.phase(72))
        assertEquals(72, UploadProgress.percent(72))
    }

    @Test
    fun `a finished compression is not mistaken for a finished upload`() {
        // The board reads 100 as "uploaded, the server is writing the post".
        assertEquals(UploadProgress.Phase.Compressing, UploadProgress.phase(UploadProgress.compressing(100)))
        assertEquals(100, UploadProgress.percent(UploadProgress.compressing(150)))
        assertEquals(0, UploadProgress.percent(UploadProgress.PREPARING))
    }
}
