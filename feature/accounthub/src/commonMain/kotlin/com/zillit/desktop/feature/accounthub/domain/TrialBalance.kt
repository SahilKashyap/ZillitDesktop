package com.zillit.desktop.feature.accounthub.domain

import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/**
 * One account's movement and closing balance over the period.
 *
 * [ending] is the balance the report shows, not `debit - credit` recomputed
 * here: opening balances and conversions are the server's, and a client that
 * derived the figure would disagree with the exported PDF.
 */
data class TrialBalanceRow(
    val accountCode: String = "",
    val name: String = "",
    /** The chart's cost type, which is what the report groups by. */
    val costType: String = "",
    val debit: Double = 0.0,
    val credit: Double = 0.0,
    val ending: Double = 0.0,
)

/** A cost type's rows, with its subtotal. */
data class TrialBalanceGroup(
    val costType: String,
    val label: String,
    val rows: List<TrialBalanceRow>,
) {
    val debit: Double get() = rows.sumOf { it.debit }
    val credit: Double get() = rows.sumOf { it.credit }
    val balance: Double get() = rows.sumOf { it.ending }
}

/**
 * The report over one period.
 *
 * Grouped by cost type in the order the web draws them: Expense first, then
 * the rest alphabetically by label.
 *
 * The web's comment says "Expense last", but its sort key for Expense is
 * `"~~~~"`, and `localeCompare` puts symbols *before* letters — so every
 * browser shows Expense at the top. That drawn order is the one matched here,
 * so the two clients list a production's accounts the same way.
 */
data class TrialBalance(
    val rows: List<TrialBalanceRow> = emptyList(),
) {
    val groups: List<TrialBalanceGroup>
        get() = rows.groupBy { it.costType }
            .map { (costType, rows) ->
                TrialBalanceGroup(
                    costType = costType,
                    label = labelFor(costType),
                    rows = rows.sortedWith(compareBy(AccountCodeOrder) { it.accountCode }),
                )
            }
            .sortedWith(
                compareBy<TrialBalanceGroup> { it.costType != EXPENSE }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.label },
            )

    val debit: Double get() = rows.sumOf { it.debit }
    val credit: Double get() = rows.sumOf { it.credit }
    val balance: Double get() = rows.sumOf { it.ending }

    /**
     * Whether the two sides agree.
     *
     * To the half-penny, not exactly: these are sums of converted decimals,
     * and demanding exact equality would report a balanced ledger as broken.
     */
    val isBalanced: Boolean get() = kotlin.math.abs(debit - credit) < TOLERANCE

    companion object {
        private const val TOLERANCE = 0.005
        private const val EXPENSE = "expense"

        /** The web's `COST_TYPE_DISPLAY` for the five it knows, and a humanised key for anything newer. */
        fun labelFor(costType: String): String = when {
            costType.isBlank() -> str(S.desktop_uncategorised)
            else -> costType.replace('_', ' ').replaceFirstChar { it.uppercase() }
        }
    }
}

/**
 * Account codes in the order the web lists them — `localeCompare` with
 * `numeric: true`: runs of digits compare as numbers, everything between them
 * as text, ignoring case.
 *
 * So `20A` < `30` < `100` < `1000` < `1000-01` < `ABC`. A plain string sort
 * puts `100` before `20`, which an accountant reading a chart notices at once;
 * comparing whole codes as numbers only when both parse — the rule this
 * replaced — is not even a consistent order once codes mix digits and letters,
 * and a sort handed an inconsistent comparator is allowed to throw.
 */
object AccountCodeOrder : Comparator<String> {

    override fun compare(a: String, b: String): Int {
        val left = runs(a)
        val right = runs(b)
        for (i in 0 until minOf(left.size, right.size)) {
            val byRun = compareRuns(left[i], right[i])
            if (byRun != 0) return byRun
        }
        val byLength = left.size.compareTo(right.size)
        // Equal as far as the order can tell ("01" and "1", "a" and "A"): the
        // raw text settles it, so the order stays total and stable.
        return if (byLength != 0) byLength else a.compareTo(b)
    }

