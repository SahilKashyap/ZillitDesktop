package com.zillit.desktop.feature.email

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.email.domain.AttachmentStore
import com.zillit.desktop.feature.email.domain.EmailAttachment
import com.zillit.desktop.feature.email.ui.AttachmentDownload
import com.zillit.desktop.feature.email.ui.AttachmentDownloader
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
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
}
