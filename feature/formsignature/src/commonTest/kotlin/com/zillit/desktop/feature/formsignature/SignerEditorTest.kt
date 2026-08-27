package com.zillit.desktop.feature.formsignature

import com.zillit.desktop.feature.formsignature.domain.DocumentSigner
import com.zillit.desktop.feature.formsignature.domain.SignerOption
import com.zillit.desktop.feature.formsignature.ui.SignerEditorState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Changing who must sign a document that is already out.
 *
 * The service replaces the signer list rather than adding to it, so what the
 * editor holds is the whole list — the trap being that posting only the new
 * names would drop everyone already waiting.
 */
class SignerEditorTest {

    private fun editor(
        chosen: Set<String> = setOf("u1"),
        signed: Set<String> = emptySet(),
        loading: Boolean = false,
        saving: Boolean = false,
    ) = SignerEditorState(
        documentId = "d1",
        title = "Crew deal memo.pdf",
        options = listOf(
            SignerOption(userId = "u1", fullName = "Aisha Khan"),
            SignerOption(userId = "u2", fullName = "Ravi Menon"),
        ),
        chosen = chosen,
        alreadySigned = signed,
        loading = loading,
        saving = saving,
    )

    /** Nobody to sign is not a document anyone is waiting on. */
    @Test
    fun `an empty list cannot be saved`() {
        assertFalse(editor(chosen = emptySet()).canSave)
        assertTrue(editor().canSave)
    }

    /** Not while the options are still arriving, or a save is in flight. */
    @Test
    fun `it will not save mid-flight`() {
        assertFalse(editor(loading = true).canSave)
        assertFalse(editor(saving = true).canSave)
    }

    /**
     * The list posted is the union of what is ticked and what is already
     * signed — someone who has signed stays on the document whatever the
     * checkbox says, because their ink exists.
     */
    @Test
    fun `signed people survive the save`() {
        val state = editor(chosen = setOf("u2"), signed = setOf("u1"))

        val posted = (state.chosen + state.alreadySigned).toList()

        assertTrue("u1" in posted, "a signature cannot be discarded by a checkbox")
        assertTrue("u2" in posted)
    }

    /** Opening pre-ticks everyone already on the document. */
    @Test
    fun `the editor opens with the current signers ticked`() {
        val signers = listOf(
            DocumentSigner(userId = "u1", fullName = "Aisha", signed = true),
            DocumentSigner(userId = "u2", fullName = "Ravi"),
        )

        val chosen = signers.map { it.userId }.toSet()
        val alreadySigned = signers.filter { it.signed }.map { it.userId }.toSet()

        assertEquals(setOf("u1", "u2"), chosen)
        assertEquals(setOf("u1"), alreadySigned)
    }
}
