package com.zillit.desktop.feature.cardexpenses.ui

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.feature.cardexpenses.domain.BulkCoding
import com.zillit.desktop.feature.cardexpenses.domain.BulkItem
import com.zillit.desktop.feature.cardexpenses.domain.CardAlert
import com.zillit.desktop.feature.cardexpenses.domain.CardAnalytics
import com.zillit.desktop.feature.cardexpenses.domain.CardHistoryEntry
import com.zillit.desktop.feature.cardexpenses.domain.CardOverview
import com.zillit.desktop.feature.cardexpenses.domain.CardPerson
import com.zillit.desktop.feature.cardexpenses.domain.CardProvider
import com.zillit.desktop.feature.cardexpenses.domain.CardReceipt
import com.zillit.desktop.feature.cardexpenses.domain.CardRules
import com.zillit.desktop.feature.cardexpenses.domain.CardSettings
import com.zillit.desktop.feature.cardexpenses.domain.CardTopUp
import com.zillit.desktop.feature.cardexpenses.domain.CardTransaction
import com.zillit.desktop.feature.cardexpenses.domain.CardViewer
import com.zillit.desktop.feature.cardexpenses.domain.DraftCardReceipt
import com.zillit.desktop.feature.cardexpenses.domain.ExpenseCard
import com.zillit.desktop.feature.cardexpenses.domain.ReceiptLine
import com.zillit.desktop.feature.cardexpenses.domain.StatementImport
import com.zillit.desktop.feature.cardexpenses.domain.StatementRow
import com.zillit.desktop.feature.cardexpenses.domain.UploadHeadroom

