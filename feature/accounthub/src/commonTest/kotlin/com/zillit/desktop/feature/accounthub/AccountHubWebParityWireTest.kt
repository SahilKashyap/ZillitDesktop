package com.zillit.desktop.feature.accounthub

import com.zillit.desktop.feature.accounthub.data.decodeAssignmentRules
import com.zillit.desktop.feature.accounthub.data.decodeBankAccounts
import com.zillit.desktop.feature.accounthub.data.decodeCashClose
import com.zillit.desktop.feature.accounthub.data.decodeCompanies
import com.zillit.desktop.feature.accounthub.data.decodeDealConditions
import com.zillit.desktop.feature.accounthub.data.decodeNonUnionPay
import com.zillit.desktop.feature.accounthub.data.decodePayrollBureaus
import com.zillit.desktop.feature.accounthub.data.decodePayrollGroups
import com.zillit.desktop.feature.accounthub.data.decodePayrollSettings
import com.zillit.desktop.feature.accounthub.data.decodeSchedule
import com.zillit.desktop.feature.accounthub.data.decodeTaxTypes
import com.zillit.desktop.feature.accounthub.data.decodeTrackingSets
import com.zillit.desktop.feature.accounthub.data.decodeVendors
import com.zillit.desktop.feature.accounthub.domain.BankDetailType
import com.zillit.desktop.feature.accounthub.domain.HubBadgeCounts
import com.zillit.desktop.feature.accounthub.domain.HubBadges
import com.zillit.desktop.feature.accounthub.domain.JournalDescriptionFormat
import com.zillit.desktop.feature.accounthub.domain.SetupGap
import com.zillit.desktop.feature.accounthub.domain.SetupSnapshot
import com.zillit.desktop.feature.accounthub.domain.SetupTour
import com.zillit.desktop.feature.accounthub.domain.VendorTerms
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The shapes added while bringing the console to parity with the web's
 * Account Hub — each transcribed from `zillit_web/src/accountHub`.
 *
 * Built from response bodies, not DTOs, for the reason `AccountHubWireShapeTest`
 * gives: a field read under the wrong key decodes without error and renders
 * blank, and only a body catches that.
 */
class AccountHubWebParityWireTest {

    // -- production setup -----------------------------------------------------

    @Test
    fun `a company carries its legal name and the UK payroll references`() {
        val companies = decodeCompanies(
            """{"value":[{"id":"co-1","name":"Zillit Films","legal_name":"Zillit Films Limited",
               "uk":{"paye_ref":"120/AB12345","accounts_office_ref":"120PA00012345",
               "pension_provider":"NEST","pension_scheme_ref":"SCH-000123"}}]}""",
        )
        val company = companies.single()
        assertEquals("Zillit Films Limited", company.legalName)
        assertEquals("120/AB12345", company.ukPayeRef)
        assertEquals("120PA00012345", company.ukAccountsOfficeRef)
        // The pension pair (backend 2026-09-12) rides the same block; a client
        // that does not read it clears it on every save.
        assertEquals("NEST", company.ukPensionProvider)
        assertEquals("SCH-000123", company.ukPensionSchemeRef)
    }

    @Test
    fun `custom schedule days decode with their own dates`() {
        val schedule = decodeSchedule(
            """{"value":{"start_date":1754006400000,"end_date":"2026-12-31",
               "custom_days":[{"name":"Second Unit","start_date":"2026-09-01","end_date":1759276800000}]}}""",
        )
        val day = schedule.customDays.single()
        assertEquals("Second Unit", day.name)
        assertTrue(day.startDate != null && day.endDate != null)
        assertTrue(schedule.isSet)
    }

