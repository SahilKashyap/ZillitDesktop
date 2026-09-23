package com.zillit.desktop.feature.cardexpenses.domain

import com.zillit.desktop.core.common.ZillitResult

/**
 * Everything the card module asks the server for.
 *
 * Mirrors the web's `cardExpenses.js` export surface, grouped the way the
 * screens use it rather than the way the routes are laid out.
 */
@Suppress("TooManyFunctions") // One suspend fun per server operation; see detekt.yml.
interface CardRepository {

    suspend fun metadata(): ZillitResult<CardMetadata>

    suspend fun overview(): ZillitResult<CardOverview>

    suspend fun analytics(fromIso: String?, toIso: String?): ZillitResult<CardAnalytics>

    // -- cards -------------------------------------------------------------

    suspend fun cards(mineOnly: Boolean): ZillitResult<List<ExpenseCard>>

    suspend fun cardHistory(cardId: String): ZillitResult<List<CardHistoryEntry>>

    suspend fun cardReceipts(cardId: String): ZillitResult<List<CardReceipt>>

    suspend fun requestCard(request: NewCardRequest): ZillitResult<Unit>

    /**
     * An accountant's full edit of a card request.
     *
     * Resubmits the card: the server wipes its collected approvals and the
     * chain restarts. Only ever offered on a card that is still a request —
     * see [updateBsControlCode] for correcting a live one.
     */
    suspend fun updateCardDetails(cardId: String, edit: CardDetailsEdit): ZillitResult<Unit>

    /** Removes a card request that should never have been raised. */
    suspend fun deleteCard(cardId: String): ZillitResult<Unit>

    /**
     * Signs one step of a card request's chain.
     *
     * The step goes with it — `tier_number` of `total_tiers` — because the
     * server records which tier was signed, not merely that somebody signed.
     */
    suspend fun approveCard(cardId: String, step: TierVisibility, userId: String): ZillitResult<Unit>

    suspend fun rejectCard(cardId: String, reason: String, userId: String): ZillitResult<Unit>

    /** Approves a request over its chain; [reason] is on the record. */
    suspend fun overrideCard(cardId: String, userId: String, reason: String): ZillitResult<Unit>

    /** Brings an approved card into use with its type, its number and — if it has none — a provider. */
    suspend fun activateCard(cardId: String, activation: CardActivation): ZillitResult<Unit>

    suspend fun suspendCard(cardId: String): ZillitResult<Unit>

    suspend fun reactivateCard(cardId: String): ZillitResult<Unit>

    /**
     * Attaches a physical card to an already-active digital one.
     *
     * Two details fail silently if wrong, so they live in the implementation
     * with the reasoning attached — see `CardRepositoryImpl`.
     */
    suspend fun assignPhysicalCard(cardId: String, cardNumber: String): ZillitResult<Unit>

    /**
     * Corrects a card's balance-sheet control code, and nothing else.
     *
     * A separate operation from the full edit on purpose: the full form sends
     * a status that the server reads as a resubmit and answers by wiping the
     * card's collected approvals. Correct on a card awaiting approval,
     * destructive on one that is live.
     */
    suspend fun updateBsControlCode(cardId: String, code: String): ZillitResult<Unit>

    // -- statement imports -------------------------------------------------

    suspend fun imports(): ZillitResult<List<StatementImport>>

    /**
     * Ingests a statement already uploaded to storage, by its attachment key.
     *
     * [currency] states what the statement is denominated in; null leaves the
     * server to apply the project default.
     */
    suspend fun importStatement(attachmentKey: String, currency: String?): ZillitResult<Unit>

    suspend fun rerunMatching(statementId: String): ZillitResult<Unit>

    /** The rows of one uploaded statement, for review before they are sent out. */
    suspend fun importRows(importId: String): ZillitResult<List<StatementRow>>

    /**
     * Accepts the reviewed rows into the transaction ledger.
     *
     * Takes the row ids rather than the whole set: the server holds the rows
     * and re-sending them invites a client-side edit to overwrite what was
     * imported.
     */
    suspend fun processImport(importId: String, rowIds: List<String>): ZillitResult<Unit>

    /** Sends statement rows to the cardholders they belong to, for receipts. */
    suspend fun submitRowsToHolders(transactionIds: List<String>): ZillitResult<Unit>

