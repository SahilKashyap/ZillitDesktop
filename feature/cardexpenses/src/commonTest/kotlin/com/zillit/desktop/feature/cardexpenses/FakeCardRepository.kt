package com.zillit.desktop.feature.cardexpenses

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.cardexpenses.domain.BulkAction
import com.zillit.desktop.feature.cardexpenses.domain.BulkCoding
import com.zillit.desktop.feature.cardexpenses.domain.BulkItem
import com.zillit.desktop.feature.cardexpenses.domain.BulkOutcome
import com.zillit.desktop.feature.cardexpenses.domain.CardActivation
import com.zillit.desktop.feature.cardexpenses.domain.CardAlert
import com.zillit.desktop.feature.cardexpenses.domain.CardAnalytics
import com.zillit.desktop.feature.cardexpenses.domain.CardDetailsEdit
import com.zillit.desktop.feature.cardexpenses.domain.CardExportRow
import com.zillit.desktop.feature.cardexpenses.domain.CardHistoryEntry
import com.zillit.desktop.feature.cardexpenses.domain.CardMetadata
import com.zillit.desktop.feature.cardexpenses.domain.CardOverview
import com.zillit.desktop.feature.cardexpenses.domain.CardReceipt
import com.zillit.desktop.feature.cardexpenses.domain.CardRepository
import com.zillit.desktop.feature.cardexpenses.domain.CardSettings
import com.zillit.desktop.feature.cardexpenses.domain.CardTopUp
import com.zillit.desktop.feature.cardexpenses.domain.CardTransaction
import com.zillit.desktop.feature.cardexpenses.domain.DraftCardReceipt
import com.zillit.desktop.feature.cardexpenses.domain.ExpenseCard
import com.zillit.desktop.feature.cardexpenses.domain.ExportFormat
import com.zillit.desktop.feature.cardexpenses.domain.FundRequest
import com.zillit.desktop.feature.cardexpenses.domain.FundRequestDraft
import com.zillit.desktop.feature.cardexpenses.domain.QueryMessage
import com.zillit.desktop.feature.cardexpenses.domain.QueryThread
import com.zillit.desktop.feature.cardexpenses.domain.NewCardRequest
import com.zillit.desktop.feature.cardexpenses.domain.ProcessSubmission
import com.zillit.desktop.feature.cardexpenses.domain.ReceiptAssignment
import com.zillit.desktop.feature.cardexpenses.domain.ReceiptCoding
import com.zillit.desktop.feature.cardexpenses.domain.ReceiptEdit
import com.zillit.desktop.feature.cardexpenses.domain.ReceiptScope
import com.zillit.desktop.feature.cardexpenses.domain.SettingsSection
import com.zillit.desktop.feature.cardexpenses.domain.StatementImport
import com.zillit.desktop.feature.cardexpenses.domain.TierVisibility
import com.zillit.desktop.feature.cardexpenses.domain.TransactionFilters

/**
 * A card service in memory: every read answers from the fields below, and
 * every write succeeds and is written down in [calls] by its name, so a test
 * can say which route a handler reached — or that it reached none.
 */
