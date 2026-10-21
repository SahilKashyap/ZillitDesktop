package com.zillit.desktop.core.common

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What every surface that shows typed text agrees is an address.
 *
 * CNC's bubbles and the chat that runs for the length of a call both read
 * through here, so a change in what gets lit up is a change in both.
 */
class LinkSpanTest {

    @Test
    fun `plain words come back whole, as one span`() {
        assertEquals(listOf(LinkSpan.Words("one minute")), linkSpans("one minute"))
    }

    @Test
    fun `an address keeps its own scheme`() {
        assertEquals(
            listOf(
                LinkSpan.Words("sheet is at "),
                LinkSpan.Link(url = "https://zillit.com/cs/4", text = "https://zillit.com/cs/4"),
            ),
            linkSpans("sheet is at https://zillit.com/cs/4"),
        )
    }

    @Test
    fun `a bare www address is opened over https, reading as typed`() {
        assertEquals(
            listOf(LinkSpan.Link(url = "https://www.zillit.com", text = "www.zillit.com")),
            linkSpans("www.zillit.com"),
        )
    }

    @Test
    fun `an email address opens the mail client`() {
        assertEquals(
            listOf(LinkSpan.Link(url = "mailto:ad@zillit.com", text = "ad@zillit.com")),
            linkSpans("ad@zillit.com"),
        )
    }

    @Test
    fun `the full stop that ends the sentence is not part of the address`() {
        assertEquals(
            listOf(
                LinkSpan.Link(url = "https://zillit.com/a", text = "https://zillit.com/a"),
                LinkSpan.Words("."),
            ),
            linkSpans("https://zillit.com/a."),
        )
    }

    @Test
    fun `two addresses in one line are both found, with the words between them kept`() {
        assertEquals(
            listOf(
                LinkSpan.Link(url = "https://a.com", text = "https://a.com"),
                LinkSpan.Words(" and "),
                LinkSpan.Link(url = "https://b.com", text = "https://b.com"),
            ),
            linkSpans("https://a.com and https://b.com"),
        )
    }
}
