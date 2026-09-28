package com.zillit.desktop.feature.payroll

import com.zillit.desktop.feature.payroll.data.toAssetTags
import com.zillit.desktop.feature.payroll.data.toTrackingSets
import com.zillit.desktop.feature.payroll.domain.Journal
import com.zillit.desktop.feature.payroll.domain.JournalCategory
import com.zillit.desktop.feature.payroll.domain.JournalEdit
import com.zillit.desktop.feature.payroll.domain.JournalRow
import com.zillit.desktop.feature.payroll.domain.JournalSplits
import com.zillit.desktop.feature.payroll.domain.PayrollTimecard
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private var seq = 0
private fun id(): String = "c${seq++}"

private fun row(
    id: String = "r1",
    src: String = "rates_ots",
    amount: Double? = 100.0,
    code: String = "5000",
    date: String? = "2026-05-08",
    taxBase: Double? = null,
    taxRate: Double? = null,
) = JournalRow(
    id = id,
    timecardId = "tc1",
    companyId = "co",
    src = src,
    groupKey = "basic",
    identifier = "basic",
    label = "Basic",
    category = if (src == Journal.SRC_TAX) JournalCategory.Tax else JournalCategory.Basic,
    description = "Week 04 May Ada Basic",
    descriptionOverride = "",
    amount = amount,
    code = code,
    effectiveDate = date,
    taxRate = taxRate,
    taxBase = taxBase,
)

/**
 * Splitting a journal line into ledger allocations.
 *
 * The parent's figure is the timecard's and stays derived; the children carry
 * fixed amounts that must sum to it, because the server does not rebalance
 * them. Every case below is about that invariant holding.
 */
class JournalSplitTest {

    @Test
    fun `a first split cuts the line in two even halves`() {
        val parent = row(amount = 100.0)
        val splits = JournalSplits.split(parent, null, ::id)
        assertEquals(2, splits.size)
        assertEquals(listOf(50.0, 50.0), splits.map { it.amount })
        // Each allocation starts from the parent's coding — that is what the
        // accountant is editing away from.
        assertTrue(splits.all { it.nominalCode == "5000" })
        assertTrue(splits.all { it.effectiveDate == "2026-05-08" })
        assertTrue(splits.all { it.description == "Week 04 May Ada Basic" })
    }

    /**
     * An odd total cannot divide evenly; the last allocation takes the penny.
     * Summed to the penny, because that is the figure on screen — a double's
     * last bits are not something the ledger ever shows.
     */
    @Test
    fun `allocations always sum to the parent, remainder on the last`() {
        val parent = row(amount = 100.01)
        val two = JournalSplits.split(parent, null, ::id)
        assertEquals(listOf(50.0, 50.01), two.map { it.amount })
        assertEquals(100.01, PayrollTimecard.round2(two.sumOf { it.amount }))
        val three = JournalSplits.split(parent, JournalEdit(splits = two), ::id)
        assertEquals(3, three.size)
        assertEquals(100.01, PayrollTimecard.round2(three.sumOf { it.amount }))
    }

    @Test
    fun `each later split adds one allocation and evens them all out`() {
        val parent = row(amount = 90.0)
        val two = JournalSplits.split(parent, null, ::id)
        val three = JournalSplits.split(parent, JournalEdit(splits = two), ::id)
        assertEquals(listOf(30.0, 30.0, 30.0), three.map { it.amount })
        // The allocations already on screen keep their identity, so a code
        // typed into one does not jump to another when a third is added.
        assertEquals(two.map { it.id }, three.dropLast(1).map { it.id })
    }

    @Test
    fun `typing one allocation re-spreads its siblings`() {
        val parent = row(amount = 120.0)
        val three = JournalSplits.split(parent, JournalEdit(splits = JournalSplits.split(parent, null, ::id)), ::id)
        val edited = JournalSplits.editSplitAmount(parent, JournalEdit(splits = three), three[0].id, 60.0)
        assertEquals(60.0, edited[0].amount)
        assertEquals(120.0, PayrollTimecard.round2(edited.sumOf { it.amount }))
        // The typed one is left exactly as typed; only the others move.
        assertEquals(listOf(60.0, 30.0, 30.0), edited.map { it.amount })
    }