    @Test
    fun `a bank account reads its AP clearance code and typed extra details`() {
        val banks = decodeBankAccounts(
            """[{"id":"b1","name":"Barclays","nominal_code":"1200","ap_clearance_nominal_code":"2100",
               "currency":{"code":"GBP","name":"Pound Sterling","symbol":"£"},
               "additional_details":[{"field":"Reference","value":"PROD-1","field_type":"text"},{"field":"",
               "value":"x"}]}]""",
        )
        val bank = banks.single()
        assertEquals("2100", bank.apClearanceNominalCode)
        assertEquals("Pound Sterling", bank.currencyName)
        assertEquals(listOf("Reference"), bank.additionalDetails.map { it.title }, "the untitled row is dropped")
        assertEquals(BankDetailType.Text, bank.additionalDetails.single().fieldType)
    }

    /** Older clients wrote the details array JSON-encoded into a string; it still reads. */
    @Test
    fun `stringified extra details are read as the array they encode`() {
        val banks = decodeBankAccounts(
            """[{"id":"b1","name":"HSBC",
                "additional_details":"[{\"field\":\"IBAN note\",\"value\":\"GB00\",\"field_type\":\"text\"}]"}]""",
        )
        assertEquals("IBAN note", banks.single().additionalDetails.single().title)
    }

    @Test
    fun `a tax rate keeps its country and reads a numeric value`() {
        val taxes = decodeTaxTypes(
            """{"value":[{"type":"vat","identifier":"GB_standard","label":"Standard","value":20,
               "country":"United Kingdom","country_code":"GB"},
               {"type":"custom","identifier":"my_levy","label":"Levy","value":"2.5","country":"Custom",
               "country_code":null}]}""",
        )
        assertEquals("GB", taxes[0].countryCode)
        assertEquals("United Kingdom", taxes[0].country)
        assertEquals(20.0, taxes[0].rate)
        assertEquals(2.5, taxes[1].rate)
        assertNull(taxes[1].storedCountryCode)
    }

    @Test
    fun `payroll settings read the journal description format and the account codes`() {
        val settings = decodePayrollSettings(
            """{"journal_description_format":"uppercase","journal_group_by_category":true,
               "payroll_accounts":["6000",{"code":"6010"},""]}""",
        )
        assertEquals(JournalDescriptionFormat.Uppercase, settings.journalDescriptionFormat)
        assertTrue(settings.journalGroupByCategory)
        assertEquals(listOf("6000", "6010"), settings.payrollAccounts)
    }

    @Test
    fun `a payroll group decodes from the payroll host's shape`() {
        val groups = decodePayrollGroups(
            """[{"_id":"g1","assignee_id":"u9","user_ids":["u1",""],"department_ids":["d1"],"designation_ids":[]}]""",
        )
        val group = groups.single()
        assertEquals("u9", group.assigneeId)
        assertEquals(listOf("u1"), group.userIds)
        assertEquals(listOf("d1"), group.departmentIds)
    }

    /** The jsonb columns sometimes arrive as JSON encoded into a string. */
    @Test
    fun `an assignment rule reads its lists whether arrays or encoded strings`() {
        val rules = decodeAssignmentRules(
            """[{"id":"r1","module":"purchase_orders","departments":"[\"d1\",\"d2\"]","vendors":["v1"],
               "nominal_codes":null,"amount_min":"250.5","target_user_id":"u2","is_active":true,"priority":1}]""",
        )
        val rule = rules.single()
        assertEquals(listOf("d1", "d2"), rule.departments)
        assertEquals(listOf("v1"), rule.vendors)
        assertTrue(rule.nominalCodes.isEmpty())
        assertEquals(250.5, rule.amountMinValue)
        assertEquals("u2", rule.assignTo)
        assertTrue(rule.persisted)
    }

    @Test
    fun `a pay rule reads its cap, day type and the trigger gates`() {
        val pay = decodeNonUnionPay(
            """{"overtimes":[{"id":"ot1","label":"OT 1.5x","rate_type":"multiplier","rate_amount":1.5,
               "cap_type":"capped","cap_amount":4,"day_type":"SWD",
               "triggers":[{"after":600,"increment":30,"bdr_min":1,"bdr_max":2.5}]}]}""",
        )
        val rule = pay.overtimes.single()
        assertTrue(rule.capped)
        assertEquals("SWD", rule.dayType)
        assertEquals(30, rule.triggers.single().incrementMinutes)
        assertEquals(1.0, rule.triggers.single().bdrMin)
        assertEquals(2.5, rule.triggers.single().bdrMax)
    }

