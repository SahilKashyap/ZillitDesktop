package com.zillit.desktop.feature.accounthub

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.accounthub.data.BibleAccountDto
import com.zillit.desktop.feature.accounthub.data.BibleReportDto
import com.zillit.desktop.feature.accounthub.data.laterLock
import com.zillit.desktop.feature.accounthub.data.normalizeLockedDate
import com.zillit.desktop.feature.accounthub.data.settingsPeriodLock
import com.zillit.desktop.feature.accounthub.data.toBibleReport
import com.zillit.desktop.feature.accounthub.data.toExportBody
import com.zillit.desktop.feature.accounthub.data.toPeriodLock
import com.zillit.desktop.feature.accounthub.data.toQueryParameters
import com.zillit.desktop.feature.accounthub.domain.BibleAccount
import com.zillit.desktop.feature.accounthub.domain.BibleAccountTypes
import com.zillit.desktop.feature.accounthub.domain.BibleFilters
import com.zillit.desktop.feature.accounthub.domain.BibleFormat
import com.zillit.desktop.feature.accounthub.domain.BiblePeriod
import com.zillit.desktop.feature.accounthub.domain.BibleQuery
import com.zillit.desktop.feature.accounthub.domain.BibleReport
import com.zillit.desktop.feature.accounthub.domain.LedgerSource
import com.zillit.desktop.feature.accounthub.domain.PeriodLock
import com.zillit.desktop.feature.accounthub.domain.PeriodMode
import com.zillit.desktop.feature.accounthub.domain.SourceBadge
import com.zillit.desktop.feature.accounthub.domain.TaxType
import com.zillit.desktop.feature.accounthub.domain.bibleTaxOptions
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The closeout bible.
 *
 * The report a production's books are checked against line by line. Two things
 * must not go wrong: a failed bucket must be visible, and the uncoded pile
 * must be named rather than shown as the sentinel it arrives as.
 */
class BibleReportTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun decode(body: String): BibleReport {
        val result = json.parseToJsonElement(body).toBibleReport()
        return assertIs<ZillitResult.Success<BibleReport>>(result).data
    }

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
        assertEquals("Uncoded", account.title)
        assertEquals(420.5, account.total)
        assertEquals("0 entries", account.entriesLabel)
    }

    /**
     * The service's other buckets are named the way the web's cost-report
     * adapter names them, and never printed — `__fringes_unallocated__` showed
     * verbatim on the live develop report on 2026-09-13.
     */
    @Test
    fun `service buckets are named, not printed`() {
        fun bucket(code: String, name: String = code) = BibleAccount(code = code, name = name)

        val fringes = bucket("__fringes_unallocated__")
        assertTrue(fringes.isInternalKey)
        assertEquals("", fringes.displayCode, "a bucket has no chart code to print")
        assertEquals("Fringes — Unallocated", fringes.displayName)
        assertEquals("Fringes — Unallocated", fringes.title)

        assertEquals("Payroll — Unallocated", bucket("__payroll_unallocated__").title)
        assertEquals("Budget — Unallocated", bucket("__uncoded_budget__").title)
        assertEquals("Non-Allocated Items", bucket("__unallocated__").title)
        assertEquals("Production Insurance", bucket("__unallocated__:Production Insurance").title)
        assertEquals("Cash unposted", bucket("__cash_unposted__").title, "an unknown key is spelled out, not printed")
        assertEquals("Payroll — Unallocated", bucket("__payroll_unallocated__", name = "").title)
        assertEquals(
            "Payroll awaiting codes",
            bucket("__payroll_unallocated__", name = "Payroll awaiting codes").title,
            "a real name from the server wins over the fallback",
        )
    }

    /** Only the double-underscore convention is masked: a mis-coded account must stay visible to be re-coded. */
    @Test
    fun `a mis-coded account keeps its code`() {
        val miscoded = BibleAccount(code = "art_4110", name = "Art department")
        assertFalse(miscoded.isInternalKey)
        assertEquals("art_4110", miscoded.displayCode)
        assertEquals("art_4110 · Art department", miscoded.title)
        assertEquals("7100 · Camera hire", BibleAccount(code = "7100", name = "Camera hire").title)
        assertEquals("—", BibleAccount(code = "", name = "").title, "a missing code still reads as missing")
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

    /** A failure sent as an error object is read as its message, and does not fail the whole report. */
    @Test
    fun `a bucket failure sent as an object still reads as words`() {
        val report = decode(
            """{"status":1,"data":{"accounts":[{"code":"100","total":5}],
               "errors":{"card":{"message":"card service unavailable","code":503}}}}""".extractData(),
        )
        assertEquals("card service unavailable", report.errors["card"])
        assertEquals(1, report.accounts.size, "the accounts that did come back are still on screen")
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

        val camel = decode("""{"grandTotal":"750.25","accounts":[{"code":"100","total":250}]}""")
        assertEquals(750.25, camel.grandTotal, "the web reads grandTotal too, and a quoted figure is still a figure")

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
               "transactions":[{"src":"INV","eff_date":"1750000000000","invoice_number":"INV-9",
               "po_number":"PO-3","vendor":"Panavision","description":"Lens set","currency":"USD",
               "amount":-50}]}]}""",
        ).toDomain()

        val txn = report.accounts.single().transactions.single()
        assertEquals("INV-9", txn.invoiceNumber)
        assertEquals("PO-3", txn.purchaseOrderNumber)
        assertEquals("Panavision", txn.party)
        assertEquals("USD", txn.originalCurrency, "what it was raised in")
        assertEquals(1_750_000_000_000L, txn.effectiveDateMillis)
        assertEquals("GBP", report.currencyCode, "what the amounts were converted to")
        assertEquals(1, report.transactionCount)
    }

    /**
     * The service sends the report bare in `data`, as the web reads it
     * (`raw.data`). The desktop once decoded only `{ value: … }`, and every run
     * came back "No transactions found" over a full ledger.
     */
    @Test
    fun `the report is read bare, as the cost-report service sends it, and wrapped too`() {
        val body = """{"accounts":[{"code":"7100","name":"Camera hire","total":"1000" ,
            "transactions":[{"src":"CARD","amount":"99.50","currency":{"code":"EUR","symbol":"€"}}]}],
            "currency":{"code":"GBP","name":"Pound","symbol":"£"},"generatedAt":1757700000000,
            "filters":{"period_start":"946684800000","period_end":1757721599999,"account_start":"7000",
            "include_open_pos":false}}"""
        val bare = decode(body)
        val wrapped = decode("""{"value":$body}""")

        assertEquals(bare, wrapped)
        assertEquals("7100", bare.accounts.single().code)
        assertEquals(99.5, bare.accounts.single().transactions.single().amount)
        assertEquals("EUR", bare.accounts.single().transactions.single().originalCurrency)
        assertEquals("GBP", bare.currencyCode, "a currency object is read as its code rather than failing the report")
        assertEquals(1_757_700_000_000L, bare.generatedAtMillis)
        val echo = bare.echo!!
        assertEquals(946_684_800_000L, echo.periodStartMillis)
        assertEquals(1_757_721_599_999L, echo.periodEndMillis)
        assertEquals("7000", echo.accountStart)
        assertEquals(false, echo.includeOpenPurchaseOrders)
    }

    /** A source the server sends and this client does not model still reads. */
    @Test
    fun `an unmodelled source is humanised rather than hidden`() {
        assertEquals("Invoice", LedgerSource.labelFor("invoice"))
        assertEquals("Manual Journal", LedgerSource.labelFor("manual_je"))
        assertEquals("Something new", LedgerSource.labelFor("something_new"))
    }

    /** The server's six codes get their own tag; a filter wire name maps to the same one; anything else says itself. */
    @Test
    fun `source tags read the service's codes and the filter vocabulary alike`() {
        assertEquals(SourceBadge.Invoice, SourceBadge.from("INV"))
        assertEquals(SourceBadge.Credit, SourceBadge.from("cred"))
        assertEquals(SourceBadge.Payroll, SourceBadge.from("PR"))
        assertEquals(SourceBadge.Card, SourceBadge.from("card"))
        assertEquals("PO", SourceBadge.textFor("po"))
        assertEquals("JE", SourceBadge.textFor("manual_je"))
        assertEquals("ADJ", SourceBadge.textFor("ADJ"))
        assertEquals("—", SourceBadge.textFor(" "))
        assertNull(SourceBadge.from("ADJ"))
    }

    @Test
    fun `the account type filter adds unclassified and reads alphabetically`() {
        assertEquals(
            listOf("Asset", "Capital", "Expense", "Income", "Liability", "Unclassified"),
            BibleAccountTypes.map { it.label },
        )
        assertEquals("unclassified", BibleAccountTypes.last().value)
    }

    /** The web appends `other`: lines taxed at a custom rate are stored under it and must stay reachable. */
    @Test
    fun `the tax filter keys on identifiers, prints the rate and offers other`() {
        val options = bibleTaxOptions(
            listOf(
                TaxType(identifier = "GB_standard", label = "Standard rate", value = "20"),
                TaxType(identifier = "custom_1", label = "Reduced", value = "12.5%"),
                TaxType(identifier = "", label = "Unsaved row", value = "5"),
                TaxType(identifier = "GB_exempt", label = "Exempt", value = ""),
            ),
        )
        assertEquals(listOf("GB_standard", "custom_1", "GB_exempt", "other"), options.map { it.value })
        assertEquals(listOf("Standard rate · 20%", "Reduced · 12.5%", "Exempt", "Other"), options.map { it.label })
    }

    /** Brackets for a credit with the symbol inside them, two decimals, grouped — the web's `fmtAmount`. */
    @Test
    fun `money prints as an accountant writes it`() {
        assertEquals("£1,234.50", BibleFormat.money(1234.5, "£"))
        assertEquals("(£1,234,567.00)", BibleFormat.money(-1_234_567.0, "£"))
        assertEquals("0.01", BibleFormat.money(0.005, ""), "half a penny rounds up, not to even")
        assertEquals("$0.00", BibleFormat.money(0.0, "$"))
    }

    @Test
    fun `dates print short and export stamps are local`() {
        val utc = TimeZone.UTC
        val millis = LocalDate(2026, 9, 7).atStartOfDayIn(utc).toEpochMilliseconds() + 83_000_000L
        assertEquals("07/09/26", BibleFormat.shortDate(millis, utc))
        assertEquals("—", BibleFormat.shortDate(null, utc))
        assertEquals("2026-09-07_2303", BibleFormat.exportStamp(millis, utc))

        val kolkata = TimeZone.of("Asia/Kolkata")
        assertEquals("08/09/26", BibleFormat.shortDate(millis, kolkata), "the reader's day, not UTC's")
        assertEquals("2026-09-08_0433", BibleFormat.exportStamp(millis, kolkata))
    }

    // -- the period ------------------------------------------------------------

    private val today = LocalDate(2026, 9, 12)

    @Test
    fun `current period runs from the lock date through the end of today`() {
        val zone = TimeZone.of("Asia/Kolkata")
        val period = BiblePeriod.current("2026-09-06", today, zone)
        assertEquals(LocalDate(2026, 9, 6).atStartOfDayIn(zone).toEpochMilliseconds(), period.startMillis)
        assertEquals(LocalDate(2026, 9, 13).atStartOfDayIn(zone).toEpochMilliseconds() - 1, period.endMillis)
    }

    @Test
    fun `current period with nothing closed runs from the floor`() {
        val period = BiblePeriod.current("", today, TimeZone.UTC)
        assertEquals(LocalDate(2000, 1, 1).atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds(), period.startMillis)
        assertEquals("Till 12 Sep 2026", BiblePeriod.label(BibleFilters(), "", today))
        assertEquals("6 Sep 2026 – 12 Sep 2026", BiblePeriod.label(BibleFilters(), "2026-09-06", today))
    }

    @Test
    fun `a date range covers whole local days and refuses half-typed or backwards ranges`() {
        val zone = TimeZone.of("America/Los_Angeles")
        val range = BiblePeriod.custom("2026-01-01", "2026-01-31", zone)!!
        assertEquals(LocalDate(2026, 1, 1).atStartOfDayIn(zone).toEpochMilliseconds(), range.startMillis)
        assertEquals(LocalDate(2026, 2, 1).atStartOfDayIn(zone).toEpochMilliseconds() - 1, range.endMillis)

        assertNull(BiblePeriod.custom("2026-01-0", "2026-01-31", zone), "half-typed")
        assertNull(BiblePeriod.custom("2026-02-01", "2026-01-31", zone), "backwards")
        assertNull(BiblePeriod.custom("2026-02-30", "2026-03-01", zone), "not a day")

        val filters = BibleFilters(periodMode = PeriodMode.Custom, fromDate = "2026-01-01", toDate = "2026-01-31")
        assertEquals("1 Jan 2026 – 31 Jan 2026", BiblePeriod.label(filters, "2026-09-06", today))
        assertEquals("2026-01-01" to "2026-09-12", BiblePeriod.defaultRange(today))
    }

    // -- the wire ----------------------------------------------------------------

    private val query = BibleQuery(
        periodStartMillis = 946_665_000_000,
        periodEndMillis = 1_785_263_399_999,
        accountStart = " 7000 ",
        accountTypes = listOf("expense", "asset"),
        sources = listOf("invoice"),
        companyIds = listOf("c1", "c2"),
        vendorId = "v-42",
        tags = listOf("camera", " ", "grip"),
        currency = "GBP",
    )

    /** The web pins this: the vendor is the one single choice, so it goes bare while the rest go comma-joined. */
    @Test
    fun `the query sends the vendor bare and the multi-selects comma-joined`() {
        val params = query.toQueryParameters()
        assertEquals("v-42", params["vendor_id"])
        assertEquals("c1,c2", params["company_id"])
        assertEquals("camera,grip", params["tags"], "blank tags are dropped, not sent as empty CSV slots")
        assertEquals("expense,asset", params["account_type"])
        assertEquals("invoice", params["source"])
        assertEquals("7000", params["account_start"])
        assertEquals("GBP", params["currency"])
        assertEquals("946665000000", params["period_start"])
        assertFalse("account_end" in params)
        assertFalse("tax" in params)
        assertFalse("include_open_pos" in params, "the server's default is on; only turning it off is said")

        val cleared = query.copy(vendorId = "", includeOpenPurchaseOrders = false).toQueryParameters()
        assertFalse("vendor_id" in cleared, "a cleared vendor means all vendors, not an empty filter")
        assertEquals("false", cleared["include_open_pos"])
    }

    /** The export carries the run's filters plus the words the file's header prints; empty keys are left out. */
    @Test
    fun `the export body carries the filters and the header words`() {
        val body = query.toExportBody(
            projectName = "Zillit Films",
            companyName = "Zillit Films Ltd",
            periodLabel = "Till 12 Sep 2026",
        )
        assertEquals(JsonPrimitive(946_665_000_000), body["period_start"])
        assertEquals(JsonPrimitive("v-42"), body["vendor_id"])
        assertEquals(JsonPrimitive("c1,c2"), body["company_id"])
        assertEquals(JsonPrimitive(true), body["include_open_pos"], "always stated in the file")
        assertEquals(JsonPrimitive("Zillit Films"), body["project_name"])
        assertEquals(JsonPrimitive("Zillit Films Ltd"), body["company_name"])
        assertEquals(JsonPrimitive("Till 12 Sep 2026"), body["period_label"])
        assertFalse("tax" in body)
        assertFalse("account_end" in body)

        val bare = query.copy(companyIds = emptyList()).toExportBody("", "", "Till 12 Sep 2026")
        assertFalse("company_name" in bare)
        assertFalse("project_name" in bare)
    }

    // -- the close boundary --------------------------------------------------------

    @Test
    fun `a locked date is normalised from every shape it has arrived in`() {
        assertEquals("2026-05-14", normalizeLockedDate(JsonPrimitive("2026-05-14")))
        assertEquals("2026-05-14", normalizeLockedDate(JsonPrimitive("2026-05-14T00:00:00.000Z")))
        assertEquals("2026-05-14", normalizeLockedDate(JsonPrimitive(1_778_716_800_000)))
        assertEquals("2026-05-14", normalizeLockedDate(JsonPrimitive("1778716800000")))
        assertEquals("", normalizeLockedDate(JsonPrimitive("not a date")))
        assertEquals("", normalizeLockedDate(null))
    }

    /** The service answers bare; the desktop used to read only `value`, and every production read as never closed. */
    @Test
    fun `the lock route is read bare or wrapped, under either name`() {
        val bare = json.parseToJsonElement(
            """{"tz":"Europe/London","week":{"start_day_of_week":1,"end_day_of_week":0},"lockedDate":"2026-09-06"}""",
        ).toPeriodLock()!!
        assertEquals("2026-09-06", bare.lockedThrough)
        assertEquals("Europe/London", bare.timeZone)
        assertEquals(1, bare.weekStartDay)

        val wrapped = json.parseToJsonElement("""{"value":{"last_cr_locked_date":"2026-09-13"}}""").toPeriodLock()!!
        assertEquals("2026-09-13", wrapped.lockedThrough, "the write route's key")
        assertNull(json.parseToJsonElement("[]").toPeriodLock())
    }

    /** The web's useCrLock reads the settings document too, and the later of the two dates wins. */
    @Test
    fun `the settings document stands in for the lock, and the later date wins`() {
        val settings = json.parseToJsonElement(
            """{"project_id":"p1","settings":{"last_cr_locked_date":"2026-09-13","timezone":"Asia/Kolkata",
               "cost_report_week":{"start_day_of_week":1,"end_day_of_week":0}}}""",
        ).settingsPeriodLock()!!
        assertEquals("2026-09-13", settings.lockedThrough)

        val route = PeriodLock(lockedThrough = "2026-09-06", timeZone = "")
        val merged = laterLock(route, settings)!!
        assertEquals("2026-09-13", merged.lockedThrough)
        assertEquals("Asia/Kolkata", merged.timeZone, "the zone comes from whichever reading has one")
        assertEquals("2026-09-06", laterLock(route, null)!!.lockedThrough)
        assertEquals("2026-09-06", laterLock(route, PeriodLock())!!.lockedThrough, "a blank date is not later")
        assertNull(laterLock(null, null))
        assertNull(json.parseToJsonElement("""{"settings":null}""").settingsPeriodLock())
    }

    private fun String.extractData(): String =
        json.parseToJsonElement(this).jsonObject["data"].toString()
}
