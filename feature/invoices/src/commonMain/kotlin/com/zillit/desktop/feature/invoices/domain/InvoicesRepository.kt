package com.zillit.desktop.feature.invoices.domain

import com.zillit.desktop.core.common.ZillitError
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

/** PDF or Excel, as the web's export menu offers. */
enum class InvoiceExportFormat(val wire: String, val label: String, val extension: String) {
    Pdf("pdf", "Export PDF", "pdf"),
    Excel("xlsx", "Export Excel", "xlsx"),
}

/** What can be exported as a file — the web's two export menus. */
enum class InvoiceExport(val path: String, val fileStem: String) {
    Register("export", "invoice-register"),
    Accruals("accruals/export", "accruals"),
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
@Suppress("TooManyFunctions") // One suspend fun per server operation; see detekt.yml.
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

    /**
     * Moves an invoice out of pre-approval and into the approval chain.
     *
     * These five are the pre-approval queue's, defaulted so a host or a test
     * double without them still satisfies the interface.
     */
    suspend fun sendToApproval(id: String): ZillitResult<Unit> = ZillitResult.Success(Unit)

    /** Holds it for query: a reason, and the notes that explain it. */
    suspend fun hold(id: String, reason: HoldReason, notes: String): ZillitResult<Unit> = ZillitResult.Success(Unit)

    /** Lifts a hold, putting it back in the queue. */
    suspend fun release(id: String): ZillitResult<Unit> = ZillitResult.Success(Unit)

    /** A senior accountant's override — past the rule that is blocking it. */
    suspend fun override(id: String): ZillitResult<Unit> = ZillitResult.Success(Unit)

    /** Takes the purchase order off an invoice, leaving it unmatched. */
    suspend fun unmatch(id: String): ZillitResult<Unit> = ZillitResult.Success(Unit)

    /**
     * Invoices that have reached the ledger — `GET /invoices/posted`.
     *
     * Its own route rather than a status filter on the register: the server
     * decides what counts as posted, and it answers the paged envelope.
     */
    suspend fun postedInvoices(): ZillitResult<List<Invoice>> = ZillitResult.Success(emptyList())

    // -- payment runs, sales invoices, and the entry stage -------------------
    // Defaulted, like the reads above, so a host or a test double without them
    // still satisfies the interface.

    /** The batches waiting to be paid — `GET /invoices/active-runs`. */
    suspend fun paymentRuns(): ZillitResult<List<PaymentRun>> = ZillitResult.Success(emptyList())

    /** Builds a run from the chosen invoices, paid one way. */
    suspend fun createPaymentRun(
        name: String,
        number: String,
        payMethod: PayMethod,
        invoiceIds: List<String>,
    ): ZillitResult<Unit> = ZillitResult.Success(Unit)

    suspend fun approvePaymentRun(id: String): ZillitResult<Unit> = ZillitResult.Success(Unit)

    suspend fun rejectPaymentRun(id: String, reason: String): ZillitResult<Unit> = ZillitResult.Success(Unit)

    suspend fun deletePaymentRun(id: String): ZillitResult<Unit> = ZillitResult.Success(Unit)

    /** Money owed to the production — `GET /invoices/sales-invoices`. */
    suspend fun salesInvoices(): ZillitResult<List<SalesInvoice>> = ZillitResult.Success(emptyList())

    suspend fun createSalesInvoice(invoice: SalesInvoice): ZillitResult<Unit> = ZillitResult.Success(Unit)

    /** Sends it to the client. */
    suspend fun sendSalesInvoice(id: String): ZillitResult<Unit> = ZillitResult.Success(Unit)

    suspend fun markSalesInvoicePaid(id: String): ZillitResult<Unit> = ZillitResult.Success(Unit)

    suspend fun deleteSalesInvoice(id: String): ZillitResult<Unit> = ZillitResult.Success(Unit)

    /** Posts an entered invoice to the ledger — the entry stage's last act. */
    suspend fun postInvoice(id: String): ZillitResult<Unit> = ZillitResult.Success(Unit)

    /** Sends it back for another decision instead. */
    suspend fun returnToApproval(id: String): ZillitResult<Unit> = ZillitResult.Success(Unit)

    /** Wires and faster payments leave the queue by being marked paid together. */
    suspend fun markPaid(ids: List<String>): ZillitResult<Unit> = ZillitResult.Success(Unit)

    /** Parks an entered invoice for a second pair of eyes — `status: under_review`. */
    suspend fun markUnderReview(id: String): ZillitResult<Unit> = ZillitResult.Success(Unit)

    /** Hands an invoice to a member of the accounts team, with the reason on the record. */
    suspend fun assign(id: String, userId: String, reason: String): ZillitResult<Unit> = ZillitResult.Success(Unit)

