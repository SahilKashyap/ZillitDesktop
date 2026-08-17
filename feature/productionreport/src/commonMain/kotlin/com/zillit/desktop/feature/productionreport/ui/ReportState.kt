package com.zillit.desktop.feature.productionreport.ui

import com.zillit.desktop.feature.productionreport.domain.ReportStatus
import com.zillit.desktop.feature.productionreport.domain.ReportSummary
import com.zillit.desktop.feature.productionreport.domain.ReportViewer
import com.zillit.desktop.feature.productionreport.domain.SheetMember
import com.zillit.desktop.feature.productionreport.domain.SheetMetadata
import com.zillit.desktop.feature.productionreport.domain.SheetPayload
import com.zillit.desktop.feature.productionreport.domain.SheetPdfPage

/** The tool's top-level tabs. Non-authors get approvals only. */
enum class ReportDestination(val label: String) {
    Drafts("Drafts"),
    Approvals("Approvals"),
    Published("Published"),
    ;

    fun visibleTo(viewer: ReportViewer): Boolean =
        viewer.canAuthor || this == Approvals
}

/** The approvals tab's three buckets — same split as the web. */
enum class ApprovalBucket(val label: String) {
    Sent("Sent"),
    Received("Received"),
    Finalized("Finalized"),
}

/** The editor, open over one new or existing sheet. */
data class SheetEditor(
    /** Null until the first save creates the sheet. */
    val sheetId: String? = null,
    val name: String = "",
    val status: ReportStatus = ReportStatus.Draft,
    val payload: SheetPayload = SheetPayload(),
    val dirty: Boolean = false,
    val saving: Boolean = false,
)

/** The PDF viewer, open over one saved sheet. */
data class SheetPdfView(
    val sheetId: String = "",
    val title: String = "",
    val loading: Boolean = true,
    val pages: List<SheetPdfPage> = emptyList(),
)

/** The send-for-approval dialog: pick a route, and reviewers for comments. */
data class SendDialog(
    val sheetId: String = "",
    val sheetName: String = "",
    val forComments: Boolean = false,
    val selectedIds: Set<String> = emptySet(),
)

/** The publish dialog. */
data class PublishDialog(
    val sheetId: String = "",
    val sheetName: String = "",
    val continuation: Boolean = false,
    val notes: String = "",
)

data class ReportUiState(
    val viewer: ReportViewer = ReportViewer(),
    val destination: ReportDestination = ReportDestination.Drafts,
    val bucket: ApprovalBucket = ApprovalBucket.Sent,
    val loading: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
    val drafts: List<ReportSummary> = emptyList(),
    val sent: List<ReportSummary> = emptyList(),
    val received: List<ReportSummary> = emptyList(),
    val finalized: List<ReportSummary> = emptyList(),
    val published: List<ReportSummary> = emptyList(),
    val metadata: SheetMetadata = SheetMetadata(),
    val members: List<SheetMember> = emptyList(),
    val editor: SheetEditor? = null,
    val pdf: SheetPdfView? = null,
    val send: SendDialog? = null,
    val publish: PublishDialog? = null,
) {
    val listFor: List<ReportSummary>
        get() = when (destination) {
            ReportDestination.Drafts -> drafts
            ReportDestination.Published -> published
            ReportDestination.Approvals -> when (bucket) {
                ApprovalBucket.Sent -> sent
                ApprovalBucket.Received -> received
                ApprovalBucket.Finalized -> finalized
            }
        }
}

sealed interface ReportEvent {
    data class Open(val destination: ReportDestination) : ReportEvent
    data class OpenBucket(val bucket: ApprovalBucket) : ReportEvent
    data object Refresh : ReportEvent
    data object NewSheet : ReportEvent
    data class EditSheet(val id: String) : ReportEvent
    data class ViewPdf(val id: String, val title: String) : ReportEvent
    data object ClosePdf : ReportEvent
    data class DeleteSheet(val id: String) : ReportEvent

    // Editor
    data class NameChanged(val name: String) : ReportEvent
    data class SharedChanged(
        val shootDayNumber: String? = null,
        val totalDays: String? = null,
        val dayType: String? = null,
    ) : ReportEvent
    data class ValueChanged(
        val row: Int,
        val cell: Int,
        val line: Int,
        val column: Int,
        val value: String,
    ) : ReportEvent
    data class AddLine(val row: Int, val cell: Int) : ReportEvent
    data object SaveDraft : ReportEvent
    data object CloseEditor : ReportEvent

    // Review
    data class OpenSend(val id: String, val name: String) : ReportEvent
    data class SendModeChanged(val forComments: Boolean) : ReportEvent
    data class ToggleReviewer(val userId: String) : ReportEvent
    data object ConfirmSend : ReportEvent
    data object DismissSend : ReportEvent
    data class Approve(val sheetId: String) : ReportEvent
    data class Reject(val sheetId: String, val reason: String) : ReportEvent

    // Publish
    data class OpenPublish(val id: String, val name: String) : ReportEvent
    data class PublishOptionsChanged(
        val continuation: Boolean? = null,
        val notes: String? = null,
    ) : ReportEvent
    data object ConfirmPublish : ReportEvent
    data object DismissPublish : ReportEvent

    data object DismissError : ReportEvent
}

sealed interface ReportEffect {
    data class Notice(val message: String) : ReportEffect
}
