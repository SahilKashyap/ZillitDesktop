package com.zillit.desktop.feature.invoices

import com.zillit.desktop.feature.invoices.domain.CodedLine
import com.zillit.desktop.feature.invoices.domain.EntryBlock
import com.zillit.desktop.feature.invoices.domain.EntryCoding
import com.zillit.desktop.feature.invoices.domain.EntryHeader
import com.zillit.desktop.feature.invoices.domain.Invoice
import com.zillit.desktop.feature.invoices.domain.LinkedPoDetail
import com.zillit.desktop.feature.invoices.domain.PeriodLock
import com.zillit.desktop.feature.invoices.domain.PoLine
import com.zillit.desktop.feature.invoices.domain.TaxLine
import com.zillit.desktop.feature.invoices.domain.TaxType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The coding screen's rules — `EntryDetailModal.jsx` and `lib/lineItemSplit.js`. */
class EntryCodingTest {

    private var ids = 0
    private val newId = { "n${++ids}" }

    // -- seeding -----------------------------------------------------------------

    @Test
    fun `saved lines win, then the orders' lines, then one line for the whole invoice`() {
        val saved = Invoice(id = "i", grossAmount = 120.0, lineItems = listOf(CodedLine("a", amount = 100.0)))
        assertEquals(listOf("a"), EntryCoding.seedLines(saved, listOf(CodedLine("po")), newId).map { it.id })

        val bare = Invoice(id = "i", description = "Grip hire", grossAmount = 120.0)
        assertEquals(listOf("po"), EntryCoding.seedLines(bare, listOf(CodedLine("po")), newId).map { it.id })

        val single = EntryCoding.seedLines(bare, emptyList(), newId).single()
        assertEquals("Grip hire", single.description)
        assertEquals(120.0, single.amount)
        assertEquals(
            "Invoice",
            EntryCoding.seedLines(bare.copy(description = ""), emptyList(), newId).single().description,
        )
    }

    @Test
    fun `an order's lines keep their coding and say where they came from`() {
        val order = LinkedPoDetail(
            poId = "po1",
            poNumber = "PO-0007",
            lines = listOf(
                PoLine(description = "Lamps", quantity = 2.0, unitPrice = 50.0, account = "2400", taxRate = 20.0),
            ),
        )
        val line = EntryCoding.poLines(listOf(order), newId).single()
        assertEquals(100.0, line.amount)
        assertEquals("2400", line.account)
        assertEquals(20.0, line.taxRate)
        assertEquals("PO-0007", line.sourcePo)
    }

    @Test
    fun `an order's line brings its layers, tags and untouched extras`() {
        val carried = com.zillit.desktop.feature.invoices.domain.CarriedFields(
            customFieldsJson = """[{"name":"Set","value":"A"}]""",
            rentalStartJson = "1789257600000",
        )
        val order = LinkedPoDetail(
            poId = "po1",
            lines = listOf(
                PoLine(
                    description = "Lamps",
                    total = 100.0,
                    trackingCodes = mapOf("set" to "LOC"),
                    tags = listOf("prep"),
                    carried = carried,
                ),
            ),
        )
        val line = EntryCoding.poLines(listOf(order), newId).single()
        assertEquals(mapOf("set" to "LOC"), line.trackingCodes)
        assertEquals(listOf("prep"), line.tags)
        assertEquals(carried, line.carried)
    }

    // -- splits ------------------------------------------------------------------

    @Test
    fun `the first split halves the parent`() {
        val (lines, selected) = EntryCoding.split(listOf(CodedLine("p", amount = 100.0)), "p", newId)
        assertEquals(listOf("p", "n1", "n2"), lines.map { it.id })
        assertEquals(listOf(100.0, 50.0, 50.0), lines.map { it.amount })
        assertEquals("n1", selected)
        assertTrue(lines.drop(1).all { it.splitParentId == "p" })
    }

