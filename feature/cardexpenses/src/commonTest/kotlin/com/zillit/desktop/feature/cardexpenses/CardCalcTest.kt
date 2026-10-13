package com.zillit.desktop.feature.cardexpenses

import com.zillit.desktop.feature.cardexpenses.ui.components.CardCalc
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The sum typed into an amount: worked out, signs kept, and a non-sum left alone. */
class CardCalcTest {

    @Test
    fun `an expression is worked out`() {
        assertEquals("120", CardCalc.evaluate("100+20"))
        assertEquals("3600", CardCalc.evaluate("1200*3"))
        assertEquals("1,500".replace(",", ""), CardCalc.evaluate("1,500"))
    }

    @Test
    fun `a credit line keeps its minus`() {
        assertEquals("-50", CardCalc.evaluate("-50"))
        assertEquals("-30", CardCalc.evaluate("20-50"))
        assertTrue(CardCalc.isPlainNumber("-50"))
    }

    @Test
    fun `text that is not a sum does not evaluate`() {
        assertNull(CardCalc.evaluate("12+"))
        assertEquals("", CardCalc.evaluate(""))
    }
}
