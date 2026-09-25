package com.zillit.desktop.feature.cardexpenses.ui

import com.zillit.desktop.feature.cardexpenses.domain.BulkAction
import com.zillit.desktop.feature.cardexpenses.domain.ProcessLine
import com.zillit.desktop.feature.cardexpenses.domain.TaxLineDraft

/**
 * What the accountant does on the process pages — Approval Queue, Process,
 * Bulk Process, History — and in the full-page process editor. Handled by
 * [CardProcessActions].
 */
sealed interface ProcessPageEvent : CardEvent {

    // -- the approval queue ---------------------------------------------------

    /** Signs the row's next step, in one click (`ApprovalQueuePage.jsx:177-191`). */
    data class ApproveRow(val receiptId: String) : ProcessPageEvent

    /** Approves over the chain with "Accountant override", in one click. */
    data class OverrideRow(val receiptId: String) : ProcessPageEvent

    /** Opens a row's detail, read fresh from `/receipts/:id/detail`. */
    data class OpenDetail(val receiptId: String) : ProcessPageEvent
    data object CloseDetail : ProcessPageEvent

    data class AskReject(val receiptId: String) : ProcessPageEvent
    data class EditRejectReason(val reason: String) : ProcessPageEvent
    data object CancelReject : ProcessPageEvent
    data object ConfirmReject : ProcessPageEvent

    /** Approves or overrides the ticked rows — never rejects them (`ApprovalQueuePage.jsx:600-611`). */
    data class BulkApproval(val action: BulkAction) : ProcessPageEvent

    // -- ticking rows (approval queue and bulk process) ------------------------

    /** Ticks or unticks one row, if this viewer may tick it at all. */
    data class ToggleRow(val receiptId: String) : ProcessPageEvent

    /** The header box: ticks every row of [visibleIds] this viewer may tick, or clears them. */
    data class ToggleAllRows(val visibleIds: List<String>) : ProcessPageEvent

    // -- bulk process ----------------------------------------------------------

    data object BatchPost : ProcessPageEvent

    // -- the editor's line grid --------------------------------------------------

    data class SelectLine(val lineId: String?) : ProcessPageEvent

    /** Replaces the line with the same id; a parent's edits reach its children. */
    data class EditLine(val line: ProcessLine) : ProcessPageEvent

    /** A split child's amount: its siblings share the rest. */
    data class EditSplitAmount(val lineId: String, val amount: Double) : ProcessPageEvent
    data object SplitLine : ProcessPageEvent
    data object AddLine : ProcessPageEvent
    data class RemoveLine(val lineId: String) : ProcessPageEvent
    data class EditTaxLine(val taxLine: TaxLineDraft) : ProcessPageEvent

    /** Opens (or closes) the receipt's History panel. */
    data class ShowHistory(val open: Boolean) : ProcessPageEvent
}
