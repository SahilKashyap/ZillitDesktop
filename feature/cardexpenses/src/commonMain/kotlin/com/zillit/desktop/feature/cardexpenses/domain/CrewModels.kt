package com.zillit.desktop.feature.cardexpenses.domain

import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str

/**
 * The cardholder's side of the tool — My Transactions, Card Extension, the
 * crew Approval Queue and the Coding Queue — as the web draws them
 * (`UserReceiptsPage.jsx`, `CardExtensionPage.jsx`, `CardsForApprovalPage.jsx`,
 * `CodingQueuePage.jsx`).
 *
 * The rules here are pure so they can be tested without a screen: which card
 * counts, what order a batch is refused in, when an edit may go.
 */

/**
 * What the crew pages need from the application rather than the card service.
 *
 * A host seam, as [CardAttachmentUploader] is: the production's companies and
 * its chart of accounts belong to the Account Hub's Production Setup, and
 * whether the production is a television one to the project profile. Each
 * defaults to "nothing known", which degrades to a text-only code field, a
 * batch with no company and no Episode field — never to a crash.
 */
class CardCrewHost(
    /** `project-settings/companies` — for the batch's `company_id` fallback. */
    val companies: suspend () -> List<CardCompanyRef> = { emptyList() },
    /** The chart's postable codes, for the Cost Code pickers (`CoaCodeInput`). */
    val nominals: suspend () -> List<CardNominal> = { emptyList() },
    /** `useIsTelevisionProject` — Episode shows on television productions only. */
    val isTelevision: () -> Boolean = { false },
    /** Now, in epoch millis — the "date cannot be in the future" check. */
    val now: () -> Long = { kotlin.time.Clock.System.now().toEpochMilliseconds() },
)

/** A production company, as far as the card-to-company resolver needs it. */
data class CardCompanyRef(val id: String, val bankIds: List<String> = emptyList())

/** One code in the chart of accounts. */
data class CardNominal(val code: String, val name: String = "")

/**
 * The web's `resolveCardCompany` (`data/card-company.js`).
 *
 * The card's pinned company wins; failing that, the company whose banks
 * include the card's issuer. Null when neither answers — the server stores
 * NULL and an accountant can set it while coding.
 */
object CardCompanies {
    fun resolve(card: ExpenseCard?, companies: List<CardCompanyRef>): String? {
        card?.companyId?.takeIf { it.isNotBlank() }?.let { return it }
        val bank = card?.issuer?.takeIf { it.isNotBlank() } ?: return null
        return companies.firstOrNull { bank in it.bankIds }?.id
    }
}

/**
 * The Edit Receipt / Edit & Resubmit / Upload Receipt dialog's save.
 *
 * The PATCH body `UserReceiptsPage.jsx:1451-1475` builds. [attachment] is a
 * three-way choice because the wire has three: a new object, an explicit
 * null for a removed file, and the key left out when the file is unchanged.
 */
data class ReceiptEdit(
    val description: String,
    val amount: Double?,
    val date: Long?,
    val cardId: String?,
    val currency: String?,
    val nominalCode: String,
    val episode: String,
    val codeDescription: String,
    val category: ReceiptCategory,
    val urgent: Boolean,
    val requestTopUp: Boolean,
    val attachment: AttachmentChange,
)

/** What an edit does to the receipt's file. */
sealed interface AttachmentChange {
    data object Keep : AttachmentChange

    data object Remove : AttachmentChange

    data class Replace(val file: CardAttachment) : AttachmentChange
}

/**
 * One step of a top-up's embedded audit trail.
 *
 * The row carries `history` as a JSON-stringified array (`cardExpenses.js`
 * top-ups contract); the web's drawer shows the reason and the amount on the
 * note line (`TopUpExtensionPanel.jsx:344-356`).
 */
data class TopUpTrailStep(
    val action: String,
    val userId: String?,
    val at: Long?,
    val reason: String?,
    val amount: Double?,
)

/** The crew-side rules, each with the web line it comes from. */
object CrewRules {

    /** The one card that counts for uploads: the first `active` one (`UserReceiptsPage.jsx:177`). */
    fun activeCard(cards: List<ExpenseCard>): ExpenseCard? = cards.firstOrNull { it.status == CardStatus.Active }

    /** Top-ups go to active cards only — nothing else has a balance to credit (`CardExtensionPage.jsx:60-70`). */
    fun toppableCards(cards: List<ExpenseCard>): List<ExpenseCard> = cards.filter { it.status == CardStatus.Active }

