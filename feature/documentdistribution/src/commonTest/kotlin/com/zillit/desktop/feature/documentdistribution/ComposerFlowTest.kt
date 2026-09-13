package com.zillit.desktop.feature.documentdistribution

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.documentdistribution.domain.Contact
import com.zillit.desktop.feature.documentdistribution.domain.DocDistHost
import com.zillit.desktop.feature.documentdistribution.domain.DocDistSignature
import com.zillit.desktop.feature.documentdistribution.domain.DocDistViewer
import com.zillit.desktop.feature.documentdistribution.domain.EmailTemplate
import com.zillit.desktop.feature.documentdistribution.domain.LibraryDocument
import com.zillit.desktop.feature.documentdistribution.domain.LibraryFolder
import com.zillit.desktop.feature.documentdistribution.domain.LibraryPage
import com.zillit.desktop.feature.documentdistribution.domain.LibraryQuery
import com.zillit.desktop.feature.documentdistribution.domain.LocalFile
import com.zillit.desktop.feature.documentdistribution.domain.NewDistribution
import com.zillit.desktop.feature.documentdistribution.domain.Distribution
import com.zillit.desktop.feature.documentdistribution.domain.DistributionList
import com.zillit.desktop.feature.documentdistribution.domain.HistoryPage
import com.zillit.desktop.feature.documentdistribution.domain.Recipient
import com.zillit.desktop.feature.documentdistribution.domain.SentAttachment
import com.zillit.desktop.feature.documentdistribution.domain.WatermarkLine
import com.zillit.desktop.feature.documentdistribution.domain.WatermarkStyle
import com.zillit.desktop.feature.documentdistribution.ui.AddressField
import com.zillit.desktop.feature.documentdistribution.ui.DocDistDestination
import com.zillit.desktop.feature.documentdistribution.ui.DocDistEvent
import com.zillit.desktop.feature.documentdistribution.ui.DocDistViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.LocalDate
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The composer end to end: what opens it, what it sends, and what a
 * duplicated send restores.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ComposerFlowTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private val pdf = LibraryDocument(
        id = "d1",
        name = "Call Sheet.pdf",
        contentType = "application/pdf",
        sizeBytes = 100,
    )
    private val sheet = LibraryDocument(
        id = "d2",
        name = "Budget.xlsx",
        contentType = "application/vnd.ms-excel",
        sizeBytes = 50,
    )

    private open class Repo : FakeDocDistRepository(MutableSharedFlow()) {
        val sent = mutableListOf<NewDistribution>()
        val deletedEphemeral = mutableListOf<String>()
        var uploaded = 0
        var inFolder = listOf<LibraryDocument>()
        override suspend fun documents(query: LibraryQuery) = ZillitResult.Success(LibraryPage(inFolder, inFolder.size))
        override suspend fun folders() = ZillitResult.Success(listOf(LibraryFolder("f1", "Call sheets")))
        override suspend fun documentsInFolders(folderIds: Collection<String>) = ZillitResult.Success(inFolder)
        override suspend fun contacts() = ZillitResult.Success(listOf(Contact("ada@x.com", "Ada Lovelace", "1st AD")))
        override suspend fun lists() = ZillitResult.Success(
            listOf(
                DistributionList("l1", "Unit", listOf(Recipient("ada@x.com", "Ada"), Recipient("bob@x.com", "Bob"))),
            ),
        )
        override suspend fun templates() = ZillitResult.Success(
            listOf(EmailTemplate("t1", "Daily", "Today's call sheet", "<p>Hi,</p><p>Attached.</p>")),
        )
        override suspend fun send(distribution: NewDistribution): ZillitResult<Unit> {
            sent += distribution
            return ZillitResult.Success(Unit)
        }
        override suspend fun deleteEphemeral(attachmentId: String): ZillitResult<Unit> {
            deletedEphemeral += attachmentId
            return ZillitResult.Success(Unit)
        }
        override suspend fun uploadEphemeral(file: LocalFile): ZillitResult<LibraryDocument> {
            uploaded++
            return ZillitResult.Success(
                LibraryDocument(
                    id = "e${uploaded}",
                    name = file.name,
                    contentType = file.contentType,
                    sizeBytes = file.sizeBytes,
                    isEphemeral = true,
                ),
            )
        }
        override suspend fun documentsByIds(ids: List<String>) = ZillitResult.Success(
            listOf(pdfStored).filter { it.id in ids },
        )
        override suspend fun ephemeralByIds(ids: List<String>) = ZillitResult.Success(
            listOf(LibraryDocument(id = "e9", name = "device.pdf", contentType = "application/pdf"))
                .filter { it.id in ids },
        )
        val pdfStored = LibraryDocument(
            id = "d1",
            name = "Call Sheet.pdf",
            contentType = "application/pdf",
            sizeBytes = 100,
        )
    }

    private val host = object : DocDistHost {
        var files = listOf<LocalFile>()
        override suspend fun pickFiles(): List<LocalFile> = files
        override suspend fun signatures() = listOf(
            DocDistSignature("s1", "Work", "<b>Ada</b> · 1st AD", useForNew = true),
        )
    }

    private fun model(repo: Repo) = DocDistViewModel(
        repository = repo,
        viewer = { DocDistViewer(userId = "u1", userEmail = "me@x.com", ready = true) },
        today = { LocalDate(2026, 9, 13) },
        host = host,
    ).also { it.start() }

    @Test
    fun `a send goes out as HTML with its signature, the stamped PDF's watermark, and the lists that fed it`() =
        runTest(dispatcher) {
            val repo = Repo().apply { inFolder = listOf(pdf, sheet) }
            val vm = model(repo)
            runCurrent()

            vm.onEvent(DocDistEvent.ToggleDocument("d1"))
            vm.onEvent(DocDistEvent.ToggleDocument("d2"))
            vm.onEvent(DocDistEvent.Compose)
            runCurrent()
            val opened = vm.state.value.composer
            assertTrue(opened.open)
            assertEquals(setOf("d1"), opened.watermarked, "the PDF is stamped by default, the spreadsheet cannot be")
            assertEquals("Work", opened.signature?.title, "the signature marked for new mail is picked up")

            vm.onEvent(DocDistEvent.AddList("l1"))
            vm.onEvent(DocDistEvent.ComposeAddresses(AddressField.To, listOf("ada@x.com", "carol@x.com"), ""))
            vm.onEvent(DocDistEvent.ApplyTemplate("t1"))
            vm.onEvent(DocDistEvent.ComposeBody("Hi,\n\nAttached is today's sheet."))
            vm.onEvent(DocDistEvent.Send)
            runCurrent()

            val sent = repo.sent.single()
            assertEquals("Today's call sheet", sent.subject)
            assertEquals(
                "<p>Hi,</p><p>Attached is today's sheet.</p><br/><br/><div><b>Ada</b> · 1st AD</div>",
                sent.bodyHtml,
            )
            assertEquals(listOf("ada@x.com", "carol@x.com"), sent.to.map { it.email })
            assertEquals("Ada Lovelace", sent.to.first().name, "a typed address is named from the address book")
            assertEquals(listOf("d1", "d2"), sent.attachmentIds)
            assertEquals(setOf("d1"), sent.watermarks.keys)
            assertEquals("me@x.com", sent.replyTo)
            // Bob was removed from To before sending, so the list is credited with Ada only.
            assertEquals(listOf("ada@x.com"), sent.listsUsed.single().emails)
            assertTrue(!vm.state.value.composer.open, "the composer closes at once and sends in the background")
        }

    @Test
    fun `a device upload attaches as ephemeral and is deleted again when removed or abandoned`() = runTest(dispatcher) {
        val repo = Repo()
        val vm = model(repo)
        runCurrent()
        vm.onEvent(DocDistEvent.ComposeBlank)
        runCurrent()

        host.files = listOf(
            LocalFile("scan.png", "image/png", ByteArray(10)),
            LocalFile("virus.exe", "application/octet-stream", ByteArray(10)),
        )
        vm.onEvent(DocDistEvent.PickAndAttach)
        runCurrent()
        val composer = vm.state.value.composer
        assertEquals(listOf("scan.png"), composer.attachments.map { it.name }, "the unsupported file is skipped")
        assertTrue(composer.attachments.single().isEphemeral)
        assertEquals(setOf("e1"), composer.watermarked, "a stampable upload starts stamped")

        vm.onEvent(DocDistEvent.CloseComposer)
        runCurrent()
        assertEquals(listOf("e1"), repo.deletedEphemeral, "an unsent upload does not linger on the server")
    }

    @Test
    fun `duplicating a past send restores its attachments, stamp and body without recipients`() = runTest(dispatcher) {
        val past = Distribution(
            id = "h1",
            subject = "Yesterday's sheet",
            bodyHtml = "<p>Hello</p><br/><br/><div>Sent from Web</div>",
            recipients = emptyList(),
            attachments = listOf(
                SentAttachment("d1", "Call Sheet.pdf", watermarked = true),
                SentAttachment("e9", "device.pdf", source = "ephemeral"),
                SentAttachment("gone", "deleted.pdf"),
            ),
            watermark = WatermarkStyle(line1 = WatermarkLine.Custom, line1Custom = "DRAFT"),
        )
        val vm2 = DocDistViewModel(
            repository = object : Repo() {
                override suspend fun history(page: Int, search: String, senderIds: Set<String>, limit: Int) =
                    ZillitResult.Success(HistoryPage(listOf(past), 1))
            },
            viewer = { DocDistViewer(userId = "u1", ready = true) },
            today = { LocalDate(2026, 9, 13) },
            host = host,
        ).also { it.start() }
        runCurrent()
        vm2.onEvent(DocDistEvent.Open(DocDistDestination.History))
        runCurrent()
        vm2.onEvent(DocDistEvent.DuplicateDistribution("h1"))
        runCurrent()

        val composer = vm2.state.value.composer
        assertTrue(composer.open)
        assertEquals(DocDistDestination.Library, vm2.state.value.destination)
        assertEquals("Yesterday's sheet", composer.subject)
        assertEquals("Hello\n\nSent from Web", composer.body)
        assertNull(composer.signature, "the duplicated body already carries its sign-off")
        assertEquals(listOf("d1", "e9"), composer.attachments.map { it.id }, "the deleted one is dropped")
        assertTrue(composer.attachments[1].reused, "the past send's upload is borrowed, not owned")
        assertEquals(setOf("d1"), composer.watermarked)
        assertEquals("DRAFT", composer.watermark.line1Custom)
        assertTrue(composer.to.isEmpty())
        assertTrue(vm2.state.value.notice.orEmpty().contains("1 attachment no longer available"))
    }
}
