package com.zillit.desktop.feature.payroll

import com.zillit.desktop.feature.payroll.data.normaliseLockDate
import com.zillit.desktop.feature.payroll.data.parseMetadata
import com.zillit.desktop.feature.payroll.data.routeLockDate
import com.zillit.desktop.feature.payroll.data.settingsLockDate
import com.zillit.desktop.feature.payroll.data.toDealCoding
import com.zillit.desktop.feature.payroll.data.toJournalCoding
import com.zillit.desktop.feature.payroll.data.toTimecard
import com.zillit.desktop.feature.payroll.domain.Journal
import com.zillit.desktop.feature.payroll.domain.JournalBuilder
import com.zillit.desktop.feature.payroll.domain.JournalCategory
import com.zillit.desktop.feature.payroll.domain.JournalCoding
import com.zillit.desktop.feature.payroll.domain.JournalEdit
import com.zillit.desktop.feature.payroll.domain.JournalLine
import com.zillit.desktop.feature.payroll.domain.PayBreakdown
import com.zillit.desktop.feature.payroll.domain.PayBucket
import com.zillit.desktop.feature.payroll.domain.PayrollAccount
import com.zillit.desktop.feature.payroll.domain.PayrollMetadata
import com.zillit.desktop.feature.payroll.domain.PayslipPreview
import com.zillit.desktop.feature.payroll.domain.ProcessingRow
import com.zillit.desktop.feature.payroll.domain.TimecardStatus
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The payroll service's documents as this module reads them, and the figures built from them. */
class PayrollShapeTest {

    private fun obj(text: String) = Json.parseToJsonElement(text) as JsonObject

    /** Monday 2026-08-03, midnight UTC. */
    private val week = 1_785_715_200_000L
    private val day = 86_400_000L

    private val full = obj(
        """
        {"_id":"tc1","user_id":"u1","status":"paid","week_starting":$week,"company_id":"co1",
         "currency":{"code":"GBP","symbol":"£"},"nominal_code":"5100",
         "days":[
           {"date":$week,"day_type":"SWD","basic_hours":10,"call_time":${week + 7 * 3_600_000},
            "login_details":{"time":${week + 6 * 3_600_000},"source":"gps"},
            "rates_ots":[{"identifier":"basic","label":"Basic","rate_amount":"350"},
                         {"identifier":"camera_ot","rate_type":"overtime","label":"Camera OT",
                          "rate_amount":80,"work_duration":90}],
            "allowances":[{"identifier":"kit","label":"Kit","rate_amount":25,"is_rental":true}]},
           {"date":${week + day},"day_type":"SWD","basic_hours":10,
            "rates_ots":[{"identifier":"basic","label":"Basic","rate_amount":350}]}
         ],
         "weekly_allowances":[{"_id":"w1","identifier":"mileage","label":"Mileage","rate_amount":0.45,"qty":100}],
         "attached_claims":[{"_id":"c1","claim_name":"Taxi","claim_amount":20,"cash_expense_batch_id":"b1"}],
         "deductions":[{"_id":"d1","label":"Advance","rate_type":"flat","rate_amount":50,
                         "actual_amount":50,"is_custom":true}],
         "history":[{"action":"paid","from":"locked","to":"paid","action_at":${week + 3 * day},"action_by":"acct1"}]}
        """,
    )

    @Test
    fun `a full timecard reads, currency object and all`() {
        val card = assertNotNull(full.toTimecard())
        assertEquals(TimecardStatus.Paid, card.status)
        assertEquals("GBP", card.currency)
        assertEquals(2, card.days.size)
        assertEquals(700.0, card.days.sumOf { it.basicPay })
        assertEquals(45.0, card.weeklyAllowRent, 0.0001)
        assertEquals(20.0, card.claimsTotal)
        assertEquals(50.0, card.deductionsTotal)
        assertEquals("paid", card.history.single().action)
    }

    @Test
    fun `the pay code breakdown is the web's rows and totals, nominal from the week first`() {
        val card = assertNotNull(full.toTimecard())
        val deal = obj(
            """{"overtimes":[{"row_id":"camera_ot","nominal_code":"5150"}],
               "rentals":[{"id":"kit","nominal_code":"2150"}]}""",
        )
            .toDealCoding()
        val breakdown = PayBreakdown.of(card, deal, weeklyWord = "Weekly", claimsWord = "Claims")
        assertEquals(listOf("5100", "5150", "2150", "5100", "", ""), breakdown.rows.map { it.nominal })
        assertEquals(700.0, breakdown.total(PayBucket.Basic))
        assertEquals(80.0, breakdown.total(PayBucket.Overtime))
        assertEquals(25.0, breakdown.total(PayBucket.Rental))
        assertEquals(20.0, breakdown.total(PayBucket.Claim))
        assertEquals(870.0, breakdown.gross, 0.0001)
        assertEquals("Mon 03 Aug", breakdown.rows.first().day)
        assertEquals("", breakdown.rows[1].day, "the day is named on its first row only")
        assertEquals("1.5h", breakdown.rows[1].qty)
        assertEquals("CASH", breakdown.rows.last().source)
    }

