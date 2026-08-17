package com.zillit.desktop.feature.callsheet.ui

import com.zillit.desktop.feature.callsheet.domain.CallSheetStatus
import com.zillit.desktop.feature.callsheet.domain.CallSheetSummary
import com.zillit.desktop.feature.callsheet.domain.CallSheetViewer
import com.zillit.desktop.feature.callsheet.domain.SheetMember
import com.zillit.desktop.feature.callsheet.domain.SheetMetadata
import com.zillit.desktop.feature.callsheet.domain.SheetPayload
import com.zillit.desktop.feature.callsheet.domain.SheetPdfPage

/** The tool's top-level tabs. Non-authors get approvals only. */
enum class CallSheetDestination(val label: String) {
    Drafts("Drafts"),
    Approvals("Approvals"),
    Published("Published"),
    ;

    fun visibleTo(viewer: CallSheetViewer): Boolean =
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
    val status: CallSheetStatus = CallSheetStatus.Draft,
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

data class CallSheetUiState(
    val viewer: CallSheetViewer = CallSheetViewer(),
    val destination: CallSheetDestination = CallSheetDestination.Drafts,
    val bucket: ApprovalBucket = ApprovalBucket.Sent,
    val loading: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
    val drafts: List<CallSheetSummary> = emptyList(),
    val sent: List<CallSheetSummary> = emptyList(),
    val received: List<CallSheetSummary> = emptyList(),
    val finalized: List<CallSheetSummary> = emptyList(),
    val published: List<CallSheetSummary> = emptyList(),
    val metadata: SheetMetadata = SheetMetadata(),
    val members: List<SheetMember> = emptyList(),
    val editor: SheetEditor? = null,
    val pdf: SheetPdfView? = null,
    val send: SendDialog? = null,
    val publish: PublishDialog? = null,
) {
    val listFor: List<CallSheetSummary>
        get() = when (destination) {
            CallSheetDestination.Drafts -> drafts
            CallSheetDestination.Published -> published
            CallSheetDestination.Approvals -> when (bucket) {
                ApprovalBucket.Sent -> sent
                ApprovalBucket.Received -> received
                ApprovalBucket.Finalized -> finalized
            }
        }
}

sealed interface CallSheetEvent {
    data class Open(val destination: CallSheetDestination) : CallSheetEvent
    data class OpenBucket(val bucket: ApprovalBucket) : CallSheetEvent
    data object Refresh : CallSheetEvent
    data object NewSheet : CallSheetEvent
    data class EditSheet(val id: String) : CallSheetEvent
    data class ViewPdf(val id: String, val title: String) : CallSheetEvent
    data object ClosePdf : CallSheetEvent
    data class DeleteSheet(val id: String) : CallSheetEvent

    // Editor
    data class NameChanged(val name: String) : CallSheetEvent
    data class SharedChanged(
        val shootDayNumber: String? = null,
        val totalDays: String? = null,
        val dayType: String? = null,
    ) : CallSheetEvent
    data class ValueChanged(
        val row: Int,
        val cell: Int,
        val line: Int,
        val column: Int,
        val value: String,
    ) : CallSheetEvent
    data class AddLine(val row: Int, val cell: Int) : CallSheetEvent
    data object SaveDraft : CallSheetEvent
    data object CloseEditor : CallSheetEvent

    // Review
    data class OpenSend(val id: String, val name: String) : CallSheetEvent
    data class SendModeChanged(val forComments: Boolean) : CallSheetEvent
    data class ToggleReviewer(val userId: String) : CallSheetEvent
    data object ConfirmSend : CallSheetEvent
    data object DismissSend : CallSheetEvent
    data class Approve(val sheetId: String) : CallSheetEvent
    data class Reject(val sheetId: String, val reason: String) : CallSheetEvent

    // Publish
    data class OpenPublish(val id: String, val name: String) : CallSheetEvent
    data class PublishOptionsChanged(
        val continuation: Boolean? = null,
        val notes: String? = null,
    ) : CallSheetEvent
    data object ConfirmPublish : CallSheetEvent
    data object DismissPublish : CallSheetEvent

    data object DismissError : CallSheetEvent
}

sealed interface CallSheetEffect {
    data class Notice(val message: String) : CallSheetEffect
}
