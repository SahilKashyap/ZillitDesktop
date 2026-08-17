package com.zillit.desktop.feature.accounthub.domain

/**
 * The production's legal entities.
 *
 * A company's currency is **not** stored — it is derived from the banks it
 * links. A co-production can straddle currencies legitimately, so the web
 * lists every distinct code its banks span rather than collapsing to one
 * (`CompaniesSection.bankCurrencyCodes`), and the backend owns the canonical
 * value. Sending a currency from here would be inventing one.
 */
data class Company(
    val id: String,
    val name: String = "",
    val country: String = "",
    val countryCode: String = "",
    /**
     * The banks this entity owns.
     *
     * One bank belongs to **at most one** company. The web enforces that at
     * commit time by subtracting the edited company's ids from every sibling;
     * [Companies.linking] does the same here.
     */
    val bankIds: List<String> = emptyList(),
    val taxCredits: List<String> = emptyList(),
)

/** Operations over the whole company list, where the invariant lives. */
object Companies {

    /**
     * [companies] with [bankIds] assigned to [companyId] and taken from
     * everyone else.
     *
     * The subtraction is the point: a bank moved between companies without it
     * ends up owned twice, and the two owners then disagree about which
     * currency the production banks in.
     */
    fun linking(
        companies: List<Company>,
        companyId: String,
        bankIds: List<String>,
    ): List<Company> = companies.map { company ->
        when (company.id) {
            companyId -> company.copy(bankIds = bankIds.distinct())
            else -> company.copy(bankIds = company.bankIds - bankIds.toSet())
        }
    }

    /** Every currency code the company's banks span, in link order. */
    fun currencyCodes(company: Company, banks: List<BankAccount>): List<String> {
        val byId = banks.associateBy { it.id }
        return company.bankIds.mapNotNull { byId[it]?.currencyCode?.takeIf(String::isNotBlank) }
            .distinct()
    }
}

/**
 * One row of `account_hub_bank_accounts` — the table Bank Reconciliation,
 * Vendors and Payroll all read.
 *
 * This module edits the `production` slice of it. Unlike every other Production
 * Setup section, each row is a first-class record with its own REST endpoints,
 * so it saves on its own rather than through a section-level "Save changes".
 */
data class BankAccount(
    val id: String,
    val name: String = "",
    /**
     * The snapshot taken when the bank was linked to a company.
     *
     * Stale once that company is deleted, which is why [holderName] re-derives
     * from the live list instead of trusting it.
     */
    val accountHolderName: String = "",
    /** The company that owns it, when one does. */
    val entityId: String? = null,
    val entityType: String = PRODUCTION,
    val accountNumber: String = "",
    /** Digit-only canonically; see [SortCode]. */
    val sortCode: String = "",
    val swiftCode: String = "",
    val ibanNumber: String = "",
    val nominalCode: String = "",
    val chequeNumber: String = "",
    val wireNumber: String = "",
    val currencyCode: String = "",
    val currencySymbol: String = "",
) {
    /**
     * Who the account is actually held by, given the live companies.
     *
     * Four cases, and the middle two are why this is not just a field read:
     *  - linked to a live company → that company's **current** name, so a
     *    rename shows through;
     *  - linked to a company that has since been deleted → blank, so the card
     *    reads "—" rather than naming an entity that no longer exists;
     *  - companies still loading → the stored snapshot, so the list does not
     *    blank out on every open;
     *  - never linked → the stored name, which was free-typed.
     */
    fun holderName(companies: List<Company>, companiesLoading: Boolean): String {
        val linkedTo = entityId?.takeIf { it.isNotBlank() } ?: return accountHolderName
        if (companiesLoading) return accountHolderName
        return companies.firstOrNull { it.id == linkedTo }?.name.orEmpty()
    }

    companion object {
        const val PRODUCTION = "production"
    }
}

/**
 * UK sort codes: six digits, shown as XX-XX-XX.
 *
 * The hyphens are display only — the stored value is digit-only. Formatting
 * strips first so legacy rows persisted with hyphens render identically to new
 * ones, and both helpers no-op on blank input because non-UK accounts leave the
 * field empty and carry their routing in the IBAN instead.
 */
object SortCode {

    fun digits(value: String): String = value.filter { it.isDigit() }.take(LENGTH)

    /** Formats partial input too, so it can mask a field as it is typed. */
    fun formatted(value: String): String {
        val digits = digits(value)
        return when {
            digits.length <= FIRST -> digits
            digits.length <= SECOND -> "${digits.take(FIRST)}-${digits.drop(FIRST)}"
            else -> "${digits.take(FIRST)}-${digits.substring(FIRST, SECOND)}-${digits.drop(SECOND)}"
        }
    }

    private const val LENGTH = 6
    private const val FIRST = 2
    private const val SECOND = 4
}

/** One currency the production transacts in. */
data class ProjectCurrency(
    val code: String,
    val name: String = "",
    val symbol: String = "",
    /**
     * Rate against the project default. The default's own rate is the base and
     * is pinned to 1.
     */
    val rate: Double? = null,
)

/**
 * Every currency the production transacts in, and the one that pre-fills new
 * transactions everywhere else.
 *
 * [defaultCode] is nullable because a fresh project has currencies before it
 * has a default, and picking one silently would put a wrong currency on the
 * first purchase order somebody raises.
 */