    @Test
    fun `the payslip sums each pay code across the week`() {
        val preview = PayslipPreview.of(assertNotNull(full.toTimecard()))
        val basic = preview.earnings.first { it.bucket == PayBucket.Basic }
        assertEquals(700.0, basic.amount)
        assertEquals(1_200, basic.minutes)
        assertEquals("20h", PayBreakdown.hoursMinutes(basic.minutes))
        assertEquals(780.0, preview.grossPay)
        assertEquals(listOf("Kit", "Mileage"), preview.allowances.map { it.label })
    }

    @Test
    fun `the processing projection is placed by date and reads its summaries`() {
        val card = assertNotNull(
            obj(
                """{"_id":"tc2","user_id":"u2","status":"locked","week_starting":$week,
                "day_summary":[{"date":${week + 2 * day},"day_type":"SWD","basic":300,"ots":40,
                                "allowances_rentals":10}],
                "weekly_allowances_rentals_total":15,"attached_claims_total":5,"deductions_total":20}""",
            ).toTimecard(),
        )
        val row = ProcessingRow(card, week)
        assertTrue(row.days[0].isOff)
        assertEquals(350.0, row.days[2].total)
        assertEquals(1, row.daysWorked)
        assertEquals(370.0, card.gross)
        assertEquals(350.0, card.net)
    }

    @Test
    fun `the outstanding projection is told apart by its ots total`() {
        val card = assertNotNull(
            obj(
                """{"_id":"tc3","user_id":"u3","status":"approved","basic_pay":500,"ots_total":60,
                "daily_allowances_total":10,"weekly_rentals_total":5,"attached_claims_total":0,"total_days":4}""",
            ).toTimecard(),
        )
        assertNotNull(card.outstanding)
        val row = ProcessingRow(card, week)
        assertEquals(60.0, row.otTotal)
        assertEquals(15.0, row.allowanceTotal)
        assertEquals(575.0, row.totalPay)
    }

    @Test
    fun `a row with no id or no crew member is dropped`() {
        assertNull(obj("""{"user_id":"u1"}""").toTimecard())
        assertNull(obj("""{"_id":"x"}""").toTimecard())
    }

    /** Dev answers `is_final_approver`; the web documents `isFinalApprover`. */
    @Test
    fun `metadata merges the approver flag, the pay period and the payroll accounts`() {
        val metadata = parseMetadata(
            meta = obj("""{"is_final_approver":true,"pay_period":{"start_day_of_week":3}}"""),
            settings = obj(
                """{"value":{"payroll_accounts":["2100",{"code":"2410"}],"journal_group_by_category":"true",
                   "journal_description_format":"title"}}""",
            ),
            chart = listOf(obj("""{"code":"2100","name":"Payroll Control"}""")),
        )
        assertTrue(metadata.isFinalApprover)
        assertEquals(3, metadata.payPeriodStartDay)
        assertEquals(
            listOf(PayrollAccount("2100", "Payroll Control"), PayrollAccount("2410", "2410")),
            metadata.payrollAccounts,
        )
        assertTrue(metadata.journalGroupByCategory)
        assertTrue(metadata.journalTitleCase)
        assertFalse(parseMetadata(obj("""{"isFinalApprover":false}"""), null, emptyList()).isFinalApprover)
    }

    @Test
    fun `the lock date reads every shape the backends use`() {
        assertEquals("2026-05-14", normaliseLockDate(JsonPrimitive("2026-05-14")))
        assertEquals("2026-05-14", normaliseLockDate(JsonPrimitive("2026-05-14T00:00:00.000Z")))
        assertEquals("2026-08-03", normaliseLockDate(JsonPrimitive(week)))
        assertNull(normaliseLockDate(JsonPrimitive("")))
        assertEquals("2026-05-14", obj("""{"lockedDate":"2026-05-14"}""").routeLockDate())
        assertEquals(
            "2026-05-20",
            obj("""{"value":{"settings":{"last_cr_locked_date":"2026-05-20"}}}""").settingsLockDate(),
        )
    }

    @Test
    fun `saved coding reads grouped or flat, with the legacy amount on its own side`() {
        val grouped = obj(
            """{"timecards":[{"id":"tc1","lines":[{"src":"rates_ots","group_key":"basic|Basic",
               "nominal_code":"5100","amount":700}]}],
            "run_lines":[{"src":"payroll_account","group_key":"acct:2100","amount":900}]}""",
        ).toJournalCoding()
        assertEquals(700.0, grouped.byTimecard.getValue("tc1").single().debit)
        assertEquals(900.0, grouped.runLines.single().credit)
        assertNull(grouped.runLines.single().debit)
        val flat = Json.parseToJsonElement("""[{"timecard_id":"tc9","src":"claim","group_key":"id:c1"}]""")
            .toJournalCoding()
        assertEquals("id:c1", flat.byTimecard.getValue("tc9").single().groupKey)
    }