/** Everything the card tool is showing. */
@Suppress("LongParameterList") // One screen's whole state; see the module KDoc.
data class CardUiState(
    val viewer: CardViewer,
    val destination: CardDestination,
    val loading: Boolean = false,
    val error: ZillitError? = null,
    val busy: Boolean = false,
    /** A file is being read and stored. Separate from [busy]: it blocks less. */
    val uploading: Boolean = false,
    /**
     * Whether this build can open a file picker at all.
     *
     * In state rather than read off the view model, because the screens that
     * offer an attach button have to say *why* it is disabled — and a build
     * with no picker cannot upload receipts or import statements, which is a
     * fact about the application rather than about the person using it.
     */
    val canAttachFiles: Boolean = false,
    val notice: String? = null,

    val overview: CardOverview? = null,
    val analytics: CardAnalytics? = null,
    val analyticsRange: AnalyticsRange = AnalyticsRange(),
    val cards: List<ExpenseCard> = emptyList(),
    val transactions: List<CardTransaction> = emptyList(),
    val receipts: List<CardReceipt> = emptyList(),
    val matchCandidates: List<CardTransaction> = emptyList(),
    val topUps: List<CardTopUp> = emptyList(),
    val alerts: List<CardAlert> = emptyList(),
    val imports: List<StatementImport> = emptyList(),
    val settings: CardSettings? = null,
    val settingsDraft: CardSettings? = null,
    val bulkItems: List<BulkItem> = emptyList(),
    val bulkCoding: BulkCoding = BulkCoding(),
    /** The crew, for the holder picker. Supplied by the host; see the ViewModel. */
    val people: List<CardPerson> = emptyList(),

    /** The statement whose rows are open for review, and those rows. */
    val openImportId: String? = null,
    val importRows: List<StatementRow> = emptyList(),
    /** What the statement being imported is denominated in; blank = project default. */
    val statementCurrency: String = "",

    /** The receipt whose splits are open, if any. */
    val splits: SplitDraft? = null,
    /** The audit trail of the receipt in the detail pane. */
    val receiptHistory: List<CardHistoryEntry> = emptyList(),
    /** The coding editor over the selected receipt, on the coding queues. */
    val coding: CodingDraft? = null,
    /** The top-up whose trail is open in the funding queue, and that trail. */
    val openTopUpId: String? = null,
    val topUpHistory: List<CardHistoryEntry> = emptyList(),

    /** What the open card drilldown has loaded. */
    val cardDetail: CardDetail? = null,
    /** The new-card form, open. */
    val newCard: NewCardDraft? = null,
    /** The full card-details edit, open over one card. */
    val cardEdit: CardEditDraft? = null,

    val search: String = "",
    val statusFilter: String = ALL_STATUSES,
    val selectedReceiptId: String? = null,
    val selectedCardId: String? = null,
    val selectedTransactionId: String? = null,
    /** Rows ticked for a bulk approve, reject or delete. */
    val selection: Set<String> = emptySet(),
    val draft: List<DraftCardReceipt> = listOf(DraftCardReceipt()),
    val prompt: CardPrompt? = null,
) {
    /** This viewer's own card, for the cardholder screens. */
    val myCard: ExpenseCard?
        get() = cards.firstOrNull { it.holderId == viewer.userId } ?: cards.firstOrNull()

    val headroom: UploadHeadroom get() = UploadHeadroom.of(myCard)

    val draftTotal: Double get() = draft.sumOf { it.amountValue }

    val selectedReceipt: CardReceipt? get() = receipts.firstOrNull { it.id == selectedReceiptId }

    val selectedCard: ExpenseCard? get() = cards.firstOrNull { it.id == selectedCardId }

    val selectedTransaction: CardTransaction?
        get() = transactions.firstOrNull { it.id == selectedTransactionId }

    /** Bulk rows this viewer is allowed to tick. */
    val selectableBulkItems: List<BulkItem>
        get() = bulkItems.filter { it.selectableBy(viewer.userId) }

    /** What the ticked bulk rows come to. */
    val bulkSelectedTotal: Double
        get() = bulkItems.filter { it.id in selection }.sumOf { it.amount }

    /** The destinations this viewer may open, in sidebar order. */
    val destinations: List<CardDestination>
        get() = CardDestination.entries.filter { it.visibleTo(viewer) }

    /**
     * What this production's money is in.
     *
     * Read off the cards rather than configured: the card service states a
     * currency per card and nowhere else, and every card on a production is
     * held in the same one. Analytics and the settings limits carry no
     * currency of their own on the wire, and a bare `12,480.55` on a screen
     * about money is a figure nobody can act on.
     */
    val currency: String?
        get() = cards.firstNotNullOfOrNull { it.currency?.takeIf(String::isNotBlank) }
            ?: overview?.cards?.firstNotNullOfOrNull { it.currency?.takeIf(String::isNotBlank) }

    /**
     * Who holds this card, by name.
     *
     * Resolved against the crew directory first, because `/overview` projects
     * its card rows to a thinner field set that carries **no holder name at
     * all** — so the dashboard printed a raw `6a2becfdf0a26d2…` in the Holder
     * column. The web has the same rule and the same reason.
     *
     * A holder nobody can name reads as an em dash, never as an id: an
     * ObjectId on screen is meaningless to everyone who sees it and looks like
     * corruption. See the module's note on the same trap elsewhere in finance.
     */
    fun holderName(card: ExpenseCard): String =
        people.firstOrNull { it.id == card.holderId }?.pickerLabel
            ?: card.holderName.takeIf { it.isNotBlank() }
            ?: EM_DASH

    /**
     * The holder's name without their role.
     *
     * For a table column, where "Purple Mob · writer_label" truncates to
     * "Purple Mob · write…" and the half of it that identifies the person is
     * the half that gets cut. The role is in the pane beside it.
     */
    fun holderShortName(card: ExpenseCard): String = personName(card.holderId, card.holderName)

    /**
     * The issuer's name for this card.
     *
     * `card_issuer` holds a provider id on some productions, so it is looked
     * up in the configured providers first — a bare id is not a name.
     */
    fun issuerName(card: ExpenseCard): String? =
        providers.firstOrNull { it.id == card.providerId || it.id == card.issuer }?.name
            ?: card.issuer?.takeIf { it.readsAsAName() }

    /** The same rule for anything else that names a person by id. */
    fun personName(userId: String?, fallback: String = ""): String =
        people.firstOrNull { it.id == userId }?.name
            ?: fallback.takeIf { it.isNotBlank() }
            ?: EM_DASH

    /** The issuers configured for the production, for every card form. */
    val providers: List<CardProvider>
        get() = settings?.providers?.takeIf { it.isNotEmpty() } ?: viewer.metadata.cardProviders

    /**
     * Who may still be issued a card.
     *
     * The one-card-per-user rule, applied to the picker rather than to the
     * submit: offering someone who cannot hold a card and refusing afterwards
     * is how people learn to ignore the rule instead of asking for the old
     * card to be closed.
     */
    val eligibleHolders: List<CardPerson>
        get() = people.filter { CardRules.canRequestCard(cards, it.id) }
}

