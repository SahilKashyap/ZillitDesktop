package com.zillit.desktop.feature.purchaseorder

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.purchaseorder.domain.OTHER_TAX_TYPE
import com.zillit.desktop.feature.purchaseorder.domain.PoEntryHeader
import com.zillit.desktop.feature.purchaseorder.domain.PoLine
import com.zillit.desktop.feature.purchaseorder.domain.PoStatus
import com.zillit.desktop.feature.purchaseorder.domain.PoTaxType
import com.zillit.desktop.feature.purchaseorder.domain.PoViewer
import com.zillit.desktop.feature.purchaseorder.domain.PoProjectSettings
import com.zillit.desktop.feature.purchaseorder.domain.PoSplitType
import com.zillit.desktop.feature.purchaseorder.domain.PurchaseOrder
import com.zillit.desktop.feature.purchaseorder.domain.PurchaseOrderRepository
import com.zillit.desktop.feature.purchaseorder.domain.isoDayToUtcMidnight
import com.zillit.desktop.feature.purchaseorder.domain.wrapNominal
import com.zillit.desktop.feature.purchaseorder.ui.PoDestination
import com.zillit.desktop.feature.purchaseorder.ui.PoEntryLedger
import com.zillit.desktop.feature.purchaseorder.ui.PoEntryState
import com.zillit.desktop.feature.purchaseorder.ui.PoEvent
import com.zillit.desktop.feature.purchaseorder.ui.PurchaseOrderViewModel
import com.zillit.desktop.feature.purchaseorder.ui.clampTaxRate
import com.zillit.desktop.feature.purchaseorder.ui.collapseUnknownTaxTypes
import com.zillit.desktop.feature.purchaseorder.ui.splitByPeriod
import com.zillit.desktop.feature.purchaseorder.ui.withExpenditureType
import com.zillit.desktop.feature.purchaseorder.ui.withRental
import com.zillit.desktop.feature.purchaseorder.ui.withTaxRate
import com.zillit.desktop.feature.purchaseorder.ui.withTaxType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The PO Entry grid's editors — Exp. Type with its rental window, Account Tags,
 * the "Other" tax type with a typed rate — and the `[[code]]` wrap of a nominal
 * the chart does not know.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PoEntryGridTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    @Suppress("LongParameterList") // A fixture: every field a test varies, named at the call.
    private fun line(
        id: String? = "p",
        total: Double = 100.0,
        code: String? = "2400",
        splitParentId: String? = null,
        expenditureType: String? = null,
        taxType: String? = null,
        vatRate: Double? = null,
        rentalStart: String? = null,
        rentalEnd: String? = null,
    ) = PoLine(
        id = id, description = "Hire", quantity = 1.0, unitPrice = total, nominalCode = code, vatRate = vatRate,
        amount = total, expenditureType = expenditureType, taxType = taxType, splitParentId = splitParentId,
        rentalStart = rentalStart, rentalEnd = rentalEnd,
    )

    // -- Exp. Type and the rental window -------------------------------------------

    @Test
    fun `choosing Rent opens the window and a processed rental can then split by period`() {
        val lines = listOf(line()).withExpenditureType(0, "Rent")
        assertEquals("Rent", lines[0].expenditureType)
        assertTrue(!lines[0].isDivisibleRental, "no dates yet")

        val dated = lines.withRental(0, "2026-01-01", "2026-03-31")
        assertTrue(dated[0].isDivisibleRental)
        val monthly = dated.splitByPeriod(0, PoSplitType.Monthly)
        assertEquals(4, monthly!!.size, "the parent and its three monthly children")
    }

    /** The web's select clears the window on any other type, so a stale hire date cannot ride a Purchase. */
    @Test
    fun `any other type drops the rental window`() {
        val rental = listOf(line(expenditureType = "Rent", rentalStart = "2026-01-01", rentalEnd = "2026-02-01"))
        val purchase = rental.withExpenditureType(0, "Purchase")
        assertNull(purchase[0].rentalStart)
        assertNull(purchase[0].rentalEnd)
        assertNull(rental.withExpenditureType(0, null)[0].expenditureType)
    }

    @Test
    fun `the type follows the split children, and a child cannot change its own`() {
        val lines = listOf(line(id = "p"), line(id = "c1", splitParentId = "p"), line(id = "c2", splitParentId = "p"))
        val consumables = lines.withExpenditureType(0, "Consumption")
        assertEquals(listOf("Consumption", "Consumption", "Consumption"), consumables.map { it.expenditureType })

        assertEquals(lines, lines.withExpenditureType(1, "Rent"), "locked on a child, as the web's select is")
        assertEquals(lines, lines.withRental(1, "2026-01-01", "2026-02-01"))
    }

    // -- tax: "Other" and its rate --------------------------------------------------

    private val vat = PoTaxType("vat", "VAT", 20.0)
    private val none = PoTaxType("zero", "Zero rated", null)

    @Test
    fun `a Production Setup type brings its rate and the children inherit both`() {
        val lines = listOf(line(id = "p"), line(id = "c", splitParentId = "p"))
        val taxed = lines.withTaxType(0, "vat", vat)
        assertEquals(listOf("vat", "vat"), taxed.map { it.taxType })
        assertEquals(listOf(20.0, 20.0), taxed.map { it.vatRate })
        assertNull(lines.withTaxType(0, "zero", none)[0].vatRate, "a type with no rate has none")
    }

    /** The web's ZL-20656: Custom right after a preset must not arrive pre-filled with the preset's 5%. */
    @Test
    fun `Other starts empty from a preset but keeps a rate already typed`() {
        val preset = listOf(line(taxType = "vat", vatRate = 5.0))
        assertNull(preset.withTaxType(0, OTHER_TAX_TYPE, null)[0].vatRate)

        val typed = listOf(line(taxType = OTHER_TAX_TYPE, vatRate = 7.5))
        assertEquals(7.5, typed.withTaxType(0, OTHER_TAX_TYPE, null)[0].vatRate)

        assertNull(typed.withTaxType(0, null, null)[0].taxType, "none clears the type")
        assertNull(typed.withTaxType(0, null, null)[0].vatRate)
    }

    @Test
    fun `a typed rate reaches the children and a child's own tax is locked`() {
        val lines = listOf(
            line(id = "p", taxType = OTHER_TAX_TYPE),
            line(id = "c", splitParentId = "p", taxType = OTHER_TAX_TYPE),
        )
        assertEquals(listOf(12.5, 12.5), lines.withTaxRate(0, 12.5).map { it.vatRate })
        assertEquals(lines, lines.withTaxRate(1, 12.5))
        assertEquals(lines, lines.withTaxType(1, "vat", vat))
    }

    @Test
    fun `a percent is clamped to 0 to 100 and blank is none`() {
        assertEquals(100.0, clampTaxRate("150"))
        assertEquals(0.0, clampTaxRate("-3"))
        assertEquals(17.5, clampTaxRate(" 17.5 "))
        assertNull(clampTaxRate(""))
        assertNull(clampTaxRate("abc"))
    }

    /** The web's `resolveLineTaxType`: a type since removed from Production Setup shows as Other over its rate. */
    @Test
    fun `a type the production no longer lists collapses to Other and keeps its rate`() {
        val lines = listOf(
            line(taxType = "vat", vatRate = 20.0),
            line(taxType = "retired", vatRate = 5.0),
            line(taxType = OTHER_TAX_TYPE, vatRate = 3.0),
            line(taxType = null),
        )
        val resolved = lines.collapseUnknownTaxTypes(setOf("vat"))
        assertEquals(listOf("vat", OTHER_TAX_TYPE, OTHER_TAX_TYPE, null), resolved.map { it.taxType })
        assertEquals(5.0, resolved[1].vatRate)
    }

    // -- wrapNominal ---------------------------------------------------------------

    @Test
    fun `a code the chart does not know is wrapped, a known one is not`() {
        val chart = setOf("2400", "B-100")
        assertEquals("2400", wrapNominal(" 2400 ", chart))
        assertEquals("b-100", wrapNominal("b-100", chart), "a case-only difference is the same code")
        assertEquals("[[9999]]", wrapNominal("9999", chart))
        assertEquals("9999", wrapNominal("9999", emptySet()), "no chart read yet: never wrap")
        assertEquals("", wrapNominal("  ", chart))
    }

    private val header = PoEntryHeader("v-1", null, "dept", "9999", "GBP", null)

    @Test
    fun `save wraps the header and every line, the tax line included`() {
        val entry = PoEntryState(
            orderId = "po-1",
            lines = listOf(line(code = "9999"), line(id = "q", code = "2400")),
            taxOverride = 5.0,
            taxCode = "8888",
        )
        val update = PoEntryLedger(entry, recoverable = emptySet(), ready = true)
            .update(header, chart = setOf("2400"))

        assertEquals("[[9999]]", update.header.nominalCode)
        assertEquals(listOf("[[9999]]", "2400", "[[8888]]"), update.lines.map { it.nominalCode })
    }

    /** The web's `handlePostPO` maps the lines' `account` as typed; only `hdrPatch` is wrapped. */
    @Test
    fun `post wraps the header code only, as the web does`() {
        val entry = PoEntryState(orderId = "po-1", lines = listOf(line(code = "9999")))
        val post = PoEntryLedger(entry, recoverable = emptySet(), ready = true)
            .post(parityOrder(), header, chart = setOf("2400"))

        assertEquals("[[9999]]", post.header.nominalCode)
        assertEquals("9999", post.lines.single().nominalCode)
    }

    // -- through the view model ----------------------------------------------------

    private class Fake(val inner: FlowFake, private val chart: Set<String>) :
        PurchaseOrderRepository by inner {
        override suspend fun chartCodes() = ZillitResult.Success(chart)
    }

    private val senior = PoViewer("acct-1", "department_accounts", "designation_production_accountant_accounts")

    private fun TestScope.processing(order: PurchaseOrder, chart: Set<String>): Pair<PurchaseOrderViewModel, FlowFake> {
        val inner = FlowFake(listOf(order))
        val model = PurchaseOrderViewModel(Fake(inner, chart), { senior })
        model.start()
        runCurrent()
        model.onEvent(PoEvent.Open(PoDestination.AllPos))
        runCurrent()
        model.onEvent(PoEvent.ProcessOrder(order.id))
        runCurrent()
        return model to inner
    }

    @Test
    fun `saving the page sends the unknown code wrapped and posting sends only the header wrapped`() =
        runTest(dispatcher) {
            val order = parityOrder(
                status = PoStatus.AccountsEntered,
                gross = 100.0,
                lines = listOf(PoLine("l1", "Camera body", 1.0, 100.0, nominalCode = "9999", vatRate = null)),
                effectiveDate = "2026-09-10".isoDayToUtcMidnight(),
            ).copy(nominalCode = "9999")
            val (model, repository) = processing(order, chart = setOf("2400"))

            model.onEvent(PoEvent.SaveEntry)
            runCurrent()
            val saved = repository.saved.single()
            assertEquals("[[9999]]", saved.header.nominalCode)
            assertEquals("[[9999]]", saved.lines.single().nominalCode)

            // What the page shows is the record read back, clean: nothing wrapped is stored on the line.
            assertEquals("9999", model.state.value.entry?.lines?.single()?.nominalCode)

            model.onEvent(PoEvent.PostEntry)
            runCurrent()
            val post = repository.posted.single()
            assertEquals("[[9999]]", post.header.nominalCode)
            assertEquals("9999", post.lines.single().nominalCode)
        }

    @Test
    fun `opening a processed order collapses a retired tax type to Other`() = runTest(dispatcher) {
        val order = parityOrder(
            status = PoStatus.AccountsEntered,
            gross = 110.0,
            lines = listOf(
                PoLine("l1", "Body", 1.0, 100.0, "2400", 10.0, taxType = "retired"),
            ),
        )
        val inner = FlowFake(listOf(order))
        val tax = object : PoProjectSettings {
            override suspend fun taxTypes() = listOf(PoTaxType("vat", "VAT", 20.0))
        }
        val model = PurchaseOrderViewModel(Fake(inner, emptySet()), { senior }, projectSettings = tax)
        model.start()
        runCurrent()
        model.onEvent(PoEvent.Open(PoDestination.AllPos))
        runCurrent()
        model.onEvent(PoEvent.ProcessOrder(order.id))
        runCurrent()

        val line = model.state.value.entry!!.lines.single()
        assertEquals(OTHER_TAX_TYPE, line.taxType)
        assertEquals(10.0, line.vatRate)
    }
}
