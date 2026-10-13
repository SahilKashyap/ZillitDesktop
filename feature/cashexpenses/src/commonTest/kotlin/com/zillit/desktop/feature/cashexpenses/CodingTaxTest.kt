package com.zillit.desktop.feature.cashexpenses

import com.zillit.desktop.feature.cashexpenses.domain.BatchStatus
import com.zillit.desktop.feature.cashexpenses.domain.CashReferenceSources
import com.zillit.desktop.feature.cashexpenses.domain.CashTaxType
import com.zillit.desktop.feature.cashexpenses.domain.CashViewer
import com.zillit.desktop.feature.cashexpenses.domain.ClaimLineItem
import com.zillit.desktop.feature.cashexpenses.domain.EditorLine
import com.zillit.desktop.feature.cashexpenses.domain.LineExtras
import com.zillit.desktop.feature.cashexpenses.domain.LineItemEditor
import com.zillit.desktop.feature.cashexpenses.domain.recoverableIds
import com.zillit.desktop.feature.cashexpenses.domain.taxChoice
import com.zillit.desktop.feature.cashexpenses.domain.withTaxChoice
import com.zillit.desktop.feature.cashexpenses.ui.BatchEvent
import com.zillit.desktop.feature.cashexpenses.ui.CashDestination
import com.zillit.desktop.feature.cashexpenses.ui.CashEvent
import com.zillit.desktop.feature.cashexpenses.ui.CashExpensesViewModel
import com.zillit.desktop.feature.cashexpenses.ui.CodingDraft
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The coding editor's tax types and the reclaimable-tax line they derive
 * (`PCPostLedgerPage.jsx` + `LineItemsEditor`): the recoverable tax inside the
 * lines is what the tax line posts, it stands in for that tax in the totals,
 * and a typed amount overrides it until it is reset.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CodingTaxTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private val vat = CashTaxType("STANDARD", "VAT", rate = 20.0, recoverable = true)
    private val exempt = CashTaxType("EXEMPT", "Exempt", rate = 0.0, recoverable = false)
    private val types = listOf(vat, exempt)
    private val recoverable = setOf("STANDARD")

    private fun ids(): () -> String {
        var next = 0
        return { "tax-${next++}" }
    }

    private fun taxed(id: String, net: Double, type: String = "STANDARD", rate: Double = 20.0) =
        EditorLine(id = id, unitPrice = net, taxType = type, taxRatePercent = rate, account = "5010")

    private fun draft(vararg lines: EditorLine, gross: Double = 120.0, overridden: Boolean = false) =
        CodingDraft("b1", "c1", receiptGross = gross, currency = "GBP", lines = lines.toList(), taxTypes = types)
            .copy(taxOverridden = overridden)

    // -- the arithmetic ---------------------------------------------------------------

    @Test
    fun `only the parent lines with a recoverable type feed the reclaimable tax`() {
        val parent = taxed("p", 100.0)
        val child = taxed("c", 50.0).copy(splitParentId = "p")
        val exemptLine = taxed("e", 40.0, type = "EXEMPT", rate = 0.0)
        val auto = taxed("a", -10.0).copy(autoDeduction = true)
        val taxLine = EditorLine(id = "t", unitPrice = 99.0, extras = LineExtras(isTax = true))

        assertEquals(20.0, LineItemEditor.reclaimable(listOf(parent, child, exemptLine, auto, taxLine), recoverable))
        assertEquals(0.0, LineItemEditor.reclaimable(listOf(parent), emptySet()))
    }

    @Test
    fun `a derived tax line changes nothing in the total, a typed one moves it by the difference`() {
        val line = taxed("a", 100.0)
        val derived = EditorLine(id = "t", unitPrice = 20.0, extras = LineExtras(isTax = true))
        val typed = derived.copy(unitPrice = 15.0)

        assertEquals(120.0, LineItemEditor.total(listOf(line, derived), recoverable))
        assertEquals(115.0, LineItemEditor.total(listOf(line, typed), recoverable))
        // No recoverable types: the line adds, as it always did.
        assertEquals(140.0, LineItemEditor.total(listOf(line, derived)))
        // No tax line: the lines stand as they are.
        assertEquals(120.0, LineItemEditor.total(listOf(line), recoverable))
    }

    @Test
    fun `the tax line is created, follows the lines, and goes when nothing is left to reclaim`() {
        val newId = ids()
        val created = LineItemEditor.withDerivedTax(listOf(taxed("a", 100.0)), recoverable, overridden = false, newId)
        val tax = created.single { it.extras.isTax }
        assertEquals(20.0, tax.unitPrice)

        val followed = LineItemEditor.withDerivedTax(
            created.map { if (it.id == "a") it.copy(unitPrice = 50.0) else it },
            recoverable,
            overridden = false,
            newId,
        )
        assertEquals(10.0, followed.single { it.extras.isTax }.unitPrice)

        val gone = LineItemEditor.withDerivedTax(
            followed.map { if (it.id == "a") it.copy(taxType = "EXEMPT", taxRatePercent = 0.0) else it },
            recoverable,
            overridden = false,
            newId,
        )
        assertTrue(gone.none { it.extras.isTax })
    }

    @Test
    fun `a derived tax line that has a nominal stays at nothing rather than losing it`() {
        val line = taxed("a", 100.0, type = "EXEMPT", rate = 0.0)
        val tax = EditorLine(id = "t", unitPrice = 20.0, account = "2200", extras = LineExtras(isTax = true))

        val kept = LineItemEditor.withDerivedTax(listOf(line, tax), recoverable, overridden = false, ids())

        assertEquals(0.0, kept.single { it.extras.isTax }.unitPrice)
        assertEquals("2200", kept.single { it.extras.isTax }.account)
    }

    @Test
    fun `an overridden tax line is left alone`() {
        val lines = listOf(taxed("a", 100.0), EditorLine(id = "t", unitPrice = 5.0, extras = LineExtras(isTax = true)))
        assertEquals(lines, LineItemEditor.withDerivedTax(lines, recoverable, overridden = true, ids()))
    }

    // -- the draft ----------------------------------------------------------------------

    @Test
    fun `a stored tax line opens as an override and a fresh receipt follows its lines`() {
        val stored = draft(
            taxed("a", 100.0),
            EditorLine(id = "t", unitPrice = 15.0, extras = LineExtras(isTax = true)),
        ).opened(ids())
        assertTrue(stored.taxOverridden)
        assertEquals(15.0, stored.lines.single { it.extras.isTax }.unitPrice)

        val fresh = draft(taxed("a", 100.0)).opened(ids())
        assertFalse(fresh.taxOverridden)
        assertEquals(20.0, fresh.lines.single { it.extras.isTax }.unitPrice)
        assertTrue(fresh.balances, "100 net + 20 tax is the 120 receipt, with the tax line standing in for the tax")
    }

    @Test
    fun `typing an amount on the tax line overrides it and a reset gives it back`() {
        val opened = draft(taxed("a", 100.0)).opened(ids())
        val index = opened.lines.indexOfFirst { it.extras.isTax }

        val typed = opened.editLine(index, opened.lines[index].copy(unitPrice = 12.0)).derived(ids())
        assertTrue(typed.taxOverridden)
        assertEquals(12.0, typed.lines[index].unitPrice, "an override survives the next derivation")
        assertFalse(typed.balances)

        val reset = typed.resetTax(ids())
        assertFalse(reset.taxOverridden)
        assertEquals(20.0, reset.lines[index].unitPrice)
    }

    @Test
    fun `a tax line with nothing to reclaim is not sent unless it was typed`() {
        val tax = EditorLine(id = "t", unitPrice = 0.0, account = "2200", extras = LineExtras(isTax = true))
        val lines = arrayOf(taxed("a", 100.0, type = "EXEMPT", rate = 0.0), tax)

        assertEquals(listOf("a"), draft(*lines).savedLines.map { it.id })
        assertEquals(listOf("a", "t"), draft(*lines, overridden = true).savedLines.map { it.id })
    }

    @Test
    fun `a split parent's tax reaches the children that still held its old one`() {
        val parent = taxed("p", 100.0, type = "", rate = 0.0)
        val following = taxed("c1", 50.0, type = "", rate = 0.0).copy(splitParentId = "p")
        val custom = taxed("c2", 50.0, type = "EXEMPT", rate = 0.0).copy(splitParentId = "p")

        val edited = draft(parent, following, custom, gross = 100.0)
            .editLine(0, parent.copy(taxType = "STANDARD", taxRatePercent = 20.0))

        assertEquals("STANDARD", edited.lines[1].taxType)
        assertEquals(20.0, edited.lines[1].taxRatePercent)
        assertEquals("EXEMPT", edited.lines[2].taxType, "a child the coder set by hand keeps it")
    }

    @Test
    fun `recoverable ids are the flagged types only`() {
        assertEquals(setOf("STANDARD"), types.recoverableIds())
        assertEquals("VAT · 20%", vat.optionLabel)
        assertEquals("Exempt · 0%", exempt.optionLabel)
        assertEquals("Custom", CashTaxType("X", "Custom").optionLabel)
    }

    @Test
    fun `the Tax select reads a type, Other for one it lacks, and blank for none`() {
        assertEquals("STANDARD", taxed("a", 100.0).taxChoice(types))
        // A stored type the production no longer has reads as Other, but the line is left as it was.
        val stale = taxed("a", 100.0, type = "OLD_VAT", rate = 17.5)
        assertEquals("other", stale.taxChoice(types))
        assertEquals("OLD_VAT", stale.taxType)
        assertEquals("", EditorLine(id = "n", unitPrice = 5.0).taxChoice(types))
    }

    @Test
    fun `picking a tax type brings its rate, Other keeps the rate typed, none clears both`() {
        val line = EditorLine(id = "a", unitPrice = 100.0)

        val picked = line.withTaxChoice("STANDARD", types)
        assertEquals("STANDARD", picked.taxType)
        assertEquals(20.0, picked.taxRatePercent)
        assertEquals(120.0, picked.gross)

        val other = picked.withTaxChoice("other", types)
        assertEquals("other", other.taxType)
        assertEquals(20.0, other.taxRatePercent)

        val none = other.withTaxChoice("", types)
        assertEquals("", none.taxType)
        assertEquals(0.0, none.taxRatePercent)
    }

    // -- the whole flow -------------------------------------------------------------------

    private val senior = CashViewer(
        userId = "me",
        departmentIdentifier = "department_accounts",
        designationIdentifier = "designation_production_accountant_accounts",
    )

    private fun TestScope.viewModel(repository: FakeCash) = CashExpensesViewModel(
        repository = repository,
        viewer = { senior },
        reference = CashReferenceSources(taxTypes = { types }),
    ).also {
        it.start()
        advanceUntilIdle()
    }

    @Test
    fun `a taxed line derives the tax line, balances, and saves past the post and ledger gate`() =
        runTest(dispatcher) {
            val repository = FakeCash(writesSucceed = true).apply {
                queueRows = listOf(queuedBatch(status = BatchStatus.ReadyToPost, assignedTo = "me"))
            }
            val vm = viewModel(repository)
            vm.onEvent(CashEvent.Open(CashDestination.PostLedger))
            advanceUntilIdle()
            vm.onEvent(CashEvent.SelectBatch("b1"))
            advanceUntilIdle()

            vm.onEvent(BatchEvent.OpenSplit("c1"))
            val seeded = assertNotNull(vm.state.value.coding).lines.single()
            assertEquals(2, vm.state.value.taxTypes.size)
            // £83.33 net at 20% is the £100 receipt; 16.67 of it is reclaimable.
            val taxed = seeded.copy(unitPrice = 83.33, taxType = "STANDARD", taxRatePercent = 20.0)
            vm.onEvent(CashEvent.EditCodingLine(0, taxed))
            val coding = assertNotNull(vm.state.value.coding)
            assertEquals(16.67, coding.lines.single { it.extras.isTax }.unitPrice)
            assertTrue(coding.balances)

            vm.onEvent(BatchEvent.ApplySplit)
            vm.onEvent(BatchEvent.Save("b1"))
            advanceUntilIdle()

            val lines = assertNotNull(repository.lastSavedClaims).single().rawLines
            val taxLine = lines.single { it["is_tax"] == JsonPrimitive(true) }
            assertEquals(16.67, taxLine["total"]?.jsonPrimitive?.content?.toDouble())
            val base = lines.single { it["is_tax"] == null }
            assertEquals("STANDARD", base["tax_type"]?.jsonPrimitive?.content)
            assertEquals(100.0, base["total"]?.jsonPrimitive?.content?.toDouble())
        }

    @Test
    fun `lines saved with the tax line still add up once read back with the tax types`() {
        val lines = listOf(
            ClaimLineItem(
                id = null, account = "5010", description = "Tape", total = 120.0, taxRate = 20.0,
                autoDeduction = false, taxType = "STANDARD",
            ),
            ClaimLineItem(
                id = null, account = "2200", description = "", total = 20.0, taxRate = null,
                autoDeduction = false, isTax = true,
            ),
        )
        val editor = LineItemEditor.fromWire(lines)

        assertEquals(120.0, LineItemEditor.total(editor, recoverable))
        assertEquals(140.0, LineItemEditor.total(editor), "without the tax types the line can only add")
    }
}
