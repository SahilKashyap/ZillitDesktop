package com.zillit.desktop.feature.dealmemo.ui.preview

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.dealmemo.domain.DealDoc
import com.zillit.desktop.feature.dealmemo.domain.DealPdfKind
import com.zillit.desktop.feature.dealmemo.domain.DealSignTarget
import com.zillit.desktop.feature.dealmemo.domain.DocRead
import com.zillit.desktop.feature.dealmemo.domain.preview.AdditionalDoc
import com.zillit.desktop.feature.dealmemo.domain.preview.DealAttachment
import com.zillit.desktop.feature.dealmemo.domain.preview.DealSigner
import com.zillit.desktop.feature.dealmemo.domain.preview.SignSurface
import com.zillit.desktop.feature.dealmemo.domain.preview.SignSurfaceKind
import com.zillit.desktop.feature.dealmemo.domain.preview.SignedPdf
import com.zillit.desktop.feature.dealmemo.ui.DealMemoViewModel
import com.zillit.desktop.feature.dealmemo.ui.DealToastTone
import com.zillit.desktop.feature.dealmemo.ui.SignerEvent
import com.zillit.desktop.feature.dealmemo.ui.documents.DealPdfPage
import com.zillit.desktop.feature.dealmemo.ui.documents.DealPlacement
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Signing one document (`DMSignDocumentModal.jsx`): choose a signature from
 * the E-Signature library or make one, place it on every page it belongs,
 * then flatten, upload and post the signed copy — the server does not
 * flatten, and never sees a coordinate.
 */
@Suppress("TooManyFunctions") // The signing flow's steps, one function each.
internal class SigningActions(private val vm: DealMemoViewModel, private val page: DealPreviewActions) {

    private var sourceJob: Job? = null
    private var libraryJob: Job? = null

    @Suppress("CyclomaticComplexMethod")
    fun onEvent(event: SignerEvent) {
        if (vm.ui.preview?.signer == null) return
        when (event) {
            is SignerEvent.SelectSaved -> updateLibrary { copy(selectedId = event.id) }
            is SignerEvent.AskDeleteSaved -> updateLibrary { copy(pendingDelete = event.id) }
            SignerEvent.ConfirmDeleteSaved -> deleteSaved()
            SignerEvent.CancelDeleteSaved -> updateLibrary { copy(pendingDelete = null) }
            SignerEvent.NewSignature -> updateLibrary { copy(creating = NewSignatureStep.Method) }
            is SignerEvent.Method -> updateLibrary { copy(creating = event.step) }
            SignerEvent.StepBack -> updateLibrary {
                copy(creating = if (creating == NewSignatureStep.Method) null else NewSignatureStep.Method)
            }
            is SignerEvent.TypedText -> updateLibrary { copy(typedText = event.text) }
            is SignerEvent.TypedFont -> updateLibrary { copy(typedFont = event.font) }
            SignerEvent.ToggleSaveForNextTime -> updateLibrary { copy(saveForNextTime = !saveForNextTime) }
            SignerEvent.UseTyped -> useTyped()
            is SignerEvent.UseDrawn -> useDrawn(event)
            SignerEvent.ContinueToSigning -> continueWithSaved()
            SignerEvent.ChangeSignature -> changeSignature()
            is SignerEvent.Zoom -> updateSigner {
                copy(zoom = (zoom + event.delta).coerceIn(SignerState.ZOOM_MIN, SignerState.ZOOM_MAX))
            }
            SignerEvent.Retry -> loadSource()
            is SignerEvent.AddAnother -> updateSigner {
                if (busy || !ready) this else copy(stamps = stamps + event.placement)
            }
            is SignerEvent.RemoveStamp -> updateSigner {
                if (busy) this else copy(stamps = stamps.filterIndexed { index, _ -> index != event.index })
            }
            is SignerEvent.Sign -> sign(event.placement)
            SignerEvent.Cancel -> cancel()
        }
    }

    /** Opens the signer on exactly one document; a signature chosen earlier this visit is reused. */
    fun open(surface: SignSurface, userType: String) {
        val cached = vm.ui.preview?.signature
        page.updatePreview {
            copy(
                signer = SignerState(
                    surface = surface,
                    userType = userType,
                    stage = if (cached == null) SignerStage.Choose else SignerStage.Place,
                ),
            )
        }
        if (cached == null) loadLibrary()
        loadSource()
    }

