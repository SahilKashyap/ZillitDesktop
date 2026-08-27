package com.zillit.desktop.feature.formsignature.ui

import com.zillit.desktop.feature.formsignature.domain.FormSignatureViewer
import com.zillit.desktop.feature.formsignature.domain.HistoryEntry
import com.zillit.desktop.feature.formsignature.domain.PdfPageImage
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

/**
 * The tool's areas — the web's three live tiles, plus the hub itself.
 *
 * The documents area is **not** posting-gated here, although the web's tile
 * nominally is: the area contains Received for Signature, which is the one
 * surface a view-only crew member must reach to sign what is sent to them.
 * (The web's gate is also accidentally permissive — it truth-tests the whole
 * rights object — so in practice every viewer gets in there too. Within the
 * area, uploading and the sent list stay posting-only.)
 */
enum class FormSignatureArea(val label: String) {
    Hub("Documents & Signature"),
    StandardForms("Standard forms & contracts"),
    Documents("Documents for signature"),
    Signatures("Signature block"),
}

/** The standard-forms tabs, in the web's order and wire vocabulary. */
enum class StandardTab(val label: String) {
    All("All documents"),
    Mine("Your documents"),
}

data class StandardFormsState(
    val tab: StandardTab = StandardTab.All,
    val rows: List<StandardForm> = emptyList(),
    val loading: Boolean = false,
)

data class DocumentsState(
    val tab: SignDocumentTab = SignDocumentTab.Uploaded,
    val rows: List<SignDocument> = emptyList(),
    val loading: Boolean = false,
)

data class SignaturesState(
    val blocks: List<SignatureBlock> = emptyList(),
    /** Fetched PNGs by block id, for preview. */
    val images: Map<String, ByteArray> = emptyMap(),
    val loading: Boolean = false,
)

/** Where an open document came from — decides which sign route applies. */
enum class DetailSource {
    /** The shared library — read-only, sign is not offered. */
    StandardAll,

    /** The reader's own list; signs via the older sign-document route. */
    StandardMine,

    /** The for-signature flow; signs via the document route. */
    ForSignature,
}

/**
 * An open document.
 *
 * [mySpots] are the placeholders the sender placed for the current user —
 * present only in the placeholder flow, before they have signed. When empty
 * and signing is offered, the flow is free placement: [freeSpot] holds where
 * the reader chose to put their mark.
 */
data class DetailState(
    val source: DetailSource,
    val documentId: String,
    val title: String,
    val stored: StoredDocument?,
    val pages: List<PdfPageImage> = emptyList(),
    val loadingPages: Boolean = true,
    val mySpots: List<SignSpot> = emptyList(),
    val freeSpot: SignSpot? = null,
    val canSign: Boolean = false,
    val alreadySigned: Boolean = false,
    val signing: Boolean = false,
    /** Non-PDF documents render a notice instead of pages. */
    val notPdf: Boolean = false,
) {
    /** Signing needs somewhere to put ink: placed spots, or a chosen free spot. */
    val readyToSign: Boolean get() = canSign && !signing &&
        (mySpots.isNotEmpty() || freeSpot != null)
}

/** The two steps of sending a document for signature. */
data class SendState(
    val fileName: String = "",
    val fileBytes: ByteArray? = null,
    val title: String = "",
    val pages: List<PdfPageImage> = emptyList(),
    val options: List<SignerOption> = emptyList(),
    val chosen: List<String> = emptyList(),
    val onlySignature: Boolean = true,
    val senderSigns: Boolean = false,
    /** Step 2 selection: whose box the next tap places, and of which kind. */
    val placing: Boolean = false,
    val activeSigner: String? = null,
    val activeKind: SignSpotKind = SignSpotKind.Signature,
    val spots: Map<String, List<SignSpot>> = emptyMap(),
    val sending: Boolean = false,
) {
    val placedCount: Int get() = spots.values.sumOf { it.size }

    /** Every chosen signer needs at least one box — the web refuses otherwise. */
    val everySignerCovered: Boolean get() =
        chosen.isNotEmpty() && chosen.all { !spots[it].isNullOrEmpty() }

    // Equality over a projection: the byte array only participates by size,
    // so recomposition isn't asked to compare megabytes.
    private fun projection(): List<Any?> = listOf(
        fileName, title, pages, options, chosen, onlySignature, senderSigns,
        placing, activeSigner, activeKind, spots, sending, fileBytes?.size,
    )

    override fun equals(other: Any?): Boolean =
        other is SendState && other.projection() == projection()

    override fun hashCode(): Int = projection().hashCode()
}

/** The standard-library upload dialog. */
data class UploadFormState(
    val fileName: String = "",
    val fileBytes: ByteArray? = null,
    val type: StandardFormType = StandardFormType.Contract,
    val note: String = "",
    val uploading: Boolean = false,
) {
    override fun equals(other: Any?): Boolean = other is UploadFormState &&
        other.fileName == fileName && other.type == type && other.note == note &&
        other.uploading == uploading && (other.fileBytes?.size ?: -1) == (fileBytes?.size ?: -1)

    override fun hashCode(): Int = fileName.hashCode() * 31 + (fileBytes?.size ?: 0)
}

