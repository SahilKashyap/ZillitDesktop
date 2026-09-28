package com.zillit.desktop.feature.invoices.domain

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.common.map
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
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
    /** Null sends no `perPage` at all — the department board's `list({ department_id })` sends none. */
    val perPage: Int? = DEFAULT_PER_PAGE,
) {
    companion object {
        const val DEFAULT_PER_PAGE = 200
    }
}

/** PDF or Excel, as the web's export menu offers. */
enum class InvoiceExportFormat(val wire: String, private val labelKey: String, val extension: String) {
    Pdf("pdf", S.recce_export_pdf, "pdf"),
    Excel("xlsx", S.desktop_dm_export_excel, "xlsx"),
    ;

    val label: String get() = str(labelKey)

}

/** What can be exported as a file — the web's two export menus. */
/**
 * A register the server renders as a file. [type] is sent beside the format
 * where one route serves two registers — credit notes and disputes.
 */
enum class InvoiceExport(val path: String, val fileStem: String, val type: String? = null) {
    Register("export", "invoice-register"),
    Accruals("accruals/export", "accruals"),
    CreditNotes("credit-notes/export", "credit-notes", "credit_note"),
    Disputes("credit-notes/export", "disputes", "dispute"),
}

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
    /** Already paid: sent as `paid: true` instead of the inbox status — never both. */
    val paid: Boolean = false,
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

    suspend fun createEntered(entered: EnteredInvoice): ZillitResult<Invoice?>

    suspend fun delete(id: String): ZillitResult<Unit>

    suspend fun approve(id: String, tierNumber: Int, totalTiers: Int): ZillitResult<Invoice?>

    suspend fun reject(id: String, reason: String): ZillitResult<Invoice?>

    suspend fun chase(id: String): ZillitResult<Unit>

    /** Newest first. */
    suspend fun history(id: String): ZillitResult<List<HistoryEntry>>


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

    /**
     * Skips the rest of the approval chain — `POST /:id/override`.
     *
     * The one route every override path takes (Override, Override & Pay, the
     * review's Confirm & override): the server's permission gate, its bell
     * fan-out and its auto-assignment all hang off it, not off a status PATCH.
     */
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

    /** One run with the invoices it pays — `GET /invoices/active-runs/:id`. */
    suspend fun paymentRun(id: String): ZillitResult<PaymentRunDetail> =
        ZillitResult.Failure(ZillitError.Unknown("No run detail wired"))

    /**
     * Signs one tier of a run's authorisation chain — the web sends the tier
     * it is signing and how many there are (`resolveRunApproval`).
     */
    suspend fun approvePaymentRun(id: String, tierNumber: Int, totalTiers: Int): ZillitResult<Unit> =
        ZillitResult.Success(Unit)

    suspend fun rejectPaymentRun(id: String, reason: String): ZillitResult<Unit> = ZillitResult.Success(Unit)

    suspend fun deletePaymentRun(id: String): ZillitResult<Unit> = ZillitResult.Success(Unit)

    // -- the same decisions, answering the server's own word ----------------
    //
    // [approve], [reject], [delete], [approvePaymentRun] and [rejectPaymentRun]
    // again, answering the envelope's `message` key (null when it sent none) —
    // what the web toasts through `showApiSuccess` (`DepartmentInvoiceModule.jsx`
    // approveOne / rejectOne / handleDelete / handleApproveRun / handleRejectRun).
    // The defaults delegate, so a fake that implements only the plain call
    // still answers, with no message.

    suspend fun approveWithMessage(id: String, tierNumber: Int, totalTiers: Int): ZillitResult<String?> =
        approve(id, tierNumber, totalTiers).map { null }

    suspend fun rejectWithMessage(id: String, reason: String): ZillitResult<String?> =
        reject(id, reason).map { null }

    suspend fun deleteWithMessage(id: String): ZillitResult<String?> = delete(id).map { null }

    suspend fun approvePaymentRunWithMessage(id: String, tierNumber: Int, totalTiers: Int): ZillitResult<String?> =
        approvePaymentRun(id, tierNumber, totalTiers).map { null }

    suspend fun rejectPaymentRunWithMessage(id: String, reason: String): ZillitResult<String?> =
        rejectPaymentRun(id, reason).map { null }

    // -- Payment Runs' other writes, answering the server's word -------------
    //
    // `PaymentsPage.jsx` toasts `res.message` after creating a run, cancelling
    // one and marking paid, and lands on the run it just created (`json.data.id`).
    // Defaulted to the plain calls, so a fake with only those still answers.

    /** [createPaymentRun], answering the new run's id and the envelope's `message`. */
    suspend fun createPaymentRunWithMessage(
        name: String,
        number: String,
        payMethod: PayMethod,
        invoiceIds: List<String>,
    ): ZillitResult<RunCreated> = createPaymentRun(name, number, payMethod, invoiceIds).map { RunCreated() }

    suspend fun deletePaymentRunWithMessage(id: String): ZillitResult<String?> = deletePaymentRun(id).map { null }

    suspend fun markPaidWithMessage(ids: List<String>): ZillitResult<String?> = markPaid(ids).map { null }

    /**
     * Files a bank confirmation on a paid wire —
     * `POST /invoices/:id/wire-attachments` — answering the invoice's list as
     * it now stands (`invoices.js:252-254`).
     */
    suspend fun uploadWireAttachment(
        id: String,
        attachment: InvoiceAttachment,
        mimeType: String,
        size: Long,
    ): ZillitResult<WireAttachmentsChange> =
        ZillitResult.Failure(ZillitError.Unknown("No wire attachments wired"))

    /** `DELETE /invoices/:id/wire-attachments/:media` (`invoices.js:262-264`). */
    suspend fun removeWireAttachment(id: String, media: String): ZillitResult<WireAttachmentsChange> =
        ZillitResult.Failure(ZillitError.Unknown("No wire attachments wired"))

    /** [postedInvoices] with the endpoint's `total`, for "Showing N of {total}". */
    suspend fun postedLedger(): ZillitResult<PostedLedger> = postedInvoices().map { PostedLedger(it) }

    /** Money owed to the production — `GET /invoices/sales-invoices`. */
    suspend fun salesInvoices(): ZillitResult<List<SalesInvoice>> = ZillitResult.Success(emptyList())

    suspend fun createSalesInvoice(invoice: SalesInvoiceWrite): ZillitResult<Unit> = ZillitResult.Success(Unit)

    /** Sends it to the client. */
    suspend fun sendSalesInvoice(id: String): ZillitResult<Unit> = ZillitResult.Success(Unit)

    suspend fun markSalesInvoicePaid(id: String): ZillitResult<Unit> = ZillitResult.Success(Unit)

    suspend fun deleteSalesInvoice(id: String): ZillitResult<Unit> = ZillitResult.Success(Unit)

    // -- sales invoices, credit notes, vendors, accruals and settings: the
    // reads the previews make, and the writes again answering the envelope's
    // `message` key (null when it sent none) — what the web toasts through
    // `showApiSuccess`. The message variants delegate by default, so a fake
    // that implements only the plain call still answers, with no message.

    /** `GET /sales-invoices/:id` — the preview's full record (`SalesPage.jsx:958`). */
    suspend fun salesInvoice(id: String): ZillitResult<SalesInvoice> =
        ZillitResult.Failure(ZillitError.Unknown("No sales invoice read wired"))

    /** `PATCH /sales-invoices/:id` — Update Invoice on a draft (`SalesPage.jsx:443-444`). */
    suspend fun updateSalesInvoice(id: String, invoice: SalesInvoiceWrite): ZillitResult<Unit> =
        ZillitResult.Success(Unit)

    /** `GET /sales-invoices/:id/history`, newest first — the preview's History panel. */
    suspend fun salesInvoiceHistory(id: String): ZillitResult<List<HistoryEntry>> = ZillitResult.Success(emptyList())

    suspend fun createSalesInvoiceWithMessage(invoice: SalesInvoiceWrite): ZillitResult<String?> =
        createSalesInvoice(invoice).map { null }

    suspend fun updateSalesInvoiceWithMessage(id: String, invoice: SalesInvoiceWrite): ZillitResult<String?> =
        updateSalesInvoice(id, invoice).map { null }

    /** Mark Sent — `POST /sales-invoices/:id/send`. */
    suspend fun sendSalesInvoiceWithMessage(id: String): ZillitResult<String?> = sendSalesInvoice(id).map { null }

    suspend fun deleteSalesInvoiceWithMessage(id: String): ZillitResult<String?> = deleteSalesInvoice(id).map { null }

    suspend fun createCreditNoteWithMessage(write: CreditNoteWrite): ZillitResult<String?> =
        createCreditNote(write).map { null }

    suspend fun updateCreditNoteWithMessage(id: String, write: CreditNoteWrite): ZillitResult<String?> =
        updateCreditNote(id, write).map { null }

    suspend fun deleteCreditNoteWithMessage(id: String): ZillitResult<String?> = deleteCreditNote(id).map { null }

    suspend fun applyCreditNoteWithMessage(id: String): ZillitResult<String?> = applyCreditNote(id).map { null }

    suspend fun saveTeamWithMessage(rows: List<InvoiceTeamRow>): ZillitResult<String?> = saveTeam(rows).map { null }

    suspend fun saveAlertsWithMessage(alerts: Set<String>): ZillitResult<String?> = saveAlerts(alerts).map { null }

    suspend fun saveRunAuthorisationWithMessage(levels: List<RunAuthLevel>): ZillitResult<String?> =
        saveRunAuthorisation(levels).map { null }

    /** `GET /invoices/accruals/:id` — `{accrual, po, vendor, invoices}` (`AccrualsPage.jsx:94`). */
    suspend fun accrualDetail(id: String): ZillitResult<AccrualDetail> =
        ZillitResult.Failure(ZillitError.Unknown("No accrual detail wired"))

    /** `GET /api/v2/purchase-orders?per_page=200` — the vendor history's orders (`SuppliersPage.jsx:321`). */
    suspend fun vendorPurchaseOrders(): ZillitResult<List<VendorPo>> = ZillitResult.Success(emptyList())

    /** The Layers picker's sets — `GET /account-hub/tracking-sets?include_nodes=true&active_only=true`. */
    suspend fun trackingSets(): ZillitResult<List<TrackingSet>> = ZillitResult.Success(emptyList())

    /** The core `preset/isd-codes` countries — the sales address's Country select (`useIsdCodes`). */
    suspend fun countries(): ZillitResult<List<ClientCountry>> = ZillitResult.Success(emptyList())

    /**
     * `GET /v2/preset/geonames/postalcode/{country}/{postcode}` — the city and
     * state a postcode sits in (`lib/postcodeAutofill.js`). A confirmed empty
     * answer is blanks; a refusal is a failure, which leaves the fields alone.
     */
    suspend fun postcodePlace(countryCode: String, postcode: String): ZillitResult<PostcodeMatch> =
        ZillitResult.Failure(ZillitError.Unknown("No postcode lookup wired"))

    /** Posts an entered invoice to the ledger — the entry stage's last act. */
    suspend fun postInvoice(id: String): ZillitResult<Unit> = ZillitResult.Success(Unit)

    /** Sends it back for another decision instead. */
    suspend fun returnToApproval(id: String): ZillitResult<Unit> = ZillitResult.Success(Unit)

    /** Wires and faster payments leave the queue by being marked paid together. */
    suspend fun markPaid(ids: List<String>): ZillitResult<Unit> = ZillitResult.Success(Unit)

    /** Parks an entered invoice for a second pair of eyes — `status: under_review`. */
    suspend fun markUnderReview(id: String): ZillitResult<Unit> = ZillitResult.Success(Unit)

    // -- the ledger view (Invoice Entry) ----------------------------------------

    /** Save, Post's first half and Submit for Review — `PATCH /invoices/:id`. */
    suspend fun saveEntry(id: String, write: EntryWrite): ZillitResult<Unit> = ZillitResult.Success(Unit)

    /** Quick Entry — `POST /invoices` straight to ready-to-pay. */
    suspend fun quickEntry(entry: QuickEntry): ZillitResult<Unit> = ZillitResult.Success(Unit)

    // -- the ledger's writes, answering the server's own word -----------------
    //
    // [saveEntry], [postInvoice], [returnToApproval] and [quickEntry] again,
    // answering the envelope's `message` key (null when it sent none) — what
    // `EntryPage.jsx` toasts through `showApiSuccess(res)`. The defaults
    // delegate, so a fake with only the plain calls still answers.

    suspend fun saveEntryWithMessage(id: String, write: EntryWrite): ZillitResult<String?> =
        saveEntry(id, write).map { null }

    suspend fun postInvoiceWithMessage(id: String): ZillitResult<String?> = postInvoice(id).map { null }

    suspend fun returnToApprovalWithMessage(id: String): ZillitResult<String?> =
        returnToApproval(id).map { null }

    suspend fun quickEntryWithMessage(entry: QuickEntry): ZillitResult<String?> = quickEntry(entry).map { null }

    /**
     * Invoice ids whose query thread another participant opened or answered
     * — `query:opened` / `query:replied` on an `invoice` entity, which the web
     * turns into `ah:query:entity:invoice:<id>` for an open `QueryPanel`.
     */
    val queryUpdates: Flow<String> get() = emptyFlow()

    /** Production Setup's companies and tax types, and the boundary it stores — the hub's project settings. */
    suspend fun projectSettings(): ZillitResult<InvoiceProjectSettings> = ZillitResult.Success(InvoiceProjectSettings())

    /**
     * The cost report's close boundary, read the way the web's `useCrLock`
     * reads it: the lock route and the settings document, the later winning.
     */
    suspend fun periodLock(): ZillitResult<PeriodLock> = ZillitResult.Success(PeriodLock())

    /** Every active code on the chart, for wrapping a new nominal as `[[code]]`. */
    suspend fun chartCodes(): ZillitResult<Set<String>> = ZillitResult.Success(emptySet())

    // -- the inbox ------------------------------------------------------------------

    /**
     * `POST /invoices/process` — inbox entries on to pre-approval: ids alone
     * for the queue's bulk Process, ids plus the review's edits for Accept.
     */
    suspend fun process(ids: List<String>, accept: InboxAccept? = null): ZillitResult<Unit> =
        ZillitResult.Success(Unit)

    /** A note stored on a matched order's link — the review's Match Notes. */
    suspend fun matchNote(id: String, poId: String, note: String): ZillitResult<Unit> = ZillitResult.Success(Unit)

    /** One file of a bulk upload, handed over under its batch id. */
    suspend fun bulkUpload(
        batchId: String,
        attachment: InvoiceAttachment,
        size: Long,
        paid: Boolean,
    ): ZillitResult<Unit> = ZillitResult.Success(Unit)

    /** Every bulk batch the server is still tracking — Ongoing Uploads' server half. */
    suspend fun bulkBatches(): ZillitResult<List<ServerBatch>> = ZillitResult.Success(emptyList())

    /**
     * Every `invoice:bulk_upload_progress` frame as a batch snapshot — the
     * one delivery of a finished batch's last counts, because the list drops
     * the batch the moment it completes (`recordProgressFrame`).
     */
    val bulkProgress: Flow<ServerBatch> get() = emptyFlow()

    /**
     * [process], answering the server's own success `message` (a message key)
     * when it sent one, so the toast can say what the web's does. Defaulted
     * onto [process] for doubles that only override that.
     */
    suspend fun processWithMessage(ids: List<String>, accept: InboxAccept? = null): ZillitResult<String?> =
        process(ids, accept).map { null }

    /** [createEntered], answering the server's success `message` key when it sent one. */
    suspend fun createEnteredWithMessage(entered: EnteredInvoice): ZillitResult<String?> =
        createEntered(entered).map { null }

    /**
     * `POST {hub}/vendors {name}` — the vendor quick-add the Inbox review and
     * Enter Invoice make at submit time (`usePendingVendor.resolveVendorId`).
     */
    suspend fun createVendor(name: String): ZillitResult<Vendor> =
        ZillitResult.Failure(ZillitError.Unknown("No vendor create wired"))

    /** The core `preset/currencies` catalogue — what a company's country resolves its currency through. */
    suspend fun currencyCatalogue(): ZillitResult<List<CatalogueCurrency>> = ZillitResult.Success(emptyList())

    // -- queries ------------------------------------------------------------------

    /** The query thread on an invoice — empty when nobody has asked anything yet. */
    suspend fun queryThread(invoiceId: String): ZillitResult<QueryThread> = ZillitResult.Success(QueryThread())

    /** The first message, which opens the thread on the invoice. */
    suspend fun openQuery(invoiceId: String, text: String): ZillitResult<QueryThread> =
        ZillitResult.Success(QueryThread())

    /** A reply on a thread that already exists. */
    suspend fun addQuery(threadId: String, text: String): ZillitResult<QueryThread> =
        ZillitResult.Success(QueryThread())

    /** Hands an invoice to a member of the accounts team, with the reason on the record. */
    suspend fun assign(id: String, userId: String, reason: String): ZillitResult<Unit> = ZillitResult.Success(Unit)

    /** What is committed but not yet invoiced — `GET /invoices/accruals`. */
    suspend fun accruals(): ZillitResult<List<Accrual>> = ZillitResult.Success(emptyList())

    /** Asks the server to work the accruals out again from the current orders and invoices. */
    suspend fun regenerateAccruals(): ZillitResult<Unit> = ZillitResult.Success(Unit)

    /** Credit notes and disputes — `GET /invoices/credit-notes`. */
    suspend fun creditNotes(): ZillitResult<List<CreditNote>> = ZillitResult.Success(emptyList())

    /**
     * Applies a pending credit note against what is owed — and resolves a
     * disputed one: the web's Apply and Resolve both call `/apply`
     * (`CreditsPage.jsx`). `/dispute` would raise the dispute again.
     */
    suspend fun applyCreditNote(id: String): ZillitResult<Unit> = ZillitResult.Success(Unit)

    /** Raises a dispute on it — `POST /credit-notes/:id/dispute`. */
    suspend fun disputeCreditNote(id: String): ZillitResult<Unit> = ZillitResult.Success(Unit)

    /** `POST /credit-notes` — a credit note (pending) or a dispute (disputed). */
    suspend fun createCreditNote(write: CreditNoteWrite): ZillitResult<Unit> = ZillitResult.Success(Unit)

    /** `PATCH /credit-notes/:id` — the whole form again, as the web sends it. */
    suspend fun updateCreditNote(id: String, write: CreditNoteWrite): ZillitResult<Unit> = ZillitResult.Success(Unit)

    suspend fun deleteCreditNote(id: String): ZillitResult<Unit> = ZillitResult.Success(Unit)

    /** `GET /credit-notes/:id/history`, newest first. */
    suspend fun creditNoteHistory(id: String): ZillitResult<List<HistoryEntry>> = ZillitResult.Success(emptyList())

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

    /**
     * One purchase order, read-only — `GET /api/v2/purchase-orders/:id`, the
     * web's `purchaseOrdersApi.getOne` behind `LinkedPoViewer` and the
     * linked-PO cards (`useLinkedPoSummaries`).
     */
    suspend fun purchaseOrder(id: String): ZillitResult<PurchaseOrderRecord> =
        ZillitResult.Failure(ZillitError.Unknown("No purchase-order read wired"))

    /**
     * Renders a purchase order's PDF and answers where it was stored —
     * `POST /api/v2/purchase-orders/:id/pdf`, the web's `usePoPdfPreview`. The
     * file is fetched like any other stored attachment.
     */
    suspend fun purchaseOrderPdf(id: String): ZillitResult<InvoiceAttachment> =
        ZillitResult.Failure(ZillitError.Unknown("No purchase-order PDF wired"))

    // The pre-approval and queue decisions again, answering the envelope's
    // `message` for the toast (`showApiSuccess`); defaulted to the plain call
    // with no message, as the decisions above are.

    suspend fun overrideWithMessage(id: String): ZillitResult<String?> = override(id).map { null }

    suspend fun sendToApprovalWithMessage(id: String): ZillitResult<String?> = sendToApproval(id).map { null }

    suspend fun releaseWithMessage(id: String): ZillitResult<String?> = release(id).map { null }

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

    /**
     * `GET /invoices/sales-invoices/:id/pdf` — the server's rendering of a
     * sales invoice (`salesInvoicesApi.getPdfBlobUrl`). A file, not an
     * envelope, so the host makes the raw call as it does for [export].
     */
    suspend fun salesInvoicePdf(id: String): ZillitResult<ByteArray> =
        ZillitResult.Failure(ZillitError.Unknown("No PDF reader wired"))
}