    /** `1000-01A` → `1000`, `-`, `01`, `A`. */
    private fun runs(code: String): List<String> {
        val out = mutableListOf<String>()
        var start = 0
        for (i in 1..code.length) {
            if (i == code.length || code[i].isAsciiDigit() != code[start].isAsciiDigit()) {
                out += code.substring(start, i)
                start = i
            }
        }
        return out
    }

    private fun compareRuns(a: String, b: String): Int =
        if (a[0].isAsciiDigit() && b[0].isAsciiDigit()) compareNumbers(a, b) else a.compareTo(b, ignoreCase = true)

    /** By value, without parsing — a code can be longer than a Long holds. */
    private fun compareNumbers(a: String, b: String): Int {
        val left = a.trimStart('0')
        val right = b.trimStart('0')
        return if (left.length != right.length) left.length.compareTo(right.length) else left.compareTo(right)
    }

    /** ASCII only: a Unicode digit read as a number would break the order's consistency. */
    private fun Char.isAsciiDigit(): Boolean = this in '0'..'9'
}

/**
 * A ledger figure as the web prints one: `groupAmount(Math.abs(n), 2)` under
 * the currency's symbol — two decimals, thousands grouped — bracketed when it
 * is negative.
 */
object LedgerMoney {

    /** "£1,234.56", or "(£1,234.56)"; a negative that rounds to nothing prints as nothing. */
    fun format(value: Double, symbol: String = ""): String {
        val pence = pence(value)
        val whole = (pence / PENCE_PER_UNIT).toString().reversed().chunked(THOUSANDS).joinToString(",").reversed()
        val figure = "$symbol$whole.${(pence % PENCE_PER_UNIT).toString().padStart(2, '0')}"
        return if (value < 0 && pence > 0) "($figure)" else figure
    }

    /**
     * The size of [value] in whole hundredths, rounded half away from zero on
     * its shortest decimal form — how `toLocaleString` rounds.
     *
     * Not `round(value * 100)`: `1.005 * 100` is `100.49999999999999`, so the
     * web's £1.01 would print here as £1.00; and Kotlin's `round` sends an exact
     * half to the even neighbour, so £0.125 would be £0.12 against the web's
     * £0.13. A total that differs from the web's by a penny is a total nobody
     * can reconcile.
     */
    fun pence(value: Double): Long {
        if (!value.isFinite()) return 0
        val plain = plainDecimal(kotlin.math.abs(value))
        val point = plain.indexOf('.')
        val whole = if (point < 0) plain else plain.substring(0, point)
        val decimals = (if (point < 0) "" else plain.substring(point + 1)).padEnd(ROUNDING_DIGIT + 1, '0')
        val truncated = whole.toLong() * PENCE_PER_UNIT + decimals.take(ROUNDING_DIGIT).toLong()
        return if (decimals[ROUNDING_DIGIT] >= '5') truncated + 1 else truncated
    }

    /** `1.2345678905E7` → `12345678.905`: the JVM's shortest form, without the exponent it uses past 10⁷. */
    private fun plainDecimal(value: Double): String {
        val text = value.toString()
        val exponentAt = text.indexOfFirst { it == 'E' || it == 'e' }
        if (exponentAt < 0) return text
        val mantissa = text.substring(0, exponentAt)
        val digits = mantissa.replace(".", "")
        val point = (mantissa.indexOf('.').takeIf { it >= 0 } ?: mantissa.length) +
            text.substring(exponentAt + 1).toInt()
        return when {
            point <= 0 -> "0." + "0".repeat(-point) + digits
            point >= digits.length -> digits + "0".repeat(point - digits.length)
            else -> digits.substring(0, point) + "." + digits.substring(point)
        }
    }

    private const val PENCE_PER_UNIT = 100L
    private const val THOUSANDS = 3

    /** Two decimals kept; the third decides. */
    private const val ROUNDING_DIGIT = 2
}

/**
 * The window a report covers.
 *
 * Supplied by the host rather than computed here: a year boundary needs a
 * calendar and a zone, and this module has neither — pulling a date library in
 * for one subtraction would be the wrong trade.
 */
data class ReportPeriod(val startMillis: Long, val endMillis: Long)

