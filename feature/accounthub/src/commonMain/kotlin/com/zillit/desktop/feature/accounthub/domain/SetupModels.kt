package com.zillit.desktop.feature.accounthub.domain

import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str

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
    /** The registered name, when it differs from the trading one. */
    val legalName: String = "",
    /**
     * The UK payroll references — PAYE and Accounts Office.
     *
     * Sent as one `uk` block on every save, blanks included: the web writes
     * the whole list back, and a company whose block was left out of the body
     * had both references silently cleared (`makeCompanyDraft`).
     */
    val ukPayeRef: String = "",
    val ukAccountsOfficeRef: String = "",
    /**
     * The workplace pension (backend 2026-09-12), in the same `uk` block.
     *
     * Free text, so never trimmed per keystroke — a provider carries spaces
     * ("Legal & General"); trimmed once on the way out instead.
     */
    val ukPensionProvider: String = "",
    val ukPensionSchemeRef: String = "",
) {
    /** The first two letters of the name — the card's monogram, as the web draws it. */
    val monogram: String
        get() = name.filter { it.isLetter() }.take(2).uppercase().ifBlank { "?" }

    /**
     * Whether the company files UK payroll, so the PAYE block applies.
     *
     * Case-blind: stored country codes are not guaranteed uppercase, and a
     * strict match would hide the references of a company saved as "gb".
     */
    val isUk: Boolean get() = countryCode.equals(UK, ignoreCase = true)

    companion object {
        const val UK = "GB"
    }
}

/**
 * The UK employer references a company carries — the web's `ukPayrollFields`.
 *
 * Neither is required: a production company can legitimately not have its
 * PAYE reference yet. What blocks is a reference that is present and
 * malformed — presence is optional, correctness is not.
 */
object UkPayrollRefs {
    /** The server caps both refs here and rejects longer ones outright. */
    const val REF_MAX = 20
    const val PENSION_PROVIDER_MAX = 200
    const val PENSION_SCHEME_MAX = 50

    private val PAYE = Regex("^\\d{3}/[A-Za-z0-9]{1,10}$")
    private val ACCOUNTS_OFFICE = Regex("^\\d{3}P[A-Za-z]\\d{8}$")

    const val PAYE_ERROR = "Use the format 123/AB456."
    const val ACCOUNTS_OFFICE_ERROR = "Use the format 123PA00012345."

    /** True when something was typed and it does not parse. */
    fun isPayeInvalid(value: String): Boolean = value.trim().let { it.isNotEmpty() && !PAYE.matches(it) }

    fun isAccountsOfficeInvalid(value: String): Boolean =
        value.trim().let { it.isNotEmpty() && !ACCOUNTS_OFFICE.matches(it) }
}

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

    /**
     * The banks a company owns, read from **both** sides of the link.
     *
     * The link is stored twice and only one side is written by the bank
     * editor: it persists the holder as `entity_id` on the bank, while the
     * company row carries `bank_ids`. Counting only the company's list left
     * a bank created from the Bank Accounts section reading "0 accounts" on
     * its company (ZL-20605). `entity_id` is the authoritative pointer;
     * `bank_ids` stands in for rows saved before it existed.
     */
    fun linkedBankIds(company: Company, banks: List<BankAccount>): List<String> {
        val owned = banks.filter { it.entityId == company.id }.map { it.id }
        val legacy = company.bankIds.filter { id -> banks.none { it.id == id && !it.entityId.isNullOrBlank() } }
        return (owned + legacy).distinct()
    }

    /** Every currency code the company's banks span, in link order. */
    fun currencyCodes(company: Company, banks: List<BankAccount>): List<String> =
        currencyCodes(linkedBankIds(company, banks), banks)

    fun currencyCodes(bankIds: List<String>, banks: List<BankAccount>): List<String> {
        val byId = banks.associateBy { it.id }
        return bankIds.mapNotNull { byId[it]?.currencyCode?.takeIf(String::isNotBlank) }.distinct()
    }

    /**
     * Why the editor refuses to commit, or null when it may — the web's own
     * gate: a name, a country, and no malformed UK reference. The UK checks
     * apply only to a UK company: one moved elsewhere keeps its block, and
     * an ungated check would refuse over a field the person can no longer see.
     */
    fun problem(draft: Company): String? = when {
        draft.name.isBlank() -> str(S.desktop_hub_give_the_company_a_name)
        draft.country.isBlank() -> str(S.desktop_hub_pick_the_companys_country)
        draft.isUk && UkPayrollRefs.isPayeInvalid(draft.ukPayeRef) -> UkPayrollRefs.PAYE_ERROR
        draft.isUk && UkPayrollRefs.isAccountsOfficeInvalid(draft.ukAccountsOfficeRef) ->
            UkPayrollRefs.ACCOUNTS_OFFICE_ERROR
        else -> null
    }

    /**
     * The list as the server should receive it.
     *
     * Bank ids that no longer resolve are dropped, and a blank legal name
     * falls back to the trading one: this PATCH rewrites every row, so a
     * company nobody has opened since the field shipped would otherwise keep
     * a blank forever while being rewritten on every save.
     */
    fun forWire(companies: List<Company>, banks: List<BankAccount>): List<Company> {
        val known = banks.map { it.id }.toSet()
        return companies.filter { it.name.isNotBlank() }.map { company ->
            company.copy(
                name = company.name.trim(),
                legalName = company.legalName.trim().ifBlank { company.name.trim() },
                bankIds = company.bankIds.filter { it in known },
                taxCredits = company.taxCredits.map { it.trim() }.filter { it.isNotEmpty() },
            )
        }
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
    val currencyName: String = "",
    /** The clearing code accounts payable posts through. Accountants must set one. */
    val apClearanceNominalCode: String = "",
    /**
     * Free-form typed rows — a routing number, a branch reference.
     *
     * Stored serialised on the row; see [BankDetail]. Untitled rows are dropped
     * on save rather than persisted as blanks.
     */
    val additionalDetails: List<BankDetail> = emptyList(),
) {
    /** The last four digits, for the masked card. */
    val accountLast4: String get() = accountNumber.takeLast(MASK_TAIL)

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
        private const val MASK_TAIL = 4
    }
}

