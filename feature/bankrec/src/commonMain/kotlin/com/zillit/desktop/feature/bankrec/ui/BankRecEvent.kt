package com.zillit.desktop.feature.bankrec.ui

import com.zillit.desktop.feature.bankrec.domain.BankException
import com.zillit.desktop.feature.bankrec.domain.BankPeriod
import com.zillit.desktop.feature.bankrec.domain.BankTransaction
import com.zillit.desktop.feature.bankrec.domain.ExceptionStatus
import com.zillit.desktop.feature.bankrec.domain.FraudAlert
import com.zillit.desktop.feature.bankrec.domain.FxVariance
import com.zillit.desktop.feature.bankrec.domain.LedgerEntry
import com.zillit.desktop.feature.bankrec.domain.PortalLink
import com.zillit.desktop.feature.bankrec.domain.PortalLinkDraft
import com.zillit.desktop.feature.bankrec.domain.QuickAddForm

sealed interface BankRecEvent {

    // -- shell --------------------------------------------------------------

    data class OpenTab(val tab: BankTab) : BankRecEvent
    data object Refresh : BankRecEvent
    data object ClearNotice : BankRecEvent

    /** Opens the workspace on a named period, from Overview or History. */
    data class OpenPeriod(val periodId: String) : BankRecEvent

    // -- importing a statement ----------------------------------------------

    data object ComposeImport : BankRecEvent
    data class EditImport(val bankAccountId: String, val periodId: String) : BankRecEvent
    data object DismissImport : BankRecEvent
    data object ChooseStatement : BankRecEvent

    // -- periods ------------------------------------------------------------

    data class AskDeletePeriods(val periods: List<BankPeriod>) : BankRecEvent
    data object DismissDeletePeriods : BankRecEvent
    data object ConfirmDeletePeriods : BankRecEvent

    // -- the workspace ------------------------------------------------------

    data class FilterWorkspace(val filter: WorkspaceFilter) : BankRecEvent
    data object RerunAutoMatch : BankRecEvent

    /** Picks the bank line to reconcile. */
    data class SelectTransaction(val transaction: BankTransaction?) : BankRecEvent

    /** Proposes a match, which is then confirmed. */
    data class ProposeMatch(
        val transaction: BankTransaction,
        val entry: LedgerEntry,
        val wasSuggested: Boolean = false,
    ) : BankRecEvent

    data object DismissMatch : BankRecEvent
    data object ConfirmMatch : BankRecEvent

    /** Accepts the engine's own suggestion for a line. */
    data class AcceptSuggestion(val transaction: BankTransaction) : BankRecEvent

    data object AskSignOff : BankRecEvent
    data class EditSignOffNote(val note: String) : BankRecEvent
    data object DismissSignOff : BankRecEvent
    data object ConfirmSignOff : BankRecEvent

    // -- exceptions ---------------------------------------------------------

    data class FilterExceptions(val periodId: String) : BankRecEvent
    data class SetExceptionStatus(val id: String, val status: ExceptionStatus) : BankRecEvent

    data class ComposeExceptionNote(val exception: BankException, val status: ExceptionStatus) :
        BankRecEvent

    data class EditExceptionNote(val text: String) : BankRecEvent
    data object DismissExceptionNote : BankRecEvent
    data object SaveExceptionNote : BankRecEvent

    data class ComposeQuickAdd(val exception: BankException) : BankRecEvent
    data class EditQuickAdd(val form: QuickAddForm) : BankRecEvent
    data object DismissQuickAdd : BankRecEvent
    data object SaveQuickAdd : BankRecEvent

    // -- fraud --------------------------------------------------------------

    data class FilterFraud(val periodId: String) : BankRecEvent
    data class DismissAlert(val alert: FraudAlert) : BankRecEvent
    data class AskEscalate(val alert: FraudAlert) : BankRecEvent
    data object DismissEscalate : BankRecEvent
    data object ConfirmEscalate : BankRecEvent
    data class ShowAuditLog(val show: Boolean) : BankRecEvent

    // -- FX -----------------------------------------------------------------

    data class FilterFx(val periodId: String) : BankRecEvent
    data class ComposeFxPosting(val variance: FxVariance) : BankRecEvent
    data class EditFxPosting(val nominalCode: String, val costCentre: String) : BankRecEvent
    data object DismissFxPosting : BankRecEvent
    data object ConfirmFxPosting : BankRecEvent
    data object AskPostAllFx : BankRecEvent
    data object DismissPostAllFx : BankRecEvent
    data object ConfirmPostAllFx : BankRecEvent

    // -- shared links -------------------------------------------------------

    data object ComposePortalLink : BankRecEvent
    data class EditPortalLink(val link: PortalLink) : BankRecEvent
    data class EditPortalDraft(val draft: PortalLinkDraft) : BankRecEvent
    data object DismissPortalDraft : BankRecEvent
    data object SavePortalLink : BankRecEvent
    data class CopyPortalLink(val link: PortalLink) : BankRecEvent
    data class AskRevokeLink(val link: PortalLink) : BankRecEvent
    data object DismissRevokeLink : BankRecEvent
    data object ConfirmRevokeLink : BankRecEvent

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
