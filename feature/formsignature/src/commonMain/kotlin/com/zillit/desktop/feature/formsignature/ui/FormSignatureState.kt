package com.zillit.desktop.feature.formsignature.ui

import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.formsignature.domain.ChatUnit
import com.zillit.desktop.feature.formsignature.domain.ExternalSigner
import com.zillit.desktop.feature.formsignature.domain.FormSignatureUnread
import com.zillit.desktop.feature.formsignature.domain.FormSignatureViewer
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
 * The tool's screens — the web's `ContractSignatureScreens` plus the two
 * routes it pushes for the signature block (`/set-signature`,
 * `/set-up-signature/sign`). One shell, one back caret, one title.
 */
enum class FormSignScreen {
    Tiles,
    StandardDocuments,
    DocumentsForSignature,
    SignatureBlock,
    DrawSignature,
    Detail,
    Chat,
}

/** The standard-documents tabs, in the web's order and wire vocabulary. */
enum class StandardTab(val wire: String, private val labelKey: String) {
    All("all-forms", S.txt_documents),
    Mine("your-forms", S.txt_my_downloads),
    ;

    val label: String get() = str(labelKey)
}

data class StandardFormsState(
    val tab: StandardTab = StandardTab.All,
    val rows: List<StandardForm> = emptyList(),
    val loading: Boolean = false,
    val search: String = "",
) {
    /** The web's filter — name or serial number — over its newest-first sort. */
    val visible: List<StandardForm>
        get() {
            val needle = search.trim().lowercase()
            return rows
                .filter {
                    needle.isEmpty() || it.name.lowercase().contains(needle) ||
                        it.serialNo.lowercase().contains(needle)
                }
                .sortedByDescending { it.createdOn ?: 0L }
        }
}

data class DocumentsState(
    val tab: SignDocumentTab = SignDocumentTab.Uploaded,
    val rows: List<SignDocument> = emptyList(),
    val loading: Boolean = false,
    val search: String = "",
) {
    /** Name or uploader, newest first — `DocumentsForSignature.jsx`'s `filteredDocuments`. */
    val visible: List<SignDocument>
        get() {
            val needle = search.trim().lowercase()
            return rows
                .filter {
                    needle.isEmpty() || it.name.lowercase().contains(needle) ||
                        it.uploaderName().lowercase().contains(needle)
                }
                .sortedByDescending { it.createdOn ?: 0L }
        }
}

data class SignaturesState(
    val blocks: List<SignatureBlock> = emptyList(),
    /** Fetched PNGs by block id, for preview. */
    val images: Map<String, ByteArray> = emptyMap(),
    val loading: Boolean = false,
) {
    val signature: SignatureBlock? get() = blocks.firstOrNull { it.isSignature }
    val initials: SignatureBlock? get() = blocks.firstOrNull { !it.isSignature }
    fun of(kind: SignSpotKind): SignatureBlock? = if (kind == SignSpotKind.Signature) signature else initials
}

/**
 * The discussion room. [receiver] is the person an admin is answering —
 * the web's `receiverUserData`, required before an admin may post.
 */
data class ChatState(
    val unit: ChatUnit? = null,
    val receiver: SignerOption? = null,
    val pickingReceiver: Boolean = false,
    val options: List<SignerOption> = emptyList(),
    val loadingOptions: Boolean = false,
)

/** Where an open document came from — decides which sign route applies and which buttons show. */
sealed interface DetailSource {
    /** The shared library — read, download, or add to My Downloads. */
    data object LibraryAll : DetailSource

    /** The reader's own downloads; signs via the older sign-document route. */
    data object MyDownloads : DetailSource

    /** The for-signature flow; signs via the document route. */
    data class ForSignature(val tab: SignDocumentTab) : DetailSource
}

/**
 * A mark being positioned by hand — the web's `ResizableImage`: the PNG,
 * its rectangle in the page image's pixels (top-left), and the aspect that
 * keeps its height honest while the width is dragged.
 */
