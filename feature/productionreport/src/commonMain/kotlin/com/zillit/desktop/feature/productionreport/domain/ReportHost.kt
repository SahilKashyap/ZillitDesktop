package com.zillit.desktop.feature.productionreport.domain

import com.zillit.desktop.core.common.ZillitResult
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
 * Where a report goes beyond this service: the tool's unit chat, Document
 * Distribution, and storage for a drawn signature. All of it lives in other
 * modules, so the host wires it.
 */
interface ReportPublishing {
    /** Document Distribution posting rights (or project admin) — the web's `canDistribute`. */
    fun canDistribute(): Boolean

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

    /** Posts the published PDF into the production report unit's chat; New replaces earlier posts. */
    suspend fun postToChat(pdf: ByteArray, fileName: String, replacePrevious: Boolean): ZillitResult<Unit>

    /** Picks PDFs on this machine and posts each into the unit chat. Success(0) is a cancelled picker. */
    suspend fun attachDocuments(replacePrevious: Boolean): ZillitResult<Int>

    /** Puts a drawn signature (PNG) into storage for `signature_image`. */
    suspend fun uploadSignature(png: ByteArray): ZillitResult<ApprovalDecision.Signature>
}

/** Opens the one-to-one chat with a crew member. False when the host has no chat to open. */
fun interface ReportChatOpener {
    fun openChat(userId: String, fullName: String): Boolean
}

/**
 * The tool's badges from the notification ledger, and the reads that clear
 * them: approval units by segment (`notification:read`), comments by thread or
 * tab (`notification:level:read`).
 */
interface ReportBadgeSource {
    val leaves: Flow<List<BadgeLeaf>> get() = emptyFlow()

    fun readUnits(units: List<String>) = Unit

    fun readCommentThread(reportId: String) = Unit

    fun readCommentTab(tab: String) = Unit
}

/** OpenWeather One Call 3.0 for a point, raw — the report stores its own shape. */
fun interface ReportWeatherSource {
    suspend fun oneCall(lat: Double, lng: Double): ZillitResult<JsonObject>
}
