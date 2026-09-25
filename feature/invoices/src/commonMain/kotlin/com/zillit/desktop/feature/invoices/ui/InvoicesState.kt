package com.zillit.desktop.feature.invoices.ui

import com.zillit.desktop.feature.invoices.domain.CurrencyRates
import com.zillit.desktop.feature.invoices.domain.InvoiceExportFormat
import com.zillit.desktop.feature.invoices.domain.InvoiceExport
import com.zillit.desktop.feature.invoices.domain.ApprovalStatus
import com.zillit.desktop.feature.invoices.domain.ApprovalTierConfig
import com.zillit.desktop.feature.invoices.domain.BankAccount
import com.zillit.desktop.feature.invoices.domain.DuplicateFlag
import com.zillit.desktop.feature.invoices.domain.InvoiceOverview
import com.zillit.desktop.feature.invoices.domain.HistoryEntry
import androidx.compose.ui.graphics.vector.ImageVector
import com.zillit.desktop.core.common.orDash
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.invoices.domain.Accrual
import com.zillit.desktop.feature.invoices.domain.AccrualFilter
import com.zillit.desktop.feature.invoices.domain.Accruals
import com.zillit.desktop.feature.invoices.domain.CreditNote
import com.zillit.desktop.feature.invoices.domain.CreditNoteFilter
import com.zillit.desktop.feature.invoices.domain.CreditNotes
import com.zillit.desktop.feature.invoices.domain.DateWindow
import com.zillit.desktop.feature.invoices.domain.OpenItemRow
import com.zillit.desktop.feature.invoices.domain.LineEdit
import com.zillit.desktop.feature.invoices.domain.AssignmentReason
import com.zillit.desktop.feature.invoices.domain.EntryFilter
import com.zillit.desktop.feature.invoices.domain.EntrySort
import com.zillit.desktop.feature.invoices.domain.HoldReason
import com.zillit.desktop.feature.invoices.domain.InvoiceAlert
import com.zillit.desktop.feature.invoices.domain.InvoiceAssignmentRule
import com.zillit.desktop.feature.invoices.domain.InvoiceTeamRow
import com.zillit.desktop.feature.invoices.domain.PoSuggestion
import com.zillit.desktop.feature.invoices.domain.PoPills
import com.zillit.desktop.feature.invoices.domain.PurchaseOrderRecord
import com.zillit.desktop.feature.invoices.domain.InvoiceAssignee
import com.zillit.desktop.feature.invoices.domain.canAccessEntryRow
import com.zillit.desktop.feature.invoices.domain.InvoiceAnalytics
import com.zillit.desktop.feature.invoices.domain.Invoice
import com.zillit.desktop.feature.invoices.domain.InvoiceAttachment
import com.zillit.desktop.feature.invoices.domain.AmountSplit
import com.zillit.desktop.feature.invoices.domain.CatalogueCurrency
import com.zillit.desktop.feature.invoices.domain.InboxTriage
import com.zillit.desktop.feature.invoices.domain.InvoiceFormat
import com.zillit.desktop.feature.invoices.domain.InvoiceStatus
import com.zillit.desktop.feature.invoices.domain.InvoiceViewer
import com.zillit.desktop.feature.invoices.domain.PayMethod
import com.zillit.desktop.feature.invoices.domain.PaymentGroup
import com.zillit.desktop.feature.invoices.domain.BulkBatch
import com.zillit.desktop.feature.invoices.domain.BulkRow
import com.zillit.desktop.feature.invoices.domain.BulkUploads
import com.zillit.desktop.feature.invoices.domain.Company
import com.zillit.desktop.feature.invoices.domain.ServerBatch
import com.zillit.desktop.feature.invoices.domain.PaymentRun
import com.zillit.desktop.feature.invoices.domain.PeriodLock
import com.zillit.desktop.feature.invoices.domain.TaxType
import com.zillit.desktop.feature.invoices.domain.PaymentRunDetail
import com.zillit.desktop.feature.invoices.domain.RunApprovalDecision
import com.zillit.desktop.feature.invoices.domain.RunAuthLevel
import com.zillit.desktop.feature.invoices.domain.PaymentRuns
import com.zillit.desktop.feature.invoices.domain.PaymentTab
import com.zillit.desktop.feature.invoices.domain.SalesInvoice
import com.zillit.desktop.feature.invoices.domain.TrackingSet
import com.zillit.desktop.feature.invoices.domain.PickedInvoiceFile
import com.zillit.desktop.feature.invoices.domain.Vendor
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str

/**
 * The department view's tabs — the web's `TABS` plus the right-hand
 * "Payment Run Approval" (`DepartmentInvoiceModule.jsx:78-93, 1055`).
 */
enum class DepartmentTab(val id: String, private val labelKey: String, private val emptyKey: String) {
    ApprovalQueue("all", S.ah_approval_queue, S.desktop_inv_no_awaiting_your_approval),
    MyDepartment("dept", S.intradepartment, S.desktop_inv_none_in_your_department),
    MyInvoices("my", S.desktop_my_invoices, S.desktop_inv_none_uploaded_yet),

    /** The bulk uploads being extracted — not a list of invoices. */
    Uploads("uploads", S.desktop_inv_ongoing_uploads, S.desktop_inv_uploads_empty_hint),

    /** The payment runs waiting on this reader's signature — only for someone on the run chain. */
    RunApproval("runApproval", S.desktop_inv_payment_run_approval, S.ah_payment_runs_empty_subtitle),
    ;

    val label: String get() = str(labelKey)
    val emptyText: String get() = str(emptyKey)

    /** The `level_1` the service files this tab's rows under (`constants.js:176-186`); null for none. */
    val badgeKey: String?
        get() = when (this) {
            ApprovalQueue -> "invoice_approval_queue"
            MyInvoices -> "my_invoices"
            RunApproval -> "payment_runs"
            MyDepartment, Uploads -> null
        }

    /**
     * The `level_1` a row on this tab is chipped and read under — the web's
     * `rowChipLevel1` / `readScope`: My Invoices its own, runs theirs, and
     * every other tab the approval queue's, the only one the server files
     * rows visible there under (`DepartmentInvoiceModule.jsx:802-804, 1257-1265`).
     */
    val rowBadgeKey: String
        get() = when (this) {
            MyInvoices -> "my_invoices"
            RunApproval -> "payment_runs"
            ApprovalQueue, MyDepartment, Uploads -> "invoice_approval_queue"
        }

    companion object {
        /** The tab a `?tab=` names — the web's `VALID_TABS`; anything else is the Approval Queue. */
        fun fromId(id: String?): DepartmentTab = entries.firstOrNull { it.id == id } ?: ApprovalQueue
    }
}

/** Client-side filter on `approval_status`. */
enum class QuickFilter(private val labelKey: String) {
    All(S.all),
    Pending(S.pending),
    Approved(S.approved),
    Rejected(S.rejected),
    ;

    val label: String get() = str(labelKey)

    fun keeps(invoice: Invoice): Boolean = when (this) {
        All -> true
        Pending -> invoice.approvalStatus == ApprovalStatus.Pending
        Approved -> invoice.approvalStatus == ApprovalStatus.Approved
        Rejected -> invoice.approvalStatus == ApprovalStatus.Rejected
    }
}