/** What the report is asked for. */
data class TrialBalanceQuery(
    val periodStartMillis: Long,
    val periodEndMillis: Long,
    /** Blank means every account type. */
    val accountType: String = "",
    /** Blank means the production's default currency; the server converts. */
    val currency: String = "",
    /**
     * Accounts with no movement and a zero balance.
     *
     * Always sent, including when false: omitting it would leave the server's
     * own default to decide, and the two have disagreed before.
     */
    val includeZeroAccounts: Boolean = false,
    /** The account range, inclusive, as codes. Blank ends are open. */
    val accountStart: String = "",
    val accountEnd: String = "",
    /** One legal entity, or blank for the whole production. */
    val companyId: String = "",
)

/**
 * What the filter bar says — the web's `draft`, and once a report has been run
 * from it, its `applied`.
 *
 * Days, not instants: the web holds `YYYY-MM-DD` and turns a day into the
 * machine's midnight or end of day only when it asks, so comparing two sets of
 * filters compares what the reader chose rather than when they chose it.
 */
data class TrialBalanceFilters(
    val mode: PeriodMode = PeriodMode.Current,
    /** The period's first and last day, inclusive. */
    val from: String = "",
    val to: String = "",
    /** The account range, trimmed. Blank ends are open. */
    val accountStart: String = "",
    val accountEnd: String = "",
    /** One legal entity, or blank for every company — the web's `"all"`. */
    val companyId: String = "",
    /** Blank stands for the production's default currency. */
    val currency: String = "",
    val includeZeroAccounts: Boolean = false,
) {
    /** Whether both days read and run forwards — what a request needs. */
    val hasValidPeriod: Boolean
        get() {
            val start = TrialBalancePeriod.parse(from) ?: return false
            val end = TrialBalancePeriod.parse(to) ?: return false
            return start <= end
        }

    /**
     * The wire query, or null while the period cannot be asked for — the web
     * sends nothing then rather than a period nobody chose.
     *
     * [defaultCurrency] stands in for a blank currency, the web's
     * `applied.currency || defaultCode`, so a report is in the production's
     * own currency even before one has been picked.
     */
    fun toQuery(zone: TimeZone, defaultCurrency: String = ""): TrialBalanceQuery? {
        if (!hasValidPeriod) return null
        return TrialBalanceQuery(
            periodStartMillis = TrialBalancePeriod.startOfDay(from, zone) ?: return null,
            periodEndMillis = TrialBalancePeriod.endOfDay(to, zone) ?: return null,
            currency = currency.ifBlank { defaultCurrency },
            includeZeroAccounts = includeZeroAccounts,
            accountStart = accountStart,
            accountEnd = accountEnd,
            companyId = companyId,
        )
    }

    /**
     * The period in words, as the filter bar prints it and the export's header
     * carries it: "6 Sep 2026 – 12 Sep 2026", or "Till 12 Sep 2026" for a
     * Current Period that starts at the floor because nothing is closed.
     */
    val periodLabel: String
        get() = if (mode == PeriodMode.Current && from == TrialBalancePeriod.FLOOR) {
            str(S.desktop_till_date, TrialBalancePeriod.label(to))
        } else {
            str(S.ah_rental_format, TrialBalancePeriod.label(from), TrialBalancePeriod.label(to))
        }
}

/**
 * The calendar arithmetic behind the trial balance's period, in local days —
 * the web's `ymdToMs`, `todayYmd` and `fmtYmd`.
 */
object TrialBalancePeriod {

    /**
     * Where "Current Period" starts while nothing has been closed.
     *
     * The web's `FLOOR_YMD`. An open-ended "Till <today>" still needs a first
     * day, and one well after the epoch stays a positive instant in every
     * zone: a 1970 floor goes negative east of UTC, and the service refuses a
     * negative `period_start`.
     */
    const val FLOOR = "2000-01-01"

    private val MONTHS: List<String>
        get() = listOf(
            S.desktop_month_short_jan, S.desktop_month_short_feb, S.desktop_month_short_mar, S.desktop_month_short_apr,
            S.desktop_month_short_may, S.desktop_month_short_jun, S.desktop_month_short_jul, S.desktop_month_short_aug,
            S.desktop_month_short_sep, S.desktop_month_short_oct, S.desktop_month_short_nov, S.desktop_month_short_dec,
        ).map { str(it) }

