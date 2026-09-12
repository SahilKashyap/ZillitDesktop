package com.zillit.desktop.feature.bankrec.ui

import com.zillit.desktop.feature.bankrec.domain.AuditExportFormat
import com.zillit.desktop.feature.bankrec.domain.AuditFilters
import com.zillit.desktop.feature.bankrec.domain.ExceptionStatus
import com.zillit.desktop.feature.bankrec.domain.PickedStatement
import com.zillit.desktop.feature.bankrec.domain.PortalLink
import com.zillit.desktop.feature.bankrec.domain.PortalLinkDraft
import com.zillit.desktop.feature.bankrec.domain.QuickAddForm
import com.zillit.desktop.feature.bankrec.domain.QuickEntryType
import com.zillit.desktop.feature.bankrec.domain.WorkspaceFilter

sealed interface BankRecEvent {

    // -- shell --------------------------------------------------------------

    data class OpenTab(val tab: BankTab) : BankRecEvent
    data object Refresh : BankRecEvent
    data object ClearNotice : BankRecEvent

    /** Opens the workspace on a named period, in the full view — Overview's and History's Open. */
    data class OpenPeriod(val periodId: String) : BankRecEvent

    // -- periods: Overview and History --------------------------------------

    data class TogglePeriodSelection(val scope: PeriodScope, val periodId: String) : BankRecEvent
    data class ToggleAllPeriods(val scope: PeriodScope) : BankRecEvent
    data class ClearPeriodSelection(val scope: PeriodScope) : BankRecEvent

    /** A row's Delete (one id) or the selection's (every selected id). */
    data class AskDeletePeriods(val periodIds: List<String>) : BankRecEvent
    data object DismissDeletePeriods : BankRecEvent
    data object ConfirmDeletePeriods : BankRecEvent

    data class SetHistoryAccount(val bankAccountId: String) : BankRecEvent
    data class OpenPeriodDetail(val periodId: String) : BankRecEvent
    data object ClosePeriodDetail : BankRecEvent

    data object OpenExportPdf : BankRecEvent
    data class ToggleExportPeriod(val periodId: String) : BankRecEvent
    data object ToggleAllExportPeriods : BankRecEvent
    data object CloseExportPdf : BankRecEvent
    data object ConfirmExportPdf : BankRecEvent

    // -- importing a statement ----------------------------------------------

    data object OpenImport : BankRecEvent
    data class SelectImportAccount(val bankAccountId: String) : BankRecEvent
    data object BrowseStatement : BankRecEvent
    data class DropStatement(val file: PickedStatement) : BankRecEvent
    data class ImportDragOver(val over: Boolean) : BankRecEvent
    data object StartImport : BankRecEvent
    data object CloseImport : BankRecEvent

    // -- the workspace ------------------------------------------------------

    data class FilterWorkspace(val filter: WorkspaceFilter) : BankRecEvent
    data class SelectRow(val rowId: String) : BankRecEvent
    data class SetWorkspaceExpanded(val expanded: Boolean) : BankRecEvent
    data object ToggleQuickEntry : BankRecEvent
    data object RerunAutoMatch : BankRecEvent

    /** "View ›" on a suggestion: scroll to the invoice and light it for a moment. */
    data class ViewInvoice(val invoiceId: String) : BankRecEvent

    /** "Accept ›" on a suggestion — confirmed before it is sent. */
    data class ProposeMatch(val transactionId: String, val invoiceId: String) : BankRecEvent
    data object DismissMatch : BankRecEvent
    data object ConfirmMatch : BankRecEvent

    data class OpenManualMatch(val transactionId: String) : BankRecEvent
    data class PickManualMatch(val ledgerRowId: String?) : BankRecEvent
    data object CloseManualMatch : BankRecEvent
    data object ConfirmManualMatch : BankRecEvent

