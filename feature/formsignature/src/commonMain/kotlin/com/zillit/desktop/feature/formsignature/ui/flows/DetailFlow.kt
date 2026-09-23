@file:Suppress("TooManyFunctions") // One function per user act on the document; merging them hides the acts.

package com.zillit.desktop.feature.formsignature.ui.flows

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.formsignature.domain.FormSignatureBadges
import com.zillit.desktop.feature.formsignature.domain.FormSignatureHost
import com.zillit.desktop.feature.formsignature.domain.FormSignatureRepository
import com.zillit.desktop.feature.formsignature.domain.PdfPageImage
import com.zillit.desktop.feature.formsignature.domain.PdfWork
import com.zillit.desktop.feature.formsignature.domain.PlacedStamp
import com.zillit.desktop.feature.formsignature.domain.SignDocument
import com.zillit.desktop.feature.formsignature.domain.SignDocumentTab
import com.zillit.desktop.feature.formsignature.domain.SignFileTransfer
import com.zillit.desktop.feature.formsignature.domain.SignSpot
import com.zillit.desktop.feature.formsignature.domain.SignatureBlock
import com.zillit.desktop.feature.formsignature.domain.StandardForm
import com.zillit.desktop.feature.formsignature.domain.StoredDocument
import com.zillit.desktop.feature.formsignature.domain.UploadPurpose
import com.zillit.desktop.feature.formsignature.ui.ConfirmState
import com.zillit.desktop.feature.formsignature.ui.DetailSource
import com.zillit.desktop.feature.formsignature.ui.DetailState
import com.zillit.desktop.feature.formsignature.ui.FormSignScreen
import com.zillit.desktop.feature.formsignature.ui.FormSignStore
import com.zillit.desktop.feature.formsignature.ui.FreeMark
import com.zillit.desktop.feature.formsignature.ui.PAGE_RENDER_WIDTH
import com.zillit.desktop.feature.formsignature.ui.PDF_MIME
import com.zillit.desktop.feature.formsignature.ui.PickPurpose
import com.zillit.desktop.feature.formsignature.ui.PickerState
import com.zillit.desktop.feature.formsignature.ui.StandardTab
import com.zillit.desktop.feature.formsignature.ui.orFail

/**
 * An open document — the web's `FormDetailsV2`.
 *
 * ## Signing is local work first, a call second
 *
 * The server's contract is "hand me the finished file": the reader stamps
 * their saved marks into the PDF here — into the placeholders the sender
 * placed, or wherever they drag a mark — and only **Send Document** uploads
 * the result and tells the service the document is signed. Every step can
 * fail separately, and the order matters: a sign call before the upload has
 * landed would point the server at a key that does not exist yet.
 */
