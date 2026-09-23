package com.zillit.desktop.feature.productionreport.domain

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.serialization.json.JsonObject

/**
 * The server-rendered PDF of a saved report. A seam because the bytes stream
 * outside the enveloped API client, and rendering pages needs PDFBox.
 */
interface ReportDelivery {
    /** `GET /production-reports/{id}/pdf`. */
    suspend fun pdf(reportId: String): ZillitResult<ByteArray>

    /** PDF bytes as page images for the in-app viewer. */
    fun renderPages(pdf: ByteArray, targetWidthPx: Int): ZillitResult<List<SheetPdfPage>>

    /** Writes the PDF to Downloads and opens it — the web viewer's download and print. */
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

/**
 * A live DOCUMENT message in the tool's unit chat that a publish may swap
 * out — `buildReplaceableDocuments`. [chatId] is the message's `_id`, never
 * its `unique_id`: a `unique_id` target finds nothing and the post quietly
 * appends.
 */
data class ReplaceTarget(val chatId: String, val label: String)

/**
 * Where a report goes beyond this service: the tool's unit chat, Document
 * Distribution, a crew member's 1:1 chat, and storage for a drawn signature.
 * All of it lives in other modules, so the host wires it.
 */
interface ReportPublishing {
    /** Document Distribution posting rights (or project admin) — the web's `canDistribute`. */
    fun canDistribute(): Boolean

    /**
     * The unit chat's live documents (newest first) for the publish dialog's
     * Replace card. A failure degrades to the two-card dialog — publishing
     * never depends on this call.
     */
    suspend fun replaceableDocuments(): ZillitResult<List<ReplaceTarget>> = ZillitResult.Success(emptyList())

    /**
     * ZL-21415 "Send for Chat": the report's PDF as a document message in a
     * 1:1 C&C chat with [userId]. Hosts without a chat refuse.
     */
    suspend fun sendPdfToChat(userId: String, pdf: ByteArray, fileName: String): ZillitResult<Unit> =
        ZillitResult.Failure(ZillitError.Validation(str(S.desktop_chat_sharing_not_available_here)))

    /**
     * Stores the PDF and files it in Document Distribution under
     * `Production Report` (or `Draft Production Report` before final approval),
     * dated by the shoot date.
     */
    suspend fun sendToDocumentDistribution(
        pdf: ByteArray,
        fileName: String,
        fromDraft: Boolean,
        dateYmd: String?,
    ): ZillitResult<Unit>

    /**
     * Posts the published PDF into the production report unit's chat. New
     * ([replacePrevious]) retires every earlier post; Replace names the ONE
     * document to retire in [replaceChatId] and never wipes the unit; a
     * Continuation appends. The server refuses a target no longer live with
     * `unit_chat_replace_target_not_found`.
     */
    suspend fun postToChat(
        pdf: ByteArray,
        fileName: String,
        replacePrevious: Boolean,
        replaceChatId: String? = null,
    ): ZillitResult<Unit>

    /** Picks PDFs on this machine and posts each into the unit chat. Success(0) is a cancelled picker. */
    suspend fun attachDocuments(replacePrevious: Boolean): ZillitResult<Int>

    /** Puts a drawn signature (PNG) into storage for `signature_image`. */
    suspend fun uploadSignature(png: ByteArray): ZillitResult<ApprovalDecision.Signature>
}

/**
 * The tool's badges from the notification ledger, and the read that clears
 * them — `readProductionReportBadge`: one report's REPORT or COMMENT badges
 * on one surface (`notification:level:read` with unit, `level_1` on the
 * approval unit, `level_2` = kind, `level_3` = id). Never unit-wide: a
 * unit-wide read wiped Sent and Finalized together.
 */
interface ReportBadgeSource {
    val leaves: Flow<List<BadgeLeaf>> get() = emptyFlow()

    fun readBadge(surface: BadgeSurface, kind: BadgeKind, reportId: String) = Unit
}

/** OpenWeather One Call 3.0 for a point, raw — the report stores its own shape. */
fun interface ReportWeatherSource {
    suspend fun oneCall(lat: Double, lng: Double): ZillitResult<JsonObject>
}
