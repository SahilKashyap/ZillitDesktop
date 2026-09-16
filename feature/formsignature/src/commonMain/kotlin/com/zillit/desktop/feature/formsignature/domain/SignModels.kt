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

    /** A Word file — the standard library takes those; signing converts them first. */
    val isWord: Boolean get() = !isPdf && (
        contentSubtype.lowercase() in WORD_SUBTYPES ||
            WORD_SUBTYPES.any { name.endsWith(".$it", ignoreCase = true) }
        )

    /** The file's extension, upper-cased, for the chip under a name. */
    val extension: String get() = name.substringAfterLast('.', "").ifBlank { contentSubtype }.uppercase()

    companion object {
        const val CONTENT_TYPE_DOCUMENT = "document"
        const val SUBTYPE_PDF = "pdf"
        val WORD_SUBTYPES = setOf("doc", "docx")
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
    /** `document_id` — the library record a My Downloads row was copied from; badges key on either. */
    val documentId: String = "",
    val serialNo: String = "",
    val document: StoredDocument?,
    val type: StandardFormType = StandardFormType.Reference,
    /** `created_on`, epoch millis; null when the wire sent nothing readable. */
    val createdOn: Long? = null,
    val uploaderId: String = "",
    val uploaderName: String = "",
    val uploaderDesignation: String = "",
    /** Signed copies attached to the form; who has signed rides on them. */
    val signedCopies: List<SignedCopy> = emptyList(),
) {
    val name: String get() = document?.name.orEmpty()

    /**
     * The copy to open — the web's `getDocument`: the *latest signed copy*
     * when one exists, else the original. A person who has signed their
     * downloaded form sees their ink when they open it again.
     */
    val current: StoredDocument? get() = signedCopies.lastOrNull()?.document ?: document

    fun signedBy(userId: String): Boolean = signedCopies.any { it.signedBy == userId }
}

/** A signed rendition attached to a form. */
data class SignedCopy(
    val signedBy: String = "",
    val signedOn: Long? = null,
    val document: StoredDocument? = null,
)

/**
 * `document_type` on the wire — the web's V2 upload radio posts these three
 * spellings, and `getDocumentType` renders anything else as a reference
 * document, which is also the default branch here.
 */
enum class StandardFormType(val wire: String, val label: String) {
    Contract("contract", "Contract"),
    Other("other_document", "Other Document"),
    Reference("reference_document", "Reference Document"),
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
) {
    /** The web's `${type}_${page_number}` key, which is how it marks a placeholder filled. */
    val key: String get() = "${kind.wire}_$page"
}

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
    /** Older rows carry their signed copies as `documents[]`, like the library does. */
    val signedCopies: List<SignedCopy> = emptyList(),
    val signers: List<DocumentSigner> = emptyList(),
    val uploadedBy: String = "",
    val createdOn: Long? = null,
    val onlySignatureRequired: Boolean = false,
    val userSignatureRequired: Boolean = false,
    val finalized: Boolean = false,
    /** The union of every signer's boxes — what the sender placed. */
    val spots: List<SignSpot> = emptyList(),
) {
    val name: String get() = document?.name.orEmpty()

    /** `users[].user_fullname` for `uploaded_by` — the web's uploader column. */
    fun uploaderName(): String =
        signers.firstOrNull { it.userId == uploadedBy }?.fullName ?: ""

    /** The copy to open: the web's `getDocument` order — last signed copy, the signing copy, the original. */
    val current: StoredDocument? get() = signedCopies.lastOrNull()?.document ?: signingDocument ?: document

    /** The web's `isDocumentForSignatureFlow`: boxes were placed, so signing is click-to-fill. */
    val hasPlaceholders: Boolean get() = spots.isNotEmpty()

    fun signer(userId: String): DocumentSigner? = signers.firstOrNull { it.userId == userId }

    /** The web's `isCurrentUserSigned`. */
    fun signedBy(userId: String): Boolean = signer(userId)?.signed == true || finalized

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

/** The three lists of the for-signature area, in the web's segment order. */
enum class SignDocumentTab(val wire: String, val label: String) {
    Uploaded("send-for-signature", "Send for Signature"),
    Received("received-for-signature", "Received for Signature"),
    Finalized("fully-signed-document", "Fully Signed Document"),
    ;

    /** The badge ledger's `level_1` for this tab; the sent list has none. */
    val readLevel: String? get() = when (this) {
        Uploaded -> null
        Received -> "received_for_signature"
        Finalized -> "fully_signed"
    }
}

/** A saved signature block — a drawn PNG the server stores by reference. */
data class SignatureBlock(
    val id: String,
    /** True = the full signature; false = initials. Exactly one of each may exist. */
    val isSignature: Boolean = true,
    val image: StoredDocument? = null,
    val name: String = "",
    val createdOn: Long? = null,
) {
    val kind: SignSpotKind get() = if (isSignature) SignSpotKind.Signature else SignSpotKind.Initials
}

/** A crew member the tool offers as a signer, from its own users route. */
data class SignerOption(
    val userId: String,
    val fullName: String = "",
    val email: String = "",
    val canPost: Boolean = false,
    val canView: Boolean = true,
    val designation: String = "",
    /** `left` rows are not offered — the web's `status !== 'left'`. */
    val status: String = "",
) {
    val label: String get() = fullName.ifBlank { email.ifBlank { userId } }
}

/** Someone outside the production, from the external-users directory. */
data class ExternalSigner(
    val id: String,
    val fullName: String = "",
    val email: String = "",
) {
    val label: String get() = fullName.ifBlank { email.ifBlank { id } }
}

/**
 * The tool's discussion unit (`GET form-signature/unit` on the unit host):
 * the room "Chat with Admins" / "Chat with Users" opens on. [members]
 * carry `enabled` — true for the people who *answer* (admins), which is
 * the web's `isSelectUserShow` test.
 */
data class ChatUnit(
    val id: String,
    val name: String = "",
    val members: List<ChatMember> = emptyList(),
) {
    fun answers(userId: String): Boolean = members.firstOrNull { it.userId == userId }?.enabled == true
}

data class ChatMember(val userId: String, val enabled: Boolean = false)

/** One line of the standard-form history dialog. */
data class HistoryPerson(
    val userId: String = "",
    val fullName: String = "",
    val designation: String = "",
    val at: Long? = null,
)