data class FreeMark(
    val png: ByteArray,
    val kind: SignSpotKind,
    val page: Int,
    val x: Float,
    val y: Float,
    val width: Float,
    val aspect: Float,
) {
    val height: Float get() = width * aspect

    override fun equals(other: Any?): Boolean = other is FreeMark &&
        other.kind == kind && other.page == page && other.x == x && other.y == y &&
        other.width == width && other.aspect == aspect && other.png.size == png.size

    override fun hashCode(): Int = listOf(kind, page, x, y, width, aspect, png.size).hashCode()
}

/** A placeholder being placed on the sender's document — a box, no ink yet. */
data class DraftBox(
    val kind: SignSpotKind,
    val page: Int,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
)

/**
 * An open document.
 *
 * [placeholders] are the boxes the sender placed for the current user that
 * are still empty — a click on one stamps a saved mark straight into it.
 * When the document has none ([placeholderFlow] false) the reader signs by
 * free placement: [freeMark] is the mark being dragged into position.
 */
data class DetailState(
    val source: DetailSource,
    val documentId: String,
    val title: String,
    val stored: StoredDocument?,
    val pages: List<PdfPageImage> = emptyList(),
    /** 0-based index into [pages]. */
    val page: Int = 0,
    val loadingPages: Boolean = true,
    /** A Word document, or something PDFBox could not open — shown as a chip. */
    val notPdf: Boolean = false,
    val placeholderFlow: Boolean = false,
    val placeholders: List<SignSpot> = emptyList(),
    val canSign: Boolean = false,
    val alreadySigned: Boolean = false,
    /** The web's `isOpenedDocumentSigned`: ink is in the local copy but not sent. */
    val signedLocally: Boolean = false,
    val freeMark: FreeMark? = null,
    /** Stamping, converting, downloading — the page is being worked on. */
    val busy: Boolean = false,
    val sending: Boolean = false,
    val transferring: Boolean = false,
) {
    val current: PdfPageImage? get() = pages.getOrNull(page)
    val pageCount: Int get() = pages.size

    /** The web's Send Document button: shown while unsigned, enabled once something was stamped. */
    val offersSend: Boolean get() = canSign && !alreadySigned
    val readyToSend: Boolean get() = offersSend && signedLocally && !sending && !busy

    /** The web's Add Signature / Sign Document pair — only the free-placement flow. */
    val offersFreeSign: Boolean get() = canSign && !alreadySigned && !placeholderFlow
}

enum class SendStep { Details, Place }

/** Someone chosen to sign, in the order chosen — crew first, then outsiders. */
data class SendPerson(
    val id: String,
    val name: String,
    val subtitle: String = "",
    val external: Boolean = false,
)

/** The two steps of sending a document for signature — the web's `UploadDocumentForSignatureV2`. */
data class SendState(
    val step: SendStep = SendStep.Details,
    val fileName: String = "",
    val fileBytes: ByteArray? = null,
    /** The original with the sender's own marks stamped in; null until they add one. */
    val stampedBytes: ByteArray? = null,
    val title: String = "",
    val pages: List<PdfPageImage> = emptyList(),
    val page: Int = 0,
    val options: List<SignerOption> = emptyList(),
    val externals: List<ExternalSigner> = emptyList(),
    val loadingPeople: Boolean = false,
    val chosen: List<String> = emptyList(),
    val hasExternal: Boolean = false,
    val chosenExternal: List<String> = emptyList(),
    /** False = initials and signature (the web's default radio); true = signature only. */
    val onlySignature: Boolean = false,
    val senderSigns: Boolean = false,
    val activeSigner: String? = null,
    val spots: Map<String, List<SignSpot>> = emptyMap(),
    val draft: DraftBox? = null,
    val senderMark: FreeMark? = null,
    val senderSignaturePlaced: Boolean = false,
    val senderInitialsPlaced: Boolean = false,
    val busy: Boolean = false,
    val sending: Boolean = false,
) {
    val current: PdfPageImage? get() = pages.getOrNull(page)

    /** Everyone who must sign, crew then outsiders, in the order chosen. */
    val people: List<SendPerson>
        get() = chosen.map { id ->
            val option = options.firstOrNull { it.userId == id }
            SendPerson(id, option?.label ?: id, option?.designation.orEmpty())
        } + (if (hasExternal) chosenExternal else emptyList()).map { id ->
            val outsider = externals.firstOrNull { it.id == id }
            SendPerson(id, outsider?.label ?: id, outsider?.email.orEmpty(), external = true)
        }

    fun spotsOf(personId: String, kind: SignSpotKind): List<SignSpot> =
        spots[personId].orEmpty().filter { it.kind == kind }

    /** The web's per-user status line: signature placed, and initials too when required. */
    fun covered(personId: String): Boolean =
        spotsOf(personId, SignSpotKind.Signature).isNotEmpty() &&
            (onlySignature || spotsOf(personId, SignSpotKind.Initials).isNotEmpty())

    // Equality over a projection: the byte arrays only participate by size,
    // so recomposition isn't asked to compare megabytes.
    private fun projection(): List<Any?> = listOf(
        step, fileName, title, pages, page, options, externals, loadingPeople, chosen, hasExternal,
        chosenExternal, onlySignature, senderSigns, activeSigner, spots, draft, senderMark,
        senderSignaturePlaced, senderInitialsPlaced, busy, sending, fileBytes?.size, stampedBytes?.size,
    )

    override fun equals(other: Any?): Boolean = other is SendState && other.projection() == projection()
    override fun hashCode(): Int = projection().hashCode()
}