internal class DetailFlow(
    private val store: FormSignStore,
    private val repository: FormSignatureRepository,
    private val transfer: SignFileTransfer,
    private val pdfWork: PdfWork,
    private val host: FormSignatureHost,
    private val badges: FormSignatureBadges,
    private val marks: MarksFlow,
    /** The list behind the document refetches once it has been signed. */
    private val onSigned: () -> Unit,
) {

    /** The open document's bytes — held here, not in state, by weight. */
    private var pdf: ByteArray? = null

    /** The bytes as fetched, for a Word document that cannot be shown as pages. */
    private var original: ByteArray? = null

    fun openStandardForm(form: StandardForm) {
        val me = store.state.currentUserId
        val mine = store.state.standard.tab == StandardTab.Mine
        badges.readStandardForm(form.id)
        open(
            DetailState(
                source = if (mine) DetailSource.MyDownloads else DetailSource.LibraryAll,
                documentId = form.id,
                title = form.name.ifBlank { str(S.document) },
                stored = form.current,
                canSign = mine && !form.signedBy(me),
                alreadySigned = form.signedBy(me),
            ),
        )
    }

    fun openDocument(document: SignDocument) {
        val me = store.state.currentUserId
        val tab = store.state.documents.tab
        open(
            DetailState(
                source = DetailSource.ForSignature(tab),
                documentId = document.id,
                title = document.name.ifBlank { str(S.document) },
                stored = document.current,
                placeholderFlow = document.hasPlaceholders,
                placeholders = document.pendingSpotsFor(me),
                // The web offers signing on Received (and My Downloads); the
                // sent list is the sender's own view.
                canSign = tab == SignDocumentTab.Received && !document.signedBy(me),
                alreadySigned = document.signedBy(me),
            ),
        )
    }

    private fun open(detail: DetailState) {
        pdf = null
        original = null
        store.update { copy(detail = detail, screen = FormSignScreen.Detail) }
        val stored = detail.stored ?: run {
            store.update { copy(detail = detail.copy(loadingPages = false, notPdf = true)) }
            return
        }
        store.launch {
            val bytes = store.orFail(transfer.fetch(stored)) ?: run {
                store.update { copy(detail = detail.copy(loadingPages = false, notPdf = true)) }
                return@launch
            }
            original = bytes
            if (!stored.isPdf && stored.isWord) {
                store.update { copy(detail = detail.copy(loadingPages = false, notPdf = true)) }
                return@launch
            }
            showPages(bytes)
        }
    }

    private fun showPages(bytes: ByteArray) {
        when (val pages = pdfWork.renderPages(bytes, PAGE_RENDER_WIDTH)) {
            is ZillitResult.Failure -> store.update {
                copy(detail = detail?.copy(loadingPages = false, notPdf = true, busy = false))
            }
            is ZillitResult.Success -> {
                pdf = bytes
                store.update {
                    copy(detail = detail?.copy(pages = pages.data, loadingPages = false, notPdf = false, busy = false))
                }
            }
        }
    }

    fun close(force: Boolean = false) {
        val detail = store.state.detail ?: return
        if (detail.signedLocally && !force) {
            store.update { copy(confirm = ConfirmState.LeaveSigned) }
            return
        }
        pdf = null
        original = null
        val back = when (detail.source) {
            DetailSource.LibraryAll, DetailSource.MyDownloads -> FormSignScreen.StandardDocuments
            is DetailSource.ForSignature -> FormSignScreen.DocumentsForSignature
        }
        store.update { copy(detail = null, picker = null, screen = back) }
    }

    fun turnPage(delta: Int) = store.update {
        val d = detail
        // The web disables paging while a mark is being positioned.
        if (d == null || d.freeMark != null) return@update this
        copy(detail = d.copy(page = (d.page + delta).coerceIn(0, (d.pageCount - 1).coerceAtLeast(0))))
    }

    // ------------------------------------------------------------ placeholders

    /** A placeholder clicked: the picker opens on its kind, and the pick lands straight in it. */
    fun tapPlaceholder(spot: SignSpot) {
        val detail = store.state.detail ?: return
        if (!detail.canSign || detail.busy) return
        store.update { copy(picker = PickerState(purpose = PickPurpose.FillPlaceholder(spot), kind = spot.kind)) }
    }

    fun fillPlaceholder(spot: SignSpot, block: SignatureBlock) {
        val bytes = pdf ?: return
        store.update { copy(picker = null, detail = detail?.copy(busy = true)) }
        store.launch {
            val png = store.orFail(marks.imageOf(block)) ?: return@launch unbusy()
            val stamped = store.orFail(pdfWork.stamp(bytes, listOf(PlacedStamp(png, spot)))) ?: return@launch unbusy()
            val page = store.orFail(pdfWork.renderPage(stamped, spot.page, PAGE_RENDER_WIDTH)) ?: return@launch unbusy()
            pdf = stamped
            store.update {
                copy(
                    detail = detail?.copy(
                        pages = detail.pages.replacing(page),
                        placeholders = detail.placeholders - spot,
                        signedLocally = true,
                        busy = false,
                    ),
                )
            }
        }
    }

    // ---------------------------------------------------------- free placement

    /**
     * "Add Signature": the picker, over both kinds. A Word document is
     * converted first — the web's `convertToPdf` on the same button.
     */
    fun addSignature() {
        val detail = store.state.detail ?: return
        if (!detail.offersFreeSign || detail.busy) return
        if (detail.notPdf) {
            val source = original ?: return
            store.update { copy(detail = detail.copy(busy = true)) }
            store.launch {
                val converted = store.orFail(host.convertToPdf(detail.stored?.name ?: detail.title, source))
                    ?: return@launch unbusy()
                showPages(converted)
                if (store.state.detail?.notPdf == false) openFreePicker()
            }
            return
        }
        openFreePicker()
    }

    private fun openFreePicker() = store.update { copy(picker = PickerState(purpose = PickPurpose.FreeOnDetail)) }

    /** A saved mark picked for free placement: it appears at the web's default spot, ready to drag. */
    fun placeFreeMark(block: SignatureBlock) {
        val detail = store.state.detail ?: return
        store.update { copy(picker = null, detail = detail.copy(busy = true)) }
        store.launch {
            val png = store.orFail(marks.imageOf(block)) ?: return@launch unbusy()
            val aspect = store.orFail(pdfWork.imageSize(png))?.let { (w, h) -> h.toFloat() / w }
                ?: return@launch unbusy()
            store.update {
                copy(
                    detail = detail.copy(
                        busy = false,
                        freeMark = FreeMark(
                            png = png,
                            kind = block.kind,
                            page = detail.current?.page ?: 1,
                            x = FREE_MARK_X,
                            y = FREE_MARK_Y,
                            width = FREE_MARK_WIDTH,
                            aspect = aspect,
                        ),
                    ),
                )
            }
        }
    }

    fun moveFreeMark(x: Float, y: Float, width: Float) = store.update {
        val mark = detail?.freeMark ?: return@update this
        val page = detail.current ?: return@update this
        val w = width.coerceIn(MIN_MARK_WIDTH, page.widthPx.toFloat())
        copy(
            detail = detail.copy(
                freeMark = mark.copy(
                    width = w,
                    x = x.coerceIn(0f, (page.widthPx - w).coerceAtLeast(0f)),
                    y = y.coerceIn(0f, (page.heightPx - w * mark.aspect).coerceAtLeast(0f)),
                ),
            ),
        )
    }

    fun cancelFreeMark() = store.update { copy(detail = detail?.copy(freeMark = null)) }

    /** "Sign Document" in the free flow: the dragged mark becomes ink where it sits. */
    fun confirmFreeMark() {
        val detail = store.state.detail ?: return
        val mark = detail.freeMark ?: return
        val page = detail.pages.firstOrNull { it.page == mark.page } ?: return
        val bytes = pdf ?: return
        val spot = page.spotFromRect(mark.x, mark.y, mark.width, mark.height, mark.kind)
        store.update { copy(detail = detail.copy(busy = true)) }
        store.launch {
            val stamped = store.orFail(pdfWork.stamp(bytes, listOf(PlacedStamp(mark.png, spot))))
                ?: return@launch unbusy()
            val rendered = store.orFail(pdfWork.renderPage(stamped, spot.page, PAGE_RENDER_WIDTH))
                ?: return@launch unbusy()
            pdf = stamped
            store.update {
                copy(
                    detail = detail.copy(
                        pages = detail.pages.replacing(rendered),
                        freeMark = null,
                        signedLocally = true,
                        busy = false,
                    ),
                )
            }
        }
    }

    // ------------------------------------------------------------------ send

    fun askSend() {
        val detail = store.state.detail ?: return
        if (!detail.readyToSend) return
        store.update { copy(confirm = ConfirmState.SendSigned) }
    }

    /** Upload the stamped file, then tell the service — the web's `handleSignDocument`. */
    fun send() {
        val detail = store.state.detail ?: return
        val bytes = pdf ?: return
        if (!detail.readyToSend) return
        store.update { copy(detail = detail.copy(sending = true)) }
        store.launch {
            val fileName = detail.stored?.name?.ifBlank { null } ?: detail.title
            val uploaded = store.orFail(
                transfer.store(UploadPurpose.Document, fileName.ensurePdfName(), PDF_MIME, bytes),
            ) ?: return@launch store.update { copy(detail = detail.copy(sending = false)) }
            val signed = uploaded.copy(name = fileName, contentSubtype = StoredDocument.SUBTYPE_PDF)
            val answer = when (detail.source) {
                DetailSource.MyDownloads -> repository.signStandardForm(detail.documentId, signed)
                is DetailSource.ForSignature -> repository.signDocument(detail.documentId, signed)
                DetailSource.LibraryAll -> ZillitResult.Success(Unit)
            }
            when (answer) {
                is ZillitResult.Failure -> {
                    store.update { copy(detail = detail.copy(sending = false)) }
                    store.fail(answer.error)
                }
                is ZillitResult.Success -> {
                    store.notice(str(S.desktop_fs_document_signed_and_sent))
                    close(force = true)
                    onSigned()
                }
            }
        }
    }

    // ---------------------------------------------------------- side actions

    /** "Download in device" — the stamped PDF when there is one, else the file as stored. */
    fun download() {
        val detail = store.state.detail ?: return
        val bytes = pdf ?: original ?: return
        val name = detail.stored?.name?.ifBlank { null } ?: detail.title
        val fileName = if (pdf != null) name.ensurePdfName() else name.withExtension(detail.stored?.extension)
        store.launch {
            when (val saved = host.saveToDownloads(fileName, bytes)) {
                is ZillitResult.Failure -> store.fail(saved.error)
                is ZillitResult.Success -> store.notice(str(S.docusign_signing_attachment_saved))
            }
        }
    }

    fun print() {
        val detail = store.state.detail ?: return
        val bytes = pdf ?: return
        val name = (detail.stored?.name?.ifBlank { null } ?: detail.title).ensurePdfName()
        store.launch { store.orFail(host.print(name, bytes)) }
    }

    /** The library detail's "Transfer this form to your My Downloads". */
    fun transferToDownloads() {
        val detail = store.state.detail ?: return
        if (detail.source != DetailSource.LibraryAll || detail.transferring) return
        store.update { copy(detail = detail.copy(transferring = true)) }
        store.launch {
            when (val added = repository.selfAssign(detail.documentId)) {
                is ZillitResult.Failure -> store.fail(added.error)
                is ZillitResult.Success -> store.notice(added.data.ifBlank { str(S.desktop_fs_added_to_my_downloads) })
            }
            store.update { copy(detail = detail.copy(transferring = false)) }
        }
    }

    private fun unbusy() = store.update { copy(detail = detail?.copy(busy = false)) }

    private fun List<PdfPageImage>.replacing(page: PdfPageImage): List<PdfPageImage> =
        map { if (it.page == page.page) page else it }

    private fun String.ensurePdfName(): String =
        if (endsWith(".pdf", ignoreCase = true)) this else "${substringBeforeLast('.', this)}.pdf"

    private fun String.withExtension(extension: String?): String {
        val ext = extension?.lowercase()?.takeIf { it.isNotBlank() } ?: return this
        return if (endsWith(".$ext", ignoreCase = true)) this else "$this.$ext"
    }

    private companion object {
        // The web's `ResizableImage` defaults: 200 wide at (300, 300).
        const val FREE_MARK_X = 300f
        const val FREE_MARK_Y = 300f
        const val FREE_MARK_WIDTH = 200f
        const val MIN_MARK_WIDTH = 40f
    }
}
