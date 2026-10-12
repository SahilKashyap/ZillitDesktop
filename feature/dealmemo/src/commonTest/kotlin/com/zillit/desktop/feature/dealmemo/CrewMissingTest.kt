package com.zillit.desktop.feature.dealmemo

import com.zillit.desktop.feature.dealmemo.domain.DealDoc
import com.zillit.desktop.feature.dealmemo.domain.preview.CrewDraft
import com.zillit.desktop.feature.dealmemo.domain.preview.CrewMissing
import com.zillit.desktop.feature.dealmemo.domain.preview.CrewRequirements
import com.zillit.desktop.feature.dealmemo.domain.preview.CrewStep
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The crew form's red "still to fill" state: the Send for Approval pop-up's own set, shown on the form. */
class CrewMissingTest {

    private fun deal(json: String) = DealDoc(Json.parseToJsonElement(json).jsonObject)

    // Everything the always-required floor asks for, filled: name, bank name and holder, emergency name, and one
    // half of each either/or pair. No marks and no territory, so each case empties only what it is about.
    private val bankDone = """{"name":"Barclays","account_holder_name":"Ada Lovelace","account_number":"12345678"}"""

    private fun filled(legalName: String = "Augusta Ada King", bank: String = bankDone, territory: String = "") = deal(
        """{"_id":"d1"$territory,"crew_details":{"emp_status":"paye","full_legal_name":"$legalName",
            "emergency_contact_name":"Byron","emergency_contact_number":"7700900000"},"bank":$bank}""",
    )

    private val complete = filled()

    @Test
    fun `a complete deal has nothing red`() {
        val none = CrewMissing.of(complete, CrewDraft.seed(complete))
        assertTrue(none.paths.isEmpty())
        assertFalse(none.any)
        assertTrue(none.steps.isEmpty())
        assertNull(none.hint("full_legal_name"))
    }

    @Test
    fun `an empty always-required field is red, and clears when filled`() {
        val empty = filled(legalName = "")
        val draft = CrewDraft.seed(empty)

        assertEquals(CrewMissing.REQUIRED, CrewMissing.of(empty, draft).hint("full_legal_name"))
        val typed = draft.withCrew("full_legal_name", JsonPrimitive("Augusta Ada King"))
        assertNull(CrewMissing.of(empty, typed).hint("full_legal_name"))
    }

    @Test
    fun `the red set is the blocker list's set, label for label`() {
        val d = deal("""{"_id":"d2","crew_details":{"mandatory_fields":["dob","mobile"]}}""")
        val draft = CrewDraft.seed(d)
        val red = CrewMissing.of(d, draft)

        assertEquals(CrewRequirements.missingLabels(d, draft).size, red.paths.size - PAIRS_IN_BLOCKER_LIST)
        assertTrue(red.paths.containsAll(listOf("dob", "mobile") + CrewRequirements.ALWAYS_REQUIRED))
    }

    @Test
    fun `an untouched pair names its other half, and either half clears both`() {
        val d = deal(
            """{"_id":"d3","crew_details":{"full_legal_name":"A"},"bank":{"name":"B","account_holder_name":"C"}}""",
        )
        val draft = CrewDraft.seed(d)
        val red = CrewMissing.of(d, draft)

        assertEquals("Required — or enter IBAN", red.hint("bank.account_number"))
        assertEquals("Required — or enter Account Number", red.hint("bank.iban_number"))
        assertTrue(red.hint("emergency_contact_number").orEmpty().startsWith("Required — or enter "))

        val iban = CrewMissing.of(d, draft.withBank("iban_number", JsonPrimitive("GB29NWBK")))
        assertNull(iban.hint("bank.account_number"))
        assertNull(iban.hint("bank.iban_number"))
    }

    @Test
    fun `when the sender marked a side, the pair stands down and only the mark applies`() {
        val d = deal(
            """{"_id":"d4","crew_details":{"mandatory_fields":["bankAccountNumber"],"full_legal_name":"A"},
                "bank":{"name":"B","account_holder_name":"C"}}""",
        )
        val red = CrewMissing.of(d, CrewDraft.seed(d))

        assertEquals(CrewMissing.REQUIRED, red.hint("bank.account_number"))
        assertNull(red.hint("bank.iban_number"))
    }

    @Test
    fun `an address is red until line one, city and postcode are there`() {
        val d = deal("""{"_id":"d5","crew_details":{"mandatory_fields":["homeAddress"]}}""")
        val draft = CrewDraft.seed(d)
        val partial = draft.withCrew(
            "home_address",
            Json.parseToJsonElement("""{"line1":"12 Baker Street","city":"London"}"""),
        )
        val whole = draft.withCrew(
            "home_address",
            Json.parseToJsonElement("""{"line1":"12 Baker Street","city":"London","postal_code":"NW1 6XE"}"""),
        )

        assertEquals(CrewMissing.REQUIRED, CrewMissing.of(d, draft).hint("home_address"))
        assertEquals(CrewMissing.REQUIRED, CrewMissing.of(d, partial).hint("home_address"))
        assertNull(CrewMissing.of(d, whole).hint("home_address"))
    }