/** The signature-drawing dialog. */
data class DrawState(
    /** True = drawing the signature; false = the initials. */
    val isSignature: Boolean = true,
    /** Replacing an existing block, when set. */
    val existingId: String? = null,
    val name: String = "",
    val strokes: List<List<StrokePoint>> = emptyList(),
    val saving: Boolean = false,
)

data class HistoryState(
    val documentId: String,
    val entries: List<HistoryEntry> = emptyList(),
    val loading: Boolean = true,
)

data class FormSignatureUiState(
    val viewer: FormSignatureViewer = FormSignatureViewer(),
    val currentUserId: String = "",
    val area: FormSignatureArea = FormSignatureArea.Hub,
    val standard: StandardFormsState = StandardFormsState(),
    val documents: DocumentsState = DocumentsState(),
    val signatures: SignaturesState = SignaturesState(),
    val detail: DetailState? = null,
    /** Editing who must sign a document that has already gone out. */
    val signerEditor: SignerEditorState? = null,
    val send: SendState? = null,
    val uploadForm: UploadFormState? = null,
    val draw: DrawState? = null,
    val history: HistoryState? = null,
)

/**
 * Changing the signers on a sent document.
 *
 * [chosen] starts as everyone already on the document, because the service
 * takes the whole list rather than a delta — dropping someone here is what
 * removes them.
 */
data class SignerEditorState(
    val documentId: String,
    val title: String,
    val options: List<SignerOption> = emptyList(),
    val chosen: Set<String> = emptySet(),
    val alreadySigned: Set<String> = emptySet(),
    val loading: Boolean = true,
    val saving: Boolean = false,
) {
    /** Nobody to sign is not a document anyone is waiting on. */
    val canSave: Boolean get() = chosen.isNotEmpty() && !saving && !loading
}

/**
 * The signer editor's own events.
 *
 * A marker over the four of them, so the view model's event list stays one
 * line per feature rather than one per button.
 */
sealed interface SignerEditorEvent

sealed interface FormSignatureEvent {
    data class SwitchArea(val area: FormSignatureArea) : FormSignatureEvent
    data object Refresh : FormSignatureEvent

    data class SwitchStandardTab(val tab: StandardTab) : FormSignatureEvent
    data class SwitchDocumentsTab(val tab: SignDocumentTab) : FormSignatureEvent

    data class OpenStandardForm(val form: StandardForm) : FormSignatureEvent
    data class OpenDocument(val document: SignDocument) : FormSignatureEvent
    data object CloseDetail : FormSignatureEvent
    data class PlaceFreeSpot(val page: Int, val xPx: Float, val yPx: Float) : FormSignatureEvent
    data object SignOpenDocument : FormSignatureEvent

    data class SelfAssign(val formId: String) : FormSignatureEvent
    data class DeleteStandardForm(val formId: String) : FormSignatureEvent
    data class DeleteDocument(val documentId: String) : FormSignatureEvent

    /** Opens the signer editor on a sent document. */
    data class EditSigners(val document: SignDocument) : FormSignatureEvent, SignerEditorEvent
    data class ToggleSigner(val userId: String) : FormSignatureEvent, SignerEditorEvent
    data object SaveSigners : FormSignatureEvent, SignerEditorEvent
    data object CloseSignerEditor : FormSignatureEvent, SignerEditorEvent
    data class ShowHistory(val documentId: String) : FormSignatureEvent
    data object CloseHistory : FormSignatureEvent

    data object StartUploadForm : FormSignatureEvent
    data class EditUploadForm(val state: UploadFormState) : FormSignatureEvent
    data object SubmitUploadForm : FormSignatureEvent
    data object CancelUploadForm : FormSignatureEvent

    data object StartSend : FormSignatureEvent
    data class EditSend(val state: SendState) : FormSignatureEvent
    data object BeginPlacement : FormSignatureEvent
    data class PlaceSendSpot(val page: Int, val xPx: Float, val yPx: Float) : FormSignatureEvent
    data class RemoveSendSpot(val signer: String, val index: Int) : FormSignatureEvent
    data object SubmitSend : FormSignatureEvent
    data object CancelSend : FormSignatureEvent

    data class StartDraw(val isSignature: Boolean, val existingId: String?) : FormSignatureEvent
    data class EditDraw(val state: DrawState) : FormSignatureEvent

    /**
     * Appends one finished stroke. Additive on purpose: the drawing pad's
     * gesture closure is long-lived and would clobber earlier strokes if it
     * carried the whole state.
     */
    data class AddDrawStroke(val stroke: List<StrokePoint>) : FormSignatureEvent
    data object SubmitDraw : FormSignatureEvent
    data object CancelDraw : FormSignatureEvent
    data class DeleteSignature(val blockId: String) : FormSignatureEvent

    /** The host's file dialog answered. */
    data class FilePicked(val name: String, val bytes: ByteArray) : FormSignatureEvent {
        override fun equals(other: Any?): Boolean = other is FilePicked &&
            other.name == name && other.bytes.contentEquals(bytes)

        override fun hashCode(): Int = name.hashCode() * 31 + bytes.size
    }
}

sealed interface FormSignatureEffect {
    /** Ask the host to show a PDF picker; the answer returns as [FormSignatureEvent.FilePicked]. */
    data object PickPdf : FormSignatureEffect
    data class Notice(val message: String) : FormSignatureEffect
    data class Failed(val message: String) : FormSignatureEffect
}