    private fun cancel() {
        if (vm.ui.preview?.signer?.busy == true) return
        sourceJob?.cancel()
        libraryJob?.cancel()
        page.updatePreview { copy(signer = null) }
    }

    // -- stage one: the signature ----------------------------------------------------------------

    private fun loadLibrary() {
        val store = vm.store
        if (store == null) {
            updateLibrary { copy(loading = false) }
            return
        }
        libraryJob?.cancel()
        updateLibrary { copy(loading = true, failed = false) }
        libraryJob = vm.work {
            when (val result = store.savedSignatures()) {
                is ZillitResult.Success -> {
                    val entries = result.data.map { saved ->
                        val png = saved.image?.takeIf { it.intact }?.let { store.fetch(it).getOrNull() }
                        val image = png?.let { withContext(vm.workDispatcher) { vm.pdf.image(it) } }
                        LibraryEntry(saved.id, image, png)
                    }
                    updateLibrary {
                        copy(
                            loading = false,
                            entries = entries,
                            selectedId = selectedId ?: entries.firstOrNull { it.png != null }?.id,
                        )
                    }
                }
                is ZillitResult.Failure -> updateLibrary { copy(loading = false, failed = true) }
            }
        }
    }

    /** "Continue to Signing": the picked saved signature, fetched fresh from storage. */
    private fun continueWithSaved() {
        val library = vm.ui.preview?.signer?.library ?: return
        val entry = library.entries.firstOrNull { it.id == library.selectedId }
        when {
            entry == null ->
                vm.toast("No signature was selected. Please pick one, or draw a new one.", DealToastTone.Error)
            entry.png == null -> vm.toast(
                "That signature can't be opened from storage — its file reference is incomplete. Please draw a new " +
                    "one.",
                DealToastTone.Error,
            )
            else -> capture(entry.png)
        }
    }

    private fun useTyped() {
        val library = vm.ui.preview?.signer?.library ?: return
        if (library.typedText.isBlank() || library.working) return
        updateLibrary { copy(working = true) }
        vm.work {
            val png = withContext(vm.workDispatcher) { vm.pdf.typed(library.typedText, library.typedFont) }
            finishNew(png, library.saveForNextTime)
        }
    }

    private fun useDrawn(event: SignerEvent.UseDrawn) {
        val library = vm.ui.preview?.signer?.library ?: return
        if (library.working) return
        updateLibrary { copy(working = true) }
        vm.work {
            val png = withContext(vm.workDispatcher) { vm.pdf.ink(event.strokes, event.width, event.height) }
            finishNew(png, library.saveForNextTime)
        }
    }

    /** A new signature is used at once; "Save this for next time" also keeps it in the library. */
    private suspend fun finishNew(png: ByteArray?, save: Boolean) {
        if (png == null) {
            updateLibrary { copy(working = false) }
            vm.toast("No signature was selected. Please pick one, or draw a new one.", DealToastTone.Error)
            return
        }
        if (save) vm.store?.saveSignature(png)?.let { result ->
            (result as? ZillitResult.Failure)?.let { vm.toastError(it.error, "failed_to_save_signature") }
        }
        updateLibrary { copy(working = false, creating = null, typedText = "") }
        capture(png)
    }

    private fun capture(png: ByteArray) {
        vm.work {
            val image = withContext(vm.workDispatcher) { vm.pdf.image(png) }
            if (image == null) {
                vm.toast("Couldn't download that signature. Please try again, or draw a new one.", DealToastTone.Error)
                return@work
            }
            val aspect = if (image.width > 0) image.height.toFloat() / image.width else DEFAULT_ASPECT
            page.updatePreview {
                copy(
                    signature = CapturedSignature(png, image, aspect),
                    signer = signer?.copy(stage = SignerStage.Place, placementEpoch = signer.placementEpoch + 1),
                )
            }
        }
    }

    /** Back to choosing; the signatures already placed stay where they are. */
    private fun changeSignature() {
        if (vm.ui.preview?.signer?.busy == true) return
        page.updatePreview { copy(signature = null, signer = signer?.copy(stage = SignerStage.Choose)) }
        if (vm.ui.preview?.signer?.library?.entries.isNullOrEmpty()) loadLibrary()
    }

