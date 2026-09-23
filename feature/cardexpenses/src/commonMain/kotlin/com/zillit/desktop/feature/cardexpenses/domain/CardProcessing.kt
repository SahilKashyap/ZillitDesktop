package com.zillit.desktop.feature.cardexpenses.domain

import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import kotlinx.serialization.json.JsonObject
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * What the accountant's process editor works on for one receipt.
 *
 * Read off `GET /receipts/:id/detail` — the queue's own rows carry none of it.
 * The web's `ProcessReceiptModal` is the reference: coded lines are edited, and
 * the lines the server owns (auto-deductions, the consolidated reclaimable-tax
 * line) travel back **verbatim**, because the card service stores what it is
 * sent and does not re-create them — dropping one deletes it.
 */
data class ReceiptProcessing(
    /** Whether the detail has been read; the queue row alone has no lines. */
    val loaded: Boolean = false,
    val lines: List<ProcessLine> = emptyList(),
    /** Auto-deductions and the `is_tax` line, sent back as they came. */
    val fixedLines: List<FixedLine> = emptyList(),
    /** `query`, `review`, `deduct` — the processing rules the receipt tripped. */
    val flags: Set<String> = emptySet(),
    val cardLimit: Double? = null,
    val cardBalance: Double? = null,
    /** The holder asked for the spend to be topped back up. */
    val requestTopUp: Boolean = false,
    /** The ledger date an accountant set, epoch millis at UTC midnight. */
    val effectiveDate: Long? = null,
    val escalationReason: String? = null,
) {
    val needsQuery: Boolean get() = QUERY in flags
    val needsReview: Boolean get() = REVIEW in flags

    companion object {
        const val QUERY = "query"
        const val REVIEW = "review"
    }
}

/**
 * One coded line in the editor.
 *
 * The editor works in **net**; the wire's `amount` is gross (net + tax at the
 * line's rate), derived on save the way the web's `buildPayload` does. [raw]
 * is the line as it arrived, so the fields this client does not edit —
 * tracking codes, tags, split parents, rental dates, expenditure type —
 * round-trip rather than being blanked.
 */
data class ProcessLine(
    val id: String? = null,
    val description: String = "",
    val account: String = "",
    val net: Double = 0.0,
    /** Percent, e.g. 20.0; null for no tax. */
    val taxRate: Double? = null,
    val quantity: Double = 1.0,
    val raw: JsonObject? = null,
) {
    val tax: Double get() = round2(net * (taxRate ?: 0.0) / PERCENT)

    val gross: Double get() = round2(net + tax)

    /** Nothing entered at all — not a line the ledger will see. */
    val blank: Boolean get() = description.isBlank() && account.isBlank() && net == 0.0
}

/**
 * A line the server owns: an auto-deduction or the reclaimable-tax line.
 *
 * Auto-deductions count in the totals (their gross and their own tax); the tax
 * line does not — the web's totals net it out while tax types are unknown to
 * the client, which on the desktop is always.
 */
data class FixedLine(val raw: JsonObject, val gross: Double, val tax: Double, val countsInTotal: Boolean)

/** What happens to the card's balance when the receipt posts. */
enum class TopUpMethod(val wire: String, private val labelKey: String) {
    Restore("restore", S.desktop_card_restore_to_limit),
    Expense("expense", S.desktop_card_top_up_by_expense),
    None("none", S.desktop_card_no_top_up),
    ;

    val label: String get() = str(labelKey)
}

/**
 * The arithmetic and the rules of the process editor, pure so they can be
 * pinned without a screen. Every figure is gross unless it says otherwise.
 */
data class ProcessFigures(
    val receiptAmount: Double,
    val lines: List<ProcessLine>,
    val fixedLines: List<FixedLine>,
    val cardLimit: Double?,
    val cardBalance: Double?,
) {
    private val counted = fixedLines.filter { it.countsInTotal }

    val net: Double get() = round2(lines.sumOf { it.net } + counted.sumOf { it.gross - it.tax })
    val tax: Double get() = round2(lines.sumOf { it.tax } + counted.sumOf { it.tax })
    val gross: Double get() = round2(lines.sumOf { it.gross } + counted.sumOf { it.gross })

    /**
     * Coded gross must reconcile to the receipt, to the penny, before a save
     * or a post — over **or** under. A receipt with no amount never blocks:
     * there is nothing authoritative to match (`money.js isAmountMismatch`).
     */
    val mismatch: Boolean
        get() = receiptAmount > 0 && abs(round2(gross - receiptAmount)) > PENNY

    /** What will actually be debited: the lines' total when it is lower. */
    val effectiveAmount: Double
        get() = if (gross > 0 && gross < receiptAmount) gross else receiptAmount

    private val limit: Double get() = cardLimit ?: 0.0
    private val balance: Double get() = cardBalance ?: limit

    val balanceAfter: Double get() = (balance - effectiveAmount).coerceAtLeast(0.0)

    fun topUpAmount(method: TopUpMethod): Double = when (method) {
        TopUpMethod.Restore -> (limit - balance + effectiveAmount).coerceAtLeast(0.0)
        TopUpMethod.Expense -> effectiveAmount
        TopUpMethod.None -> 0.0
    }

    /** A top-up that would carry the card past its limit — refused, as the web refuses it. */
    fun topUpOverfills(method: TopUpMethod): Boolean {
        val topUp = topUpAmount(method)
        return topUp > 0 && (balance - effectiveAmount) + topUp > limit
    }

    /** The largest top-up the card can take after this expense. */
    val topUpHeadroom: Double get() = limit - (balance - effectiveAmount)

    /** One-based numbers of the lines that would reach the ledger without a nominal. */
    val linesMissingNominal: List<Int>
        get() = lines.mapIndexedNotNull { index, line ->
            (index + 1).takeIf { !line.blank && line.account.isBlank() }
        }
}