/** The window Analytics is reporting on. Blank ends mean "all time". */
data class AnalyticsRange(val from: String = "", val to: String = "") {
    val fromOrNull: String? get() = from.trim().takeIf { it.isNotEmpty() }
    val toOrNull: String? get() = to.trim().takeIf { it.isNotEmpty() }
    val isAllTime: Boolean get() = fromOrNull == null && toOrNull == null
}

/**
 * One card, opened.
 *
 * Three lists rather than three visits: the question an accountant opens a
 * card to answer — "what has been spent on this and where did the money come
 * from" — needs the receipts and the top-ups next to each other, and fetching
 * them on separate clicks made a just-raised top-up appear to have vanished.
 */
data class CardDetail(
    val cardId: String,
    val loading: Boolean = false,
    val receipts: List<CardReceipt> = emptyList(),
    val topUps: List<CardTopUp> = emptyList(),
    val history: List<CardHistoryEntry> = emptyList(),
    /** The control-code field, editable in place on a live card. */
    val bsControlCode: String = "",
) {
    val spend: Double get() = receipts.sumOf { it.amount }
    val funded: Double get() = topUps.filter { it.status == COMPLETED }.sumOf { it.amount }

    private companion object {
        const val COMPLETED = "completed"
    }
}

/**
 * The new-card form.
 *
 * One form for both surfaces: a cardholder asking for their own, and an
 * accountant issuing one to somebody else. They differ only in whether the
 * holder can be changed, which the screen decides — keeping two forms is what
 * let the web's copies drift apart over the one-card rule.
 */
data class NewCardDraft(
    val holderId: String = "",
    val proposedLimit: String = "",
    val currency: String = "",
    val providerId: String = "",
    val issuer: String = "",
    val companyId: String = "",
    val bsControlCode: String = "",
    val justification: String = "",
) {
    val limitValue: Double get() = proposedLimit.trim().toDoubleOrNull() ?: 0.0

    /**
     * Why this cannot be submitted yet, or null.
     *
     * Ordered the way the form reads, so the message names the first thing
     * missing rather than an arbitrary one. The provider requirement is soft:
     * with none configured the field cannot be satisfied, and blocking on it
     * would make the form unusable until somebody visits Settings.
     */
    fun validationError(providersConfigured: Boolean, holderRequired: Boolean): String? = when {
        holderRequired && holderId.isBlank() -> "Choose who the card is for."
        limitValue <= 0 -> "The proposed limit has to be more than zero."
        currency.isBlank() -> "Choose the currency the card is held in."
        providersConfigured && providerId.isBlank() -> "Choose a card provider."
        bsControlCode.isBlank() -> "A balance-sheet control code is required."
        justification.isBlank() -> "Say what the card is for."
        else -> null
    }
}

/**
 * An accountant's edit of a card already on file.
 *
 * Seeded from the card and kept beside it so the balance arithmetic can be
 * shown before it is committed: raising a limit adds the difference to the
 * remaining balance, and lowering one takes it away — never below zero.
 */