    private fun deleteSaved() {
        val id = vm.ui.preview?.signer?.library?.pendingDelete ?: return
        val store = vm.store ?: return
        updateLibrary { copy(pendingDelete = null, working = true) }
        vm.work {
            when (val result = store.deleteSignature(id)) {
                is ZillitResult.Success -> updateLibrary {
                    copy(
                        working = false,
                        entries = entries.filterNot { it.id == id },
                        selectedId = selectedId?.takeIf { it != id },
                    )
                }
                is ZillitResult.Failure -> {
                    updateLibrary { copy(working = false) }
                    vm.toastError(result.error, "something_went_wrong")
                }
            }
        }
    }

    // -- stage two: the document -------------------------------------------------------------------

    /** The stored copy of a signable document, or a fresh render of it; then its pages. */
    fun loadSource() {
        val preview = vm.ui.preview ?: return
        val signer = preview.signer ?: return
        val deal = preview.deal ?: return
        sourceJob?.cancel()
        updateSigner { copy(loading = true, failed = false, source = null, pages = emptyList()) }
        sourceJob = vm.work {
            when (val result = resolveSource(deal, signer.surface)) {
                is ZillitResult.Success -> updateSigner {
                    copy(loading = false, source = result.data.first, pages = result.data.second)
                }
                is ZillitResult.Failure -> {
                    vm.toastError(result.error, "failed_to_load_document_for_signing")
                    updateSigner { copy(loading = false, failed = true) }
                }
            }
        }
    }

    @Suppress("ReturnCount") // One return per source a signature can be stamped on.
    private suspend fun resolveSource(
        deal: DealDoc,
        surface: SignSurface,
    ): ZillitResult<Pair<SignSource, List<DealPdfPage>>> {
        val store = vm.store ?: return ZillitResult.Failure(ZillitError.Validation("Documents can't be signed here."))
        val stored = storedAttachment(deal, surface)
        val (attachment, fromStored) = if (stored != null) {
            stored to true
        } else {
            val kind = if (surface.kind == SignSurfaceKind.StartForm) DealPdfKind.StartForm else DealPdfKind.DealMemo
            when (val generated = page.documents.generate(deal, kind)) {
                is ZillitResult.Success -> DealAttachment(generated.data) to false
                is ZillitResult.Failure -> return generated
            }
        }
        val bytes = when (val fetched = store.fetch(attachment)) {
            is ZillitResult.Success -> fetched.data
            is ZillitResult.Failure -> return fetched
        }
        val pages = when (val rendered = withContext(vm.workDispatcher) { vm.pdf.pages(bytes, SIGN_WIDTH_PX) }) {
            is ZillitResult.Success -> rendered.data
            is ZillitResult.Failure -> return rendered
        }
        if (pages.isEmpty()) {
            return ZillitResult.Failure(ZillitError.Validation("Couldn’t load this document for signing."))
        }
        val source = SignSource(
            bytes = bytes,
            name = attachment.name ?: "${surface.label}.pdf",
            media = attachment.media,
            fromStored = fromStored,
            attachment = attachment.json,
        )
        return ZillitResult.Success(source to pages)
    }

    /** The stored base a document is signed from: the deal memo and start form only when intact; a document always. */
    private fun storedAttachment(deal: DealDoc, surface: SignSurface): DealAttachment? = when (surface.kind) {
        SignSurfaceKind.DealPdf -> SignedPdf.of(DocRead.obj(deal.json, "deal_pdf")).attachment?.takeIf { it.intact }
        SignSurfaceKind.StartForm -> SignedPdf.of(DocRead.obj(deal.json, "crew_start_form_pdf")).attachment?.takeIf {
            it.intact
        }
        SignSurfaceKind.Document -> surface.doc?.document
    }

    /** `readCurrent`: the same document's stored file on a fresh read of the deal — intact or not. */
    private fun currentMedia(deal: DealDoc, surface: SignSurface): String? = when (surface.kind) {
        SignSurfaceKind.DealPdf -> SignedPdf.of(DocRead.obj(deal.json, "deal_pdf")).attachment?.media
        SignSurfaceKind.StartForm -> SignedPdf.of(DocRead.obj(deal.json, "crew_start_form_pdf")).attachment?.media
        SignSurfaceKind.Document -> AdditionalDoc.listOf(DocRead.obj(deal.json, "additional_documents"))
            .firstOrNull { it.id == surface.doc?.id }?.document?.media
    }