/** The standard-library upload drawer — the web's `UploadFormModal`. */
data class UploadFormState(
    val fileName: String = "",
    val fileBytes: ByteArray? = null,
    val name: String = "",
    val type: StandardFormType = StandardFormType.Contract,
    val uploading: Boolean = false,
) {
    val extension: String get() = fileName.substringAfterLast('.', "").lowercase()

    override fun equals(other: Any?): Boolean = other is UploadFormState &&
        other.fileName == fileName && other.name == name && other.type == type &&
        other.uploading == uploading && (other.fileBytes?.size ?: -1) == (fileBytes?.size ?: -1)

    override fun hashCode(): Int = fileName.hashCode() * 31 + (fileBytes?.size ?: 0)
}

/**
 * The drawing pad — the web's `SignatureCanvas` page (from the block
 * gallery) and its `AddSignaturesModal` (from the picker) share this.
 */
data class DrawState(
    /** True = drawing the signature; false = the initials. */
    val isSignature: Boolean = true,
    /** Replacing an existing block, when set. */
    val existingId: String? = null,
    val name: String = "",
    val strokes: List<List<StrokePoint>> = emptyList(),
    val saving: Boolean = false,
    /** Shown as its own screen (the gallery's Add/Edit) rather than over the picker. */
    val asPage: Boolean = true,
) {
    val hasInk: Boolean get() = strokes.any { it.size > 1 }
}

/** Why the signature picker is open — decides where the chosen mark goes. */
sealed interface PickPurpose {
    /** Straight into a placeholder the sender placed (`handleConfirmAtPlaceholder`). */
    data class FillPlaceholder(val spot: SignSpot) : PickPurpose

    /** Onto the open document as a draggable mark (`ResizableImage`). */
    data object FreeOnDetail : PickPurpose

    /** Onto the document being sent, as the sender's own mark. */
    data class SenderMark(val kind: SignSpotKind) : PickPurpose
}

/** The web's `SignaturesModal`: saved marks of one kind (or both), with Add when one is missing. */
data class PickerState(
    val purpose: PickPurpose,
    /** Null shows both kinds — the free-placement flow's choice. */
    val kind: SignSpotKind? = null,
)

/** The web's `UpdateHistoryModal` on the standard-documents tile. */
data class HistoryState(val form: StandardForm)

/** A yes/no the web asks before something that cannot be undone. */
sealed interface ConfirmState {
    data class DeleteForm(val formId: String) : ConfirmState
    data class DeleteDocument(val documentId: String) : ConfirmState
    data class DeleteSignature(val blockId: String) : ConfirmState

