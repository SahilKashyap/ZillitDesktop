@file:Suppress("TooManyFunctions") // One handler per user act; merging them hides the acts.

package com.zillit.desktop.feature.formsignature.ui

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.mvvm.ZillitViewModel
import com.zillit.desktop.feature.formsignature.domain.DocumentSigner
import com.zillit.desktop.feature.formsignature.domain.FormSignatureRepository
import com.zillit.desktop.feature.formsignature.domain.FormSignatureViewer
import com.zillit.desktop.feature.formsignature.domain.PdfWork
import com.zillit.desktop.feature.formsignature.domain.PlacedStamp
import com.zillit.desktop.feature.formsignature.domain.SignDocument
import com.zillit.desktop.feature.formsignature.domain.SignDocumentTab
import com.zillit.desktop.feature.formsignature.domain.SignFileTransfer
import com.zillit.desktop.feature.formsignature.domain.SignSpot
import com.zillit.desktop.feature.formsignature.domain.SignSpotKind
import com.zillit.desktop.feature.formsignature.domain.SignatureBlock
import com.zillit.desktop.feature.formsignature.domain.StandardForm
import com.zillit.desktop.feature.formsignature.domain.StoredDocument
import com.zillit.desktop.feature.formsignature.domain.UploadPurpose

/**
 * Documents & Signature.
 *
 * ## Signing is local work first, a call second
 *
 * The server's contract is "hand me the finished file": this view model
 * fetches the PDF, stamps the reader's saved signature into it at the placed
 * spots, uploads the result, and only then tells the service the document is
 * signed. Every step can fail separately, and the order matters — a sign
 * call before the upload has landed would point the server at a key that
 * does not exist yet.
 */