    /** A receipt may be deleted until it is approved or posted (`UserReceiptsPage.jsx:1020`). */
    fun canDelete(receipt: CardReceipt): Boolean =
        receipt.status != CardWorkflowStatus.Approved && receipt.status != CardWorkflowStatus.Posted

    /**
     * Whether the card shows "Receipt attached".
     *
     * Past pending-receipt and rejected, a receipt is taken to have its
     * document even when the row does not carry it (`UserReceiptsPage.jsx:921`).
     */
    fun hasReceipt(receipt: CardReceipt): Boolean =
        !receipt.attachmentKey.isNullOrBlank() ||
            (receipt.status != CardWorkflowStatus.PendingReceipt && receipt.status != CardWorkflowStatus.Rejected)

    /** The card's Upload Receipt button (`UserReceiptsPage.jsx:1009`). */
    fun offersUpload(receipt: CardReceipt): Boolean =
        !hasReceipt(receipt) && receipt.status == CardWorkflowStatus.PendingReceipt

    /** Edit Receipt in the detail view (`ReceiptDetailModal.jsx:421`). */
    fun canEdit(receipt: CardReceipt): Boolean = receipt.status in EDITABLE

    /**
     * An accountant-initiated row: attachment, description and date become
     * required on the edit (`UserReceiptsPage.jsx:491-492`).
     */
    fun accountantSent(receipt: CardReceipt): Boolean =
        !receipt.transactionId.isNullOrBlank() || receipt.status == CardWorkflowStatus.PendingReceipt

    /** UTC midnight today, as the web's `new Date(new Date().toISOString().split("T")[0])`. */
    fun todayUtc(now: Long): Long = now.floorDiv(DAY) * DAY

    /**
     * Why the upload batch may not go, or null — the web's order exactly
     * (`UserReceiptsPage.jsx:309-335`): attachment, date, description,
     * amount, then a date in the future.
     */
    fun batchError(rows: List<DraftCardReceipt>, now: Long): String? {
        val today = todayUtc(now)
        rows.forEachIndexed { index, row ->
            val number = index + 1
            val error = when {
                row.attachment == null && row.attachmentKey.isNullOrBlank() ->
                    str(S.desktop_ce_crew_err_attachment, number)
                row.date == null -> str(S.desktop_ce_crew_err_date, number)
                row.description.isBlank() -> str(S.desktop_ce_crew_err_description, number)
                row.amountValue <= 0 -> str(S.desktop_ce_crew_err_amount, number)
                row.date > today -> str(S.desktop_ce_crew_err_future, number)
                else -> null
            }
            if (error != null) return error
        }
        return null
    }

    /**
     * Why an edit may not be saved, or null (`UserReceiptsPage.jsx:1416-1440`).
     *
     * The headroom test sits between the amount and the date, as the web
     * orders them; the caller passes the refusal to show when the increase
     * does not fit (it names the available figure), or null when it does.
     */
    @Suppress("LongParameterList") // The dialog's whole input; a holder would only rename it.
    fun editError(
        accountantSent: Boolean,
        hasAttachment: Boolean,
        description: String,
        date: Long?,
        amount: String,
        overLimit: String?,
        now: Long,
    ): String? {
        val value = amount.trim().toDoubleOrNull()
        return when {
            accountantSent && !hasAttachment -> str(S.desktop_ce_crew_err_edit_attachment)
            accountantSent && description.isBlank() -> str(S.ah_err_description_required)
            accountantSent && date == null -> str(S.error_date_required)
            value == null || value <= 0 -> str(S.desktop_ce_crew_err_edit_amount)
            overLimit != null -> overLimit
            date != null && date > todayUtc(now) -> str(S.desktop_ce_crew_err_edit_future)
            else -> null
        }
    }

    /** The status chips over My Transactions (`UserReceiptsPage.jsx:446-456`); null is All. */
    val FILTERS: List<CardWorkflowStatus?> = listOf(
        null,
        CardWorkflowStatus.PendingReceipt,
        CardWorkflowStatus.PendingCode,
        CardWorkflowStatus.AwaitingApproval,
        CardWorkflowStatus.Approved,
        CardWorkflowStatus.Queried,
        CardWorkflowStatus.UnderReview,
        CardWorkflowStatus.Escalated,
        CardWorkflowStatus.Posted,
    )

    private val EDITABLE = setOf(
        CardWorkflowStatus.PendingReceipt,
        CardWorkflowStatus.PendingCode,
        CardWorkflowStatus.AwaitingApproval,
    )

    private const val DAY = 86_400_000L
}
