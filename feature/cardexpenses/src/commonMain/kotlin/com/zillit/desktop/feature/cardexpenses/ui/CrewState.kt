package com.zillit.desktop.feature.cardexpenses.ui

import com.zillit.desktop.feature.cardexpenses.domain.CardAttachment
import com.zillit.desktop.feature.cardexpenses.domain.CardCompanyRef
import com.zillit.desktop.feature.cardexpenses.domain.CardHistoryEntry
import com.zillit.desktop.feature.cardexpenses.domain.CardNominal
import com.zillit.desktop.feature.cardexpenses.domain.CardReceipt
import com.zillit.desktop.feature.cardexpenses.domain.CardWorkflowStatus
import com.zillit.desktop.feature.cardexpenses.domain.CardDates
import com.zillit.desktop.feature.cardexpenses.domain.DraftCardReceipt
import com.zillit.desktop.feature.cardexpenses.domain.ExpenseCard
import com.zillit.desktop.feature.cardexpenses.domain.ReceiptCategory

/**
 * The cardholder pages' own state: My Transactions and its upload page, Card
 * Extension, the crew Approval Queue and the Coding Queue.
 *
 * One field on [CardUiState] rather than a dozen, so these pages can grow
 * without the shared state growing with them.
 */
data class CrewState(
    /** The full-screen Upload Receipts page is open over My Transactions. */
    val uploadOpen: Boolean = false,
    /** My Transactions' status chip; null is All. */
    val filter: CardWorkflowStatus? = null,
    /** The receipt open in the read-only detail view. */
    val detail: CrewReceiptView? = null,
    /** The Edit Receipt / Edit & Resubmit dialog. */
    val edit: ReceiptEditDraft? = null,
    /** A receipt waiting on "Delete Receipt?". */
    val deleteId: String? = null,

    /** The crew Approval Queue's two sections and what each holds. */
    val approvalTab: CrewApprovalTab = CrewApprovalTab.Cards,
    val approvalCards: List<ExpenseCard> = emptyList(),
    val approvalReceipts: List<CardReceipt> = emptyList(),
    /** The card request open full screen from the queue. */
    val approvalCardId: String? = null,
    /** The reject-with-reason dialog, over a card or a receipt. */
    val reject: CrewReject? = null,
    /** The row whose approve or reject is in flight — its buttons read "…". */
    val actionId: String? = null,

    /** The Coding Queue's Code Receipt dialog. */
    val code: CodeReceiptDraft? = null,

    /** Card Extension: the card chosen, its top-ups' read state, the open trail and the request form. */
    val topUpCardId: String? = null,
    val topUpsFailed: Boolean = false,
    val topUpTrailId: String? = null,
    val topUpRequest: TopUpRequestDraft? = null,

    /** Supplied by the host once per production; see `CardCrewHost`. */
    val companies: List<CardCompanyRef> = emptyList(),
    val nominals: List<CardNominal> = emptyList(),
    val isTelevision: Boolean = false,
) {
    /**
     * Whether a crew page has taken over the whole content area — the web's
     * `onFullScreenChange` (Upload Receipts, and a card request opened from
     * the Approval Queue). The cardholder header and tabs should hide while
     * it is true.
     */
    val fullScreen: Boolean get() = uploadOpen || approvalCardId != null

    /** Everything transient, dropped when the page changes. The host data stays. */
    fun onPageChange(): CrewState =
        CrewState(companies = companies, nominals = nominals, isTelevision = isTelevision, topUpCardId = topUpCardId)
}

/** The crew Approval Queue's two sections (`CardsForApprovalPage.jsx:246-274`). */
enum class CrewApprovalTab { Cards, Receipts }

/** A receipt opened read-only; [loading] while `/receipts/:id/detail` is out. */
data class CrewReceiptView(
    val receipt: CardReceipt,
    val loading: Boolean = true,
    /** Where it was opened from — My Transactions offers Edit, the Approval Queue Approve / Reject. */
    val origin: CrewOrigin = CrewOrigin.MyTransactions,
    val historyOpen: Boolean = false,
    val history: List<CardHistoryEntry>? = null,
)

enum class CrewOrigin { MyTransactions, ApprovalQueue }

/** A reject waiting on its reason; [receipt] tells the two dialogs' wording apart. */
data class CrewReject(
    val targetId: String,
    val receipt: Boolean,
    /** Who or what is being rejected — the dialog's bold name. */
    val subject: String,
    val reason: String = "",
)

/**
 * The Edit Receipt dialog, seeded from the row as `openEditModal` does
 * (`UserReceiptsPage.jsx:219-243`).
 */
data class ReceiptEditDraft(
    val receipt: CardReceipt,
    val merchant: String,
    val amount: String,
    /** `YYYY-MM-DD`, blank for none. */
    val date: String,
    val costCode: String,
    val episode: String,
    val codeDescription: String,
    val category: ReceiptCategory,
    val urgent: Boolean,
    val topUp: Boolean,
    /** A file picked in the dialog, replacing whatever was there. */
    val newFile: CardAttachment? = null,
    /** Whether the receipt's existing file is still kept. */
    val keepExisting: Boolean,
    val saving: Boolean = false,
) {
    val rejected: Boolean get() = receipt.status == CardWorkflowStatus.Rejected
    val hasAttachment: Boolean get() = newFile != null || keepExisting

    companion object {
        fun of(receipt: CardReceipt) = ReceiptEditDraft(
            receipt = receipt,
            merchant = receipt.description,
            amount = receipt.amount.takeIf { it != 0.0 }?.let(::plainAmount).orEmpty(),
            date = CardDates.toIso(receipt.date),
            costCode = receipt.nominalCode.orEmpty(),
            episode = receipt.episode.orEmpty(),
            codeDescription = receipt.codeDescription.orEmpty(),
            category = ReceiptCategory.from(receipt.category),
            urgent = receipt.urgent,
            topUp = receipt.processing.requestTopUp,
            keepExisting = !receipt.attachmentKey.isNullOrBlank(),
        )
    }
}

/** The Coding Queue's dialog (`CodingQueuePage.jsx:60-65`). */
data class CodeReceiptDraft(
    val receipt: CardReceipt,
    val costCode: String,
    val episode: String,
    val description: String,
    val saving: Boolean = false,
) {
    companion object {
        fun of(receipt: CardReceipt) = CodeReceiptDraft(
            receipt = receipt,
            costCode = receipt.nominalCode.orEmpty(),
            episode = receipt.episode.orEmpty(),
            description = receipt.codeDescription.orEmpty(),
        )
    }
}

/** Card Extension's Request Top-up dialog. Amount and reason are both required (ZL-20808). */
data class TopUpRequestDraft(
    val cardId: String,
    val amount: String = "",
    val reason: String = "",
    val sending: Boolean = false,
) {
    val amountValue: Double? get() = amount.trim().toDoubleOrNull()?.takeIf { it > 0 }
    val canSend: Boolean get() = amountValue != null && reason.isNotBlank() && !sending
}