class FormSignatureViewModel(
    private val repository: FormSignatureRepository,
    private val transfer: SignFileTransfer,
    private val pdfWork: PdfWork,
    private val resolveViewer: () -> FormSignatureViewer,
    private val currentUserId: () -> String,
    private val newId: () -> String,
) : ZillitViewModel<FormSignatureUiState, FormSignatureEvent, FormSignatureEffect>(
    FormSignatureUiState(),
) {

    /** The open document's bytes — held here, not in state, by weight. */
    private var detailPdf: ByteArray? = null

    /** Which flow asked for a file, so the picker's answer lands in it. */
    private var pendingPick: PickTarget? = null

    fun start() {
        val viewer = resolveViewer()
        setState { copy(viewer = viewer, currentUserId = currentUserId()) }
        loadSignatures()
    }

    @Suppress("CyclomaticComplexMethod") // One branch per user act; the fan-out IS the function.
    override fun onEvent(event: FormSignatureEvent) {
        when (event) {
            is FormSignatureEvent.SwitchArea -> switchArea(event.area)
            FormSignatureEvent.Refresh -> refresh()
            is FormSignatureEvent.SwitchStandardTab -> {
                setState { copy(standard = standard.copy(tab = event.tab)) }
                loadStandardForms()
            }
            is FormSignatureEvent.SwitchDocumentsTab -> {
                setState { copy(documents = documents.copy(tab = event.tab)) }
                loadDocuments()
            }
            is FormSignatureEvent.OpenStandardForm -> openStandardForm(event.form)
            is FormSignatureEvent.OpenDocument -> openDocument(event.document)
            FormSignatureEvent.CloseDetail -> {
                detailPdf = null
                setState { copy(detail = null) }
            }
            is FormSignatureEvent.PlaceFreeSpot ->
                placeFreeSpot(event.page, event.xPx, event.yPx)
            FormSignatureEvent.SignOpenDocument -> signOpenDocument()
            is FormSignatureEvent.SelfAssign -> selfAssign(event.formId)
            is FormSignatureEvent.DeleteStandardForm -> deleteStandardForm(event.formId)
            is FormSignatureEvent.DeleteDocument -> deleteDocument(event.documentId)
            is FormSignatureEvent.ShowHistory -> showHistory(event.documentId)
            FormSignatureEvent.CloseHistory -> setState { copy(history = null) }
            FormSignatureEvent.StartUploadForm -> {
                pendingPick = PickTarget.StandardForm
                sendEffect(FormSignatureEffect.PickPdf)
            }
            is FormSignatureEvent.EditUploadForm -> setState { copy(uploadForm = event.state) }
            FormSignatureEvent.SubmitUploadForm -> submitUploadForm()
            FormSignatureEvent.CancelUploadForm -> setState { copy(uploadForm = null) }
            FormSignatureEvent.StartSend -> {
                pendingPick = PickTarget.SendDocument
                sendEffect(FormSignatureEffect.PickPdf)
            }
            is FormSignatureEvent.EditSend -> setState { copy(send = event.state) }
            FormSignatureEvent.BeginPlacement -> beginPlacement()
            is FormSignatureEvent.PlaceSendSpot ->
                placeSendSpot(event.page, event.xPx, event.yPx)
            is FormSignatureEvent.RemoveSendSpot -> removeSendSpot(event.signer, event.index)
            FormSignatureEvent.SubmitSend -> submitSend()
            FormSignatureEvent.CancelSend -> setState { copy(send = null) }
            is FormSignatureEvent.StartDraw -> setState {
                copy(draw = DrawState(isSignature = event.isSignature, existingId = event.existingId))
            }
            is FormSignatureEvent.EditDraw -> setState { copy(draw = event.state) }
            is FormSignatureEvent.AddDrawStroke -> setState {
                copy(draw = draw?.copy(strokes = draw.strokes + listOf(event.stroke)))
            }
            FormSignatureEvent.SubmitDraw -> submitDraw()
            FormSignatureEvent.CancelDraw -> setState { copy(draw = null) }
            is FormSignatureEvent.DeleteSignature -> deleteSignature(event.blockId)
            is FormSignatureEvent.FilePicked -> filePicked(event.name, event.bytes)
        }
    }

    private fun switchArea(area: FormSignatureArea) {
        setState { copy(area = area, viewer = resolveViewer(), currentUserId = currentUserId()) }
        refresh()
    }

    private fun refresh() {
        when (currentState.area) {
            FormSignatureArea.Hub -> Unit
            FormSignatureArea.StandardForms -> loadStandardForms()
            FormSignatureArea.Documents -> loadDocuments()
            FormSignatureArea.Signatures -> loadSignatures()
        }
    }

    private fun loadStandardForms() {
        setState { copy(standard = standard.copy(loading = true)) }
        launchResult(
            block = { repository.standardForms(currentState.standard.tab == StandardTab.Mine) },
            onSuccess = { rows -> setState { copy(standard = standard.copy(rows = rows, loading = false)) } },
            onError = { error ->
                setState { copy(standard = standard.copy(loading = false)) }
                sendEffect(FormSignatureEffect.Failed(error.userMessage))
            },
        )
    }

    private fun loadDocuments() {
        setState { copy(documents = documents.copy(loading = true)) }
        launchResult(
            block = { repository.documents(currentState.documents.tab) },
            onSuccess = { rows -> setState { copy(documents = documents.copy(rows = rows, loading = false)) } },
            onError = { error ->
                setState { copy(documents = documents.copy(loading = false)) }
                sendEffect(FormSignatureEffect.Failed(error.userMessage))
            },
        )
    }

    private fun loadSignatures() {
        setState { copy(signatures = signatures.copy(loading = true)) }
        launchResult(
            block = { repository.signatures() },
            onSuccess = { blocks ->
                setState { copy(signatures = signatures.copy(blocks = blocks, loading = false)) }
                fetchSignatureImages(blocks)
            },
            onError = { setState { copy(signatures = signatures.copy(loading = false)) } },
        )
    }

    private fun fetchSignatureImages(blocks: List<SignatureBlock>) {
        blocks.forEach { block ->
            val image = block.image ?: return@forEach
            if (currentState.signatures.images.containsKey(block.id)) return@forEach
            launch {
                val bytes = (transfer.fetch(image) as? ZillitResult.Success)?.data ?: return@launch
                setState {
                    copy(signatures = signatures.copy(images = signatures.images + (block.id to bytes)))
                }
            }
        }
    }

    // ------------------------------------------------------------------ detail

    private fun openStandardForm(form: StandardForm) {
        val mine = currentState.standard.tab == StandardTab.Mine
        val source = if (mine) DetailSource.StandardMine else DetailSource.StandardAll
        openDetail(
            DetailState(
                source = source,
                documentId = form.id,
                title = form.name.ifBlank { "Document" },
                stored = form.document,
                canSign = mine && !form.signedBy(currentState.currentUserId),
                alreadySigned = form.signedBy(currentState.currentUserId),
            ),
        )
    }

    private fun openDocument(document: SignDocument) {
        val me = currentState.currentUserId
        val tab = currentState.documents.tab
        val mySigner = document.signer(me)
        val canSign = !document.finalized && when (tab) {
            SignDocumentTab.Received -> mySigner != null && !mySigner.signed
            SignDocumentTab.Uploaded ->
                document.userSignatureRequired && (mySigner == null || !mySigner.signed)
            SignDocumentTab.Finalized -> false
        }
        openDetail(
            DetailState(
                source = DetailSource.ForSignature,
                documentId = document.id,
                title = document.name.ifBlank { "Document" },
                stored = document.current,
                mySpots = document.pendingSpotsFor(me),
                canSign = canSign,
                alreadySigned = mySigner?.signed == true || document.finalized,
            ),
        )
    }

    private fun openDetail(detail: DetailState) {
        detailPdf = null
        val stored = detail.stored
        if (stored == null || !stored.isPdf) {
            setState { copy(detail = detail.copy(loadingPages = false, notPdf = true)) }
            return
        }
        setState { copy(detail = detail) }
        launch {
            when (val fetched = transfer.fetch(stored)) {
                is ZillitResult.Failure -> {
                    setState { copy(detail = currentState.detail?.copy(loadingPages = false)) }
                    sendEffect(FormSignatureEffect.Failed(fetched.error.userMessage))
                }
                is ZillitResult.Success -> {
                    detailPdf = fetched.data
                    when (val pages = pdfWork.renderPages(fetched.data, PAGE_RENDER_WIDTH)) {
                        is ZillitResult.Failure -> setState {
                            copy(detail = currentState.detail?.copy(loadingPages = false, notPdf = true))
                        }
                        is ZillitResult.Success -> setState {
                            copy(
                                detail = currentState.detail?.copy(
                                    pages = pages.data,
                                    loadingPages = false,
                                ),
                            )
                        }
                    }
                }
            }
        }
    }

    /** Free placement: only offered when the sender placed no boxes for me. */
    private fun placeFreeSpot(page: Int, xPx: Float, yPx: Float) {
        val detail = currentState.detail ?: return
        if (!detail.canSign || detail.mySpots.isNotEmpty()) return
        val pageImage = detail.pages.firstOrNull { it.page == page } ?: return
        val spot = pageImage.spotFromTap(
            xPx = xPx,
            yPx = yPx,
            kind = SignSpotKind.Signature,
            spotWidth = FREE_SPOT_WIDTH,
            spotHeight = FREE_SPOT_HEIGHT,
        )
        setState { copy(detail = detail.copy(freeSpot = spot)) }
    }

    private fun signOpenDocument() {
        val detail = currentState.detail ?: return
        val pdf = detailPdf
        if (!detail.readyToSign || pdf == null) return
        setState { copy(detail = detail.copy(signing = true)) }
        launch {
            val outcome = runSign(detail, pdf)
            when (outcome) {
                is ZillitResult.Failure -> {
                    setState { copy(detail = currentState.detail?.copy(signing = false)) }
                    sendEffect(FormSignatureEffect.Failed(outcome.error.userMessage))
                }
                is ZillitResult.Success -> {
                    detailPdf = null
                    setState { copy(detail = null) }
                    sendEffect(FormSignatureEffect.Notice("Signed and sent back."))
                    refresh()
                }
            }
        }
    }

    /** The whole signing pipeline; the first failure wins. */
    private suspend fun runSign(detail: DetailState, pdf: ByteArray): ZillitResult<Unit> {
        val spots = detail.mySpots.ifEmpty { listOfNotNull(detail.freeSpot) }

        val stamps = mutableListOf<PlacedStamp>()
        for (spot in spots) {
            when (val ink = signatureImageFor(spot.kind)) {
                is ZillitResult.Failure -> return ink
                is ZillitResult.Success -> stamps += PlacedStamp(ink.data, spot)
            }
        }

        val flattened = when (val stamped = pdfWork.stamp(pdf, stamps)) {
            is ZillitResult.Failure -> return stamped
            is ZillitResult.Success -> stamped.data
        }

        val fileName = detail.stored?.name?.ifBlank { null } ?: "${detail.title}.pdf"
        val uploaded = when (
            val stored = transfer.store(UploadPurpose.Document, fileName, PDF_MIME, flattened)
        ) {
            is ZillitResult.Failure -> return stored
            is ZillitResult.Success -> stored.data.copy(name = fileName)
        }

        return when (detail.source) {
            DetailSource.StandardMine -> repository.signStandardForm(detail.documentId, uploaded)
            DetailSource.ForSignature -> repository.signDocument(detail.documentId, uploaded)
            DetailSource.StandardAll -> ZillitResult.Success(Unit)
        }
    }

    /** The saved block for [kind], fetched, or a message telling the user to make one. */
    private suspend fun signatureImageFor(kind: SignSpotKind): ZillitResult<ByteArray> {
        val block = currentState.signatures.blocks.firstOrNull {
            it.isSignature == (kind == SignSpotKind.Signature)
        }
        val image = block?.image
            ?: return ZillitResult.Failure(
                com.zillit.desktop.core.common.ZillitError.Validation(
                    "Set up your ${kind.label.lowercase()} in Signature block first.",
                ),
            )
        currentState.signatures.images[block.id]?.let { return ZillitResult.Success(it) }
        return transfer.fetch(image)
    }

    // ---------------------------------------------------------------- library

    private fun selfAssign(formId: String) {
        launchResult(
            block = { repository.selfAssign(formId) },
            onSuccess = {
                sendEffect(FormSignatureEffect.Notice("Added to your documents."))
                loadStandardForms()
            },
            onError = { sendEffect(FormSignatureEffect.Failed(it.userMessage)) },
        )
    }

    private fun deleteStandardForm(formId: String) {
        launchResult(
            block = { repository.deleteStandardForm(formId) },
            onSuccess = { loadStandardForms() },
            onError = { sendEffect(FormSignatureEffect.Failed(it.userMessage)) },
        )
    }

    private fun deleteDocument(documentId: String) {
        launchResult(
            block = { repository.deleteDocument(documentId) },
            onSuccess = { loadDocuments() },
            onError = { sendEffect(FormSignatureEffect.Failed(it.userMessage)) },
        )
    }

    private fun showHistory(documentId: String) {
        setState { copy(history = HistoryState(documentId = documentId)) }
        launchResult(
            block = { repository.history(documentId) },
            onSuccess = { entries ->
                setState { copy(history = history?.copy(entries = entries, loading = false)) }
            },
            onError = { setState { copy(history = history?.copy(loading = false)) } },
        )
    }

    private fun submitUploadForm() {
        val form = currentState.uploadForm ?: return
        val bytes = form.fileBytes ?: return
        setState { copy(uploadForm = form.copy(uploading = true)) }
        launch {
            val stored = transfer.store(UploadPurpose.Document, form.fileName, PDF_MIME, bytes)
            when (stored) {
                is ZillitResult.Failure -> {
                    setState { copy(uploadForm = currentState.uploadForm?.copy(uploading = false)) }
                    sendEffect(FormSignatureEffect.Failed(stored.error.userMessage))
                }
                is ZillitResult.Success -> {
                    val saved = repository.addStandardForm(
                        document = stored.data.copy(name = form.fileName),
                        type = form.type,
                        note = form.note,
                    )
                    when (saved) {
                        is ZillitResult.Failure -> {
                            setState {
                                copy(uploadForm = currentState.uploadForm?.copy(uploading = false))
                            }
                            sendEffect(FormSignatureEffect.Failed(saved.error.userMessage))
                        }
                        is ZillitResult.Success -> {
                            setState { copy(uploadForm = null) }
                            sendEffect(FormSignatureEffect.Notice("Document published."))
                            loadStandardForms()
                        }
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------- send

    private fun filePicked(name: String, bytes: ByteArray) {
        when (pendingPick) {
            PickTarget.StandardForm -> setState {
                copy(uploadForm = UploadFormState(fileName = name, fileBytes = bytes))
            }
            PickTarget.SendDocument -> startSendWith(name, bytes)
            null -> Unit
        }
        pendingPick = null
    }

    private fun startSendWith(name: String, bytes: ByteArray) {
        val pages = when (val rendered = pdfWork.renderPages(bytes, PAGE_RENDER_WIDTH)) {
            is ZillitResult.Failure -> {
                sendEffect(FormSignatureEffect.Failed("Only PDF documents can be sent for signature."))
                return
            }
            is ZillitResult.Success -> rendered.data
        }
        setState {
            copy(
                send = SendState(
                    fileName = name,
                    fileBytes = bytes,
                    title = name.removeSuffix(".pdf"),
                    pages = pages,
                ),
            )
        }
        launchResult(
            block = { repository.signerOptions() },
            onSuccess = { options ->
                setState { copy(send = send?.copy(options = options)) }
            },
            onError = { sendEffect(FormSignatureEffect.Failed(it.userMessage)) },
        )
    }

    private fun beginPlacement() {
        val send = currentState.send ?: return
        if (send.chosen.isEmpty()) {
            sendEffect(FormSignatureEffect.Failed("Choose at least one signer first."))
            return
        }
        setState { copy(send = send.copy(placing = true, activeSigner = send.chosen.first())) }
    }

    private fun placeSendSpot(page: Int, xPx: Float, yPx: Float) {
        val send = currentState.send ?: return
        val signer = send.activeSigner ?: return
        val pageImage = send.pages.firstOrNull { it.page == page } ?: return
        val spot = pageImage.spotFromTap(
            xPx = xPx,
            yPx = yPx,
            kind = send.activeKind,
            spotWidth = FREE_SPOT_WIDTH,
            spotHeight = FREE_SPOT_HEIGHT,
        )
        val mine = send.spots[signer].orEmpty() + spot
        setState { copy(send = send.copy(spots = send.spots + (signer to mine))) }
    }

    private fun removeSendSpot(signer: String, index: Int) {
        val send = currentState.send ?: return
        val mine = send.spots[signer].orEmpty().filterIndexed { i, _ -> i != index }
        setState { copy(send = send.copy(spots = send.spots + (signer to mine))) }
    }

    private fun submitSend() {
        val send = currentState.send ?: return
        val bytes = send.fileBytes ?: return
        if (!send.everySignerCovered) {
            sendEffect(
                FormSignatureEffect.Failed("Every signer needs at least one signature box."),
            )
            return
        }
        setState { copy(send = send.copy(sending = true)) }
        launch {
            val fileName = send.title.ifBlank { send.fileName }.ensurePdfName()
            val stored = transfer.store(UploadPurpose.Document, fileName, PDF_MIME, bytes)
            when (stored) {
                is ZillitResult.Failure -> {
                    setState { copy(send = currentState.send?.copy(sending = false)) }
                    sendEffect(FormSignatureEffect.Failed(stored.error.userMessage))
                }
                is ZillitResult.Success -> {
                    val signers = send.chosen.mapIndexed { index, userId ->
                        val option = send.options.firstOrNull { it.userId == userId }
                        DocumentSigner(
                            userId = userId,
                            email = option?.email.orEmpty(),
                            fullName = option?.fullName.orEmpty(),
                            order = index + 1,
                            spots = send.spots[userId].orEmpty(),
                        )
                    }
                    val sent = repository.sendForSignature(
                        document = stored.data.copy(name = fileName),
                        signers = signers,
                        onlySignatureRequired = send.onlySignature,
                        userSignatureRequired = send.senderSigns,
                    )
                    when (sent) {
                        is ZillitResult.Failure -> {
                            setState { copy(send = currentState.send?.copy(sending = false)) }
                            sendEffect(FormSignatureEffect.Failed(sent.error.userMessage))
                        }
                        is ZillitResult.Success -> {
                            setState { copy(send = null) }
                            sendEffect(FormSignatureEffect.Notice("Sent for signature."))
                            loadDocuments()
                        }
                    }
                }
            }
        }
    }

    // -------------------------------------------------------------- signature

    private fun submitDraw() {
        val draw = currentState.draw ?: return
        if (draw.strokes.none { it.size > 1 }) {
            val kind = if (draw.isSignature) "signature" else "initials"
            sendEffect(FormSignatureEffect.Failed("Draw your $kind first."))
            return
        }
        setState { copy(draw = draw.copy(saving = true)) }
        launch {
            val png = when (
                val raster = pdfWork.rasterizeStrokes(draw.strokes, DRAW_WIDTH, DRAW_HEIGHT)
            ) {
                is ZillitResult.Failure -> {
                    setState { copy(draw = currentState.draw?.copy(saving = false)) }
                    sendEffect(FormSignatureEffect.Failed(raster.error.userMessage))
                    return@launch
                }
                is ZillitResult.Success -> raster.data
            }
            val stored = transfer.store(
                purpose = UploadPurpose.SignatureImage,
                fileName = "${newId()}.png",
                contentType = "image/png",
                bytes = png,
            )
            when (stored) {
                is ZillitResult.Failure -> {
                    setState { copy(draw = currentState.draw?.copy(saving = false)) }
                    sendEffect(FormSignatureEffect.Failed(stored.error.userMessage))
                }
                is ZillitResult.Success -> {
                    val label = draw.name.ifBlank { if (draw.isSignature) "Signature" else "Initials" }
                    val saved = repository.saveSignature(
                        image = stored.data,
                        name = label,
                        isSignature = draw.isSignature,
                        existingId = draw.existingId,
                    )
                    when (saved) {
                        is ZillitResult.Failure -> {
                            setState { copy(draw = currentState.draw?.copy(saving = false)) }
                            sendEffect(FormSignatureEffect.Failed(saved.error.userMessage))
                        }
                        is ZillitResult.Success -> {
                            setState { copy(draw = null) }
                            loadSignatures()
                        }
                    }
                }
            }
        }
    }

    private fun deleteSignature(blockId: String) {
        launchResult(
            block = { repository.deleteSignature(blockId) },
            onSuccess = { loadSignatures() },
            onError = { sendEffect(FormSignatureEffect.Failed(it.userMessage)) },
        )
    }

    private fun String.ensurePdfName(): String =
        if (endsWith(".pdf", ignoreCase = true)) this else "$this.pdf"

    private enum class PickTarget { StandardForm, SendDocument }

    private companion object {
        const val PAGE_RENDER_WIDTH = 800
        const val PDF_MIME = "application/pdf"

        // The default box, PDF points — the same footprint the web places.
        const val FREE_SPOT_WIDTH = 160.0
        const val FREE_SPOT_HEIGHT = 56.0

        // The drawing canvas, matching the web's 800×300.
        const val DRAW_WIDTH = 800
        const val DRAW_HEIGHT = 300
    }
}
