package com.zillit.desktop.feature.esignature

import com.zillit.desktop.feature.esignature.domain.CsvParse
import com.zillit.desktop.feature.esignature.ui.ParsedCsv
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The bulk-send CSV reader — separators sniffed, quotes honoured, noise dropped. */
class CsvParseTest {

    @Test
    fun `comma, semicolon and tab files all read the same`() {
        listOf(",", ";", "\t").forEach { sep ->
            val parsed = CsvParse.parse("name${sep}email\nJane Doe${sep}jane@x.io\n")
            assertEquals(listOf("name", "email"), parsed.headers, "separator '$sep'")
            assertEquals("jane@x.io", parsed.rows.single()["email"])
        }
    }

    @Test
    fun `quoted cells keep their separators and doubled quotes, and blank rows are dropped`() {
        val parsed = CsvParse.parse("﻿name,email,note\r\n\"Doe, Jane\",jane@x.io,\"She said \"\"hi\"\"\"\r\n,,\r\n\r\n")
        assertEquals(1, parsed.rows.size)
        assertEquals("Doe, Jane", parsed.rows[0]["name"])
        assertEquals("She said \"hi\"", parsed.rows[0]["note"])
    }

    @Test
    fun `the required columns are found by their aliases and rows are judged by email syntax`() {
        val csv = ParsedCsv(listOf("Full Name", "E-mail", "rate"), listOf(
            mapOf("Full Name" to "Jane", "E-mail" to "jane@x.io", "rate" to "1"),
            mapOf("Full Name" to "Bob", "E-mail" to "not-an-email", "rate" to "2"),
            mapOf("Full Name" to "", "E-mail" to "x@y.io", "rate" to "3"),
        ), ',')
        assertTrue(csv.hasRequiredColumns)
        assertEquals(1, csv.validRows().size)
        assertEquals(listOf("rate"), csv.extraColumns)
        assertFalse(ParsedCsv(listOf("who", "where"), emptyList(), ',').hasRequiredColumns)
    }

    @Test
    fun `serialising quotes only what needs it`() {
        val out = CsvParse.serialise(listOf("name", "email"), listOf(mapOf("name" to "Doe, Jane", "email" to "j@x.io")))
        assertEquals("name,email\n\"Doe, Jane\",j@x.io\n", out)
    }
}