    /** A `YYYY-MM-DD` day, or null for anything else — including a day half typed or one that does not exist. */
    fun parse(day: String): LocalDate? = runCatching { LocalDate.parse(day.trim()) }.getOrNull()

    /** Midnight at the start of [day], in [zone]. */
    fun startOfDay(day: String, zone: TimeZone): Long? = parse(day)?.atStartOfDayIn(zone)?.toEpochMilliseconds()

    /** The last millisecond of [day], in [zone] — 23:59:59.999, even across a clock change. */
    fun endOfDay(day: String, zone: TimeZone): Long? =
        parse(day)?.plus(1, DateTimeUnit.DAY)?.atStartOfDayIn(zone)?.toEpochMilliseconds()?.minus(1)

    fun today(nowMillis: Long, zone: TimeZone): String =
        Instant.fromEpochMilliseconds(nowMillis).toLocalDateTime(zone).date.toString()

    /** 1 January of this year — where the web's Date Range opens. */
    fun yearStart(nowMillis: Long, zone: TimeZone): String =
        "${Instant.fromEpochMilliseconds(nowMillis).toLocalDateTime(zone).date.year}-01-01"

    /** "6 Sep 2026" — the web's `fmtYmd` — or a dash for a day that does not read. */
    fun label(day: String): String {
        val date = parse(day) ?: return "—"
        return "${date.day} ${MONTHS[date.month.ordinal]} ${date.year}"
    }
}

/**
 * How the period is chosen — the web's two chips.
 *
 * "Current Period" runs from the last closed date to today, and reads as
 * "Till <today>" when nothing has been closed yet. "Date Range" is the two
 * pickers.
 */
enum class PeriodMode(private val labelKey: String) {
    Current(S.desktop_current_period),
    Custom(S.cs_date_range),
    ;

    val label: String get() = str(labelKey)
}

/** The formats a report exports in — `POST …/export/{format}` returns the file. */
enum class ExportFormat(val wire: String, private val labelKey: String, val extension: String) {
    Pdf("pdf", S.av_pdf, "pdf"),
    Excel("xlsx", S.excel, "xlsx"),
    Csv("csv", S.desktop_csv, "csv"),
    ;

    val label: String get() = str(labelKey)
}

/** The three reports a closing package can carry — the web's `REPORTS`. */
enum class ClosingReport(val wire: String, private val labelKey: String) {
    CostReport("cost_report", S.cr_title),
    TrialBalance("trial_balance", S.desktop_trial_balance),
    BibleReport("bible_report", S.desktop_bible_report),
    ;

    val label: String get() = str(labelKey)
}

/**
 * One package of the closing publish — who gets which reports.
 *
 * Recipients are crew by id or outside addresses; a package with neither, or
 * with no report, is not sent (`isPackageValid`).
 */
data class ClosingPackage(
    val id: Int,
    val userIds: List<String> = emptyList(),
    val emails: List<String> = emptyList(),
    val emailDraft: String = "",
    val reports: List<ClosingReport> = emptyList(),
) {
    val recipientCount: Int get() = userIds.size + emails.size

    val isValid: Boolean get() = recipientCount > 0 && reports.isNotEmpty()
}

// -- period close ------------------------------------------------------------

/**
 * How far the production's books are closed.
 *
 * One boundary, and it only ever moves forward. Everything dated on or before
 * [lockedThrough] is read-only in every source module — purchase orders,
 * invoices, cards, cash and payroll alike — and there is no endpoint to undo
 * it. That is why this screen asks before it acts and never offers an unlock.
 *
 * [lockedThrough] is the inclusive last day closed, as `YYYY-MM-DD`. Blank
 * means nothing has been closed yet.
 */
data class PeriodLock(
    val lockedThrough: String = "",
    /** The production's zone, which is what decides where a day ends. */
    val timeZone: String = "",
    val weekStartDay: Int? = null,
    val weekEndDay: Int? = null,
) {
    val isClosed: Boolean get() = lockedThrough.isNotBlank()
}