    @Test
    fun `removing an allocation re-spreads the rest`() {
        val parent = row(amount = 90.0)
        val three = JournalSplits.split(parent, JournalEdit(splits = JournalSplits.split(parent, null, ::id)), ::id)
        val two = JournalSplits.removeSplit(parent, JournalEdit(splits = three), three[1].id)
        assertEquals(2, two.size)
        assertEquals(90.0, PayrollTimecard.round2(two.sumOf { it.amount }))
    }

    /** One allocation is not a split — the row goes back to posting whole. */
    @Test
    fun `removing down to one allocation un-splits the line`() {
        val parent = row(amount = 90.0)
        val two = JournalSplits.split(parent, null, ::id)
        assertTrue(JournalSplits.removeSplit(parent, JournalEdit(splits = two), two[0].id).isEmpty())
    }

    /** A locked code is configuration; an allocation inherits it on the wire. */
    @Test
    fun `an allocation of a payroll account cannot be repointed`() {
        val account = row(id = "acct::a", src = Journal.SRC_ACCOUNT, amount = 500.0, code = "2210")
        val splits = JournalSplits.split(account, null, ::id).map { it.copy(nominalCode = "9999") }
        val lines = Journal.lineFor(account, JournalEdit(splits = splits))
        val children = lines.filter { it.splitParentId != null }
        assertEquals(2, children.size)
        assertTrue(children.all { it.nominalCode == "2210" })
        // A credit row's allocations are credits too.
        assertTrue(children.all { it.credit != null && it.debit == null })
    }

    @Test
    fun `an allocation of a debit row is a debit`() {
        val parent = row(amount = 100.0)
        val lines = Journal.lineFor(parent, JournalEdit(splits = JournalSplits.split(parent, null, ::id)))
        val children = lines.filter { it.splitParentId != null }
        assertTrue(children.all { it.debit != null && it.credit == null })
        assertTrue(children.all { it.splitParentId == "r1" })
    }

    @Test
    fun `a line is not splittable once it is the tax line`() {
        assertFalse(row(src = Journal.SRC_TAX).splittable)
        assertTrue(row().splittable)
    }
}

/** The tax line: one per timecard, its figure following the rate until overridden. */
class JournalTaxTest {

    private val tax = row(id = "tax::tc1", src = Journal.SRC_TAX, amount = null, code = "", taxBase = 1000.0)

    @Test
    fun `the figure follows rate times base`() {
        assertEquals(200.0, Journal.amountOf(tax.copy(taxRate = 20.0), null))
        assertEquals(175.0, Journal.amountOf(tax, JournalEdit(taxRate = 17.5)))
        // No rate is no tax, not a zero line.
        assertNull(Journal.amountOf(tax, null))
        assertNull(Journal.amountOf(tax, JournalEdit(taxRate = 0.0)))
    }

    @Test
    fun `a typed figure overrides the rate, and clearing it goes back to derived`() {
        assertEquals(500.0, Journal.amountOf(tax.copy(taxRate = 20.0), JournalEdit(amount = 500.0)))
        val cleared = JournalEdit(taxRate = 20.0, amountCleared = true)
        assertEquals(200.0, Journal.amountOf(tax, cleared))
    }

    /** Only the tax line and a payroll account carry a typed figure. */
    @Test
    fun `a pay break's figure is the timecard's and is never typed`() {
        assertFalse(row().amountEditable)
        assertTrue(tax.amountEditable)
        assertTrue(row(src = Journal.SRC_ACCOUNT).amountEditable)
    }

    @Test
    fun `the tax line posts its figure as a debit, with the picked rate`() {
        val line = Journal.lineFor(tax, JournalEdit(taxRate = 20.0)).single()
        assertEquals(200.0, line.debit)
        assertNull(line.credit)
        assertEquals(20.0, line.taxRate)
    }

    /** A typed description is what posts; the derived one is only a default. */
    @Test
    fun `the accountant's own wording is what posts`() {
        val line = Journal.lineFor(row(), JournalEdit(description = "Camera basic, week 19")).single()
        assertEquals("Camera basic, week 19", line.ledgerDescription)
        assertEquals("", Journal.lineFor(row(), null).single().ledgerDescription)
    }
}

