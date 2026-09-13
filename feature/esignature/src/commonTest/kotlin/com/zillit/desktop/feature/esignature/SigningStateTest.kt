package com.zillit.desktop.feature.esignature

import com.zillit.desktop.feature.esignature.domain.Envelope
import com.zillit.desktop.feature.esignature.domain.FieldAnswer
import com.zillit.desktop.feature.esignature.domain.FieldType
import com.zillit.desktop.feature.esignature.ui.ManageState
import com.zillit.desktop.feature.esignature.ui.SentFilter
import com.zillit.desktop.feature.esignature.ui.SigningState
import com.zillit.desktop.feature.esignature.ui.flows.SigningFlow
import com.zillit.desktop.feature.esignature.domain.EnvelopeStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What counts as done, what unlocks Finish, and what the Sent chips slice. */
class SigningStateTest {

    private val me = signer("r1", "u1")

    @Test
    fun `done follows the web's rules field by field`() {
        val locked = field("l", FieldType.Text, "r1").copy(locked = true, defaultValue = "x")
        val optional = field("o", FieldType.Text, "r1", required = false)
        val stamped = field("d", FieldType.DateSigned, "r1")
        val blank = field("t", FieldType.Text, "r1")
        val typedEmpty = field("e", FieldType.Text, "r1")
        val state = SigningState(
            envelope = Envelope("e"),
            me = me,
            myFields = listOf(locked, optional, stamped, blank, typedEmpty),
            answers = mapOf("e" to FieldAnswer.Typed("")),
        )
        assertTrue(state.isDone(locked))
        assertTrue(state.isDone(optional))
        assertTrue(state.isDone(stamped))
        assertFalse(state.isDone(blank))
        assertFalse(state.isDone(typedEmpty), "an emptied answer is not an answer")
        assertEquals(2, state.requiredCount, "locked and stamped fields are not asked of the signer")
        assertTrue(state.hasOptional)
    }

    @Test
    fun `sign once collapses the auto initials, off shows them all`() {
        val fields = listOf(
            field("a", FieldType.InitialHere, "r1", autoInitial = true),
            field("b", FieldType.InitialHere, "r1", autoInitial = true),
            field("s", FieldType.SignHere, "r1"),
        )
        val state = SigningState(envelope = Envelope("e"), me = me, myFields = fields)
        assertEquals(listOf("a", "s"), state.visibleFields.map { it.id })
        assertEquals(listOf("a", "b", "s"), state.copy(signOnce = false).visibleFields.map { it.id })
    }

    @Test
    fun `text validation matches the web`() {
        assertNull(SigningFlow.validateText(FieldType.Email, ""))
        assertEquals("Please enter a valid email address", SigningFlow.validateText(FieldType.Email, "nope"))
        assertNull(SigningFlow.validateText(FieldType.Email, "a@b.io"))
        assertEquals("Phone number is too short", SigningFlow.validateText(FieldType.Phone, "+44 1"))
        assertEquals("Use digits, spaces, +, - or () only", SigningFlow.validateText(FieldType.Phone, "call me"))
        assertNull(SigningFlow.validateText(FieldType.Phone, "+44 (0)20 7946 0000"))
        assertEquals("Numbers only", SigningFlow.validateText(FieldType.Number, "ten"))
        assertNull(SigningFlow.validateText(FieldType.Number, "1,500.00"))
    }

    @Test
    fun `the Sent chips slice by progress and bulk children stay hidden`() {
        val fresh = Envelope(
            "a",
            status = EnvelopeStatus.Sent,
            recipients = listOf(signer("r", "x"), signer("q", "y")),
            updated = 1,
        )
        val half = Envelope(
            "b",
            status = EnvelopeStatus.Sent,
            recipients = listOf(signer("r", "x", status = "signed"), signer("q", "y")),
            updated = 2,
        )
        val bulk = Envelope("c", status = EnvelopeStatus.Sent, bulkJobId = "j")
        val manage = ManageState(buckets = mapOf("sent" to listOf(fresh, half, bulk)))
        assertEquals(listOf("b", "a"), manage.sentFiltered.map { it.id }, "newest activity first")
        assertEquals(listOf("a"), manage.copy(sentFilter = SentFilter.Awaiting).sentFiltered.map { it.id })
        assertEquals(listOf("b"), manage.copy(sentFilter = SentFilter.InProgress).sentFiltered.map { it.id })
        assertEquals(1, manage.hiddenBulkSent)
    }
}
