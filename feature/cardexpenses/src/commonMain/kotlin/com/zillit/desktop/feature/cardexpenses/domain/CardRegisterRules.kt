package com.zillit.desktop.feature.cardexpenses.domain

import com.zillit.desktop.core.common.MessageElement

/**
 * The register's own rules — search, the approval-chain walk, the tile badge,
 * the money a mixed-currency dashboard adds up, the request cap and the
 * sixteen-digit card number — each ported from the one web file that defines
 * it, so the tiles, the detail page and the forms cannot disagree.
 */
object CardSearch {

    /**
     * Whether a card matches the register's search box (`lib/cardSearch.js`).
     *
     * The **holder** the tile prints — name, designation, department — and the
     * card fields the tile prints: provider, last four, control code. Never the
     * card's own department id: an ObjectId substring-matched pulled arbitrary
     * cards into every search (ZL-20589, reopened).
     */
    fun matches(card: ExpenseCard, query: String, holder: CardPerson?, providerName: String?): Boolean {
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) return true
        return listOf(
            holder?.name,
            holder?.designation,
            holder?.department,
            providerName,
            card.lastFour,
            card.bsControlCode,
        ).any { it?.lowercase()?.contains(needle) == true }
    }
}

/** Where one level of a card's chain stands (`lib/approvalChainSteps.js`). */
enum class ChainStepStatus { Approved, Current, Waiting }

/** One level of the chain, as the tile strip and the detail panel draw it. */
data class CardChainStep(
    val tier: Int,
    val status: ChainStepStatus,
    /** Who signed it; set on an approved step. */
    val approverId: String? = null,
    val approvedAt: Long? = null,
    /** Who may sign it; set on a current or waiting step. */
    val approverIds: List<String> = emptyList(),
)

object CardChain {

    /**
     * The chain's steps for [card]: level N approved when a sign-off carries
     * its tier, current when it is the lowest unsigned level whose predecessor
     * is signed, waiting otherwise. Empty with no chain configured.
     */
    fun steps(card: ExpenseCard, configs: List<TierConfig>): List<CardChainStep> {
        val chain = ApprovalTiers.resolve(configs, card.departmentId, card.monthlyLimit) ?: return emptyList()
        val signed = card.approvals.map { it.tierNumber }.toSet()
        return chain.mapIndexed { index, approvers ->
            val tier = index + 1
            val approval = card.approvals.firstOrNull { it.tierNumber == tier }
            when {
                approval != null -> CardChainStep(
                    tier = tier,
                    status = ChainStepStatus.Approved,
                    approverId = approval.userId,
                    approvedAt = approval.approvedAt,
                )

                tier == 1 || (tier - 1) in signed ->
                    CardChainStep(tier, ChainStepStatus.Current, approverIds = approvers)

                else -> CardChainStep(tier, ChainStepStatus.Waiting, approverIds = approvers)
            }
        }
    }

    /**
     * No level-1 approver for the card's department — the web's
     * `hasTier1ApproverForDept` over the resolved chain. The detail page shows
     * "Set Approval Level" instead of a chain nobody can sign.
     */
    fun needsApprovalLevel(card: ExpenseCard, configs: List<TierConfig>): Boolean =
        ApprovalTiers.resolve(configs, card.departmentId, card.monthlyLimit).isNullOrEmpty()
}

/** What the tile's badge says, before it is worded (`adminUi.jsx:117-185`). */
sealed interface CardBadge {
    data object DigitalActive : CardBadge
    data class PendingTiers(val signed: Int, val total: Int) : CardBadge
    data object InProgress : CardBadge
    data class Plain(val status: CardStatus) : CardBadge

    companion object {
        /**
         * "Digital Active" for a live card with only a virtual number; "Pending
         * (n/N)" once a pending card's chain is known; "In-Progress" for an
         * approved card, and for an overridden one when the viewer is not an
         * accountant; the status's own label otherwise.
         */
        fun of(card: ExpenseCard, totalTiers: Int, isAccountant: Boolean): CardBadge = when {
            card.isDigitalActive -> DigitalActive
            card.status == CardStatus.Pending && totalTiers > 0 -> PendingTiers(card.approvals.size, totalTiers)
            card.status == CardStatus.Approved -> InProgress
            card.status == CardStatus.Override && !isAccountant -> InProgress
            else -> Plain(card.status)
        }
    }
}

/**
 * The production's money reference: its default currency and the others it
 * selected, their exchange rates, and the companies and banks card providers
 * bind. A host seam — these are the hub's Production Setup documents.
 */