/**
 * The type a typed extra detail is checked against.
 *
 * Soft on the web — a red border, never a block — until save, when the first
 * malformed titled row refuses the save (`firstInvalidDetail`). Text and phone
 * always pass; an empty value always passes.
 */
enum class BankDetailType(val wire: String, private val labelKey: String) {
    Text("text", S.docusign_field_text),
    Number("number", S.docusign_number_value_hint),
    Phone("phone", S.phone),
    Email("email", S.email),
    Url("url", S.docusign_field_url),
    ;

    val label: String get() = str(labelKey)

    /** Whether [value] is well-formed for this type. Blank always is. */
    fun accepts(value: String): Boolean {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return true
        return when (this) {
            Text, Phone -> true
            Number -> trimmed.replace(",", "").toDoubleOrNull() != null
            Email -> EMAIL.matches(trimmed)
            Url -> URL.matches(trimmed)
        }
    }

    companion object {
        val Default = Text
        private val EMAIL = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")
        private val URL = Regex("^(https?://)?[\\w.-]+\\.[a-zA-Z]{2,}(/.*)?$")

        fun from(wire: String?): BankDetailType = entries.firstOrNull { it.wire == wire } ?: Default
    }
}

/**
 * One typed extra field on a bank record — `{ field, value, field_type }`.
 *
 * The web's two-phase editor: a row is *defined* (given a title and a type)
 * and then *filled*. Only titled rows persist, so a row that was started and
 * abandoned cannot block a save.
 */
data class BankDetail(
    /** The row's title — `field` on a bank's wire, `label` on a vendor's. */
    val title: String = "",
    val value: String = "",
    val fieldType: BankDetailType = BankDetailType.Default,
) {
    val isTitled: Boolean get() = title.isNotBlank()

    val isValid: Boolean get() = fieldType.accepts(value)
}

/** The rules the bank editor applies before a row goes to the server. */
object BankAccounts {

    /** The titled rows, typed — what is persisted. */
    fun persistable(details: List<BankDetail>): List<BankDetail> = details.filter { it.isTitled }

    /** The first titled row whose value is malformed for its type, or null. */
    fun firstInvalidDetail(details: List<BankDetail>): BankDetail? =
        persistable(details).firstOrNull { !it.isValid }

    /**
     * Whether another row already carries this account number.
     *
     * Compared normalised — spaces stripped, case-folded — so "12 34 56 78"
     * and "12345678" collide the way a person reads them.
     */
    fun duplicateNumber(draft: BankAccount, banks: List<BankAccount>): Boolean {
        val number = normalisedNumber(draft.accountNumber)
        if (number.isEmpty()) return false
        return banks.any { it.id != draft.id && normalisedNumber(it.accountNumber) == number }
    }

    private fun normalisedNumber(value: String) = value.filterNot { it.isWhitespace() }.lowercase()

    /**
     * Digits only, applied to what is typed — never to a stored value, so a
     * legacy non-digit number is shown and round-tripped untouched.
     */
    fun typedAccountNumber(value: String): String = value.filter { it.isDigit() }

    /**
     * A nominal code the chart does not know, wrapped as `[[code]]`.
     *
     * The web's `wrapNominal`: a code typed free-hand rather than picked from
     * the chart is marked so the ledger can tell a resolved code from a
     * placeholder. Case-blind, because the chart's own lookup is.
     */
    fun wrapNominal(code: String, knownCodes: Collection<String>): String {
        val trimmed = code.trim()
        if (trimmed.isEmpty()) return trimmed
        if (trimmed.startsWith("[[") && trimmed.endsWith("]]")) return trimmed
        return if (knownCodes.any { it.equals(trimmed, ignoreCase = true) }) trimmed else "[[$trimmed]]"
    }

