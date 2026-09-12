package com.zillit.desktop.feature.accounthub

import com.zillit.desktop.feature.accounthub.data.TrialBalanceRowDto
import com.zillit.desktop.feature.accounthub.data.rows
import com.zillit.desktop.feature.accounthub.domain.AccountCodeOrder
import com.zillit.desktop.feature.accounthub.domain.CurrencySettings
import com.zillit.desktop.feature.accounthub.domain.LedgerMoney
import com.zillit.desktop.feature.accounthub.domain.PeriodLock
import com.zillit.desktop.feature.accounthub.domain.PeriodMode
import com.zillit.desktop.feature.accounthub.domain.ProjectCurrency
import com.zillit.desktop.feature.accounthub.domain.TrialBalance
import com.zillit.desktop.feature.accounthub.domain.TrialBalanceFilters
import com.zillit.desktop.feature.accounthub.domain.TrialBalancePeriod
import com.zillit.desktop.feature.accounthub.domain.TrialBalanceRow
import com.zillit.desktop.feature.accounthub.ui.AccountHubUiState
import com.zillit.desktop.feature.accounthub.ui.PeriodCloseState
import com.zillit.desktop.feature.accounthub.ui.SectionEdit
import com.zillit.desktop.feature.accounthub.ui.SetupState
import com.zillit.desktop.feature.accounthub.ui.TrialBalanceState
import com.zillit.desktop.feature.accounthub.ui.trialBalanceCurrencies
import com.zillit.desktop.feature.accounthub.ui.trialBalanceDirty
import com.zillit.desktop.feature.accounthub.ui.trialBalanceDraft
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The trial balance's rules against the web's `TrialBalanceModule`: the order
 * it reads in, the period it asks for, how loosely it reads a row, and how it
 * prints a figure.
 */
class TrialBalanceRulesTest {

    private fun row(code: String, type: String = "asset") =
        TrialBalanceRow(accountCode = code, name = code, costType = type)

    // -- order ------------------------------------------------------------------

    /** `localeCompare(…, { numeric: true })`, run on the same codes in node, gives exactly this. */
    @Test
    fun `codes sort the way the web's numeric locale compare sorts them`() {
        val codes = listOf("1000", "200", "1000-01", "ABC", "20A", "30", "100")

        assertEquals(listOf("20A", "30", "100", "200", "1000", "1000-01", "ABC"), codes.sortedWith(AccountCodeOrder))
    }

    /**
     * The order is a real order: any shuffle of the same codes sorts the same.
     *
     * The comparator this replaced compared two codes as numbers only when both
     * parsed, which is not transitive once codes mix digits and letters — and a
     * sort handed such a comparator may throw rather than merely misorder.
     */
    @Test
    fun `the code order is consistent whatever order the rows arrive in`() {
        val codes = listOf("30", "100", "20A", "ab", "AB", "007", "7", "", "5000-10", "5000-9", "X1", "x10", "X2")
        val expected = codes.sortedWith(AccountCodeOrder)
        repeat(50) { seed ->
            assertEquals(expected, codes.shuffled(kotlin.random.Random(seed)).sortedWith(AccountCodeOrder))
        }
        assertEquals("", expected.first(), "a blank code reads first, as an empty string does on the web")
        assertTrue(expected.indexOf("5000-9") < expected.indexOf("5000-10"), "digit runs inside a code are numbers")
    }

    /**
     * The order the web draws, not the one its comment promises: its Expense
     * key `"~~~~"` sorts before letters under `localeCompare`. Node, given the
     * module's own `sortKey`/`typeLabel` and these keys, prints exactly this
     * (the web's unknown type reads "cost of sales"; both clients upper-case it).
     */
    @Test
    fun `groups read expense first, then alphabetically, as the web draws them`() {
        val report = TrialBalance(
            listOf(
                row("1", "liability"),
                row("2", "asset"),
                row("3", ""),
                row("4", "income"),
                row("5", "expense"),
                row("6", "capital"),
                row("7", "cost_of_sales"),
            ),
        )

        assertEquals(
            listOf("Expense", "Asset", "Capital", "Cost of sales", "Income", "Liability", "Uncategorised"),
            report.groups.map { it.label },
        )
    }

    // -- the period -------------------------------------------------------------

    /** The web's `ymdToMs`: local midnight on the first day, 23:59:59.999 on the last. */
    @Test
    fun `a period runs from local midnight to the last millisecond of its last day`() {
        val zone = TimeZone.of("Asia/Kolkata")
        val filters = TrialBalanceFilters(from = "2026-09-06", to = "2026-09-12")

        val query = filters.toQuery(zone)!!

        assertEquals(LocalDate(2026, 9, 6).atStartOfDayIn(zone).toEpochMilliseconds(), query.periodStartMillis)
        assertEquals(LocalDate(2026, 9, 13).atStartOfDayIn(zone).toEpochMilliseconds() - 1, query.periodEndMillis)
    }