data class CardReference(
    val defaultCurrency: String = "",
    val currencies: List<CardCurrency> = emptyList(),
    val companies: List<CardCompany> = emptyList(),
    val banks: List<CardBank> = emptyList(),
) {
    /** The picker's options: the selected currencies, the default always among them. */
    fun currencyOptions(ensure: String? = null): List<String> =
        (listOf(defaultCurrency) + currencies.map { it.code } + listOfNotNull(ensure))
            .map { it.trim().uppercase() }
            .filter { it.isNotEmpty() }
            .distinct()

    /** A rate against the default; null when none is known (`exrForCode`). */
    fun rate(code: String): Double? =
        currencies.firstOrNull { it.code.equals(code, ignoreCase = true) }?.rate?.takeIf { it > 0 }

    /**
     * Whether [code] converts to the default: the default itself, a rated
     * currency — or anything, while no default is known (`rateMissing` needs one).
     */
    fun convertible(code: String?): Boolean {
        val from = code?.trim()?.uppercase().orEmpty()
        val default = defaultCurrency.trim().uppercase()
        return from.isEmpty() || default.isEmpty() || from == default || rate(from) != null
    }

    /** [amount] in [code], in the default; face value where no rate is known. */
    fun toDefault(amount: Double, code: String?): Double {
        val from = code?.trim()?.uppercase().orEmpty()
        val default = defaultCurrency.trim().uppercase()
        if (from.isEmpty() || default.isEmpty() || from == default) return amount
        return rate(from)?.let { amount / it } ?: amount
    }

    /**
     * The web's `describeConvertedTotal`: one currency keeps its own total, a
     * mix converts to the default through the project rates, flagging any
     * amount added at face value for want of a rate.
     */
    fun total(rows: List<Pair<Double, String?>>): ConvertedTotal {
        val default = defaultCurrency.uppercase()
        val codes = rows.map { (_, code) -> code?.trim()?.uppercase()?.ifEmpty { null } ?: default }
            .filter { it.isNotEmpty() }
            .distinct()
        if (codes.size <= 1) {
            return ConvertedTotal(rows.sumOf { it.first }, codes.firstOrNull() ?: default, mixed = false)
        }
        var unrated = false
        val sum = rows.sumOf { (amount, code) ->
            if (!convertible(code)) unrated = true
            toDefault(amount, code)
        }
        return ConvertedTotal(sum, default, mixed = true, unrated = unrated, codes = codes)
    }
}

/** One of the production's currencies; [rate] is against the default. */
data class CardCurrency(val code: String, val rate: Double? = null)

/** A money total, and whether it was converted to get there. */
data class ConvertedTotal(
    val amount: Double,
    val currency: String,
    val mixed: Boolean,
    val unrated: Boolean = false,
    val codes: List<String> = emptyList(),
)

/** Where a resolved cap came from (`requestCap.js resolveRequestCap`). */
enum class CapSource { Max, Weekly, Fallback }

/** A requester's weekly rate on their active deal, in its own currency. */
data class DealRate(val weekly: Double, val currency: String?)

/** A cap, resolved: its amount (null = no cap), its source and its currency (null = the default). */
data class ResolvedCap(val amount: Double?, val source: CapSource, val currency: String?)

/** What the guard says about a proposed limit. */
sealed interface CapVerdict {
    data object NoCap : CapVerdict

    /** A cap exists but one side cannot be converted; never blocks. */
    data class Unenforceable(val currency: String) : CapVerdict

    data class Enforced(val capInDefault: Double, val source: CapSource) : CapVerdict
}

/**
 * The request cap, applied (`useRequestCapGuard.js`).
 *
 * Never blocks on uncertainty: no cap, no deal, or a currency without a rate
 * all let the request through — the server enforces nothing, and a wrong
 * block stops a legitimate request an approver would still catch.
 */
object RequestCapGuard {

    fun resolve(cap: RequestCap?, deal: DealRate?): ResolvedCap {
        if (cap == null || !cap.enabled) return ResolvedCap(null, CapSource.Max, null)
        if (cap.basis == RequestCapBasis.WeeklySalary) {
            val multiplier = cap.salaryMultiplier
            if (deal != null && deal.weekly > 0 && multiplier > 0) {
                return ResolvedCap(deal.weekly * multiplier, CapSource.Weekly, deal.currency)
            }
            return ResolvedCap(cap.maxAmount.takeIf { it > 0 }, CapSource.Fallback, null)
        }
        return ResolvedCap(cap.maxAmount.takeIf { it > 0 }, CapSource.Max, null)
    }

