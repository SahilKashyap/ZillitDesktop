package com.zillit.desktop.feature.accounthub.domain

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str

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
 * Decides both what may be chosen and where it is stored. Agreements take PDFs
 * only because those ride the signing flow, which takes nothing else; the
 * terms document is distributed by the service as it is, so it takes Word
 * files too, at the web's 10 MB; a budget is read and thrown away, so it takes
 * whatever the parser reads.
 */
enum class SetupUpload(
    val extensions: Set<String>,
    val maxBytes: Long,
    private val refusalKey: String,
    /** The size refusal — the web's wording where the web has one. */
    private val tooLargeKey: String,
) {
    /** One of the production's standard agreements. */
    Agreement(
        extensions = setOf("pdf"),
        maxBytes = TWENTY_MB,
        refusalKey = S.desktop_hub_only_pdf_files_can_be_attached_they_have_to_go,
        tooLargeKey = OVER_TWENTY,
    ),

    /** The terms and conditions issued with every purchase order — the web's `validateTermsFile`. */
    PurchaseOrderTerms(
        extensions = setOf("pdf", "doc", "docx"),
        maxBytes = TEN_MB,
        refusalKey = S.desktop_hub_only_pdf_doc_or_docx_files_are_accepted,
        tooLargeKey = S.desktop_hub_file_must_be_10mb_or_smaller,
    ),

    /**
     * Paperwork attached to a purchase order: a quote, a signed copy, a
     * delivery note.
     *
     * Wider than the terms document on purpose — the web's picker accepts
     * any image plus office documents and text — because this is evidence
     * rather than a document anyone signs, and a photograph of a signed
     * delivery note is the most common one.
     */
    PurchaseOrderAttachment(
        extensions = setOf("pdf", "doc", "docx", "xls", "xlsx", "csv", "txt", "png", "jpg", "jpeg", "heic", "webp"),
        maxBytes = TWENTY_MB,
        refusalKey = S.desktop_hub_attach_a_pdf_an_office_document_a_text_file_or,
        tooLargeKey = OVER_TWENTY,
    ),

    /** A budget file to parse. Read once and never stored as a document. */
    BudgetImport(
        extensions = setOf("pdf", "xlsx", "xls", "csv"),
        maxBytes = TWENTY_MB,
        refusalKey = S.desktop_hub_a_budget_has_to_be_a_pdf_an_excel_file,
        tooLargeKey = OVER_TWENTY,
    ),
    ;

    val refusal: String get() = str(refusalKey)
    val tooLarge: String get() = str(tooLargeKey)

    /** Why [name] at [bytes] cannot be used for this, or null when it can. */
    fun refuse(name: String, bytes: Long): String? = when {
        name.substringAfterLast('.', "").lowercase() !in extensions -> refusal
        bytes > maxBytes -> tooLarge
        else -> null
    }
}

private const val TWENTY_MB: Long = 20L * 1024 * 1024
private const val TEN_MB: Long = 10L * 1024 * 1024
private const val OVER_TWENTY = S.desktop_hub_that_file_is_over_the_20_mb_limit

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

    /** Whether [adopt] works here — a host that cannot take dropped bytes offers no drop zone. */
    val acceptsDrops: Boolean get() = false

    /**
     * A file dragged onto the window instead of chosen in the picker — the
     * web's drop zone. The same rules as [pick] apply, refusals included.
     * Null when it was refused or the host does not take drops.
     */
    fun adopt(
        name: String,
        bytes: ByteArray,
        purpose: SetupUpload,
        onRefused: (String) -> Unit,
    ): PickedAgreementFile? = null
}