    /**
     * Why the editor refuses to save, or null when it may — the web's own
     * order and wording (`BankAccountFormModal`).
     */
    @Suppress("CyclomaticComplexMethod") // A screen, read top to bottom; the order is the reading order.
    fun validationError(
        draft: BankAccount,
        banks: List<BankAccount>,
        companies: List<Company>,
        accountant: Boolean,
    ): String? = when {
        draft.name.isBlank() -> str(S.ah_err_bank_name_required)
        draft.entityId.isNullOrBlank() && draft.accountHolderName.isBlank() ->
            str(S.desktop_hub_account_holder_company_is_required)
        !draft.entityId.isNullOrBlank() && companies.none { it.id == draft.entityId } ->
            str(S.desktop_hub_account_holder_company_is_required)
        draft.accountNumber.isBlank() -> str(S.ah_err_account_number_required)
        duplicateNumber(draft, banks) -> str(S.desktop_hub_an_account_with_this_number_already_exists)
        accountant && draft.nominalCode.isBlank() -> str(S.desktop_hub_bank_account_nominal_code_is_required)
        accountant && draft.apClearanceNominalCode.isBlank() -> str(S.desktop_hub_ap_clearance_nominal_code_is_required)
        draft.currencyCode.isBlank() -> str(S.ah_err_currency_required)
        firstInvalidDetail(draft.additionalDetails) != null ->
            str(
                S.desktop_hub_x_is_not_a_valid_y,
                firstInvalidDetail(draft.additionalDetails)?.title,
                firstInvalidDetail(draft.additionalDetails)?.fieldType?.label?.lowercase(),
            )
        else -> null
    }

    /** `•••• 1234` for anything longer than four characters; short values show whole. */
    fun masked(value: String): String {
        val trimmed = value.trim()
        return if (trimmed.length > MASK_TAIL) "•••• ${trimmed.takeLast(MASK_TAIL)}" else trimmed
    }

    private const val MASK_TAIL = 4
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
    /** The issuing country, from the catalogue; blank on a stored row. */
    val country: String = "",
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

    /**
     * Non-default currencies with no positive rate.
     *
     * The web refuses the save while any remain — "Add an exchange rate for
     * X, Y before saving." — because a rate of 1 on a currency that is not the
     * base is a conversion that quietly reports the wrong figure.
     */
    val missingRates: List<String>
        get() = currencies.filter { it.code != defaultCode && (it.rate == null || it.rate <= 0.0) }
            .map { it.code }

    /** Why the section cannot be saved, or null when it can. */
    fun validationError(): String? =
        missingRates.takeIf { it.isNotEmpty() }
            ?.let { str(S.desktop_hub_add_an_exchange_rate_for_x_before_saving, it.joinToString(", ")) }

    /**
     * Drops a currency. Dropping the default hands it to the first currency
     * left, as the web does — a list with currencies and no default would
     * pre-fill nothing on the next purchase order.
     */
    fun without(code: String): CurrencySettings {
        val remaining = currencies.filterNot { it.code == code }
        return CurrencySettings(
            currencies = remaining,
            defaultCode = if (defaultCode == code) remaining.firstOrNull()?.code else defaultCode,
        )
    }

    /** Adds a currency; the first one added becomes the default. */
    fun with(currency: ProjectCurrency): CurrencySettings =
        if (currencies.any { it.code == currency.code }) {
            this
        } else {
            copy(currencies = currencies + currency, defaultCode = defaultCode ?: currency.code)
        }

    /** Adds the currency when absent, drops it when present — the catalogue tile's click. */
    fun toggled(currency: ProjectCurrency): CurrencySettings =
        if (currencies.any { it.code == currency.code }) without(currency.code) else with(currency)

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