/**
 * Layers and Tags on a journal line.
 *
 * Both are coding, not money: they decide which dimension a cost lands on in
 * the ledger. An allocation carries its own, because coding one half of a
 * line differently is the reason to split it.
 */
class JournalCodingTest {

    private val layers = mapOf("set-dept" to "CAM", "set-ep" to "EP01")
    private val tags = listOf("Recharge", "Bonded")

    @Test
    fun `a line's layers and tags are the accountant's edit over the saved ones`() {
        val saved = row().copy(trackingCodes = mapOf("set-dept" to "ART"), tags = listOf("Old"))
        assertEquals(mapOf("set-dept" to "ART"), Journal.layersOf(saved, null))
        assertEquals(listOf("Old"), Journal.tagsOf(saved, null))
        val edited = JournalEdit(trackingCodes = layers, tags = tags)
        assertEquals(layers, Journal.layersOf(saved, edited))
        assertEquals(tags, Journal.tagsOf(saved, edited))
    }

    /** Clearing every layer is a real edit, not a fall-back to what was saved. */
    @Test
    fun `an empty map clears the layers rather than reverting them`() {
        val saved = row().copy(trackingCodes = layers)
        assertTrue(Journal.layersOf(saved, JournalEdit(trackingCodes = emptyMap())).isEmpty())
        assertTrue(Journal.tagsOf(saved.copy(tags = tags), JournalEdit(tags = emptyList())).isEmpty())
    }

    @Test
    fun `both travel on the wire, on the line and on each allocation`() {
        val line = Journal.lineFor(row(), JournalEdit(trackingCodes = layers, tags = tags)).single()
        assertEquals(layers, line.trackingCodes)
        assertEquals(tags, line.tags)
    }

    /** A fresh allocation starts from the parent's coding — that is the baseline. */
    @Test
    fun `a new allocation clones the parent's layers and tags`() {
        val parent = row().copy(trackingCodes = layers, tags = tags)
        val splits = JournalSplits.split(parent, null, ::id)
        assertTrue(splits.all { it.trackingCodes == layers })
        assertTrue(splits.all { it.tags == tags })
    }

    /** …and then diverges: that is what splitting is for. */
    @Test
    fun `an allocation can be coded away from its parent`() {
        val parent = row().copy(trackingCodes = layers)
        val splits = JournalSplits.split(parent, null, ::id)
        val moved = splits.mapIndexed { index, child ->
            if (index == 0) child.copy(trackingCodes = mapOf("set-dept" to "ART")) else child
        }
        val children = Journal.lineFor(parent, JournalEdit(splits = moved)).filter { it.splitParentId != null }
        assertEquals(mapOf("set-dept" to "ART"), children[0].trackingCodes)
        assertEquals(layers, children[1].trackingCodes)
    }
}

/**
 * Reading the production's Layers and Tags off the wire.
 *
 * A header row groups codes; it is not something a line can be coded to. An
 * absent `active` means active — only an explicit `false` disables.
 */
class JournalReferenceWireTest {

    @Test
    fun `only active, non-header codes are offered`() {
        val json = """
            {"data":[
              {"_id":"s1","name":"Department","nodes":[
                {"code":"CAM","label":"Camera"},
                {"code":"GRP","label":"Grip","active":false},
                {"code":"00","label":"Production","is_header":true},
                {"code":"ART","name":"Art"}
              ]},
              {"_id":"s2","name":"Retired","active":false,"nodes":[{"code":"X","label":"X"}]}
            ]}
        """.trimIndent()
        val sets = Json.parseToJsonElement(json).toTrackingSets()
        assertEquals(1, sets.size)
        assertEquals("Department", sets[0].name)
        assertEquals(listOf("CAM", "ART"), sets[0].nodes.map { it.code })
        // `name` stands in for a missing `label`.
        assertEquals("Art", sets[0].nodes[1].label)
    }

    @Test
    fun `account tags are read from the settings document, blanks dropped`() {
        val json = """{"value":{"settings":{"asset_tags":["Recharge","  ","Bonded"," Split "]}}}"""
        assertEquals(listOf("Recharge", "Bonded", "Split"), Json.parseToJsonElement(json).toAssetTags())
    }
}