    // -- transactions ------------------------------------------------------

    /** The statement lines, narrowed server-side by [filters]; none set reads them all. */
    suspend fun transactions(filters: TransactionFilters = TransactionFilters()): ZillitResult<List<CardTransaction>>

    suspend fun codeTransaction(
        transactionId: String,
        nominalCode: String,
        description: String?,
    ): ZillitResult<Unit>

    suspend fun submitTransaction(transactionId: String): ZillitResult<Unit>

    suspend fun postTransaction(transactionId: String): ZillitResult<Unit>

    suspend fun queryTransaction(transactionId: String, reason: String): ZillitResult<Unit>

    suspend fun rejectTransaction(transactionId: String, reason: String): ZillitResult<Unit>

    suspend fun flagTransactionPersonal(transactionId: String): ZillitResult<Unit>

    /** Removes a statement line; a matched receipt is unlinked, not deleted. */
    suspend fun deleteTransaction(transactionId: String): ZillitResult<Unit>

    /** The bulk sibling of [deleteTransaction]; unknown ids are skipped. */
    suspend fun bulkDeleteTransactions(transactionIds: List<String>): ZillitResult<BulkOutcome>

    // -- receipts ----------------------------------------------------------

    suspend fun receipts(scope: ReceiptScope): ZillitResult<List<CardReceipt>>

    suspend fun matchCandidates(receiptId: String): ZillitResult<List<CardTransaction>>

    suspend fun matchReceipt(receiptId: String, transactionId: String): ZillitResult<Unit>

    suspend fun unmatchReceipt(receiptId: String): ZillitResult<Unit>

    suspend fun confirmReceiptMatch(receiptId: String): ZillitResult<Unit>

    /** Uploads a batch of receipts against [card], whose id and currency they carry. */
    suspend fun submitReceipts(
        card: ExpenseCard?,
        receipts: List<DraftCardReceipt>,
    ): ZillitResult<Unit>

    /** Sends a receipt for approval, saving [coding] with it when there is any. */
    suspend fun submitReceiptForApproval(
        receiptId: String,
        coding: ReceiptCoding? = null,
    ): ZillitResult<Unit>

    /** Codes a receipt and clears the coordinator's own approval step with it. */
    suspend fun approveAndSubmitReceipt(
        receiptId: String,
        coding: ReceiptCoding,
    ): ZillitResult<Unit>

    /** Saves coding without advancing the receipt — the coding queue's draft save. */
    suspend fun updateReceiptCoding(receiptId: String, coding: ReceiptCoding): ZillitResult<Unit>

    /** Codes a receipt and advances it out of the coding queue. */
    suspend fun codeReceipt(receiptId: String, coding: ReceiptCoding): ZillitResult<Unit>

    suspend fun postReceipt(receiptId: String): ZillitResult<Unit>

    /** One receipt with everything the process editor needs: lines, flags, card figures. */
    suspend fun receiptDetail(receiptId: String): ZillitResult<CardReceipt>

    /**
     * The accountant's save, post, submit-for-review and escalate — one route,
     * `save-process`, told apart by [ProcessSubmission.status].
     *
     * The desktop used to post through `/post` with an empty body, which
     * carried no lines, no ledger date and no top-up decision.
     */
    suspend fun saveProcessReceipt(receiptId: String, submission: ProcessSubmission): ZillitResult<Unit>

    /** Hands a receipt to an accountant; [reassign] when it already had one. */
    suspend fun assignReceipt(receiptId: String, assignment: ReceiptAssignment, reassign: Boolean): ZillitResult<Unit>

    suspend fun flagReceiptPersonal(receiptId: String): ZillitResult<Unit>

    suspend fun dismissDuplicate(receiptId: String): ZillitResult<Unit>

    suspend fun dismissPersonal(receiptId: String): ZillitResult<Unit>

    suspend fun deleteReceipt(receiptId: String): ZillitResult<Unit>

    suspend fun receiptHistory(receiptId: String): ZillitResult<List<CardHistoryEntry>>

    // -- bulk processing ---------------------------------------------------

    /** Receipts ready to be coded and posted together. */
    suspend fun bulkProcessable(): ZillitResult<List<BulkItem>>

