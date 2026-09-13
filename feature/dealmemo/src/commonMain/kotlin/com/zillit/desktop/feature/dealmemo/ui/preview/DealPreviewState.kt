package com.zillit.desktop.feature.dealmemo.ui.preview

import androidx.compose.ui.graphics.ImageBitmap
import com.zillit.desktop.feature.dealmemo.domain.DealDoc
import com.zillit.desktop.feature.dealmemo.domain.preview.AdditionalDoc
import com.zillit.desktop.feature.dealmemo.domain.preview.CrewDraft
import com.zillit.desktop.feature.dealmemo.domain.preview.CrewField
import com.zillit.desktop.feature.dealmemo.domain.preview.DealAttachment
import com.zillit.desktop.feature.dealmemo.domain.preview.DealCompany
import com.zillit.desktop.feature.dealmemo.domain.preview.DealCountry
import com.zillit.desktop.feature.dealmemo.domain.preview.DealUnit
import com.zillit.desktop.feature.dealmemo.domain.rates.EmpStatus
import com.zillit.desktop.feature.dealmemo.ui.DealBadgeUnit
import com.zillit.desktop.feature.dealmemo.ui.DealProjectInfo
import com.zillit.desktop.feature.dealmemo.ui.documents.DealPdfPage

/** The deal page's in-flight action; one at a time, as the web's `actionBusy`. */
enum class PreviewAction { Activate, Approve, Send, Reject }

/** Why the "Not ready yet" list is up: a Send attempt, or the deal memo chip with details missing. */
enum class GateMode { Send, Fields }

/** The production data the card names things with — loaded once per entry. */
data class ProductionRefs(
    val project: DealProjectInfo = DealProjectInfo(),
    val webOrigin: String? = null,
    val companies: List<DealCompany> = emptyList(),
    val units: List<DealUnit> = emptyList(),
    val countries: List<DealCountry> = emptyList(),
    val agencies: Map<String, String> = emptyMap(),
    /** Territory → its employment statuses, for the PDF's status label. */
    val empStatuses: Map<String, List<EmpStatus>> = emptyMap(),
    val loaded: Boolean = false,
)

/** What a viewer shows once its bytes arrive. */
sealed interface ViewerContent {
    data class Pages(val pages: List<DealPdfPage>) : ViewerContent

    data class Picture(val image: ImageBitmap) : ViewerContent

    /** Neither a PDF nor an image — offered as a download. */
    data object Unsupported : ViewerContent
}

/** A file open over the page: the deal PDF, an additional document, or a passport scan. */
data class FileViewer(
    val title: String,
    val subtitle: String = "",
    val fileName: String,
    val loading: Boolean = true,
    val failed: Boolean = false,
    val content: ViewerContent? = null,
    val bytes: ByteArray? = null,
    val kind: FileViewerKind,
    val doc: AdditionalDoc? = null,
    val attachment: DealAttachment? = null,
) {
    override fun equals(other: Any?): Boolean = other is FileViewer && other.title == title &&
        other.fileName == fileName && other.loading == loading && other.failed == failed &&
        other.content == content && other.kind == kind && other.bytes === bytes

    override fun hashCode(): Int = title.hashCode() * 31 + fileName.hashCode()
}

enum class FileViewerKind { DealPdf, Document, Passport }

data class RejectDraft(val reason: String = "", val busy: Boolean = false)

/**
 * The deal page, standalone (`/deals/:id`) or embedded in My Deal — one
 * deal, the dialogs over it, and the work in flight.
 */
data class DealPreviewState(
    val dealId: String,
    val embedded: Boolean,
    /** The list the deal was opened from, whose badge the page reads. */
    val from: DealBadgeUnit? = null,
    val deal: DealDoc? = null,
    val loading: Boolean = false,
    val failed: Boolean = false,
    val notFound: Boolean = false,
    val shareToken: String? = null,
    val linkCopied: Boolean = false,
    val action: PreviewAction? = null,
    val acknowledging: Boolean = false,
    val editMenuOpen: Boolean = false,
    val moreMenuOpen: Boolean = false,
    val checklistOpen: Boolean = false,
    val gate: GateMode? = null,
    val reject: RejectDraft? = null,
    val viewer: FileViewer? = null,
    val startFormOpen: Boolean = false,
    val signer: SignerState? = null,
    /** The signature chosen this visit — reused for every later document. */
    val signature: CapturedSignature? = null,
    val nominals: NominalsEditorState? = null,
    val rules: RulesEditorState? = null,
    /** The crew member's unsaved edits — kept until Save or Discard, so the memo shows them. */
    val crewDraft: CrewDraft? = null,
    /** "Complete your details" is open over the page. */
    val crewForm: CrewFormUi? = null,
)

/** The crew form's own state: its step, what was touched, what is in flight. */
data class CrewFormUi(
    val step: Int = 0,
    val touched: Set<CrewField> = emptySet(),
    /** A Save or Continue was refused — every format error now shows. */
    val submitAttempted: Boolean = false,
    val saving: Boolean = false,
    val uploading: Boolean = false,
    val discardPrompt: Boolean = false,
)

/** A signature image ready to place: its PNG, and height over width. */
class CapturedSignature(val png: ByteArray, val image: ImageBitmap, val aspect: Float)
