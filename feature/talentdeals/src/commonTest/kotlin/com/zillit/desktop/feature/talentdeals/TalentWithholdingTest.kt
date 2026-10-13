package com.zillit.desktop.feature.talentdeals

import com.zillit.desktop.feature.talentdeals.domain.ExpenseDraft
import com.zillit.desktop.feature.talentdeals.domain.TalentWithholding
import com.zillit.desktop.feature.talentdeals.domain.WithholdingBasis
import com.zillit.desktop.feature.talentdeals.domain.WithholdingType
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Foreign-performer withholding — the arithmetic behind the breakdown box.
 * Two rules carry every number on that panel, and both produce a plausible
 * wrong figure rather than an error: GROSSING UP (an in-kind payment is made
 * NET, so the tax is on the grossed-up value and the production pays it ON TOP)
 * and AUTHORISATION (a pre-authorised rate replaces the contract rate on the
 * fee and every expense). Ports the web's `withholding.test.js`.
 */
class TalentWithholdingTest {

    private var n = 0
    private fun expense(amount: String, withhold: Boolean = true, grossUp: Boolean = false) =
        ExpenseDraft(id = "e${n++}", label = "Flights", amount = amount, withhold = withhold, grossUp = grossUp)

    private fun basis(
        withholding: Boolean = true,
        authorised: Boolean = false,
        authorisedRate: String = "",
        type: WithholdingType = WithholdingType.Percent,
        value: String = "20",
    ) = TalentWithholding.basis(withholding, authorised, authorisedRate, type, value)

    // -- the basis ---------------------------------------------------------------------

    @Test
    fun `no withholding is no basis`() {
        assertEquals(WithholdingBasis.None, basis(withholding = false))
    }

    @Test
    fun `a percentage is a rate and an amount is flat`() {
        assertEquals(WithholdingBasis.Rate(20.0), basis())
        assertEquals(WithholdingBasis.Flat(5000.0), basis(type = WithholdingType.Amount, value = "5000"))
    }

    @Test
    fun `a figure nobody has filled in is no basis at all`() {
        // Otherwise the breakdown shows a confident 0.00 tax on a half-typed form.
        assertEquals(WithholdingBasis.None, basis(value = ""))
        assertEquals(WithholdingBasis.None, basis(value = "0"))
    }

    @Test
    fun `an authorised rate REPLACES the contract rate`() {
        assertEquals(WithholdingBasis.Rate(8.0), basis(value = "20", authorised = true, authorisedRate = "8"))
    }

    @Test
    fun `an authorised rate still blank falls back to none, not the contract rate`() {
        // The certificate is ticked but not yet keyed in: applying the contract's
        // 20% would tax at a rate the production has just said is wrong.
        assertEquals(WithholdingBasis.None, basis(authorised = true, authorisedRate = ""))
    }

    // -- grossing up -------------------------------------------------------------------

    @Test
    fun `grossing up puts the tax on the whole amount`() {
        assertEquals(125.0, TalentWithholding.grossUpAmount(100.0, 20.0))
        assertEquals(100.0, TalentWithholding.grossUpAmount(100.0, 0.0))
    }

    @Test
    fun `a rate of 100 or more is refused rather than divided by zero`() {
        assertEquals(100.0, TalentWithholding.grossUpAmount(100.0, 100.0))
        assertEquals(100.0, TalentWithholding.grossUpAmount(100.0, 140.0))
    }

    // -- a rate ------------------------------------------------------------------------

    private val rate = WithholdingBasis.Rate(20.0)

    @Test
    fun `the fee is taxed at the rate`() {
        val s = TalentWithholding.summary("100000", emptyList(), rate)
        assertEquals(20000.0, s.tax)
        assertEquals(80000.0, s.net)
        assertEquals(100000.0, s.cost)
    }

    @Test
    fun `a straight expense is taxed from what the performer receives`() {
        val s = TalentWithholding.summary("0", listOf(expense("1000")), rate)
        assertEquals(200.0, s.tax)
        // The production pays the 1,000 it agreed; 200 of it goes to the tax authority.
        assertEquals(1000.0, s.cost)
        assertEquals(800.0, s.net)
    }

    @Test
    fun `an in-kind expense is grossed up and the production carries the tax`() {
        val s = TalentWithholding.summary("0", listOf(expense("100", grossUp = true)), rate)
        val flights = s.rows[1]
        assertEquals(125.0, flights.gross)
        assertEquals(25.0, flights.tax)
        // The performer keeps the whole 100 of flights; the production is 125 out of pocket.
        assertEquals(100.0, s.net)
        assertEquals(125.0, s.cost)
    }

    @Test
    fun `a non-taxable line is left out of what the tax is worked out on`() {
        val s = TalentWithholding.summary("1000", listOf(expense("500", withhold = false)), rate)
        assertEquals(1000.0, s.grossTotal)
        assertEquals(200.0, s.tax)
        // Still paid, just not taxed.
        assertEquals(1500.0, s.cost)
        assertEquals(1300.0, s.net)
    }

    @Test
    fun `a line nothing is withheld on cannot be grossed up`() {
        val s = TalentWithholding.summary("0", listOf(expense("100", withhold = false, grossUp = true)), rate)
        assertEquals(100.0, s.rows[1].gross)
        assertEquals(0.0, s.tax)
    }

    @Test
    fun `an unnamed expense reads as the default word`() {
        val s = TalentWithholding.summary("0", listOf(expense("1").copy(label = "")), rate, expenseLabel = "Expense")
        assertEquals("Expense", s.rows[1].label)
    }

    // -- a flat figure -----------------------------------------------------------------

    private val flat = WithholdingBasis.Flat(5000.0)

    @Test
    fun `a flat figure puts the whole withholding on the fee and none on the expenses`() {
        val s = TalentWithholding.summary("50000", listOf(expense("1000")), flat)
        assertEquals(5000.0, s.rows[0].tax)
        assertEquals(0.0, s.rows[1].tax)
        assertEquals(5000.0, s.tax)
    }

    @Test
    fun `a flat figure is capped at the fee rather than reporting a negative net`() {
        val s = TalentWithholding.summary("3000", emptyList(), flat)
        assertEquals(3000.0, s.tax)
        assertEquals(0.0, s.net)
    }

    // -- none --------------------------------------------------------------------------

    @Test
    fun `no withholding still totals what is paid, with no tax anywhere`() {
        val s = TalentWithholding.summary("10000", listOf(expense("500")), WithholdingBasis.None)
        assertEquals(0.0, s.tax)
        assertEquals(500.0, s.expenses)
        assertEquals(10500.0, s.cost)
        assertEquals(10500.0, s.net)
        assertEquals(0.0, s.grossTotal)
    }
}