    data object OpenSignOff : BankRecEvent
    data class EditSignOffNote(val note: String) : BankRecEvent
    data object CloseSignOff : BankRecEvent
    data object ConfirmSignOff : BankRecEvent

    // -- quick entry --------------------------------------------------------

    data class SetQuickEntryType(val type: QuickEntryType) : BankRecEvent
    data class OpenQuickAdd(val transactionId: String) : BankRecEvent
    data object ClearQuickAdd : BankRecEvent
    data class EditQuickEntry(val form: QuickAddForm) : BankRecEvent
    data class EditQuickEntryFraud(val reason: String, val priority: String) : BankRecEvent
    data object AddAndMatch : BankRecEvent
    data class OpenFxEntry(val transactionId: String) : BankRecEvent

    data class EditFxEntry(
        val currency: String,
        val foreignAmount: String,
        val bankRate: String,
    ) : BankRecEvent

    data object PostWorkspaceFx : BankRecEvent

    // -- exceptions ---------------------------------------------------------

    data class SetExceptionsPeriod(val choice: String) : BankRecEvent
    data class SetExceptionStatus(val exceptionId: String, val status: ExceptionStatus) : BankRecEvent
    data class OpenExceptionQuickAdd(val exceptionId: String) : BankRecEvent
    data class EditExceptionQuickAdd(val form: QuickAddForm) : BankRecEvent
    data object CloseExceptionQuickAdd : BankRecEvent
    data object SubmitExceptionQuickAdd : BankRecEvent
    data object ExportExceptionsPdf : BankRecEvent

    // -- fraud --------------------------------------------------------------

    data class SetFraudPeriod(val choice: String) : BankRecEvent
    data class DismissAlert(val alertId: String) : BankRecEvent
    data class EscalateAlert(val alertId: String) : BankRecEvent
    data object OpenAuditLog : BankRecEvent
    data object CloseAuditLog : BankRecEvent
    data class FilterAuditLog(val filters: AuditFilters) : BankRecEvent
    data class SortAuditLog(val sort: AuditSort) : BankRecEvent
    data class ShowAuditExportMenu(val show: Boolean) : BankRecEvent
    data class ExportAuditLog(val format: AuditExportFormat) : BankRecEvent

    // -- FX -----------------------------------------------------------------

    data class SetFxPeriod(val choice: String) : BankRecEvent
    data class OpenFxPost(val varianceId: String) : BankRecEvent

    data class EditFxPost(
        val nominalCode: String,
        val costCentre: String,
        val budgetRate: String,
        val bankRate: String,
    ) : BankRecEvent

    data object CloseFxPost : BankRecEvent
    data object ConfirmFxPost : BankRecEvent
    data object PostAllFx : BankRecEvent

    // -- shared links -------------------------------------------------------

    data class SelectPortalPeriod(val periodId: String) : BankRecEvent
    data object ComposePortalLink : BankRecEvent
    data class EditPortalLink(val link: PortalLink) : BankRecEvent
    data class EditPortalDraft(val draft: PortalLinkDraft) : BankRecEvent
    data object DismissPortalDraft : BankRecEvent
    data object SavePortalLink : BankRecEvent
    data class CopyPortalLink(val link: PortalLink) : BankRecEvent
    data class RevokePortalLink(val link: PortalLink) : BankRecEvent

    // -- rules --------------------------------------------------------------

    data class ToggleMatchRule(val key: String, val enabled: Boolean) : BankRecEvent
    data class ToggleFraudRule(val key: String, val enabled: Boolean) : BankRecEvent
    data class SetFraudThreshold(val key: String, val amount: Double?) : BankRecEvent
    data object SaveMatchRules : BankRecEvent
    data object SaveFraudRules : BankRecEvent
}

/** One-shot things the module asks the host to do. */
sealed interface BankRecEffect {
    data class Failed(val message: String) : BankRecEffect

    /** Puts a shared link's URL on the clipboard. */
    data class CopyToClipboard(val text: String) : BankRecEffect
}