    /**
     * The floor stays a positive instant east of UTC — the web moved off 1970
     * because the service refuses a negative `period_start`.
     */
    @Test
    fun `the open-ended floor is a positive instant in the zones furthest east`() {
        val furthestEast = TimeZone.of("Pacific/Kiritimati")
        val query = TrialBalanceFilters(from = TrialBalancePeriod.FLOOR, to = "2026-09-12").toQuery(furthestEast)!!

        assertTrue(query.periodStartMillis > 0)
    }

    @Test
    fun `nothing is asked for while a day does not read or the range runs backwards`() {
        val zone = TimeZone.UTC
        assertNull(TrialBalanceFilters(from = "2026-09-0", to = "2026-09-12").toQuery(zone), "half typed")
        assertNull(TrialBalanceFilters(from = "2026-02-30", to = "2026-03-12").toQuery(zone), "not a day")
        assertNull(TrialBalanceFilters(from = "2026-09-13", to = "2026-09-12").toQuery(zone), "backwards")
        assertTrue(TrialBalanceFilters(from = "2026-09-12", to = "2026-09-12").hasValidPeriod, "one day is a period")
    }

    /** The web's `applied.currency || defaultCode`: the first report is already in the production's currency. */
    @Test
    fun `a blank currency asks in the production default, and a chosen one is kept`() {
        val zone = TimeZone.UTC
        val base = TrialBalanceFilters(
            from = "2026-01-01",
            to = "2026-09-12",
            accountStart = "1000",
            companyId = "co-1",
        )

        assertEquals("GBP", base.toQuery(zone, defaultCurrency = "GBP")!!.currency)
        assertEquals("USD", base.copy(currency = "USD").toQuery(zone, defaultCurrency = "GBP")!!.currency)
        assertEquals("1000", base.toQuery(zone)!!.accountStart)
        assertEquals("co-1", base.toQuery(zone)!!.companyId)
        assertFalse(base.toQuery(zone)!!.includeZeroAccounts, "false is carried, not left for the server to default")
    }

    @Test
    fun `the period reads as the web prints it`() {
        assertEquals("6 Sep 2026", TrialBalancePeriod.label("2026-09-06"))
        assertEquals("—", TrialBalancePeriod.label("soon"))
        val floor = TrialBalancePeriod.FLOOR
        assertEquals(
            "Till 12 Sep 2026",
            TrialBalanceFilters(mode = PeriodMode.Current, from = floor, to = "2026-09-12").periodLabel,
        )
        assertEquals(
            "6 Sep 2026 – 12 Sep 2026",
            TrialBalanceFilters(mode = PeriodMode.Current, from = "2026-09-06", to = "2026-09-12").periodLabel,
        )
        assertEquals(
            "1 Jan 2026 – 12 Sep 2026",
            TrialBalanceFilters(mode = PeriodMode.Custom, from = "2026-01-01", to = "2026-09-12").periodLabel,
        )
    }

    // -- the filter bar ---------------------------------------------------------

    private fun hub(trial: TrialBalanceState, lockedThrough: String = "") = AccountHubUiState(
        trialBalance = trial,
        periodClose = PeriodCloseState(lock = PeriodLock(lockedThrough = lockedThrough)),
    )

    @Test
    fun `current period starts at the last closed day, or at the floor while nothing is closed`() {
        val trial = TrialBalanceState(today = "2026-09-12", fromText = "2026-01-01", toText = "2026-03-31")

        assertEquals("2026-09-06", hub(trial, lockedThrough = "2026-09-06").trialBalanceDraft.from)
        assertEquals(TrialBalancePeriod.FLOOR, hub(trial).trialBalanceDraft.from)
        assertEquals("2026-09-12", hub(trial).trialBalanceDraft.to)

        val custom = hub(trial.copy(periodMode = PeriodMode.Custom), lockedThrough = "2026-09-06").trialBalanceDraft
        assertEquals(
            "2026-01-01" to "2026-03-31",
            custom.from to custom.to,
            "Date Range reads the pickers, not the lock",
        )
    }

    /** The draft trims, as the web's does, so a stray space is not a change worth a Refresh. */
    @Test
    fun `a space typed into an account code does not ask for a refresh`() {
        val trial = TrialBalanceState(today = "2026-09-12", accountFromText = "1000")
        val applied = hub(trial).trialBalanceDraft

        val spaced = hub(trial.copy(accountFromText = "1000 ", applied = applied))
        assertFalse(spaced.trialBalanceDirty)
        assertEquals("1000 ", spaced.trialBalance.accountFromText, "what was typed stays as typed")

        assertTrue(hub(trial.copy(accountFromText = "2000", applied = applied)).trialBalanceDirty)
        assertTrue(hub(trial.copy(periodMode = PeriodMode.Custom, applied = applied)).trialBalanceDirty)
        assertFalse(hub(trial).trialBalanceDirty, "nothing applied yet is nothing to refresh against")
    }