    /** The new child goes after the selected one; the last existing child takes the rounding. */
    @Test
    fun `a further split adds a sibling and evens them out`() {
        val (two, _) = EntryCoding.split(listOf(CodedLine("p", amount = 100.0)), "p", newId)
        val (three, selected) = EntryCoding.split(two, "n1", newId)
        assertEquals(listOf("p", "n1", "n3", "n2"), three.map { it.id })
        assertEquals(listOf(33.33, 33.33, 33.34), three.filter { it.isSplit }.map { it.amount })
        assertEquals("n3", selected)
    }

    @Test
    fun `pennies round half up, as the web's Math-round does`() {
        assertEquals(0.13, EntryCoding.round2(0.125))
        assertEquals(2.68, EntryCoding.round2(2.675000001))
    }

    @Test
    fun `a child's new amount leaves the rest to its siblings`() {
        val (lines, _) = EntryCoding.split(listOf(CodedLine("p", amount = 100.0)), "p", newId)
        val next = EntryCoding.redistribute(lines, "n1", 70.0)
        assertEquals(listOf(70.0, 30.0), next.filter { it.isSplit }.map { it.amount })
    }

    @Test
    fun `a parent's new amount re-cuts its children in the same shares, and its tax passes down`() {
        val (lines, _) = EntryCoding.split(listOf(CodedLine("p", amount = 100.0)), "p", newId)
        val skewed = EntryCoding.redistribute(lines, "n1", 75.0)
        val grown = EntryCoding.update(skewed, "p") { it.withAmount(200.0).copy(taxRate = 20.0, taxType = "vat") }
        assertEquals(listOf(150.0, 50.0), grown.filter { it.isSplit }.map { it.amount })
        assertTrue(grown.filter { it.isSplit }.all { it.taxRate == 20.0 && it.taxType == "vat" })
    }

    @Test
    fun `removing keeps the last parent, takes a parent's children, and folds a pair back`() {
        val one = listOf(CodedLine("p", amount = 10.0))
        assertEquals(one, EntryCoding.remove(one, "p"))

        val (split, _) = EntryCoding.split(listOf(CodedLine("p", amount = 10.0), CodedLine("q")), "p", newId)
        assertEquals(listOf("q"), EntryCoding.remove(split, "p").map { it.id })
        // One of two children gone: the split folds back into its parent.
        assertEquals(listOf("p", "q"), EntryCoding.remove(split, "n1").map { it.id })
    }

    // -- totals and gates ------------------------------------------------------------

    @Test
    fun `totals count parents only, and the tax line's override replaces the reclaimable part`() {
        val types = listOf(TaxType("vat", "VAT", rate = 20.0, isRecoverable = true))
        val (lines, _) = EntryCoding.split(
            listOf(CodedLine("p", amount = 100.0, taxRate = 20.0, taxType = "vat")),
            "p",
            newId,
        )
        val plain = EntryCoding.totals(lines, TaxLine(), types)
        assertEquals(100.0, plain.net)
        assertEquals(20.0, plain.tax, 0.001)
        assertEquals(120.0, plain.gross, 0.001)

        val overridden = EntryCoding.totals(lines, TaxLine(amount = 19.5, overridden = true), types)
        assertEquals(19.5, overridden.tax, 0.001)
        assertEquals(20.0, EntryCoding.reclaimableTax(lines, types), 0.001)
    }

    @Test
    fun `the coded gross must match to the penny, and an invoice with no gross never blocks`() {
        assertFalse(EntryCoding.amountMismatch(120.004, 120.0))
        assertTrue(EntryCoding.amountMismatch(119.98, 120.0))
        assertFalse(EntryCoding.amountMismatch(5.0, 0.0))
    }

