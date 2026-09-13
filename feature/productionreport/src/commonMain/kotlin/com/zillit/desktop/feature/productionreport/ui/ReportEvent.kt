package com.zillit.desktop.feature.productionreport.ui

import com.zillit.desktop.feature.productionreport.domain.ApprovalSection
import com.zillit.desktop.feature.productionreport.domain.CellKind
import com.zillit.desktop.feature.productionreport.domain.DraftChip
import com.zillit.desktop.feature.productionreport.domain.EditorSelection
import com.zillit.desktop.feature.productionreport.domain.InsertKind
import com.zillit.desktop.feature.productionreport.domain.ManageTab
import com.zillit.desktop.feature.productionreport.domain.ReportSummary
import com.zillit.desktop.feature.productionreport.domain.SavedTemplate

/** Everything the production report screen can ask for. */
sealed interface ReportEvent

/** The workspace, the lists and the row actions. */
sealed interface ListEvent : ReportEvent {
    data class SetWorkspace(val workspace: Workspace) : ListEvent
    data class OpenTab(val tab: ManageTab) : ListEvent
    data class OpenSection(val section: ApprovalSection) : ListEvent
    data class SetDraftChip(val chip: DraftChip) : ListEvent
    data class SetDraftsView(val view: ListView) : ListEvent
    data class SetApprovalsView(val view: ListView) : ListEvent
    data object ToggleOlderPublished : ListEvent
    data object Retry : ListEvent

    data class View(val report: ReportSummary) : ListEvent
    data object ClosePdf : ListEvent
    data object DownloadPdf : ListEvent
    data class Edit(val report: ReportSummary) : ListEvent
    data class Delete(val report: ReportSummary) : ListEvent
    data class OpenHistory(val report: ReportSummary, val title: String) : ListEvent
    data class OpenComments(val report: ReportSummary, val readOnly: Boolean) : ListEvent
    data class ChatWithApprovers(val report: ReportSummary) : ListEvent
    data class ChatWithCreator(val report: ReportSummary) : ListEvent
    data class ChatWith(val userId: String) : ListEvent
    data object AttachDocument : ListEvent
}

/** Sending, approving, rejecting, reminding, publishing and Document Distribution. */
sealed interface WorkflowEvent : ReportEvent {
    data class SendForSignature(val report: ReportSummary) : WorkflowEvent
    data class SendForComments(val report: ReportSummary) : WorkflowEvent
    data class SendToDocDist(val report: ReportSummary, val fromDraft: Boolean) : WorkflowEvent
    data object ConfirmDocDist : WorkflowEvent

    data object ChooseComments : WorkflowEvent
    data object ChooseSignature : WorkflowEvent
    data object BackToChooser : WorkflowEvent
    data class SearchRecipients(val query: String) : WorkflowEvent
    data class ToggleRecipient(val userId: String) : WorkflowEvent
    data object ToggleAllRecipients : WorkflowEvent
    data object SendRecipients : WorkflowEvent
    data class FinishSend(val revokeAccess: Boolean) : WorkflowEvent
    data object CancelRemoval : WorkflowEvent

    data class OpenApprove(val report: ReportSummary) : WorkflowEvent
    class ApproveWithSignature(val png: ByteArray) : WorkflowEvent
    data object ApproveWithoutSignature : WorkflowEvent
    data class OpenReject(val report: ReportSummary) : WorkflowEvent
    data class EditRejectReason(val reason: String) : WorkflowEvent
    data object ConfirmReject : WorkflowEvent
    data class OpenReminder(val report: ReportSummary) : WorkflowEvent
    data class EditReminder(val message: String) : WorkflowEvent
    data object ConfirmReminder : WorkflowEvent
    data class ViewReminders(val report: ReportSummary) : WorkflowEvent

    data class OpenPublish(val report: ReportSummary) : WorkflowEvent
    data class PickDestination(val destination: PublishDestination) : WorkflowEvent
    data object ContinuePublish : WorkflowEvent
    data class PickContinuation(val continuation: Boolean) : WorkflowEvent
    data object ConfirmPublish : WorkflowEvent
}

/** Dialog chrome, templates and the comment thread. */
sealed interface DialogEvent : ReportEvent {
    data object Dismiss : DialogEvent
    data object Confirm : DialogEvent
    data object ConfirmSecondary : DialogEvent
    data class EditDraftName(val name: String) : DialogEvent
    data object ConfirmDraftName : DialogEvent

    data object CreateTemplate : DialogEvent
    data class PickTemplate(val index: Int) : DialogEvent
    data class UseTemplate(val index: Int) : DialogEvent
    data class OpenSavedTemplate(val template: SavedTemplate) : DialogEvent
    data class DeleteSavedTemplate(val template: SavedTemplate) : DialogEvent

