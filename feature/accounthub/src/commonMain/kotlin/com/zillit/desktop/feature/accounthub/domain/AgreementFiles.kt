package com.zillit.desktop.feature.accounthub.domain

import com.zillit.desktop.core.common.ZillitResult

/** A PDF the user chose but has not uploaded yet. */
data class PickedAgreementFile(
    val name: String,
    val bytes: Long,
    /** Whatever the host needs to read the file again at upload time. */
    val handle: String,
)

/**
 * What a picked file is for.
 *
 * Decides both what may be chosen and where it is stored. The two document
 * purposes take PDFs only because those ride the signing flow, which takes
 * nothing else; a budget is read and thrown away, so it takes whatever the
 * parser reads.
 */
enum class SetupUpload(val extensions: Set<String>, val maxBytes: Long, val refusal: String) {
    /** One of the production's standard agreements. */
    Agreement(
        extensions = setOf("pdf"),
        maxBytes = TWENTY_MB,
        refusal = "Only PDF files can be attached — they have to go through the signing flow.",
    ),

    /** The terms and conditions issued with every purchase order. */
    PurchaseOrderTerms(
        extensions = setOf("pdf"),
        maxBytes = TWENTY_MB,
        refusal = "The terms document has to be a PDF — it is issued with every order.",
    ),

    /** A budget file to parse. Read once and never stored as a document. */
    BudgetImport(
        extensions = setOf("pdf", "xlsx", "xls", "csv"),
        maxBytes = TWENTY_MB,
        refusal = "A budget has to be a PDF, an Excel file or a CSV.",
    ),
    ;

    /** Why [name] at [bytes] cannot be used for this, or null when it can. */
    fun refuse(name: String, bytes: Long): String? = when {
        name.substringAfterLast('.', "").lowercase() !in extensions -> refusal
        bytes > maxBytes -> "That file is over the 20 MB limit."
        else -> null
    }
}

private const val TWENTY_MB: Long = 20L * 1024 * 1024

/**
 * Choosing and storing the setup screen's PDFs.
 *
 * A host seam rather than repository calls, because neither half belongs to
 * this service: the picker is a desktop file dialog and the upload goes
 * straight to S3. The hub only learns what came back, and stores that.
 *
 * Null on the view model leaves those sections read-only, which is what a host
 * that has not wired storage should get — a picker that opens onto an upload
 * that cannot happen is worse than no picker.
 */
interface AgreementFiles {

    /**
     * Empty when the user cancelled. Refusals are reported through [onRefused].
     *
     * [multiple] is false for the terms document: there is one of it, and a
     * dialog that lets three be chosen for a single slot invites the question
     * of which one won.
     */
    suspend fun pick(
        purpose: SetupUpload = SetupUpload.Agreement,
        multiple: Boolean = true,
        onRefused: (String) -> Unit,
    ): List<PickedAgreementFile>

    /**
     * Uploads one picked file.
     *
     * [caption] is the description typed against the row; it rides the
     * attachment contract's `caption` field.
     */
    suspend fun upload(
        file: PickedAgreementFile,
        caption: String,
        purpose: SetupUpload = SetupUpload.Agreement,
    ): ZillitResult<AgreementDocument>
}