    @Test
    fun `post is refused in the web's order - bank, lock, match, date, nominals`() {
        val lines = listOf(CodedLine("a", description = "Lamps", amount = 100.0), CodedLine("b"))
        val header = EntryHeader(bankId = "b1", effectiveDate = "2026-09-01")
        fun block(h: EntryHeader = header, l: List<CodedLine> = lines, gross: Double = 100.0, locked: Boolean = false) =
            EntryCoding.postBlock(h, l, TaxLine(), emptyList(), true, gross, locked)

        assertEquals(EntryBlock.NoBank, block(h = header.copy(bankId = ""), locked = true))
        assertEquals(EntryBlock.Locked, block(locked = true, gross = 5.0))
        assertEquals(EntryBlock.Mismatch, block(gross = 150.0, h = header.copy(effectiveDate = "")))
        assertEquals(EntryBlock.NoEffectiveDate, block(h = header.copy(effectiveDate = "")))
        // Line 2 is a blank placeholder and never counts.
        assertEquals(EntryBlock.MissingNominal(listOf(1)), block())
        assertNull(block(l = listOf(lines.first().copy(account = "2400"), lines.last())))
    }

    /**
     * The web appends `{ id: "__tax", account }` to the nominal check, but its
     * own `isBlankLine` skips a row with no description and no amount — so a
     * sent tax line never blocks on its nominal (`EntryDetailModal.jsx:960-962`).
     */
    @Test
    fun `a sent tax line without a nominal does not block, as on the web`() {
        val lines = listOf(CodedLine("a", description = "Lamps", account = "2400", amount = 100.0))
        val block = EntryCoding.postBlock(
            EntryHeader(bankId = "b", effectiveDate = "2026-09-01"),
            lines,
            TaxLine(amount = 0.0, overridden = true),
            emptyList(),
            true,
            100.0,
            false,
        )
        assertNull(block)
    }

    @Test
    fun `a described parent line with no amount blocks the post, after the nominals`() {
        val header = EntryHeader(bankId = "b", effectiveDate = "2026-09-01")
        fun block(lines: List<CodedLine>) =
            EntryCoding.postBlock(header, lines, TaxLine(), emptyList(), true, 0.0, false)

        val priced = CodedLine("a", description = "Lamps", account = "2400", amount = 100.0)
        val unpriced = CodedLine("b", description = "Cables", account = "2400")
        assertEquals(EntryBlock.MissingAmount(listOf(2)), block(listOf(priced, unpriced)))
        // A nominal is asked for first.
        assertEquals(EntryBlock.MissingNominal(listOf(2)), block(listOf(priced, unpriced.copy(account = ""))))
        // Negatives are real money; blank placeholders and split children never count.
        assertNull(block(listOf(priced, unpriced.withAmount(-5.0))))
        assertNull(block(listOf(priced, CodedLine("blank"))))
        val child = CodedLine("c", description = "Lamps", account = "2400", splitParentId = "a")
        assertNull(block(listOf(priced, child)))
        assertEquals(listOf(2, 3), EntryCoding.missingAmountRows(listOf(priced, unpriced, unpriced.copy(id = "d"))))
    }

    // -- the currency-change mode ------------------------------------------------------

    @Test
    fun `a picked currency other than the stored one is a change, blank and case never are`() {
        assertTrue(EntryCoding.isCurrencyChanged("USD", "GBP", "GBP"))
        assertFalse(EntryCoding.isCurrencyChanged("", "GBP", "GBP"))
        assertFalse(EntryCoding.isCurrencyChanged("gbp", "GBP", "EUR"))
        // Nothing stored: the project's default is what it was entered in.
        assertFalse(EntryCoding.isCurrencyChanged("GBP", "", "GBP"))
        assertTrue(EntryCoding.isCurrencyChanged("EUR", "", "GBP"))
    }

    @Test
    fun `once the currency changed the old gross is not matched against`() {
        val lines = listOf(CodedLine("a", description = "Lamps", account = "2400", amount = 90.0))
        val header = EntryHeader(bankId = "b", effectiveDate = "2026-09-01", currency = "USD")
        fun block(changed: Boolean) =
            EntryCoding.postBlock(header, lines, TaxLine(), emptyList(), true, 100.0, false, currencyChanged = changed)
        assertEquals(EntryBlock.Mismatch, block(changed = false))
        assertNull(block(changed = true))
    }