/** A heading in the accountant's sidebar — the web's `NAV_SECTIONS`. */
enum class InvoiceNavGroup(private val labelKey: String?) {
    /** The first group has no heading on the web either. */
    Start(null),
    ReceiveAndMatch(S.desktop_inv_nav_receive_and_match),
    ApproveAndPay(S.desktop_inv_nav_approve_and_pay),
    VendorsAndCreditors(S.desktop_inv_nav_vendors_and_creditors),
    SalesAndManagement(S.desktop_inv_nav_sales_and_management),
    ;

    val label: String? get() = labelKey?.let { str(it) }
}

/**
 * The accountant's sidebar — every row the web has, in its order, under its
 * headings, with its ids. Every one of them opens a page.
 */
enum class AccountantPage(
    val id: String,
    private val labelKey: String,
    val group: InvoiceNavGroup,
    /** The row's icon, matched to the web's own per item. */
    val icon: ImageVector,
    /** Senior accountants only — the web's `senior: true` on Settings. */
    val seniorOnly: Boolean = false,
) {
    Overview("overview", S.ah_overview, InvoiceNavGroup.Start, ZillitIcons.Grid),
    Inbox("inbox", S.desktop_invoice_inbox, InvoiceNavGroup.ReceiveAndMatch, ZillitIcons.Mail),
    Register("register", S.desktop_invoice_register, InvoiceNavGroup.ReceiveAndMatch, ZillitIcons.File),
    Matching("matching", S.desktop_invoices_pre_approval, InvoiceNavGroup.ReceiveAndMatch, ZillitIcons.Receipt),
    ApprovalQueue("approval", S.ah_approval_queue, InvoiceNavGroup.ApproveAndPay, ZillitIcons.Shield),
    Entry("process", S.desktop_invoice_entry, InvoiceNavGroup.ApproveAndPay, ZillitIcons.Edit),
    Payments("payments", S.ah_payment_runs_btn, InvoiceNavGroup.ApproveAndPay, ZillitIcons.Wallet),
    Posted("posted", S.desktop_posted_invoices, InvoiceNavGroup.ApproveAndPay, ZillitIcons.Check),
    Credits("credits", S.desktop_credit_notes, InvoiceNavGroup.ApproveAndPay, ZillitIcons.ArrowLeft),
    Creditors("creditors", S.desktop_creditors_control, InvoiceNavGroup.VendorsAndCreditors, ZillitIcons.Bank),
    Vendors("suppliers", S.ah_vendors, InvoiceNavGroup.VendorsAndCreditors, ZillitIcons.Users),
    Sales("sales", S.desktop_sales_invoices, InvoiceNavGroup.SalesAndManagement, ZillitIcons.CreditCard),
    Accruals("accruals", S.desktop_accruals, InvoiceNavGroup.SalesAndManagement, ZillitIcons.Ledger),
    Analytics("analytics", S.analytics, InvoiceNavGroup.SalesAndManagement, ZillitIcons.BarChart),
    /** Empty on purpose: the web shows its own "coming soon" here. */
    Reports("reports", S.reports, InvoiceNavGroup.SalesAndManagement, ZillitIcons.Search),
    Settings("settings", S.settings, InvoiceNavGroup.SalesAndManagement, ZillitIcons.Settings, seniorOnly = true),
    ;

    val label: String get() = str(labelKey)

    /**
     * The page's segment in the web's URL — `/invoices/<segment>`. The row id
     * everywhere but Entry, whose row is `process` and whose route is `entry`.
     */
    val segment: String get() = if (this == Entry) "entry" else id

    /** The `level_1` the service files this page's rows under (`constants.js:176-186`); null for none. */
    val badgeKey: String?
        get() = when (this) {
            Inbox -> "invoice_inbox"
            Register -> "invoice_register"
            Matching -> "invoice_matching"
            ApprovalQueue -> "invoice_approval_queue"
            Entry -> "invoice_entry"
            Payments -> "payment_runs"
            Credits -> "credit_notes"
            Sales -> "sales_invoices"
            else -> null
        }

    /**
     * The kicker above the page title — the web's `PageHeader` eyebrow.
     *
     * It names the stage rather than the screen ("Enter", "Pay", "Match"), so
     * the header reads as a place in the lifecycle.
     */
    val eyebrow: String
        get() = when (this) {
            Overview -> str(S.desktop_inv_eyebrow_invoices_ap)
            Inbox -> str(S.desktop_receive)
            Register, Posted -> str(S.ah_invoices)
            Matching -> str(S.desktop_match)
            ApprovalQueue -> str(S.approve)
            Entry -> str(S.desktop_enter)
            Payments -> str(S.desktop_pay)
            Credits -> str(S.desktop_credits)
            Creditors -> str(S.desktop_creditors)
            Vendors -> str(S.ah_vendors)
            Sales -> str(S.desktop_sales)
            Accruals -> str(S.desktop_accruals)
            Analytics -> str(S.analytics)
            Reports -> str(S.reports)
            Settings -> str(S.desktop_configure)
        }

    /** The heading the web prints for this page, which is not always [label]. */
    val heading: String
        get() = when (this) {
            Overview -> str(S.ah_overview)
            Credits -> str(S.desktop_credit_notes_and_disputes)
            Posted -> str(S.desktop_posted_invoices)
            Settings -> str(S.desktop_invoices_setup)
            else -> label
        }

    /** The sentence under the heading — the web's `description`, word for word. */
    val blurb: String
        get() = when (this) {
            Overview -> str(S.desktop_inv_blurb_overview)
            Inbox -> str(S.desktop_inv_blurb_inbox)
            Register -> str(S.desktop_inv_blurb_register)
            Matching -> str(S.desktop_inv_blurb_matching)
            ApprovalQueue -> str(S.desktop_inv_blurb_approval_queue)
            Entry -> str(S.desktop_inv_blurb_entry)
            Payments -> str(S.desktop_inv_blurb_payments)
            Posted -> str(S.desktop_inv_blurb_posted)
            Credits -> str(S.desktop_inv_blurb_credits)
            Creditors -> str(S.desktop_inv_blurb_creditors)
            Vendors -> str(S.desktop_inv_blurb_vendors)
            Sales -> str(S.desktop_inv_blurb_sales)
            Accruals -> str(S.desktop_inv_blurb_accruals)
            Analytics -> str(S.desktop_inv_blurb_analytics)
            Reports -> str(S.desktop_inv_blurb_reports)
            Settings -> str(S.desktop_inv_blurb_settings)
        }

    /** Whether this page is a list of invoices, which is what the shared table shows. */
    val isInvoiceList: Boolean
        get() = this in setOf(Register, Inbox, ApprovalQueue, Posted, Matching, Creditors, Entry, Payments, Vendors)

    companion object {
        /** Where a click on a pipeline stage goes — the web's `STAGE_ROUTE`. */
        fun forPipelineStage(stageId: String): AccountantPage = when (stageId) {
            "inbox" -> Inbox
            "matching" -> Matching
            "approval" -> ApprovalQueue
            "ready_to_pay" -> Payments
            "paid" -> Register
            else -> entries.firstOrNull { it.id == stageId } ?: Register
        }

        /** A route the server handed back (`/invoices/register`), or null when it names nothing here. */
        fun forHref(href: String): AccountantPage? {
            val segment = href.trim('/').substringAfterLast("invoices/", "").substringBefore('/')
            return forSegment(segment)
        }

        /**
         * The page a tool route opens — `/film-tools/invoices/<segment>`.
         *
         * The bare path, and any segment this module does not own, land on
         * Overview: the web redirects both `/` and `*` there
         * (`InvoicesModule.jsx`), so a stale bookmark still arrives somewhere.
         */
        fun forRoute(path: String): AccountantPage {
            val tail = path.substringAfter(ROUTE_ROOT, missingDelimiterValue = "")
            return forSegment(tail.trim('/').substringBefore('/')) ?: Overview
        }

        /**
         * The invoice a Posted route names — `/invoices/posted/<id>`, the web's
         * URL-driven read-only detail (`InvoicesModule.jsx:499-511`); null for
         * the bare list or any other page.
         */
        fun postedDetailId(path: String): String? {
            val segments = path.substringBefore('?').substringAfter(ROUTE_ROOT, missingDelimiterValue = "")
                .trim('/').split('/').filter { it.isNotBlank() }
            return segments.getOrNull(1)?.takeIf { segments.first() == Posted.segment }
        }

        /** The web's URL segment, or the row id — they differ only for Entry. */
        private fun forSegment(segment: String): AccountantPage? =
            entries.firstOrNull { it.segment == segment } ?: entries.firstOrNull { it.id == segment }

        private const val ROUTE_ROOT = "/invoices"

        fun visibleTo(viewer: InvoiceViewer): List<AccountantPage> =
            entries.filter { !it.seniorOnly || viewer.isSenior }
    }
}

