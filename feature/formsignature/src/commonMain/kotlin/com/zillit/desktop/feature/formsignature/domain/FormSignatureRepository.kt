package com.zillit.desktop.feature.formsignature.domain

import com.zillit.desktop.core.common.ZillitResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * File transfer, injected from the host.
 *
 * This module never talks to storage itself: the app already owns an S3
 * uploader (with the region/bucket negotiation and its Box sibling) and a
 * signed reader. Two narrow functions are all this tool needs of them.
 */
interface SignFileTransfer {

    /** Uploads [bytes] under a key shaped for this tool; returns the descriptor. */
    suspend fun store(
        purpose: UploadPurpose,
        fileName: String,
        contentType: String,
        bytes: ByteArray,
    ): ZillitResult<StoredDocument>

    /** Fetches a stored file's bytes — previews, and the PDF being signed. */
    suspend fun fetch(document: StoredDocument): ZillitResult<ByteArray>
}

/** What an upload is for; decides the object-key shape. */
enum class UploadPurpose {
    /** A document (standard form, or one sent for signature). */
    Document,

    /** A drawn signature PNG — keyed under the project's `sign/` prefix, as the web does. */
    SignatureImage,
}

/**
 * The Documents & Signature REST surface, all on the documents service.
 *
 * The V2 web tool is the reference. Notable transcription choices:
 *
 *  - both standard-forms tabs come through [standardForms]; the repository
 *    folds the `your-forms` spelling (`sender_documents`, `sender_id`) into
 *    the same model;
 *  - signing sends a **finished file**, not marks — see [PdfWork.stamp];
 *  - saving a signature block is one POST for create and update alike; the
 *    web's add and update functions are byte-identical.
 */
interface FormSignatureRepository {

    /**
     * Which list a socket `document:*` event says to refetch — the web's
     * two pages refresh independently (`StandardFormsV2.jsx` the forms
     * list, `DocumentsForSignature.jsx`/`FormPage.jsx` the for-signature
     * list), so the kind travels with the pulse and the view model reloads
     * only what is on screen. Defaulted empty for tests and hosts without
     * a socket.
     */
    val refreshes: Flow<FormSignRefresh> get() = emptyFlow()

    suspend fun standardForms(selfAssigned: Boolean): ZillitResult<List<StandardForm>>

    /** Adds a library form to the caller's own list. */
    suspend fun selfAssign(documentId: String): ZillitResult<Unit>

    suspend fun deleteStandardForm(documentId: String): ZillitResult<Unit>

    /** Publishes an uploaded file into the standard library. */
    suspend fun addStandardForm(
        document: StoredDocument,
        type: StandardFormType,
        note: String,
    ): ZillitResult<Unit>

    suspend fun history(documentId: String): ZillitResult<List<HistoryEntry>>

    suspend fun documents(tab: SignDocumentTab): ZillitResult<List<SignDocument>>

    /** Uploads-and-sends: the sender's document with its placed spots. */
    suspend fun sendForSignature(
        document: StoredDocument,
        signers: List<DocumentSigner>,
        onlySignatureRequired: Boolean,
        userSignatureRequired: Boolean,
    ): ZillitResult<Unit>

    suspend fun deleteDocument(documentId: String): ZillitResult<Unit>

    /**
     * Records this user's signing of a for-signature document: [signed] is
     * the flattened rendition already uploaded to storage.
     */
    suspend fun signDocument(documentId: String, signed: StoredDocument): ZillitResult<Unit>

    /** The same act for a self-assigned standard form, on its older route. */
    suspend fun signStandardForm(documentId: String, signed: StoredDocument): ZillitResult<Unit>

    suspend fun signatures(): ZillitResult<List<SignatureBlock>>

    /** Create and update alike; [existingId] present means update. */
    suspend fun saveSignature(
        image: StoredDocument,
        name: String,
        isSignature: Boolean,
        existingId: String?,
    ): ZillitResult<Unit>

    suspend fun deleteSignature(signatureId: String): ZillitResult<Unit>

    /** Everyone the tool offers as a signer, with their tool rights. */
    suspend fun signerOptions(): ZillitResult<List<SignerOption>>
}

/** The two lists a socket event can point at; see [FormSignatureRepository.refreshes]. */
enum class FormSignRefresh { Forms, Documents }
