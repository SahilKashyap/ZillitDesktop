package com.zillit.desktop.feature.esignature

import com.zillit.desktop.feature.esignature.domain.Envelope
import com.zillit.desktop.feature.esignature.domain.EnvelopeField
import com.zillit.desktop.feature.esignature.domain.EnvelopeRecipient
import com.zillit.desktop.feature.esignature.domain.EnvelopeStatus
import com.zillit.desktop.feature.esignature.domain.FieldType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Whose envelope this is to sign, and which fields on it are theirs.
 *
 * Two joins decide it and they use different keys: the person is found by
 * `userId`, the fields by the recipient row's own `id`. Crossing them —
 * matching fields on a user id — silently hands a signer either nothing to
 * sign or somebody else's boxes.
 */
class WhoseToSignTest {

    private fun signer(id: String, userId: String, status: String = "sent") =
        EnvelopeRecipient(id = id, userId = userId, name = userId, role = "signer", status = status)

    private fun field(id: String, recipientId: String, type: FieldType = FieldType.SignHere) =
        EnvelopeField(id = id, type = type, recipientId = recipientId)

    private val envelope = Envelope(
        id = "env-1",
        status = EnvelopeStatus.Sent,
        recipients = listOf(signer("r1", "u1"), signer("r2", "u2")),
        fields = listOf(field("f1", "r1"), field("f2", "r1"), field("f3", "r2")),
    )

    @Test
    fun `a signer is found by user id and their fields by recipient id`() {
        val me = envelope.recipientFor("u1")

        assertEquals("r1", me?.id)
        assertEquals(listOf("f1", "f2"), envelope.fieldsFor(me!!).map { it.id })
    }

    /** The two ids never collide by accident, so the wrong join returns nothing. */
    @Test
    fun `fields do not answer to the signer's user id`() {
        val byUserId = envelope.fields.filter { it.recipientId == "u1" }

        assertTrue(byUserId.isEmpty(), "recipientId holds the row id, never the user id")
    }

    @Test
    fun `a copied-in recipient is never asked to sign`() {
        val cc = EnvelopeRecipient(id = "r3", userId = "u3", role = "cc")
        val withCc = envelope.copy(recipients = envelope.recipients + cc)

        assertNull(withCc.recipientFor("u3"), "cc is a reader, not a signer")
    }

    @Test
    fun `somebody not on the envelope is nobody`() {
        assertNull(envelope.recipientFor("u9"))
        assertNull(envelope.recipientFor(""))
    }

    @Test
    fun `signed covers both signed and completed, declined stands alone`() {
        assertTrue(signer("r", "u", status = "signed").signed)
        assertTrue(signer("r", "u", status = "completed").signed)
        assertFalse(signer("r", "u", status = "delivered").signed)
        assertTrue(signer("r", "u", status = "declined").declined)
        assertFalse(signer("r", "u", status = "declined").signed, "declining is not signing")
    }

    @Test
    fun `the summary counts signers only`() {
        assertEquals("0 of 2 signed", envelope.signerSummary)

        val oneDone = envelope.copy(
            recipients = listOf(signer("r1", "u1", status = "signed"), signer("r2", "u2")),
        )
        assertEquals("1 of 2 signed", oneDone.signerSummary)
    }

    @Test
    fun `an envelope of watchers reports no signers rather than zero of zero`() {
        val ccOnly = envelope.copy(
            recipients = listOf(EnvelopeRecipient(id = "r3", userId = "u3", role = "cc")),
        )

        assertEquals("No signers", ccOnly.signerSummary)
    }

    /**
     * The gate the detail screen applies before offering the signing surface:
     * the viewer is a signer, has not already signed or declined, and the
     * envelope itself is still open.
     */
    private fun mineToSign(envelope: Envelope, userId: String): List<EnvelopeField> {
        val me = envelope.recipientFor(userId) ?: return emptyList()
        val open = envelope.status != EnvelopeStatus.Completed
        return if (!me.signed && !me.declined && open) envelope.fieldsFor(me) else emptyList()
    }

    @Test
    fun `a signer who has already signed is not asked twice`() {
        val after = envelope.copy(
            recipients = listOf(signer("r1", "u1", status = "signed"), signer("r2", "u2")),
        )

        assertTrue(mineToSign(after, "u1").isEmpty())
        assertEquals(1, mineToSign(after, "u2").size, "the other signer still owes a signature")
    }

    @Test
    fun `a completed envelope is closed even to a signer still marked sent`() {
        val closed = envelope.copy(status = EnvelopeStatus.Completed)

        assertTrue(mineToSign(closed, "u1").isEmpty(), "a finished envelope takes no more marks")
    }

    /** Voided and expired are not Completed — the recipient's own state closes those. */
    @Test
    fun `a voided envelope closes through the recipient, not the status`() {
        val voided = envelope.copy(
            status = EnvelopeStatus.Voided,
            recipients = listOf(signer("r1", "u1", status = "declined"), signer("r2", "u2")),
        )

        assertTrue(mineToSign(voided, "u1").isEmpty(), "declined stops the ask")
    }

    @Test
    fun `a signer with no fields placed has nothing to sign`() {
        val fieldless = envelope.copy(fields = listOf(field("f3", "r2")))

        assertTrue(mineToSign(fieldless, "u1").isEmpty())
    }
}
