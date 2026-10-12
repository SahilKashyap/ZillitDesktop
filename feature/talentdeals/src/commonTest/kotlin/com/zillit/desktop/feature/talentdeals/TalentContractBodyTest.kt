@file:Suppress("MaxLineLength") // Wire fixtures read best as one JSON line each.

package com.zillit.desktop.feature.talentdeals

import com.zillit.desktop.feature.talentdeals.data.TalentWire
import com.zillit.desktop.feature.talentdeals.domain.ExpenseDraft
import com.zillit.desktop.feature.talentdeals.domain.FeeBasis
import com.zillit.desktop.feature.talentdeals.domain.PaidAs
import com.zillit.desktop.feature.talentdeals.domain.PaymentMode
import com.zillit.desktop.feature.talentdeals.domain.TalentAttachment
import com.zillit.desktop.feature.talentdeals.domain.TalentContractBody
import com.zillit.desktop.feature.talentdeals.domain.TalentField
import com.zillit.desktop.feature.talentdeals.domain.TalentFileSlot
import com.zillit.desktop.feature.talentdeals.domain.TalentForm
import com.zillit.desktop.feature.talentdeals.domain.TalentIds
import com.zillit.desktop.feature.talentdeals.domain.TalentValidation
import com.zillit.desktop.feature.talentdeals.domain.TrancheDraft
import com.zillit.desktop.feature.talentdeals.domain.WithholdingType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `buildContract` — the POST body for `/api/v2/deal-memo/talent-contracts`, in
 * the TalentContract model's field names — and its inverse, `fromContract`, so
 * Edit reopens the SAME form. The form speaks camelCase with yyyy-mm-dd dates
 * and picked files; the API speaks snake_case with epoch ms and attachment
 * envelopes. This is the one place the two meet, so it is pinned field by field.
 */
class TalentContractBodyTest {

    private val attachment = TalentAttachment(
        media = "p/actual/1.pdf",
        bucket = "b",
        region = "r",
        name = "Agreement.pdf",
        contentType = "document",
        contentSubtype = "pdf",
    )
    private val certificate = attachment.copy(name = "HMRC.pdf")

    private val form = TalentForm(
        person = "  Jane Doe ",
        contractFile = TalentFileSlot.Stored(attachment),
        territory = "us",
        union = "sag",
        paymentMode = PaymentMode.Escrow,
        feeBasis = FeeBasis.Salary,
        fee = "500000",
        currency = "USD",
        weekly = true,
        withholding = true,
        withholdingType = WithholdingType.Percent,
        withholdingValue = "20",
        withholdingAuthorised = true,
        authorisedRate = "8",
        certificateFile = TalentFileSlot.Stored(certificate),
        hasExpenses = true,
        expenses = listOf(
            ExpenseDraft("e1", "Travel", "4200", withhold = true, grossUp = true),
            ExpenseDraft("e2", "Per diem", "9,000", withhold = false, grossUp = true),
        ),
        paidAs = PaidAs.Percent,
        tranches = listOf(
            TrancheDraft("t1", "Prep", "2026-01-05", "2026-02-13", "6", "40"),
            TrancheDraft("t2", "Shoot", "2026-02-16", "2026-05-29", "15", "60"),
        ),
        autoTimeCards = true,
    )

    private fun body(of: TalentForm = form, contract: TalentAttachment? = attachment,
        cert: TalentAttachment? = certificate) =
        TalentContractBody.build(of, contract, cert, reference = "TC-482202", role = "Cast")

    // -- the body ----------------------------------------------------------------------

    @Test
    fun `the model's names, not the form's`() {
        val b = body()
        assertEquals("TC-482202", b["talent_reference"]!!.jsonPrimitive.content)
        assertEquals("Jane Doe", b["person"]!!.jsonPrimitive.content)
        assertEquals("Cast", b["role"]!!.jsonPrimitive.content)
        assertEquals("us", b["territory_code"]!!.jsonPrimitive.content)
        assertEquals("sag", b["union_identifier"]!!.jsonPrimitive.content)
        assertEquals("500000", b["fee"]!!.jsonPrimitive.content)
        assertEquals("USD", b["contract_currency"]!!.jsonPrimitive.content)
        assertEquals("escrow", b["payment_mode"]!!.jsonPrimitive.content)
        assertEquals("salary", b["fee_basis"]!!.jsonPrimitive.content)
        assertTrue(b["auto_time_cards"]!!.jsonPrimitive.boolean)
        assertEquals("issued", b["status"]!!.jsonPrimitive.content)
        assertEquals("p/actual/1.pdf", b["long_form_contract"]!!.jsonObject["media"]!!.jsonPrimitive.content)
        assertFalse("currency" in b)
        assertFalse("territory" in b)
    }