/**
 * The hold dialog — the web's `HoldForQueryModal`.
 *
 * [invoices] is what will be held: one row, or everything selected. A reason
 * is required, and "Other" is refused without notes.
 */
data class HoldRequest(
    val invoices: List<Invoice>,
    val reason: HoldReason? = null,
    val notes: String = "",
    val busy: Boolean = false,
) {
    val isReady: Boolean get() = reason != null && !(reason.needsNotes() && notes.isBlank())
}

/**
 * The assign dialog — the web's "Assign N invoices" sheet.
 *
 * A reason is required, and "Other" is refused without words, so an
 * assignment always says on the record why it moved.
 */
data class AssignRequest(
    val invoiceIds: List<String>,
    val userId: String = "",
    val reason: AssignmentReason? = null,
    val notes: String = "",
    val busy: Boolean = false,
) {
    val isReady: Boolean
        get() = userId.isNotBlank() && reason != null && !(reason.needsNotes() && notes.isBlank())

    /** What goes on the record: the preset's own wording, or the words typed. */
    val reasonText: String get() = if (reason?.needsNotes() == true) notes.trim() else reason?.wire.orEmpty()
}

/**
 * The "process what is ticked" sheet, opened when the selection spans more
 * than one payment method.
 *
 * Each method leaves the queue its own way — BACs becomes runs, a wire is
 * marked paid, a cheque is printed — so a mixed selection cannot have one
 * button. [busy] names the method being worked so only its row spins.
 */
data class ProcessRequest(
    val invoices: List<Invoice>,
    /** The method code whose button is working — only its row says "Processing…". */
    val busy: String? = null,
) {
    /**
     * The method codes in the selection, in the order they first appear — the
     * web groups on the raw `payMethodCode`, so wire and faster are two cards
     * and a code this client does not know is a card of its own.
     */
    val codes: List<String> get() = invoices.map { it.payCode }.distinct()

    /** The known methods among [codes]. */
    val methods: List<PayMethod>
        get() = codes.mapNotNull { code -> PayMethod.entries.firstOrNull { it.wire == code } }

    fun idsFor(code: String): List<String> = invoices.filter { it.payCode == code }.map { it.id }

    fun countFor(code: String): Int = invoices.count { it.payCode == code }

    fun countFor(method: PayMethod): Int = countFor(method.wire)

    fun rowsFor(code: String): List<Invoice> = invoices.filter { it.payCode == code }
}

/**
 * One payment run opened from the Active Runs tab — the web's run detail:
 * what it pays, why it was turned down if it was, and the footer that
 * cancels, rejects or signs it.
 */
data class RunDetailView(
    val run: PaymentRun,
    val detail: PaymentRunDetail? = null,
    val loading: Boolean = true,
    val busy: Boolean = false,
    /** The "Cancel payment run?" confirmation is up. */
    val confirmCancel: Boolean = false,
) {
    /** The freshest copy of the run: the detail read once it is in, the row until then. */
    val shown: PaymentRun get() = detail?.run?.takeIf { it.id.isNotBlank() } ?: run
}

/** Turning a run down needs a reason — the web refuses an empty one. */
data class RunRejection(
    val run: PaymentRun,
    val reason: String = "",
    val busy: Boolean = false,
) {
    val isReady: Boolean get() = reason.isNotBlank()
}

/** The posted page's status filter — the web's three options. */
enum class PostedFilter(private val labelKey: String, val status: InvoiceStatus?) {
    All(S.all, null),
    ReadyToPay(S.desktop_ready_to_pay, InvoiceStatus.ReadyToPay),
    Paid(S.desktop_paid, InvoiceStatus.Paid),
    ;

    val label: String get() = str(labelKey)

    fun keeps(invoice: Invoice): Boolean = status == null || invoice.status == status
}

/** The register's status chips. Empty = every status. */
enum class RegisterChip(private val labelKey: String, val statuses: Set<InvoiceStatus>) {
    All(S.all, emptySet()),
    Inbox(S.inbox_text, setOf(InvoiceStatus.Inbox)),
    Matching(S.desktop_matching, setOf(InvoiceStatus.Matching)),
    Approval(S.ah_step_approval, setOf(InvoiceStatus.Approval)),
    /** `entry` exactly — the web's chip filter is an exact status match (`RegisterPage.jsx:378`). */
    Entry(S.desktop_entry, setOf(InvoiceStatus.Entry)),
    Ready(S.dd_csv_status_ready, setOf(InvoiceStatus.ReadyToPay)),
    Paid(S.desktop_paid, setOf(InvoiceStatus.Paid)),
    ;

    val label: String get() = str(labelKey)

    fun keeps(invoice: Invoice): Boolean = statuses.isEmpty() || invoice.status in statuses
}

/** Fetched attachment bytes; identity-compared so state diffs stay cheap. */
class AttachmentBytes(val bytes: ByteArray)

/** The detail dialog: always re-read from `GET /:id`, with its preview and sub-dialogs. */
data class InvoiceDetail(
    val invoice: Invoice,
    val loading: Boolean = true,
    val preview: AttachmentBytes? = null,
    val previewLoading: Boolean = false,
    val previewFailed: Boolean = false,
    /** User ids → names for approvers, creator, rejecter, history actors. */
    val names: Map<String, String> = emptyMap(),
    /** User ids → designations, the line under each name in the chain and the audit footer. */
    val designations: Map<String, String> = emptyMap(),
    val history: List<HistoryEntry>? = null,
    val historyOpen: Boolean = false,
    val historyLoading: Boolean = false,
    val rejecting: Boolean = false,
    val rejectReason: String = "",
    val acting: Boolean = false,
    val opening: Boolean = false,
    /**
     * False when opened from the Register: the web's register is a history
     * surface and passes its detail modal no approve, reject or override.
     */
    val decisions: Boolean = true,
    /**
     * Opened from Payment Runs on a wire or faster payment: the footer offers
     * Mark Paid, and nothing else decides (`PaymentsPage.jsx:2388-2406`).
     */
    val markPaid: Boolean = false,
) {
    fun nameOf(userId: String): String = names[userId] ?: userId.ifBlank { str(S.desktop_unknown) }
}