    // -- tax, cascade and auto-fill ------------------------------------------------------

    @Test
    fun `switching a preset line to Other drops its rate on the ledger, keeps it in Credits and Sales`() {
        val types = listOf(TaxType("vat", "VAT", rate = 20.0))
        val vat = CodedLine("a", amount = 100.0, taxType = "vat", taxRate = 20.0)
        assertNull(EntryCoding.pickTax(vat, "other", types, keepRateOnOther = false).taxRate)
        assertEquals("other", EntryCoding.pickTax(vat, "other", types, keepRateOnOther = false).taxType)
        assertEquals(20.0, EntryCoding.pickTax(vat, "other", types, keepRateOnOther = true).taxRate)
        // A line already on Other keeps its typed rate.
        val custom = vat.copy(taxType = "other", taxRate = 7.5)
        assertEquals(7.5, EntryCoding.pickTax(custom, "other", types, keepRateOnOther = false).taxRate)
        // A preset brings its own rate; none clears both.
        assertEquals(20.0, EntryCoding.pickTax(custom, "vat", types, keepRateOnOther = false).taxRate)
        assertEquals(CodedLine("a", amount = 100.0), EntryCoding.pickTax(vat, "", types, keepRateOnOther = false))
    }

    @Test
    fun `the Credits and Sales editor passes a parent's coding to children still holding the old value`() {
        val parent = CodedLine(
            "p",
            amount = 100.0,
            account = "2400",
            trackingCodes = mapOf("set" to "LOC"),
            tags = listOf("prep"),
            taxType = "vat",
            taxRate = 20.0,
        )
        val (split, _) = EntryCoding.split(listOf(parent), "p", newId)
        // The second child is recoded on its own; the first still mirrors the parent.
        val customised = split.map {
            if (it.id == "n2") it.copy(account = "9999", tags = listOf("own"), trackingCodes = emptyMap()) else it
        }
        val next = EntryCoding.update(customised, "p", cascadeCoding = true) {
            it.copy(
                account = "2500",
                trackingCodes = mapOf("set" to "STU"),
                tags = listOf("wrap"),
                taxType = "zero",
                taxRate = 0.0,
            )
        }
        val first = next.single { it.id == "n1" }
        assertEquals("2500", first.account)
        assertEquals(mapOf("set" to "STU"), first.trackingCodes)
        assertEquals(listOf("wrap"), first.tags)
        assertEquals("zero", first.taxType)
        val second = next.single { it.id == "n2" }
        assertEquals("9999", second.account, "a child coded on its own keeps its nominal")
        assertEquals(listOf("own"), second.tags)
        assertEquals(mapOf("set" to "STU"), second.trackingCodes, "an empty value is filled")

        // The ledger passes the tax only.
        val ledger = EntryCoding.update(split, "p") { it.copy(account = "2500", taxRate = 5.0) }
        assertTrue(ledger.filter { it.isSplit }.all { it.account == "2400" && it.taxRate == 5.0 })
    }

    @Test
    fun `children inherit their parent's layers and tags when cut`() {
        val parent = CodedLine("p", amount = 10.0, trackingCodes = mapOf("set" to "LOC"), tags = listOf("prep"))
        val (split, _) = EntryCoding.split(listOf(parent), "p", newId)
        assertTrue(split.filter { it.isSplit }.all { it.trackingCodes == parent.trackingCodes && it.tags == parent.tags })
    }