    @Test
    fun `withholding nests, with the certificate only when authorised`() {
        val w = body()["withholding"]!!.jsonObject
        assertTrue(w["applies"]!!.jsonPrimitive.boolean)
        assertEquals("pct", w["basis"]!!.jsonPrimitive.content)
        assertEquals("20", w["value"]!!.jsonPrimitive.content)
        assertTrue(w["authorised"]!!.jsonPrimitive.boolean)
        assertEquals("8", w["authorised_rate"]!!.jsonPrimitive.content)
        assertEquals("HMRC.pdf", w["certificate_attachment"]!!.jsonObject["name"]!!.jsonPrimitive.content)

        val unauthorised = body(form.copy(withholdingAuthorised = false))["withholding"]!!.jsonObject
        assertFalse(unauthorised["authorised"]!!.jsonPrimitive.boolean)
        assertEquals("0", unauthorised["authorised_rate"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, unauthorised["certificate_attachment"])
    }

    @Test
    fun `the withholding block is zeroed when it does not apply`() {
        val w = body(form.copy(withholding = false))["withholding"]!!.jsonObject
        assertFalse(w["applies"]!!.jsonPrimitive.boolean)
        assertEquals("pct", w["basis"]!!.jsonPrimitive.content)
        assertEquals("0", w["value"]!!.jsonPrimitive.content)
        assertFalse(w["authorised"]!!.jsonPrimitive.boolean)
        assertEquals("0", w["authorised_rate"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, w["certificate_attachment"])
    }

    @Test
    fun `tranche dates go as epoch ms and figures as numbers`() {
        val schedule = body()["payment_schedule"]!!.jsonObject
        assertEquals("weekly", schedule["cadence"]!!.jsonPrimitive.content)
        assertEquals("pct", schedule["paid_as"]!!.jsonPrimitive.content)
        val first = schedule["tranches"]!!.jsonArray[0].jsonObject
        assertEquals("Prep", first["label"]!!.jsonPrimitive.content)
        assertEquals(1767571200000L, first["start_date"]!!.jsonPrimitive.long)
        assertEquals(1770940800000L, first["end_date"]!!.jsonPrimitive.long)
        assertEquals("6", first["weeks"]!!.jsonPrimitive.content)
        assertEquals("40", first["value"]!!.jsonPrimitive.content)
    }

    @Test
    fun `no tranches go when the weekly term is off`() {
        val schedule = body(form.copy(weekly = false))["payment_schedule"]!!.jsonObject
        assertEquals("", schedule["cadence"]!!.jsonPrimitive.content)
        assertEquals("pct", schedule["paid_as"]!!.jsonPrimitive.content)
        assertEquals(0, schedule["tranches"]!!.jsonArray.size)
    }

    @Test
    fun `gross_up never goes on a line nothing is withheld on`() {
        val expenses = body()["expenses"]!!.jsonArray.map { it.jsonObject }
        assertEquals(2, expenses.size)
        assertTrue(expenses[0]["gross_up"]!!.jsonPrimitive.boolean)
        assertEquals("9000", expenses[1]["amount"]!!.jsonPrimitive.content)
        assertFalse(expenses[1]["withhold"]!!.jsonPrimitive.boolean)
        assertFalse(expenses[1]["gross_up"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `a seeded expense row nobody filled in drops out, a named or priced one stays`() {
        val rows = listOf(
            ExpenseDraft("a", "", "", withhold = true),
            ExpenseDraft("b", "Per diem", "", withhold = true),
            ExpenseDraft("c", "", "50", withhold = true),
        )
        val expenses = body(form.copy(expenses = rows))["expenses"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("Per diem", ""), expenses.map { it["label"]!!.jsonPrimitive.content })
        assertEquals(listOf("0", "50"), expenses.map { it["amount"]!!.jsonPrimitive.content })
    }

    @Test
    fun `fee_basis is dropped when there is no payment mode`() {
        val b = body(form.copy(paymentMode = null))
        assertEquals("", b["payment_mode"]!!.jsonPrimitive.content)
        assertEquals("", b["fee_basis"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a missing agreement goes as null`() {
        assertEquals(JsonNull, body(contract = null)["long_form_contract"])
    }

    @Test
    fun `a stored envelope is sent back untouched, keys this client does not model included`() {
        val stored = Json.parseToJsonElement(
            """{"media":"m","bucket":"b","region":"r","name":"n.pdf","content_type":"document","content_subtype":"pdf","caption":"","file_size":42}""",
        ).jsonObject
        val row = TalentWire.contractOf(
            Json.parseToJsonElement("""{"_id":"c1","person":"X","long_form_contract":$stored}""").jsonObject,
        )!!
        val sent = body(contract = row.longFormContract)["long_form_contract"]!!.jsonObject
        assertEquals(stored, sent)
        assertEquals("42", sent["file_size"]!!.jsonPrimitive.content)
    }

    @Test
    fun `references are TC and six digits`() {
        assertEquals("TC-482202", TalentContractBody.newReference { 482202 })
    }

    // -- the inverse -------------------------------------------------------------------

    private val stored = """
        {"_id":"id-1","talent_reference":"TC-482202","person":"Jane Doe","role":"Cast","status":"issued",
         "long_form_contract":{"media":"p/actual/1.pdf","bucket":"b","region":"r","name":"Agreement.pdf","content_type":"document","content_subtype":"pdf"},
         "territory_code":"us","union_identifier":"sag","fee":500000,"contract_currency":"USD",
         "payment_mode":"escrow","fee_basis":"salary",
         "withholding":{"applies":true,"basis":"pct","value":20,"authorised":true,"authorised_rate":8,
           "certificate_attachment":{"media":"p/actual/2.pdf","bucket":"b","region":"r","name":"HMRC.pdf"}},
         "payment_schedule":{"cadence":"weekly","paid_as":"pct","tranches":[
           {"label":"Prep","start_date":1767571200000,"end_date":1770940800000,"weeks":6,"value":40},
           {"label":"Shoot","start_date":1771200000000,"end_date":1780012800000,"weeks":15,"value":60}]},
         "expenses":[{"label":"Travel","amount":4200,"withhold":true,"gross_up":true}],
         "auto_time_cards":true}
    """.trimIndent()

    private fun row() = TalentWire.contractOf(Json.parseToJsonElement(stored).jsonObject)!!

    @Test
    fun `every field comes back in the form's shape`() {
        val f = TalentContractBody.fromContract(row(), TalentIds())
        assertEquals("Jane Doe", f.person)
        assertEquals("Agreement.pdf", f.contractFile!!.name)
        assertEquals("us", f.territory)
        assertEquals("sag", f.union)
        assertEquals(PaymentMode.Escrow, f.paymentMode)
        assertEquals(FeeBasis.Salary, f.feeBasis)
        assertEquals("500000", f.fee)
        assertEquals("USD", f.currency)
        assertTrue(f.weekly)
        assertTrue(f.withholding)
        assertEquals("20", f.withholdingValue)
        assertTrue(f.withholdingAuthorised)
        assertEquals("8", f.authorisedRate)
        assertEquals("HMRC.pdf", f.certificateFile!!.name)
        assertTrue(f.hasExpenses)
        assertEquals("Prep", f.tranches[0].label)
        assertEquals("2026-01-05", f.tranches[0].start)
        assertEquals("2026-02-13", f.tranches[0].end)
        assertEquals("6", f.tranches[0].weeks)
        assertEquals("40", f.tranches[0].value)
        assertTrue(f.autoTimeCards)
    }

    @Test
    fun `ids are unique across rows`() {
        val f = TalentContractBody.fromContract(row(), TalentIds())
        val ids = f.tranches.map { it.id } + f.expenses.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `a full round trip gives the same body back`() {
        val first = TalentContractBody.fromContract(row(), TalentIds())
        val stored = row()
        val again = TalentContractBody.build(
            form = first,
            contract = (first.contractFile as TalentFileSlot.Stored).attachment,
            certificate = (first.certificateFile as TalentFileSlot.Stored).attachment,
            reference = stored.reference,
            role = stored.role,
        )
        // Building from the stored row's own form reproduces what a Save would send.
        val round = TalentContractBody.fromContract(
            TalentWire.contractOf(JsonObject(again + ("_id" to JsonPrimitive("id-1"))))!!,
            TalentIds(),
        )
        assertEquals(first.copy(tranches = emptyList(), expenses = emptyList()), round.copy(tranches = emptyList(),
            expenses = emptyList()))
        assertEquals(first.tranches.map { it.copy(id = "") }, round.tranches.map { it.copy(id = "") })
        assertEquals(first.expenses.map { it.copy(id = "") }, round.expenses.map { it.copy(id = "") })
    }

    @Test
    fun `an untouched row gives blank strings, not zeros`() {
        val blank = TalentContractBody.fromContract(TalentWire.contractOf(Json.parseToJsonElement("""{"_id":"x","person":"X"}""").jsonObject)!!, TalentIds())
        assertEquals("", blank.withholdingValue)
        assertEquals("", blank.authorisedRate)
        assertEquals("", blank.fee)
        assertEquals(emptyList(), blank.tranches)
        assertFalse(blank.weekly)
        assertNull(blank.contractFile)
        assertNull(blank.paymentMode)
    }

    // -- validation --------------------------------------------------------------------

    @Test
    fun `an empty form is missing the five basics`() {
        val errors = TalentValidation.errors(TalentForm(), unionRequired = true)
        assertEquals(
            setOf(TalentField.Person, TalentField.Contract, TalentField.Fee, TalentField.Territory, TalentField.Union,
                TalentField.PaymentMode),
            errors,
        )
    }

    @Test
    fun `a complete form has no errors`() {
        assertEquals(emptySet(), TalentValidation.errors(form.copy(tranches = form.tranches), unionRequired = true))
    }

    @Test
    fun `the union is only required when there is something to pick`() {
        assertFalse(TalentField.Union in TalentValidation.errors(form.copy(union = ""), unionRequired = false))
        assertTrue(TalentField.Union in TalentValidation.errors(form.copy(union = ""), unionRequired = true))
    }

    @Test
    fun `what the payment covers is only asked once a mode is chosen`() {
        assertTrue(TalentField.FeeBasis in TalentValidation.errors(form.copy(feeBasis = null), true))
        assertFalse(TalentField.FeeBasis in TalentValidation.errors(form.copy(paymentMode = null, feeBasis = null),
            true))
        assertTrue(TalentField.PaymentMode in TalentValidation.errors(form.copy(paymentMode = null, feeBasis = null),
            true))
    }

    @Test
    fun `withholding needs its figure, and an authorisation its rate`() {
        assertTrue(TalentField.Withholding in TalentValidation.errors(form.copy(withholdingValue = ""), true))
        assertTrue(TalentField.AuthorisedRate in TalentValidation.errors(form.copy(authorisedRate = "0"), true))
        assertFalse(TalentField.AuthorisedRate in TalentValidation.errors(form.copy(withholdingAuthorised = false,
            authorisedRate = ""), true))
        assertFalse(TalentField.Withholding in TalentValidation.errors(form.copy(withholding = false,
            withholdingValue = ""), true))
    }

    @Test
    fun `a half-allocated schedule blocks the save, but only once there is a fee`() {
        val short = form.copy(tranches = listOf(TrancheDraft("t", "Shoot", value = "60")))
        assertTrue(TalentField.Schedule in TalentValidation.errors(short, true))
        // With the fee still blank, "allocate the whole fee" would be a second message about the same empty box.
        val noFee = TalentValidation.errors(short.copy(fee = ""), true)
        assertFalse(TalentField.Schedule in noFee)
        assertTrue(TalentField.Fee in noFee)
        // A schedule behind an unticked weekly term does not gate the save.
        assertFalse(TalentField.Schedule in TalentValidation.errors(short.copy(weekly = false), true))
    }

    // -- the form's own moves ----------------------------------------------------------

    @Test
    fun `the two escrow boxes are one choice`() {
        val first = TalentForm().pickMode(PaymentMode.Escrow)
        assertEquals(PaymentMode.Escrow, first.paymentMode)
        assertEquals(PaymentMode.FirstDay, first.pickMode(PaymentMode.FirstDay).paymentMode)
        // Unticking the mode clears what it covers.
        val cleared = first.pickBasis(FeeBasis.Fringes).pickMode(PaymentMode.Escrow)
        assertNull(cleared.paymentMode)
        assertNull(cleared.feeBasis)
    }

    @Test
    fun `ticking weekly seeds a Prep row, unticking clears the table`() {
        val ids = TalentIds()
        val on = TalentForm().toggleWeekly(true, ids)
        assertEquals(listOf("Prep"), on.tranches.map { it.label })
        assertEquals(emptyList(), on.toggleWeekly(false, ids).tranches)
    }

    @Test
    fun `ticking expenses seeds a blank row, unticking clears it`() {
        val ids = TalentIds()
        val on = TalentForm().toggleExpenses(true, ids)
        assertEquals(1, on.expenses.size)
        assertEquals(emptyList(), on.toggleExpenses(false, ids).expenses)
    }

    @Test
    fun `switching the withholding type clears the figure, unticking authorisation clears its rate and file`() {
        val switched = form.setWithholdingType(WithholdingType.Amount)
        assertEquals("", switched.withholdingValue)
        val unauthorised = form.setAuthorised(false)
        assertEquals("", unauthorised.authorisedRate)
        assertNull(unauthorised.certificateFile)
        assertNotNull(form.setAuthorised(true).certificateFile)
    }

    @Test
    fun `a body is a JSON object`() {
        assertTrue(body() is JsonObject)
    }
}