enum class EnterTab(val id: String, private val labelKey: String) {
    Upload("upload", S.ah_upload_invoice),
    Manual("manual", S.desktop_manual_entry),
    ;

    val label: String get() = str(labelKey)
}

/** A field of Enter Invoice that submit found missing — `validateManual`'s keys, in its order. */
enum class EnterField { Vendor, InvoiceNumber, InvoiceDate, EffectiveDate, Department, GrossAmount, Attachment }

/** The accountant's Enter Invoice form. Amounts are kept as typed. */
data class EnterInvoiceForm(
    val tab: EnterTab = EnterTab.Upload,
    val file: PickedInvoiceFile? = null,
    val attachment: InvoiceAttachment? = null,
    val uploading: Boolean = false,
    val vendorId: String = "",
    val vendorQuery: String = "",
    /**
     * A vendor picked by name that does not exist yet — `usePendingVendor`:
     * created on submit, just before the invoice, so an abandoned form
     * leaves no vendor behind.
     */
    val pendingVendorName: String? = null,
    val invoiceNumber: String = "",
    /** `YYYY-MM-DD` as typed. */
    val invoiceDate: String = "",
    val effectiveDate: String = "",
    val dueDate: String = "",
    val departmentId: String = "",
    val description: String = "",
    val gross: String = "",
    val net: String = "",
    val tax: String = "",
    /** Once the user types a gross, net/tax follow it rather than the other way round. */
    val grossEdited: Boolean = false,
    val currency: String = "",
    val payMethod: PayMethod = PayMethod.Bacs,
    val bankId: String = "",
    /** The legal entity (Production Setup → Companies); blank lets the server fill it from a PO. */
    val companyId: String = "",
    val episode: String = "",
    val poNumber: String = "",
    /** Already settled: the server takes it straight to ready-to-pay, never into the inbox. */
    val paid: Boolean = false,
    val mismatchAcknowledged: Boolean = false,
    val saving: Boolean = false,
    val error: String? = null,
    /** What submit found wrong, per field — every one at once, as the web lists them. */
    val errors: Map<EnterField, String> = emptyMap(),
    /** "Amounts don't match" is up, waiting on Create anyway or Go back. */
    val confirmSplit: Boolean = false,
) {
    val netValue: Double? get() = net.trim().replace(",", "").toDoubleOrNull()
    val taxValue: Double? get() = tax.trim().replace(",", "").toDoubleOrNull()
    val grossValue: Double? get() = gross.trim().replace(",", "").toDoubleOrNull()

    /** The Net / Tax / Gross trio as the shared split rules read it. */
    val amounts: AmountSplit get() = AmountSplit(net, tax, gross, grossAnchored = grossEdited)

    /**
     * Net or Tax typed (a blank one counting as nothing) and not adding up to
     * a positive Gross — `describeAmountSplit(...).mismatch`, shared with the
     * Inbox review.
     */
    val amountsMismatch: Boolean get() = InboxTriage.splitMismatch(amounts)

    val busy: Boolean get() = uploading || saving
}

