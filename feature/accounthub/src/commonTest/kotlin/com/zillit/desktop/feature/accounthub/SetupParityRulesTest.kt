package com.zillit.desktop.feature.accounthub

import com.zillit.desktop.core.socket.SocketEventName
import com.zillit.desktop.feature.accounthub.data.HUB_REFRESH_BY_EVENT
import com.zillit.desktop.feature.accounthub.data.InvoicesSetupDto
import com.zillit.desktop.feature.accounthub.data.accountHubJson
import com.zillit.desktop.feature.accounthub.data.decodePayrollBureaus
import com.zillit.desktop.feature.accounthub.data.normalisedTags
import com.zillit.desktop.feature.accounthub.domain.AssignmentRule
import com.zillit.desktop.feature.accounthub.domain.DayType
import com.zillit.desktop.feature.accounthub.domain.DayTypes
import com.zillit.desktop.feature.accounthub.data.settingsDocument
import com.zillit.desktop.feature.accounthub.domain.BankAccount
import com.zillit.desktop.feature.accounthub.domain.BankAccounts
import com.zillit.desktop.feature.accounthub.domain.CoaLineType
import com.zillit.desktop.feature.accounthub.domain.Companies
import com.zillit.desktop.feature.accounthub.domain.Company
import com.zillit.desktop.feature.accounthub.domain.HubArea
import com.zillit.desktop.feature.accounthub.domain.InvoiceTeamMember
import com.zillit.desktop.feature.accounthub.domain.IsoDate
import com.zillit.desktop.feature.accounthub.domain.LocalIds
import com.zillit.desktop.feature.accounthub.domain.PayDayKind
import com.zillit.desktop.feature.accounthub.domain.PayRule
import com.zillit.desktop.feature.accounthub.domain.PayRuleKind
import com.zillit.desktop.feature.accounthub.domain.PayRuleTemplate
import com.zillit.desktop.feature.accounthub.domain.PayTrigger
import com.zillit.desktop.feature.accounthub.domain.PayrollAccountRow
import com.zillit.desktop.feature.accounthub.domain.PayrollAccounts
import com.zillit.desktop.feature.accounthub.domain.RunAuthorisationTier
import com.zillit.desktop.feature.accounthub.domain.InvoicesSetup
import com.zillit.desktop.feature.accounthub.domain.SchedulePhase
import com.zillit.desktop.feature.accounthub.domain.TaxType
import com.zillit.desktop.feature.accounthub.ui.DateRangeText
import com.zillit.desktop.feature.accounthub.ui.PayRuleEditor
import com.zillit.desktop.feature.accounthub.ui.ScheduleForm
import com.zillit.desktop.feature.accounthub.ui.SetupModal
import com.zillit.desktop.feature.accounthub.ui.SetupSection
import com.zillit.desktop.feature.accounthub.ui.SetupState
import com.zillit.desktop.feature.accounthub.ui.SectionEdit
import com.zillit.desktop.feature.accounthub.ui.SliceLoads
import com.zillit.desktop.feature.accounthub.ui.UserPickerPurpose
import com.zillit.desktop.feature.accounthub.ui.UserPickerState
import com.zillit.desktop.feature.accounthub.ui.components.Calc
import com.zillit.desktop.feature.accounthub.ui.pickerExcluded
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Production Setup parity rules that live in the domain — each one a way
 * a production's settings were silently lost or overwritten.
 */
class SetupParityRulesTest {

    // -- settings documents: body first, `value` tolerated ------------------------

    @Test
    fun `a settings document is read bare, and a value wrapper still works`() {
        val bare = accountHubJson.parseToJsonElement("""{"description_format":"DDMM_ITEM"}""")
        val wrapped = accountHubJson.parseToJsonElement("""{"value":{"description_format":"DDMM_ITEM"}}""")

        assertEquals("DDMM_ITEM", bare.settingsDocument()?.get("description_format")?.toString()?.trim('"'))
        assertEquals("DDMM_ITEM", wrapped.settingsDocument()?.get("description_format")?.toString()?.trim('"'))
        assertNull(JsonNull.settingsDocument(), "no data is a fresh project")
        assertNull(accountHubJson.parseToJsonElement("""{"value":null}""").settingsDocument())
        assertNull(accountHubJson.parseToJsonElement("{}").settingsDocument())
    }

