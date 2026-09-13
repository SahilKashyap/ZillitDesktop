package com.zillit.desktop.feature.dealmemo

import com.zillit.desktop.feature.dealmemo.domain.DealDoc
import com.zillit.desktop.feature.dealmemo.domain.preview.BankWire
import com.zillit.desktop.feature.dealmemo.domain.preview.CrewDraft
import com.zillit.desktop.feature.dealmemo.domain.preview.CrewField
import com.zillit.desktop.feature.dealmemo.domain.preview.CrewFormRules
import com.zillit.desktop.feature.dealmemo.domain.preview.CrewFormValues
import com.zillit.desktop.feature.dealmemo.domain.preview.CrewRequirements
import com.zillit.desktop.feature.dealmemo.domain.preview.CrewStep
import com.zillit.desktop.feature.dealmemo.domain.preview.DealAddress
import com.zillit.desktop.feature.dealmemo.domain.preview.DealCalc
import com.zillit.desktop.feature.dealmemo.domain.preview.DealCountry
import com.zillit.desktop.feature.dealmemo.domain.preview.MemoBlock
import com.zillit.desktop.feature.dealmemo.domain.preview.MemoCard
import com.zillit.desktop.feature.dealmemo.domain.preview.MemoContext
import com.zillit.desktop.feature.dealmemo.domain.preview.MemoValue
import com.zillit.desktop.feature.dealmemo.domain.preview.UkPayroll
import com.zillit.desktop.feature.dealmemo.domain.preview.isDirtyAgainst
import com.zillit.desktop.feature.dealmemo.domain.preview.payload
import com.zillit.desktop.feature.dealmemo.domain.preview.withDraft
import com.zillit.desktop.feature.dealmemo.ui.pages.crew.isoFromStoredCode
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** "Complete your details": the draft, its format checks, the requiredness model and the value conversions. */
class CrewFormRulesTest {

    private fun deal(json: String) = DealDoc(Json.parseToJsonElement(json).jsonObject)

    private val payeUk = deal(
        """{"_id":"d1","status":"issued","user_id":"u1",
            "territory_union":{"territory_code":"uk"},
            "crew_details":{"crew_name":"Amara","emp_status":"paye","home_address":"12 Baker Street\nLondon",
              "emergency_contact":"Chidi","loan_out_company":{"name":"Old Co Ltd"},
              "uk":{"p45_previous_pay":0,"pension_status":""}},
            "bank":{"name":"Barclays","bank_acc_id":"acc-1"}}""",
    )

    // -- the draft ------------------------------------------------------------------------------

    @Test
    fun `the seed is an allow-list that leaves a PAYE crew member's company alone`() {
        val draft = CrewDraft.seed(payeUk)
        val cd = draft.crewDetails

        assertFalse("loan_out_company" in cd, "seeding it would blank retained company data on save")
        assertEquals(
            "Chidi",
            cd["emergency_contact_name"]?.jsonPrimitive?.content,
            "the legacy single contact seeds the name",
        )
        assertEquals(JsonNull, cd["dob"])
        assertEquals("", cd["email"]?.jsonPrimitive?.content, "text keys seed as empty strings")
        assertEquals("12 Baker Street\nLondon", cd["home_address"]?.jsonObject?.get("line1")?.jsonPrimitive?.content)
        val uk = cd["uk"]?.jsonObject
        assertEquals("opt_in", uk?.get("pension_status")?.jsonPrimitive?.content, "a blank pension reads as opted in")
        assertEquals(JsonNull, uk?.get("starter_statement"))
        assertEquals("0", uk?.get("p45_previous_pay")?.jsonPrimitive?.content, "zero is a P45 value")
    }