/**
 * Who may do what in the process editor.
 *
 * The web's header buttons (`ProcessReceiptModal.jsx:503-570`) and the
 * queue's row lock (`ProcessPage.jsx:436-438`), in one place so the screen
 * and the view model's handlers cannot drift apart.
 */
object ProcessRules {

    /**
     * Whether this accountant may open the receipt at all.
     *
     * A senior works any row; everyone else only the rows assigned to them. An
     * unassigned row is a senior's to hand out.
     */
    fun canOpen(viewer: CardViewer, receipt: CardReceipt): Boolean {
        if (!viewer.isAccountant) return false
        val assignee = receipt.assignedTo
        return viewer.isSenior || (!assignee.isNullOrBlank() && assignee == viewer.userId)
    }

    /**
     * Whether Post is offered: hidden from a non-senior while a review or
     * query rule is on the receipt, and from anyone whose posting limit is
     * below what will be debited.
     */
    fun canPost(viewer: CardViewer, processing: ReceiptProcessing, effectiveAmount: Double): Boolean {
        if (!viewer.isAccountant) return false
        if (!viewer.isSenior && (processing.needsReview || processing.needsQuery)) return false
        val limit = viewer.metadata.postingLimit
        return limit == null || limit >= effectiveAmount
    }

    /** Escalating and submitting for review are how a non-senior hands a receipt up. */
    fun canHandUp(viewer: CardViewer): Boolean = viewer.isAccountant && !viewer.isSenior
}

/**
 * One `save-process` call.
 *
 * [status] tells the four buttons apart: null saves, `posted` posts,
 * `under_review` submits for review, `escalated` escalates — the web's
 * `saveAndAction` (`ProcessReceiptModal.jsx:446-460`).
 */
data class ProcessSubmission(
    val lines: List<ProcessLine>,
    val fixedLines: List<FixedLine>,
    val net: Double,
    val tax: Double,
    val gross: Double,
    val description: String?,
    val nominalCode: String?,
    val effectiveDate: Long?,
    val userId: String,
    val status: String? = null,
    val topUpMethod: TopUpMethod? = null,
    val topUpAmount: Double? = null,
    val escalationReason: String? = null,
) {
    companion object {
        const val POSTED = "posted"
        const val UNDER_REVIEW = "under_review"
        const val ESCALATED = "escalated"
    }
}

/** Handing a receipt to someone: the web's `{assign_to, assigned_by, reason}`. */
data class ReceiptAssignment(val assignTo: String, val assignedBy: String, val reason: String)

/**
 * Activating an approved card: what kind it is, its number, and — for a card
 * requested without one — the provider it is held with, whose bank and
 * company ride along (`CardRegisterPage.jsx:460-506`).
 */
data class CardActivation(
    val cardType: CardType,
    val cardNumber: String,
    val providerId: String? = null,
    val bankId: String? = null,
    val companyId: String? = null,
)

/**
 * One register row as the export prints it (`CardRegisterPage.jsx:369-381`):
 * the names are resolved here, because the server has only ids.
 */
data class CardExportRow(
    val id: String,
    val last4: String,
    val holder: String,
    val department: String,
    val issuer: String,
    val status: String,
    val currency: String,
    val limit: Double,
    val balance: Double?,
)

/** The two file kinds the card exports come in. */
enum class ExportFormat(val wire: String, val extension: String) {
    Pdf("pdf", "pdf"),
    Excel("xlsx", "xlsx"),
}

internal fun round2(value: Double): Double = (value * HUNDRED).roundToLong() / HUNDRED

private const val PERCENT = 100.0
private const val HUNDRED = 100.0
private const val PENNY = 0.01
