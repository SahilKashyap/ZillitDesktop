package com.zillit.desktop.feature.email

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.email.domain.AttachmentStore
import com.zillit.desktop.feature.email.domain.EmailAttachment
import com.zillit.desktop.feature.email.ui.AttachmentDownload
import com.zillit.desktop.feature.email.ui.AttachmentDownloader
import com.zillit.desktop.feature.email.ui.PreviewBody
import com.zillit.desktop.feature.email.ui.renderAttachmentPreview
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Downloading an attachment.
 *
 * The two halves fail differently — the server refusing the file and the disk
 * refusing the write are not the same problem — and the user should be told
 * which happened.
 */
class AttachmentDownloadTest {

    private val attachment = EmailAttachment(id = "a1", fileName = "call-sheet.pdf", sizeBytes = 1024)

    private class RecordingStore(private val fails: Boolean = false) : AttachmentStore {
        var savedName: String? = null
        var savedBytes: ByteArray? = null

        override suspend fun save(fileName: String, bytes: ByteArray): ZillitResult<String> {
            savedName = fileName
            savedBytes = bytes
            return if (fails) {
                ZillitResult.Failure(ZillitError.Storage(technical = "ENOSPC", userMessage = "Disk is full."))
            } else {
                ZillitResult.Success("/Users/x/Downloads/$fileName")
            }
        }
    }

    private fun downloader(server: FakeMailServer, store: AttachmentStore?) =
        AttachmentDownloader(server, store)

    private suspend fun AttachmentDownloader.fetch() =
        download(attachment, messageId = "m1", folderName = "INBOX")

    @Test
    fun `an attachment is fetched, decoded and saved`() = runTest {
        val store = RecordingStore()
        val downloader = downloader(FakeMailServer(), store)

        downloader.fetch()

        assertEquals("call-sheet.pdf", store.savedName)
        assertEquals("Hello", store.savedBytes?.decodeToString())
        val state = assertIs<AttachmentDownload.Saved>(downloader.state.value["a1"])
        assertEquals("/Users/x/Downloads/call-sheet.pdf", state.path)
    }

    @Test
    fun `a data URI payload is decoded`() = runTest {
        val store = RecordingStore()
        val server = FakeMailServer(attachmentPayload = "data:application/pdf;base64,SGVsbG8=")

        downloader(server, store).fetch()

        assertEquals("Hello", store.savedBytes?.decodeToString())
    }

    @Test
    fun `clicking twice does not download twice`() = runTest {
        // A second copy would land as "call-sheet (2).pdf", which reads as a bug.
        val server = FakeMailServer()
        val downloader = downloader(server, RecordingStore())

        downloader.fetch()
        downloader.fetch()
        downloader.fetch()

        assertEquals(1, server.attachmentCalls)
    }

    @Test
    fun `a server refusal is reported as a server refusal`() = runTest {
        val store = RecordingStore()
        val server = FakeMailServer().apply { attachmentFails = true }

        val downloader = downloader(server, store)
        downloader.fetch()

        assertIs<AttachmentDownload.Failed>(downloader.state.value["a1"])
        assertNull(store.savedBytes, "nothing should have been written")
    }

    @Test
    fun `a disk failure is reported separately from a server failure`() = runTest {
        val downloader = downloader(FakeMailServer(), RecordingStore(fails = true))

        downloader.fetch()

        val failed = assertIs<AttachmentDownload.Failed>(downloader.state.value["a1"])
        assertEquals("Disk is full.", failed.reason)
    }

    @Test
    fun `an empty payload fails rather than writing a zero-byte file`() = runTest {
        val store = RecordingStore()
        val downloader = downloader(FakeMailServer(attachmentPayload = ""), store)

        downloader.fetch()

        assertIs<AttachmentDownload.Failed>(downloader.state.value["a1"])
        assertNull(store.savedBytes)
    }

    @Test
    fun `with no store configured nothing is attempted`() = runTest {
        // A build without filesystem access should not offer a download it
        // cannot finish.
        val server = FakeMailServer()
        val downloader = downloader(server, store = null)

        downloader.fetch()

        assertEquals(0, server.attachmentCalls)
        assertNull(downloader.state.value["a1"])
    }

    // -- preview ---------------------------------------------------------------

    private fun previewer(server: FakeMailServer, store: AttachmentStore?) =
        AttachmentDownloader(server, store, render = { _, _, bytes -> PreviewBody.Text(bytes.decodeToString()) })

    private suspend fun AttachmentDownloader.open() =
        preview(attachment, messageId = "m1", folderName = "INBOX")

    @Test
    fun `a click previews the file in the app without saving it`() = runTest {
        val store = RecordingStore()
        val downloader = previewer(FakeMailServer(), store)

        downloader.open()

        val open = assertNotNull(downloader.preview.value)
        assertEquals(PreviewBody.Text("Hello"), open.body)
        assertNull(store.savedBytes, "previewing must not write to disk")
        assertNull(downloader.state.value["a1"])
    }

    @Test
    fun `the preview's download writes the bytes it already has`() = runTest {
        val server = FakeMailServer()
        val store = RecordingStore()
        val downloader = previewer(server, store)

        downloader.open()
        downloader.downloadPreviewed()

        assertEquals(1, server.attachmentCalls)
        assertEquals("Hello", store.savedBytes?.decodeToString())
        assertIs<AttachmentDownload.Saved>(downloader.state.value["a1"])
    }

    @Test
    fun `a refused preview says why`() = runTest {
        val server = FakeMailServer().apply { attachmentFails = true }
        val downloader = previewer(server, RecordingStore())

        downloader.open()

        val open = assertNotNull(downloader.preview.value)
        assertNotNull(open.failed)
        assertNull(open.body)
    }

    @Test
    fun `closing clears the preview`() = runTest {
        val downloader = previewer(FakeMailServer(), RecordingStore())

        downloader.open()
        downloader.closePreview()

        assertNull(downloader.preview.value)
    }

    @Test
    fun `text attachments render as text and unknown ones as unsupported`() {
        assertEquals(
            PreviewBody.Text("a,b"),
            renderAttachmentPreview("rows.csv", contentType = null, bytes = "a,b".encodeToByteArray()),
        )
        assertEquals(
            PreviewBody.Unsupported,
            renderAttachmentPreview("archive.zip", contentType = "application/zip", bytes = byteArrayOf(1, 2)),
        )
    }
}