    @Test
    fun `a fresh seed is clean, a legacy string address included, and any change makes it dirty`() {
        val draft = CrewDraft.seed(payeUk)
        assertFalse(draft.isDirtyAgainst(payeUk))
        assertFalse((null as CrewDraft?).isDirtyAgainst(payeUk))

        val typed = draft.withCrew("preferred_name", JsonPrimitive("Amara O."))
        assertTrue(typed.isDirtyAgainst(payeUk))
        assertFalse(
            typed.withCrew("preferred_name", JsonPrimitive("")).isDirtyAgainst(payeUk),
            "typing then clearing is not a change",
        )
    }

    @Test
    fun `the payload carries the whole draft and a bank without the server's ids`() {
        val draft = CrewDraft.seed(payeUk)
            .withBank(
                "additional_details",
                Json.parseToJsonElement("""[{"field":"Roll","value":"R-1"},{"field":"","value":"orphan"}]"""),
            )
        val body = draft.payload()
        val bank = body["bank"]?.jsonObject

        assertEquals(draft.crewDetails, body["crew_details"])
        assertFalse("bank_acc_id" in bank.orEmpty())
        assertEquals(1, bank?.get("additional_details")?.jsonArray?.size, "an untitled detail is dropped")
        assertEquals(
            "text",
            bank?.get("additional_details")?.jsonArray?.first()?.jsonObject?.get("field_type")?.jsonPrimitive?.content,
        )
        assertFalse("bank" in draft.payload(bankLocked = true))
    }

    @Test
    fun `the memo follows the draft, and the saved deal is never touched`() {
        val draft = CrewDraft.seed(payeUk).withCrew("full_legal_name", JsonPrimitive("Amara N. Okafor"))
        val shown = payeUk.withDraft(draft)

        assertEquals("Amara N. Okafor", shown.fullLegalName)
        assertNull(payeUk.fullLegalName)
        assertEquals("u1", shown.userId, "accountant-owned keys survive the merge")
    }

    // -- steps and format checks -----------------------------------------------------------------

    @Test
    fun `the loan-out step follows the draft's employment status, never retained company data`() {
        assertEquals(4, CrewFormRules.steps(payeUk, CrewDraft.seed(payeUk)).size)
        val loanOut = CrewDraft.seed(payeUk).withCrew("emp_status", JsonPrimitive("loanout-c"))
        assertEquals(CrewStep.LoanOut, CrewFormRules.steps(payeUk, loanOut).last())
    }

    @Test
    fun `format errors name every step's field in the footer's order, and empty is never invalid`() {
        val emergency = JsonObject(mapOf("email" to JsonPrimitive("chidi@")))
        val draft = CrewDraft.seed(payeUk)
            .withCrew("emergency_details", emergency)
            .withCrew("emergency_contact_number", JsonPrimitive("77"))
            .withCrew("email", JsonPrimitive("a@b.c"))
            .withCrew("mobile", JsonPrimitive(""))

        val errors = CrewFormRules.formatErrors(payeUk, draft)

        assertEquals(
            listOf(CrewField.Email, CrewField.EmergencyEmail, CrewField.EmergencyPhone),
            errors.map { it.field },
        )
        assertEquals(CrewFormRules.PHONE_ERROR, errors.last().message)
    }

    @Test
    fun `the PAYE reference blocks only on the P45 route`() {
        val badRef = mapOf("p45_previous_paye_ref" to JsonPrimitive("12/AB"))
        val statement = CrewDraft.seed(payeUk).withCrew(
            "uk",
            JsonObject(CrewDraft.ukFromWire(null) + badRef + ("starter_statement" to JsonPrimitive("A"))),
        )
        val p45 = CrewDraft.seed(payeUk).withCrew("uk", JsonObject(CrewDraft.ukFromWire(null) + badRef))

        assertTrue(CrewFormRules.formatErrors(payeUk, statement).none { it.field == CrewField.PayeRef })
        assertEquals(CrewField.PayeRef, CrewFormRules.formatErrors(payeUk, p45).single().field)
        assertTrue(CrewFormRules.validPayeRef("123/AB456"))
    }