    // -- deal memo slices -----------------------------------------------------

    @Test
    fun `deal conditions read the web's order-and-condition rows, the old id-and-text rows, and bare strings`() {
        val conditions = decodeDealConditions(
            """{"value":[{"order":0,"condition":"Overtime after ten hours."},{"id":"c2",
                "text":"Turnaround is eleven hours."},"Meals provided."]}""",
        )
        assertEquals(
            listOf("Overtime after ten hours.", "Turnaround is eleven hours.", "Meals provided."),
            conditions.map { it.condition },
        )
        assertEquals("c2", conditions[1].id)
    }

    @Test
    fun `bureaux read from the legacy wrapper and keep id-less rows`() {
        val bureaus = decodePayrollBureaus("""{"value":{"bureau":[{"title":"Sargent-Disc"},
            {"name":"Entertainment Partners","_id":"b2"}]}}""")
        assertEquals(listOf("Sargent-Disc", "Entertainment Partners"), bureaus.map { it.title })
        assertEquals("b2", bureaus[1].id)
        assertTrue(bureaus[0].id.isNotBlank())
    }

    // -- vendors and layers ---------------------------------------------------

    @Test
    fun `a vendor carries its bank block, classification and audit trail`() {
        val vendors = decodeVendors(
            """[{"id":"v1","name":"Panavision","status":"VERIFIED","added_by":"u1",
               "verified_by":"u2","verified_at":"1754006400000",
               "bank_name":"Barclays","account_holder_name":"Panavision Ltd","account_number":"12345678",
               "sort_code":"204891",
               "iban_code":"GB00BARC","swift_code":"BARCGB22","additional_info":[{"label":"Ref","value":"PV-1",
               "field_type":"text"}],
               "vendor_type":"Equipment","company_type":"Limited Company","terms":"net_30","default_code":"5100",
               "compliance":"ok"}]""",
        )
        val vendor = vendors.single()
        assertTrue(vendor.verified)
        assertTrue(vendor.hasBankDetails)
        assertTrue(vendor.hasClassification)
        assertEquals("Ref", vendor.additionalInfo.single().title)
        assertEquals("u2", vendor.verifiedBy)
        assertEquals(1754006400000L, vendor.verifiedAtMillis)
        assertEquals(VendorTerms.Net30.label, VendorTerms.labelFor(vendor.terms))
    }

    @Test
    fun `a tracking set reads its prefix and colour, and a node its label and description`() {
        val sets = decodeTrackingSets(
            """[{"id":"s1","name":"Locations","prefix":"LOC","color":"#FB923C","active":true,
               "nodes":[{"id":"n1","code":"LOC-LON","label":"London","description":"Soundstage hire","parent_id":null,
               "active":false}]}]""",
        )
        val set = sets.single()
        assertEquals("LOC", set.shownPrefix)
        assertEquals("#FB923C", set.color)
        val node = set.nodes.single()
        assertEquals("London", node.name)
        assertEquals("Soundstage hire", node.description)
        assertFalse(node.isActive)
    }

    // -- cash & close -----------------------------------------------------------

    @Test
    fun `the cash-close dashboard reads every panel the web draws`() {
        val dash = decodeCashClose(
            """{"data":{"closeProgress":{"pct":40,"total":5},
               "waterfall":[{"label":"Opening","type":"pos","h":120,"mb":0,"amount":"£1.2m"}],
               "heatRows":[{"label":"Camera","cells":[{"val":"12","bg":"#fee","color":"#900"}]}],
               "checklist":[{"label":"Post invoices","done":false,"badge":"3 left","badgeV":"amber",
               "meta":"Due Friday"}],
               "recon":[{"supplier":"Panavision","status":"green","detail":"Matched"}],
               "weeks":[{"week":"W7","amount":"£40k","pct":60,"color":"amber","detail":"Netflix advance due"}]}}""",
        )
        assertEquals(40, dash.progressPercent)
        assertEquals("£1.2m", dash.waterfall.single().amount)
        assertEquals("Due Friday", dash.checklist.single().meta)
        assertEquals("W7", dash.weeks.single().label)
        assertEquals("Camera", dash.heatRows.single().label)
        assertFalse(dash.isEmpty)
    }