    // -- invoices: lists as arrays or encoded strings, and the limit contract ------

    @Test
    fun `invoice lists read as arrays or as JSON strings`() {
        val setup = accountHubJson.decodeFromString(
            InvoicesSetupDto.serializer(),
            """{"team_members":"[{\"user_id\":\"u1\",\"posting_limit\":500}]",
               "alerts":"[\"invoice_overdue\"]",
               "run_authorization":"[{\"tier\":1,\"user\":[\"u2\"]}]"}""",
        ).toDomain()

        assertEquals("u1", setup.teamMembers.single().userId)
        assertEquals("500", setup.teamMembers.single().postingLimit)
        assertEquals(1, setup.alerts.size)
        assertEquals(listOf("u2"), setup.runAuthorisation.single().userIds)
    }

    @Test
    fun `null, absent and unlimited are Unlimited, zero is submit-only`() {
        val members = accountHubJson.decodeFromString(
            InvoicesSetupDto.serializer(),
            """{"team_members":[{"user_id":"a","posting_limit":null},{"user_id":"b"},
               {"user_id":"c","posting_limit":"unlimited"},{"user_id":"d","posting_limit":0},
               {"user_id":"e","posting_limit":2500},{"user_id":"f","run_access":"true"}]}""",
        ).toDomain().teamMembers

        assertTrue(members[0].isUnlimited)
        assertTrue(members[1].isUnlimited, "the live Invoices page reads an absent limit as no ceiling")
        assertTrue(members[2].isUnlimited)
        assertTrue(members[3].isSubmitOnly && !members[3].isUnlimited, "zero is submit-only, never unlimited")
        assertEquals("2500", members[4].postingLimit)
        assertTrue(members[5].runAccess, "a stringly boolean from an unparsed column")
    }

    @Test
    fun `senior forces unlimited posting and both rights`() {
        val member = InvoiceTeamMember(userId = "u", postingLimit = "100")

        val senior = member.withSenior(true)
        assertTrue(senior.isUnlimited && senior.runAccess && senior.overrideAccess)
        assertEquals("", member.copy(isSenior = true).forWire().postingLimit, "saved as unlimited whatever it held")
        assertEquals(InvoiceTeamMember.SUBMIT_ONLY, member.withUnlimited(false).postingLimit)
        assertTrue(member.withUnlimited(true).isUnlimited)
    }

    // -- run authorisation: one person, one level ----------------------------------

    @Test
    fun `a run level's picker excludes people on other levels`() {
        val setup = SetupState(
            invoicesSetup = SectionEdit(
                InvoicesSetup(
                    runAuthorisation = listOf(
                        RunAuthorisationTier(1, listOf("a", "b")),
                        RunAuthorisationTier(2, listOf("c")),
                    ),
                ),
            ),
        )

        fun level(index: Int) = UserPickerState(UserPickerPurpose.RunAuthorisation, index = index)
        assertEquals(setOf("c"), setup.pickerExcluded(level(0)))
        assertEquals(setOf("a", "b"), setup.pickerExcluded(level(1)))
        assertEquals(emptySet(), setup.pickerExcluded(UserPickerState(UserPickerPurpose.PayrollApprovers)))
    }

    // -- banks -----------------------------------------------------------------------

