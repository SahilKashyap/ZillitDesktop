package com.zillit.desktop.feature.cardexpenses.ui

import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.cardexpenses.domain.CardDates
import com.zillit.desktop.feature.cardexpenses.domain.CardHistoryEntry
import com.zillit.desktop.feature.cardexpenses.domain.CardPeriodLock
import com.zillit.desktop.feature.cardexpenses.domain.CardReceipt
import com.zillit.desktop.feature.cardexpenses.domain.CardType
import com.zillit.desktop.feature.cardexpenses.domain.ProcessFigures
import com.zillit.desktop.feature.cardexpenses.domain.ProcessLine
import com.zillit.desktop.feature.cardexpenses.domain.ProcessRefs
import com.zillit.desktop.feature.cardexpenses.domain.TaxLineDraft
import com.zillit.desktop.feature.cardexpenses.domain.TopUpMethod
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.Instant

/** Where the process editor was opened from: the queue works it, History only corrects it. */
enum class ProcessMode { Process, History }

/** The Process page's two queues; Posting Review is a senior's (`ProcessPage.jsx:505-510`). */
enum class ProcessTab { Processing, Review }

/**
 * The accountant's process editor, open over one receipt — a full page that
 * takes over the content column, as the web's `ProcessReceiptModal fullPage`.
 *
 * The header corrections (vendor, cost code, ledger date), the coded lines and
 * the reclaimable-tax row, and — when the holder asked for one — the top-up
 * decision. Held in state because five buttons commit it, and each must read
 * what the others saw.
 */
data class ProcessDraft(
    val receipt: CardReceipt,
    val mode: ProcessMode,
    /** The detail read is still in flight; nothing commits until it lands. */
    val loading: Boolean,
    val description: String,
    val nominalCode: String,
    /** `YYYY-MM-DD`; required to post. */
    val effectiveDate: String,
    val lines: List<ProcessLine>,
    val topUp: TopUpMethod,
    /** The assign / reassign dialog, when open. */
    val assign: AssignDraft? = null,
    /** The escalation reason, when the escalate dialog is open. */
    val escalation: String? = null,
    /** The reclaimable-tax row's own nominal, Layers, tags and override. */
    val taxLine: TaxLineDraft = TaxLineDraft(),
    /** The line Split acts on. */
    val selectedLineId: String? = null,
    /** Rows a blocked post flagged for a missing nominal; the tax row as [ProcessFigures.TAX_ROW]. */
    val lineErrors: Set<String> = emptySet(),
    /**
     * Whether the queue's row lock applies — opened from the Processing
     * Queue, where only a senior or the assignee may work a row. Bulk Process
     * opens any row it lists, as the web's does.
     */
    val gated: Boolean = true,
    /** The receipt's trail, while the History panel is open; empty while it loads. */
    val history: List<CardHistoryEntry>? = null,
) {
    /** The editor's arithmetic, against the production's tax types. */
    fun figures(refs: ProcessRefs): ProcessFigures = ProcessFigures(
        receiptAmount = receipt.amount,
        lines = lines,
        fixedLines = receipt.processing.fixedLines,
        cardLimit = receipt.processing.cardLimit,
        cardBalance = receipt.processing.cardBalance,
        taxLine = taxLine,
        taxTypes = refs.taxTypes,
        taxTypesKnown = refs.taxTypesKnown,
    )

    val effectiveDateMillis: Long? get() = CardDates.toMillis(effectiveDate)

    /** The receipt's saved ledger date sits in the closed period: nothing on it may change. */
    fun periodLocked(lock: CardPeriodLock): Boolean = lock.isLocked(receipt.processing.effectiveDate)

    companion object {
        /**
         * Seeded from the receipt. Every line gets an id, so a split can point
         * at its parent; a receipt with no coded lines gets one for its whole
         * amount, so the editor is never empty; a fresh ledger date is the
         * first open day — today, or the day after the lock when it is later.
         */
        fun of(
            receipt: CardReceipt,
            mode: ProcessMode,
            loading: Boolean,
            today: Long,
            lock: CardPeriodLock = CardPeriodLock(),
            gated: Boolean = true,
        ): ProcessDraft {
            val processing = receipt.processing
            val lines = processing.lines.map { it.copy(id = it.id ?: newLineId()) }.ifEmpty {
                listOf(
                    ProcessLine(
                        id = newLineId(),
                        description = receipt.description.ifBlank { SEED_DESCRIPTION },
                        account = receipt.nominalCode.orEmpty(),
                        net = receipt.amount,
                    ),
                )
            }
            return ProcessDraft(
                receipt = receipt,
                mode = mode,
                loading = loading,
                description = receipt.description,
                nominalCode = receipt.nominalCode.orEmpty(),
                effectiveDate = processing.effectiveDate?.let(CardDates::toIso) ?: lock.defaultDay(localDay(today)),
                lines = lines,
                topUp = if (processing.requestTopUp) TopUpMethod.Restore else TopUpMethod.None,
                taxLine = processing.taxLine ?: TaxLineDraft(),
                gated = gated,
            )
        }

        /** The web's fallback description for the seeded line (`ProcessReceiptModal.jsx:294`). */
        private const val SEED_DESCRIPTION = "Transaction"
    }
}

