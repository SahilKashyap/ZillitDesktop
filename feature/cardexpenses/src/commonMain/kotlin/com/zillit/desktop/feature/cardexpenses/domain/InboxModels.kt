package com.zillit.desktop.feature.cardexpenses.domain

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult

/**
 * The accountant's reconciliation surfaces — Import Statement, Receipt Inbox,
 * All Transactions — and the receipt detail and manual match they open.
 *
 * Kept apart from `CardModels` because these shapes are what the web's pages
 * read off their own responses (`ImportStatementPage.jsx`, `ReceiptInboxPage.jsx`,
 * `ManualMatchModal.jsx`, `ReceiptDetailModal.jsx`), not the queue rows every
 * other page shares.
 */

/**
 * One transaction the manual match can tie a receipt to
 * (`ManualMatchModal.jsx:165-195`).
 *
 * Its own shape rather than [CardTransaction]: the candidates route answers
 * `merchant_name` and a `match_score`, neither of which a ledger row carries,
 * and reading them as a transaction printed a blank merchant and no score.
 */
data class MatchCandidate(
    val id: String,
    val merchant: String,
    val date: Long?,
    /** Null for the importer's `0000` placeholder, which names no card. */
    val cardLastFour: String?,
    val amount: Double,
    val currency: String?,
    /** Whole percent, 0..100. */
    val scorePercent: Int,
)

/** The receipt's stored document, as the detail view previews it. */
data class ReceiptMedia(
    val key: String,
    val name: String? = null,
    val bucket: String? = null,
    val region: String? = null,
    val contentType: String? = null,
) {
    val fileName: String get() = name?.takeIf { it.isNotBlank() } ?: key.substringAfterLast('/')

    val isPdf: Boolean
        get() = contentType?.contains(PDF, ignoreCase = true) == true ||
            key.endsWith(".$PDF", ignoreCase = true) || name?.endsWith(".$PDF", ignoreCase = true) == true

    private companion object {
        const val PDF = "pdf"
    }
}

/** One line of a receipt's coding, as the detail view lists it (`ReceiptDetailModal.jsx:366-388`). */
data class ReceiptDetailLine(
    val code: String?,
    val description: String?,
    val net: Double,
    val tax: Double,
    val tags: List<String> = emptyList(),
    /** A split child: drawn with a `↳` and no tax of its own. */
    val splitChild: Boolean = false,
)

/** One sign-off on a receipt; tier 0 or an `override` flag is an override, drawn purple. */
data class ReceiptDetailApproval(val userId: String, val tierNumber: Int, val override: Boolean)

/**
 * Everything the receipt detail shows.
 *
 * Built from `GET /receipts/:id/detail` when an opener has fetched it, or from
 * the slim queue row with [of] while it has not: the row lacks the lines, the
 * approvals and the document model, and the view says so by what it leaves out.
 */
data class ReceiptDetail(
    val receipt: CardReceipt,
    val lines: List<ReceiptDetailLine> = emptyList(),
    val approvals: List<ReceiptDetailApproval> = emptyList(),
    val media: ReceiptMedia? = receipt.attachmentKey?.takeIf { it.isNotBlank() }?.let { ReceiptMedia(it) },
    /** What the linked transaction is in, when the detail says; the receipt's own otherwise. */
    val transactionCurrency: String? = null,
) {
    companion object {
        fun of(receipt: CardReceipt): ReceiptDetail = ReceiptDetail(receipt)
    }
}

/**
 * A write the inbox, the manual match and All Transactions make, each
 * answered by the server's own message (`showApiSuccess`).
 */
sealed interface InboxWrite {
    /** `POST /receipts/:id/confirm-match` — the row menu's Attach. */
    data class ConfirmMatch(val receiptId: String) : InboxWrite

    /** `POST /receipts/:id/manual-match {transactionId}`. */
    data class ManualMatch(val receiptId: String, val transactionId: String) : InboxWrite

    data class FlagReceiptPersonal(val receiptId: String) : InboxWrite

    /** The linked receipt's Flag Personal goes to its statement line. */
    data class FlagTransactionPersonal(val transactionId: String) : InboxWrite

    data class DismissDuplicate(val receiptId: String) : InboxWrite

    data class DismissPersonal(val receiptId: String) : InboxWrite

    /** `POST /receipts/rerun-match {}` — every statement, as the web sends it. */
    data object RerunMatch : InboxWrite

    data class DeleteTransaction(val transactionId: String) : InboxWrite
}

/** What a write answered, and the sentence the server said it in. */
data class ServerOutcome<T>(val data: T, val message: String?)

/** The statement file an accountant picked or dropped, not yet stored. */
class StatementFile(val name: String, val bytes: ByteArray) {
    override fun toString(): String = "StatementFile(name=$name, size=${bytes.size})"
}

