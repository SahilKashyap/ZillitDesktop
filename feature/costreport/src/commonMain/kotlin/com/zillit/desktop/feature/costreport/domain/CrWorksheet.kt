package com.zillit.desktop.feature.costreport.domain

import com.zillit.desktop.core.common.Money
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.math.roundToLong
import kotlin.time.Instant

/** What a write answered: its value, and the server's own message when it sent one. */
data class CrWrite<T>(val value: T, val message: String? = null)

/**
 * A saved weekly forecast — one row of `GET /weekly-etc/versions?week_ending=`.
 *
 * Versions are filed per week (the Sunday), carry the accountant's ETC and
 * VTP overrides, and are what a post applies as its `etc_version_id`.
 */
data class EtcVersion(
    val id: String,
    val label: String = "",
    val rowCount: Int? = null,
    val savedAtMs: Long? = null,
) {
    /** `End of week 4 · 12 rows · 05 May, 14:07` — the Version picker's option. */
    val optionLabel: String
        get() = buildString {
            append(label.ifBlank { str(S.untitled) })
            rowCount?.takeIf { it > 0 }?.let {
                append(
                    if (it == 1) {
                        str(S.desktop_cr_untitled_rows_one, it)
                    } else {
                        str(S.desktop_cr_untitled_rows_many, it)
                    },
                )
            }
            CrDates.dayMonthTime(savedAtMs).takeIf { it.isNotBlank() }?.let { append(" · $it") }
        }

    /** `End of week 4 · 12 lines` — the Publish dialog's CR Version option. */
    val postOptionLabel: String
        get() = label.ifBlank { id } + (
            rowCount?.let {
                if (it == 1) str(S.desktop_cr_lines_one, it) else str(S.desktop_cr_lines_many, it)
            } ?: ""
            )
}

/** One account's saved overrides. A zero means "not set for that column". */
data class EtcVersionLine(
    val account: String,
    val etcAmount: Double = 0.0,
    val vtpAmount: Double = 0.0,
    /** The display currency the amounts were typed in; null on rows saved before the stamp existed. */
    val currency: String? = null,
)

/**
 * The overrides as a version's lines: one per account that has an ETC or a
 * VTP override, the other column zero — the web's `doSave` / `doSaveVer`.
 */
fun CrOverrides.toVersionLines(): List<EtcVersionLine> =
    (etc.keys + vtp.keys).distinct().map { account ->
        EtcVersionLine(account = account, etcAmount = etc[account] ?: 0.0, vtpAmount = vtp[account] ?: 0.0)
    }

/**
 * A saved version as overrides in the current display currency.
 *
 * Only non-zero amounts become overrides: a zero in a version row means the
 * column was not set, so the cell falls back to its derived value. Each row's
 * amounts are re-expressed from the currency they were typed in —
 * `value × rate(current) ÷ rate(saved)` — so a version saved in pounds still
 * means pounds after the report switches to dollars.
 */
fun overridesFromVersion(lines: List<EtcVersionLine>, displayCurrency: String?, rates: CurrencyRates): CrOverrides {
    val etc = LinkedHashMap<String, Double>()
    val vtp = LinkedHashMap<String, Double>()
    lines.forEach { line ->
        val factor = line.currency?.let { rates.factor(from = it, to = displayCurrency) } ?: 1.0
        val etcValue = line.etcAmount * factor
        val vtpValue = line.vtpAmount * factor
        if (etcValue != 0.0) etc[line.account] = etcValue
        if (vtpValue != 0.0) vtp[line.account] = vtpValue
    }
    return CrOverrides(etc = etc, vtp = vtp)
}

/**
 * Units of each currency per one of the project default (`exr`), default 1.
 * The server converts every source amount; this only moves typed overrides.
 */
data class CurrencyRates(private val byCode: Map<String, Double> = emptyMap()) {
    fun rate(code: String?): Double = code?.let { byCode[it] }?.takeIf { it.isFinite() && it > 0 } ?: 1.0

    fun factor(from: String?, to: String?): Double = rate(to) / rate(from)
}

