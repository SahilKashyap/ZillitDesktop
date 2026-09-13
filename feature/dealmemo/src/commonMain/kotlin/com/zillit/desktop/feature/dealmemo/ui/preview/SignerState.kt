package com.zillit.desktop.feature.dealmemo.ui.preview

import androidx.compose.ui.graphics.ImageBitmap
import com.zillit.desktop.feature.dealmemo.domain.preview.SignSurface
import com.zillit.desktop.feature.dealmemo.ui.documents.DealPdfPage
import com.zillit.desktop.feature.dealmemo.ui.documents.DealPlacement
import com.zillit.desktop.feature.dealmemo.ui.documents.SignatureFont
import kotlinx.serialization.json.JsonObject

/** Stage one of signing: choose a signature. Stage two: place it on the document. */
enum class SignerStage { Choose, Place }

/** How a new signature is being made, inside the library picker. */
enum class NewSignatureStep { Method, Type, Draw }

/** A saved signature with its image, once fetched. */
data class LibraryEntry(val id: String, val image: ImageBitmap?, val png: ByteArray?) {
    override fun equals(other: Any?): Boolean = other is LibraryEntry && other.id == id && other.image === image

    override fun hashCode(): Int = id.hashCode()
}

/** The saved-signature picker (E-Signature's `SavedSignaturePicker`, single-document mode). */
data class SignatureLibrary(
    val loading: Boolean = true,
    val failed: Boolean = false,
    val entries: List<LibraryEntry> = emptyList(),
    val selectedId: String? = null,
    /** Null on the list; otherwise which step of making a new one. */
    val creating: NewSignatureStep? = null,
    val typedText: String = "",
    val typedFont: SignatureFont = SignatureFont.Formal,
    val saveForNextTime: Boolean = true,
    val pendingDelete: String? = null,
    val working: Boolean = false,
)

/** The document being signed: its bytes and where they came from. */
class SignSource(
    val bytes: ByteArray,
    val name: String,
    /** The stored `media` key — compared against a fresh read before a stored copy is signed. */
    val media: String?,
    val fromStored: Boolean,
    val attachment: JsonObject,
)

/**
 * One document open in the signer (`DMSignDocumentModal.jsx`) — exactly one:
 * the next document is picked from the bar, never switched to in here.
 */
data class SignerState(
    val surface: SignSurface,
    /** `approver` for Approve & Sign, else `crew`. */
    val userType: String,
    val stage: SignerStage,
    val library: SignatureLibrary = SignatureLibrary(),
    val loading: Boolean = true,
    val failed: Boolean = false,
    val source: SignSource? = null,
    val pages: List<DealPdfPage> = emptyList(),
    /** Signatures already placed; the live overlay is not among them. */
    val stamps: List<DealPlacement> = emptyList(),
    val zoom: Float = 1f,
    val busy: Boolean = false,
    /** Bumped whenever the overlay must jump back to the document's end. */
    val placementEpoch: Int = 0,
) {
    val ready: Boolean get() = !loading && !failed && source != null && pages.isNotEmpty()

    companion object {
        const val ZOOM_MIN = 0.5f
        const val ZOOM_MAX = 2f
        const val ZOOM_STEP = 0.25f
    }
}