/**
 * The stored statement, as the import route takes it: the whole attachment
 * model (`attachmentUpload.js:53-61`), not the bare key the desktop used to send.
 */
data class StoredStatement(
    val media: String,
    val bucket: String?,
    val region: String?,
    val name: String,
    val contentType: String,
    val contentSubtype: String,
)

/** The import record the route creates (`res.data.import`). */
data class StatementImportInfo(
    val id: String?,
    val fileName: String?,
    val issuer: String?,
    val accountName: String?,
    val accountNumber: String?,
    val sortCode: String?,
    val currency: String?,
)

/** `res.data.summary`: what the file held. */
data class StatementSummary(
    val totalRows: Int = 0,
    val newCount: Int = 0,
    val duplicateCount: Int = 0,
    val declinedCount: Int = 0,
    val periodStart: Long? = null,
    val periodEnd: Long? = null,
)

/** One row the import produced — already a transaction, with its status. */
data class ImportedRow(
    val id: String,
    val rowIndex: Int?,
    val date: Long?,
    val merchant: String,
    val holderId: String?,
    val cardLastFour: String?,
    val amount: Double,
    val currency: String?,
    val status: String,
) {
    /** Only a `new` row is selectable — the rest are duplicates, declines or already sent. */
    val isNew: Boolean get() = status == NEW

    companion object {
        const val NEW = "new"
        const val PENDING_RECEIPT = "pending_receipt"
    }
}

/** `POST /imports/import-statement` → `{import, rows, summary, processed}`. */
data class StatementImportResult(
    val info: StatementImportInfo,
    val summary: StatementSummary,
    /** `processed.rowsProcessed`; null when the server did not process (no success banner). */
    val rowsProcessed: Int?,
    val rows: List<ImportedRow>,
)

/** `POST /transactions/submit-to-users` → `{submitted, skipped, totalAmount, notified}`. */
data class CrewSubmission(
    val submitted: Int,
    val skipped: Int,
    val totalAmount: Double,
    /** Who was told, and about how many transactions. */
    val notified: List<Pair<String, Int>>,
)

/** The production's currencies: its default first, then the rest it trades in. */
data class CardCurrencies(val defaultCode: String? = null, val codes: List<String> = emptyList())

/**
 * The inbox surfaces' host seams: the file picker and storage, the document
 * store, and two catalogues that belong to the Account Hub rather than to the
 * card service.
 *
 * One interface rather than five constructor parameters: the view model's
 * signature is shared with every other card page, and these all arrive
 * together from `CardExpensesWiring`.
 */
interface CardInboxHost {
    /** A cancelled picker is success carrying null. */
    suspend fun pickStatement(): ZillitResult<StatementFile?>

    /** Puts the statement in the project's store; the import route reads it from there. */
    suspend fun storeStatement(file: StatementFile): ZillitResult<StoredStatement>

    /** The receipt's document bytes, for the detail view's inline preview. */
    suspend fun media(media: ReceiptMedia): ZillitResult<ByteArray>

    /** The production's departments (`useDepartments`). */
    suspend fun departments(): List<CardDepartment>

    /** The production's configured currencies (`useProjectCurrencies`). */
    suspend fun currencies(): CardCurrencies
}

/**
 * The inbox surfaces' server calls, answered with the server's own message.
 *
 * Every member has a body that refuses, so a repository that predates these
 * surfaces — a test double — compiles unchanged and says why it cannot help.
 */
interface CardInboxApi {
    suspend fun inboxReceiptDetail(receiptId: String): ZillitResult<ReceiptDetail> = unsupported()

    suspend fun inboxMatchCandidates(receiptId: String): ZillitResult<List<MatchCandidate>> = unsupported()

    /** The write, answered with the server's message (null when it sent none). */
    suspend fun inboxWrite(write: InboxWrite): ZillitResult<String?> = unsupported()

    /** How many the server removed — an explicit zero is "nothing"; see `deletedTransactions`. */
    suspend fun bulkDeleteTransactionsCounted(transactionIds: List<String>): ZillitResult<ServerOutcome<Int>> =
        unsupported()

    /** Ingests a stored statement; [currency] null lets the server apply the project default. */
    suspend fun importStatementFile(
        attachment: StoredStatement,
        currency: String?,
    ): ZillitResult<ServerOutcome<StatementImportResult>> = unsupported()

    suspend fun submitToCrew(transactionIds: List<String>): ZillitResult<ServerOutcome<CrewSubmission>> =
        unsupported()
}

private fun <T> unsupported(): ZillitResult<T> =
    ZillitResult.Failure(ZillitError.Unknown("not supported by this repository"))
