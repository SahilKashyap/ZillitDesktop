package com.zillit.desktop.feature.formsignature.domain

/**
 * A file as this backend stores it — an S3 (or Box) descriptor, never bytes.
 *
 * The `caption`/`duration`/`height`/`width` quartet is carried because the
 * wire shape demands it on writes (`caption:'', duration:1, height:1,
 * width:1` on every document the web sends); they mean nothing for a PDF and
 * are not modelled here beyond the write path.
 */
data class StoredDocument(
    /** The object key. Called `media` on the wire. */
    val media: String,
    val thumbnail: String = media,
    val bucket: String = "",
    val region: String = "",
    val name: String = "",
    /** `document` for PDFs; the web hardcodes it on every sign-tool write. */
    val contentType: String = CONTENT_TYPE_DOCUMENT,
    val contentSubtype: String = SUBTYPE_PDF,
) {
    val isPdf: Boolean get() = contentSubtype.equals(SUBTYPE_PDF, ignoreCase = true) ||
        name.endsWith(".pdf", ignoreCase = true)

    companion object {
        const val CONTENT_TYPE_DOCUMENT = "document"
        const val SUBTYPE_PDF = "pdf"
    }
}

/**
 * The standard forms & contracts library — one row.
 *
 * Two tabs share this shape with different wire spellings: `all-forms` rows
 * carry `document` and `user_id`/`full_name`; `your-forms` (self-assigned)
 * rows carry `sender_documents` and `sender_id`. The data layer folds both
 * into one model so the UI never branches on which list a row came from.
 */
data class StandardForm(
    val id: String,
    val serialNo: String = "",
    val document: StoredDocument?,
    val type: StandardFormType = StandardFormType.Reference,
    val createdOn: String = "",
    val uploaderId: String = "",
    val uploaderName: String = "",
    /** Signed copies attached to the form; who has signed rides on them. */
    val signedCopies: List<SignedCopy> = emptyList(),
) {
    val name: String get() = document?.name.orEmpty()

    fun signedBy(userId: String): Boolean = signedCopies.any { it.signedBy == userId }
}

/** A signed rendition attached to a standard form. */
data class SignedCopy(
    val signedBy: String = "",
    val document: StoredDocument? = null,
)

/**
 * `document_type` on the wire. Anything unrecognised renders as a reference
 * document, which is also the web's default branch.
 */
enum class StandardFormType(val wire: String, val label: String) {
    Contract("contract", "Contract"),
    Other("other_document", "Other Document"),
    Reference("form", "Reference Document"),
    ;

    companion object {
        fun fromWire(raw: String?): StandardFormType =
            entries.firstOrNull { it.wire == raw } ?: Reference
    }
}

/**
 * A placed signature or initials box, in **PDF points with a bottom-left
 * origin** — the exact numbers the web stores and reads back. Nothing in
 * this app converts them except at the rendering boundary, where screen
 * pixels are top-left; keeping the domain in PDF space means a coordinate
 * read from the server can be handed straight to the stamper.
 */
data class SignSpot(
    val kind: SignSpotKind,
    /** 1-based, as the wire has it (`page_number`). */
    val page: Int,
    val x: Double,
    val y: Double,
    val width: Double,
    val height: Double,
)

enum class SignSpotKind(val wire: String, val label: String) {
    Signature("signature", "Signature"),
    Initials("initials", "Initials"),
    ;

    companion object {
        fun fromWire(raw: String?): SignSpotKind =
            entries.firstOrNull { it.wire == raw } ?: Signature
    }
}

/** One signer on a document sent for signature. */
data class DocumentSigner(
    val userId: String,
    val email: String = "",
    val fullName: String = "",
    /** `signed` is the only status that matters; everything else is pending. */
    val signed: Boolean = false,
    val order: Int = 0,
    val isExternal: Boolean = false,
    val spots: List<SignSpot> = emptyList(),
)

/**
 * A document in the for-signature flow — uploaded, received, or finalized.
 */
data class SignDocument(
    val id: String,
    val document: StoredDocument?,
    /**
     * The progressively-signed rendition. Each signer flattens their marks
     * into the PDF and uploads it whole, so the latest copy is the one to
     * show and to sign on top of; the original is only the starting point.
     */
    val signingDocument: StoredDocument? = null,
    val signers: List<DocumentSigner> = emptyList(),
    val uploadedBy: String = "",
    val createdOn: String = "",
    val onlySignatureRequired: Boolean = true,
    val userSignatureRequired: Boolean = false,
    val finalized: Boolean = false,
    /** The union of every signer's boxes — what the sender placed. */
    val spots: List<SignSpot> = emptyList(),
) {
    val name: String get() = document?.name.orEmpty()

    fun uploaderName(): String =
        signers.firstOrNull { it.userId == uploadedBy }?.fullName ?: ""

    /** The copy to open: the latest signed rendition, else the original. */
    val current: StoredDocument? get() = signingDocument ?: document

    fun signer(userId: String): DocumentSigner? = signers.firstOrNull { it.userId == userId }

    /**
     * What this user still has to fill in. Empty either when they have
     * signed or when the sender placed no boxes for them (the free-placement
     * flow, where they put their signature wherever they choose).
     */
    fun pendingSpotsFor(userId: String): List<SignSpot> {
        val me = signer(userId) ?: return emptyList()
        if (me.signed || finalized) return emptyList()
        return me.spots
    }
}

/** The three lists of the for-signature area, in the web's tab order. */
enum class SignDocumentTab(val wire: String, val label: String) {
    Uploaded("send-for-signature", "Sent for signature"),
    Received("received-for-signature", "Received for signature"),
    Finalized("fully-signed-document", "Fully signed"),
}

/** A saved signature block — a drawn PNG the server stores by reference. */
data class SignatureBlock(
    val id: String,
    /** True = the full signature; false = initials. Exactly one of each may exist. */
    val isSignature: Boolean = true,
    val image: StoredDocument? = null,
    val name: String = "",
)

/** A crew member offered as a signer, from the tool's own users route. */
data class SignerOption(
    val userId: String,
    val fullName: String = "",
    val email: String = "",
    val canPost: Boolean = false,
)

/** One line of a document's history dialog. */
data class HistoryEntry(
    val action: String = "",
    val actorName: String = "",
    val happenedOn: String = "",
)