data class InvoicesUiState(
    val viewer: InvoiceViewer = InvoiceViewer(),
    /** Unread notifications per `level_1` key — the sidebar's and tabs' red chips. */
    val unread: Map<String, Int> = emptyMap(),
    /** Unread per `level_1`, then per row id (`level_3`) — the per-row chips (`renderUnread`). */
    val unreadRows: Map<String, Map<String, Int>> = emptyMap(),
    val loading: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
    val projectCurrency: String = "GBP",
    /** The production's currencies and their rates, for the mixed-currency totals. */
    val rates: CurrencyRates = CurrencyRates(),
    // Department view.
    val departmentTab: DepartmentTab = DepartmentTab.ApprovalQueue,
    val quickFilter: QuickFilter = QuickFilter.All,
    // Accountant view.
    val page: AccountantPage = AccountantPage.Overview,
    /** The dashboard, and the duplicate flags beside it. */
    val overview: InvoiceOverview? = null,
    val overviewLoading: Boolean = false,
    val duplicates: List<DuplicateFlag> = emptyList(),
    val duplicatesLoading: Boolean = false,
    val registerChip: RegisterChip = RegisterChip.All,
    /** Department `_id`; null = every department. */
    val registerDepartment: String? = null,
    /** The Register's Date filter, on the invoice date — the web's "All dates" select. */
    val registerDate: DateWindow = DateWindow.All,
    /** Every department of the production, in the directory's (admin) order — the Register's filter. */
    val departmentOrder: List<String> = emptyList(),
    val postedFilter: PostedFilter = PostedFilter.All,
    /** The pre-approval queue's hold dialog, over the rows it will hold. */
    val holdFor: HoldRequest? = null,
    /** The list of keyboard shortcuts, opened with `?`. */
    val shortcutsOpen: Boolean = false,
    val analytics: InvoiceAnalytics? = null,
    val analyticsLoading: Boolean = false,
    val creditNotes: List<CreditNote> = emptyList(),
    val creditNotesLoading: Boolean = false,
    val creditNoteFilter: CreditNoteFilter = CreditNoteFilter.All,
    /** Credit Notes & Disputes' pickers, form, preview and dialogs. */
    val credit: CreditNotesUi = CreditNotesUi(),
    val accruals: List<Accrual> = emptyList(),
    val accrualsLoading: Boolean = false,
    val accrualFilter: AccrualFilter = AccrualFilter.All,
    /** Payment Runs: the open tab, and the batches themselves. */
    val paymentTab: PaymentTab = PaymentTab.OpenItems,
    /** Open Items' vendor groups the reader has shut; every group starts open, as on the web. */
    val collapsedGroups: Set<String> = emptySet(),
    val paymentRuns: List<PaymentRun> = emptyList(),
    /** The run authorisation chain from Settings — who signs a run at each tier. */
    val runAuth: List<RunAuthLevel> = emptyList(),
    /** Whether Settings gives anybody run access; false raises the web's "no authoriser" banner. */
    val hasRunAuthoriser: Boolean = true,
    /** Payment Runs' own ticks, paid wires, loaders and wire-confirmation dialog. */
    val pay: PaymentsUi = PaymentsUi(),
    /** Creditors Control's vendor, sort and ageing selects. */
    val creditors: CreditorsUi = CreditorsUi(),
    /** Posted's `total` from the endpoint — "Showing N of {total}" when the page is capped. */
    val postedTotal: Int = 0,
    /** The run open in its detail dialog. */
    val runDetail: RunDetailView? = null,
    val runDraft: ProcessRequest? = null,
    val rejectRun: RunRejection? = null,
    /** Sales invoices, and the one being written. */
    val salesInvoices: List<SalesInvoice> = emptyList(),
    val salesDraft: SalesInvoiceDraft? = null,
    /** A draft sales invoice waiting on "delete it?" — the web confirms first. */
    val confirmSalesDelete: SalesInvoice? = null,
    /** Sales Invoices' chips, preview, history and PDF. */
    val sales: SalesUi = SalesUi(),
    /** Vendors' chips and the vendor detail. */
    val vendorsPage: VendorsUi = VendorsUi(),
    /** Accruals' Sort and Dept selects, and the accrual detail. */
    val accrualsPage: AccrualsUi = AccrualsUi(),
    /** The Layers picker's tracking sets, for the credit-note and sales line grids. */
    val trackingSets: List<TrackingSet> = emptyList(),
    /** Invoice Entry: its own filter row, sort box and pay-method box. */
    val entryFilter: EntryFilter = EntryFilter.All,
    val entrySort: EntrySort = EntrySort.Default,
    val payFilter: PayMethod? = null,
    /** The accounts team an invoice can be handed to, and the open assign sheet. */
    val assignees: List<InvoiceAssignee> = emptyList(),
    val assignFor: AssignRequest? = null,
    /** User ids → names for the Assigned column. */
    val userNames: Map<String, String> = emptyMap(),
    val search: String = "",
    // Data.
    val invoices: List<Invoice> = emptyList(),
    val vendors: Map<String, Vendor> = emptyMap(),
    val banks: List<BankAccount> = emptyList(),
    val tierConfigs: List<ApprovalTierConfig> = emptyList(),
    val departmentNames: Map<String, String> = emptyMap(),
    // Dialogs and selections.
    val detail: InvoiceDetail? = null,
    val enter: EnterInvoiceForm? = null,
    val confirmDelete: Invoice? = null,
    val selected: Set<String> = emptySet(),
    val chased: Set<String> = emptySet(),
    /** The one invoice whose chase is in flight — the web allows one at a time (`chasingId`). */
    val chasing: String? = null,
    /** A linked purchase order open read-only over the review or the detail — the web's `LinkedPoViewer`. */
    val linkedPo: LinkedPoView? = null,
    /** Purchase orders already read this session, by id — the web's module-wide `poCache`. */
    val poSummaries: Map<String, PurchaseOrderRecord> = emptyMap(),
    /** The Settings page — loaded only when that row is open. */
    val setup: InvoiceSetupState = InvoiceSetupState(),
    /** The side-by-side PO review over a pre-approval row. */
    val review: ReviewOverlay? = null,
    /** The PO picker open on a pre-approval row, and what it found. */
    val poPicker: PoPicker? = null,
    /** Invoice Entry's coding screen, when one invoice is open in it. */
    val ledger: EntryLedger? = null,
    /** A record's query thread, open in its side panel. */
    val query: QueryView? = null,
    /** Quick Entry's form, while it is up. */
    val quickEntry: QuickEntryDraft? = null,
    /** The cost report's close boundary; documents dated inside it are read-only. */
    val periodLock: PeriodLock = PeriodLock(),
    /** Production Setup's companies and tax types — the ledger's selects. */
    val companies: List<Company> = emptyList(),
    /** The core currency catalogue — what a picked company's country resolves its currency through. */
    val currencyCatalogue: List<CatalogueCurrency> = emptyList(),
    val taxTypes: List<TaxType> = emptyList(),
    /** False until the tax types have been read, so the tax swap is not guessed at. */
    val taxTypesKnown: Boolean = false,
    /** Every active nominal on the chart — what decides whether a typed code is new. */
    val chart: Set<String> = emptySet(),
    /** The ledger's and Quick Entry's chart names and account tags — read when either first opens. */
    val entryRefs: EntryRefs = EntryRefs(),
    /** The Inbox page's half on screen: the queue, or the uploads being extracted. */
    val inboxTab: InboxTab = InboxTab.Queue,
    /** One inbox invoice open for review. */
    val inboxReview: InboxReview? = null,
    /** The rows bulk Process refused, and what each is missing. */
    val blockedProcess: List<BlockedEntry> = emptyList(),
    /** Why the last bulk Process failed — said beside its button, as the web's floating bar says it. */
    val inboxProcessError: String? = null,
    /** Files picked for a bulk upload, not yet sent. */
    val bulkPick: BulkPick? = null,
    /** The client's half of every batch this session started. */
    val bulkBatches: List<BulkBatch> = emptyList(),
    /** The server's half — what it is still extracting, for everyone. */
    val serverBatches: List<ServerBatch> = emptyList(),
    /** Batch ids the server has listed at least once; one that then disappears has finished. */
    val seenBatches: Set<String> = emptySet(),
) {
    /** Ongoing Uploads: both halves of every batch, newest first. */
    val uploadRows: List<BulkRow> get() = BulkUploads.rows(bulkBatches, serverBatches, seenBatches)

    /** A document dated inside the closed cost-report period — `isDateLocked`. */
    fun isLocked(invoice: Invoice): Boolean = periodLock.isLocked(invoice.effectiveDateMs)

    val isAccountant: Boolean get() = viewer.isAccountant

    /** Whether something over the page owns the keyboard, so the shortcuts stay out of its way. */
    val dialogOpen: Boolean
        get() = detail != null || enter != null || confirmDelete != null ||
            holdFor != null || runDraft != null || salesDraft != null || assignFor != null ||
            confirmSalesDelete != null || credit.form != null || credit.preview != null ||
            credit.history != null || credit.confirmDelete != null ||
            rejectRun != null || runDetail != null || review != null || linkedPo != null || setup.memberDraft != null ||
            ledger != null || query != null || quickEntry != null ||
            inboxReview != null || blockedProcess.isNotEmpty() || bulkPick != null ||
            setup.pickingForTier != null || setup.removingMember != null || setup.removingRule != null ||
            sales.preview != null || sales.history != null || sales.pdf != null ||
            vendorsPage.detail != null || accrualsPage.detailId != null || credit.viewing != null ||
            pay.wireAttachments != null

    fun vendorName(invoice: Invoice): String =
        vendors[invoice.vendorId]?.name?.ifBlank { null } ?: invoice.supplierName.ifBlank { str(S.desktop_unknown) }

    /** A department the directory has not got: an em dash, never its id. */
    fun departmentName(id: String): String = departmentNames[id] ?: id.orDash()

    /**
     * The Register's department filter: every department of the production in
     * the directory's order (`useDepartments`), not just the ones on screen;
     * the rows' own ids only while the directory has not answered.
     */
    val registerDepartmentOptions: List<String>
        get() = departmentOrder.ifEmpty { departmentOptions }

    /**
     * The vendor as the web's accountant tables print it: the directory's name,
     * then — on the Register only — the description up to its first "–", then
     * "Unknown" (`RegisterPage.jsx:197-200`, `MatchingPage.jsx:212`,
     * `ApprovalPage.jsx:450`).
     */
    fun pageVendorName(invoice: Invoice): String =
        vendors[invoice.vendorId]?.name?.ifBlank { null }
            ?: invoice.description.takeIf { page == AccountantPage.Register }
                ?.substringBefore('–')?.trim()?.ifBlank { null }
            ?: str(S.desktop_unknown)

    /** Department ids seen in the current rows, for the register filter. */
    val departmentOptions: List<String>
        get() = invoices.map { it.departmentId }.filter { it.isNotBlank() }.distinct().sortedBy { departmentName(it) }

    /** The rows after the client-side filters of the current page. */
    val shownInvoices: List<Invoice>
        get() {
            val needle = search.trim().lowercase()
            return invoices.filter { inv ->
                val inDepartment = registerDepartment == null || inv.departmentId == registerDepartment
                val byFilter = when {
                    !isAccountant -> quickFilter.keeps(inv)
                    page == AccountantPage.Register -> registerChip.keeps(inv) && inDepartment
                    page == AccountantPage.Posted -> postedFilter.keeps(inv) && inDepartment
                    else -> true
                }
                byFilter && (needle.isEmpty() || pageMatches(inv, needle))
            }.let { rows ->
                // Pre-approval lists what is waiting first and what is held
                // after it, as the web renders its two fetches (`MatchingPage.jsx:678-744`).
                if (isAccountant && page == AccountantPage.Matching) {
                    rows.sortedBy { it.status == InvoiceStatus.Held }
                } else {
                    rows
                }
            }
        }

    /**
     * The search box, page by page: the Register and Pre-approval read the
     * row's words as their web pages write them — the formatted gross, the
     * PO cell (and, on Pre-approval, the typed PO number) — every other page
     * the shared fields.
     */
    private fun pageMatches(inv: Invoice, needle: String): Boolean {
        if (!isAccountant || (page != AccountantPage.Register && page != AccountantPage.Matching)) {
            return matches(inv, needle)
        }
        val gross = InvoiceFormat.money(inv.grossAmount, inv.currency.ifBlank { projectCurrency })
        val fields = if (page == AccountantPage.Register) {
            // `po` is null for an urgent row (`RegisterPage.jsx:211-215`).
            val po = if (inv.isUrgentRaw) null else inv.linkedPoLabel ?: str(S.desktop_no_po)
            listOfNotNull(inv.displayNumber, pageVendorName(inv), inv.description, gross, po)
        } else {
            listOfNotNull(
                inv.displayNumber, pageVendorName(inv), inv.description, gross,
                PoPills.matchingLabel(inv), inv.poNumber,
            )
        }
        return fields.any { it.lowercase().contains(needle) }
    }

    /** The credit notes after the chips, the search box and the Date filter, in the Sort's order. */
    fun shownCreditNotes(nowMs: Long): List<CreditNote> = credit.sort.sort(
        creditNotes.filter { note ->
            creditNoteFilter.keeps(note) && CreditNotes.matches(note, search) &&
                credit.date.keeps(note.effectiveDateMs, nowMs)
        },
    )

    /** The project's currency first, then every one it has a rate for — Production Setup's list. */
    val currencyOptions: List<String>
        get() = (listOf(projectCurrency) + rates.rates.keys).filter { it.isNotBlank() }.distinct()

    /**
     * The accruals as the page shows them — the search (formatted amounts
     * too), the chip, the page's own Dept select and its Sort
     * (`AccrualsPage.jsx:354-384`).
     */
    val shownAccruals: List<Accrual>
        get() = Accruals.shown(
            rows = accruals,
            search = search,
            filter = accrualFilter,
            departmentId = accrualsPage.departmentId,
            sort = accrualsPage.sort,
            vendorName = { accrualVendorName(it) },
            money = { amount, currency -> InvoiceFormat.money(amount, currency.ifBlank { projectCurrency }) },
        )

    /** `vendorMap[a.vendor_id] || "Unknown"` — the vendor directory first, then any name the row carries. */
    fun accrualVendorName(accrual: Accrual): String =
        vendors[accrual.vendorId]?.name?.ifBlank { null } ?: accrual.vendorName.ifBlank { str(S.desktop_unknown) }

    /**
     * The entry queue as it is on screen: searched, filtered, then sorted.
     *
     * The pay-method filter compares the canonical code, never the label —
     * two spellings of Faster Payment share a label and must share a filter.
     */
    val entryRows: List<Invoice>
        get() {
            val needle = search.trim().lowercase()
            val kept = invoices.filter { invoice ->
                (needle.isEmpty() || matches(invoice, needle)) &&
                    entryFilter.keeps(invoice, viewer.userId) &&
                    (payFilter == null || invoice.payMethod == payFilter)
            }
            return when (entrySort) {
                EntrySort.Default -> kept
                EntrySort.AmountHighLow -> kept.sortedByDescending { it.grossAmount }
                EntrySort.AmountLowHigh -> kept.sortedBy { it.grossAmount }
                EntrySort.VendorAZ -> kept.sortedBy { vendorName(it).lowercase() }
            }
        }

    /** Whether this viewer may open the row at all; a locked row shows, greyed. */
    fun canAccessEntry(invoice: Invoice): Boolean =
        canAccessEntryRow(invoice, viewer.isSenior, viewer.userId)

    /** What select-all covers: the rows this viewer may act on, and none in a closed period. */
    val entrySelectableIds: List<String>
        get() = entryRows.filter { canAccessEntry(it) && !isLocked(it) }.map { it.id }

    /** The assignee's name for the Assigned column; blank id = nobody. */
    fun assigneeName(invoice: Invoice): String? = invoice.assignedTo.takeIf { it.isNotBlank() }?.let { id ->
        userNames[id] ?: assignees.firstOrNull { it.id == id }?.name ?: id
    }

    /**
     * The rows of the payment tab on screen: every open item, or one method's
     * queue. Never searched — the web's Payment Runs has no search box.
     */
    val paymentRows: List<Invoice>
        get() = when (paymentTab) {
            PaymentTab.Wires -> wireInvoices
            PaymentTab.Cheques -> chequeInvoices
            else -> invoices
        }

    /**
     * The count on a payment tab — the web badges Wires and Cheques only
     * (`PaymentsPage.jsx:1500-1516`); zero draws no badge.
     */
    fun paymentTabCount(tab: PaymentTab): Int = when (tab) {
        PaymentTab.Wires -> wireInvoices.size
        PaymentTab.Cheques -> chequeInvoices.size
        PaymentTab.Runs, PaymentTab.OpenItems -> 0
    }

    /** The Wires tab: wire and faster payment together, on the canonical code. */
    val wireInvoices: List<Invoice> get() = invoices.filter { it.payCode in PaymentRuns.WIRE_CODES }

    val chequeInvoices: List<Invoice> get() = invoices.filter { it.payCode == PayMethod.Cheque.wire }

    /** BACs only — a code this client does not know never joins a BACs run. */
    val bacsInvoices: List<Invoice> get() = invoices.filter { it.payCode == PayMethod.Bacs.wire }

    /**
     * Who a payment is to, as Payment Runs names them — the web's
     * `vendorMap[vendor_id] || "Unknown Vendor"`; also the `name` a new run is
     * sent with, so it must not fall back to anything the web would not.
     */
    fun payeeName(invoice: Invoice): String =
        vendors[invoice.vendorId]?.name?.ifBlank { null } ?: str(S.desktop_inv_unknown_vendor)

    /** One BACs run per vendor and currency, over the ticked open items: what "Create BACs Run" makes. */
    val bacsGroups: List<PaymentGroup>
        get() = PaymentRuns.groupByVendorCurrency(
            invoices = bacsInvoices.filter { it.id in pay.openItemsSelected },
            vendorName = { payeeName(it) },
            defaultCurrency = projectCurrency,
        )

    /** Every vendor group in the open items, for the tile that counts them. */
    val openItemGroups: List<PaymentGroup>
        get() = PaymentRuns.groupByVendorCurrency(invoices, { payeeName(it) }, projectCurrency)

    /** Open Items grouped by vendor and currency, each group open unless it was shut. */
    val openItemRows: List<OpenItemRow>
        get() = openItemGroups.flatMap { group ->
            val open = group.key !in collapsedGroups
            val items = if (open) group.invoices.map { OpenItemRow.Item(it) } else emptyList()
            listOf(OpenItemRow.Header(group, open)) + items
        }

    /** The open items behind Open Items' ticks. */
    val selectedPaymentRows: List<Invoice> get() = invoices.filter { it.id in pay.openItemsSelected }

    /** The one method code the ticked open items share, or null when mixed (or none). */
    val selectedPayCode: String?
        get() = selectedPaymentRows.map { it.payCode }.distinct().singleOrNull()

    /** One row's unread under [key] — the web's `getInvoiceTotalUnread(badges, level_1, id)`. */
    fun rowUnread(key: String, id: String): Int = unreadRows[key]?.get(id) ?: 0

    /**
     * The department's Payment Run Approval list: the runs whose next tier
     * this reader may sign now — `resolveRunApproval(...).canApprove`, which
     * also insists the run is pending (`DepartmentInvoiceModule.jsx:630-654`).
     */
    val runsAwaitingMe: List<PaymentRun> get() = paymentRuns.filter { runApproval(it).canApprove }

    /** Whether the reader may sign [run] now, and at which tier — the web's `resolveRunApproval`. */
    fun runApproval(run: PaymentRun): RunApprovalDecision =
        PaymentRuns.resolveApproval(runAuth, run.approvals, run.status, viewer.userId)

    /**
     * The web's "no authoriser" banner: once the settings have answered (or
     * failed), nobody has run access and the reader is not senior either
     * (`PaymentsPage.jsx:1021, 1528`).
     */
    val showNoRunAuthoriser: Boolean
        get() = pay.settingsLoaded && !viewer.hasSeniorDesignation && !hasRunAuthoriser

    /** The known method the ticked open items share, or null when mixed or not one this client knows. */
    val selectedPayMethod: PayMethod?
        get() = selectedPayCode?.let { code -> PayMethod.entries.firstOrNull { it.wire == code } }

    val matchingCount: Int get() = invoices.count { it.status == InvoiceStatus.Matching }
    val heldCount: Int get() = invoices.count { it.status == InvoiceStatus.Held }

    /**
     * The Approval Queue panel's pills: every row that is not `status`
     * approved is "awaiting" — rejected rows included — and the rest
     * "approved" (`ApprovalPage.jsx:505, 534-535`).
     */
    val awaitingCount: Int get() = invoices.count { !it.isApprovedStatus }
    val approvedCount: Int get() = invoices.count { it.isApprovedStatus }

    private fun matches(inv: Invoice, needle: String): Boolean =
        inv.displayNumber.lowercase().contains(needle) ||
            vendorName(inv).lowercase().contains(needle) ||
            inv.description.lowercase().contains(needle) ||
            inv.poLabel.orEmpty().lowercase().contains(needle) ||
            inv.grossAmount.toString().contains(needle) ||
            (isAccountant && page == AccountantPage.Inbox && inboxRowText(inv).any { it.lowercase().contains(needle) })

    /**
     * What the Inbox search reads beyond the shared fields — the row's words
     * as the web's `entryToRow` writes them (`InboxPage.jsx:324-326`): the
     * formatted gross ("£1,200.00"), the pay-method label, "Manual entry" for
     * a blank description and "No PO" for an unmatched row.
     */
    private fun inboxRowText(inv: Invoice): List<String> = listOf(
        InvoiceFormat.money(inv.grossAmount, inv.currency.ifBlank { projectCurrency }),
        inv.payMethod.label,
        if (inv.description.isBlank()) str(S.desktop_manual_entry) else "",
        inv.poLabel ?: str(S.desktop_no_po),
    )
}