    // -- the shell ----------------------------------------------------------------

    @Test
    fun `sidebar badges follow the web's getBadgeCount`() {
        val counts = HubBadgeCounts(
            hubUnits = mapOf(HubBadges.PO_UNIT to 3, HubBadges.INVOICES_UNIT to 2, HubBadges.BANK_RECON_UNIT to 1),
            tools = mapOf(HubBadges.INVOICES_TOOL to 7, HubBadges.CARD_UNIT to 4),
        )
        assertEquals(3, HubBadges.countFor("purchase-orders", isAccountant = true, counts))
        assertEquals(
            0,
            HubBadges.countFor("purchase-orders", isAccountant = false, counts),
            "a department user sees no PO badge",
        )
        assertEquals(2, HubBadges.countFor("invoices", isAccountant = true, counts))
        assertEquals(7, HubBadges.countFor("invoices", isAccountant = false, counts))
        assertEquals(4, HubBadges.countFor("card-expenses", isAccountant = false, counts))
        assertEquals(1, HubBadges.countFor("bank-reconciliation", isAccountant = true, counts))
        assertEquals(0, HubBadges.countFor("bank-reconciliation", isAccountant = false, counts))
        assertEquals(0, HubBadges.countFor("vendors", isAccountant = true, counts))
    }

    @Suppress("LongParameterList") // A fixture with one knob per gap, so each test names only what it changes.
    private fun snapshot(
        companies: Int = 1,
        banks: Int? = 1,
        currencies: Int = 1,
        tags: Int = 1,
        taxes: Int = 1,
        coaEmpty: Boolean = false,
        scheduleSet: Boolean = true,
        payRules: Int = 1,
        entitlements: Int = 1,
        agreements: Int = 1,
        conditions: Int = 1,
        bureaus: Int = 1,
        ready: Boolean = true,
    ) = SetupSnapshot(
        ready = ready,
        companies = companies,
        banks = banks,
        currencies = currencies,
        tags = tags,
        taxes = taxes,
        coaReady = true,
        coaEmpty = coaEmpty,
        scheduleSet = scheduleSet,
        payRules = payRules,
        entitlements = entitlements,
        agreements = agreements,
        conditions = conditions,
        bureaus = bureaus,
    )

    @Test
    fun `the setup tour lists the production gaps in the web's order`() {
        val gaps = SetupTour.productionGaps(snapshot(companies = 0, currencies = 0, taxes = 0, coaEmpty = true))
        assertEquals(listOf(SetupGap.Company, SetupGap.Currency, SetupGap.Tax, SetupGap.Coa), gaps)
    }

    @Test
    fun `a complete production has no tour`() {
        assertTrue(SetupTour.gaps(snapshot()).isEmpty())
    }

    @Test
    fun `nothing is proposed until the slices have loaded`() {
        assertTrue(SetupTour.gaps(snapshot(companies = 0, ready = false)).isEmpty())
    }

    @Test
    fun `banks that have not been read yet are not a gap`() {
        assertTrue(SetupTour.productionGaps(snapshot(banks = null)).isEmpty())
    }

    @Test
    fun `the deal memo gaps follow the web's order too`() {
        val gaps = SetupTour.dealMemoGaps(snapshot(scheduleSet = false, agreements = 0, bureaus = 0))
        assertEquals(listOf(SetupGap.Schedule, SetupGap.Agreements, SetupGap.Bureau), gaps)
    }

    @Test
    fun `the seen flag is per production and per scope`() {
        assertEquals("ah_setup_tour_seen.p1.production", SetupTour.seenKey("p1"))
        assertEquals("ah_setup_tour_seen.p1.deal", SetupTour.seenKey("p1", "deal"))
    }
}
