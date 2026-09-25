package com.zillit.desktop.feature.cashexpenses.ui

import com.zillit.desktop.feature.cashexpenses.domain.CashAssignmentRule
import com.zillit.desktop.feature.cashexpenses.domain.CashSettings
import com.zillit.desktop.feature.cashexpenses.domain.DraftReceipt
import com.zillit.desktop.feature.cashexpenses.domain.EditorLine
import com.zillit.desktop.feature.cashexpenses.domain.ExpenseType
import com.zillit.desktop.feature.cashexpenses.domain.ExportFormat
import com.zillit.desktop.feature.cashexpenses.domain.ReconDraft
import com.zillit.desktop.feature.cashexpenses.domain.RequestCap

/** Everything the user can do in the cash tool. */
sealed interface CashEvent {

    data object Refresh : CashEvent

    /**
     * Which way this composition opened the tool: on its own ([asTool]) or
     * inside the Account Hub.
     *
     * Sent by the tool's composition from `LocalHostedBy`. An accountant who
     * opened the tool on its own gets the crew view — the web's
     * `?entry=tool` — and the rights every handler checks follow the same
     * viewer, so nothing the crew view hides can be reached through an event.
     */
    data class Enter(val asTool: Boolean) : CashEvent

    /** Opens a claim's stored receipt through the host's file layer. */
    data class ViewReceipt(val receiptUrl: String) : CashEvent

    data class AssignPickUser(val userId: String) : CashEvent

    data class AssignReason(val text: String) : CashEvent

    data class Open(val destination: CashDestination) : CashEvent

    data class SwitchPipeline(val pipeline: ExpenseType) : CashEvent

    data class Search(val query: String) : CashEvent

    data class SelectBatch(val batchId: String?) : CashEvent

    data class SelectFloat(val floatId: String?) : CashEvent

    /** Opens the Float Details dialog: fetches the float's details and reads its `pc_float` row. */
    data class OpenFloatDetail(val floatId: String) : CashEvent

    data object CloseFloatDetail : CashEvent

    /** Corrects a float's BS code — an accountant, on a float nothing is spent against yet. */
    data class SaveFloatBsCode(val floatId: String, val bsCode: String) : CashEvent

    /** "Set Approval Level": the Account Hub's Approvers page, on this module's chain. */
    data object OpenApprovalLevels : CashEvent

    /** Reads the chart of accounts for the nominal pickers, once; a no-op when it is loaded. */
    data object LoadChartAccounts : CashEvent

    /** Dismisses the confirmation toast. */
    data object ClearNotice : CashEvent

    // -- prompts -----------------------------------------------------------

    data class Ask(val prompt: CashPrompt) : CashEvent

    data class UpdatePrompt(val prompt: CashPrompt) : CashEvent

    data object DismissPrompt : CashEvent

    data object ConfirmPrompt : CashEvent

    // -- submit receipts ---------------------------------------------------

    data object AddReceipt : CashEvent

    data class RemoveReceipt(val index: Int) : CashEvent

    data class EditReceipt(val index: Int, val receipt: DraftReceipt) : CashEvent

    data class EditSubmitNotes(val notes: String) : CashEvent

    /** Picks and uploads the receipt file for the Submit form's row [index]. */
    data class AttachReceipt(val index: Int) : CashEvent

    data object SubmitReceipts : CashEvent

    // -- float request -----------------------------------------------------

    data class EditFloatRequest(val draft: FloatRequestDraft) : CashEvent

    data object SubmitFloatRequest : CashEvent

    // -- coding ------------------------------------------------------------

    data class CodeClaim(
        val batchId: String,
        val claimId: String,
        val costCode: String,
        val description: String?,
    ) : CashEvent

    // -- coding editor -----------------------------------------------------

    /** Opens a receipt's line items for coding. */
    data class OpenCoding(val batchId: String, val claimId: String) : CashEvent

    data object CloseCoding : CashEvent

    data class EditCodingLine(val index: Int, val line: EditorLine) : CashEvent

    data object AddCodingLine : CashEvent