    @Test
    fun `a bank always needs a holder company`() {
        val companies = listOf(Company(id = "co-1", name = "Zillit"))
        val draft = BankAccount(
            id = "",
            name = "Barclays",
            accountHolderName = "Typed Holder Ltd",
            accountNumber = "1",
            currencyCode = "GBP",
        )

        assertNotNull(BankAccounts.validationError(draft, emptyList(), companies, accountant = false))
        assertNull(BankAccounts.validationError(draft.copy(entityId = "co-1"), emptyList(), companies, false))
        assertNotNull(
            BankAccounts.validationError(draft.copy(entityId = "gone"), emptyList(), companies, false),
            "a deleted company is no holder",
        )
    }

    // -- tax types ---------------------------------------------------------------------

    @Test
    fun `blank custom tax rows are skipped and the rest trimmed`() {
        val rows = listOf(
            TaxType(type = "VAT", identifier = "GB_standard", label = "Standard", value = "20", country = "UK"),
            TaxType(type = " WHT ", identifier = "custom_1", label = "  Withholding ", value = "5", nominal = " 2200 "),
            TaxType(type = "", identifier = "custom_2", label = "   ", value = ""),
        )

        val sent = TaxType.forWire(rows)

        assertEquals(listOf("GB_standard", "custom_1"), sent.map { it.identifier })
        assertEquals("Withholding", sent[1].label)
        assertEquals("WHT", sent[1].type)
        assertEquals("2200", sent[1].nominal)
    }

    // -- pay rules -------------------------------------------------------------------

    @Test
    fun `editing a condition keeps the billing increment`() {
        val trigger = PayTrigger(afterMinutes = 600, incrementMinutes = 30, bdrMin = 100.0)

        val edited = PayRuleTemplate.Overtime.conditionFrom("9", emptyList(), carrying = trigger)

        assertEquals(540, edited.afterMinutes)
        assertEquals(30, edited.incrementMinutes, "the rule used to go back to billing actual minutes")
        assertEquals(100.0, edited.bdrMin)
        assertEquals(PayRuleTemplate.Overtime, PayRuleTemplate.of(edited), "an increment is a gate, not a condition")
        assertEquals(
            30,
            PayRuleTemplate.CameraOvertime.conditionFrom("11", emptyList(), carrying = trigger).incrementMinutes,
            "camera keeps a chosen increment over its 15-minute default",
        )
    }

    @Test
    fun `a new rule starts at the web's defaults, never zero`() {
        assertEquals(480, PayRuleTemplate.Overtime.defaultTrigger().afterMinutes)
        assertEquals(360, PayRuleTemplate.MealPenalty.defaultTrigger().afterMinutes)
        assertEquals(600, PayRuleTemplate.BrokenTurnaround.defaultTrigger().lessMinutes)
        assertEquals(360, PayRuleTemplate.PreDawn.defaultTrigger().beforeMinutes)
        assertEquals(1320, PayRuleTemplate.NightWork.defaultTrigger().afterMinutes)
        assertEquals(listOf(PayDayKind.BankHoliday), PayRuleTemplate.BankHoliday.defaultTrigger().dayKinds)
    }

    @Test
    fun `the condition field holds what was typed`() {
        val rule = PayRule(id = "r", triggers = listOf(PayRuleTemplate.Overtime.defaultTrigger()))
        val editor = PayRuleEditor.open(PayRuleKind.Overtimes, null, rule)
        assertEquals("8", editor.conditionText)

        val typing = editor.withConditionText("5.")
        assertEquals("5.", typing.conditionText, "was rebuilt from minutes as \"5\"")
        assertEquals(300, typing.rule.singleTrigger?.afterMinutes)
        assertEquals("5.", typing.copy(rule = typing.rule.copy(label = "OT")).synced().conditionText)

        val clock = PayRuleEditor.open(
            PayRuleKind.Premiums,
            null,
            PayRule(id = "p", triggers = listOf(PayRuleTemplate.PreDawn.defaultTrigger())),
        ).withConditionText("06:0")
        assertEquals("06:0", clock.conditionText, "was rebuilt as \"00:00\"")
        assertNotNull(clock.template.conditionProblem(clock.conditionText), "and a half-typed time cannot be saved")
        assertNull(clock.template.conditionProblem("06:00"))
        assertNotNull(PayRuleTemplate.Overtime.conditionProblem(""))
        assertNotNull(PayRuleTemplate.Overtime.conditionProblem("-1"))
    }