/**
 * The project's cost-report lock: one boundary date that only moves forward.
 * Everything dated on or before [lockedDate] is closed.
 */
data class CrLockState(
    /** `YYYY-MM-DD`, or null when nothing has been locked. */
    val lockedDate: String? = null,
    val timeZone: String? = null,
    val loaded: Boolean = false,
) {
    /**
     * Whether [week] falls inside the lock: its last day, in the project's zone
     * (London when it has none, as the server assumes), on or before the boundary.
     */
    fun isLocked(week: WeekWindow): Boolean {
        val boundary = lockedDate ?: return false
        val zone = runCatching { TimeZone.of(timeZone ?: DEFAULT_ZONE) }.getOrElse { TimeZone.of(DEFAULT_ZONE) }
        val lastDay = Instant.fromEpochMilliseconds(week.endMs).toLocalDateTime(zone).date.toString()
        return lastDay <= boundary
    }

    companion object {
        const val DEFAULT_ZONE = "Europe/London"

        private val ISO_DATE = Regex("""^\d{4}-\d{2}-\d{2}(?:$|T)""")

        /**
         * Every shape the boundary has been written in: `YYYY-MM-DD` (what the
         * live backend stores), an ISO date-time, or epoch milliseconds sliced
         * in UTC (the reference server's midnight-UTC contract).
         */
        fun normaliseDate(value: String?): String? {
            val text = value?.trim().orEmpty()
            if (text.isEmpty()) return null
            if (ISO_DATE.containsMatchIn(text)) return text.take(ISO_DATE_LENGTH)
            val epoch = text.toDoubleOrNull()?.takeIf { it > 0 } ?: return null
            return Instant.fromEpochMilliseconds(epoch.toLong()).toLocalDateTime(TimeZone.UTC).date.toString()
        }

        private const val ISO_DATE_LENGTH = 10
    }
}

/** The Publish dialog's cadence pills. */
enum class PostCadence(val wire: String, private val labelKey: String, private val progressLabelKey: String) {
    Daily("daily", S.daily, S.desktop_cr_daily_cr),
    Weekly("weekly", S.ce_weekly, S.desktop_cr_week_ending_cr),
    Custom("adhoc", S.custom, S.desktop_cr_custom_cr),
    ;

    val label: String get() = str(labelKey)

    val progressLabel: String get() = str(progressLabelKey)
}

/**
 * One post of the cost report — `POST /snapshots`.
 *
 * Daily and weekly posts let the server resolve the window in the project's
 * zone; a custom post names its own. Empty filters are left off the body, so
 * the server's defaults (consolidated, the live budget) apply.
 */
data class SnapshotPost(
    val cadence: PostCadence,
    val note: String? = null,
    val companyId: String? = null,
    val budgetVersionId: String? = null,
    val currency: String? = null,
    val etcVersionId: String? = null,
    val periodStartMs: Long? = null,
    val periodEndMs: Long? = null,
    /** Set by the lock flow only: "Period Lock — Wk 21 …" is how a lock post is recognised. */
    val name: String? = null,
) {
    /** Why this post cannot be sent, in the web's words, or null. */
    val refusal: String?
        get() = when {
            cadence != PostCadence.Custom -> null
            periodStartMs == null || periodEndMs == null -> str(S.desktop_cr_pick_period)
            periodEndMs < periodStartMs -> str(S.desktop_cr_period_end_after_start)
            else -> null
        }
}

/** What typing into an ETC, EFC or VTP cell does to the overrides, and the warning it raises. */
data class CrEditResult(val overrides: CrOverrides, val warning: String? = null)

/**
 * An accountant's typed value, applied — the web's `commitEdit`.
 *
 * - **ETC** is the free knob. It cannot go below zero (there is no negative
 *   spend left), so a negative is clamped and the reader told.
 * - **EFC** is derived (`actuals + commits + ETC`), so typing one means "set
 *   ETC so the EFC comes out at this": `ETC = EFC − (actuals + commits)`,
 *   clamped at zero with a warning, and any stale EFC override dropped so the
 *   formula takes over again.
 * - **VTP** is stored as typed.
 */