    @Test
    fun `phone and sort code inputs keep what the web keeps`() {
        assertEquals("+447700900000", CrewFormRules.sanitizePhone(" +44 7700-900000", allowPlus = true))
        assertEquals("447700900000", CrewFormRules.sanitizePhone("+44 7700 900000", allowPlus = false))
        assertEquals("204891", CrewFormRules.stripSortCode("20-48-91-7"))
        assertTrue(CrewFormRules.validPhone("12345"))
        assertFalse(CrewFormRules.validPhone("1234"))
    }

    // -- requiredness ------------------------------------------------------------------------------

    @Test
    fun `the floor always stars, and an unmarked pair asks for either half until one is filled`() {
        val draft = CrewDraft.seed(payeUk)
        val required = CrewFormRules.requiredPaths(payeUk, draft)

        assertTrue(required.containsAll(CrewRequirements.ALWAYS_REQUIRED))
        assertTrue("bank.account_number" in required && "bank.iban_number" in required)
        assertTrue("Account number or IBAN" in CrewRequirements.missingLabels(payeUk, draft))

        val filled = draft.withBank("iban_number", JsonPrimitive("GB29NWBK"))
        assertFalse("bank.account_number" in CrewFormRules.requiredPaths(payeUk, filled))
    }

    @Test
    fun `the footer's still-empty note counts marked flat fields, never nested sections or the passport`() {
        val marked = deal(
            """{"_id":"d2","crew_details":{"mandatory_fields":["passportAttachment","emergencyEmail"]}}""",
        )
        assertFalse(CrewFormRules.markedGateableMissing(marked, CrewDraft.seed(marked)))

        val flat = deal("""{"_id":"d3","crew_details":{"mandatory_fields":["dob","homeAddress"]}}""")
        assertTrue(CrewFormRules.markedGateableMissing(flat, CrewDraft.seed(flat)))
    }

    @Test
    fun `a marked address needs line one, city and postcode`() {
        assertTrue(DealAddress(line1 = "12 Baker Street", city = "London").isIncomplete)
        assertFalse(DealAddress(line1 = "12 Baker Street", city = "London", postalCode = "NW1").isIncomplete)
    }

    @Test
    fun `the UK route is derived from what is filled`() {
        assertEquals("", UkPayroll.route(CrewDraft.ukFromWire(null)))
        assertEquals(
            listOf("Starter statement, or P45 details from the previous employer"),
            UkPayroll.missingLabels(null),
        )
        val partial = JsonObject(CrewDraft.ukFromWire(null) + ("p45_previous_tax" to JsonPrimitive(0)))
        assertEquals(UkPayroll.ROUTE_P45, UkPayroll.route(partial))
        assertEquals(3, UkPayroll.missingLabels(partial).size)
    }

    // -- values ------------------------------------------------------------------------------------------

    @Test
    fun `an address is read and written as typed, an emptied box as null`() {
        val typed = CrewFormValues.address(Json.parseToJsonElement("""{"line1":"12 ","city":null}"""))
        assertEquals("12 ", typed.line1, "a trailing space survives the next keystroke")

        val json = CrewFormValues.addressJson(typed.copy(line2 = ""))
        assertEquals(JsonNull, json["line2"])
        assertEquals("12 ", json["line1"]?.jsonPrimitive?.content)
        assertEquals(listOf("line1", "line2", "city", "state", "postal_code", "country"), json.keys.toList())
    }

    @Test
    fun `dates of birth before 1970 are dates, on the form and on the memo`() {
        val born = LocalDate(1965, 3, 14)
        val zone = TimeZone.of("Europe/London")
        val millis = CrewFormValues.epochOf(born, zone)
        assertTrue(millis != null && millis < 0)
        assertEquals(born, CrewFormValues.dateOf(millis, zone))

        val memo = MemoCard.build(
            deal("""{"_id":"d4","crew_details":{"crew_name":"Amara","dob":$millis}}"""),
            MemoContext(zone = zone),
        )
        val crew = memo.blocks.filterIsInstance<MemoBlock.Fields>().first { it.title == "Crew Details" }
        assertEquals(MemoValue.Text("14 Mar 1965"), crew.fields.first { it.label == "Date of Birth" }.value)
    }