data class CurrencySettings(
    val currencies: List<ProjectCurrency> = emptyList(),
    val defaultCode: String? = null,
) {
    val default: ProjectCurrency? get() = currencies.firstOrNull { it.code == defaultCode }

    /** Drops a currency, clearing the default when that is what was dropped. */
    fun without(code: String): CurrencySettings = CurrencySettings(
        currencies = currencies.filterNot { it.code == code },
        defaultCode = defaultCode?.takeIf { it != code },
    )

    /**
     * The form the server will actually store.
     *
     * ## Every rate must be a number
     *
     * A currency sent with `exr: null` is **silently dropped**: the service
     * answers `status: 1` with an empty list, so the save reports success and
     * nothing persists. Verified against dev on 2026-08-12 — a GBP row with a
     * null rate came back as `{"currencies":[],"default":null}`.
     *
     * The catalogue this app picks from carries no rate, so without this every
     * first currency a production adds would vanish on save.
     *
     * The default's rate is pinned to 1 because it *is* the base every other
     * rate is quoted against — storing anything else there would make the
     * production's own currency worth some multiple of itself.
     */
    fun forWire(): CurrencySettings = copy(
        currencies = currencies.map { currency ->
            when {
                currency.code == defaultCode -> currency.copy(rate = BASE_RATE)
                // Not "correct", but persistable and visible: a currency held
                // back because its rate is unknown cannot be corrected later,
                // whereas one stored at parity can.
                currency.rate == null -> currency.copy(rate = BASE_RATE)
                else -> currency
            }
        },
    )

    companion object {
        /** The base every other rate is quoted against. */
        const val BASE_RATE = 1.0
    }
}

/**
 * One selectable tax rate.
 *
 * ## The identifier is plumbing, never shown
 *
 * Rates imported from a country's catalogue are keyed `{CC}_{sourceId}` —
 * `GB_standard` — so a reload can tell which of the catalogue's checkboxes were
 * ticked. Custom rates get an opaque `custom_N`. Neither is surfaced: an
 * accountant picks "Standard rate 20%", not a key.
 */
data class TaxType(
    val type: String = "",
    val identifier: String = "",
    val label: String = "",
    val value: String = "",
    /** Reclaimable input tax. Absent means false — only an explicit true counts. */
    val isRecoverable: Boolean = false,
    /** The chart-of-accounts code this posts to, when one is set. */
    val nominal: String = "",
) {
    /** The originating country, for a rate imported from a catalogue. */
    val countryCode: String? get() = COUNTRY_KEY.matchEntire(identifier)?.groupValues?.get(1)

    val isCustom: Boolean get() = countryCode == null

    companion object {
        private val COUNTRY_KEY = Regex("^([A-Z]{2})_(.+)$")

        fun keyFor(countryCode: String, sourceId: String): String = "${countryCode}_$sourceId"

        /**
         * The next free `custom_N`.
         *
         * Derived from what is already there rather than from a counter, so
         * two sessions editing the same project cannot both mint `custom_3`.
         */
        fun nextCustomIdentifier(existing: List<TaxType>): String {
            val used = existing.mapNotNull { row ->
                row.identifier.removePrefix(CUSTOM_PREFIX).toIntOrNull()
                    ?.takeIf { row.identifier.startsWith(CUSTOM_PREFIX) }
            }
            return "$CUSTOM_PREFIX${(used.maxOrNull() ?: 0) + 1}"
        }

        private const val CUSTOM_PREFIX = "custom_"
    }
}

/** A country's standard rates, from the shared preset catalogue. */
data class CountryTaxes(
    val country: String,
    val countryCode: String,
    val taxes: List<TaxType> = emptyList(),
)

/** The production's overall budget, as a single figure. */
data class ProjectBudget(val amount: Double? = null, val currency: String = "")

/** A payroll bureau the production uses. */
data class PayrollBureau(val id: String, val title: String = "", val description: String = "")

/** One clause of the standard deal, in the order it is presented. */
data class DealCondition(val id: String, val order: Int = 0, val condition: String = "")

/** Prep, shoot and wrap, each a date range. */
data class SchedulePhase(val startDate: Long? = null, val endDate: Long? = null) {
    val isSet: Boolean get() = startDate != null || endDate != null
}

/**
 * The production's dates, which pre-fill a new deal memo's structure step.
 *
 * Sent as epoch millis. The read side also tolerates `YYYY-MM-DD` strings,
 * because rows written before the change are still on the project.
 */
data class ProductionSchedule(
    val startDate: Long? = null,
    val endDate: Long? = null,
    val prep: SchedulePhase = SchedulePhase(),
    val shoot: SchedulePhase = SchedulePhase(),
    val wrap: SchedulePhase = SchedulePhase(),
)

/**
 * What happens to a deal once it is signed.
 *
 * All three are required on write — the server seeds a fresh project to
 * `(true, true, false)`, so a partial patch would read as turning something
 * off rather than leaving it alone.
 */
data class PayrollDefaults(
    val autoSync: Boolean = true,
    val notifyPayroll: Boolean = true,
    val includePdf: Boolean = false,
)

/**
 * A named working day, and the two figures that drive pay.
 *
 * Non-union only on this side: a union deal takes its day types from the
 * agreement, and offering project-level ones there would quietly override a
 * negotiated term.
 */
data class DayType(
    val dayType: String,
    val label: String = "",
    /** Anything worked beyond this is overtime. */
    val workMinutes: Int? = null,
    /**
     * Drives the too-short-break penalty.
     *
     * Null and zero mean different things — unspecified versus an explicit "no
     * formal break" — so this must not be defaulted to 0 on read.
     */
    val mealBreakMinutes: Int? = null,
    val note: String = "",
)