    @Test
    fun `a path belongs to the step it is drawn on`() {
        assertEquals(CrewStep.Bank, CrewMissing.stepOf("bank.name"))
        assertEquals(CrewStep.Emergency, CrewMissing.stepOf("emergency_contact_number"))
        assertEquals(CrewStep.Emergency, CrewMissing.stepOf("emergency_details.address"))
        assertEquals(CrewStep.Representative, CrewMissing.stepOf("representative_details.email"))
        assertEquals(CrewStep.Crew, CrewMissing.stepOf("passport_attachment"))
        assertEquals(CrewStep.Crew, CrewMissing.stepOf("home_address"))
    }

    @Test
    fun `the form opens on the first step with something red, and on the first when nothing is`() {
        val onlyBank = filled(bank = "{}")
        val bankDraft = CrewDraft.seed(onlyBank)
        val red = CrewMissing.of(onlyBank, bankDraft)

        assertEquals(setOf(CrewStep.Bank), red.steps)
        // Crew, Emergency, Representative, Bank: a PAYE crew member has no Loan Out step.
        assertEquals(3, CrewMissing.openingStep(onlyBank, bankDraft))

        assertEquals(0, CrewMissing.openingStep(complete, CrewDraft.seed(complete)))
    }

    @Test
    fun `the first red field is the first one drawn, not the first one required`() {
        val d = deal("""{"_id":"d6","crew_details":{"mandatory_fields":["email","preferredName"]}}""")
        val red = CrewMissing.of(d, CrewDraft.seed(d))

        // Marks come first in the required set, but the form draws the legal name above both.
        assertEquals("full_legal_name", red.firstOn(CrewStep.Crew))
        assertEquals("bank.account_holder_name", red.firstOn(CrewStep.Bank))
        assertNull(red.firstOn(CrewStep.Representative))
    }

    // -- the territory block ------------------------------------------------------------------------

    private fun uk(status: String, block: String = "") = deal(
        """{"_id":"d7","territory_union":{"territory_code":"uk"},
            "crew_details":{"emp_status":"$status","full_legal_name":"A","emergency_contact_name":"B",
              "emergency_contact_number":"1"$block},
            "bank":{"name":"B","account_holder_name":"C","account_number":"1"}}""",
    )

    @Test
    fun `a PAYE crew member with no HMRC answer has the payroll card red, and the Crew step with it`() {
        val d = uk("paye")
        val red = CrewMissing.of(d, CrewDraft.seed(d))

        assertTrue(red.payroll)
        assertTrue(red.any)
        assertTrue(red.paths.isEmpty())
        assertEquals(setOf(CrewStep.Crew), red.steps)
        assertEquals(CrewMissing.PAYROLL, red.firstOn(CrewStep.Crew))
    }

    @Test
    fun `a starter statement answers the card, a half-filled P45 does not`() {
        val statement = uk("paye", ""","uk":{"starter_statement":"A"}""")
        assertFalse(CrewMissing.of(statement, CrewDraft.seed(statement)).payroll)

        val partial = uk("paye", ""","uk":{"p45_previous_pay":0,"p45_previous_tax":0}""")
        assertTrue(CrewMissing.of(partial, CrewDraft.seed(partial)).payroll)

        val whole = uk(
            "paye",
            ""","uk":{"p45_previous_pay":0,"p45_previous_tax":0,"p45_leaving_date":1700000000000,
                "p45_previous_paye_ref":"123/AB456"}""",
        )
        assertFalse(CrewMissing.of(whole, CrewDraft.seed(whole)).payroll)
    }

    @Test
    fun `the card is not asked of a loan-out or outside the UK`() {
        val loanOut = uk("ltd")
        assertFalse(CrewMissing.of(loanOut, CrewDraft.seed(loanOut)).payroll)

        val abroad = filled(territory = ""","territory_union":{"territory_code":"us"}""")
        assertFalse(CrewMissing.of(abroad, CrewDraft.seed(abroad)).payroll)
    }

    @Test
    fun `typing the HMRC answer into the draft clears the card`() {
        val d = uk("paye")
        val draft = CrewDraft.seed(d)
        val answered = draft.withCrew(
            "uk",
            JsonObject(CrewDraft.ukFromWire(null) + ("starter_statement" to JsonPrimitive("C"))),
        )

        assertTrue(CrewMissing.of(d, draft).payroll)
        assertFalse(CrewMissing.of(d, answered).payroll)
    }

    private companion object {
        // Each either/or pair is two required paths but one line in the pop-up's list.
        const val PAIRS_IN_BLOCKER_LIST = 2
    }
}
