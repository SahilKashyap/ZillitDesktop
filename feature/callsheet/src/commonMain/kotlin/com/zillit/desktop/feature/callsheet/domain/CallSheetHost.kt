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
 * One message of the Home call-sheet unit, as the publish dialog's Replace
 * picker needs it — the slice of the unit chat listing
 * `buildReplaceableDocuments` (`shared/workflow/replaceableDocuments.js`)
 * reads. [id] is the message's `_id`; a message the server has not
 * acknowledged yet has none and is never offered.
 */
data class UnitMessage(
    val id: String,
    val isDocument: Boolean,
    val hasMedia: Boolean,
    val name: String = "",
    val deleted: Boolean = false,
    /** Already moved to History — no longer a live document, the server refuses it as a target. */
    val archived: Boolean = false,
    val createdMs: Long = 0L,
)

/** A live document a publish can swap out — the option behind the Replace card. */
data class ReplaceTarget(val chatId: String, val label: String)

/**
 * `buildReplaceableDocuments`: DOCUMENT messages only (the server acts on
 * nothing else), not deleted, not archived, keyed on `_id` — never
 * `unique_id`, which the server does not match and which makes the post
 * quietly append — newest first.
 */
fun replaceTargets(messages: List<UnitMessage>): List<ReplaceTarget> =
    messages
        .filter { it.isDocument && it.hasMedia && !it.deleted && !it.archived && it.id.isNotBlank() }
        .sortedByDescending { it.createdMs }
        .map { ReplaceTarget(it.id, it.name.ifBlank { "Untitled document" }) }

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
     * The live messages of the Home call-sheet unit, the newest 300 in one
     * go (the chat's own page of 50 would truncate the list with no error).
     * A failure hides the Replace card; the publish must never break because
     * a side read did.
     */
    suspend fun unitMessages(): ZillitResult<List<UnitMessage>>

    /**
     * Posts a document into the Home call-sheet unit's chat. [replacePrevious]
     * is the publish type: New archives every live call sheet, Continuation
     * and attachments append. [replaceChatId] retires that ONE message
     * instead — `replace_chat_id`, sent only when there is a target.
     */
    suspend fun postToUnit(
        bytes: ByteArray,
        fileName: String,
        contentType: String,
        caption: String,
        replacePrevious: Boolean,
        replaceChatId: String? = null,
    ): ZillitResult<Unit>

    /** A PDF picked on this machine; null for a cancelled picker. */
    suspend fun pickPdf(): PickedDocument?

    /** Puts a signature (PNG) into storage for `signature_image`. */
    suspend fun uploadSignature(png: ByteArray): ZillitResult<ApprovalDecision.Signature>

    /** Shares the rendered PDF as a document in a one-to-one chat. */
    suspend fun sendPdfToChat(pdf: ByteArray, fileName: String, receiverId: String): ZillitResult<Unit>
}

/**
 * The tool's badges from the notification ledger, and the read that clears
 * one sheet's report or comment badges on one surface
 * (`notification:level:read` with unit, level_1 on the approval unit,
 * level_2 = kind, level_3 = id) — never unit-wide.
 */
interface SheetBadgeSource {
    val leaves: Flow<List<BadgeLeaf>> get() = emptyFlow()

    fun read(surface: BadgeSurface, kind: BadgeKind, sheetId: String) = Unit
}

/** OpenWeather One Call 3.0 for a point, raw — the sheet stores its own shape. */
fun interface SheetWeatherSource {
    suspend fun oneCall(lat: Double, lng: Double): ZillitResult<JsonObject>
}