    @Test
    fun `the P45 leaving date is UTC midnight both ways`() {
        val date = LocalDate(2026, 5, 29)
        val millis = CrewFormValues.epochOf(date, TimeZone.UTC)
        assertEquals(1_780_012_800_000L, millis)
        assertEquals(date, CrewFormValues.dateOf(millis, TimeZone.UTC))
    }

    @Test
    fun `NI categories normalise to one letter and an unknown letter is only a hint`() {
        assertEquals("B", CrewFormValues.niCategory(" b1 "))
        assertEquals("", CrewFormValues.niCategory("12"))
        assertTrue(CrewFormValues.isNiCategoryUnknown("G"))
        assertFalse(CrewFormValues.isNiCategoryUnknown("a"))
        assertFalse(CrewFormValues.isNiCategoryUnknown(""))
    }

    @Test
    fun `amounts serialise as a JS number would, and details filter by type`() {
        assertEquals("100", CrewFormValues.amount(100.0).toString())
        assertEquals("2450.5", CrewFormValues.amount(2450.5).toString())
        assertEquals(JsonNull, CrewFormValues.amount(null))
        assertTrue(CrewFormValues.allowsDetailInput("number", "-12.5"))
        assertFalse(CrewFormValues.allowsDetailInput("number", "12a"))
        assertTrue(CrewFormValues.allowsDetailInput("phone", "+44 (0) 20-7946"))
        assertFalse(CrewFormValues.detailLooksValid("email", "a@b"))
        assertTrue(CrewFormValues.detailLooksValid("url", "zillit.com"))
        assertEquals("12345678", CrewFormValues.accountNumber("1234-5678"))
    }

    @Test
    fun `a stored phone code resolves to a country, the first one for a shared dial`() {
        val countries = listOf(DealCountry("GG", "+44", "Guernsey"), DealCountry("GB", "+44", "United Kingdom"))
        assertEquals("GB", isoFromStoredCode(countries, "gb"))
        assertEquals("GG", isoFromStoredCode(countries, "44"))
        assertEquals("", isoFromStoredCode(countries, "+1"))
        assertEquals("", isoFromStoredCode(countries, " "))
    }

    // -- the calculator ----------------------------------------------------------------------------------

    @Test
    fun `the money field evaluates arithmetic and refuses anything else`() {
        assertFalse(DealCalc.isExpression("-5"))
        assertTrue(DealCalc.isExpression("5-3"))
        assertEquals(360.0, DealCalc.evaluate("(100+20)*3"))
        assertEquals(-4.0, DealCalc.evaluate("-(2*2)"))
        assertNull(DealCalc.evaluate("10/0"))
        assertNull(DealCalc.evaluate("1.2.3+1"))
        assertNull(DealCalc.evaluate("2*"))
        assertEquals(3.33, DealCalc.round2(10.0 / 3))
        assertTrue(DealCalc.isAllowedInput("(1 + 2) * 3.5"))
        assertFalse(DealCalc.isAllowedInput("2x3"))
    }

    @Test
    fun `plain numbers commit as typed and committed figures read grouped`() {
        assertEquals(12.0, DealCalc.plainValue("12."))
        assertNull(DealCalc.plainValue("-"))
        assertNull(DealCalc.plainValue(""))
        assertEquals("1,234.50", DealCalc.grouped(1234.5))
        assertEquals("-100,000.00", DealCalc.grouped(-100_000.0))
        assertEquals("0.00", DealCalc.grouped(-0.001))
        assertEquals("-1,234,567.8", DealCalc.groupTyped("-1234567.8"))
    }
}
