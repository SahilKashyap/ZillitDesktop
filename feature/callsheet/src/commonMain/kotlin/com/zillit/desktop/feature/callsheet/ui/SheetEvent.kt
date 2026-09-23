package com.zillit.desktop.feature.callsheet.ui

import com.zillit.desktop.feature.callsheet.domain.AccessPerson
import com.zillit.desktop.feature.callsheet.domain.ApprovalSection
import com.zillit.desktop.feature.callsheet.domain.CallSheetSummary
import com.zillit.desktop.feature.callsheet.domain.CellKind
import com.zillit.desktop.feature.callsheet.domain.DraftChip
import com.zillit.desktop.feature.callsheet.domain.EditorSelection
import com.zillit.desktop.feature.callsheet.domain.InsertKind
import com.zillit.desktop.feature.callsheet.domain.MissingTitle
import com.zillit.desktop.feature.callsheet.domain.SavedTemplate
import com.zillit.desktop.feature.callsheet.domain.SheetTab

/** Everything the call sheet screen can ask for. */
sealed interface SheetEvent

/** The tabs, the lists and the row actions. */
sealed interface ListEvent : SheetEvent {
    data class OpenTab(val tab: SheetTab) : ListEvent
    data class OpenSection(val section: ApprovalSection) : ListEvent
    data class SetDraftChip(val chip: DraftChip) : ListEvent
    data class SetDraftsView(val view: ListView) : ListEvent
    data class SetApprovalsView(val view: ListView) : ListEvent
    data object ToggleOlderPublished : ListEvent
    data object Retry : ListEvent

    data class View(val sheet: CallSheetSummary) : ListEvent
    data object ClosePdf : ListEvent
    data object DownloadPdf : ListEvent
    data class Edit(val sheet: CallSheetSummary) : ListEvent
    data class Delete(val sheet: CallSheetSummary) : ListEvent

    /** History; [fromDetail] fetches the sheet first (every list but Received). */
    data class OpenHistory(val sheet: CallSheetSummary, val title: String, val fromDetail: Boolean = true) : ListEvent
    data class OpenApprovalStatus(val sheet: CallSheetSummary) : ListEvent
    /** [readOnly] is the screen's status rule; the thread also locks unless the viewer may post in it. */
    data class OpenComments(val sheet: CallSheetSummary, val readOnly: Boolean) : ListEvent
    data class ViewReminder(val sheet: CallSheetSummary) : ListEvent

    /** Published hero: a PDF posted into the Home call-sheet unit beside the live sheet. */
    data class AttachDocument(val sheet: CallSheetSummary) : ListEvent
}

/** The Permission tab. */
sealed interface PermissionEvent : SheetEvent {
    data class Search(val query: String) : PermissionEvent
    data class Page(val page: Int) : PermissionEvent
    data class PageSize(val size: Int) : PermissionEvent
    data class Toggle(val person: AccessPerson, val enable: Boolean) : PermissionEvent
    data object Retry : PermissionEvent
}

/** Sending, approving, rejecting, reminding, publishing and Document Distribution. */
sealed interface WorkflowEvent : SheetEvent {
    data class SendForSignature(val sheet: CallSheetSummary) : WorkflowEvent
    data class SendForComments(val sheet: CallSheetSummary) : WorkflowEvent
    data class SendToDocDist(val sheet: CallSheetSummary, val fromDraft: Boolean) : WorkflowEvent
    data object ConfirmDocDist : WorkflowEvent

    data class SearchRecipients(val query: String) : WorkflowEvent
    data class ToggleRecipient(val userId: String) : WorkflowEvent
    data object ToggleAllRecipients : WorkflowEvent
    data object SendRecipients : WorkflowEvent
    data class FinishSend(val revokeAccess: Boolean) : WorkflowEvent
    data object CancelRemoval : WorkflowEvent

    data class OpenSendForChat(val sheet: CallSheetSummary) : WorkflowEvent
    data class SearchChatRecipients(val query: String) : WorkflowEvent
    data class PickChatRecipient(val userId: String) : WorkflowEvent
    data object ConfirmSendForChat : WorkflowEvent

    /** Opens the chooser: with a signature, or without. */
    data class OpenApprove(val sheet: CallSheetSummary) : WorkflowEvent

    /** The chooser's "Approve with Signature": on to the pad. */
    data object ChooseSignature : WorkflowEvent

    /** "Use This Signature": the drawn pad becomes the confirmed signature. */
    class UseSignature(val png: ByteArray) : WorkflowEvent
    data object ChangeSignature : WorkflowEvent
    data object ApproveWithSignature : WorkflowEvent

    /** The chooser's "Approve Without Signature" — `{without_signature: true}` straight away. */
    data object ApproveWithoutSignature : WorkflowEvent

