package com.zillit.desktop.feature.formsignature

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.formsignature.domain.ChatUnit
import com.zillit.desktop.feature.formsignature.domain.DocumentSigner
import com.zillit.desktop.feature.formsignature.domain.FormSignRefresh
import com.zillit.desktop.feature.formsignature.domain.FormSignatureRepository
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/** A repository that counts its loads and records its writes — enough for every view-model test here. */
internal open class FakeFormSignatureRepository(
    override val refreshes: Flow<FormSignRefresh> = emptyFlow(),
) : FormSignatureRepository {
    var forms: List<StandardForm> = emptyList()
    var documents: List<SignDocument> = emptyList()
    var blocks: List<SignatureBlock> = emptyList()
    var options: List<SignerOption> = emptyList()
    var unit: ChatUnit? = null

    var formLoads = 0
    var documentLoads = 0
    val signed = mutableListOf<Pair<String, StoredDocument>>()
    val signedForms = mutableListOf<Pair<String, StoredDocument>>()
    val sent = mutableListOf<List<DocumentSigner>>()
    var lastSendSigning: StoredDocument? = null
    var lastSendOnlySignature: Boolean? = null
    val added = mutableListOf<Pair<StoredDocument, StandardFormType>>()
    val deletedForms = mutableListOf<String>()
    val savedSignatures = mutableListOf<String>()

    override suspend fun standardForms(selfAssigned: Boolean): ZillitResult<List<StandardForm>> {
        formLoads++
        return ZillitResult.Success(forms)
    }

    override suspend fun selfAssign(documentId: String) = ZillitResult.Success("Added to My Downloads")

    override suspend fun deleteStandardForm(documentId: String): ZillitResult<Unit> {
        deletedForms += documentId
        return ZillitResult.Success(Unit)
    }

    override suspend fun addStandardForm(document: StoredDocument, type: StandardFormType): ZillitResult<Unit> {
        added += document to type
        return ZillitResult.Success(Unit)
    }

    override suspend fun documents(tab: SignDocumentTab): ZillitResult<List<SignDocument>> {
        documentLoads++
        return ZillitResult.Success(documents)
    }

    override suspend fun sendForSignature(
        document: StoredDocument,
        signingDocument: StoredDocument?,
        signers: List<DocumentSigner>,
        onlySignatureRequired: Boolean,
        userSignatureRequired: Boolean,
    ): ZillitResult<Unit> {
        sent += signers
        lastSendSigning = signingDocument
        lastSendOnlySignature = onlySignatureRequired
        return ZillitResult.Success(Unit)
    }

    override suspend fun deleteDocument(documentId: String) = ZillitResult.Success(Unit)

    override suspend fun signDocument(documentId: String, signed: StoredDocument): ZillitResult<Unit> {
        this.signed += documentId to signed
        return ZillitResult.Success(Unit)
    }

    override suspend fun signStandardForm(documentId: String, signed: StoredDocument): ZillitResult<Unit> {
        signedForms += documentId to signed
        return ZillitResult.Success(Unit)
    }

    override suspend fun signatures() = ZillitResult.Success(blocks)

    override suspend fun saveSignature(
        image: StoredDocument,
        name: String,
        isSignature: Boolean,
        existingId: String?,
    ): ZillitResult<Unit> {
        savedSignatures += name
        return ZillitResult.Success(Unit)
    }

    override suspend fun deleteSignature(signatureId: String) = ZillitResult.Success(Unit)
    override suspend fun signerOptions() = ZillitResult.Success(options)
    override suspend fun chatUnit() = ZillitResult.Success(unit)
}

/** Stores by name, fetches a fixed byte for any document. */
internal object FakeTransfer : SignFileTransfer {
    override suspend fun store(purpose: UploadPurpose, fileName: String, contentType: String, bytes: ByteArray) =
        ZillitResult.Success(StoredDocument(media = "k/$fileName", name = fileName))

    override suspend fun fetch(document: StoredDocument) = ZillitResult.Success(byteArrayOf(1, 2, 3))
}

internal object NoTransfer : SignFileTransfer {
    override suspend fun store(purpose: UploadPurpose, fileName: String, contentType: String, bytes: ByteArray) =
        ZillitResult.Failure(ZillitError.Unknown("no storage in this test"))

    override suspend fun fetch(document: StoredDocument) = ZillitResult.Success(ByteArray(0))
}

/** One A4-ish page per document, stamps appended as a byte, a 2:1 image. */
internal class FakePdf(private val pageCount: Int = 1) : PdfWork {
    val stamps = mutableListOf<PlacedStamp>()

    override fun renderPages(pdf: ByteArray, targetWidthPx: Int) = ZillitResult.Success(
        (1..pageCount).map { page ->
            PdfPageImage(
                page = page,
                imageBytes = byteArrayOf(page.toByte()),
                widthPx = targetWidthPx,
                heightPx = (targetWidthPx * 1.414).toInt(),
                widthPt = 595.0,
                heightPt = 842.0,
            )
        },
    )

    override fun stamp(pdf: ByteArray, stamps: List<PlacedStamp>): ZillitResult<ByteArray> {
        this.stamps += stamps
        return ZillitResult.Success(pdf + byteArrayOf(9))
    }

    override fun rasterizeStrokes(strokes: List<List<StrokePoint>>, canvasWidth: Int, canvasHeight: Int) =
        ZillitResult.Success(byteArrayOf(7))

    override fun imageSize(png: ByteArray) = ZillitResult.Success(200 to 100)
}
