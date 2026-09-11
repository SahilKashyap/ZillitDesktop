package com.zillit.desktop.feature.formsignature

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.socket.SocketEventName
import com.zillit.desktop.feature.formsignature.data.FORM_SIGN_SYNC_EVENTS
import com.zillit.desktop.feature.formsignature.data.refreshKindsFor
import com.zillit.desktop.feature.formsignature.domain.DocumentSigner
import com.zillit.desktop.feature.formsignature.domain.FormSignRefresh
import com.zillit.desktop.feature.formsignature.domain.FormSignatureRepository
import com.zillit.desktop.feature.formsignature.domain.FormSignatureViewer
import com.zillit.desktop.feature.formsignature.domain.HistoryEntry
import com.zillit.desktop.feature.formsignature.domain.PdfPageImage
import com.zillit.desktop.feature.formsignature.domain.PdfWork
import com.zillit.desktop.feature.formsignature.domain.PlacedStamp
import com.zillit.desktop.feature.formsignature.domain.SignDocument
import com.zillit.desktop.feature.formsignature.domain.SignDocumentTab
import com.zillit.desktop.feature.formsignature.domain.SignFileTransfer
import com.zillit.desktop.feature.formsignature.domain.SignatureBlock
import com.zillit.desktop.feature.formsignature.domain.SignerOption
import com.zillit.desktop.feature.formsignature.domain.StandardForm
import com.zillit.desktop.feature.formsignature.domain.StandardFormType
import com.zillit.desktop.feature.formsignature.domain.StoredDocument
import com.zillit.desktop.feature.formsignature.domain.StrokePoint
import com.zillit.desktop.feature.formsignature.domain.UploadPurpose
import com.zillit.desktop.feature.formsignature.ui.FormSignatureArea
import com.zillit.desktop.feature.formsignature.ui.FormSignatureEvent
import com.zillit.desktop.feature.formsignature.ui.FormSignatureViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A `document:*` pulse refetches the list it names, only while that list
 * is on screen — the web's per-page refetch handlers (`StandardFormsV2.jsx`,
 * `DocumentsForSignature.jsx`, `FormPage.jsx`) as targeted reloads.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FormSignatureSyncTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    // -- the event → kind map ---------------------------------------------

    @Test
    fun `events map to the lists their web pages refetch`() {
        assertEquals(
            listOf(FormSignRefresh.Forms),
            refreshKindsFor(SocketEventName("document:added:general")),
        )
        assertEquals(
            listOf(FormSignRefresh.Documents),
            refreshKindsFor(SocketEventName("document:sent:signature")),
        )
        assertEquals(
            listOf(FormSignRefresh.Forms, FormSignRefresh.Documents),
            refreshKindsFor(SocketEventName("document:signed")),
            "both web lists refetch on a signing",
        )
        assertEquals(
            emptyList(),
            refreshKindsFor(SocketEventName("document:message:added")),
            "the chat family has no desktop surface",
        )
    }

    /**
     * A form assigned to this user, or added to the library, lands live.
     *
     * These four were excluded as "V1-only" on the web's authority; iOS's
     * Form & Signature 2.0 refreshes the standard-forms list from all of them
     * (`StandardFormsView.swift:40-51`), and the desktop is that tool.
     */
    @Test
    fun `an assigned or library form refetches the forms list`() {
        listOf(
            "document:added:user:form",
            "document:added:user:contract",
            "document:added:saved:form",
            "document:added:saved:contract",
        ).forEach { name ->
            assertEquals(
                listOf(FormSignRefresh.Forms),
                refreshKindsFor(SocketEventName(name)),
                "$name must refresh the standard forms",
            )
            assertTrue(SocketEventName(name) in FORM_SIGN_SYNC_EVENTS, "$name must be subscribed")
        }
    }

    // -- the view model ----------------------------------------------------

    private class FakeRepo(override val refreshes: Flow<FormSignRefresh>) : FormSignatureRepository {
        var formLoads = 0
        var documentLoads = 0
        override suspend fun standardForms(selfAssigned: Boolean): ZillitResult<List<StandardForm>> {
            formLoads++
            return ZillitResult.Success(emptyList())
        }
        override suspend fun selfAssign(documentId: String) = ZillitResult.Success(Unit)
        override suspend fun deleteStandardForm(documentId: String) = ZillitResult.Success(Unit)
        override suspend fun addStandardForm(document: StoredDocument, type: StandardFormType, note: String) =
            ZillitResult.Success(Unit)
        override suspend fun history(documentId: String) = ZillitResult.Success(emptyList<HistoryEntry>())
        override suspend fun documents(tab: SignDocumentTab): ZillitResult<List<SignDocument>> {
            documentLoads++
            return ZillitResult.Success(emptyList())
        }
        override suspend fun sendForSignature(
            document: StoredDocument,
            signers: List<DocumentSigner>,
            onlySignatureRequired: Boolean,
            userSignatureRequired: Boolean,
        ) = ZillitResult.Success(Unit)
        override suspend fun deleteDocument(documentId: String) = ZillitResult.Success(Unit)
        override suspend fun signDocument(documentId: String, signed: StoredDocument) = ZillitResult.Success(Unit)
        override suspend fun signStandardForm(documentId: String, signed: StoredDocument) =
            ZillitResult.Success(Unit)
        override suspend fun signatures() = ZillitResult.Success(emptyList<SignatureBlock>())
        override suspend fun saveSignature(
            image: StoredDocument,
            name: String,
            isSignature: Boolean,
            existingId: String?,
        ) = ZillitResult.Success(Unit)
        override suspend fun deleteSignature(signatureId: String) = ZillitResult.Success(Unit)
        override suspend fun signerOptions() = ZillitResult.Success(emptyList<SignerOption>())
    }

    private object NoTransfer : SignFileTransfer {
        override suspend fun store(purpose: UploadPurpose, fileName: String, contentType: String, bytes: ByteArray) =
            ZillitResult.Success(StoredDocument(media = "k"))
        override suspend fun fetch(document: StoredDocument) = ZillitResult.Success(ByteArray(0))
    }

    private object NoPdf : PdfWork {
        override fun renderPages(pdf: ByteArray, targetWidthPx: Int) =
            ZillitResult.Success(emptyList<PdfPageImage>())
        override fun stamp(pdf: ByteArray, stamps: List<PlacedStamp>) = ZillitResult.Success(pdf)
        override fun rasterizeStrokes(strokes: List<List<StrokePoint>>, canvasWidth: Int, canvasHeight: Int) =
            ZillitResult.Success(ByteArray(0))
    }

    @Test
    fun `a pulse reloads only the list on screen`() = runTest(dispatcher) {
        val events = MutableSharedFlow<FormSignRefresh>()
        val repo = FakeRepo(refreshes = events)
        val model = FormSignatureViewModel(
            repository = repo,
            transfer = NoTransfer,
            pdfWork = NoPdf,
            resolveViewer = { FormSignatureViewer(canView = true, canPost = true, ready = true) },
            currentUserId = { "u1" },
            newId = { "id" },
        )

        model.start()
        runCurrent()
        assertEquals(0, repo.documentLoads, "the hub loads no lists")

        events.emit(FormSignRefresh.Documents)
        runCurrent()
        assertEquals(0, repo.documentLoads, "an event for a list not on screen is ignored")

        model.onEvent(FormSignatureEvent.SwitchArea(FormSignatureArea.Documents))
        runCurrent()
        assertEquals(1, repo.documentLoads)

        events.emit(FormSignRefresh.Documents)
        runCurrent()
        assertEquals(2, repo.documentLoads, "the pulse re-runs exactly one load")

        events.emit(FormSignRefresh.Forms)
        runCurrent()
        assertEquals(0, repo.formLoads, "a forms pulse leaves the documents area alone")
    }
}