    data class EditCommentDraft(val text: String) : DialogEvent
    data object SendComment : DialogEvent
    data class StartCommentEdit(val commentId: String) : DialogEvent
    data class EditCommentText(val text: String) : DialogEvent
    data object SaveCommentEdit : DialogEvent
    data object CancelCommentEdit : DialogEvent
    data class AskDeleteComment(val commentId: String) : DialogEvent
    data object ConfirmDeleteComment : DialogEvent
    data object CancelDeleteComment : DialogEvent
}

/** The editor chrome: leaving, saving, zoom, split and the pane. */
sealed interface EditorEvent : ReportEvent {
    data object Back : EditorEvent
    data object ToggleFocus : EditorEvent
    data class Zoom(val delta: Int) : EditorEvent
    data object ResetZoom : EditorEvent
    data class Split(val percent: Float) : EditorEvent
    data object ShowSections : EditorEvent
    data object HideSections : EditorEvent
    data object ClosePane : EditorEvent
    data class Select(val selection: EditorSelection?) : EditorEvent
    data class Focus(val line: Int?, val column: Int?) : EditorEvent
    data object ToggleSaveMenu : EditorEvent
    data object Save : EditorEvent
    data object SaveAs : EditorEvent
    data object SaveAsTemplate : EditorEvent
    data object UpdateTemplate : EditorEvent
    data object OpenSend : EditorEvent
    data class SetSectionSearch(val query: String) : EditorEvent
    data object Undo : EditorEvent
    data class ExpireUndo(val serial: Long) : EditorEvent
    data class RestoreDefault(val index: Int) : EditorEvent
}

/** Changes to the document itself. */
sealed interface DocumentEvent : ReportEvent {
    data class InsertRow(
        val kind: InsertKind,
        val afterIndex: Int,
        val lockApprovers: Boolean = false,
        val aboveHeader: Boolean = false,
    ) : DocumentEvent
    data class InsertCell(val row: Int, val afterCell: Int, val kind: InsertKind) : DocumentEvent
    data class RemoveRow(val row: Int) : DocumentEvent
    data class RemoveCell(val row: Int, val cell: Int) : DocumentEvent
    data class MoveBlock(val fromKey: String, val toKey: String) : DocumentEvent

    data class SetShootDay(val value: String) : DocumentEvent
    data class SetTotalDays(val value: String) : DocumentEvent
    data class SetDate(val ymd: String) : DocumentEvent
    data class SetDayType(val value: String) : DocumentEvent
    data class AddDayType(val name: String) : DocumentEvent
    data class ToggleApprover(val userId: String) : DocumentEvent
    data object SaveApprovers : DocumentEvent

    data class SetTitle(val row: Int, val cell: Int, val title: String) : DocumentEvent
    data class SetHideTitle(val row: Int, val cell: Int, val hidden: Boolean) : DocumentEvent
    data class SetVertical(val row: Int, val cell: Int, val vertical: Boolean) : DocumentEvent
    data class SetKind(val row: Int, val cell: Int, val kind: CellKind) : DocumentEvent
    data class SetNotesHorizontal(val row: Int, val cell: Int, val horizontal: Boolean) : DocumentEvent
    data class AddColumn(val row: Int, val cell: Int) : DocumentEvent
    data class RemoveColumn(val row: Int, val cell: Int, val column: Int) : DocumentEvent
    data class RenameColumn(val row: Int, val cell: Int, val column: Int, val label: String) : DocumentEvent
    data class SetColumnType(val row: Int, val cell: Int, val column: Int, val type: String) : DocumentEvent
    data class SetColumnWidths(val row: Int, val cell: Int, val widths: List<Double>) : DocumentEvent
    data class AddLine(val row: Int, val cell: Int) : DocumentEvent
    data class RemoveLine(val row: Int, val cell: Int, val line: Int) : DocumentEvent
    data class SetLineHeight(val row: Int, val cell: Int, val line: Int, val height: Int) : DocumentEvent
    data class SetValue(val row: Int, val cell: Int, val line: Int, val column: Int, val value: String) : DocumentEvent
    data class SetLocation(
        val row: Int,
        val cell: Int,
        val line: Int,
        val column: Int,
        val address: String,
        val lat: Double?,
        val lng: Double?,
    ) : DocumentEvent
    data class ApplyToColumn(val row: Int, val cell: Int, val column: Int, val value: String) : DocumentEvent

    data class SetWeatherText(val row: Int, val cell: Int, val text: String) : DocumentEvent
    data class FetchWeather(val row: Int, val cell: Int, val lat: Double, val lng: Double, val location: String) :
        DocumentEvent
    data class PickWeatherDay(val row: Int, val cell: Int, val index: Int) : DocumentEvent
    data class RefreshWeather(val row: Int, val cell: Int) : DocumentEvent
}
