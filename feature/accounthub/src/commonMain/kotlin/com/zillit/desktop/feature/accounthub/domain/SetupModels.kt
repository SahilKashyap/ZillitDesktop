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
)

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

    companion object {
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

// -- purchase order setup ----------------------------------------------------

/** How a purchase order line's description is assembled. */
enum class PoDescriptionFormat(val wire: String, val label: String, val sample: String) {
    DayMonthItem("DDMON_ITEM", "Date then item", "03MAR ALEXA MINI LF HIRE"),
    DayMonthNumericItem("DDMM_ITEM", "Numeric date then item", "03/03 ALEXA MINI LF HIRE"),
    ItemDayMonth("ITEM_DDMON", "Item then date", "ALEXA MINI LF HIRE 03MAR"),
    Custom("CUSTOM", "Custom", "Define your own pattern"),
    ;

    companion object {
        val Default = DayMonthItem

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
) {
    companion object {
        const val PREFIX_MAX = 8

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
        "Invoice overdue",
        "When an invoice passes its due date without being paid.",
    ),
    SlaBreach(
        "approval_sla_breach",
        "Approval SLA breached",
        "When an invoice sits in the approval queue beyond the SLA window.",
    ),
    Duplicate(
        "duplicate_detection",
        "Possible duplicate",
        "When an invoice looks like one already entered, by vendor and amount.",
    ),
    OverPo(
        "over_po_flagging",
        "Over the purchase order",
        "When an invoice is worth more than the order it is linked to.",
    ),
    NoPoOverride(
        "no_po_override",
        "Approved with no order",
        "When an invoice is approved without a purchase order behind it.",
    ),
    DailySummary(
        "daily_ap_summary",
        "Daily summary",
        "A morning digest of pending invoices and the payment run's state.",
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
