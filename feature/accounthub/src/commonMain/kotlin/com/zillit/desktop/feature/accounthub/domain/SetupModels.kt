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
) {
    /** The first letters of up to two words — the card's monogram. */
    val monogram: String
        get() = name.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
            .take(2).joinToString("") { it.first().uppercaseChar().toString() }
            .ifBlank { "?" }
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
enum class BankDetailType(val wire: String, val label: String) {
    Text("text", "Text"),
    Number("number", "Number"),
    Phone("phone", "Phone"),
    Email("email", "Email"),
    Url("url", "URL"),
    ;

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

    /** Whether another row already carries this account number. */
    fun duplicateNumber(draft: BankAccount, banks: List<BankAccount>): Boolean {
        val number = draft.accountNumber.trim()
        if (number.isEmpty()) return false
        return banks.any { it.id != draft.id && it.accountNumber.trim() == number }
    }

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
        draft.name.isBlank() -> "Bank name is required."
        draft.entityId.isNullOrBlank() && draft.accountHolderName.isBlank() ->
            "Account holder company is required."
        !draft.entityId.isNullOrBlank() && companies.none { it.id == draft.entityId } ->
            "Account holder company is required."
        draft.accountNumber.isBlank() -> "Account number is required."
        duplicateNumber(draft, banks) -> "An account with this number already exists."
        accountant && draft.nominalCode.isBlank() -> "Bank account nominal code is required."
        accountant && draft.apClearanceNominalCode.isBlank() -> "AP clearance nominal code is required."
        draft.currencyCode.isBlank() -> "Currency is required."
        firstInvalidDetail(draft.additionalDetails) != null ->
            "\"${firstInvalidDetail(draft.additionalDetails)?.title}\" is not a valid " +
                "${firstInvalidDetail(draft.additionalDetails)?.fieldType?.label?.lowercase()}."
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
            ?.let { "Add an exchange rate for ${it.joinToString(", ")} before saving." }

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
                "Tax rate must be between ${RATE_MIN.toInt()}% and ${RATE_MAX.toInt()}%."
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

        if (pe != null && ss != null && ss <= pe) add(SHOOT, "Overlaps with Prep — must start after prep ends")
        if (se != null && ws != null && ws <= se) add(WRAP, "Overlaps with Shoot — must start after shoot ends")

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
            if (day.name.isBlank()) add(day.id, "Name is required")
            if (cs != null && ce != null && ce < cs) add(day.id, END_BEFORE_START)
            if (ds != null && cs != null && cs < ds) add(day.id, STARTS_BEFORE)
            if (de != null && ce != null && ce > de) add(day.id, ENDS_AFTER)
        }
        return out
    }

    fun hasErrors(schedule: ProductionSchedule): Boolean = errors(schedule).isNotEmpty()

    private const val END_BEFORE_START = "End date is before start date"
    private const val STARTS_BEFORE = "Starts before production start date"
    private const val ENDS_AFTER = "Ends after production end date"
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
enum class PayBasis(val wire: String, val label: String) {
    Day("day", "Daily"),
    Week("week", "5 Days Week"),
    ThreeInFive("3in5", "3 in 5"),
    Mile("mile", "Per Mile"),
    ;

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
            "hour" to "Per Hour (retired — re-select)",
            "night" to "Per Night (retired — re-select)",
            "event" to "Per Event (retired — re-select)",
        )

        fun from(wire: String?): PayBasis? =
            entries.firstOrNull { it.wire == wire?.trim()?.lowercase() }

        /** What to show for a stored basis, retired values included. */
        fun labelFor(wire: String): String =
            from(wire)?.label ?: RETIRED[wire.trim().lowercase()] ?: wire
    }
}

/** When an allowance applies. Diverges from a rental's, so the lists differ. */
enum class AllowanceApplies(val wire: String, val label: String) {
    Shoot("shoot", "Shoot Day only"),
    NonShoot("non_shoot", "Non-shoot day"),
    Both("shoot_non_shoot", "Shoot & Non-shoot Days"),
}

/** When a rental applies. */
enum class RentalApplies(val wire: String, val label: String) {
    Shoot("shoot", "Shoot Day only"),

    /** Stored as `full_production` for back-compat with the wizard's mapper. */
    FullContract("full_production", "Full Contract"),
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
        val DAY_NAMES: List<String> =
            listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")

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
enum class PoDescriptionFormat(val wire: String, val label: String, val sample: String) {
    DayMonthItem("DDMON_ITEM", "DDMON → ITEM", "03MAR ALEXA MINI LF HIRE"),
    DayMonthNumericItem("DDMM_ITEM", "DDMM → ITEM", "03/03 ALEXA MINI LF HIRE"),
    ItemDayMonth("ITEM_DDMON", "ITEM → DDMON", "ALEXA MINI LF HIRE 03MAR"),
    /** Decoded when stored, never offered — the web's setup modal lists the three above only. */
    Custom("CUSTOM", "Custom", "Define your own pattern"),
    ;

    companion object {
        val Default = DayMonthItem

        /** The formats the setup modal offers, in the web's order. */
        val offered: List<PoDescriptionFormat> get() = entries.filter { it != Custom }

        fun from(wire: String?): PoDescriptionFormat =
            entries.firstOrNull { it.wire == wire } ?: Default
    }
}

/** How a rental order is split into periods when it posts. */
enum class PoSplitType(val wire: String, val label: String) {
    Weekly("weekly", "Weekly"),
    Daily("daily", "Daily"),
    Monthly("monthly", "Monthly"),
    FourWeek("four_week", "Four Week"),
    ;

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
) {
    /**
     * The Rental & Split section's chip: auto-split when on, plus the two
     * always-on rules — what the web's `count` adds up.
     */
    val rentalCount: Int get() = ALWAYS_ON_RULES + if (autoSplitRentals) 1 else 0

    /** The Issuance section's chip: a prefix and a terms document, each counted once. */
    val issuanceCount: Int
        get() = (if (numberPrefix.isNotBlank()) 1 else 0) + (if (termsDocument != null) 1 else 0)

    companion object {
        const val PREFIX_MAX = 8

        /** Under the prefix field — the web's `PO_PREFIX_HINT`, shared by both of its settings screens. */
        const val PREFIX_HINT =
            "Goes at the start of every new PO number. POs you've already created keep their existing numbers."

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

// -- invoices setup ----------------------------------------------------------

/** One thing accounts payable can be told about. */
enum class InvoiceAlert(val wire: String, val label: String, val hint: String) {
    Overdue(
        "invoice_overdue",
        "Invoice overdue notifications",
        "Get notified when an invoice passes its due date without being paid.",
    ),
    SlaBreach(
        "approval_sla_breach",
        "Approval SLA breach warnings",
        "Alert when an invoice sits in the approval queue beyond the SLA window.",
    ),
    Duplicate(
        "duplicate_detection",
        "Duplicate invoice detection",
        "Flag invoices that appear to be duplicates based on vendor and amount.",
    ),
    OverPo(
        "over_po_flagging",
        "Over-PO flagging alerts",
        "Warn when an invoice amount exceeds the linked purchase order value.",
    ),
    NoPoOverride(
        "no_po_override",
        "No-PO override notifications",
        "Notify when an invoice is approved without a linked purchase order.",
    ),
    DailySummary(
        "daily_ap_summary",
        "Daily AP summary email",
        "Receive a morning summary of pending invoices and payment run status.",
    ),
    ;

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