    /** "You have signed this document. Are you sure you want to go back?" */
    data object LeaveSigned : ConfirmState

    /** "Are you sure you placed the signature in the correct position?" */
    data object SendSigned : ConfirmState
}

data class FormSignatureUiState(
    val viewer: FormSignatureViewer = FormSignatureViewer(),
    val currentUserId: String = "",
    val screen: FormSignScreen = FormSignScreen.Tiles,
    val unread: FormSignatureUnread = FormSignatureUnread.None,
    val standard: StandardFormsState = StandardFormsState(),
    val documents: DocumentsState = DocumentsState(),
    val signatures: SignaturesState = SignaturesState(),
    val chat: ChatState = ChatState(),
    val detail: DetailState? = null,
    val send: SendState? = null,
    val uploadForm: UploadFormState? = null,
    val draw: DrawState? = null,
    val picker: PickerState? = null,
    val history: HistoryState? = null,
    val confirm: ConfirmState? = null,
) {
    /** The web's tile list: both document tiles hide for a pending member, the sent list needs posting. */
    val showsStandardTile: Boolean get() = !viewer.isPending
    val showsDocumentsTile: Boolean get() = viewer.canPost && !viewer.isPending

    /** The header's title — the web's `getHeadingName`. */
    val title: String
        get() = when (screen) {
            FormSignScreen.Tiles -> TOOL_TITLE
            FormSignScreen.StandardDocuments -> str(S.standard_forms)
            FormSignScreen.DocumentsForSignature -> str(S.douments_for_sign_txt)
            FormSignScreen.SignatureBlock -> str(S.set_signature_edit)
            FormSignScreen.DrawSignature -> draw?.let {
                when {
                    it.existingId != null && it.isSignature -> str(S.edit_signature)
                    it.existingId != null -> str(S.desktop_fs_edit_initials)
                    it.isSignature -> str(S.add_signature)
                    else -> str(S.txt_add_initials)
                }
            } ?: str(S.add_signature)
            FormSignScreen.Detail -> detail?.title?.ifBlank { null } ?: str(S.desktop_fs_contract_details)
            // The web's heading for the parent room is the `discussion_chat` label; the unit's own name is a key.
            FormSignScreen.Chat -> str(S.desktop_fs_discussion_chat)
        }

    companion object {
        val TOOL_TITLE: String get() = str(S.desktop_fs_tool_title)
    }
}

/** Which flow asked the host for a file. */
enum class PickTarget { StandardForm, SendDocument }

sealed interface FormSignatureEvent {
    // -- navigation
    data class Open(val screen: FormSignScreen) : FormSignatureEvent
    data object Back : FormSignatureEvent
    data object Refresh : FormSignatureEvent
    data object OpenGuide : FormSignatureEvent

    // -- standard documents
    data class SearchStandard(val query: String) : FormSignatureEvent
    data class SwitchStandardTab(val tab: StandardTab) : FormSignatureEvent
    data class OpenStandardForm(val form: StandardForm) : FormSignatureEvent
    data class SelfAssign(val formId: String) : FormSignatureEvent
    data class AskDeleteStandardForm(val formId: String) : FormSignatureEvent
    data class ShowHistory(val form: StandardForm) : FormSignatureEvent
    data object CloseHistory : FormSignatureEvent
    data object StartUploadForm : FormSignatureEvent
    data class EditUploadForm(val state: UploadFormState) : FormSignatureEvent
    data object PickUploadFile : FormSignatureEvent
    data object SubmitUploadForm : FormSignatureEvent
    data object CancelUploadForm : FormSignatureEvent

    // -- documents for signature
    data class SearchDocuments(val query: String) : FormSignatureEvent
    data class SwitchDocumentsTab(val tab: SignDocumentTab) : FormSignatureEvent
    data class OpenDocument(val document: SignDocument) : FormSignatureEvent
    data class AskDeleteDocument(val documentId: String) : FormSignatureEvent

