package com.zillit.desktop.feature.cardexpenses.ui

import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.cardexpenses.domain.CardDates
import com.zillit.desktop.feature.cardexpenses.domain.CardReceipt
import com.zillit.desktop.feature.cardexpenses.domain.CardType
import com.zillit.desktop.feature.cardexpenses.domain.ProcessFigures
import com.zillit.desktop.feature.cardexpenses.domain.ProcessLine
import com.zillit.desktop.feature.cardexpenses.domain.TopUpMethod

/** Where the process editor was opened from: the queue works it, History only corrects it. */
enum class ProcessMode { Process, History }

/** The Process page's two queues; Posting Review is a senior's (`ProcessPage.jsx:505-510`). */
enum class ProcessTab { Processing, Review }

/**
 * The accountant's process editor, open over one receipt.
 *
 * The web's `ProcessReceiptModal`: the header corrections (vendor, cost code,
 * ledger date), the coded lines, and — when the holder asked for one — the
 * top-up decision. Held in state because five buttons commit it, and each
 * must read what the others saw.
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
) {
    val figures: ProcessFigures
        get() = ProcessFigures(
            receiptAmount = receipt.amount,
            lines = lines,
            fixedLines = receipt.processing.fixedLines,
            cardLimit = receipt.processing.cardLimit,
            cardBalance = receipt.processing.cardBalance,
        )

    val effectiveDateMillis: Long? get() = CardDates.toMillis(effectiveDate)

    companion object {
        /**
         * Seeded from the receipt. A receipt with no coded lines gets one for
         * its whole amount, so the editor is never empty; a fresh ledger date
         * is today's, as the web's first selectable day is.
         */
        fun of(receipt: CardReceipt, mode: ProcessMode, loading: Boolean, today: Long): ProcessDraft {
            val processing = receipt.processing
            val lines = processing.lines.ifEmpty {
                listOf(
                    ProcessLine(
                        description = receipt.description.ifBlank { receipt.merchant.orEmpty() },
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
                effectiveDate = CardDates.toIso(processing.effectiveDate ?: today),
                lines = lines,
                topUp = if (processing.requestTopUp) TopUpMethod.Restore else TopUpMethod.None,
            )
        }
    }
}

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
