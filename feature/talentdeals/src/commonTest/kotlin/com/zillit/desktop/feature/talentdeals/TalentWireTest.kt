@file:Suppress("MaxLineLength") // Wire fixtures read best as one JSON line each.

package com.zillit.desktop.feature.talentdeals

import com.zillit.desktop.feature.talentdeals.data.TalentSyncEnvelope
import com.zillit.desktop.feature.talentdeals.data.TalentWire
import com.zillit.desktop.feature.talentdeals.domain.PaidAs
import com.zillit.desktop.feature.talentdeals.domain.TalentAttachment
import com.zillit.desktop.feature.talentdeals.domain.TalentStatus
import com.zillit.desktop.feature.talentdeals.domain.WithholdingType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The wire traps, pinned with captured shapes: snake_case keys, numbers and
 * booleans that arrive as strings, a currency that is a bare code or the whole
 * object, missing keys everywhere, money in whole units.
 */
class TalentWireTest {

    private fun row(json: String) = TalentWire.contractOf(Json.parseToJsonElement(json).jsonObject)

    @Test
    fun `a stored contract maps field for field`() {
        val c = row(
            """{"_id":"c1","talent_reference":"TC-100200","person":"Jane Doe","role":"Cast","status":"issued",
               "long_form_contract":{"media":"p/1.pdf","bucket":"b","region":"r","name":"Deal.pdf","content_type":"document","content_subtype":"pdf","caption":""},
               "territory_code":"uk","union_identifier":"sag-aftra","fee":250000,"contract_currency":"GBP",
               "payment_mode":"first-day","fee_basis":"fringes",
               "withholding":{"applies":true,"basis":"amount","value":5000,"authorised":false,"authorised_rate":0,"certificate_attachment":null},
               "payment_schedule":{"cadence":"weekly","paid_as":"flat","tranches":[{"label":"Prep","start_date":1767571200000,"end_date":1768089600000,"weeks":1,"value":50000}]},
               "expenses":[{"label":"Per diem","amount":120.5,"withhold":false,"gross_up":false}],
               "auto_time_cards":true}""",
        )!!
        assertEquals("c1", c.id)
        assertEquals("TC-100200", c.reference)
        assertEquals("Jane Doe", c.person)
        assertEquals(TalentStatus.Issued, c.statusKind)
        assertEquals("Deal.pdf", c.longFormContract!!.name)
        assertEquals("pdf", c.longFormContract!!.extension)
        assertTrue(c.longFormContract!!.isPdf)
        assertEquals(250000.0, c.fee)
        assertEquals("GBP", c.currency)
        assertEquals(WithholdingType.Amount, c.withholding.basis)
        assertEquals(5000.0, c.withholding.value)
        assertNull(c.withholding.certificate)
        assertEquals(PaidAs.Flat, c.schedule.paidAs)
        assertTrue(c.schedule.isWeekly)
        assertEquals(1, c.trancheCount)
        assertEquals(1767571200000L, c.schedule.tranches.single().startDate)
        assertEquals(120.5, c.expenses.single().amount)
        assertTrue(c.autoTimeCards)
    }

    @Test
    fun `numbers and booleans arrive as strings and still read`() {
        val c = row(
            """{"_id":"c1","fee":"1,500.50","withholding":{"applies":"true","value":"20","authorised":"false"},
               "expenses":[{"label":"x","amount":"10","withhold":"true","gross_up":"1"}],
               "auto_time_cards":"true"}""",
        )!!
        assertEquals(1500.5, c.fee)
        assertTrue(c.withholding.applies)
        assertEquals(20.0, c.withholding.value)
        assertFalse(c.withholding.authorised)
        assertTrue(c.expenses.single().withhold)
        assertTrue(c.expenses.single().grossUp)
        assertTrue(c.autoTimeCards)
    }

    @Test
    fun `the currency is a bare code or the whole object, and a row survives either`() {
        assertEquals("GBP", row("""{"_id":"a","contract_currency":"GBP"}""")!!.currency)
        assertEquals("GBP",
            row("""{"_id":"a","contract_currency":{"code":"GBP","name":"Pound","symbol":"£"}}""")!!.currency)
        assertEquals("", row("""{"_id":"a"}""")!!.currency)
        assertEquals("", row("""{"_id":"a","contract_currency":null}""")!!.currency)
    }

    @Test
    fun `a row with no id is unplaceable and an id-only row is empty but whole`() {
        assertNull(row("""{"person":"No id"}"""))
        val bare = row("""{"id":"only"}""")!!
        assertEquals("only", bare.id)
        assertEquals(0.0, bare.fee)
        assertEquals(emptyList(), bare.expenses)
        assertEquals(PaidAs.Percent, bare.schedule.paidAs)
        assertFalse(bare.schedule.isWeekly)
        assertEquals(TalentStatus.Unknown, bare.statusKind)
    }

    @Test
    fun `an attachment with no media is no attachment`() {
        assertNull(row("""{"_id":"a","long_form_contract":{"name":"ghost.pdf"}}""")!!.longFormContract)
        assertNull(row("""{"_id":"a","long_form_contract":null}""")!!.longFormContract)
    }

    @Test
    fun `status words match whatever their case`() {
        assertEquals(TalentStatus.Cancelled, TalentStatus.of(" Cancelled "))
        assertEquals(TalentStatus.Unknown, TalentStatus.of("archived"))
    }

    @Test
    fun `union rows need an identifier`() {
        val a = TalentWire.unionOf(Json.parseToJsonElement("""{"union_id":"6ab","union_identifier":"mee","union_name":"MEAA"}""").jsonObject)
        assertEquals("6ab", a!!.id)
        assertEquals("MEAA", a.name)
        assertNull(TalentWire.unionOf(Json.parseToJsonElement("""{"union_name":"No slug"}""").jsonObject))
    }

    @Test
    fun `the register's search matches name, reference, role and file name`() {
        val c = row("""{"_id":"a","talent_reference":"TC-482202","person":"Jane Doe","role":"Producer","long_form_contract":{"media":"m","name":"Long Form.pdf"}}""")!!
        listOf("jane", "tc-4822", "produc", "long form").forEach { assertTrue(it in c.searchText(), it) }
        assertFalse("zzz" in c.searchText())
    }

    @Test
    fun `an attachment names itself from the key when it has no name`() {
        assertEquals("1.pdf", TalentAttachment(media = "p/actual/1.pdf").displayName)
        assertTrue(TalentAttachment(media = "m", name = "photo.PNG").isImage)
    }

    // -- the socket frame --------------------------------------------------------------

    @Test
    fun `a frame names its contract by _id or id, and other productions' frames are not ours`() {
        val frame = Json.decodeFromString(TalentSyncEnvelope.serializer(),
            """{"project_id":"p1","data":{"_id":"c9","talent_reference":"TC-1"}}""")
        assertEquals("c9", frame.toRefresh().contractId)
        assertTrue(frame.inProject("p1"))
        assertFalse(frame.inProject("p2"))
        assertTrue(frame.inProject(null))

        val byId = Json.decodeFromString(TalentSyncEnvelope.serializer(), """{"data":{"id":"c3"}}""")
        assertEquals("c3", byId.contractId)
        assertTrue(byId.inProject("anything"))

        assertNull(Json.decodeFromString(TalentSyncEnvelope.serializer(), """{"data":{}}""").contractId)
        assertNull(Json.decodeFromString(TalentSyncEnvelope.serializer(), """{}""").contractId)
        assertNotNull(Json.decodeFromString(TalentSyncEnvelope.serializer(), """{"data":{"_id":"x"}}""").contractId)
    }
}
