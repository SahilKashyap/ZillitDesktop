package com.zillit.desktop.feature.esignature

import com.zillit.desktop.feature.esignature.domain.Envelope
import com.zillit.desktop.feature.esignature.domain.EnvelopeStatus
import com.zillit.desktop.feature.esignature.ui.EnvelopeDetailState
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Two things the desktop was missing against the web, both of them about what
 * the audit trail can later show.
 *
 * Consent is the ground an electronic signature stands on. The desktop kept it
 * on screen only — the acceptance never reached the server, so
 * `accepted_terms_on` stayed unset and the trail could not show the signer
 * ever agreed. The web hit the same defect and fixed it with
 * `POST /envelopes/{id}/accept-terms`; this now does the same.
 *
 * Cancelling is the sender's half of declining, and the desktop offered no way
 * to do it: a draft could be deleted, but an envelope already sent could not
 * be stopped.
 */
class ConsentAndVoidTest {

    private fun detail(status: EnvelopeStatus, sentByMe: Boolean) = EnvelopeDetailState(
        envelope = Envelope(id = "e1", title = "NDA", status = status),
        sentByMe = sentByMe,
    )

    /** Out and unfinished is what can be cancelled. */
    @Test
    fun `a live envelope its sender opened can be cancelled`() {
        listOf(EnvelopeStatus.Sent, EnvelopeStatus.Delivered, EnvelopeStatus.Signed).forEach {
            assertTrue(detail(it, sentByMe = true).canVoid, it.wire)
        }
    }

    /**
     * A draft is deleted, not cancelled.
     *
     * Nobody has seen it, so there is nothing to tell anybody and no trail
     * worth keeping.
     */
    @Test
    fun `a draft is not cancellable`() {
        assertFalse(detail(EnvelopeStatus.Draft, sentByMe = true).canVoid)
        assertFalse(EnvelopeStatus.Draft.isCancellable)
    }

    /** An envelope with an outcome already has one; cancelling changes nothing. */
    @Test
    fun `a finished envelope is not cancellable`() {
        listOf(
            EnvelopeStatus.Completed,
            EnvelopeStatus.Declined,
            EnvelopeStatus.Voided,
            EnvelopeStatus.Expired,
            EnvelopeStatus.Rejected,
        ).forEach { assertFalse(detail(it, sentByMe = true).canVoid, it.wire) }
    }

    /**
     * A recipient does not cancel; they decline.
     *
     * The two are the same decision from opposite ends, and offering the
     * sender's one to a signer would let them withdraw somebody else's
     * document.
     */
    @Test
    fun `a recipient is not offered cancellation`() {
        assertFalse(detail(EnvelopeStatus.Sent, sentByMe = false).canVoid)
    }

    /**
     * The consent gate stays up until the acceptance is recorded.
     *
     * Signing against a consent nothing recorded leaves the trail unable to
     * answer the only question ever asked of it.
     */
    @Test
    fun `fields stay hidden while the acceptance is in flight`() {
        val inFlight = detail(EnvelopeStatus.Sent, sentByMe = false)
            .copy(consenting = true, consented = false)

        assertFalse(inFlight.consented)
        assertFalse(inFlight.canSignNow)
    }
}