sealed interface InvoicesEvent {
    data class SelectDepartmentTab(val tab: DepartmentTab) : InvoicesEvent
    data class SelectQuickFilter(val filter: QuickFilter) : InvoicesEvent
    data class SelectPage(val page: AccountantPage) : InvoicesEvent

    /**
     * The host's route — `/film-tools/invoices/<page>` — asked for on every
     * entry and route change, so re-entry from the Account Hub lands on the
     * page it names (the bare path lands on Overview).
     */
    data class OpenRoute(val path: String) : InvoicesEvent

    /** A duplicate flag: kept and marked real, or cleared. */
    data class ConfirmDuplicate(val flagId: String) : InvoicesEvent

    data class DismissDuplicate(val flagId: String) : InvoicesEvent
    data class SelectRegisterChip(val chip: RegisterChip) : InvoicesEvent
    data class SelectRegisterDepartment(val departmentId: String?) : InvoicesEvent
    data class SelectRegisterDate(val window: DateWindow) : InvoicesEvent

    /** Open Items: shut or open one vendor group, or tick all of it (or none, when all are). */
    data class ToggleGroupOpen(val key: String) : InvoicesEvent
    data class SelectGroup(val ids: List<String>) : InvoicesEvent

    /** The Wires tab's per-row Mark Paid. */
    data class MarkPaidOne(val invoice: Invoice) : InvoicesEvent
    data class SelectPostedFilter(val filter: PostedFilter) : InvoicesEvent
    data class SelectCreditNoteFilter(val filter: CreditNoteFilter) : InvoicesEvent
    data class SelectAccrualFilter(val filter: AccrualFilter) : InvoicesEvent

