@file:Suppress("TooManyFunctions") // One function per user act on the drawer; merging them hides the acts.

package com.zillit.desktop.feature.formsignature.ui.flows

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.formsignature.domain.DocumentSigner
import com.zillit.desktop.feature.formsignature.domain.FormSignatureHost
import com.zillit.desktop.feature.formsignature.domain.FormSignatureRepository
import com.zillit.desktop.feature.formsignature.domain.PdfPageImage
import com.zillit.desktop.feature.formsignature.domain.PdfWork
import com.zillit.desktop.feature.formsignature.domain.PlacedStamp
import com.zillit.desktop.feature.formsignature.domain.SignFileTransfer
import com.zillit.desktop.feature.formsignature.domain.SignSpotKind
import com.zillit.desktop.feature.formsignature.domain.SignatureBlock
import com.zillit.desktop.feature.formsignature.domain.StoredDocument
import com.zillit.desktop.feature.formsignature.domain.UploadPurpose
import com.zillit.desktop.feature.formsignature.ui.DraftBox
import com.zillit.desktop.feature.formsignature.ui.FormSignStore
import com.zillit.desktop.feature.formsignature.ui.FormSignatureEffect
import com.zillit.desktop.feature.formsignature.ui.FreeMark
import com.zillit.desktop.feature.formsignature.ui.PAGE_RENDER_WIDTH
import com.zillit.desktop.feature.formsignature.ui.PDF_MIME
import com.zillit.desktop.feature.formsignature.ui.PickKind
import com.zillit.desktop.feature.formsignature.ui.PickPurpose
import com.zillit.desktop.feature.formsignature.ui.PickTarget
import com.zillit.desktop.feature.formsignature.ui.PickerState
import com.zillit.desktop.feature.formsignature.ui.SendState
import com.zillit.desktop.feature.formsignature.ui.SendStep
import com.zillit.desktop.feature.formsignature.ui.orFail

/**
 * Upload a document for signature — the web's `UploadDocumentForSignatureV2`
 * drawer, step for step: name + file + who signs + which marks; then, per
 * signer, boxes dragged onto the pages, and the sender's own marks stamped
 * in when they must sign too.
 */