    /** The web's `useCurrencyOptions`: the production's own, default first; the catalogue while it has none. */
    @Test
    fun `the currency filter offers the production's currencies, and the catalogue while it has picked none`() {
        val picked = AccountHubUiState(
            setup = SetupState(
                currencies = SectionEdit(
                    CurrencySettings(
                        currencies = listOf(
                            ProjectCurrency("USD", symbol = "$"),
                            ProjectCurrency("GBP", symbol = "£"),
                        ),
                        defaultCode = "GBP",
                    ),
                ),
                currencyCatalogue = listOf(ProjectCurrency("EUR"), ProjectCurrency("INR")),
            ),
        )
        assertEquals(listOf("GBP", "USD"), picked.trialBalanceCurrencies.map { it.code })

        val none = picked.copy(setup = picked.setup.copy(currencies = SectionEdit(CurrencySettings())))
        assertEquals(listOf("EUR", "INR"), none.trialBalanceCurrencies.map { it.code })
    }

    // -- reading a row ----------------------------------------------------------

    private val strict = Json { ignoreUnknownKeys = true }

    /**
     * The web reads every figure through `Number(v) || 0` and the code through
     * `String(v)`. A figure sent as a string — how a NUMERIC column leaves a Node
     * service — must still land, not drop the account from the ledger.
     */
    @Test
    fun `a figure sent as a string and a code sent as a number still read`() {
        val parsed = strict.decodeFromString(
            TrialBalanceRowDto.serializer(),
            """{"account_code":4000,"description":"","name":"Rent","cost_type":"expense",
               "debit":"1250.50","credit":null,"ending":"-12.5"}""",
        ).toDomain()

        assertEquals("4000", parsed.accountCode)
        assertEquals("Rent", parsed.name, "a blank description falls back to the name")
        assertEquals(1250.5, parsed.debit)
        assertEquals(0.0, parsed.credit)
        assertEquals(-12.5, parsed.ending)
    }

    /** Every row of a bare array arrives, whatever shape each figure takes. */
    @Test
    fun `no row of a mixed answer is dropped`() {
        val answer = strict.parseToJsonElement(
            """[{"account_code":"1000","cost_type":"asset","debit":100,"ending":100},
                {"account_code":2000,"cost_type":"liability","credit":"100.00","ending":"-100"},
                {"account_code":"null","cost_type":"asset","debit":"abc"}]""",
        )

        val read = answer.rows(TrialBalanceRowDto.serializer()).map { it.toDomain() }

        assertEquals(3, read.size)
        assertEquals(listOf("1000", "2000", ""), read.map { it.accountCode })
        assertEquals(0.0, read[2].debit, "not a number reads as nothing, as Number('abc') || 0 does")
        assertTrue(TrialBalance(read).isBalanced)
    }

    // -- printing a figure ------------------------------------------------------

    @Test
    fun `figures print with the symbol, two decimals, grouping, and brackets for a negative`() {
        assertEquals("£1,234.50", LedgerMoney.format(1234.5, "£"))
        assertEquals("(£1,234.57)", LedgerMoney.format(-1234.567, "£"))
        assertEquals("0.00", LedgerMoney.format(0.0))
        assertEquals("1,000,000,000.00", LedgerMoney.format(1_000_000_000.0))
        assertEquals("12,345,678.91", LedgerMoney.format(12_345_678.905), "past 10⁷ the JVM prints an exponent")
        assertEquals("999.99", LedgerMoney.format(999.994))
    }

    /**
     * Rounded as `toLocaleString("en-GB", { maximumFractionDigits: 2 })`
     * rounds — every expectation here is what node prints for the same value.
     * `round(value * 100)` gets the first two wrong (`1.005 * 100` is
     * `100.49999999999999`) and the third too (an exact half goes to even).
     */
    @Test
    fun `a half penny rounds away from zero, as the web's formatter does`() {
        assertEquals("1.01", LedgerMoney.format(1.005))
        assertEquals("2.68", LedgerMoney.format(2.675))
        assertEquals("0.13", LedgerMoney.format(0.125))
        assertEquals("0.01", LedgerMoney.format(0.005))
        assertEquals("0.00", LedgerMoney.format(0.0049))
    }

    /**
     * A balanced total is a sum of converted decimals, and lands on a hair
     * below zero as easily as on zero. Printed as the web prints it, that is
     * "(£0.00)" in red under the word Balanced.
     */
    @Test
    fun `a negative that rounds to nothing is not bracketed`() {
        // The rows' own order: summed this way the ledger misses zero.
        val balance = 1_487_654_321.1 + -987_654_321.55 + -499_999_999.55
        assertTrue(balance < 0.0, "the sum really does fall below zero")

        assertEquals("¥0.00", LedgerMoney.format(balance, "¥"))
        assertEquals(0L, LedgerMoney.pence(balance))
        assertEquals("£0.00", LedgerMoney.format(-0.004, "£"))
        assertEquals("(£0.01)", LedgerMoney.format(-0.005, "£"), "a real half penny is still a negative")
    }
}