    /** What is committed but not yet invoiced — `GET /invoices/accruals`. */
    suspend fun accruals(): ZillitResult<List<Accrual>> = ZillitResult.Success(emptyList())

    /** Asks the server to work the accruals out again from the current orders and invoices. */
    suspend fun regenerateAccruals(): ZillitResult<Unit> = ZillitResult.Success(Unit)

    /** Credit notes and disputes — `GET /invoices/credit-notes`. */
    suspend fun creditNotes(): ZillitResult<List<CreditNote>> = ZillitResult.Success(emptyList())

    /** Applies a pending credit note against what is owed. */
    suspend fun applyCreditNote(id: String): ZillitResult<Unit> = ZillitResult.Success(Unit)

    /** Raises a dispute on it instead. */
    suspend fun disputeCreditNote(id: String): ZillitResult<Unit> = ZillitResult.Success(Unit)

    /** Spend by department and vendor — `GET /invoices/analytics`. */
    suspend fun analytics(): ZillitResult<InvoiceAnalytics> = ZillitResult.Success(InvoiceAnalytics())

    /**
     * The accountant's dashboard, computed and formatted by the server.
     *
     * These four are defaulted to an empty answer so a host or a test double
     * that has no dashboard still satisfies the interface; the real repository
     * answers every one.
     */
    suspend fun overview(): ZillitResult<InvoiceOverview> = ZillitResult.Success(InvoiceOverview())

    /** Invoices the server thinks have been seen before. */
    suspend fun duplicates(): ZillitResult<List<DuplicateFlag>> = ZillitResult.Success(emptyList())

    /** Clears a flag: not a duplicate after all. */
    suspend fun dismissDuplicate(flagId: String): ZillitResult<Unit> = ZillitResult.Success(Unit)

    /** Keeps the flag and marks it a real duplicate. */
    suspend fun confirmDuplicate(flagId: String): ZillitResult<Unit> = ZillitResult.Success(Unit)

    suspend fun settings(): ZillitResult<InvoiceSettings>

    // -- the Settings page ---------------------------------------------------

    /**
     * The whole settings document plus the module's assignment rules — what
     * the Settings page edits. The same GET as [settings], read in full.
     */
    suspend fun setup(): ZillitResult<InvoiceSetupBundle> = ZillitResult.Success(InvoiceSetupBundle())

    /** Replaces `team_members`; the web persists this one the moment a member is added or edited. */
    suspend fun saveTeam(rows: List<InvoiceTeamRow>): ZillitResult<Unit> = ZillitResult.Success(Unit)

    suspend fun saveAlerts(alerts: Set<String>): ZillitResult<Unit> = ZillitResult.Success(Unit)

    suspend fun saveRunAuthorisation(levels: List<RunAuthLevel>): ZillitResult<Unit> = ZillitResult.Success(Unit)

    /** Account Hub host: `assignment-rules`, module `invoices`. */
    suspend fun createRule(rule: InvoiceAssignmentRule): ZillitResult<InvoiceAssignmentRule> =
        ZillitResult.Success(rule.copy(persisted = true))

    suspend fun updateRule(rule: InvoiceAssignmentRule): ZillitResult<InvoiceAssignmentRule> =
        ZillitResult.Success(rule)

    suspend fun deleteRule(id: String): ZillitResult<Unit> = ZillitResult.Success(Unit)

    /** Account Hub host: the chart's postable lines, for the rules' nominal picker. */
    suspend fun nominalCodes(): ZillitResult<List<InvoiceNominal>> = ZillitResult.Success(emptyList())

    // -- PO matching ---------------------------------------------------------

    /** Orders this invoice could be matched to: the vendor's, and the reader's own. */
    suspend fun poSuggestions(id: String, vendorId: String?): ZillitResult<PoSuggestions> =
        ZillitResult.Success(PoSuggestions())

    /** Links an order to the invoice — the web's `match`. */
    suspend fun match(id: String, suggestion: PoSuggestion): ZillitResult<Unit> = ZillitResult.Success(Unit)

    /** The orders already linked, with their own figures — what the review compares against. */
    suspend fun linkedPos(id: String): ZillitResult<List<LinkedPoDetail>> = ZillitResult.Success(emptyList())

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

    /**
     * A binary export — the register or the accruals, as PDF or Excel.
     *
     * Here rather than on the repository because `ApiClient` speaks envelopes
     * and these two routes answer a file; the host does the raw POST.
     */
    suspend fun export(export: InvoiceExport, format: InvoiceExportFormat): ZillitResult<ByteArray> =
        ZillitResult.Failure(ZillitError.Unknown("No exporter wired"))
}
