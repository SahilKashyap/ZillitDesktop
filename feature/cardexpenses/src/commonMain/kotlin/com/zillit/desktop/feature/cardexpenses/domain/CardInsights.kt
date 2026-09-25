package com.zillit.desktop.feature.cardexpenses.domain

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import kotlin.math.max

/**
 * The rules behind Top-Up To Do, Analytics, Smart Alerts, Settings and Fund
 * Requests that are worth testing without a screen: the web's sorts, its
 * derived figures and its id-scrubbing, each cited to the line it copies.
 */

// -- top-ups -----------------------------------------------------------------

object TopUpBoard {
    const val ALL = "all"
    const val PENDING = "pending"

    /** The chips, in the web's order and without counts (`TopUpToDoPage.jsx:250-257`). */
    val FILTERS = listOf(ALL, PENDING, "completed", "partial", "skipped")

    /** The web's `filtered`: everything, or the rows in one status. */
    fun filtered(rows: List<CardTopUp>, filter: String): List<CardTopUp> =
        if (filter == ALL) rows else rows.filter { it.status == filter }

    /** Urgent first, then oldest first (`sortPending`, `TopUpToDoPage.jsx:47-55`). */
    fun pending(rows: List<CardTopUp>): List<CardTopUp> =
        rows.filter { it.status == PENDING }
            .sortedWith(compareBy<CardTopUp> { !it.urgent }.thenBy { it.createdAt ?: 0L })

    fun done(rows: List<CardTopUp>): List<CardTopUp> = rows.filter { it.status != PENDING }

    /**
     * What the over-limit explainer says (`TopUpToDoPage.jsx:270-297`): the
     * balance after, how far over, and what could still be applied.
     */
    fun limitAlert(topUp: CardTopUp, amount: Double): TopUpLimitAlert {
        val balance = topUp.cardBalance ?: 0.0
        val limit = topUp.cardLimit ?: 0.0
        val after = round2(balance + amount)
        return TopUpLimitAlert(
            amount = amount,
            balance = balance,
            limit = limit,
            after = after,
            over = round2(after - limit),
            headroom = max(0.0, round2(limit - balance)),
            currency = topUp.currency,
        )
    }
}

/** The numbers the over-limit dialog spells out. */
data class TopUpLimitAlert(
    val amount: Double,
    val balance: Double,
    val limit: Double,
    val after: Double,
    val over: Double,
    val headroom: Double,
    val currency: String?,
)

// -- smart alerts --------------------------------------------------------------

/** The type chips and the backend `type` values each maps to (`SmartAlertsPage.jsx:43-50`). */
enum class AlertFilter(val types: Set<String>?) {
    All(null),
    Anomaly(setOf("Amount Anomaly")),
    Duplicate(setOf("Cross-Card Duplicate")),
    Velocity(setOf("Spending Limit")),
    Merchant(setOf("Overdue Coding", "Missing Amount")),
    Resolved(null),
    ;

    fun apply(alerts: List<CardAlert>): List<CardAlert> = when (this) {
        All -> alerts
        Resolved -> alerts.filter { it.status == CardAlert.RESOLVED }
        else -> alerts.filter { it.type in types.orEmpty() }
    }
}

object AlertText {
    private val objectId = Regex("\\b[a-fA-F0-9]{24}\\b")
    private val spaces = Regex("\\s{2,}")
    private val bareObjectId = Regex("^[a-fA-F0-9]{24}$")
    private val idLike = Regex("^[#ƒ]?[a-fA-F0-9]{6,}$")

    /**
     * The web's `replaceUserIdsInText` (`SmartAlertsPage.jsx:86-99`): every
     * crew id in the prose becomes "Name (Designation)", and any 24-hex id
     * left over becomes [unknown] — the engine bakes ids into its sentences.
     */
    fun scrub(text: String?, people: List<CardPerson>, unknown: String): String {
        if (text.isNullOrEmpty()) return text.orEmpty()
        var out: String = text
        people.forEach { person ->
            if (person.id.isNotEmpty() && out.contains(person.id)) out = out.replace(person.id, personLabel(person))
        }
        return out.replace(objectId, unknown).replace(spaces, " ")
    }

    /** "Name (Designation)", or the name alone. */
    fun personLabel(person: CardPerson): String =
        if (person.designation.isBlank()) person.name else "${person.name} (${person.designation})"

    /** The web's `formatUser`: a directory miss on an ObjectId never prints the id. */
    fun user(id: String?, people: List<CardPerson>, unknownUser: String): String {
        if (id == null) return "—"
        val person = people.firstOrNull { it.id == id } ?: return if (bareObjectId.matches(id)) unknownUser else id
        return personLabel(person)
    }

    /** A hash-like transaction ref (`#6c3e22b`) is an id too, and is hidden (`isIdLike`). */
    fun isIdLike(ref: String?): Boolean = idLike.matches(ref.orEmpty().trim())
}

// -- analytics -----------------------------------------------------------------

