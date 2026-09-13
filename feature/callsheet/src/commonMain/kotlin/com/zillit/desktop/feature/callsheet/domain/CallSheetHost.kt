package com.zillit.desktop.feature.callsheet.domain

import com.zillit.desktop.core.common.ZillitResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.serialization.json.JsonObject

/**
 * The server-rendered PDF of a saved sheet. A seam because the bytes stream
 * outside the enveloped API client, and rendering pages needs PDFBox.
 */
interface CallSheetDelivery {
    /** `GET /call-sheets/{id}/pdf` — any saved id, drafts included. */
    suspend fun pdf(sheetId: String): ZillitResult<ByteArray>

    /** PDF bytes as page images for the in-app viewer. */
    fun renderPages(pdf: ByteArray, targetWidthPx: Int): ZillitResult<List<SheetPdfPage>>

    /** Writes the PDF to Downloads and opens it — the viewer's download and print. */
    suspend fun savePdf(fileName: String, pdf: ByteArray): ZillitResult<Unit>
}

data class SheetPdfPage(
    val page: Int,
    val imageBytes: ByteArray,
    val widthPx: Int,
    val heightPx: Int,
) {
    override fun equals(other: Any?): Boolean = other is SheetPdfPage && other.page == page
    override fun hashCode(): Int = page
}

/** A file picked on this machine. */
class PickedDocument(val name: String, val contentType: String, val bytes: ByteArray)

/**
 * Where a call sheet goes beyond its own service: the Home call-sheet unit,
 * Document Distribution, a one-to-one chat, and storage for a drawn
 * signature. All of it lives in other modules, so the host wires it.
 */
interface CallSheetPublishing {
    /** Document Distribution posting rights (or project admin) — the web's `canDistribute`. */
    fun canDistribute(): Boolean

    /**
     * Stores the PDF and files it in Document Distribution under `Call Sheet`
     * (or `Draft Call Sheet` from the Drafts tab), dated by the shoot date.
     */
    suspend fun sendToDocumentDistribution(
        pdf: ByteArray,
        fileName: String,
        fromDraft: Boolean,
        dateMs: Long?,
    ): ZillitResult<Unit>

    /**
     * Posts a document into the Home call-sheet unit's chat. [replacePrevious]
     * is the publish type: New archives the live call sheet, Continuation and
     * attachments append.
     */
    suspend fun postToUnit(
        bytes: ByteArray,
        fileName: String,
        contentType: String,
        caption: String,
        replacePrevious: Boolean,
    ): ZillitResult<Unit>

    /** A PDF picked on this machine; null for a cancelled picker. */
    suspend fun pickPdf(): PickedDocument?

    /** Puts a signature (PNG) into storage for `signature_image`. */
    suspend fun uploadSignature(png: ByteArray): ZillitResult<ApprovalDecision.Signature>

    /** Shares the rendered PDF as a document in a one-to-one chat. */
    suspend fun sendPdfToChat(pdf: ByteArray, fileName: String, receiverId: String): ZillitResult<Unit>
}

/** Opens the one-to-one chat with a crew member. False when the host has no chat to open. */
fun interface SheetChatOpener {
    fun openChat(userId: String, fullName: String): Boolean
}

/**
 * The tool's badges from the notification ledger, and the reads that clear
 * them: approval units whole (`notification:read`) or by level, comments by
 * thread or tab (`notification:level:read`).
 */
interface SheetBadgeSource {
    val leaves: Flow<List<BadgeLeaf>> get() = emptyFlow()

    fun readUnit(unit: String) = Unit

    fun readUnitLevel(unit: String, level1: String) = Unit

    fun readCommentThread(sheetId: String) = Unit

    fun readCommentTab(tab: String) = Unit
}

/** OpenWeather One Call 3.0 for a point, raw — the sheet stores its own shape. */
fun interface SheetWeatherSource {
    suspend fun oneCall(lat: Double, lng: Double): ZillitResult<JsonObject>
}

/** A signature or initials the approver saved in Forms & Signatures. */
data class SavedSignature(val id: String, val isSignature: Boolean, val name: String = "")

/** The approver's saved signatures, and each one's image. */
interface SavedSignatureSource {
    suspend fun list(): ZillitResult<List<SavedSignature>>

    suspend fun image(signature: SavedSignature): ZillitResult<ByteArray>
}