    // -- payment runs -------------------------------------------------------

    data class SelectPaymentTab(val tab: PaymentTab) : InvoicesEvent

    /** Ticks or clears every open item at once. */
    data object ToggleSelectAll : InvoicesEvent

    data object CancelPaymentRun : InvoicesEvent

    /** A run's detail: opened from its row, closed, and signed at the next tier. */
    data class OpenRun(val run: PaymentRun) : InvoicesEvent
    data object CloseRun : InvoicesEvent
    data class ApproveRun(val run: PaymentRun) : InvoicesEvent

    /** Cancelling a run returns its invoices to open items — asked first, as the web does. */
    data object RequestCancelRun : InvoicesEvent
    data object ConfirmCancelRun : InvoicesEvent
    data object KeepRun : InvoicesEvent

    /** Turning a run down: the sheet, what is typed in it, and the decision. */
    data class StartRejectRun(val run: PaymentRun) : InvoicesEvent
    data class RejectRunReasonChanged(val reason: String) : InvoicesEvent
    data object ConfirmRejectRun : InvoicesEvent
    data object CancelRejectRun : InvoicesEvent

    /**
     * Acts on the ticked open items of one method code (the Process sheet's
     * card); null = whatever they all share (the header button).
     */
    data class ProcessSelected(val code: String?) : InvoicesEvent {
        constructor(method: PayMethod) : this(method.wire)
    }

    // -- the entry stage ----------------------------------------------------

    /** Posts an entered invoice to the ledger, or sends it back for a decision. */
    data class PostInvoice(val invoice: Invoice) : InvoicesEvent
    data class ReturnToApproval(val invoice: Invoice) : InvoicesEvent

    data class SelectEntryFilter(val filter: EntryFilter) : InvoicesEvent
    data class SelectEntrySort(val sort: EntrySort) : InvoicesEvent

    /** Null = every pay method. */
    data class SelectPayFilter(val method: PayMethod?) : InvoicesEvent

    /** Parks everything ticked for a second look. */
    data object ReviewSelected : InvoicesEvent

    /** The assign sheet over the ticked rows. */
    data object StartAssign : InvoicesEvent
    data class EditAssign(val request: AssignRequest) : InvoicesEvent
    data object ConfirmAssign : InvoicesEvent
    data object CancelAssign : InvoicesEvent