    fun verdict(cap: RequestCap?, deal: DealRate?, requestCurrency: String?, reference: CardReference): CapVerdict {
        val resolved = resolve(cap, deal)
        val amount = resolved.amount ?: return CapVerdict.NoCap
        val capCurrency = resolved.currency
        if (!reference.convertible(capCurrency)) return CapVerdict.Unenforceable(capCurrency.orEmpty())
        if (!reference.convertible(requestCurrency)) return CapVerdict.Unenforceable(requestCurrency.orEmpty())
        return CapVerdict.Enforced(reference.toDefault(amount, capCurrency), resolved.source)
    }

    /** Whether [limit] in [requestCurrency] is over an enforced cap. */
    fun exceeds(verdict: CapVerdict, limit: Double, requestCurrency: String?, reference: CardReference): Boolean =
        verdict is CapVerdict.Enforced && reference.toDefault(limit, requestCurrency) > verdict.capInDefault
}

/** A card number as typed: digits only, at most sixteen (`CardRegisterPage.jsx:1350`). */
object CardNumbers {
    const val LENGTH = 16
    private const val GROUP = 4

    fun digits(typed: String): String = typed.filter(Char::isDigit).take(LENGTH)

    /** `1234 5678 9012 3456`. */
    fun grouped(digits: String): String = digits.chunked(GROUP).joinToString(" ")

    fun remaining(digits: String): Int = LENGTH - digits.length

    /** `•••• •••• •••• 3456`, or null with no number. */
    fun masked(number: String?): String? =
        number?.filter(Char::isDigit)?.takeIf { it.isNotEmpty() }?.let { "•••• •••• •••• ${it.takeLast(GROUP)}" }
}

/**
 * A card write the register and the cardholder's Card tab make — one type so
 * the repository can answer each with the server's own message, which the web
 * shows in its toasts (`showApiSuccess`).
 */
sealed interface CardAction {
    val cardId: String?

    data class Request(val request: NewCardRequest) : CardAction {
        override val cardId: String? get() = null
    }

    /** A crew member's own request: `{user_id, department_id, proposed_limit, justification, currency}`. */
    data class CrewRequest(
        val userId: String,
        val departmentId: String?,
        val proposedLimit: Double,
        val justification: String,
        val currency: String,
    ) : CardAction {
        override val cardId: String? get() = null
    }

    data class EditDetails(override val cardId: String, val edit: CardDetailsEdit, val updatedBy: String) : CardAction

    /** A crew member re-submitting their own request (`UserCardsPage.jsx:249-254`). */
    data class CrewEdit(
        override val cardId: String,
        val limit: Double,
        val justification: String,
        val currency: String,
    ) : CardAction

    data class BsCode(override val cardId: String, val code: String, val updatedBy: String) : CardAction
    data class Delete(override val cardId: String) : CardAction
    data class Approve(override val cardId: String, val step: TierVisibility, val userId: String) : CardAction
    data class Reject(override val cardId: String, val reason: String, val userId: String) : CardAction
    data class Override(override val cardId: String, val reason: String, val userId: String) : CardAction
    data class Activate(override val cardId: String, val activation: CardActivation) : CardAction
    data class Suspend(override val cardId: String) : CardAction
    data class Reactivate(override val cardId: String) : CardAction
    data class AssignPhysical(override val cardId: String, val number: String) : CardAction
}

/** The server's own words for a write: a message key and its placeholders. */
data class CardServerNote(val key: String? = null, val elements: List<MessageElement> = emptyList())

/**
 * The web's `formatCompact`: `1.25K`, `150K`, `2.9M`, `1.05B` — two places,
 * trailing zeros dropped; under a thousand, the figure to two places.
 */
object CompactAmount {
    private const val THOUSAND = 1_000.0
    private const val MILLION = 1_000_000.0
    private const val BILLION = 1_000_000_000.0
    private const val TRILLION = 1_000_000_000_000.0
    private const val HUNDRED = 100.0

    fun of(amount: Double): String {
        if (amount.isNaN() || amount.isInfinite()) return "0"
        val size = kotlin.math.abs(amount)
        return when {
            size >= TRILLION -> trimmed(amount / TRILLION) + "T"
            size >= BILLION -> trimmed(amount / BILLION) + "B"
            size >= MILLION -> trimmed(amount / MILLION) + "M"
            size >= THOUSAND -> trimmed(amount / THOUSAND) + "K"
            else -> trimmed(amount)
        }
    }

    private fun trimmed(value: Double): String {
        val rounded = kotlin.math.round(value * HUNDRED) / HUNDRED
        val whole = rounded.toLong()
        return if (rounded == whole.toDouble()) whole.toString() else rounded.toString()
    }
}
