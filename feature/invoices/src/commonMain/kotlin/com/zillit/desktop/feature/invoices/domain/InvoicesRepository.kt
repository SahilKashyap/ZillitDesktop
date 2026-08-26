package com.zillit.desktop.feature.invoices.domain

import com.zillit.desktop.core.common.ZillitResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Which of the tool's reads a socket announcement invalidates.
 *
 * [Rows] is every invoice lifecycle event — the open list and any open detail
 * refetch. [Reference] is the slower-moving margin: vendors, approval tiers
 * and the settings document, reloaded together because that is one
 * `loadReference` on this side and separate refetch keys on the web.
 */
enum class InvoiceRefresh { Rows, Reference }

/** The `GET /` filters this port uses. Empty/blank values are not sent. */
data class InvoiceQuery(
    val statuses: List<InvoiceStatus> = emptyList(),
    val departmentId: String? = null,
    val search: String? = null,
    val perPage: Int = DEFAULT_PER_PAGE,
) {
    companion object {
        const val DEFAULT_PER_PAGE = 200
    }
}

/** What the department's Upload Invoice flow sends (`invoiceUploadPayload.js`). */
data class DepartmentUpload(
    val type: UploadType,
    val fileName: String,
    val attachment: InvoiceAttachment?,
    val extraction: InvoiceExtraction?,
    val departmentId: String?,
    val projectCurrency: String,
)

/** What the accountant's Enter Invoice form sends (`EnterInvoiceModal.jsx`). */
data class EnteredInvoice(
    val attachment: InvoiceAttachment,
    val invoiceNumber: String,
    val vendorId: String,
    val description: String,
    val grossAmount: Double,
    val invoiceDateMs: Long,
    val dueDateMs: Long,
    val effectiveDateMs: Long?,
    val payMethod: PayMethod,
    val currency: String,
    val netAmount: Double? = null,
    val taxAmount: Double? = null,
    val companyId: String? = null,
    val bankId: String? = null,
    val episode: String? = null,
    val departmentId: String? = null,
    val poNumber: String? = null,
    val uploadId: String? = null,
)

/** Everything the screens ask the invoices service (and its two neighbours) for. */
interface InvoicesRepository {
    /**
     * Socket announcements that what is on screen is stale — another client's
     * upload, decision or payment, answered with a refetch rather than an
     * in-place patch (the web's `ah:invoice:*` refetch pattern). Defaulted
     * empty for tests and hosts without a socket.
     */
    val refreshes: Flow<InvoiceRefresh> get() = emptyFlow()

    /** `GET /` with filters. */
    suspend fun list(query: InvoiceQuery): ZillitResult<List<Invoice>>

    /** `GET /approval` — rows the caller is an approver on. */
    suspend fun approvalQueue(): ZillitResult<List<Invoice>>

    /** `GET /my` — rows the caller created. */
    suspend fun mine(): ZillitResult<List<Invoice>>

    /** `GET /:id` — the full record; list rows omit linked POs and attachments. */
    suspend fun invoice(id: String): ZillitResult<Invoice>

    suspend fun createFromUpload(upload: DepartmentUpload): ZillitResult<Invoice?>

    suspend fun createEntered(entered: EnteredInvoice): ZillitResult<Invoice?>

    /** `PATCH /:id { status, approval_status }` — the override transitions. */
    suspend fun patchStatus(id: String, status: InvoiceStatus, approvalStatus: ApprovalStatus): ZillitResult<Invoice?>

    suspend fun delete(id: String): ZillitResult<Unit>

    suspend fun approve(id: String, tierNumber: Int, totalTiers: Int): ZillitResult<Invoice?>

    suspend fun reject(id: String, reason: String): ZillitResult<Invoice?>

    suspend fun chase(id: String): ZillitResult<Unit>

    /** Newest first. */
    suspend fun history(id: String): ZillitResult<List<HistoryEntry>>

    /** `POST /upload` — OCR over an already-stored attachment. Fails often; never fatal. */
    suspend fun extract(attachment: InvoiceAttachment): ZillitResult<InvoiceExtraction>

    suspend fun settings(): ZillitResult<InvoiceSettings>

    /** Account Hub host: `approval-tiers?module=invoices`. */
    suspend fun approvalTiers(): ZillitResult<List<ApprovalTierConfig>>

    /** Account Hub host: `vendors?perPage=500`. */
    suspend fun vendors(): ZillitResult<List<Vendor>>

    /** Account Hub host: `bank-accounts?entity_type=production`. */
    suspend fun bankAccounts(): ZillitResult<List<BankAccount>>
}

/**
 * The host's file seams: the OS picker, S3 up (project keys, direct PUT),
 * signed fetch down, and save-to-Downloads-and-open for PDFs.
 */
interface InvoiceFiles {
    /** Empty = cancelled. */
    suspend fun pick(): List<PickedInvoiceFile>

    suspend fun upload(file: PickedInvoiceFile): ZillitResult<InvoiceAttachment>

    suspend fun fetch(attachment: InvoiceAttachment): ZillitResult<ByteArray>

    suspend fun saveAndOpen(name: String, bytes: ByteArray): ZillitResult<Unit>
}