fun CrOverrides.commit(nominal: CrNominal, column: CrColumn, typed: Double, symbol: String): CrEditResult {
    val key = nominal.identity
    return when (column) {
        CrColumn.Etc -> if (typed < 0) {
            CrEditResult(
                set(CrColumn.Etc, key, 0.0),
                str(S.desktop_cr_etc_negative, nominal.code, symbol),
            )
        } else {
            CrEditResult(set(CrColumn.Etc, key, typed))
        }
        CrColumn.Efc -> {
            val a = nominal.actuals()
            val floor = a.atd + a.commits
            val etc = typed - floor
            val cleared = set(CrColumn.Efc, key, null)
            if (etc < 0) {
                CrEditResult(
                    cleared.set(CrColumn.Etc, key, 0.0),
                    str(
                        S.desktop_cr_efc_below_floor,
                        nominal.code,
                        "$symbol${Money.group(floor, if (floor % 1.0 == 0.0) 0 else 2)}",
                    ),
                )
            } else {
                CrEditResult(cleared.set(CrColumn.Etc, key, etc))
            }
        }
        CrColumn.Vtp -> CrEditResult(set(CrColumn.Vtp, key, typed))
        else -> CrEditResult(this)
    }
}

/**
 * The inline calculator every money cell doubles as — the web's
 * `calcExpression`: numbers, `+ − × ÷`, parentheses and unary signs, parsed by
 * hand and never evaluated as code. Anything outside that grammar, a division
 * by zero or a non-finite result is "no value".
 */
object CrCalc {
    private val ALLOWED = Regex("""^[\d.+\-*/()\s,]*$""")
    private val PLAIN = Regex("""^-?\d*\.?\d*$""")

    /** Whether a keystroke may stay in the field. Grouping commas are accepted and ignored. */
    fun allows(text: String): Boolean = ALLOWED.matches(text)

    /** True for an expression (`2*2`, `(1+2)`) rather than a plain number (`-5`). */
    fun isExpression(text: String): Boolean {
        val raw = text.replace(",", "").trim()
        return raw.isNotEmpty() && !PLAIN.matches(raw)
    }

    /** The value of [text] rounded to pennies, or null when it is not one. */
    fun evaluate(text: String): Double? {
        val raw = text.replace(",", "").trim()
        if (raw.isEmpty()) return null
        val value = runCatching { Parser(raw).parse() }.getOrNull() ?: return null
        if (!value.isFinite()) return null
        return (value * PENNIES).roundToLong() / PENNIES
    }

    private const val PENNIES = 100.0

    private class Parser(private val text: String) {
        private var at = 0

        fun parse(): Double {
            val value = expression()
            skipSpaces()
            require(at == text.length) { "trailing input" }
            return value
        }

        private fun expression(): Double {
            var value = term()
            while (true) {
                skipSpaces()
                value = when (peek()) {
                    '+' -> { at++; value + term() }
                    '-' -> { at++; value - term() }
                    else -> return value
                }
            }
        }

        private fun term(): Double {
            var value = factor()
            while (true) {
                skipSpaces()
                value = when (peek()) {
                    '*' -> { at++; value * factor() }
                    '/' -> {
                        at++
                        val divisor = factor()
                        require(divisor != 0.0) { "division by zero" }
                        value / divisor
                    }
                    else -> return value
                }
            }
        }

        private fun factor(): Double {
            skipSpaces()
            return when (peek()) {
                '+' -> { at++; factor() }
                '-' -> { at++; -factor() }
                '(' -> {
                    at++
                    val inner = expression()
                    skipSpaces()
                    require(peek() == ')') { "unbalanced" }
                    at++
                    inner
                }
                else -> number()
            }
        }

        private fun number(): Double {
            val start = at
            while (at < text.length && (text[at].isDigit() || text[at] == '.')) at++
            val token = text.substring(start, at)
            require(token.isNotEmpty() && token != "." && token.count { it == '.' } <= 1) { "bad number" }
            return token.toDouble()
        }

        private fun peek(): Char? = text.getOrNull(at)

        private fun skipSpaces() {
            while (at < text.length && text[at].isWhitespace()) at++
        }
    }
}