    @Test
    fun `a lone bank is picked and the company follows its entity, else the only company`() {
        val banks = listOf(com.zillit.desktop.feature.invoices.domain.BankAccount("b1", "Main", entityId = "co2"))
        val companies = listOf(
            com.zillit.desktop.feature.invoices.domain.Company("co1", "One"),
            com.zillit.desktop.feature.invoices.domain.Company("co2", "Two"),
        )
        val filled = EntryCoding.autoFill(EntryHeader(), banks, companies)
        assertEquals("b1", filled.bankId)
        assertEquals("co2", filled.companyId)
        // A pick is never overwritten.
        assertEquals("co1", EntryCoding.autoFill(EntryHeader(companyId = "co1"), banks, companies).companyId)
        // A bank with no entity: the only company, when there is one.
        val loose = listOf(com.zillit.desktop.feature.invoices.domain.BankAccount("b1", "Main"))
        assertEquals("co1", EntryCoding.autoFill(EntryHeader(), loose, companies.take(1)).companyId)
        assertEquals("", EntryCoding.autoFill(EntryHeader(), loose, companies).companyId)
    }

    @Test
    fun `a nominal the chart has not got goes as a new code, case aside`() {
        val chart = setOf("2400", "B-100")
        assertEquals("2400", EntryCoding.wrapNominal(" 2400 ", chart))
        assertEquals("b-100", EntryCoding.wrapNominal("b-100", chart))
        assertEquals("[[9999]]", EntryCoding.wrapNominal("9999", chart))
        assertEquals("9999", EntryCoding.wrapNominal("9999", emptySet()), "with no chart nothing is wrapped")
    }

    // -- the Credits / Sales editor --------------------------------------------------

    @Test
    fun `a picked child splits its parent again, and nothing picked splits nothing`() {
        var draft = com.zillit.desktop.feature.invoices.domain.LineDraft(listOf(CodedLine("p", amount = 90.0)))
        assertFalse(draft.canSplit)
        draft = com.zillit.desktop.feature.invoices.domain.LineItems.apply(
            draft,
            com.zillit.desktop.feature.invoices.domain.LineEdit.Select("p"),
            newId,
        )
        draft = com.zillit.desktop.feature.invoices.domain.LineItems.apply(
            draft,
            com.zillit.desktop.feature.invoices.domain.LineEdit.Split,
            newId,
        )
        // The first child is picked after a split — and it can split again.
        assertEquals("n1", draft.selectedId)
        assertTrue(draft.canSplit)
        draft = com.zillit.desktop.feature.invoices.domain.LineItems.apply(
            draft,
            com.zillit.desktop.feature.invoices.domain.LineEdit.Split,
            newId,
        )
        assertEquals(listOf(30.0, 30.0, 30.0), draft.lines.filter { it.isSplit }.map { it.amount })
        assertTrue(draft.lines.filter { it.isSplit }.all { it.splitParentId == "p" })
    }

    @Test
    fun `picking the picked line again lets it go`() {
        val select = com.zillit.desktop.feature.invoices.domain.LineEdit.Select("p")
        val draft = com.zillit.desktop.feature.invoices.domain.LineDraft(listOf(CodedLine("p")), selectedId = "p")
        assertNull(com.zillit.desktop.feature.invoices.domain.LineItems.apply(draft, select, newId).selectedId)
    }

    // -- the close boundary ---------------------------------------------------------

    @Test
    fun `a document on or before the locked day is locked, in the production's zone`() {
        val lock = PeriodLock(lockedThrough = "2026-09-13", timeZone = "Europe/London")
        // 23:30 UTC on the 13th is already the 14th in London (BST).
        assertFalse(lock.isLocked(1_789_342_200_000L))
        assertTrue(lock.isLocked(1_789_300_000_000L))
        assertFalse(lock.isLocked(null as Long?))
        assertTrue(lock.isLocked("2026-09-13"))
        assertFalse(lock.isLocked("2026-09-14"))
        assertFalse(PeriodLock().isLocked(1L))
        assertEquals("2026-09-20", PeriodLock.later(lock, PeriodLock("2026-09-20"))?.lockedThrough)
    }
}