    // -- sales invoices -----------------------------------------------------

    data object StartSalesInvoice : InvoicesEvent
    data class EditSalesInvoice(val draft: SalesInvoiceDraft) : InvoicesEvent
    data object ConfirmSalesInvoice : InvoicesEvent
    data object CancelSalesInvoice : InvoicesEvent
    data class SendSalesInvoice(val invoice: SalesInvoice) : InvoicesEvent
    data class DeleteSalesInvoice(val invoice: SalesInvoice) : InvoicesEvent
    data object ConfirmDeleteSales : InvoicesEvent
    data object CancelDeleteSales : InvoicesEvent
    data class EditSalesLines(val edit: LineEdit) : InvoicesEvent

    /** Works the accruals out again from the orders and invoices as they stand. */
    data object RegenerateAccruals : InvoicesEvent

    /** The row's own button: applies a pending note, or disputes it. */
    data class ActOnCreditNote(val note: CreditNote) : InvoicesEvent

    // -- the pre-approval queue -------------------------------------------

    /** Sends one invoice, or everything selected when [invoice] is null. */
    data class SendToApproval(val invoice: Invoice?) : InvoicesEvent

    /** Opens the hold dialog over one invoice, or over the selection. */
    data class StartHold(val invoice: Invoice?) : InvoicesEvent

    data class HoldReasonChanged(val reason: HoldReason) : InvoicesEvent
    data class HoldNotesChanged(val notes: String) : InvoicesEvent
    data object ConfirmHold : InvoicesEvent
    data object CancelHold : InvoicesEvent

    data class Release(val invoice: Invoice) : InvoicesEvent
    data class Unmatch(val invoice: Invoice) : InvoicesEvent
    data class Search(val query: String) : InvoicesEvent
    data object Refresh : InvoicesEvent
    data object DismissError : InvoicesEvent

    /** The web's export menu: the register or the accruals, as PDF or Excel. */
    data class Export(val export: InvoiceExport, val format: InvoiceExportFormat) : InvoicesEvent

    /** The keyboard shortcuts sheet — `?` opens it. */
    data object OpenShortcuts : InvoicesEvent
    data object CloseShortcuts : InvoicesEvent

    // -- the Settings page --------------------------------------------------

    data class ToggleAlert(val alert: InvoiceAlert) : InvoicesEvent
    data class SaveSetupSection(val section: InvoiceSetupSection) : InvoicesEvent

    data object AddTeamMember : InvoicesEvent
    data class EditTeamMember(val row: InvoiceTeamRow) : InvoicesEvent
    data class ChangeTeamMemberDraft(val draft: TeamMemberDraft) : InvoicesEvent
    data object CommitTeamMember : InvoicesEvent
    data object CancelTeamMember : InvoicesEvent
    data class RequestRemoveTeamMember(val userId: String) : InvoicesEvent
    data object ConfirmRemoveTeamMember : InvoicesEvent
    data object CancelRemoveTeamMember : InvoicesEvent

    /** A new sign-off level at this position; every level is renumbered after. */
    data class AddRunAuthLevel(val index: Int) : InvoicesEvent
    data class RemoveRunAuthLevel(val tier: Int) : InvoicesEvent
    data class OpenRunAuthPicker(val tier: Int) : InvoicesEvent
    data class SearchRunAuthPicker(val query: String) : InvoicesEvent
    data class PickRunAuthUser(val userId: String) : InvoicesEvent
    data object CloseRunAuthPicker : InvoicesEvent
    data class RemoveRunAuthUser(val tier: Int, val userId: String) : InvoicesEvent

    data object AddAssignmentRule : InvoicesEvent
    data class EditAssignmentRule(val rule: InvoiceAssignmentRule) : InvoicesEvent
    data class RequestRemoveRule(val id: String) : InvoicesEvent
    data object ConfirmRemoveRule : InvoicesEvent
    data object CancelRemoveRule : InvoicesEvent

    // -- the PO review overlay ----------------------------------------------

    /** Opens the side-by-side review over one pre-approval row. */
    data class OpenReview(val invoice: Invoice) : InvoicesEvent
    data object CloseReview : InvoicesEvent

    /** The review's own History button (`POMatchingOverlay.jsx:464-470`). */
    data object ReviewShowHistory : InvoicesEvent
    data object ReviewHideHistory : InvoicesEvent

    /**
     * A linked-PO card: shows that order in the PDF pane (in the review) and
     * opens it read-only over everything — the web's `LinkedPoViewer`.
     */
    data class OpenLinkedPo(val poId: String, val poNumber: String, val index: Int? = null) : InvoicesEvent
    data object CloseLinkedPo : InvoicesEvent

    /** The read-only order's "View PDF". */
    data object OpenLinkedPoPdf : InvoicesEvent

    /** Which linked order the middle pane shows. */
    data class SelectReviewPo(val index: Int) : InvoicesEvent

    /** Confirms the match and sends the invoice on, or overrides the chain. */
    data object ReviewSendToApproval : InvoicesEvent
    data object ReviewOverride : InvoicesEvent

    /** The PO picker on a pre-approval row: opened, chosen from, closed. */
    data class OpenPoSuggestions(val invoice: Invoice) : InvoicesEvent
    data class MatchToPo(val suggestion: PoSuggestion) : InvoicesEvent
    data object ClosePoSuggestions : InvoicesEvent

    data class Open(val invoice: Invoice) : InvoicesEvent
    data object CloseDetail : InvoicesEvent
    data object OpenAttachment : InvoicesEvent
    data object ShowHistory : InvoicesEvent
    data object HideHistory : InvoicesEvent

    data class Approve(val invoice: Invoice) : InvoicesEvent
    data object StartReject : InvoicesEvent
    data class RejectReasonChanged(val reason: String) : InvoicesEvent
    data object ConfirmReject : InvoicesEvent
    data object CancelReject : InvoicesEvent
    data class Override(val invoice: Invoice) : InvoicesEvent
    data class OverrideAndPay(val invoice: Invoice) : InvoicesEvent
    data class Chase(val invoice: Invoice) : InvoicesEvent
    data class ToggleSelect(val id: String) : InvoicesEvent
    data object ClearSelection : InvoicesEvent
    data object ApproveSelected : InvoicesEvent

    data class RequestDelete(val invoice: Invoice) : InvoicesEvent
    data object ConfirmDelete : InvoicesEvent
    data object CancelDelete : InvoicesEvent

    /** Opens the OS picker for a bulk upload — the department's Upload Invoices. */
    data object UploadInvoice : InvoicesEvent

    /** Accountant: the Enter Invoice dialog. */
    data object OpenEnter : InvoicesEvent
    data object CloseEnter : InvoicesEvent
    data class SelectEnterTab(val tab: EnterTab) : InvoicesEvent
    data object EnterPickFile : InvoicesEvent
    data class EnterChanged(val form: EnterInvoiceForm) : InvoicesEvent
    data class EnterNetChanged(val value: String) : InvoicesEvent
    data class EnterTaxChanged(val value: String) : InvoicesEvent
    data class EnterGrossChanged(val value: String) : InvoicesEvent
    data object SubmitEnter : InvoicesEvent
}

sealed interface InvoicesEffect {
    data class Notice(val text: String) : InvoicesEffect

    /** Leave the module for another tool's route — the web's `<Navigate>` redirects. */
    data class Navigate(val path: String) : InvoicesEffect
}
