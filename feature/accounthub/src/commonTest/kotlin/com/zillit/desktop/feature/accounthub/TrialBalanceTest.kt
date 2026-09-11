package com.zillit.desktop.feature.accounthub

import com.zillit.desktop.feature.accounthub.data.BibleAccountDto
import com.zillit.desktop.feature.accounthub.data.BibleReportDto
import com.zillit.desktop.feature.accounthub.data.PeriodLockDto
import com.zillit.desktop.feature.accounthub.data.TrialBalanceRowDto
import com.zillit.desktop.feature.accounthub.domain.LedgerSource
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

    /** Expense reads last; everything else alphabetically by label. */
    @Test
    fun `expense is pinned to the bottom`() {
        val report = TrialBalance(
            listOf(
                row("1", "expense"),
                row("2", "liability"),
                row("3", "asset"),
                row("4", "income"),
            ),
        )

        assertEquals(
            listOf("Asset", "Income", "Liability", "Expense"),
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

/**
 * The closeout bible.
 *
 * The report a production's books are checked against line by line. Two things
 * must not go wrong: a failed bucket must be visible, and the uncoded pile
 * must be named rather than shown as the sentinel it arrives as.
 */
class BibleReportTest {

    private val json = Json { ignoreUnknownKeys = true }

    /** The uncoded bucket is real money, and gets a real name. */
    @Test
    fun `the uncoded sentinel is named, not printed`() {
        val account = json.decodeFromString(
            BibleAccountDto.serializer(),
            """{"code":"__uncoded__","name":"__uncoded__","total":420.5,"transactions":[]}""",
        ).toDomain()

        assertTrue(account.isUncoded)
        assertEquals("Uncoded", account.displayCode)
        assertEquals("", account.displayName, "the sentinel is not a name either")
        assertEquals(420.5, account.total)
    }

    /**
     * A bucket the server could not read is surfaced.
     *
     * A total that quietly omits payroll is worse than one that says payroll
     * is missing — this is what a production closes its books against.
     */
    @Test
    fun `a failed bucket is carried, not swallowed`() {
        val report = json.decodeFromString(
            BibleReportDto.serializer(),
            """{"accounts":[],"errors":{"payroll":"timed out"}}""",
        ).toDomain()

        assertEquals(mapOf("payroll" to "timed out"), report.errors)
    }

    /**
     * The grand total is the server's where it gives one.
     *
     * Summing the accounts would silently report a smaller book whenever a
     * bucket failed, because those accounts are simply absent.
     */
    @Test
    fun `the server's total wins over a client-side sum`() {
        val given = json.decodeFromString(
            BibleReportDto.serializer(),
            """{"grand_total":1000,"accounts":[{"code":"100","total":250}]}""",
        ).toDomain()
        assertEquals(1000.0, given.grandTotal)

        val derived = json.decodeFromString(
            BibleReportDto.serializer(),
            """{"accounts":[{"code":"100","total":250},{"code":"200","total":250}]}""",
        ).toDomain()
        assertEquals(500.0, derived.grandTotal, "only summed when the server gives no total")
    }

    @Test
    fun `transactions read their references and their original currency`() {
        val report = json.decodeFromString(
            BibleReportDto.serializer(),
            """{"currency":"GBP","accounts":[{"code":"7100","name":"Camera hire","total":-50,
               "transactions":[{"src":"invoice","eff_date":"1750000000000","invoice_number":"INV-9",
               "po_number":"PO-3","vendor":"Panavision","description":"Lens set","currency":"USD",
               "amount":-50}]}]}""",
        ).toDomain()

        val txn = report.accounts.single().transactions.single()
        assertEquals("INV-9", txn.invoiceNumber)
        assertEquals("PO-3", txn.purchaseOrderNumber)
        assertEquals("Panavision", txn.party)
        assertEquals("USD", txn.originalCurrency, "what it was raised in")
        assertEquals("GBP", report.currencyCode, "what the amounts were converted to")
        assertEquals(1, report.transactionCount)
    }

    /** A source the server sends and this client does not model still reads. */
    @Test
    fun `an unmodelled source is humanised rather than hidden`() {
        assertEquals("Invoice", LedgerSource.labelFor("invoice"))
        assertEquals("Manual Journal", LedgerSource.labelFor("manual_je"))
        assertEquals("Something new", LedgerSource.labelFor("something_new"))
    }
}