    // -- send for signature
    data object StartSend : FormSignatureEvent
    data class EditSend(val state: SendState) : FormSignatureEvent
    data object SendPickFile : FormSignatureEvent
    data object SendNext : FormSignatureEvent
    data object SendBack : FormSignatureEvent
    data class SendTurnPage(val delta: Int) : FormSignatureEvent
    data class SendSelectSigner(val personId: String) : FormSignatureEvent
    data class SendAddPlaceholder(val kind: SignSpotKind) : FormSignatureEvent
    data class SendMoveDraft(val x: Float, val y: Float, val width: Float, val height: Float) : FormSignatureEvent
    data object SendConfirmDraft : FormSignatureEvent
    data object SendCancelDraft : FormSignatureEvent
    data class SendMoveSpot(val personId: String, val index: Int, val x: Float, val y: Float) : FormSignatureEvent
    data class SendRemoveSpot(val personId: String, val index: Int) : FormSignatureEvent
    data class SendAddOwnMark(val kind: SignSpotKind) : FormSignatureEvent
    data class SendMoveOwnMark(val x: Float, val y: Float, val width: Float) : FormSignatureEvent
    data object SendConfirmOwnMark : FormSignatureEvent
    data object SendCancelOwnMark : FormSignatureEvent
    data object SubmitSend : FormSignatureEvent
    data object CancelSend : FormSignatureEvent

    // -- an open document
    data object CloseDetail : FormSignatureEvent
    data class TurnPage(val delta: Int) : FormSignatureEvent
    data class TapPlaceholder(val spot: SignSpot) : FormSignatureEvent
    data object AddSignature : FormSignatureEvent
    data class MoveFreeMark(val x: Float, val y: Float, val width: Float) : FormSignatureEvent
    data object ConfirmFreeMark : FormSignatureEvent
    data object CancelFreeMark : FormSignatureEvent
    data object AskSendSigned : FormSignatureEvent
    data object DownloadDetail : FormSignatureEvent
    data object PrintDetail : FormSignatureEvent
    data object TransferToDownloads : FormSignatureEvent

    // -- the signature picker and the pad
    data class PickSignature(val block: SignatureBlock) : FormSignatureEvent
    data object ClosePicker : FormSignatureEvent
    data class StartDraw(val isSignature: Boolean, val existingId: String? = null, val asPage: Boolean = true) :
        FormSignatureEvent
    data class EditDraw(val state: DrawState) : FormSignatureEvent

    /**
     * Appends one finished stroke. Additive on purpose: the drawing pad's
     * gesture closure is long-lived and would clobber earlier strokes if it
     * carried the whole state.
     */
    data class AddDrawStroke(val stroke: List<StrokePoint>) : FormSignatureEvent
    data object ClearDraw : FormSignatureEvent
    data object SubmitDraw : FormSignatureEvent
    data object CancelDraw : FormSignatureEvent
    data class AskDeleteSignature(val blockId: String) : FormSignatureEvent

    // -- the discussion room
    data object OpenChat : FormSignatureEvent
    data object PickReceiver : FormSignatureEvent
    data class ChooseReceiver(val option: SignerOption?) : FormSignatureEvent
    data object CloseReceiverPicker : FormSignatureEvent

    // -- confirmations
    data object ConfirmYes : FormSignatureEvent
    data object ConfirmNo : FormSignatureEvent

    /** The host's file dialog answered. */
    data class FilePicked(val target: PickTarget, val name: String, val bytes: ByteArray) : FormSignatureEvent {
        override fun equals(other: Any?): Boolean = other is FilePicked &&
            other.target == target && other.name == name && other.bytes.contentEquals(bytes)

        override fun hashCode(): Int = name.hashCode() * 31 + bytes.size
    }
}

/** What the host's file dialog should accept. */
enum class PickKind { PdfOnly, PdfOrWord }

sealed interface FormSignatureEffect {
    /** Ask the host to show a file picker; the answer returns as [FormSignatureEvent.FilePicked]. */
    data class PickFile(val target: PickTarget, val kind: PickKind) : FormSignatureEffect
    data class Notice(val message: String) : FormSignatureEffect
    data class Failed(val message: String) : FormSignatureEffect
}
