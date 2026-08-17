package com.zillit.desktop.feature.cashexpenses.ui

import com.zillit.desktop.feature.cashexpenses.domain.DraftReceipt
import com.zillit.desktop.feature.cashexpenses.domain.EditorLine
import com.zillit.desktop.feature.cashexpenses.domain.ExpenseType

/** Everything the user can do in the cash tool. */
sealed interface CashEvent {

    data object Refresh : CashEvent

    data class Open(val destination: CashDestination) : CashEvent

    data class SwitchPipeline(val pipeline: ExpenseType) : CashEvent

    data class Search(val query: String) : CashEvent

    data class SelectBatch(val batchId: String?) : CashEvent

    data class SelectFloat(val floatId: String?) : CashEvent

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

    data class EditSettings(val settings: com.zillit.desktop.feature.cashexpenses.domain.CashSettings) : CashEvent

    data object SaveSettings : CashEvent
}

/** One-shot things the screen must do that state cannot express. */
sealed interface CashEffect {

    /** Something failed in a way worth interrupting for. */
    data class Failed(val message: String) : CashEffect

    /** An attachment the user asked to see, as a storage key. */
    data class OpenAttachment(val key: String) : CashEffect
}
