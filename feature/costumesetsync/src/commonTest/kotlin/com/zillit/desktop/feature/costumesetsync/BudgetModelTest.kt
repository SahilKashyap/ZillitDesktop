package com.zillit.desktop.feature.costumesetsync

import com.zillit.desktop.feature.costumesetsync.domain.BudgetForm
import com.zillit.desktop.feature.costumesetsync.domain.BudgetModel
import com.zillit.desktop.feature.costumesetsync.domain.Rec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private fun rec(json: String) = Rec(Json.parseToJsonElement(json).jsonObject)

class BudgetModelTest {
    @Test
    fun `sums are per currency, never across`() {
        val lines = listOf(
            rec("""{"amount":100}"""),
            rec("""{"amount":50,"currency":"INR"}"""),
            rec("""{"amount":20}"""),
        )
        assertEquals("£120 + ₹50", BudgetModel.sumByCurrency(lines, "GBP"))
        assertEquals("£0", BudgetModel.sumByCurrency(emptyList(), "GBP"))
    }

    @Test
    fun `a department title follows the chart`() {
        assertEquals("30" to "30-000 - WARDROBE", BudgetModel.departmentOf("30-001"))
        assertEquals("99" to "99-000", BudgetModel.departmentOf("99-120"))
        assertEquals(null, BudgetModel.departmentOf("  "))
    }

    @Test
    fun `subtotal needs amt and rate and defaults x to one`() {
        assertEquals(null, BudgetModel.subtotalOf(BudgetForm(quantity = "3")))
        assertEquals(30.0, BudgetModel.subtotalOf(BudgetForm(quantity = "3", rate = "10")))
        assertEquals(120.0, BudgetModel.subtotalOf(BudgetForm(quantity = "3", rate = "10", multiplier = "4")))
        assertEquals(45.0, BudgetModel.amountOf(BudgetForm(amount = "45")))
        assertEquals(30.0, BudgetModel.amountOf(BudgetForm(amount = "45", quantity = "3", rate = "10")))
    }

    @Test
    fun `body drops a blank date and blanks the production currency`() {
        val body = BudgetModel.toExpenseBody(BudgetForm(amount = "12", currency = "GBP", date = ""), "GBP")
        assertFalse("date" in body)
        assertEquals("", body.getValue("currency").jsonPrimitive.content)
        assertEquals(JsonNull, body.getValue("character_id"))
        assertEquals(JsonNull, body.getValue("quantity"))
        val inr = BudgetModel.toExpenseBody(BudgetForm(amount = "12", currency = "INR", date = "2026-09-29"), "GBP")
        assertTrue("date" in inr)
        assertEquals("INR", inr.getValue("currency").jsonPrimitive.content)
    }

    @Test
    fun `after an add the account and payee stay`() {
        val next = BudgetModel.formAfterAdd(
            BudgetForm(accountCode = "30-040", payee = "Dry Co", amount = "9", description = "x", category = "LAUNDRY"),
            "GBP",
            "2026-10-01",
        )
        assertEquals("30-040", next.accountCode)
        assertEquals("Dry Co", next.payee)
        assertEquals("", next.amount)
        assertEquals("", next.description)
        assertEquals("LAUNDRY", next.category)
    }

    @Test
    fun `accounts sort by code with uncoded last`() {
        val lines = listOf(
            rec("""{"account_code":"30-090","account_name":"B"}"""),
            rec("""{"category":"OTHER"}"""),
            rec("""{"account_code":"30-040","account_name":"A"}"""),
            rec("""{"account_code":"30-040"}"""),
        )
        val blocks = BudgetModel.accountsOf(lines)
        assertEquals(listOf("30-040", "30-090", ""), blocks.map { it.code })
        assertEquals(2, blocks[0].lines.size)
    }

    @Test
    fun `department groups run in chart order with uncoded last`() {
        val groups = BudgetModel.departmentGroups(
            listOf(
                rec("""{"account_code":"110-1"}"""),
                rec("""{"account_code":"30-1"}"""),
                rec("{}"),
                rec("""{"account_code":"12-3"}"""),
            ),
            "No code",
        )
        assertEquals(listOf("12", "30", "110", BudgetModel.NONE), groups.map { it.key })
        assertEquals("No code", groups.last().title)
    }

    @Test
    fun `top sheet reads sections then the rest then the grand total`() {
        val text = BudgetModel.topSheetText(
            "Film", listOf(rec("""{"account_code":"30-001","amount":100}"""), rec("""{"category":"OTHER",
            "amount":5}""")),
            "GBP", { "SECTION $it" }, "Grand total", "Budget",
        )
        val lines = text.lines()
        assertEquals("FILM · BUDGET", lines[0])
        assertEquals("30-000 WARDROBE  £100", lines[2])
        assertEquals("SECTION production  £100", lines[3])
        assertEquals("OTHER  £5", lines[5])
        assertEquals("GRAND TOTAL  £105", lines.last())
    }

    @Test
    fun `numbers group and trim`() {
        assertEquals("1,234.5", BudgetModel.fmtNum(1234.5))
        assertEquals("6", BudgetModel.fmtNum(6.0))
        assertEquals("", BudgetModel.fmtNum(null))
        assertEquals("1,200", BudgetModel.fmtNum(1200.0))
    }
}