data class CardEditDraft(
    val cardId: String,
    val currentLimit: Double,
    val currentBalance: Double,
    val limit: String,
    val currency: String,
    val providerId: String,
    val issuer: String,
    val companyId: String,
    val bsControlCode: String,
    val justification: String,
) {
    val limitValue: Double get() = limit.trim().toDoubleOrNull() ?: 0.0

    /** The balance the server will be told to hold, after the limit change. */
    val newBalance: Double get() = (currentBalance + (limitValue - currentLimit)).coerceAtLeast(0.0)

    fun validationError(providersConfigured: Boolean): String? = when {
        limitValue <= 0 -> "The proposed limit has to be more than zero."
        currency.isBlank() -> "Choose the currency the card is held in."
        providersConfigured && providerId.isBlank() -> "Choose a card provider."
        bsControlCode.isBlank() -> "A balance-sheet control code is required."
        justification.isBlank() -> "Say what the card is for."
        else -> null
    }

    companion object {
        fun of(card: ExpenseCard): CardEditDraft = CardEditDraft(
            cardId = card.id,
            currentLimit = card.limit,
            currentBalance = card.balance ?: 0.0,
            limit = (card.proposedLimit ?: card.limit).takeIf { it > 0 }?.toString().orEmpty(),
            currency = card.currency.orEmpty(),
            providerId = card.providerId.orEmpty(),
            issuer = card.issuer.orEmpty(),
            companyId = card.companyId.orEmpty(),
            bsControlCode = card.bsControlCode.orEmpty(),
            justification = card.justification.orEmpty(),
        )
    }
}

/**
 * A receipt's coding, open for editing in the coding queue.
 *
 * Held in state rather than in the composable because three different buttons
 * commit it — save, submit, and approve-and-submit — and a local `remember`
 * would leave each of them reading a field the others had not seen.
 */
data class CodingDraft(
    val receiptId: String,
    val nominalCode: String = "",
    val episode: String = "",
    val codeDescription: String = "",
) {
    val coded: Boolean get() = nominalCode.isNotBlank()

    companion object {
        fun of(receipt: CardReceipt): CodingDraft = CodingDraft(
            receiptId = receipt.id,
            nominalCode = receipt.nominalCode.orEmpty(),
            episode = receipt.episode.orEmpty(),
            codeDescription = receipt.codeDescription.orEmpty(),
        )
    }
}

/**
 * A receipt's splits, open for editing.
 *
 * Card splits are simpler than the cash module's: no parent/child tree, just
 * a flat set of coded portions that has to add up to the receipt. The
 * constraint is the same, and so is the reason for showing it live.
 */
data class SplitDraft(
    val receiptId: String,
    val receiptGross: Double,
    val currency: String?,
    val lines: List<ReceiptLine>,
) {
    val total: Double get() = lines.sumOf { it.gross }

    val remaining: Double get() = receiptGross - total

    val balances: Boolean get() = kotlin.math.abs(remaining) < PENNY

    private companion object {
        const val PENNY = 0.005
    }
}

/** A question asked before something irreversible. */
sealed interface CardPrompt {
    data class Confirm(
        val action: CardConfirmAction,
        val targetId: String,
        val title: String,
        val message: String,
    ) : CardPrompt

    data class WithReason(
        val action: CardReasonAction,
        val targetId: String,
        val title: String,
        val label: String,
        val reason: String = "",
    ) : CardPrompt

    data class WithAmount(
        val action: CardAmountAction,
        val targetId: String,
        val title: String,
        val label: String,
        val amount: String = "",
        val note: String = "",
    ) : CardPrompt

    /** Attaching a physical card number to a live digital card. */
    data class WithCardNumber(
        val targetId: String,
        val title: String,
        val number: String = "",
    ) : CardPrompt
}

enum class CardConfirmAction {
    ApproveCard,
    OverrideCard,
    ActivateCard,
    SuspendCard,
    ReactivateCard,
    DeleteCard,
    ApproveReceipt,
    OverrideReceipt,
    PostReceipt,
    SubmitReceiptForApproval,
    ConfirmMatch,
    UnmatchReceipt,
    FlagPersonal,
    DismissDuplicate,
    DismissPersonal,
    DeleteReceipt,
    CompleteTopUp,
    SkipTopUp,
    DismissAlert,
    InvestigateAlert,
    BulkApprove,
    BulkReject,
    PostTransaction,
    FlagTransactionPersonal,
    DeleteTransaction,
    BulkDeleteTransactions,
    RerunMatching,
}

enum class CardReasonAction { RejectCard, RejectReceipt, QueryTransaction, RejectTransaction, ResolveAlert }

enum class CardAmountAction { RequestTopUp, PartialTopUp }

/** The status filter's "everything" option, on the register and the ledger. */
const val ALL_STATUSES = "all"

/** What a name nobody can resolve reads as. Never the id it was looked up by. */
private const val EM_DASH = "—"