/** One week of the cash-flow chart; [actual] is null for a week not yet reached. */
data class CashFlowWeek(val label: String, val actual: Double?, val projected: Double)

/** One production phase of the cost-report impact card. */
data class BudgetPhase(val phase: BudgetPhaseName, val budget: Double, val actual: Double) {
    val percent: Int get() = if (budget > 0) kotlin.math.round(actual / budget * PERCENT).toInt() else 0
    val over: Boolean get() = actual > budget
}

enum class BudgetPhaseName { PreProduction, Production, PostProduction }

/**
 * The web's derived Analytics figures (`AnalyticsPage.jsx:120-148`), copied
 * formula for formula. They are illustrative projections off the two real
 * totals, not a forecast the service computes — kept identical so both
 * clients print the same page.
 */
object AnalyticsMath {
    fun weeks(totalSpend: Double, postedTotal: Double): List<CashFlowWeek> {
        val actual = if (postedTotal > 0) jsRound(postedTotal / THREE) else 0.0
        val forecast = if (totalSpend > 0) jsRound(totalSpend / FOUR) else 0.0
        return listOf(
            CashFlowWeek("W1", actual, forecast),
            CashFlowWeek("W2", jsRound(actual * W2_ACTUAL), jsRound(forecast * W2_FORECAST)),
            CashFlowWeek("W3", jsRound(actual * W3_ACTUAL), jsRound(forecast * W3_FORECAST)),
            CashFlowWeek("W4", null, jsRound(forecast * W4_FORECAST)),
        )
    }

    fun phases(totalSpend: Double, postedTotal: Double): List<BudgetPhase> = listOf(
        BudgetPhase(
            BudgetPhaseName.PreProduction,
            jsRound(totalSpend * PRE_BUDGET).orElse(PRE_FALLBACK),
            jsRound(postedTotal * PRE_ACTUAL),
        ),
        BudgetPhase(
            BudgetPhaseName.Production,
            jsRound(totalSpend * PROD_BUDGET).orElse(PROD_FALLBACK),
            jsRound(totalSpend * PROD_ACTUAL),
        ),
        BudgetPhase(
            BudgetPhaseName.PostProduction,
            jsRound(totalSpend * POST_BUDGET).orElse(POST_FALLBACK),
            jsRound(postedTotal * POST_ACTUAL),
        ),
    )

    /** `Math.round`: half up, towards positive infinity. */
    fun jsRound(value: Double): Double = kotlin.math.floor(value + HALF)

    private fun Double.orElse(fallback: Double): Double = if (this == 0.0) fallback else this

    private const val HALF = 0.5
    private const val THREE = 3.0
    private const val FOUR = 4.0
    private const val W2_ACTUAL = 1.4
    private const val W3_ACTUAL = 1.8
    private const val W2_FORECAST = 1.1
    private const val W3_FORECAST = 1.3
    private const val W4_FORECAST = 1.2
    private const val PRE_BUDGET = 0.5
    private const val PRE_ACTUAL = 0.6
    private const val PROD_BUDGET = 1.8
    private const val PROD_ACTUAL = 0.8
    private const val POST_BUDGET = 0.4
    private const val POST_ACTUAL = 0.2
    private const val PRE_FALLBACK = 12_400.0
    private const val PROD_FALLBACK = 45_000.0
    private const val POST_FALLBACK = 8_000.0
}

// -- settings: host lists and assignment rules ---------------------------------

/** A production company, and the banks it owns (Production Setup → Companies). */
data class CardCompany(val id: String, val name: String, val bankIds: List<String> = emptyList())

/** One department of the production — the web's `useDepartments()` list. */
data class CardDepartment(val id: String, val name: String)

/**
 * One auto-assignment rule filed under `card_expenses`
 * (`SettingsPage.jsx:94-108, 896-1297`). Conditions OR together.
 */
data class CardAssignmentRule(
    val id: String,
    val departments: List<String> = emptyList(),
    val nominalCodes: List<String> = emptyList(),
    /** Blank for no amount condition; the wire takes a number or null. */
    val amountMin: String = "",
    val assignTo: String = "",
    val isActive: Boolean = true,
    val priority: Int = 0,
    /** Whether the server holds this row; a new one carries a local id. */
    val persisted: Boolean = false,
) {
    /** `rule.amountMin || null` — zero is no condition. */
    val amountMinValue: Double? get() = amountMin.trim().replace(",", "").toDoubleOrNull()?.takeIf { it != 0.0 }
}

/**
 * The Account Hub lists and writes the card settings page needs and the card
 * service does not offer: the production's companies and departments, and
 * the hub's assignment-rules routes. A host seam, like [CardAttachmentUploader];
 * the desktop wires it to the hub repository so the HTTP lives in one place.
 */
interface CardHubSource {
    suspend fun companies(): List<CardCompany> = emptyList()

    suspend fun departments(): List<CardDepartment> = emptyList()

