package com.zillit.desktop.feature.dealmemo

import com.zillit.desktop.feature.dealmemo.domain.preview.MemoFormat
import com.zillit.desktop.feature.dealmemo.domain.rates.Js
import com.zillit.desktop.feature.dealmemo.ui.DealCoaAccount
import com.zillit.desktop.feature.dealmemo.ui.pages.preview.CoaCodes
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The small readers the builder and the deal page share: JavaScript's view of a JSON value, sort codes, codes. */
class DealValueReadersTest {

    @Test
    fun `Number() reads strings, booleans and null the way JavaScript does`() {
        assertEquals(12.5, Js.toNumber(JsonPrimitive(" 12.5 ")))
        assertEquals(0.0, Js.toNumber(JsonPrimitive("")))
        assertEquals(0.0, Js.toNumber(JsonNull))
        assertEquals(1.0, Js.toNumber(JsonPrimitive(true)))
        assertNull(Js.toNumber(JsonPrimitive("true")), "a string is never read as a boolean")
        assertNull(Js.toNumber(JsonPrimitive("12abc")))
        assertNull(Js.toNumber(null))
    }

    @Test
    fun `unset means absent, null or the empty string, and nothing else`() {
        assertTrue(Js.isNullishOrEmpty(null))
        assertTrue(Js.isNullishOrEmpty(JsonNull))
        assertTrue(Js.isNullishOrEmpty(JsonPrimitive("")))
        assertFalse(Js.isNullishOrEmpty(JsonPrimitive(" ")))
        assertFalse(Js.isNullishOrEmpty(JsonPrimitive(0)))
        assertFalse(Js.isNullishOrEmpty(JsonPrimitive(false)))
    }

    @Test
    fun `sort codes pair their digits as typed, and more than six digits stay verbatim`() {
        assertEquals("20", MemoFormat.sortCode("20"))
        assertEquals("20-4", MemoFormat.sortCode("204"))
        assertEquals("20-48-91", MemoFormat.sortCode("20 48 91"))
        assertEquals("20-48-9", MemoFormat.sortCode("20489"))
        assertEquals("1234-5678", MemoFormat.sortCode("1234-5678"))
        assertEquals("", MemoFormat.sortCode(null))
    }

    @Test
    fun `only posting codes and categories are offered, and an untyped chart offers every posting row`() {
        val chart = listOf(
            DealCoaAccount("1000", "Above the line", lineType = "header"),
            DealCoaAccount("4400", "Per diems", lineType = "category"),
            DealCoaAccount("4410", "Overtime", lineType = "sub_category"),
            DealCoaAccount("4420", "Closed", lineType = "sub_category", posting = false),
            DealCoaAccount("9000", "Untyped"),
        )
        assertEquals(listOf("4400", "4410", "9000"), CoaCodes.pickable(chart).map { it.code })
    }
}