    @Test
    fun `switching type re-reads the field from the new condition`() {
        val editor = PayRuleEditor.open(
            PayRuleKind.Penalties,
            null,
            PayRule(id = "r", triggers = listOf(PayRuleTemplate.Overtime.defaultTrigger())),
        )
        val switched = editor.copy(
            rule = editor.rule.copy(triggers = listOf(PayRuleTemplate.MealPenalty.defaultTrigger()))
        ).synced()

        assertEquals("6", switched.conditionText)
    }

    // -- ids ---------------------------------------------------------------------------

    @Test
    fun `a new row's id never repeats one on the list`() {
        val taken = listOf("rental-new-a", "rental-new-b")
        val ids = (1..200).map { LocalIds.next("rental-new", taken) }

        assertTrue(ids.none { it in taken })
        assertEquals(ids.size, ids.toSet().size)
    }

    // -- schedule dates ----------------------------------------------------------------

    @Test
    fun `schedule dates read back in UTC, the zone they are written in`() {
        val millis = IsoDate.toEpochMillis("2026-03-01")

        assertEquals("2026-03-01", IsoDate.fromEpochMillis(millis))
        assertEquals("1969-12-31", IsoDate.fromEpochMillis(-1L))
        assertEquals("2024-02-29", IsoDate.fromEpochMillis(IsoDate.toEpochMillis("2024-02-29")))
        assertEquals("", IsoDate.fromEpochMillis(null))
        assertEquals(
            DateRangeText("2026-03-01", ""),
            DateRangeText.from(SchedulePhase(startDate = millis)),
        )
    }

    @Test
    fun `a half-typed date blocks the save`() {
        assertFalse(ScheduleForm(overall = DateRangeText("2026-03-01", "")).hasHalfTypedDate)
        assertTrue(ScheduleForm(overall = DateRangeText("2026-03-0", "")).hasHalfTypedDate)
        assertTrue(ScheduleForm(shoot = DateRangeText("", "2026-1")).hasHalfTypedDate)
    }

    // -- the calculator field -----------------------------------------------------------

    @Test
    fun `the calculator field refuses letters and negatives`() {
        assertTrue(Calc.accepts("1,500*2"))
        assertFalse(Calc.accepts("12a"))
        assertFalse(Calc.accepts("-5"))
        assertTrue(Calc.accepts("-5", allowNegative = true))
        assertTrue(Calc.accepts("10-2"), "a minus inside an expression is fine")

        assertEquals("3000", Calc.committed("1,500*2", previous = "7"))
        assertEquals("7", Calc.committed("1+", previous = "7"), "an invalid expression is discarded")
        assertEquals("7", Calc.committed("2-5", previous = "7"), "a negative result is discarded, not clamped")
        assertEquals("", Calc.committed("", previous = "7"), "cleared is cleared")
    }

    // -- payroll accounts ---------------------------------------------------------------

    @Test
    fun `only new and changed payroll account rows are sent`() {
        val seeded = PayrollAccountRow(id = "coa-1", code = "6000", name = "Wages", lineType = CoaLineType.Category)
        val untouched = PayrollAccountRow(id = "coa-2", code = "6010", name = "NI", lineType = CoaLineType.Category)
        val renamed = seeded.copy(name = "Gross wages")
        val created = PayrollAccountRow(code = "6020", name = "Pension")
        val spare = PayrollAccountRow()

        val sent = PayrollAccounts.outgoing(
            rows = listOf(renamed, untouched, created, spare),
            seeds = mapOf("coa-1" to seeded, "coa-2" to untouched),
        )

        assertEquals(listOf(renamed, created), sent)
    }