/** A client id for a new line — the server remaps it on insert, as the web's `uid()` is. */
internal fun newLineId(): String = "li-${Clock.System.now().toEpochMilliseconds()}-${++lineCounter}"

private var lineCounter = 0

/** The machine's own day for [millis], `YYYY-MM-DD` — the web's `todayYmd` is local. */
internal fun localDay(millis: Long): String =
    Instant.fromEpochMilliseconds(millis).toLocalDateTime(TimeZone.currentSystemDefault()).date.toString()

/**
 * The accountant's process pages — Approval Queue, Process, Bulk Process,
 * History — beyond the rows themselves: what they read besides the card
 * routes, and the approval queue's open detail and reject dialog.
 */
data class ProcessPagesState(
    val refs: ProcessRefs = ProcessRefs(),
    /** The approval row opened for a closer look, and whether its detail is still coming. */
    val detail: CardReceipt? = null,
    val detailLoading: Boolean = false,
    /** The reject dialog: the receipt and the reason being typed. */
    val reject: RejectDraft? = null,
    /** The row whose Approve or Override is in flight. */
    val acting: String? = null,
    /** A bulk approval or a batch post is in flight. */
    val bulkBusy: Boolean = false,
)

data class RejectDraft(val receipt: CardReceipt, val reason: String = "")

/** Handing a receipt to a team member: who, and why. */
data class AssignDraft(val userId: String = "", val reason: String = "", val custom: String = "") {
    val finalReason: String get() = if (reason == CUSTOM) custom.trim() else reason

    val complete: Boolean get() = userId.isNotBlank() && finalReason.isNotBlank()

    companion object {
        /** The "Other (custom reason)" choice. */
        const val CUSTOM = "__custom"

        /** The web's reasons (`ProcessReceiptModal.jsx:50-56`), read at use so the language follows. */
        val reasons: List<String>
            get() = listOf(
                str(S.desktop_card_reason_complex),
                str(S.desktop_inv_reason_workload),
                str(S.desktop_card_reason_expertise),
                str(S.desktop_card_reason_cover),
                str(S.desktop_inv_reason_escalation),
            )
    }
}

/**
 * Activating an approved card: its type, its sixteen digits, and a provider
 * when the request came without one (`CardRegisterPage.jsx:460-506`).
 */
data class ActivationDraft(
    val cardId: String,
    val type: CardType? = null,
    val number: String = "",
    /** Whether the card still needs a provider — true for a crew self-service request. */
    val needsProvider: Boolean = false,
    val providerId: String = "",
) {
    val digits: String get() = number.filter(Char::isDigit)

    /** Why this cannot be sent yet, or null. */
    fun validationError(providersConfigured: Boolean): String? = when {
        type == null -> str(S.desktop_card_choose_card_type)
        digits.length != CARD_DIGITS -> str(S.desktop_card_enter_sixteen_digits)
        needsProvider && providersConfigured && providerId.isBlank() -> str(S.desktop_card_choose_provider_error)
        else -> null
    }

    private companion object {
        const val CARD_DIGITS = 16
    }
}