    data class RemoveCodingLine(val id: String) : CashEvent

    /** Splits a line into [ways] equal children. */
    data class SplitCodingLine(val id: String, val ways: Int) : CashEvent

    data object SaveCoding : CashEvent

    // -- settings ----------------------------------------------------------

    data class EditSettings(val settings: CashSettings) : CashEvent

    data object SaveSettings : CashEvent

    /** Opens the member editor; null closes it. */
    data class EditTeamMember(val draft: TeamMemberDraft?) : CashEvent

    data object SaveTeamMember : CashEvent

    data class RemoveTeamMember(val index: Int) : CashEvent

    data class EditRequestCap(val cap: RequestCap?) : CashEvent

    data object SaveRequestCap : CashEvent

    data class EditAssignmentRules(val rules: List<CashAssignmentRule>?) : CashEvent

    data object SaveAssignmentRules : CashEvent

    // -- the open batch ------------------------------------------------------

    data class EditEffectiveDate(val ymd: String) : CashEvent

    data class EditSeniorNotes(val text: String) : CashEvent

    /** Ticks a receipt in or out of a partial approval. */
    data class ToggleClaim(val claimId: String) : CashEvent

    data class SelectAllClaims(val selected: Boolean) : CashEvent

    /** The auditor's per-receipt Verify. */
    data class ToggleVerify(val claimId: String) : CashEvent

    data class ShowHistory(val open: Boolean) : CashEvent

    data class ShowQuery(val open: Boolean) : CashEvent

    data class EditQuery(val text: String) : CashEvent

    data object SendQuery : CashEvent

    // -- reconciliation --------------------------------------------------------

    data class OpenReconciliation(val id: String) : CashEvent

    data class EditReconciliation(val draft: ReconDraft) : CashEvent

    data object CloseReconciliation : CashEvent

    data object SaveReconciliation : CashEvent

    // -- floats and funds --------------------------------------------------------

    /** Opens the float request as an accountant raising one for crew. */
    data object RaiseFloatForCrew : CashEvent

    data class ShowFunds(val open: Boolean) : CashEvent

    data class EditFunds(val state: FundsState) : CashEvent

    data object SubmitFunds : CashEvent

    // -- exports -----------------------------------------------------------------

    data class Export(val register: ExportRegister, val format: ExportFormat) : CashEvent

    // -- floats parity --

    /** Runs [prompt] straight away, with no dialog — the web's one-click actions. */
    data class ActNow(val prompt: CashPrompt) : CashEvent

    /** Opens or closes an Active Floats row onto its batches. */
    data class ToggleFloatBatches(val floatId: String) : CashEvent

    /** Picks one of an opened float's batches, or clears the pick when it is picked again. */
    data class SelectFloatBatch(val floatId: String, val batchId: String) : CashEvent

    /** Opens the float's history drawer; null closes it. */
    data class ShowFloatHistory(val floatId: String?, val reference: String? = null) : CashEvent

    /** Opens or closes one posted batch in the Float Details dialog, fetching its receipts once. */
    data class ToggleDetailBatch(val batchId: String) : CashEvent
    // -- funds parity --
    /** Top-ups, Cash Extension, fund requests and the reconciliation's writes — see [FundsDesk]. */
    data class Funds(val action: FundsAction) : CashEvent
}

/** What an export writes out. */
enum class ExportRegister { Floats, PettyCashReceipts, OutOfPocketReceipts, History }

/** One-shot things the screen must do that state cannot express. */
sealed interface CashEffect {

    /** Something failed in a way worth interrupting for. */
    data class Failed(val message: String) : CashEffect

    /** An attachment the user asked to see, as a storage key. */
    data class OpenAttachment(val key: String) : CashEffect

    /** Another page of the workspace — a route the tool's navigator opens. */
    data class Navigate(val path: String) : CashEffect
}

/** The web's `/film-tools/account-hub/approvers?module=cash_expenses`. */
const val CASH_APPROVERS_ROUTE = "/film-tools/account-hub/approvers?module=cash_expenses"