    /**
     * Sign: freshness check on a stored base, flatten every placement, upload,
     * one sign POST. A crew signature just refetches; an approver's goes on to
     * approve. Any failure leaves the signer open.
     */
    @Suppress("ReturnCount") // Each guard is one of the web's bail-outs.
    private fun sign(last: DealPlacement) {
        val preview = vm.ui.preview ?: return
        val signer = preview.signer ?: return
        val signature = preview.signature ?: return
        val source = signer.source ?: return
        if (signer.busy || !signer.ready) return
        updateSigner { copy(busy = true) }
        vm.work {
            val outcome = runSign(preview.dealId, signer, source, signature, signer.stamps + last)
            when (outcome) {
                SignOutcome.Stale -> {
                    vm.toast(
                        "This document changed while you were signing — it has been reloaded. Please place your " +
                            "signature again.",
                        DealToastTone.Error,
                    )
                    updateSigner { copy(busy = false) }
                    loadSource()
                }
                is SignOutcome.Failed -> {
                    vm.toastError(outcome.error, "failed_to_sign_document")
                    updateSigner { copy(busy = false) }
                }
                SignOutcome.Signed -> onSigned(signer.userType)
            }
        }
    }

    @Suppress("CyclomaticComplexMethod", "ReturnCount") // Each guard is one of the web's bail-outs.
    private suspend fun runSign(
        dealId: String,
        signer: SignerState,
        source: SignSource,
        signature: CapturedSignature,
        placements: List<DealPlacement>,
    ): SignOutcome {
        if (source.fromStored) {
            val fresh = vm.repository.deal(dealId).getOrNull()
            if (fresh != null && currentMedia(fresh, signer.surface) != source.media) return SignOutcome.Stale
        }
        val stamped = withContext(vm.workDispatcher) { vm.pdf.stamp(source.bytes, signature.png, placements) }
        val flattened = when (stamped) {
            is ZillitResult.Success -> stamped.data
            is ZillitResult.Failure -> return SignOutcome.Failed(stamped.error)
        }
        val store = vm.store ?: return SignOutcome.Failed(ZillitError.Validation("Documents can't be signed here."))
        val uploaded = when (val result = store.upload(source.name, PDF_MIME, flattened)) {
            is ZillitResult.Success -> result.data.takeIf { !it.media.isNullOrEmpty() }
                ?: return SignOutcome.Failed(
                    ZillitError.Validation("Signed document upload failed — please try again."),
                )
            is ZillitResult.Failure -> return SignOutcome.Failed(result.error)
        }
        val attachment = JsonObject(
            uploaded.json + mapOf(
                "name" to JsonPrimitive(uploaded.name ?: source.name),
                "caption" to JsonPrimitive("${signer.surface.label} — signed"),
                "content_type" to JsonPrimitive("document"),
                "content_subtype" to JsonPrimitive("pdf"),
                "file_size" to JsonPrimitive(flattened.size),
            ),
        )
        val target = when (signer.surface.kind) {
            SignSurfaceKind.DealPdf -> DealSignTarget.DealMemo
            SignSurfaceKind.StartForm -> DealSignTarget.StartForm
            SignSurfaceKind.Document -> DealSignTarget.Document(signer.surface.doc?.id ?: signer.surface.key)
        }
        return when (val signed = vm.repository.sign(dealId, target, attachment)) {
            is ZillitResult.Success -> SignOutcome.Signed
            is ZillitResult.Failure -> SignOutcome.Failed(signed.error)
        }
    }

    /** No toast for a crew signature; the approver's approval toasts for itself. */
    private suspend fun onSigned(userType: String) {
        page.updatePreview { copy(signer = null) }
        page.refreshNow()
        if (userType == DealSigner.APPROVER) page.approve()
    }

    private fun updateSigner(reducer: SignerState.() -> SignerState) =
        page.updatePreview { copy(signer = signer?.reducer()) }

    private fun updateLibrary(reducer: SignatureLibrary.() -> SignatureLibrary) =
        updateSigner { copy(library = library.reducer()) }

    private sealed interface SignOutcome {
        data object Signed : SignOutcome
        data object Stale : SignOutcome
        data class Failed(val error: ZillitError) : SignOutcome
    }

    private companion object {
        const val SIGN_WIDTH_PX = 1500
        const val DEFAULT_ASPECT = 0.35f
        const val PDF_MIME = "application/pdf"
    }
}
