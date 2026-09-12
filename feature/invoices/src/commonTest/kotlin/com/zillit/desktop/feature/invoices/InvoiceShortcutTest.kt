package com.zillit.desktop.feature.invoices

import com.zillit.desktop.feature.invoices.ui.InvoiceShortcut
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The keys the sidebar's footer advertises — which is only honest if they map to something. */
class InvoiceShortcutTest {

    @Test
    fun `the footer's three keys are the web's`() {
        assertEquals(listOf("/", "N", "?"), InvoiceShortcut.entries.map { it.key })
        assertEquals(listOf("Search", "New", "Help"), InvoiceShortcut.entries.map { it.label })
    }

    /** `?` is shift and the same physical key as `/`, so the modifier separates them. */
    @Test
    fun `shift turns search into help`() {
        assertEquals(InvoiceShortcut.Search, InvoiceShortcut.of("/", shift = false))
        assertEquals(InvoiceShortcut.Help, InvoiceShortcut.of("/", shift = true))
    }

    @Test
    fun `new is either case, and nothing else is a shortcut`() {
        assertEquals(InvoiceShortcut.New, InvoiceShortcut.of("n", shift = false))
        assertEquals(InvoiceShortcut.New, InvoiceShortcut.of("N", shift = true))
        assertNull(InvoiceShortcut.of("k", shift = false))
        assertNull(InvoiceShortcut.of("", shift = false))
    }
}