    /** The web's `buildJournalLedgerRows`: one debit line per pay break, summed across the week. */
    @Test
    fun `the journal is one line per pay break, the accounts last`() {
        val card = assertNotNull(full.toTimecard()).copy(status = TimecardStatus.Locked)
        val coding = JournalCoding(
            byTimecard = mapOf("tc1" to listOf(JournalLine(
                src = "rates_ots",
                groupKey = "basic|Basic",
                nominalCode = "5199",
            ))),
            runLines = listOf(JournalLine(src = "payroll_account", groupKey = "acct:2100", credit = 800.0)),
        )
        val builder = JournalBuilder(
            metadata = PayrollMetadata(payrollAccounts = listOf(PayrollAccount("2100", "Payroll Control"))),
            nameOf = { "Ada Lovelace" },
            categoryLabel = { it.name },
            taxLabel = "Tax",
            weekWord = "Week",
        )
        val rows = builder.rows(listOf(card), coding, week)
        val basic = rows.first { it.groupKey == "basic|Basic" }
        assertEquals(700.0, basic.amount, "the two days' basic sum into one line")
        assertEquals("5199", basic.code, "the saved coding wins over the line's own code")
        assertEquals("WEEK 3-9 AUG 2026 ADA LOVELACE BASIC", basic.description)
        assertEquals("id:w1", rows.first { it.src == "weekly" }.groupKey)
        assertEquals("id:c1", rows.first { it.src == "claim" }.groupKey)
        val account = rows.last()
        assertEquals(JournalCategory.Account, account.category)
        assertEquals(800.0, account.amount)
        assertTrue(account.codeLocked)

        val balance = Journal.balance(rows, emptyMap())
        assertEquals(800.0, balance.credit)
        assertFalse(balance.balanced)
        // A posted timecard is left out of the working ledger.
        assertEquals(listOf(account), builder.rows(listOf(card.copy(status = TimecardStatus.Posted)), coding, week))
    }

    @Test
    fun `grouping by category collapses a crew member's week to one line per category`() {
        val card = assertNotNull(full.toTimecard())
        val rows = JournalBuilder(PayrollMetadata(journalGroupByCategory = true), { "Ada" }, { it.name }, "Tax", "Week")
            .rows(listOf(card), JournalCoding(), week)
        val basic = rows.single { it.groupKey == "cat:basic" }
        assertEquals(Journal.SRC_CATEGORY, basic.src)
        assertEquals(700.0, basic.amount)
    }

    /** The web's `findIncompletePostLines`: every line in scope needs a code and a date. */
    @Test
    fun `a line missing a code or a date cannot post, and an edit fills it`() {
        val card = assertNotNull(full.toTimecard()).copy(status = TimecardStatus.Locked)
        val rows = JournalBuilder(PayrollMetadata(), { "Ada" }, { it.name }, "Tax", "Week").rows(
            listOf(card),
            JournalCoding(),
            week,
        )
        val missing = Journal.incomplete(rows, emptyMap(), setOf("tc1"))
        assertEquals(rows.size, missing.size, "nothing has an effective date yet")
        val edits = rows.associate { it.id to JournalEdit(nominalCode = "5100", effectiveDate = "2026-08-10") }
        assertTrue(Journal.incomplete(rows, edits, setOf("tc1")).isEmpty())
        // Out of scope — not postable — is not checked.
        assertTrue(Journal.incomplete(rows, emptyMap(), emptySet()).isEmpty())
    }

    @Test
    fun `a pay break sends no amount, an account its typed credit, dated at UTC midnight`() {
        val card = assertNotNull(full.toTimecard())
        val builder = JournalBuilder(
            PayrollMetadata(payrollAccounts = listOf(PayrollAccount("2100", "Control"))),
            { "Ada" }, { it.name }, "Tax", "Week",
        )
        val rows = builder.rows(listOf(card), JournalCoding(), week)
        val breakLine = Journal.lineFor(rows.first(), JournalEdit(effectiveDate = "2026-08-10")).single()
        assertNull(breakLine.debit)
        assertNull(breakLine.credit)
        assertEquals(1_786_320_000_000, breakLine.effectiveDate)
        val accountLine = Journal.lineFor(rows.last(), JournalEdit(amount = 870.0, nominalCode = "9999")).single()
        assertEquals(870.0, accountLine.credit)
        assertEquals("2100", accountLine.nominalCode, "an account's code is Entry Setup's, not the edit's")
    }

    @Test
    fun `a line in a closed period is read-only`() {
        val card = assertNotNull(full.toTimecard())
        val row = JournalBuilder(PayrollMetadata(), { "Ada" }, { it.name }, "Tax", "Week")
            .rows(listOf(card), JournalCoding(), week).first()
            .copy(effectiveDate = "2026-05-01")
        assertFalse(row.editable("2026-05-14"))
        assertTrue(row.editable("2026-04-30"))
        assertTrue(row.editable(null))
    }
}
