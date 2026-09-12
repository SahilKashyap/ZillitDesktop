package com.zillit.desktop.feature.accounthub

import com.zillit.desktop.feature.accounthub.data.PeriodLockDto
import com.zillit.desktop.feature.accounthub.data.TrialBalanceRowDto
import com.zillit.desktop.feature.accounthub.domain.TrialBalance
import com.zillit.desktop.feature.accounthub.domain.TrialBalanceRow
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The report an accountant checks the ledger against.
 *
 * Two things have to be right: the order it reads in, and whether it says the
 * ledger balances. A report that claims an imbalance nobody has is worse than
 * no report — it sends somebody hunting for a penny that is not missing.
 */
class TrialBalanceTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun row(
        code: String,
        type: String,
        debit: Double = 0.0,
        credit: Double = 0.0,
        ending: Double = 0.0,
    ) = TrialBalanceRow(
        accountCode = code,
        name = code,
        costType = type,
        debit = debit,
        credit = credit,
        ending = ending,
    )

    /** Expense reads first, as the web draws it; everything else alphabetically by label. */
    @Test
    fun `expense is pinned to the top`() {
        val report = TrialBalance(
            listOf(
                row("1", "liability"),
                row("2", "asset"),
                row("3", "expense"),
                row("4", "income"),
            ),
        )

        assertEquals(
            listOf("Expense", "Asset", "Income", "Liability"),
            report.groups.map { it.label },
        )
    }

    /** A code sorts as a number where it is one. */
    @Test
    fun `codes sort numerically within a group`() {
        val report = TrialBalance(listOf(row("100", "asset"), row("20", "asset"), row("3", "asset")))

        assertEquals(listOf("3", "20", "100"), report.groups.single().rows.map { it.accountCode })
    }

    /** A row with no cost type is grouped, not dropped. */
    @Test
    fun `an uncategorised row still shows`() {
        val report = TrialBalance(listOf(row("900", "", ending = 50.0)))

        assertEquals("Uncategorised", report.groups.single().label)
        assertEquals(50.0, report.balance)
    }

    /**
     * Balance is judged to the half-penny.
     *
     * These are sums of converted decimals. Demanding exact equality would
     * report a balanced ledger as broken over a rounding tail.
     */
    @Test
    fun `a rounding tail is still balanced`() {
        val exact = TrialBalance(listOf(row("1", "asset", debit = 100.0), row("2", "income", credit = 100.0)))
        val tail = TrialBalance(listOf(row("1", "asset", debit = 100.0), row("2", "income", credit = 100.004)))
        val real = TrialBalance(listOf(row("1", "asset", debit = 100.0), row("2", "income", credit = 99.0)))

        assertTrue(exact.isBalanced)
        assertTrue(tail.isBalanced, "four thousandths is rounding, not an imbalance")
        assertTrue(!real.isBalanced)
    }

    /** Subtotals are per group; the grand total is over every row. */
    @Test
    fun `totals add up by group and overall`() {
        val report = TrialBalance(
            listOf(
                row("1", "asset", debit = 60.0, ending = 60.0),
                row("2", "asset", debit = 40.0, ending = 40.0),
                row("3", "income", credit = 100.0, ending = -100.0),
            ),
        )

        assertEquals(100.0, report.groups.first { it.costType == "asset" }.balance)
        assertEquals(100.0, report.debit)
        assertEquals(100.0, report.credit)
        assertEquals(0.0, report.balance)
    }

    /**
     * A code can come back as the string "null".
     *
     * Rendering that literally puts the word null in an accounts column.
     */
    @Test
    fun `the string null is not a code`() {
        val parsed = json.decodeFromString(
            TrialBalanceRowDto.serializer(),
            """{"account_code":"null","description":"Suspense","cost_type":"asset","ending":12.5}""",
        ).toDomain()

        assertEquals("", parsed.accountCode)
        assertEquals("Suspense", parsed.name)
        assertEquals(12.5, parsed.ending)
    }

    /** The closing balance is the server's, not debit minus credit re-derived. */
    @Test
    fun `the ending balance is taken as given`() {
        val parsed = json.decodeFromString(
            TrialBalanceRowDto.serializer(),
            """{"account_code":"100","debit":500,"credit":100,"ending":900}""",
        ).toDomain()

        assertEquals(900.0, parsed.ending, "an opening balance is in there too")
    }
}

/**
 * The lock that closes a period.
 *
 * One boundary, forward only, with no way back — which is why the screen asks
 * twice and this reads the state carefully.
 */
class PeriodLockTest {

    private val json = Json { ignoreUnknownKeys = true }

    /** The read route says `lockedDate`; the write route says the other thing. */
    @Test
    fun `both spellings of the boundary load`() {
        val read = json.decodeFromString(
            PeriodLockDto.serializer(),
            """{"lockedDate":"2026-03-29","tz":"Europe/London","week":{"start_day_of_week":1,"end_day_of_week":7}}""",
        ).toDomain()

        assertEquals("2026-03-29", read.lockedThrough)
        assertEquals("Europe/London", read.timeZone)
        assertEquals(1, read.weekStartDay)
        assertTrue(read.isClosed)

        val written = json.decodeFromString(
            PeriodLockDto.serializer(),
            """{"last_cr_locked_date":"2026-04-05","previous":"2026-03-29"}""",
        ).toDomain()

        assertEquals("2026-04-05", written.lockedThrough)
        assertTrue(written.isClosed)
    }

    /** A production that has closed nothing is not a production closed to nothing. */
    @Test
    fun `no boundary is not a closed period`() {
        val none = json.decodeFromString(PeriodLockDto.serializer(), """{"lockedDate":null}""").toDomain()

        assertEquals("", none.lockedThrough)
        assertTrue(!none.isClosed)
    }
}

// The bible's tests are in BibleReportTest.kt.