    /** Creates [rule] under `card_expenses` when it is not persisted, updates it otherwise. */
    suspend fun saveAssignmentRule(rule: CardAssignmentRule): ZillitResult<CardAssignmentRule> = unavailable()

    suspend fun deleteAssignmentRule(id: String): ZillitResult<Unit> = unavailable()

    /** Nothing wired: the lists are empty and the writes refuse. */
    object None : CardHubSource

    private companion object {
        fun <T> unavailable(): ZillitResult<T> =
            ZillitResult.Failure(ZillitError.Unknown("Assignment rules are not available in this build."))
    }
}

/**
 * The nominal codes the web's rule picker offers (`data/purchase-orders.js:7-23`).
 * A fixed list on the web too — the card settings page does not read the chart.
 */
val CARD_RULE_NOMINALS: List<Pair<String, String>> = listOf(
    "2100" to "Production — General",
    "2200" to "Art Department — Materials",
    "2300" to "Art Department — Props",
    "2400" to "Camera — Equipment Hire",
    "2500" to "Camera — Purchases",
    "2600" to "Costume — Hire",
    "2700" to "Electrical — Equipment",
    "2716" to "Office Stationery",
    "2800" to "Locations — Fees",
    "2900" to "Transport — Vehicle Hire",
    "3000" to "Catering",
    "3100" to "Post Production — Edit",
    "3200" to "Music",
    "4000" to "Travel & Accommodation",
    "5000" to "Miscellaneous",
)

/** Which of the request cap's two blocks applies (`RequestCapSection.jsx:76-78`), or null. */
enum class RequestCapProblem { Amount, Multiplier }

fun RequestCap.problem(): RequestCapProblem? = when {
    !enabled -> null
    basis == RequestCapBasis.WeeklySalary && salaryMultiplier <= 0 -> RequestCapProblem.Multiplier
    maxAmount <= 0 -> RequestCapProblem.Amount
    else -> null
}

/** The company that owns [bankId], if any (`CardProvidersEditor.jsx:131-132`). */
fun List<CardCompany>.ownerOf(bankId: String): CardCompany? =
    bankId.takeIf { it.isNotBlank() }?.let { id -> firstOrNull { id in it.bankIds } }

// -- fund requests ---------------------------------------------------------------

/** One "Fund account" option: a custodian code and every provider that uses it. */
data class CustodianOption(val code: String, val providers: List<String>, val custom: Boolean = false)

/**
 * Bank ⇄ custodian routing for a fund request (`RequestFundsModal.jsx:160-224`).
 *
 * Only banks bound to a provider are offered, and a pick on either side
 * fills the other only when the answer is unambiguous — providers can share
 * a bank, and a guess would post against the wrong code.
 */
object FundRouting {
    fun providerBanks(banks: List<CardBank>, providers: List<CardProvider>): List<CardBank> {
        val ids = providers.map { it.bankId }.filter { it.isNotBlank() }.toSet()
        return banks.filter { it.id in ids }
    }

    fun custodianForBank(bankId: String, providers: List<CardProvider>): String? =
        providers.filter { it.bankId == bankId }
            .map { it.custodianAccount.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .singleOrNull()

    fun bankForCustodian(code: String, providers: List<CardProvider>): String? =
        providers.filter { it.custodianAccount.trim() == code }
            .map { it.bankId }
            .filter { it.isNotBlank() }
            .distinct()
            .singleOrNull()

    /** Deduped by code, the provider names merged onto one option; a typed code is appended. */
    fun options(providers: List<CardProvider>, typed: String, unnamed: String): List<CustodianOption> {
        val byCode = LinkedHashMap<String, MutableList<String>>()
        providers.forEach { provider ->
            val code = provider.custodianAccount.trim()
            if (code.isNotEmpty()) byCode.getOrPut(code) { mutableListOf() } += provider.name.ifBlank { unnamed }
        }
        val options = byCode.map { (code, names) -> CustodianOption(code, names) }
        val code = typed.trim()
        return if (code.isEmpty() || options.any { it.code == code }) {
            options
        } else {
            options + CustodianOption(code, emptyList(), custom = true)
        }
    }

    /** Applies a bank pick to [draft], filling the fund account when one custodian answers. */
    fun pickBank(draft: FundRequestDraft, bankId: String, providers: List<CardProvider>): FundRequestDraft {
        val code = bankId.takeIf { it.isNotBlank() }?.let { custodianForBank(it, providers) }
        return draft.copy(bankId = bankId, fundAccount = code ?: draft.fundAccount)
    }

    /** Applies a fund-account pick, filling the bank when one bank answers. */
    fun pickAccount(draft: FundRequestDraft, typed: String, providers: List<CardProvider>): FundRequestDraft {
        val code = typed.trim()
        val bank = code.takeIf { it.isNotEmpty() }?.let { bankForCustodian(it, providers) }
        return draft.copy(fundAccount = code, bankId = bank ?: draft.bankId)
    }
}

private const val PERCENT = 100.0
