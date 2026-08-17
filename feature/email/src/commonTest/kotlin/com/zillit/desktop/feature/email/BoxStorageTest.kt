package com.zillit.desktop.feature.email

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.email.domain.AttachmentUploader
import com.zillit.desktop.feature.email.domain.BOX_ROOT_FOLDER
import com.zillit.desktop.feature.email.domain.BoxSettings
import com.zillit.desktop.feature.email.domain.RoutingAttachmentUploader
import com.zillit.desktop.feature.email.domain.StorageKind
import com.zillit.desktop.feature.email.domain.StoredFile
import com.zillit.desktop.feature.email.domain.storageKindOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Productions on Box rather than S3.
 *
 * Which storage a production uses is a server setting, so the choice cannot be
 * made when the app starts — and getting the default wrong would send a
 * production's files to a tenant it does not have.
 */
class BoxStorageTest {

    private class Recording(private val label: String) : AttachmentUploader {
        var calls = 0
            private set

        override suspend fun upload(
            fileName: String,
            contentType: String,
            bytes: ByteArray,
            onProgress: (Int) -> Unit,
        ): ZillitResult<StoredFile> {
            calls++
            return ZillitResult.Success(
                StoredFile(label, label, label, fileName, contentType, bytes.size.toLong()),
            )
        }
    }

    // -- reading the setting -----------------------------------------------

    @Test
    fun `BOX means Box, whatever its case`() {
        assertEquals(StorageKind.Box, storageKindOf("BOX"))
        assertEquals(StorageKind.Box, storageKindOf("box"))
        assertEquals(StorageKind.Box, storageKindOf("Box"))
    }

    @Test
    fun `anything else means AWS`() {
        // Defaulting the other way would send files to a Box tenant the
        // production may not have.
        assertEquals(StorageKind.Aws, storageKindOf(null))
        assertEquals(StorageKind.Aws, storageKindOf(""))
        assertEquals(StorageKind.Aws, storageKindOf("AWS"))
        assertEquals(StorageKind.Aws, storageKindOf("S3"))
        assertEquals(StorageKind.Aws, storageKindOf("BOXED"))
    }

    // -- routing -----------------------------------------------------------

    @Test
    fun `an AWS production uploads to S3`() = runTest {
        val aws = Recording("aws")
        val box = Recording("box")
        val uploader = RoutingAttachmentUploader({ StorageKind.Aws }, aws, box)

        uploader.upload("a.pdf", "application/pdf", ByteArray(1))

        assertEquals(1, aws.calls)
        assertEquals(0, box.calls)
    }

    @Test
    fun `a Box production uploads to Box`() = runTest {
        val aws = Recording("aws")
        val box = Recording("box")
        val uploader = RoutingAttachmentUploader({ StorageKind.Box }, aws, box)

        uploader.upload("a.pdf", "application/pdf", ByteArray(1))

        assertEquals(0, aws.calls)
        assertEquals(1, box.calls)
    }

    @Test
    fun `the choice is re-read per upload, not captured`() = runTest {
        // A production switch changes the storage underneath a long-lived
        // composer, and the next attachment must follow it.
        var kind = StorageKind.Aws
        val aws = Recording("aws")
        val box = Recording("box")
        val uploader = RoutingAttachmentUploader({ kind }, aws, box)

        uploader.upload("a.pdf", "application/pdf", ByteArray(1))
        kind = StorageKind.Box
        uploader.upload("b.pdf", "application/pdf", ByteArray(1))

        assertEquals(1, aws.calls)
        assertEquals(1, box.calls)
    }

    // -- settings ----------------------------------------------------------

    @Test
    fun `Box needs an enterprise id to be usable`() {
        assertFalse(BoxSettings(enterpriseClientId = "").isUsable)
        assertTrue(BoxSettings(enterpriseClientId = "ent-1").isUsable)
    }

    @Test
    fun `the folder defaults to Box's root, not to an empty parent`() {
        // The web sends an empty parent id for email attachments, which Box's
        // API does not document as valid. Copying that would be copying what
        // looks like an oversight.
        assertEquals(BOX_ROOT_FOLDER, BoxSettings("ent-1").folderId)
        assertEquals("0", BOX_ROOT_FOLDER)
    }
}
