package com.zillit.desktop.feature.formsignature

import com.zillit.desktop.feature.formsignature.domain.DocumentSigner
import com.zillit.desktop.feature.formsignature.domain.FormSignatureViewer
import com.zillit.desktop.feature.formsignature.domain.SignDocument
import com.zillit.desktop.feature.formsignature.domain.SignDocumentTab
import com.zillit.desktop.feature.formsignature.domain.SignSpot
import com.zillit.desktop.feature.formsignature.domain.SignSpotKind
import com.zillit.desktop.feature.formsignature.domain.SignatureBlock
import com.zillit.desktop.feature.formsignature.domain.SignerOption
import com.zillit.desktop.feature.formsignature.domain.StandardForm
import com.zillit.desktop.feature.formsignature.domain.StandardFormType
import com.zillit.desktop.feature.formsignature.domain.StoredDocument
import com.zillit.desktop.feature.formsignature.domain.StrokePoint
import com.zillit.desktop.feature.formsignature.ui.ConfirmState
import com.zillit.desktop.feature.formsignature.ui.FormSignScreen
import com.zillit.desktop.feature.formsignature.ui.FormSignatureEffect
import com.zillit.desktop.feature.formsignature.ui.FormSignatureEvent
import com.zillit.desktop.feature.formsignature.ui.FormSignatureViewModel
import com.zillit.desktop.feature.formsignature.ui.PickTarget
import com.zillit.desktop.feature.formsignature.ui.SendStep
import com.zillit.desktop.feature.formsignature.ui.StandardTab
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The web's flows, step for step: click-to-fill placeholders, free
 * placement, the confirmations around leaving and sending, the two-step
 * send with its validation order, the library upload, and the rights gate.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FormSignatureFlowTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private val signatureBlock =
        SignatureBlock(id = "s1", isSignature = true, image = StoredDocument("k/sig.png"), name = "Sig")
    private val initialsBlock =
        SignatureBlock(id = "s2", isSignature = false, image = StoredDocument("k/ini.png"), name = "Ini")

    private fun pdf(name: String) = StoredDocument(media = "k/$name", name = name)

    private class Harness(val repo: FakeFormSignatureRepository, val pdf: FakePdf, canPost: Boolean = true) {
        val model = FormSignatureViewModel(
            repository = repo,
            transfer = FakeTransfer,
            pdfWork = pdf,
            resolveViewer = { FormSignatureViewer(canView = true, canPost = canPost, ready = true) },
            currentUserId = { "me" },
            newId = { "id" },
        )
        val effects = mutableListOf<FormSignatureEffect>()
    }

    private fun TestScope.failuresOf(h: Harness): List<String> {
        runCurrent()
        return h.effects.filterIsInstance<FormSignatureEffect.Failed>().map { it.message }
    }

    private fun TestScope.harness(
        canPost: Boolean = true,
        pages: Int = 1,
        setup: FakeFormSignatureRepository.() -> Unit = {},
    ): Harness {
        val repo = FakeFormSignatureRepository().apply(setup)
        val h = Harness(repo, FakePdf(pages), canPost)
        backgroundScope.launch { h.model.effects.collect { h.effects += it } }
        h.model.start()
        runCurrent()
        return h
    }

    @Test
    fun `a placeholder clicked takes the saved mark and only Send uploads`() = runTest(dispatcher) {
        val spot = SignSpot(SignSpotKind.Signature, page = 1, x = 50.0, y = 700.0, width = 200.0, height = 60.0)
        val h = harness {
            blocks = listOf(signatureBlock)
            documents = listOf(
                SignDocument(
                    id = "d1",
                    document = pdf("Deal.pdf"),
                    signers = listOf(DocumentSigner(userId = "me", spots = listOf(spot))),
                    spots = listOf(spot),
                ),
            )
        }
        h.model.onEvent(FormSignatureEvent.Open(FormSignScreen.DocumentsForSignature))
        h.model.onEvent(FormSignatureEvent.SwitchDocumentsTab(SignDocumentTab.Received))
        runCurrent()
        h.model.onEvent(FormSignatureEvent.OpenDocument(h.repo.documents.first()))
        runCurrent()

        val opened = assertNotNull(h.model.currentState.detail)
        assertEquals(listOf(spot), opened.placeholders)
        assertTrue(opened.canSign && opened.placeholderFlow)

        h.model.onEvent(FormSignatureEvent.TapPlaceholder(spot))
        assertEquals(SignSpotKind.Signature, h.model.currentState.picker?.kind)
        h.model.onEvent(FormSignatureEvent.PickSignature(signatureBlock))
        runCurrent()

        val stamped = assertNotNull(h.model.currentState.detail)
        assertEquals(spot, h.pdf.stamps.single().spot, "the mark is stamped at the placeholder's own coordinates")
        assertTrue(stamped.placeholders.isEmpty(), "the filled placeholder is gone")
        assertTrue(stamped.signedLocally)
        assertTrue(h.repo.signed.isEmpty(), "nothing has reached the server yet")

        h.model.onEvent(FormSignatureEvent.AskSendSigned)
        assertEquals(ConfirmState.SendSigned, h.model.currentState.confirm)
        h.model.onEvent(FormSignatureEvent.ConfirmYes)
        runCurrent()

        val (documentId, uploaded) = h.repo.signed.single()
        assertEquals("d1", documentId)
        assertEquals("Deal.pdf", uploaded.name)
        assertNull(h.model.currentState.detail, "the detail closes after sending")
        assertEquals(FormSignScreen.DocumentsForSignature, h.model.currentState.screen)
    }

    @Test
    fun `a downloaded form is signed by dragging a mark, then sent on the older route`() = runTest(dispatcher) {
        val h = harness {
            blocks = listOf(signatureBlock, initialsBlock)
            forms = listOf(StandardForm(id = "f1", document = pdf("NDA.pdf")))
        }
        h.model.onEvent(FormSignatureEvent.Open(FormSignScreen.StandardDocuments))
        h.model.onEvent(FormSignatureEvent.SwitchStandardTab(StandardTab.Mine))
        runCurrent()
        h.model.onEvent(FormSignatureEvent.OpenStandardForm(h.repo.forms.first()))
        runCurrent()

        h.model.onEvent(FormSignatureEvent.AddSignature)
        assertNull(h.model.currentState.picker?.kind, "free placement offers both kinds")
        h.model.onEvent(FormSignatureEvent.PickSignature(signatureBlock))
        runCurrent()
        val mark = assertNotNull(h.model.currentState.detail?.freeMark)
        assertEquals(300f, mark.x)
        assertEquals(200f, mark.width)
        assertEquals(0.5f, mark.aspect, "the box keeps the image's aspect")

        h.model.onEvent(FormSignatureEvent.MoveFreeMark(x = 100f, y = 200f, width = 400f))
        h.model.onEvent(FormSignatureEvent.ConfirmFreeMark)
        runCurrent()
        val spot = h.pdf.stamps.single().spot
        // 800 px = 595 pt; the box is 400×200 px at (100, 200) from the top.
        assertEquals(100 * 595.0 / 800, spot.x, 0.01)
        assertEquals(400 * 595.0 / 800, spot.width, 0.01)
        assertEquals((1131 - 200 - 200) * 595.0 / 800, spot.y, 0.5)
        assertTrue(h.model.currentState.detail?.signedLocally == true)

        h.model.onEvent(FormSignatureEvent.AskSendSigned)
        h.model.onEvent(FormSignatureEvent.ConfirmYes)
        runCurrent()
        assertEquals(
            "f1",
            h.repo.signedForms.single().first,
            "My Downloads signs on sign-document/received-document/sign",
        )
        assertTrue(h.repo.signed.isEmpty())
    }

    @Test
    fun `leaving a signed but unsent document asks first`() = runTest(dispatcher) {
        val spot = SignSpot(SignSpotKind.Signature, 1, 10.0, 10.0, 100.0, 40.0)
        val h = harness {
            blocks = listOf(signatureBlock)
            documents = listOf(
                SignDocument(
                    id = "d1",
                    document = pdf("a.pdf"),
                    signers = listOf(DocumentSigner("me", spots = listOf(spot))),
                    spots = listOf(spot),
                ),
            )
        }
        h.model.onEvent(FormSignatureEvent.Open(FormSignScreen.DocumentsForSignature))
        h.model.onEvent(FormSignatureEvent.SwitchDocumentsTab(SignDocumentTab.Received))
        runCurrent()
        h.model.onEvent(FormSignatureEvent.OpenDocument(h.repo.documents.first()))
        runCurrent()
        h.model.onEvent(FormSignatureEvent.TapPlaceholder(spot))
        h.model.onEvent(FormSignatureEvent.PickSignature(signatureBlock))
        runCurrent()

        h.model.onEvent(FormSignatureEvent.Back)
        assertEquals(ConfirmState.LeaveSigned, h.model.currentState.confirm)
        assertNotNull(h.model.currentState.detail, "still open until answered")
        h.model.onEvent(FormSignatureEvent.ConfirmYes)
        assertNull(h.model.currentState.detail)
        assertTrue(h.repo.signed.isEmpty(), "leaving discards; nothing is sent")
    }

    @Test
    fun `sending walks the web's two steps and refuses in its order`() = runTest(dispatcher) {
        val h = harness(pages = 2) {
            options = listOf(
                SignerOption(userId = "u2", fullName = "Bea Signer", email = "bea@x.com"),
                SignerOption(userId = "me", fullName = "Me"),
                SignerOption(userId = "gone", fullName = "Left", status = "left"),
            )
        }
        h.model.onEvent(FormSignatureEvent.Open(FormSignScreen.DocumentsForSignature))
        runCurrent()
        h.model.onEvent(FormSignatureEvent.StartSend)
        runCurrent()
        val send = assertNotNull(h.model.currentState.send)
        assertEquals(listOf("u2"), send.options.map { it.userId }, "the sender and anyone who left are not offered")

        h.model.onEvent(FormSignatureEvent.FilePicked(PickTarget.SendDocument, "memo.docx", byteArrayOf(1)))
        assertEquals("PDF format only", failuresOf(h).last())
        h.model.onEvent(FormSignatureEvent.FilePicked(PickTarget.SendDocument, "memo.pdf", byteArrayOf(1)))
        assertEquals("memo", h.model.currentState.send?.title)

        h.model.onEvent(FormSignatureEvent.SendNext)
        assertEquals("Please select atleast one member", failuresOf(h).last())

        h.model.onEvent(FormSignatureEvent.EditSend(h.model.currentState.send!!.copy(chosen = listOf("u2"))))
        h.model.onEvent(FormSignatureEvent.SendNext)
        runCurrent()
        assertEquals(SendStep.Place, h.model.currentState.send?.step)
        assertEquals("u2", h.model.currentState.send?.activeSigner)

        h.model.onEvent(FormSignatureEvent.SendAddPlaceholder(SignSpotKind.Signature))
        assertNotNull(h.model.currentState.send?.draft)
        h.model.onEvent(FormSignatureEvent.SendTurnPage(1))
        assertEquals(0, h.model.currentState.send?.page, "paging is off while a box is being placed")
        h.model.onEvent(FormSignatureEvent.SendMoveDraft(x = 40f, y = 60f, width = 200f, height = 60f))
        h.model.onEvent(FormSignatureEvent.SendConfirmDraft)
        assertEquals(1, h.model.currentState.send?.spots?.get("u2")?.size)

        h.model.onEvent(FormSignatureEvent.SubmitSend)
        assertEquals("Please add an initials placeholder for all users.", failuresOf(h).last())

        h.model.onEvent(FormSignatureEvent.EditSend(h.model.currentState.send!!.copy(onlySignature = true)))
        h.model.onEvent(FormSignatureEvent.SubmitSend)
        runCurrent()
        val signers = h.repo.sent.single()
        val bea = signers.single()
        assertEquals("u2", bea.userId)
        assertEquals("bea@x.com", bea.email)
        assertEquals(1, bea.order)
        assertEquals(SignSpotKind.Signature, bea.spots.single().kind)
        assertEquals(true, h.repo.lastSendOnlySignature)
        assertNull(h.repo.lastSendSigning, "no signing_document unless the sender signed")
        assertNull(h.model.currentState.send)
    }

    @Test
    fun `the library upload carries the typed name and the file's extension`() = runTest(dispatcher) {
        val h = harness()
        h.model.onEvent(FormSignatureEvent.Open(FormSignScreen.StandardDocuments))
        runCurrent()
        h.model.onEvent(FormSignatureEvent.StartUploadForm)
        h.model.onEvent(FormSignatureEvent.FilePicked(PickTarget.StandardForm, "Crew NDA.docx", byteArrayOf(1)))
        assertEquals("Crew NDA", h.model.currentState.uploadForm?.name)
        val form = assertNotNull(h.model.currentState.uploadForm)
        h.model.onEvent(FormSignatureEvent.EditUploadForm(form.copy(type = StandardFormType.Reference)))
        h.model.onEvent(FormSignatureEvent.SubmitUploadForm)
        runCurrent()
        val (document, type) = h.repo.added.single()
        assertEquals("Crew NDA", document.name)
        assertEquals("docx", document.contentSubtype)
        assertEquals(StandardFormType.Reference, type)
        assertEquals("reference_document", type.wire)
        assertNull(h.model.currentState.uploadForm)
    }

    @Test
    fun `a viewer without posting rights is refused, not hidden`() = runTest(dispatcher) {
        val h = harness(canPost = false)
        h.model.onEvent(FormSignatureEvent.Open(FormSignScreen.StandardDocuments))
        runCurrent()
        h.model.onEvent(FormSignatureEvent.StartUploadForm)
        assertNull(h.model.currentState.uploadForm)
        runCurrent()
        assertIs<FormSignatureEffect.Failed>(h.effects.last())
        h.model.onEvent(FormSignatureEvent.AskDeleteStandardForm("f1"))
        assertNull(h.model.currentState.confirm)
    }

    @Test
    fun `a drawn mark needs a name before it is saved`() = runTest(dispatcher) {
        val h = harness()
        h.model.onEvent(FormSignatureEvent.StartDraw(isSignature = true))
        assertEquals(FormSignScreen.DrawSignature, h.model.currentState.screen)
        h.model.onEvent(FormSignatureEvent.AddDrawStroke(listOf(StrokePoint(1f, 1f), StrokePoint(5f, 5f))))
        h.model.onEvent(FormSignatureEvent.SubmitDraw)
        assertEquals("Signature Name is required.", failuresOf(h).last())
        h.model.onEvent(FormSignatureEvent.EditDraw(h.model.currentState.draw!!.copy(name = "Mine")))
        h.model.onEvent(FormSignatureEvent.SubmitDraw)
        runCurrent()
        assertEquals(listOf("Mine"), h.repo.savedSignatures)
        assertEquals(FormSignScreen.SignatureBlock, h.model.currentState.screen, "the page returns to the gallery")
    }
}
