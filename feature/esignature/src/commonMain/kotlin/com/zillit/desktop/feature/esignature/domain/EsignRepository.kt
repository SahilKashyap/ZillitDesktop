@file:Suppress("TooManyFunctions") // One function per route the web speaks.

package com.zillit.desktop.feature.esignature.domain

import com.zillit.desktop.core.common.ZillitResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * The envelope service's REST surface — the web's `docusignApi.real.js`,
 * transcribed route for route.
 *
 * Two facts shape every signature here:
 *
 *  - **The server flattens.** Signing sends field *values* (a mark sends
 *    its stored image descriptor); the finished PDF comes back from the
 *    backend as `signed_document`. No client-side stamping — the mirror
 *    image of the documents tool.
 *  - **Create then send are two calls.** The editor always creates (or
 *    updates a draft) with `send_now:false` and follows with the send call;
 *    the web never uses the combined form, so neither does this.
 */
interface EsignRepository {

    /**
     * A pulse per envelope-lifecycle event on the socket — delivered,
     * signed, declined, completed, voided, updated, deleted (the web's
     * `DocuSignObservers.jsx`, fed by `listenerSocket.js`). The listener
     * refetches the visible list rather than patching rows: the wire's
     * envelope shapes vary by event and the refetch cannot go stale.
     * Defaulted empty for tests and hosts without a socket.
     */
    val refreshes: Flow<Unit> get() = emptyFlow()

    // ---------------------------------------------------------------- envelopes

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
    suspend fun create(draft: EnvelopeDraft): ZillitResult<Envelope>

    /** Rewrites a draft in place — `PUT /envelopes/{id}`, drafts only. */
    suspend fun update(envelopeId: String, draft: EnvelopeDraft): ZillitResult<Envelope>

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
     * unset. The recipient is resolved from the session; the body names the
     * disclosure version accepted.
     */
    suspend fun acceptTerms(envelopeId: String): ZillitResult<Unit>

    /**
     * Cancels an envelope that has already gone out.
     *
     * Not a delete: the envelope and its trail stay, marked void with the
     * reason. Deleting is only ever available on a draft nobody has seen.
     */
    suspend fun voidEnvelope(envelopeId: String, reason: String): ZillitResult<Unit>

    suspend fun sign(envelopeId: String, answers: List<SignedField>): ZillitResult<Unit>

    suspend fun decline(envelopeId: String, reason: String): ZillitResult<Unit>

    suspend fun auditTrail(envelopeId: String): ZillitResult<List<AuditEntry>>

    /** The server-rendered certificate — `GET .../audit-trail/pdf`, raw bytes. */
    suspend fun auditTrailPdf(envelopeId: String): ZillitResult<ByteArray>

    // ---------------------------------------------------------------- marks

    suspend fun savedSignatures(): ZillitResult<List<SavedSignature>>

    suspend fun saveSignature(isSignature: Boolean, image: StoredFile): ZillitResult<Unit>

    suspend fun deleteSavedSignature(id: String): ZillitResult<Unit>

    // ---------------------------------------------------------------- templates

    suspend fun templates(): ZillitResult<List<EnvelopeTemplate>>

    suspend fun templateCategories(): ZillitResult<List<String>>

    suspend fun createTemplate(draft: TemplateDraft): ZillitResult<EnvelopeTemplate>

    suspend fun updateTemplate(templateId: String, draft: TemplateDraft): ZillitResult<EnvelopeTemplate>

    suspend fun deleteTemplate(templateId: String): ZillitResult<Unit>

    // ---------------------------------------------------------------- bulk send

    /** Starts a job; answers its id, status and row count. */
    suspend fun startBulkSend(templateId: String, csvText: String, name: String?): ZillitResult<BulkJob>

    suspend fun bulkJobs(): ZillitResult<List<BulkJob>>

    /** One job with its rows. */
    suspend fun bulkJob(jobId: String): ZillitResult<BulkJob>

    suspend fun retryFailedRows(jobId: String): ZillitResult<Unit>

    suspend fun remindOutstanding(jobId: String): ZillitResult<Int>
}

/**
 * What the editor writes — one envelope, drafted or sent.
 *
 * Fields carry `recipientIndex` (0-based into [recipients]); the backend
 * mints recipient ids during create and resolves the index itself. A
 * client-side id would be rejected.
 */
data class EnvelopeDraft(
    val title: String,
    val description: String = "",
    val document: StoredFile?,
    val recipients: List<EnvelopeRecipient>,
    val fields: List<EnvelopeField>,
    val settings: EnvelopeSettings = EnvelopeSettings(),
)

/** A template's write shape: role slots, not people. */
data class TemplateDraft(
    val name: String,
    val description: String = "",
    val category: String = "",
    val documents: List<StoredFile>,
    /** Only `role`, `routingOrder` and `placeholderLabel` are sent. */
    val slots: List<EnvelopeRecipient>,
    val fields: List<EnvelopeField>,
    val settings: EnvelopeSettings = EnvelopeSettings(),
    /** Set when saved from an existing envelope. */
    val fromEnvelopeId: String? = null,
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
    /** The field's own default, sent when the signer never touched it. */
    val defaultValue: String = "",
)
