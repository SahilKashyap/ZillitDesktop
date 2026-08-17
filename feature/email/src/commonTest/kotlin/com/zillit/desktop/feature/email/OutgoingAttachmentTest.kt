package com.zillit.desktop.feature.email

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.email.domain.AttachmentUploader
import com.zillit.desktop.feature.email.domain.ComposeMode
import com.zillit.desktop.feature.email.domain.OutgoingAttachment
import com.zillit.desktop.feature.email.domain.PickedFile
import com.zillit.desktop.feature.email.domain.RichText
import com.zillit.desktop.feature.email.domain.StoredFile
import com.zillit.desktop.feature.email.domain.UploadState
import com.zillit.desktop.feature.email.domain.areSettled
import com.zillit.desktop.feature.email.ui.ComposeEvent
import com.zillit.desktop.feature.email.ui.ComposeViewModel
import com.zillit.desktop.feature.email.ui.Composing
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Attaching files to an outgoing message.
 *
 * The rule that matters: the payload names objects in the bucket rather than
 * carrying bytes, so sending before an upload finishes produces a message that
 * refers to an attachment which does not exist.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OutgoingAttachmentTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private class FakeUploader(var fails: Boolean = false) : AttachmentUploader {
        /** Set to hold an upload open, so a send can be attempted mid-flight. */
        var gate: CompletableDeferred<Unit>? = null
        var uploads = 0

        override suspend fun upload(
            fileName: String,
            contentType: String,
            bytes: ByteArray,
            onProgress: (Int) -> Unit,
        ): ZillitResult<StoredFile> {
            uploads++
            gate?.await()
            return if (fails) {
                ZillitResult.Failure(ZillitError.Http(500, "Upload failed."))
            } else {
                ZillitResult.Success(
                    StoredFile(
                        media = "email/abc/$fileName",
                        bucket = "zillit-uploads",
                        region = "eu-west-1",
                        fileName = fileName,
                        contentType = contentType,
                        sizeBytes = bytes.size.toLong(),
                    ),
                )
            }
        }
    }

    private fun file(name: String = "call-sheet.pdf") =
        PickedFile(name, "application/pdf", "hello".encodeToByteArray())

    private fun composer(server: FakeMailServer, uploader: AttachmentUploader?) =
        ComposeViewModel(
            Composing(server, server, server, server, uploader = uploader, newAttachmentId = { "a1" }),
            ComposeMode.New,
        )

    private fun ComposeViewModel.addressAndWrite() {
        onEvent(ComposeEvent.ToChanged("crew@prod.com"))
        onEvent(ComposeEvent.BodyChanged(RichText.plain("See attached")))
    }

    @Test
    fun `an attached file is uploaded and named in the send`() = runTest {
        val server = FakeMailServer()
        val uploader = FakeUploader()
        val composer = composer(server, uploader)

        composer.addressAndWrite()
        composer.onEvent(ComposeEvent.AttachFile(file()))
        advanceUntilIdle()
        composer.onEvent(ComposeEvent.Send)
        advanceUntilIdle()

        val sent = server.sent?.attachments.orEmpty()
        assertEquals(1, sent.size)
        assertEquals("call-sheet.pdf", sent.single().fileName)
        assertEquals("zillit-uploads", sent.single().bucket)
    }

    @Test
    fun `the chip appears before the upload finishes`() = runTest {
        // A picker that seems to do nothing for the length of an upload is one
        // people click twice.
        val uploader = FakeUploader().apply { gate = CompletableDeferred() }
        val composer = composer(FakeMailServer(), uploader)

        composer.onEvent(ComposeEvent.AttachFile(file()))

        assertEquals(1, composer.state.value.attachments.size)
    }

    @Test
    fun `sending is blocked while a file is still going up`() = runTest {
        // The payload names objects in the bucket, so this would send a message
        // referring to an attachment that does not exist yet.
        val gate = CompletableDeferred<Unit>()
        val uploader = FakeUploader().apply { this.gate = gate }
        val composer = composer(FakeMailServer(), uploader)

        composer.addressAndWrite()
        composer.onEvent(ComposeEvent.AttachFile(file()))
        advanceUntilIdle()

        assertFalse(composer.state.value.canSend, "Send was live mid-upload")

        gate.complete(Unit)
        advanceUntilIdle()

        assertTrue(composer.state.value.canSend)
    }

    @Test
    fun `a failed upload is not named in the send`() = runTest {
        // It stays in the list so the user can see it failed, but naming an
        // object that was never stored would break the whole message.
        val server = FakeMailServer()
        val composer = composer(server, FakeUploader(fails = true))

        composer.addressAndWrite()
        composer.onEvent(ComposeEvent.AttachFile(file()))
        advanceUntilIdle()
        composer.onEvent(ComposeEvent.Send)
        advanceUntilIdle()

        assertTrue(server.sent?.attachments.orEmpty().isEmpty())
        assertIs<UploadState.Failed>(composer.state.value.attachments.single().state)
    }

    @Test
    fun `a failed upload does not block sending`() = runTest {
        // It has settled — badly, but settled. Blocking forever would trap the
        // message; the user can remove the chip or send without it.
        val composer = composer(FakeMailServer(), FakeUploader(fails = true))

        composer.addressAndWrite()
        composer.onEvent(ComposeEvent.AttachFile(file()))
        advanceUntilIdle()

        assertTrue(composer.state.value.canSend)
    }

    @Test
    fun `removing an attachment takes it off the message`() = runTest {
        val server = FakeMailServer()
        val composer = composer(server, FakeUploader())

        composer.addressAndWrite()
        composer.onEvent(ComposeEvent.AttachFile(file()))
        advanceUntilIdle()
        composer.onEvent(ComposeEvent.RemoveAttachment("a1"))
        composer.onEvent(ComposeEvent.Send)
        advanceUntilIdle()

        assertTrue(composer.state.value.attachments.isEmpty())
        assertTrue(server.sent?.attachments.orEmpty().isEmpty())
    }

    @Test
    fun `with no uploader configured nothing is attached`() = runTest {
        // A build without file storage should not offer an attach it cannot
        // finish.
        val composer = composer(FakeMailServer(), uploader = null)

        composer.onEvent(ComposeEvent.AttachFile(file()))
        advanceUntilIdle()

        assertTrue(composer.state.value.attachments.isEmpty())
    }

    @Test
    fun `settledness is about pending and in-flight, not success`() {
        val done = OutgoingAttachment("1", "a", 1, "text/plain", UploadState.Uploaded(stored()))
        val failed = OutgoingAttachment("2", "b", 1, "text/plain", UploadState.Failed("no"))
        val going = OutgoingAttachment("3", "c", 1, "text/plain", UploadState.InProgress(40))
        val waiting = OutgoingAttachment("4", "d", 1, "text/plain", UploadState.Pending)

        assertTrue(listOf(done, failed).areSettled)
        assertFalse(listOf(done, going).areSettled)
        assertFalse(listOf(done, waiting).areSettled)
        assertTrue(emptyList<OutgoingAttachment>().areSettled)
    }

    @Test
    fun `a picked file never prints its bytes`() {
        // Attachments are the sender's business and these end up in logs.
        val printed = file("contract.pdf").toString()

        assertTrue(printed.contains("contract.pdf"))
        assertFalse(printed.contains("hello"))
    }

    private fun stored() = StoredFile("k", "b", "r", "n", "text/plain", 1)
}