    data class OpenReject(val sheet: CallSheetSummary) : WorkflowEvent
    data class EditRejectReason(val reason: String) : WorkflowEvent
    data object ConfirmReject : WorkflowEvent
    data class OpenReminder(val sheet: CallSheetSummary) : WorkflowEvent
    data class EditReminder(val message: String) : WorkflowEvent
    data object ConfirmReminder : WorkflowEvent

    data class OpenPublish(val sheet: CallSheetSummary) : WorkflowEvent
    data class PickDestination(val destination: PublishDestination) : WorkflowEvent
    data object ContinuePublish : WorkflowEvent
    data object BackToDestination : WorkflowEvent
    data class PickPublishChoice(val choice: PublishChoice) : WorkflowEvent
    data class PickReplaceTarget(val chatId: String) : WorkflowEvent
    data class EditPublishNotes(val notes: String) : WorkflowEvent
    data object ConfirmPublish : WorkflowEvent
    data object AttachInstead : WorkflowEvent
    data class EditAttachCaption(val caption: String) : WorkflowEvent
    data object ConfirmAttach : WorkflowEvent
}

/** Dialog chrome, templates and the comment thread. */
sealed interface DialogEvent : SheetEvent {
    data object Dismiss : DialogEvent
    data object Confirm : DialogEvent
    data object ConfirmSecondary : DialogEvent
    data class EditDraftName(val name: String) : DialogEvent
    data object ConfirmDraftName : DialogEvent
    data class FixMissingTitle(val item: MissingTitle) : DialogEvent

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

/** The editor chrome: leaving, saving, split and the pane. */
sealed interface EditorEvent : SheetEvent {
    data object Back : EditorEvent
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
    data object SendForSignature : EditorEvent
    data object SendForComments : EditorEvent
    data class SetSectionSearch(val query: String) : EditorEvent
    data object Undo : EditorEvent
    data class ExpireUndo(val serial: Long) : EditorEvent
    data object QuickUndo : EditorEvent
    data class ExpireQuickUndo(val serial: Long) : EditorEvent
    data class RestoreDefault(val index: Int) : EditorEvent
}

/** Changes to the document itself. */
sealed interface DocumentEvent : SheetEvent {
    data class InsertRow(
        val kind: InsertKind,
        val afterIndex: Int,
        val lockApprovers: Boolean = false,
        val aboveHeader: Boolean = false,
    ) : DocumentEvent
    data class InsertCell(val row: Int, val afterCell: Int, val kind: InsertKind) : DocumentEvent
    data class RemoveRow(val row: Int) : DocumentEvent
    data class RemoveCell(val row: Int, val cell: Int, val fromPane: Boolean = false) : DocumentEvent
    data class MoveBlock(val fromKey: String, val toKey: String) : DocumentEvent

    data class SetShootDay(val value: String) : DocumentEvent
    data class SetTotalDays(val value: String) : DocumentEvent
    data class SetDate(val epochMs: Long) : DocumentEvent
    data class SetDayType(val value: String) : DocumentEvent
    data class AddDayType(val name: String) : DocumentEvent
    data class ToggleApprover(val userId: String) : DocumentEvent
    data object SaveApprovers : DocumentEvent

    data class SetTitle(val row: Int, val cell: Int, val title: String) : DocumentEvent
    data class SetHideTitle(val row: Int, val cell: Int, val hidden: Boolean) : DocumentEvent
    data class SetVertical(val row: Int, val cell: Int, val vertical: Boolean) : DocumentEvent
    data class SetKind(val row: Int, val cell: Int, val kind: CellKind) : DocumentEvent
    data class AddColumn(val row: Int, val cell: Int) : DocumentEvent
    data class RemoveColumn(val row: Int, val cell: Int, val column: Int) : DocumentEvent
    data class RenameColumn(val row: Int, val cell: Int, val column: Int, val label: String) : DocumentEvent
    data class SetColumnType(val row: Int, val cell: Int, val column: Int, val type: String) : DocumentEvent
    data class SetColumnWidths(val row: Int, val cell: Int, val widths: List<Double>) : DocumentEvent
    data class AddLine(val row: Int, val cell: Int) : DocumentEvent
    data class RemoveLine(val row: Int, val cell: Int, val line: Int) : DocumentEvent
    data class SetLineHeight(val row: Int, val cell: Int, val line: Int, val height: Int) : DocumentEvent
    data class SetValue(val row: Int, val cell: Int, val line: Int, val column: Int, val value: String) : DocumentEvent
    data class ApplyToColumn(val row: Int, val cell: Int, val column: Int, val value: String) : DocumentEvent

    data class SetWeatherText(val row: Int, val cell: Int, val text: String) : DocumentEvent
    data class FetchWeather(val row: Int, val cell: Int, val lat: Double, val lng: Double, val location: String) :
        DocumentEvent
    data class PickWeatherDay(val row: Int, val cell: Int, val index: Int) : DocumentEvent
    data class RefreshWeather(val row: Int, val cell: Int) : DocumentEvent
    data class ClearWeather(val row: Int, val cell: Int) : DocumentEvent
}
