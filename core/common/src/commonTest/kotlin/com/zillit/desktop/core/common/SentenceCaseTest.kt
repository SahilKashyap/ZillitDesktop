package com.zillit.desktop.core.common

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The capital the composers add as you type.
 *
 * Two halves to hold: the letters that *should* come up (first word, after a
 * full stop, after a line break), and the far longer list of things that must
 * be returned untouched — because this runs on every keystroke in every
 * composer, and a helper that rewrites a draft nobody asked it to rewrite is
 * worse than no capital at all.
 */
class SentenceCaseTest {

    private fun typed(before: String, after: String) = SentenceCase.applied(before, after)

    @Test
    fun `the first letter of an empty field comes up`() {
        assertEquals("C", typed("", "c"))
    }

    @Test
    fun `the rest of the first word is left as typed`() {
        assertEquals("Copy", typed("Cop", "Copy"))
    }

    @Test
    fun `a letter after a full stop and a space comes up`() {
        assertEquals("Copy that. W", typed("Copy that. ", "Copy that. w"))
    }

    @Test
    fun `a letter after a question mark or a bang comes up`() {
        assertEquals("Ready? G", typed("Ready? ", "Ready? g"))
        assertEquals("Go! N", typed("Go! ", "Go! n"))
    }

    @Test
    fun `a letter on a new line comes up whatever ended the line before`() {
        assertEquals("Sam\nD", typed("Sam\n", "Sam\nd"))
    }

    @Test
    fun `a letter after two spaces still comes up`() {
        assertEquals("Done.  N", typed("Done.  ", "Done.  n"))
    }

    @Test
    fun `an opening quote is looked past, not treated as the sentence`() {
        assertEquals("\"C", typed("\"", "\"c"))
        assertEquals("Done. \"C", typed("Done. \"", "Done. \"c"))
        // Mid-sentence, though, the quote opens no sentence of its own: `He
        // said "we leave at six"` is the typist's to capitalise or not.
        assertEquals("He said \"w", typed("He said \"", "He said \"w"))
    }

    @Test
    fun `a letter mid-sentence is left alone`() {
        assertEquals("Copy that", typed("Copy tha", "Copy that"))
    }

    @Test
    fun `a dot with no space after it is not the end of a sentence`() {
        // www.example.com, e.g., 2.5 — the ones this would wreck.
        assertEquals("www.e", typed("www.", "www.e"))
        assertEquals("e.g", typed("e.", "e.g"))
    }

    @Test
    fun `a capital the typist chose is untouched`() {
        assertEquals("ETA 10", typed("ETA 1", "ETA 10"))
    }

    @Test
    fun `a deliberate lowercase stays lowercase once anything else is typed`() {
        // The whole reason only the new character is considered: the typist
        // lowercased the first letter on purpose, and the next keystroke must
        // not put it back.
        assertEquals("iphone charger", typed("iphone charge", "iphone charger"))
    }

    @Test
    fun `a deletion is not a keystroke`() {
        assertEquals("copy tha", typed("copy that", "copy tha"))
        assertEquals("", typed("c", ""))
    }

    @Test
    fun `a paste is left as it was pasted`() {
        assertEquals("copy that", typed("", "copy that"))
        assertEquals("Done. copy that", typed("Done. ", "Done. copy that"))
    }

    @Test
    fun `text replaced wholesale is left alone`() {
        // A completed mention, a draft reloaded, a signature inserted: a
        // character longer than what it replaced, but not by a typed one.
        assertEquals("hi dave", typed("hi sam", "hi dave"))
        assertEquals("@sam ", typed("@sa", "@sam "))
    }

    @Test
    fun `a character with no case is left alone`() {
        assertEquals("👍", typed("", "👍"))
        assertEquals("Done. 👍", typed("Done. ", "Done. 👍"))
    }

    @Test
    fun `a letter typed into the middle of a line opens no sentence`() {
        assertEquals("the call sheet", typed("the all sheet", "the call sheet"))
    }

    @Test
    fun `a letter typed at the very start of a draft comes up`() {
        assertEquals("Call sheet is out", typed("all sheet is out", "call sheet is out"))
    }
}