    // -- load tracking, routes and live refresh ------------------------------------------

    @Test
    fun `a refresh that fails does not take away a loaded section`() {
        val loaded = SliceLoads().requested(SetupSection.Companies).succeeded(SetupSection.Companies)

        val refreshed = loaded.requested(SetupSection.Companies).failedWith(SetupSection.Companies, "down")

        assertTrue(refreshed.isLoaded(SetupSection.Companies))
        assertNull(refreshed.failure(SetupSection.Companies))
        val never = SliceLoads().requested(SetupSection.Companies).failedWith(SetupSection.Companies, "down")
        assertEquals("down", never.failure(SetupSection.Companies))
        assertFalse(never.isLoaded(SetupSection.Companies))
        assertTrue(never.requested(SetupSection.Companies).isLoading(SetupSection.Companies), "Retry reads again")
    }

    @Test
    fun `setup deep links name the modal`() {
        assertEquals(SetupModal.Payroll, SetupModal.fromRoute("payroll"))
        assertEquals(SetupModal.Payroll, SetupModal.fromRoute("payroll_settings"))
        assertEquals(SetupModal.PurchaseOrders, SetupModal.fromRoute("po"))
        assertEquals(SetupModal.Invoices, SetupModal.fromRoute("INVOICES"))
        assertNull(SetupModal.fromRoute("timecard"))
        assertNull(SetupModal.fromRoute(null))
    }

    @Test
    fun `a production setup change refreshes the setup page`() {
        assertEquals(HubArea.ProductionSetup, HUB_REFRESH_BY_EVENT[SocketEventName("production_setup:updated")])
    }

    // -- P2: legacy shapes and small wire rules -----------------------------------------

    @Test
    fun `the oldest bureau shape, a bare name, is read as one bureau`() {
        val bureaus = decodePayrollBureaus("""{"value":{"bureau":"Sargent Disc"}}""")

        assertEquals(listOf("Sargent Disc"), bureaus.map { it.title }, "dropped, the next save stored an empty list")
        assertTrue(decodePayrollBureaus("""{"value":{"bureau":""}}""").isEmpty())
    }

    @Test
    fun `tags are upper-cased, trimmed and de-duplicated`() {
        assertEquals(listOf("CAMERA", "VFX"), listOf(" camera", "CAMERA", "", "vfx ").normalisedTags())
    }

    @Test
    fun `a custom day type typed as SWD does not lock itself`() {
        val rows = DayTypes.seeded(emptyList()) + DayType(dayType = "SWD")

        assertTrue(DayTypes.isSeededDefault(0, rows))
        assertFalse(DayTypes.isSeededDefault(rows.lastIndex, rows), "it stays a deletable custom row")
        assertNotNull(DayTypes.problem(rows), "and the duplicate is what refuses the save")
    }

    @Test
    fun `typed tax credits are added once each, case-blind`() {
        assertEquals(
            listOf("UK HETV", "AVEC"),
            Companies.withTypedCredits(listOf("UK HETV"), "uk hetv, AVEC ,"),
        )
    }

    @Test
    fun `a zero minimum amount is no minimum`() {
        assertNull(AssignmentRule(id = "r", amountMin = "0").amountMinValue)
        assertEquals(1500.0, AssignmentRule(id = "r", amountMin = "1,500").amountMinValue)
    }

    @Test
    fun `the invoices wire sends unlimited as null and submit-only as zero`() {
        // Pinned through the DTO's reader, both ways round: what is sent is what reads back.
        val body = accountHubJson.parseToJsonElement(
            """{"team_members":[{"user_id":"a","posting_limit":null},{"user_id":"b","posting_limit":0}]}""",
        ).jsonObject
        val members = accountHubJson.decodeFromJsonElement(InvoicesSetupDto.serializer(), body).toDomain().teamMembers
        assertTrue(members[0].isUnlimited)
        assertTrue(members[1].isSubmitOnly)
    }
}
