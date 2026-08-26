package com.zillit.desktop.feature.esignature

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.esignature.domain.AuditEntry
import com.zillit.desktop.feature.esignature.domain.Envelope
import com.zillit.desktop.feature.esignature.domain.EnvelopeRecipient
import com.zillit.desktop.feature.esignature.domain.EnvelopeScope
import com.zillit.desktop.feature.esignature.domain.EsignFileTransfer
import com.zillit.desktop.feature.esignature.domain.EsignPage
import com.zillit.desktop.feature.esignature.domain.EsignPdf
import com.zillit.desktop.feature.esignature.domain.EsignRepository
import com.zillit.desktop.feature.esignature.domain.EsignViewer
import com.zillit.desktop.feature.esignature.domain.NewField
import com.zillit.desktop.feature.esignature.domain.SavedSignature
import com.zillit.desktop.feature.esignature.domain.SignedField
import com.zillit.desktop.feature.esignature.domain.StoredFile
import com.zillit.desktop.feature.esignature.ui.EsignViewModel
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

/**
 * An `esignature:envelope:*` pulse re-runs the visible list load, once —
 * the web's `DocuSignObservers.jsx:42-52` upsert, ported as a targeted
 * refetch (`listenerSocket.js:27-33`).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EsignSyncTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private class FakeRepo(override val refreshes: Flow<Unit>) : EsignRepository {
        var listLoads = 0
        override suspend fun envelopes(scope: EnvelopeScope, bucket: String, userId: String?):
            ZillitResult<List<Envelope>> {
            listLoads++
            return ZillitResult.Success(emptyList())
        }
        override suspend fun envelope(id: String) = ZillitResult.Success(Envelope(id))
        override suspend fun create(
            title: String,
            description: String,
            document: StoredFile,
            recipients: List<EnvelopeRecipient>,
            fields: List<NewField>,
        ) = ZillitResult.Success(Envelope("e1"))
        override suspend fun send(envelopeId: String) = ZillitResult.Success(Unit)
        override suspend fun deleteDraft(envelopeId: String) = ZillitResult.Success(Unit)
        override suspend fun remind(envelopeId: String, recipientId: String?) = ZillitResult.Success(Unit)
        override suspend fun markViewed(envelopeId: String) = ZillitResult.Success(Unit)
        override suspend fun sign(envelopeId: String, answers: List<SignedField>) = ZillitResult.Success(Unit)
        override suspend fun decline(envelopeId: String, reason: String) = ZillitResult.Success(Unit)
        override suspend fun auditTrail(envelopeId: String) = ZillitResult.Success(emptyList<AuditEntry>())
        override suspend fun savedSignatures() = ZillitResult.Success(emptyList<SavedSignature>())
        override suspend fun saveSignature(isSignature: Boolean, image: StoredFile) = ZillitResult.Success(Unit)
        override suspend fun deleteSavedSignature(id: String) = ZillitResult.Success(Unit)
    }

    private object NoTransfer : EsignFileTransfer {
        override suspend fun store(fileName: String, contentType: String, bytes: ByteArray) =
            ZillitResult.Success(StoredFile("k"))
        override suspend fun fetch(file: StoredFile) = ZillitResult.Success(ByteArray(0))
    }

    private object NoPdf : EsignPdf {
        override fun renderPages(pdf: ByteArray, targetWidthPx: Int) =
            ZillitResult.Success(emptyList<EsignPage>())
        override fun rasterizeStrokes(strokes: List<List<Pair<Float, Float>>>, width: Int, height: Int) =
            ZillitResult.Success(ByteArray(0))
    }

    @Test
    fun `an envelope event reloads the visible list once`() = runTest(dispatcher) {
        val events = MutableSharedFlow<Unit>()
        val repo = FakeRepo(refreshes = events)
        val model = EsignViewModel(
            repository = repo,
            transfer = NoTransfer,
            pdf = NoPdf,
            resolveViewer = { EsignViewer(canView = true, canPost = true, ready = true) },
            currentUserId = { "u1" },
            currentUserName = { "Vidya" },
            signerOptions = { emptyList() },
            newId = { "id" },
        )

        model.start()
        runCurrent()
        assertEquals(1, repo.listLoads, "start loads the list once")

        events.emit(Unit)
        runCurrent()
        assertEquals(2, repo.listLoads, "the socket pulse re-runs exactly one load")

        model.start()
        runCurrent()
        events.emit(Unit)
        runCurrent()
        assertEquals(4, repo.listLoads, "a second start must not stack a second collector")
    }
}