        /** The reserve currencies behind the picker's "Major" filter chip. */
        val MAJOR_CODES: Set<String> = setOf("USD", "EUR", "GBP", "JPY", "CHF", "CAD", "AUD", "CNY")
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
    /** The country's name, for a catalogue rate; blank for a custom one. */
    val country: String = "",
    /** The stored `country_code`, when the row carries one. Null on custom rows. */
    val storedCountryCode: String? = null,
) {
    /**
     * The originating country, for a rate imported from a catalogue.
     *
     * The stored code where the row has one; otherwise parsed out of the
     * identifier, which is how rows saved before the column existed are read.
     */
    val countryCode: String?
        get() = storedCountryCode?.takeIf { it.isNotBlank() }
            ?: COUNTRY_KEY.matchEntire(identifier)?.groupValues?.get(1)

    val isCustom: Boolean get() = countryCode == null

    /** The rate as a number, or null while the field is blank or half-typed. */
    val rate: Double? get() = value.trim().removeSuffix("%").toDoubleOrNull()

    companion object {
        private val COUNTRY_KEY = Regex("^([A-Z]{2})_(.+)$")

        const val RATE_MIN = 0.0
        const val RATE_MAX = 100.0

        /** Whether a typed rate sits outside 0–100. Blank is not out of range. */
        fun isRateOutOfRange(value: String): Boolean {
            val rate = value.trim().removeSuffix("%").toDoubleOrNull() ?: return value.isNotBlank()
            return rate < RATE_MIN || rate > RATE_MAX
        }

        /** The web's refusal, or null when every rate is in range. */
        fun problem(rows: List<TaxType>): String? =
            if (rows.any { isRateOutOfRange(it.value) }) {
                str(S.desktop_hub_tax_rate_must_be_between_x_and_y_percent, RATE_MIN.toInt(), RATE_MAX.toInt())
            } else {
                null
            }

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
    /**
     * Named overlays on the schedule — Night Shoot, Second Unit.
     *
     * Deliberately no overlap rule between them: an overlay runs inside the
     * shoot by design. Saved as the full list every time (`custom_days`).
     */
    val customDays: List<CustomDay> = emptyList(),
) {
    val isSet: Boolean get() = startDate != null || endDate != null
}

/** One named overlay on the schedule. [id] is local — the wire carries none. */
data class CustomDay(
    val id: String = "",
    val name: String = "",
    val startDate: Long? = null,
    val endDate: Long? = null,
)

/**
 * The schedule's rules, in the web's words (`ProductionScheduleSection.computeErrors`).
 *
 * Keyed by the phase they belong to so each row can show its own; the custom
 * rows are keyed by their local id.
 */
object ScheduleRules {
    const val OVERALL = "overall"
    const val PREP = "prep"
    const val SHOOT = "shoot"
    const val WRAP = "wrap"

    @Suppress("CyclomaticComplexMethod", "LongMethod") // One line per rule; the list IS the contract.
    fun errors(schedule: ProductionSchedule): Map<String, List<String>> {
        val out = mutableMapOf<String, MutableList<String>>()
        fun add(key: String, message: String) = out.getOrPut(key) { mutableListOf() }.add(message)
        val ds = schedule.startDate
        val de = schedule.endDate
        val (ps, pe) = schedule.prep.startDate to schedule.prep.endDate
        val (ss, se) = schedule.shoot.startDate to schedule.shoot.endDate
        val (ws, we) = schedule.wrap.startDate to schedule.wrap.endDate

        if (ds != null && de != null && de < ds) add(OVERALL, END_BEFORE_START)
        if (ps != null && pe != null && pe < ps) add(PREP, END_BEFORE_START)
        if (ss != null && se != null && se < ss) add(SHOOT, END_BEFORE_START)
        if (ws != null && we != null && we < ws) add(WRAP, END_BEFORE_START)

        if (pe != null && ss != null && ss <= pe) add(SHOOT, str(S.dm_ds_phase_err_overlap_prep))
        if (se != null && ws != null && ws <= se) add(WRAP, str(S.dm_ds_phase_err_overlap_shoot))

        if (ds != null) {
            if (ps != null && ps < ds) add(PREP, STARTS_BEFORE)
            if (ps == null && ss != null && ss < ds) add(SHOOT, STARTS_BEFORE)
        }
        if (de != null) {
            if (we != null && we > de) add(WRAP, ENDS_AFTER)
            if (we == null && se != null && se > de) add(SHOOT, ENDS_AFTER)
            val laterPhasesUnset = we == null && se == null
            if (laterPhasesUnset && pe != null && pe > de) add(PREP, ENDS_AFTER)
        }
        schedule.customDays.forEach { day ->
            val (cs, ce) = day.startDate to day.endDate
            if (day.name.isBlank()) add(day.id, str(S.name_is_required))
            if (cs != null && ce != null && ce < cs) add(day.id, END_BEFORE_START)
            if (ds != null && cs != null && cs < ds) add(day.id, STARTS_BEFORE)
            if (de != null && ce != null && ce > de) add(day.id, ENDS_AFTER)
        }
        return out
    }

    fun hasErrors(schedule: ProductionSchedule): Boolean = errors(schedule).isNotEmpty()

    private val END_BEFORE_START: String get() = str(S.dm_ds_phase_err_end_before_start)
    private val STARTS_BEFORE: String get() = str(S.desktop_hub_starts_before_production_start_date)
    private val ENDS_AFTER: String get() = str(S.desktop_hub_ends_after_production_end_date)
}

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

// -- allowances and rentals --------------------------------------------------

/**
 * How often an allowance or rental is paid.
 *
 * Shared with the deal wizard's entitlements step — this catalogue seeds those
 * rows, so an option offered here and not there would save a row the wizard
 * renders as a blank required field (`utils/entitlements.js`).
 */
enum class PayBasis(val wire: String, private val labelKey: String) {
    Day("day", S.daily),
    Week("week", S.desktop_5_days_week),
    ThreeInFive("3in5", S.desktop_3_in_5),
    Mile("mile", S.desktop_per_mile),
    ;

    val label: String get() = str(labelKey)

    companion object {
        /**
         * Per Hour, Per Night and Per Event were retired 2026-07.
         *
         * A saved row can still carry one, and blanking a required field reads
         * as data loss — one stray click would rewrite a crew member's agreed
         * pay cadence. So a legacy value is kept and shown, marked for
         * re-picking, rather than dropped.
         */
        val RETIRED: Map<String, String> = mapOf(
            "hour" to str(S.desktop_hub_per_hour_retired_re_select_dashes),
            "night" to str(S.desktop_hub_per_night_retired_re_select_dashes),
            "event" to str(S.desktop_hub_per_event_retired_re_select_dashes),
        )

        fun from(wire: String?): PayBasis? =
            entries.firstOrNull { it.wire == wire?.trim()?.lowercase() }

        /** What to show for a stored basis, retired values included. */
        fun labelFor(wire: String): String =
            from(wire)?.label ?: RETIRED[wire.trim().lowercase()] ?: wire
    }
}

/** When an allowance applies. Diverges from a rental's, so the lists differ. */
enum class AllowanceApplies(val wire: String, private val labelKey: String) {
    Shoot("shoot", S.desktop_shoot_day_only),
    NonShoot("non_shoot", S.desktop_non_shoot_day),
    Both("shoot_non_shoot", S.desktop_hub_shoot_non_shoot_days),
    ;

    val label: String get() = str(labelKey)
}

/** When a rental applies. */
enum class RentalApplies(val wire: String, private val labelKey: String) {
    Shoot("shoot", S.desktop_shoot_day_only),

    /** Stored as `full_production` for back-compat with the wizard's mapper. */
    FullContract("full_production", S.desktop_full_contract),
    ;

    val label: String get() = str(labelKey)
}

/**
 * One production-default allowance or equipment rental.
 *
 * [capped] and [capAmount] apply to rentals only; an allowance has no cap on
 * the wire and carries the default.
 *
 * [enabled] round-trips untouched. The deal wizard drives a per-row toggle
 * from it, but this screen is the project *defaults* surface where a row
 * nobody wants is simply deleted — so no toggle is shown here, and a stored
 * `false` survives being edited.
 */
data class EntitlementRow(
    val id: String = "",
    val name: String = "",
    val enabled: Boolean = true,
    /** Blank while the field is empty; the wire takes a number or null. */
    val amount: String = "",
    val basis: String = "",
    val appliesTo: String = "",
    val capped: Boolean = false,
    val capAmount: String = "",
    val nominalCode: String = "",
)

/** The project's default allowances and equipment rentals. */
data class AllowancesRentals(
    val allowances: List<EntitlementRow> = emptyList(),
    val rentals: List<EntitlementRow> = emptyList(),
)

// -- agreements and documents ------------------------------------------------

/**
 * One document attached to the production's standard agreements.
 *
 * These are not decoration: a deal memo offers them as additional documents
 * for signature, so a production with none has a deal memo that cannot carry
 * its own paperwork.
 *
 * The metadata is Zillit's canonical attachment shape, stored flat on the row.
 * [media] is the S3 key the Open action resolves.
 */
data class AgreementDocument(
    val id: String = "",
    val title: String = "",
    val description: String = "",
    /** The original filename. */
    val name: String = "",
    val media: String = "",
    val bucket: String = "",
    val region: String = "",
    val contentType: String = "",
    /** `pdf`, `docx`, `png` — what the file-type badge reads. */
    val contentSubtype: String = "",
    val fileSize: Long = 0,
) {
    /** Whether the store can be asked for it — the web refuses to open one missing any of the three. */
    val openable: Boolean get() = media.isNotBlank() && bucket.isNotBlank() && region.isNotBlank()
}

/**
 * What the agreements surface accepts.
 *
 * PDF only, 20 MB a file — a product decision from 2026-07-15 rather than a
 * technical limit: these documents ride the signing flow, and only a PDF can
 * go through it. The rule now lives on [SetupUpload], one per purpose, because
 * the budget import takes spreadsheets too; this is the agreements' view of it.
 */
object AgreementUploads {
    val MAX_BYTES: Long get() = SetupUpload.Agreement.maxBytes
    const val EXTENSION = "pdf"

    /** Why [name] cannot be attached, or null when it can. */
    fun refusal(name: String, bytes: Long): String? = SetupUpload.Agreement.refuse(name, bytes)
}

// -- payroll settings --------------------------------------------------------

/**
 * The project's payroll approvers and its pay-cycle window.
 *
 * One row the account-hub service owns, which payroll-server reads directly
 * for its own metadata — so an edit here reaches the timecard and payroll-run
 * surfaces too, and is not a hub-local preference.
 *
 * [payPeriodLockedAt] is server-owned and read-only. Once the first timecard
 * exists the server refuses any pay-period change with a 422, so the window is
 * shown but not editable, and the field is dropped from the save entirely
 * rather than sent back as it arrived.
 */
data class PayrollSettings(
    val approverIds: List<String> = emptyList(),
    val payPeriodStartDay: Int = MONDAY,
    val payPeriodEndDay: Int = SUNDAY,
    val payPeriodLockedAt: Long? = null,
    /** How a payroll journal line's description is cased. */
    val journalDescriptionFormat: JournalDescriptionFormat = JournalDescriptionFormat.Default,
    /** Group journal rows into OTs, penalties, premiums and turnarounds under each company. */
    val journalGroupByCategory: Boolean = false,
    /**
     * The balance-sheet codes payroll posts through, as chart codes.
     *
     * Read-only on this document: the plain PATCH ignores it, and the codes
     * are written through `/custom-accounts`, which keeps the chart in step.
     */
    val payrollAccounts: List<String> = emptyList(),
) {
    val payPeriodLocked: Boolean get() = payPeriodLockedAt != null

    /**
     * Whether an accountant has touched this at all.
     *
     * An approver, or a window that is not the Monday-to-Sunday default —
     * either signal means the card was configured, which is the web's own
     * criterion for its tile.
     */
    val isConfigured: Boolean
        get() = approverIds.isNotEmpty() ||
            payPeriodStartDay != MONDAY ||
            payPeriodEndDay != SUNDAY

    /** The window as the tile prints it — "Mon → Sun". */
    val payPeriodLabel: String
        get() = "${dayName(payPeriodStartDay).take(SHORT_DAY)} → ${dayName(payPeriodEndDay).take(SHORT_DAY)}"

    companion object {
        private const val SHORT_DAY = 3
        const val MONDAY = 1
        const val SUNDAY = 7
        private const val WEEK = 7

        /** Day names by wire number, Monday first as the server counts them. */
        val DAY_NAMES: List<String>
            get() = listOf(
                S.day_monday, S.day_tuesday, S.day_wednesday, S.day_thursday,
                S.day_friday, S.day_saturday, S.day_sunday,
            ).map { str(it) }

        fun dayName(day: Int): String = DAY_NAMES.getOrElse(day - 1) { "—" }

        /** [day] moved [delta] places round the week, staying in 1..7. */
        fun shiftDay(day: Int, delta: Int): Int = ((day - 1 + delta + WEEK) % WEEK) + 1

        /**
         * The window is always seven days.
         *
         * Picking either end moves the other, so a pay period can never be
         * saved as four days or nine — the invariant the web enforces the same
         * way, because payroll downstream assumes a week.
         */
        fun endFor(start: Int): Int = shiftDay(start, delta = 6)

        fun startFor(end: Int): Int = shiftDay(end, delta = -6)

        /** Out-of-range or absent days fall back to a Monday week. */
        fun sanitise(start: Int?, end: Int?): Pair<Int, Int> {
            val safeStart = start?.takeIf { it in MONDAY..SUNDAY } ?: MONDAY
            val safeEnd = end?.takeIf { it in MONDAY..SUNDAY } ?: endFor(safeStart)
            return safeStart to safeEnd
        }
    }
}

/** How a payroll journal description reads — the web's two radio cards. */
enum class JournalDescriptionFormat(val wire: String, val label: String, val sample: String) {
    Uppercase("uppercase", "WEEK DD-DD MON YYYY CREW NAME PAY", "WEEK 22-28 JUN 2026 JANE SMITH OT 1.5X"),
    Title("title", "Week Dd-Dd Mon Yyyy Crew Name Pay", "Week 22-28 Jun 2026 Jane Smith OT 1.5x"),
    ;

    companion object {
        val Default = Uppercase

        fun from(wire: String?): JournalDescriptionFormat = entries.firstOrNull { it.wire == wire } ?: Default
    }
}

/**
 * Crew routed to one accountant for payroll — by department, by role or by name.
 *
 * `/api/v2/payroll/payroll-groups`. The management layer only: the scoping
 * itself is a backend follow-up, and the web says so on its own card.
 */
data class PayrollGroup(
    val id: String = "",
    val assigneeId: String = "",
    val userIds: List<String> = emptyList(),
    val departmentIds: List<String> = emptyList(),
    val designationIds: List<String> = emptyList(),
) {
    /** An accountant and at least one thing to route — the web's `canSave`. */
    val canSave: Boolean
        get() = assigneeId.isNotBlank() &&
            (userIds.isNotEmpty() || departmentIds.isNotEmpty() || designationIds.isNotEmpty())
}

/**
 * One row of the payroll-accounts batch — `PATCH /payroll-settings/custom-accounts`.
 *
 * No [id] creates the code in the chart; an id updates it; an id with
 * [delete] deactivates it and drops it from the list.
 */
data class PayrollAccountRow(
    val id: String? = null,
    val code: String = "",
    val name: String = "",
    val lineType: CoaLineType = CoaLineType.Category,
    val delete: Boolean = false,
)

// -- purchase order setup ----------------------------------------------------

/** How a purchase order line's description is assembled. */
enum class PoDescriptionFormat(val wire: String, private val labelKey: String, private val sampleKey: String) {
    DayMonthItem("DDMON_ITEM", S.desktop_hub_po_format_ddmon_item, S.desktop_hub_po_sample_ddmon_item),
    DayMonthNumericItem("DDMM_ITEM", S.desktop_hub_po_format_ddmm_item, S.desktop_hub_po_sample_ddmm_item),
    ItemDayMonth("ITEM_DDMON", S.desktop_hub_po_format_item_ddmon, S.desktop_hub_po_sample_item_ddmon),
    /** Decoded when stored, never offered — the web's setup modal lists the three above only. */
    Custom("CUSTOM", S.custom, S.desktop_hub_define_your_own_pattern),
    ;

    val label: String get() = str(labelKey)
    val sample: String get() = str(sampleKey)

    companion object {
        val Default = DayMonthItem

        /** The formats the setup modal offers, in the web's order. */
        val offered: List<PoDescriptionFormat> get() = entries.filter { it != Custom }

        fun from(wire: String?): PoDescriptionFormat =
            entries.firstOrNull { it.wire == wire } ?: Default
    }
}

/** How a rental order is split into periods when it posts. */
enum class PoSplitType(val wire: String, private val labelKey: String) {
    Weekly("weekly", S.ce_weekly),
    Daily("daily", S.daily),
    Monthly("monthly", S.ce_monthly),
    FourWeek("four_week", S.desktop_four_week),
    ;

    val label: String get() = str(labelKey)

    companion object {
        val Default = Weekly

        fun from(wire: String?): PoSplitType = entries.firstOrNull { it.wire == wire } ?: Default
    }
}

/**
 * The production's purchase-order settings.
 *
 * This is the only screen that edits them on the desktop: the Purchase Orders
 * tool has no settings surface here, where the web has one in the tool *and*
 * this drill-down over the same document.
 *
 * `require_effective_date` and `enforce_period_close` are not modelled. Both
 * are forced true by the web regardless of what is stored, and are shown there
 * as a static "Always" rather than a switch — a toggle this client could not
 * turn off would be a lie about what the service does.
 *
 * `allow_amend_after_approval` is not modelled either: PO amendments are built
 * but paused behind the web's `AMENDMENTS_ENABLED = false`, so the row is
 * hidden there and there is nothing here to reach it from.
 */
data class PurchaseOrderSetup(
    val descriptionFormat: PoDescriptionFormat = PoDescriptionFormat.Default,
    /** Absent means on — only an explicit false turns auto-splitting off. */
    val autoSplitRentals: Boolean = true,
    val splitType: PoSplitType = PoSplitType.Default,
    val numberPrefix: String = "",
    /** The terms issued with every order, or null when none is set. */
    val termsDocument: AgreementDocument? = null,
    /**
     * Whether the raiser may edit an approved order.
     *
     * Round-tripped, never shown: amendments are paused behind the web's own
     * `AMENDMENTS_ENABLED = false`, and a save that dropped the key would
     * reset a production's stored choice.
     */
    val allowAmendAfterApproval: Boolean = false,
    /**
     * Which posted and closed PO lines the Asset Register counts — the web's
     * `asset_filters`, edited here and on the PO module's own Settings page
     * through the same document (web 03f047d47 put the section back into
     * this modal).
     */
    val assetFilters: AssetFilters = AssetFilters(),
) {
    /**
     * The Rental & Split section's chip: auto-split when on, plus the two
     * always-on rules — what the web's `count` adds up.
     */
    val rentalCount: Int get() = ALWAYS_ON_RULES + if (autoSplitRentals) 1 else 0

    /** The Asset Register Rules chip: each sub-rule that constrains something, as the web counts them. */
    val assetCount: Int get() = assetFilters.count

    /** The Issuance section's chip: a prefix and a terms document, each counted once. */
    val issuanceCount: Int
        get() = (if (numberPrefix.isNotBlank()) 1 else 0) + (if (termsDocument != null) 1 else 0)

    companion object {
        const val PREFIX_MAX = 8

        /** Under the prefix field — the web's `PO_PREFIX_HINT`, shared by both of its settings screens. */
        val PREFIX_HINT: String
            get() = str(S.desktop_hub_goes_at_the_start_of_every_new_po_number_pos)

        /** Require effective date and enforce period close, forced on by the service. */
        private const val ALWAYS_ON_RULES = 2

        /**
         * Upper case, letters and digits only, at most eight.
         *
         * Applied as the field is typed rather than on save. The server builds
         * the number, so what a stray hyphen would produce is not this
         * client's to guess at — it simply never sends one.
         */
        fun normalisePrefix(raw: String): String =
            raw.uppercase().filter { it.isLetterOrDigit() }.take(PREFIX_MAX)
    }
}

// -- asset register rule -----------------------------------------------------

/** The expenditure types a PO line can carry — the web's `ASSET_EXP_TYPES`. */
enum class AssetExpenditureType(val wire: String, private val labelKey: String) {
    Purchase("Purchase", S.ah_exp_purchase),

    /** The stored value stays the server's enum; only the wording changed. */
    Consumption("Consumption", S.ah_exp_consumption),
    ;

    val label: String get() = str(labelKey)

    companion object {
        fun labelFor(wire: String): String = entries.firstOrNull { it.wire == wire }?.label ?: wire
    }
}

/**
 * Which line items on posted and closed orders qualify as assets — the web's
 * `asset_filters`, the same rule the PO module's Settings page edits.
 *
 * The bounds are kept as typed so the field can hold "1,500" while it is
 * edited; a blank bound is no bound. Every sub-rule empty is "no constraint",
 * and the wire collapses that to null.
 */
data class AssetFilters(
    val priceLow: String = "",
    val priceHigh: String = "",
    /** The server's enum values; empty means every type. */
    val expTypes: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
) {
    val low: Double? get() = priceLow.toAmountOrNull()
    val high: Double? get() = priceHigh.toAmountOrNull()

    val isEmpty: Boolean get() = low == null && high == null && expTypes.isEmpty() && tags.isEmpty()

    /** How many sub-rules constrain something — the web's section chip. */
    val count: Int
        get() = listOf(low != null || high != null, expTypes.isNotEmpty(), tags.isNotEmpty()).count { it }

    /** Why the rule cannot be saved, or null — the web's `assetFilterError`. */
    val error: String?
        get() {
            val lowBound = low
            val highBound = high
            return when {
                lowBound != null && highBound != null && lowBound > highBound ->
                    str(S.desktop_hub_price_low_must_not_exceed_price_high)
                lowBound != null && lowBound < 0 -> str(S.desktop_hub_price_low_must_be_zero_or_more)
                highBound != null && highBound < 0 -> str(S.desktop_hub_price_high_must_be_zero_or_more)
                else -> null
            }
        }

    /** Spells the rule back out, so "no constraint" cannot pass for "nothing saved" — `buildAssetSummary`. */
    fun summary(symbol: String): String {
        val lowBound = low
        val highBound = high
        val parts = mutableListOf<String>()
        when {
            lowBound != null && highBound != null ->
                parts += str(S.desktop_hub_asset_rule_total_between, money(symbol, lowBound), money(symbol, highBound))
            lowBound != null -> parts += str(S.desktop_hub_asset_rule_total_at_least, money(symbol, lowBound))
            highBound != null -> parts += str(S.desktop_hub_asset_rule_total_at_most, money(symbol, highBound))
        }
        val or = " ${str(S.or)} "
        if (expTypes.isNotEmpty()) parts += expTypes.joinToString(or) { AssetExpenditureType.labelFor(it) }
        if (tags.isNotEmpty()) parts += str(S.desktop_hub_asset_rule_tagged, tags.joinToString(or))
        if (parts.isEmpty()) return str(S.desktop_hub_every_line_item_on_a_posted_or_closed_po_no)
        return str(S.desktop_hub_lines_on_a_posted_or_closed_po_matching, parts.joinToString(", ${str(S.and)} "))
    }

    private fun money(symbol: String, value: Double): String {
        val whole = value.toLong()
        val grouped = whole.toString().reversed().chunked(THOUSANDS).joinToString(",").reversed()
        val cents = ((value - whole) * CENTS).toInt()
        return if (cents == 0) "$symbol$grouped" else "$symbol$grouped.${cents.toString().padStart(2, '0')}"
    }

    private companion object {
        const val THOUSANDS = 3
        const val CENTS = 100
    }
}

/** "1,500" and "1500.50" both parse; blank, or anything else, is no amount. */
private fun String.toAmountOrNull(): Double? = trim().replace(",", "").takeIf { it.isNotEmpty() }?.toDoubleOrNull()

// -- invoices setup ----------------------------------------------------------

/** One thing accounts payable can be told about. */
enum class InvoiceAlert(val wire: String, private val labelKey: String, private val hintKey: String) {
    Overdue(
        "invoice_overdue",
        S.desktop_invoice_overdue_notifications,
        S.desktop_hub_get_notified_when_an_invoice_passes_its_due_date_without,
    ),
    SlaBreach(
        "approval_sla_breach",
        S.desktop_hub_approval_sla_breach_warnings,
        S.desktop_hub_alert_when_an_invoice_sits_in_the_approval_queue_beyond,
    ),
    Duplicate(
        "duplicate_detection",
        S.desktop_duplicate_invoice_detection,
        S.desktop_hub_flag_invoices_that_appear_to_be_duplicates_based_on_vendor,
    ),
    OverPo(
        "over_po_flagging",
        S.desktop_hub_over_po_flagging_alerts,
        S.desktop_hub_warn_when_an_invoice_amount_exceeds_the_linked_purchase_order,
    ),
    NoPoOverride(
        "no_po_override",
        S.desktop_hub_no_po_override_notifications,
        S.desktop_hub_notify_when_an_invoice_is_approved_without_a_linked_purchase,
    ),
    DailySummary(
        "daily_ap_summary",
        S.desktop_hub_daily_ap_summary_email,
        S.desktop_hub_receive_a_morning_summary_of_pending_invoices_and_payment_run,
    ),
    ;

    val label: String get() = str(labelKey)
    val hint: String get() = str(hintKey)

    companion object {
        fun from(wire: String?): InvoiceAlert? = entries.firstOrNull { it.wire == wire }
    }
}

/** One person on the accounts-payable team, and what they may do. */
data class InvoiceTeamMember(
    val userId: String = "",
    /** Blank while empty; zero and "not set" are different answers. */
    val postingLimit: String = "",
    val runAccess: Boolean = false,
    val overrideAccess: Boolean = false,
    val isSenior: Boolean = false,
)

/** One level of the payment-run sign-off chain. [tier] is 1-based, as shown. */
data class RunAuthorisationTier(val tier: Int = 1, val userIds: List<String> = emptyList())

/**
 * Who may enter and authorise accounts-payable work.
 *
 * The desktop's Invoices tool reads this document to gate its row actions but
 * has never written it, so this section is the only place it is edited here.
 *
 * Alerts are stored as a list of the enabled keys, not a map of booleans — an
 * alert nobody has turned on is simply absent, and writing `false` for it would
 * invent a preference the service does not hold.
 */
data class InvoicesSetup(
    val teamMembers: List<InvoiceTeamMember> = emptyList(),
    val alerts: Set<InvoiceAlert> = emptySet(),
    val runAuthorisation: List<RunAuthorisationTier> = emptyList(),
) {
    /**
     * Whether accounts payable can actually process anything.
     *
     * Both halves are needed — a team with nobody to authorise a run, or an
     * authorisation chain with nobody to enter an invoice, processes nothing.
     * The web's own criterion for this tile.
     */
    val isConfigured: Boolean
        get() = teamMembers.isNotEmpty() && runAuthorisation.isNotEmpty()

    /** Levels renumbered from one, so a removal never leaves a gap. */
    fun renumbered(): InvoicesSetup =
        copy(runAuthorisation = runAuthorisation.mapIndexed { index, tier -> tier.copy(tier = index + 1) })
}
