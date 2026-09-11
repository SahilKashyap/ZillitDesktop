package com.zillit.desktop.feature.esignature.domain

import com.zillit.desktop.core.common.ZillitResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * The envelope service's REST surface — the slice of the web's DocuSign
 * panel this port speaks.
 *
 * Two facts shape every signature here:
 *
 *  - **The server flattens.** Signing sends field *values* (a mark sends
 *    its stored image descriptor); the finished PDF comes back from the
 *    backend as `signed_document`. No client-side stamping — the mirror
 *    image of the documents tool.
 *  - **Create then send are two calls.** The editor always creates with
 *    `send_now:false` and follows with the send call; the web never uses
 *    the combined form, so neither does this.
 */
interface EsignRepository {

    /**
     * A pulse per envelope-lifecycle event on the socket — delivered,
     * signed, declined, completed, voided, updated, deleted (the web's
     * `DocuSignObservers.jsx:42-52`, fed by `listenerSocket.js:27-33`).
     * The listener refetches the visible list rather than patching rows:
     * the wire's envelope shapes vary by event and the refetch cannot go
     * stale. Defaulted empty for tests and hosts without a socket.
     */
    val refreshes: Flow<Unit> get() = emptyFlow()

    /**
     * [userId] rides as a query parameter on the received scope — the web's
     * slice sends it, and without it the received bucket answers empty.
     */
    suspend fun envelopes(
        scope: EnvelopeScope,
        bucket: String,
        userId: String?,
    ): ZillitResult<List<Envelope>>

    suspend fun envelope(id: String): ZillitResult<Envelope>

    /** Creates a draft; the answer carries the server's envelope. */
    suspend fun create(
        title: String,
        description: String,
        document: StoredFile,
        recipients: List<EnvelopeRecipient>,
        fields: List<NewField>,
        /** Every page gets an initials mark for every signer. */
        initialsOnAllPages: Boolean = false,
        /** Days between reminder emails; null leaves the server's default. */
        reminderCadenceDays: Int? = null,
    ): ZillitResult<Envelope>

    suspend fun send(envelopeId: String): ZillitResult<Unit>

    suspend fun deleteDraft(envelopeId: String): ZillitResult<Unit>

    /** Remind every outstanding recipient, or one when [recipientId] is given. */
    suspend fun remind(envelopeId: String, recipientId: String?): ZillitResult<Unit>

    suspend fun markViewed(envelopeId: String): ZillitResult<Unit>

    /**
     * Records that this signer agreed to sign electronically.
     *
     * The whole basis of the consent gate: without it the envelope's audit
     * trail cannot show the signer ever agreed, and `accepted_terms_on` stays
     * unset. The recipient is resolved from the session, so there is no body.
     */
    suspend fun acceptTerms(envelopeId: String): ZillitResult<Unit>

    /**
     * Cancels an envelope that has already gone out.
     *
     * Not a delete: the envelope and its trail stay, marked void with the
     * reason. Deleting is only ever available on a draft nobody has seen.
     */
    suspend fun voidEnvelope(envelopeId: String, reason: String): ZillitResult<Unit>

    suspend fun sign(
        envelopeId: String,
        answers: List<SignedField>,
    ): ZillitResult<Unit>

    suspend fun decline(envelopeId: String, reason: String): ZillitResult<Unit>

    suspend fun auditTrail(envelopeId: String): ZillitResult<List<AuditEntry>>

    suspend fun savedSignatures(): ZillitResult<List<SavedSignature>>

    suspend fun saveSignature(isSignature: Boolean, image: StoredFile): ZillitResult<Unit>

    suspend fun deleteSavedSignature(id: String): ZillitResult<Unit>
}

/**
 * A field being placed at compose time. Coordinates in PDF points,
 * top-left origin; the owner travels as `recipient_index` (0-based into
 * the recipients array) — the backend mints recipient ids during create
 * and resolves the index itself. A client-side id would be rejected.
 */
data class NewField(
    val type: FieldType,
    val recipientIndex: Int,
    val page: Int,
    val x: Double,
    val y: Double,
    val width: Double = FieldType.DEFAULT_WIDTH,
    val height: Double = FieldType.DEFAULT_HEIGHT,
    val style: FieldStyle = FieldStyle(),
    val options: List<String> = emptyList(),
)

/**
 * One entry of the sign call's `signed_fields`, keyed by the tab's own id
 * — the backend rejects positional indexes.
 */
data class SignedField(
    val tabId: String,
    val type: FieldType,
    val documentIndex: Int,
    val answer: FieldAnswer?,
)