@Suppress("TooManyFunctions") // The whole repository surface, one line each.
internal class FakeCardRepository(
    var metadata: CardMetadata = CardMetadata(),
    var receipts: List<CardReceipt> = emptyList(),
    var cards: List<ExpenseCard> = emptyList(),
    var topUps: List<CardTopUp> = emptyList(),
    var settings: CardSettings = CardSettings(),
    var fundRequests: List<FundRequest> = emptyList(),
    var alerts: List<CardAlert> = emptyList(),
) : CardRepository {

    val calls = mutableListOf<String>()
    val submissions = mutableListOf<ProcessSubmission>()
    val activations = mutableListOf<CardActivation>()
    val partials = mutableListOf<Pair<Double?, String>>()

    private fun ok(name: String): ZillitResult<Unit> {
        calls += name
        return ZillitResult.Success(Unit)
    }

    private fun <T> read(value: T): ZillitResult<T> = ZillitResult.Success(value)

    override suspend fun metadata() = read(metadata)
    override suspend fun overview() = read(CardOverview())
    override suspend fun analytics(fromIso: String?, toIso: String?) = read(CardAnalytics())
    override suspend fun cards(mineOnly: Boolean) = read(cards)
    override suspend fun cardHistory(cardId: String) = read(emptyList<CardHistoryEntry>())
    override suspend fun cardReceipts(cardId: String) = read(emptyList<CardReceipt>())
    override suspend fun requestCard(request: NewCardRequest) = ok("requestCard")
    override suspend fun updateCardDetails(cardId: String, edit: CardDetailsEdit) = ok("updateCardDetails")
    override suspend fun deleteCard(cardId: String) = ok("deleteCard")
    override suspend fun approveCard(cardId: String, step: TierVisibility, userId: String) = ok("approveCard")
    override suspend fun rejectCard(cardId: String, reason: String, userId: String) = ok("rejectCard")
    override suspend fun overrideCard(cardId: String, userId: String, reason: String) = ok("overrideCard")
    override suspend fun activateCard(cardId: String, activation: CardActivation): ZillitResult<Unit> {
        activations += activation
        return ok("activateCard")
    }

    override suspend fun suspendCard(cardId: String) = ok("suspendCard")
    override suspend fun reactivateCard(cardId: String) = ok("reactivateCard")
    override suspend fun assignPhysicalCard(cardId: String, cardNumber: String) = ok("assignPhysicalCard")
    override suspend fun updateBsControlCode(cardId: String, code: String) = ok("updateBsControlCode")
    override suspend fun imports() = read(emptyList<StatementImport>())
    override suspend fun transactions(filters: TransactionFilters) = read(emptyList<CardTransaction>())
    override suspend fun codeTransaction(transactionId: String, nominalCode: String, description: String?) =
        ok("codeTransaction")

    override suspend fun submitTransaction(transactionId: String) = ok("submitTransaction")
    override suspend fun postTransaction(transactionId: String) = ok("postTransaction")
    override suspend fun queryTransaction(transactionId: String, reason: String) = ok("queryTransaction")
    override suspend fun rejectTransaction(transactionId: String, reason: String) = ok("rejectTransaction")
    override suspend fun flagTransactionPersonal(transactionId: String) = ok("flagTransactionPersonal")
    override suspend fun deleteTransaction(transactionId: String) = ok("deleteTransaction")
    override suspend fun bulkDeleteTransactions(transactionIds: List<String>): ZillitResult<BulkOutcome> {
        calls += "bulkDeleteTransactions"
        return read(BulkOutcome(transactionIds.size, 0))
    }

    override suspend fun receipts(scope: ReceiptScope) = read(receipts)
    override suspend fun matchCandidates(receiptId: String) = read(emptyList<CardTransaction>())
    override suspend fun matchReceipt(receiptId: String, transactionId: String) = ok("matchReceipt")
    override suspend fun unmatchReceipt(receiptId: String) = ok("unmatchReceipt")
    override suspend fun confirmReceiptMatch(receiptId: String) = ok("confirmReceiptMatch")
    override suspend fun submitReceipts(card: ExpenseCard?, receipts: List<DraftCardReceipt>): ZillitResult<Unit> {
        batches += card to receipts
        return ok("submitReceipts")
    }
    override suspend fun submitReceiptForApproval(receiptId: String, coding: ReceiptCoding?) =
        ok("submitReceiptForApproval")

    override suspend fun approveAndSubmitReceipt(receiptId: String, coding: ReceiptCoding) =
        ok("approveAndSubmitReceipt")

    override suspend fun updateReceiptCoding(receiptId: String, coding: ReceiptCoding) = ok("updateReceiptCoding")
    override suspend fun codeReceipt(receiptId: String, coding: ReceiptCoding) = ok("codeReceipt")
    override suspend fun postReceipt(receiptId: String) = ok("postReceipt")
    override suspend fun receiptDetail(receiptId: String): ZillitResult<CardReceipt> {
        calls += "receiptDetail"
        return read(receipts.first { it.id == receiptId })
    }

    /** When set, `save-process` refuses the way the server does — a failure with its message. */
    var refuseSave: String? = null

    override suspend fun saveProcessReceipt(receiptId: String, submission: ProcessSubmission): ZillitResult<Unit> {
        submissions += submission
        refuseSave?.let {
            return ZillitResult.Failure(com.zillit.desktop.core.common.ZillitError.Validation(it))
        }
        return ok("saveProcessReceipt")
    }

    override suspend fun assignReceipt(receiptId: String, assignment: ReceiptAssignment, reassign: Boolean) =
        ok(if (reassign) "reassignReceipt" else "assignReceipt")

    override suspend fun flagReceiptPersonal(receiptId: String) = ok("flagReceiptPersonal")
    override suspend fun dismissDuplicate(receiptId: String) = ok("dismissDuplicate")
    override suspend fun dismissPersonal(receiptId: String) = ok("dismissPersonal")
    override suspend fun deleteReceipt(receiptId: String) = ok("deleteReceipt")
    override suspend fun receiptHistory(receiptId: String) = read(emptyList<CardHistoryEntry>())

    // -- the cardholder's pages (crew builder) ---------------------------------
    var approvalCards: List<ExpenseCard> = emptyList()
    val receiptEdits = mutableListOf<Pair<String, ReceiptEdit>>()
    val batches = mutableListOf<Pair<ExpenseCard?, List<DraftCardReceipt>>>()
    override suspend fun cardsForApproval() = read(approvalCards)
    override suspend fun updateReceipt(receiptId: String, edit: ReceiptEdit): ZillitResult<Unit> {
        receiptEdits += receiptId to edit
        return ok("updateReceipt")
    }
    override suspend fun confirmReceipt(receiptId: String) = ok("confirmReceipt")
    override suspend fun bulkProcessable() = read(emptyList<BulkItem>())
    override suspend fun bulkProcess(receiptIds: List<String>, coding: BulkCoding): ZillitResult<BulkOutcome> {
        calls += "bulkProcess"
        return read(BulkOutcome(receiptIds.size, 0))
    }

    override suspend fun approvalQueue() = read(receipts)
    override suspend fun approveReceipt(receiptId: String, tierNumber: Int, userId: String) =
        ok("approveReceipt:$tierNumber")
    override suspend fun rejectReceipt(receiptId: String, reason: String, userId: String) = ok("rejectReceipt")

    /** What the process pages read besides the rows; set per test. */
    var processRefs = com.zillit.desktop.feature.cardexpenses.domain.ProcessRefs(loaded = true)
    override suspend fun processReferences() = read(processRefs)
    override suspend fun overrideReceipt(receiptId: String, userId: String, reason: String) = ok("overrideReceipt")
    override suspend fun bulkApproval(action: BulkAction, receiptIds: List<String>) = ok("bulk:${action.wire}")
    override suspend fun topUps() = read(topUps)
    override suspend fun cardTopUps(cardId: String) = read(topUps)
    override suspend fun topUpHistory(topUpId: String) = read(emptyList<CardHistoryEntry>())
    override suspend fun requestTopUp(cardId: String, amount: Double, reason: String?) = ok("requestTopUp")
    override suspend fun completeTopUp(topUpId: String) = ok("completeTopUp")
    override suspend fun partialTopUp(topUpId: String, amount: Double?, note: String): ZillitResult<Unit> {
        partials += amount to note
        return ok("partialTopUp")
    }

    override suspend fun skipTopUp(topUpId: String) = ok("skipTopUp")
    override suspend fun queryThread(entityType: String, entityId: String) = read(QueryThread())
    override suspend fun sendQuery(
        thread: QueryThread,
        entityType: String,
        entityId: String,
        text: String,
    ): ZillitResult<QueryThread> {
        calls += "sendQuery"
        return read(QueryThread("thread-1", thread.messages + QueryMessage("me", text, null)))
    }

    override suspend fun fundRequests() = read(fundRequests)
    override suspend fun createFundRequest(draft: FundRequestDraft) = ok("createFundRequest")
    override suspend fun receiveFundRequest(id: String) = ok("receiveFundRequest")
    override suspend fun cancelFundRequest(id: String) = ok("cancelFundRequest")

    override suspend fun exportCards(format: ExportFormat, rows: List<CardExportRow>): ZillitResult<ByteArray> {
        calls += "exportCards"
        return read(ByteArray(1))
    }

    override suspend fun exportTransactions(format: ExportFormat): ZillitResult<ByteArray> {
        calls += "exportTransactions"
        return read(ByteArray(1))
    }

    override suspend fun alerts() = read(alerts)
    override suspend fun resolveAlert(alertId: String, note: String?) = ok("resolveAlert")
    override suspend fun dismissAlert(alertId: String) = ok("dismissAlert")
    override suspend fun investigateAlert(alertId: String) = ok("investigateAlert")
    override suspend fun settings() = read(settings)
    override suspend fun updateSettings(section: SettingsSection, settings: CardSettings): ZillitResult<CardSettings> {
        calls += "updateSettings"
        return read(settings)
    }
}