internal class SendFlow(
    private val store: FormSignStore,
    private val repository: FormSignatureRepository,
    private val transfer: SignFileTransfer,
    private val pdfWork: PdfWork,
    private val host: FormSignatureHost,
    private val marks: MarksFlow,
    /** The sent list refetches once a document has gone out. */
    private val onSent: () -> Unit,
) {

    fun start() {
        store.update { copy(send = SendState(loadingPeople = true)) }
        store.launch {
            val me = store.state.currentUserId
            // The web's `onlyViewingRightsUsers`, minus the sender and anyone who left, by name.
            val options = (repository.signerOptions() as? ZillitResult.Success)?.data.orEmpty()
                .filter { it.userId != me && it.status != STATUS_LEFT }
                .sortedBy { it.label.lowercase() }
            val externals = host.externalSigners()
            store.update {
                copy(send = send?.copy(options = options, externals = externals, loadingPeople = false))
            }
        }
    }

    fun edit(state: SendState) = store.update { copy(send = state) }

    fun pickFile() = store.effect(FormSignatureEffect.PickFile(PickTarget.SendDocument, PickKind.PdfOnly))

    /** ZL-17750: this flow takes PDFs only; anything PDFBox cannot open is refused here. */
    fun filePicked(name: String, bytes: ByteArray) {
        val send = store.state.send ?: return
        if (!name.endsWith(".pdf", ignoreCase = true) || pdfWork.pageCount(bytes) !is ZillitResult.Success) {
            store.fail("PDF format only")
            return
        }
        store.update {
            copy(
                send = send.copy(
                    fileName = name,
                    fileBytes = bytes,
                    stampedBytes = null,
                    title = send.title.ifBlank { name.removeSuffix(".pdf").removeSuffix(".PDF") },
                    pages = emptyList(),
                    spots = emptyMap(),
                    senderSignaturePlaced = false,
                    senderInitialsPlaced = false,
                ),
            )
        }
    }

    /** Step one's gate, in the web's order, then the pages for step two. */
    fun next() {
        val send = store.state.send ?: return
        val bytes = send.fileBytes
        val problem = when {
            send.title.isBlank() -> "Please add a document name"
            bytes == null -> "Please upload a document"
            send.people.isEmpty() -> "Please select atleast one member"
            send.hasExternal && send.chosenExternal.isEmpty() -> "Select External Users"
            else -> null
        }
        if (problem != null || bytes == null) {
            store.fail(problem ?: "Please upload a document")
            return
        }
        store.update { copy(send = send.copy(busy = true)) }
        store.launch {
            val pages = store.orFail(pdfWork.renderPages(send.stampedBytes ?: bytes, PAGE_RENDER_WIDTH))
                ?: return@launch store.update { copy(send = send.copy(busy = false)) }
            store.update {
                copy(
                    send = send.copy(
                        pages = pages,
                        page = 0,
                        step = SendStep.Place,
                        activeSigner = send.activeSigner ?: send.people.firstOrNull()?.id,
                        busy = false,
                    ),
                )
            }
        }
    }

    fun back() = store.update { copy(send = send?.copy(step = SendStep.Details, draft = null, senderMark = null)) }

    fun turnPage(delta: Int) = store.update {
        val s = send ?: return@update this
        // Paging is off while a box or a mark is being positioned, as on the web.
        if (s.draft != null || s.senderMark != null) return@update this
        copy(send = s.copy(page = (s.page + delta).coerceIn(0, (s.pages.size - 1).coerceAtLeast(0))))
    }

    fun selectSigner(personId: String) = store.update {
        copy(send = send?.copy(activeSigner = personId, draft = null))
    }

    // -------------------------------------------------------------- boxes

    /** A new placeholder at the web's default spot, ready to drag. */
    fun addPlaceholder(kind: SignSpotKind) = store.update {
        val s = send ?: return@update this
        val page = s.current ?: return@update this
        copy(
            send = s.copy(
                draft = DraftBox(
                    kind = kind,
                    page = page.page,
                    x = DRAFT_X,
                    y = DRAFT_Y,
                    width = if (kind == SignSpotKind.Signature) SIGNATURE_W else INITIALS_W,
                    height = if (kind == SignSpotKind.Signature) SIGNATURE_H else INITIALS_H,
                ),
            ),
        )
    }

    fun moveDraft(x: Float, y: Float, width: Float, height: Float) = store.update {
        val s = send ?: return@update this
        val draft = s.draft ?: return@update this
        val page = s.current ?: return@update this
        val w = width.coerceIn(MIN_BOX, page.widthPx.toFloat())
        val h = height.coerceIn(MIN_BOX, page.heightPx.toFloat())
        copy(
            send = s.copy(
                draft = draft.copy(
                    width = w,
                    height = h,
                    x = x.coerceIn(0f, (page.widthPx - w).coerceAtLeast(0f)),
                    y = y.coerceIn(0f, (page.heightPx - h).coerceAtLeast(0f)),
                ),
            ),
        )
    }

    fun confirmDraft() = store.update {
        val s = send ?: return@update this
        val draft = s.draft ?: return@update this
        val signer = s.activeSigner ?: return@update this
        val page = s.pages.firstOrNull { it.page == draft.page } ?: return@update this
        val spot = page.spotFromRect(draft.x, draft.y, draft.width, draft.height, draft.kind)
        copy(send = s.copy(spots = s.spots + (signer to s.spots[signer].orEmpty() + spot), draft = null))
    }

    fun cancelDraft() = store.update { copy(send = send?.copy(draft = null)) }

    /** A placed box dragged: its top-left moves, its size stays. */
    fun moveSpot(personId: String, index: Int, x: Float, y: Float) = store.update {
        val s = send ?: return@update this
        val mine = s.spots[personId].orEmpty()
        val spot = mine.getOrNull(index) ?: return@update this
        val page = s.pages.firstOrNull { it.page == spot.page } ?: return@update this
        val rect = page.pixelRect(spot)
        val (w, h) = rect[RECT_W] to rect[RECT_H]
        val moved = page.spotFromRect(
            x.coerceIn(0f, (page.widthPx - w).coerceAtLeast(0f)),
            y.coerceIn(0f, (page.heightPx - h).coerceAtLeast(0f)),
            w,
            h,
            spot.kind,
        )
        val replaced = mine.mapIndexed { i, old -> if (i == index) moved else old }
        copy(send = s.copy(spots = s.spots + (personId to replaced)))
    }

    fun removeSpot(personId: String, index: Int) = store.update {
        val s = send ?: return@update this
        val kept = s.spots[personId].orEmpty().filterIndexed { i, _ -> i != index }
        copy(send = s.copy(spots = s.spots + (personId to kept)))
    }

    // -------------------------------------------------------- sender's marks

    fun addOwnMark(kind: SignSpotKind) = store.update {
        copy(picker = PickerState(purpose = PickPurpose.SenderMark(kind), kind = kind))
    }

    fun placeOwnMark(block: SignatureBlock) {
        val send = store.state.send ?: return
        val page = send.current ?: return
        store.update { copy(picker = null, send = send.copy(busy = true)) }
        store.launch {
            val png = store.orFail(marks.imageOf(block)) ?: return@launch unbusy()
            val aspect = store.orFail(pdfWork.imageSize(png))?.let { (w, h) -> h.toFloat() / w }
                ?: return@launch unbusy()
            store.update {
                copy(
                    send = send.copy(
                        busy = false,
                        senderMark = FreeMark(
                            png = png,
                            kind = block.kind,
                            page = page.page,
                            x = MARK_X,
                            y = MARK_Y,
                            width = SIGNATURE_W,
                            aspect = aspect,
                        ),
                    ),
                )
            }
        }
    }

    fun moveOwnMark(x: Float, y: Float, width: Float) = store.update {
        val s = send ?: return@update this
        val mark = s.senderMark ?: return@update this
        val page = s.current ?: return@update this
        val w = width.coerceIn(MIN_BOX, page.widthPx.toFloat())
        copy(
            send = s.copy(
                senderMark = mark.copy(
                    width = w,
                    x = x.coerceIn(0f, (page.widthPx - w).coerceAtLeast(0f)),
                    y = y.coerceIn(0f, (page.heightPx - w * mark.aspect).coerceAtLeast(0f)),
                ),
            ),
        )
    }

    fun cancelOwnMark() = store.update { copy(send = send?.copy(senderMark = null)) }

    /** "Confirm Placement": the sender's mark becomes ink in the copy that will be sent as `signing_document`. */
    fun confirmOwnMark() {
        val send = store.state.send ?: return
        val mark = send.senderMark ?: return
        val page = send.pages.firstOrNull { it.page == mark.page } ?: return
        val source = send.stampedBytes ?: send.fileBytes ?: return
        val spot = page.spotFromRect(mark.x, mark.y, mark.width, mark.height, mark.kind)
        store.update { copy(send = send.copy(busy = true)) }
        store.launch {
            val stamped = store.orFail(pdfWork.stamp(source, listOf(PlacedStamp(mark.png, spot))))
                ?: return@launch unbusy()
            val rendered = store.orFail(pdfWork.renderPage(stamped, spot.page, PAGE_RENDER_WIDTH))
                ?: return@launch unbusy()
            store.update {
                copy(
                    send = send.copy(
                        stampedBytes = stamped,
                        pages = send.pages.replacing(rendered),
                        senderMark = null,
                        senderSignaturePlaced = send.senderSignaturePlaced || mark.kind == SignSpotKind.Signature,
                        senderInitialsPlaced = send.senderInitialsPlaced || mark.kind == SignSpotKind.Initials,
                        busy = false,
                    ),
                )
            }
        }
    }

    // ---------------------------------------------------------------- submit

    /** The web's `handleSubmit` gate, in its order. */
    private fun refusal(send: SendState): String? = when {
        send.senderSigns && !send.senderSignaturePlaced -> "Please add your signature on the document."
        send.senderSigns && !send.onlySignature && !send.senderInitialsPlaced ->
            "Please add your initials on the document."
        send.people.any { send.spotsOf(it.id, SignSpotKind.Signature).isEmpty() } ->
            "Please add a signature placeholder for all users."
        !send.onlySignature && send.people.any { send.spotsOf(it.id, SignSpotKind.Initials).isEmpty() } ->
            "Please add an initials placeholder for all users."
        else -> null
    }

    /** The `users[]` the web builds: crew by option, outsiders by directory entry, in the order chosen. */
    private fun signersOf(send: SendState): List<DocumentSigner> = send.people.mapIndexed { index, person ->
        val option = send.options.firstOrNull { it.userId == person.id }
        val outsider = send.externals.firstOrNull { it.id == person.id }
        DocumentSigner(
            userId = person.id,
            email = if (person.external) outsider?.email.orEmpty() else option?.email.orEmpty(),
            fullName = if (person.external) outsider?.fullName.orEmpty() else option?.fullName.orEmpty(),
            order = index + 1,
            isExternal = person.external,
            // Signature-only documents send no initials boxes, however many were placed.
            spots = send.spots[person.id].orEmpty().filter { !send.onlySignature || it.kind == SignSpotKind.Signature },
        )
    }

    /** The web's `handleSubmit`: the gate, the upload(s), the post. */
    fun submit() {
        val send = store.state.send ?: return
        val bytes = send.fileBytes ?: return
        refusal(send)?.let { problem ->
            store.fail(problem)
            return
        }
        store.update { copy(send = send.copy(sending = true)) }
        store.launch {
            val fileName = send.fileName.ifBlank { "${send.title}.pdf" }
            val uploaded = store.orFail(transfer.store(UploadPurpose.Document, fileName, PDF_MIME, bytes))
                ?: return@launch unsend()
            val document = uploaded.copy(name = send.title.trim(), contentSubtype = StoredDocument.SUBTYPE_PDF)
            // The sender signed: their stamped copy travels beside the original.
            val signingDocument = send.stampedBytes?.takeIf { send.senderSigns }?.let { stamped ->
                store.orFail(transfer.store(UploadPurpose.Document, fileName, PDF_MIME, stamped))
                    ?.copy(name = send.title.trim(), contentSubtype = StoredDocument.SUBTYPE_PDF)
                    ?: return@launch unsend()
            }
            val sent = repository.sendForSignature(
                document = document,
                signingDocument = signingDocument,
                signers = signersOf(send),
                onlySignatureRequired = send.onlySignature,
                userSignatureRequired = send.senderSigns,
            )
            when (sent) {
                is ZillitResult.Failure -> {
                    unsend()
                    store.fail(sent.error)
                }
                is ZillitResult.Success -> {
                    store.update { copy(send = null) }
                    store.notice("Document sent for signature.")
                    onSent()
                }
            }
        }
    }

    fun cancel() = store.update { copy(send = null, picker = null) }

    private fun unbusy() = store.update { copy(send = send?.copy(busy = false)) }
    private fun unsend() = store.update { copy(send = send?.copy(sending = false)) }

    private fun List<PdfPageImage>.replacing(page: PdfPageImage): List<PdfPageImage> =
        map { if (it.page == page.page) page else it }

    private companion object {
        const val STATUS_LEFT = "left"

        // The web's placeholder defaults: 200×60 signature, 150×40 initials, at (100, 100).
        const val SIGNATURE_W = 200f
        const val SIGNATURE_H = 60f
        const val INITIALS_W = 150f
        const val INITIALS_H = 40f
        const val DRAFT_X = 100f
        const val DRAFT_Y = 100f
        const val MARK_X = 100f
        const val MARK_Y = 100f
        const val MIN_BOX = 30f

        // `pixelRect` answers x, y, w, h.
        const val RECT_W = 2
        const val RECT_H = 3
    }
}
