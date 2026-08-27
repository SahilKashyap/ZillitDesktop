package com.zillit.desktop.feature.esignature

import com.zillit.desktop.feature.esignature.domain.EnvelopeStatus
import com.zillit.desktop.feature.esignature.domain.FieldType
import com.zillit.desktop.feature.esignature.domain.SignerOptionLike
import com.zillit.desktop.feature.esignature.ui.ComposeState
import com.zillit.desktop.feature.esignature.ui.PlacedField
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The gates on the compose flow — what has to be true before an envelope
 * may leave. Both of them exist because the service refuses the envelope
 * otherwise: a recipient with no address is rejected outright, and a signer
 * with no fields produces an envelope nobody can ever complete.
 */
class ComposeGateTest {

    private fun option(id: String, email: String = "") =
        SignerOptionLike(userId = id, fullName = id.uppercase(), email = email)

    private fun mark(page: Int = 1) =
        PlacedField(type = FieldType.SignHere, page = page, x = 10.0, y = 20.0)

    @Test
    fun `an empty compose covers nobody`() {
        assertFalse(ComposeState().everySignerCovered, "nothing chosen is not coverage")
    }

    @Test
    fun `every chosen signer needs at least one field`() {
        val state = ComposeState(
            chosen = listOf("u1", "u2"),
            placed = mapOf("u1" to listOf(mark())),
        )

        assertFalse(state.everySignerCovered, "u2 has nowhere to sign")
        assertTrue(
            state.copy(placed = state.placed + ("u2" to listOf(mark(page = 2)))).everySignerCovered,
        )
    }

    /** An empty list is as uncovered as a missing one — `isNullOrEmpty`, not `== null`. */
    @Test
    fun `a signer whose fields were all removed is uncovered again`() {
        val state = ComposeState(chosen = listOf("u1"), placed = mapOf("u1" to emptyList()))

        assertFalse(state.everySignerCovered, "an emptied entry still means no fields")
    }

    /** Fields left behind for a signer since dropped do not count towards coverage. */
    @Test
    fun `fields for an unchosen signer do not cover the chosen one`() {
        val state = ComposeState(
            chosen = listOf("u1"),
            placed = mapOf("u2" to listOf(mark())),
        )

        assertFalse(state.everySignerCovered, "u2's marks are not u1's")
        assertEquals(1, state.placedCount, "but they are still placed on the page")
    }

    @Test
    fun `placed count spans signers and pages`() {
        val state = ComposeState(
            placed = mapOf(
                "u1" to listOf(mark(page = 1), mark(page = 3)),
                "u2" to listOf(mark(page = 1)),
            ),
        )

        assertEquals(3, state.placedCount)
    }

    @Test
    fun `a crew row's address is used as it stands`() {
        val state = ComposeState(options = listOf(option("u1", "sam@unit.example")))

        assertEquals("sam@unit.example", state.emailFor("u1"))
        assertTrue(state.copy(chosen = listOf("u1")).missingEmails.isEmpty())
    }

    @Test
    fun `a signer with no crew address is named as missing`() {
        val state = ComposeState(options = listOf(option("u1")), chosen = listOf("u1"))

        assertEquals(listOf("u1"), state.missingEmails)
        assertEquals("", state.emailFor("u1"))
    }

    @Test
    fun `the sender's override answers for a blank crew row`() {
        val state = ComposeState(
            options = listOf(option("u1")),
            chosen = listOf("u1"),
            emailOverrides = mapOf("u1" to "sam@unit.example"),
        )

        assertEquals("sam@unit.example", state.emailFor("u1"))
        assertTrue(state.missingEmails.isEmpty(), "the override satisfies the service")
    }

    /**
     * But only for a blank one. A crew row that already carries an address
     * wins over anything the sender typed — so the override box is a way to
     * supply a missing address, never a way to correct a wrong one.
     */
    @Test
    fun `the crew row wins over an override`() {
        val state = ComposeState(
            options = listOf(option("u1", "onfile@unit.example")),
            emailOverrides = mapOf("u1" to "typed@unit.example"),
        )

        assertEquals("onfile@unit.example", state.emailFor("u1"))
    }

    @Test
    fun `a signer the options never listed is missing, not crashed`() {
        val state = ComposeState(chosen = listOf("ghost"))

        assertEquals("", state.emailFor("ghost"))
        assertEquals(listOf("ghost"), state.missingEmails)
    }

    @Test
    fun `bytes take part in equality by length only`() {
        val a = ComposeState(fileBytes = byteArrayOf(1, 2, 3))
        val b = ComposeState(fileBytes = byteArrayOf(9, 9, 9))

        assertEquals(a, b, "same length reads as the same state — arrays never equal themselves")
        assertEquals(a.hashCode(), b.hashCode())
        assertFalse(a == ComposeState(fileBytes = byteArrayOf(1, 2)))
        assertFalse(a == ComposeState(), "a chosen file differs from none")
    }

    @Test
    fun `an unrecognised status keeps its wire string rather than passing for draft`() {
        assertEquals(EnvelopeStatus.Sent, EnvelopeStatus.fromWire("sent"))
        assertEquals(EnvelopeStatus.Unknown, EnvelopeStatus.fromWire("archived"))
        assertEquals(EnvelopeStatus.Unknown, EnvelopeStatus.fromWire(null))
    }

    @Test
    fun `a field type the port does not draw falls to Other, not to a signature`() {
        assertEquals(FieldType.SignHere, FieldType.fromWire("signHere"))
        assertEquals(FieldType.Other, FieldType.fromWire("dropdown"))
        assertEquals(FieldType.Other, FieldType.fromWire(""), "the empty wire is not a match")
        assertFalse(FieldType.Other.isMark, "an unknown field never demands a signature")
        assertFalse(FieldType.Other.isTyped)
    }

    @Test
    fun `marks and typed fields are told apart`() {
        assertTrue(FieldType.SignHere.isMark && FieldType.InitialHere.isMark)
        assertTrue(FieldType.Text.isTyped && FieldType.FullName.isTyped && FieldType.Email.isTyped)
        assertFalse(FieldType.DateSigned.isMark || FieldType.DateSigned.isTyped)
        assertFalse(FieldType.Checkbox.isMark || FieldType.Checkbox.isTyped)
    }
}