    /**
     * Codes and posts [receiptIds] in one call.
     *
     * [coding] overrides each row's own where a field is set and leaves it
     * alone where the field is null — see [BulkCoding].
     */
    suspend fun bulkProcess(receiptIds: List<String>, coding: BulkCoding): ZillitResult<BulkOutcome>

    // -- approvals ---------------------------------------------------------

    suspend fun approvalQueue(): ZillitResult<List<CardReceipt>>

    suspend fun approveReceipt(receiptId: String, note: String?): ZillitResult<Unit>

    suspend fun rejectReceipt(receiptId: String, reason: String): ZillitResult<Unit>

    suspend fun overrideReceipt(receiptId: String, userId: String, reason: String): ZillitResult<Unit>

    /** Approves or rejects several receipts in one call. */
    suspend fun bulkApproval(action: BulkAction, receiptIds: List<String>): ZillitResult<Unit>

    // -- top-ups -----------------------------------------------------------

    suspend fun topUps(): ZillitResult<List<CardTopUp>>

    suspend fun cardTopUps(cardId: String): ZillitResult<List<CardTopUp>>

    /** One top-up's audit trail, for the funding queue's row expansion. */
    suspend fun topUpHistory(topUpId: String): ZillitResult<List<CardHistoryEntry>>

    suspend fun requestTopUp(cardId: String, amount: Double, reason: String?): ZillitResult<Unit>

    suspend fun completeTopUp(topUpId: String): ZillitResult<Unit>

    /**
     * Records a part-payment. The [note] is required and sent — it is what the
     * trail shows for why a row is half funded — and [amount] is optional, as
     * on the web (`TopUpToDoPage.jsx:208-225`).
     */
    suspend fun partialTopUp(topUpId: String, amount: Double?, note: String): ZillitResult<Unit>

    suspend fun skipTopUp(topUpId: String): ZillitResult<Unit>

    // -- alerts and settings -----------------------------------------------

    // -- queries and fund requests -----------------------------------------

    /** The thread on one entity; an empty thread with no id when nobody has asked yet. */
    suspend fun queryThread(entityType: String, entityId: String): ZillitResult<QueryThread>

    /** Adds [text] to [thread], or opens the thread with it when it has no id yet. */
    suspend fun sendQuery(
        thread: QueryThread,
        entityType: String,
        entityId: String,
        text: String,
    ): ZillitResult<QueryThread>

    suspend fun fundRequests(): ZillitResult<List<FundRequest>>

    suspend fun createFundRequest(draft: FundRequestDraft): ZillitResult<Unit>

    suspend fun receiveFundRequest(id: String): ZillitResult<Unit>

    suspend fun cancelFundRequest(id: String): ZillitResult<Unit>

    // -- exports -----------------------------------------------------------

    /** The register as a file; the server prints the [rows] it is handed, names already resolved. */
    suspend fun exportCards(format: ExportFormat, rows: List<CardExportRow>): ZillitResult<ByteArray>

    suspend fun exportTransactions(format: ExportFormat): ZillitResult<ByteArray>

    suspend fun alerts(): ZillitResult<List<CardAlert>>

    suspend fun resolveAlert(alertId: String, note: String?): ZillitResult<Unit>

    suspend fun dismissAlert(alertId: String): ZillitResult<Unit>

    suspend fun investigateAlert(alertId: String): ZillitResult<Unit>

    suspend fun settings(): ZillitResult<CardSettings>

    /**
     * Saves one section of the settings document and returns the whole of it.
     *
     * Per section because a PATCH merges: see the implementation for why
     * sending the whole document is the wrong shape here.
     */
    suspend fun updateSettings(
        section: SettingsSection,
        settings: CardSettings,
    ): ZillitResult<CardSettings>
}

/** Which slice of receipts a screen wants. */
enum class ReceiptScope(val path: String) {
    /** Everything on the production — the accountant's inbox. */
    All("/receipts"),

    /** This viewer's own. */
    Mine("/receipts/my"),

    /** Waiting on a coordinator to code. */
    PendingCoding("/receipts/pending-coding"),

    /** Posted, for the history screen. */
    Posted("/receipts/posted-history"),

    /** Assigned to this accountant to process. */
    ProcessQueue("/receipts/process-queue"),
}

enum class BulkAction(val wire: String) { Approve("approve"), Reject("reject"), Override("override") }
