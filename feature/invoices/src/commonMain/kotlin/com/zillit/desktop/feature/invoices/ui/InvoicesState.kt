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
import com.zillit.desktop.feature.invoices.domain.CreditNote
import com.zillit.desktop.feature.invoices.domain.CreditNoteFilter
import com.zillit.desktop.feature.invoices.domain.AssignmentReason
import com.zillit.desktop.feature.invoices.domain.EntryFilter
import com.zillit.desktop.feature.invoices.domain.EntrySort
import com.zillit.desktop.feature.invoices.domain.HoldReason
import com.zillit.desktop.feature.invoices.domain.InvoiceAlert
import com.zillit.desktop.feature.invoices.domain.InvoiceAssignmentRule
import com.zillit.desktop.feature.invoices.domain.InvoiceTeamRow
import com.zillit.desktop.feature.invoices.domain.PoSuggestion
import com.zillit.desktop.feature.invoices.domain.PoSuggestions
import com.zillit.desktop.feature.invoices.domain.InvoiceAssignee
import com.zillit.desktop.feature.invoices.domain.canAccessEntryRow
import com.zillit.desktop.feature.invoices.domain.InvoiceAnalytics
import com.zillit.desktop.feature.invoices.domain.Invoice
import com.zillit.desktop.feature.invoices.domain.InvoiceAttachment
import com.zillit.desktop.feature.invoices.domain.InvoiceExtraction
import com.zillit.desktop.feature.invoices.domain.InvoiceFormat
import com.zillit.desktop.feature.invoices.domain.InvoiceStatus
import com.zillit.desktop.feature.invoices.domain.InvoiceViewer
import com.zillit.desktop.feature.invoices.domain.PayMethod
import com.zillit.desktop.feature.invoices.domain.PaymentGroup
import com.zillit.desktop.feature.invoices.domain.PaymentRun
import com.zillit.desktop.feature.invoices.domain.PaymentRuns
import com.zillit.desktop.feature.invoices.domain.PaymentTab
import com.zillit.desktop.feature.invoices.domain.SalesInvoice
import com.zillit.desktop.feature.invoices.domain.PickedInvoiceFile
import com.zillit.desktop.feature.invoices.domain.UploadType
import com.zillit.desktop.feature.invoices.domain.Vendor

/** The department view's three tabs. */
enum class DepartmentTab(val id: String, val label: String, val emptyText: String) {
    ApprovalQueue("all", "Approval Queue", "No invoices awaiting your approval"),
    MyDepartment("dept", "My Department", "No invoices found in your department"),
    MyInvoices("my", "My Invoices", "You haven't uploaded any invoices yet"),
    ;

    /** The `level_1` the service files this tab's rows under (`constants.js:176-186`); null for none. */
    val badgeKey: String?
        get() = when (this) {
            ApprovalQueue -> "invoice_approval_queue"
            MyInvoices -> "my_invoices"
            MyDepartment -> null
        }
}

/** Client-side filter on `approval_status`. */
enum class QuickFilter(val label: String) {
    All("All"),
    Pending("Pending"),
    Approved("Approved"),
    Rejected("Rejected"),
    ;

    fun keeps(invoice: Invoice): Boolean = when (this) {
        All -> true
        Pending -> invoice.approvalStatus == ApprovalStatus.Pending
        Approved -> invoice.approvalStatus == ApprovalStatus.Approved
        Rejected -> invoice.approvalStatus == ApprovalStatus.Rejected
    }
}

/** A heading in the accountant's sidebar — the web's `NAV_SECTIONS`. */
enum class InvoiceNavGroup(val label: String?) {
    /** The first group has no heading on the web either. */
    Start(null),
    ReceiveAndMatch("Receive & Match"),
    ApproveAndPay("Approve & Pay"),
    VendorsAndCreditors("Vendors & Creditors"),
    SalesAndManagement("Sales & Management"),
}

/**
 * The accountant's sidebar — every row the web has, in its order, under its
 * headings, with its ids. Every one of them opens a page.
 */
enum class AccountantPage(
    val id: String,
    val label: String,
    val group: InvoiceNavGroup,
    /** The row's icon, matched to the web's own per item. */
    val icon: ImageVector,
    /** Senior accountants only — the web's `senior: true` on Settings. */
    val seniorOnly: Boolean = false,
) {
    Overview("overview", "Overview", InvoiceNavGroup.Start, ZillitIcons.Grid),
    Inbox("inbox", "Invoice Inbox", InvoiceNavGroup.ReceiveAndMatch, ZillitIcons.Mail),
    Register("register", "Invoice Register", InvoiceNavGroup.ReceiveAndMatch, ZillitIcons.File),
    Matching("matching", "Invoices Pre-approval", InvoiceNavGroup.ReceiveAndMatch, ZillitIcons.Receipt),
    ApprovalQueue("approval", "Approval Queue", InvoiceNavGroup.ApproveAndPay, ZillitIcons.Shield),
    Entry("process", "Invoice Entry", InvoiceNavGroup.ApproveAndPay, ZillitIcons.Edit),
    Payments("payments", "Payment Runs", InvoiceNavGroup.ApproveAndPay, ZillitIcons.Wallet),
    Posted("posted", "Posted Invoices", InvoiceNavGroup.ApproveAndPay, ZillitIcons.Check),
    Credits("credits", "Credit Notes", InvoiceNavGroup.ApproveAndPay, ZillitIcons.ArrowLeft),
    Creditors("creditors", "Creditors Control", InvoiceNavGroup.VendorsAndCreditors, ZillitIcons.Bank),
    Vendors("suppliers", "Vendors", InvoiceNavGroup.VendorsAndCreditors, ZillitIcons.Users),
    Sales("sales", "Sales Invoices", InvoiceNavGroup.SalesAndManagement, ZillitIcons.CreditCard),
    Accruals("accruals", "Accruals", InvoiceNavGroup.SalesAndManagement, ZillitIcons.Ledger),
    Analytics("analytics", "Analytics", InvoiceNavGroup.SalesAndManagement, ZillitIcons.BarChart),
    /** Empty on purpose: the web shows its own "coming soon" here. */
    Reports("reports", "Reports", InvoiceNavGroup.SalesAndManagement, ZillitIcons.Search),
    Settings("settings", "Settings", InvoiceNavGroup.SalesAndManagement, ZillitIcons.Settings, seniorOnly = true),
    ;

    /**
     * The kicker above the page title — the web's `PageHeader` eyebrow.
     *
     * It names the stage rather than the screen ("Enter", "Pay", "Match"), so
     * the header reads as a place in the lifecycle.
     */
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

    val eyebrow: String
        get() = when (this) {
            Overview -> "Invoices / AP"
            Inbox -> "Receive"
            Register, Posted -> "Invoices"
            Matching -> "Match"
            ApprovalQueue -> "Approve"
            Entry -> "Enter"
            Payments -> "Pay"
            Credits -> "Credits"
            Creditors -> "Creditors"
            Vendors -> "Vendors"
            Sales -> "Sales"
            Accruals -> "Accruals"
            Analytics -> "Analytics"
            Reports -> "Reports"
            Settings -> "Configure"
        }

    /** The heading the web prints for this page, which is not always [label]. */
    val heading: String
        get() = when (this) {
            Overview -> "Overview"
            Credits -> "Credit Notes & Disputes"
            Posted -> "Posted Invoices"
            Settings -> "Invoices Setup"
            else -> label
        }

    /** The sentence under the heading — the web's `description`, word for word. */
    val blurb: String
        get() = when (this) {
            Overview -> "Vendor invoice lifecycle — from receipt to payment."
            Inbox -> "Upload invoices or forward by email. AI extracts the data."
            Register -> "All invoices in one place. Click any row for details."
            Matching ->
                "Match invoices to POs. Auto-suggested matches appear based on vendor, amount, and PO reference."
            ApprovalQueue -> "Route invoices through the multi-tier approval chain."
            Entry -> "Code, validate, and post approved invoices to the ledger."
            Payments -> "Build payment runs from open items. BACs, wires, and cheques."
            Posted -> "Everything that has hit the ledger — awaiting payment and paid."
            Credits -> "Credit notes and dispute tracking."
            Creditors -> "Outstanding balances and ageing by vendor."
            Vendors -> "AI-powered vendor compliance, bank verification, and default coding."
            Sales -> "Revenue invoicing — co-production, placement, facility recharges."
            Accruals -> "Auto-accruals from open POs. Reverses on invoice receipt."
            Analytics -> "Spend analysis by department and vendor."
            Reports -> "Cost reports, Tax returns, and management exports."
            Settings -> "AP controls — posting limits, alert preferences, and the sign-off chain."
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
            return entries.firstOrNull { it.id == segment }
        }

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
    val busy: PayMethod? = null,
) {
    /** Methods present in the selection, in the order the tabs use. */
    val methods: List<PayMethod>
        get() = PayMethod.entries.filter { method -> invoices.any { it.payMethod == method } }

    fun idsFor(method: PayMethod): List<String> = invoices.filter { it.payMethod == method }.map { it.id }

    fun countFor(method: PayMethod): Int = invoices.count { it.payMethod == method }
}

/** Turning a run down needs a reason — the web refuses an empty one. */
data class RunRejection(
    val run: PaymentRun,
    val reason: String = "",
    val busy: Boolean = false,
) {
    val isReady: Boolean get() = reason.isNotBlank()
}

/** A sales invoice being written — the web's form tab, as a sheet. */
data class SalesInvoiceDraft(
    val clientName: String = "",
    val reference: String = "",
    val description: String = "",
    val amount: String = "",
    val currency: String = "",
    /** `YYYY-MM-DD` as typed; blank is allowed, a wrong date is not. */
    val dueDate: String = "",
    val busy: Boolean = false,
) {
    val amountValue: Double? get() = amount.trim().replace(",", "").toDoubleOrNull()

    val dueDateMs: Long? get() = InvoiceFormat.parseDateInput(dueDate)

    val dateIsWrong: Boolean get() = dueDate.isNotBlank() && dueDateMs == null

    val isReady: Boolean
        get() = clientName.isNotBlank() && (amountValue ?: 0.0) > 0.0 && !dateIsWrong
}

/** The posted page's status filter — the web's three options. */
enum class PostedFilter(val label: String, val status: InvoiceStatus?) {
    All("All", null),
    ReadyToPay("Ready to Pay", InvoiceStatus.ReadyToPay),
    Paid("Paid", InvoiceStatus.Paid),
    ;

    fun keeps(invoice: Invoice): Boolean = status == null || invoice.status == status
}

/** The register's status chips. Empty = every status. */
enum class RegisterChip(val label: String, val statuses: Set<InvoiceStatus>) {
    All("All", emptySet()),
    Inbox("Inbox", setOf(InvoiceStatus.Inbox)),
    Matching("Matching", setOf(InvoiceStatus.Matching)),
    Approval("Approval", setOf(InvoiceStatus.Approval)),
    Entry("Entry", setOf(InvoiceStatus.Entry, InvoiceStatus.UnderReview)),
    Ready("Ready", setOf(InvoiceStatus.ReadyToPay)),
    Paid("Paid", setOf(InvoiceStatus.Paid)),
    ;

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
    val history: List<HistoryEntry>? = null,
    val historyOpen: Boolean = false,
    val historyLoading: Boolean = false,
    val rejecting: Boolean = false,
    val rejectReason: String = "",
    val acting: Boolean = false,
    val opening: Boolean = false,
) {
    fun nameOf(userId: String): String = names[userId] ?: userId.ifBlank { "Unknown" }
}

enum class UploadStage { Uploading, Extracting, Ready }

/** The department's Upload Invoice flow: pick → S3 → extraction → type sheet → send. */
data class UploadFlow(
    val file: PickedInvoiceFile,
    val stage: UploadStage = UploadStage.Uploading,
    val attachment: InvoiceAttachment? = null,
    val extraction: InvoiceExtraction? = null,
    val extractionFailed: Boolean = false,
    val uploadFailed: String? = null,
    val type: UploadType? = null,
    val sending: Boolean = false,
)

enum class EnterTab(val id: String, val label: String) {
    Upload("upload", "Upload Invoice"),
    Manual("manual", "Manual Entry"),
}

/** The accountant's Enter Invoice form. Amounts are kept as typed. */
data class EnterInvoiceForm(
    val tab: EnterTab = EnterTab.Upload,
    val file: PickedInvoiceFile? = null,
    val attachment: InvoiceAttachment? = null,
    val uploading: Boolean = false,
    val extracting: Boolean = false,
    val extraction: InvoiceExtraction? = null,
    val extractionFailed: Boolean = false,
    val vendorId: String = "",
    val vendorQuery: String = "",
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
    val episode: String = "",
    val poNumber: String = "",
    val mismatchAcknowledged: Boolean = false,
    val saving: Boolean = false,
    val error: String? = null,
) {
    val netValue: Double? get() = net.trim().replace(",", "").toDoubleOrNull()
    val taxValue: Double? get() = tax.trim().replace(",", "").toDoubleOrNull()
    val grossValue: Double? get() = gross.trim().replace(",", "").toDoubleOrNull()

    /** Net and tax both given but not adding up to gross. */
    val amountsMismatch: Boolean
        get() {
            val n = netValue ?: return false
            val t = taxValue ?: return false
            val g = grossValue ?: return false
            return kotlin.math.abs(n + t - g) > MISMATCH_TOLERANCE
        }

    val busy: Boolean get() = uploading || extracting || saving

    private companion object {
        const val MISMATCH_TOLERANCE = 0.011
    }
}

data class InvoicesUiState(
    val viewer: InvoiceViewer = InvoiceViewer(),
    /** Unread notifications per `level_1` key — the sidebar's and tabs' red chips. */
    val unread: Map<String, Int> = emptyMap(),
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
    val accruals: List<Accrual> = emptyList(),
    val accrualsLoading: Boolean = false,
    val accrualFilter: AccrualFilter = AccrualFilter.All,
    /** Payment Runs: the open tab, and the batches themselves. */
    val paymentTab: PaymentTab = PaymentTab.OpenItems,
    val paymentRuns: List<PaymentRun> = emptyList(),
    val runDraft: ProcessRequest? = null,
    val rejectRun: RunRejection? = null,
    /** Sales invoices, and the one being written. */
    val salesInvoices: List<SalesInvoice> = emptyList(),
    val salesDraft: SalesInvoiceDraft? = null,
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
    val upload: UploadFlow? = null,
    val enter: EnterInvoiceForm? = null,
    val confirmDelete: Invoice? = null,
    val selected: Set<String> = emptySet(),
    val chased: Set<String> = emptySet(),
    /** The Settings page — loaded only when that row is open. */
    val setup: InvoiceSetupState = InvoiceSetupState(),
    /** The side-by-side PO review over a pre-approval row. */
    val review: ReviewOverlay? = null,
    /** The PO picker open on a pre-approval row, and what it found. */
    val poPicker: PoPicker? = null,
) {
    val isAccountant: Boolean get() = viewer.isAccountant

    /** Whether something over the page owns the keyboard, so the shortcuts stay out of its way. */
    val dialogOpen: Boolean
        get() = detail != null || upload != null || enter != null || confirmDelete != null ||
            holdFor != null || runDraft != null || salesDraft != null || assignFor != null ||
            rejectRun != null || review != null || setup.memberDraft != null ||
            setup.pickingForTier != null || setup.removingMember != null || setup.removingRule != null

    fun vendorName(invoice: Invoice): String =
        vendors[invoice.vendorId]?.name?.ifBlank { null } ?: invoice.supplierName.ifBlank { "Unknown" }

    /** A department the directory has not got: an em dash, never its id. */
    fun departmentName(id: String): String = departmentNames[id] ?: id.orDash()

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
                byFilter && (needle.isEmpty() || matches(inv, needle))
            }
        }

    /** The credit notes after the chips and the search box. */
    val shownCreditNotes: List<CreditNote>
        get() {
            val needle = search.trim().lowercase()
            return creditNotes.filter { note ->
                creditNoteFilter.keeps(note) && (
                    needle.isEmpty() ||
                        note.reference.lowercase().contains(needle) ||
                        note.vendorName.lowercase().contains(needle) ||
                        note.reason.lowercase().contains(needle)
                    )
            }
        }

    /** The accruals after the chips, the department filter and the search box. */
    val shownAccruals: List<Accrual>
        get() {
            val needle = search.trim().lowercase()
            return accruals.filter { accrual ->
                accrualFilter.keeps(accrual) &&
                    (registerDepartment == null || accrual.departmentId == registerDepartment) &&
                    (
                        needle.isEmpty() ||
                            accrual.poNumber.lowercase().contains(needle) ||
                            accrual.vendorName.lowercase().contains(needle) ||
                            accrual.description.lowercase().contains(needle)
                        )
            }
        }

    /**
     * The count beside a sidebar row — the web's `item.badge`.
     *
     * Only what is on screen can be counted: the lists are fetched per page,
     * so a row that is not open has nothing to say and shows nothing.
     */
    /** The key the page on screen is filed under: the accountant's page, or the department tab. */
    val openBadgeKey: String? get() = if (viewer.isAccountant) page.badgeKey else departmentTab.badgeKey

    fun sidebarBadge(page: AccountantPage): Int? = when {
        page != this.page -> null
        page == AccountantPage.Credits -> creditNotes.size.takeIf { it > 0 }
        page == AccountantPage.Accruals -> accruals.size.takeIf { it > 0 }
        page == AccountantPage.Sales -> salesInvoices.size.takeIf { it > 0 }
        // Vendors lists vendors; it reads every invoice only to add up spend.
        page == AccountantPage.Vendors -> vendors.size.takeIf { it > 0 }
        page.isInvoiceList -> invoices.size.takeIf { it > 0 }
        else -> null
    }

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

    /** What select-all covers: the rows this viewer may actually act on. */
    val entrySelectableIds: List<String>
        get() = entryRows.filter { canAccessEntry(it) }.map { it.id }

    /** The assignee's name for the Assigned column; blank id = nobody. */
    fun assigneeName(invoice: Invoice): String? = invoice.assignedTo.takeIf { it.isNotBlank() }?.let { id ->
        userNames[id] ?: assignees.firstOrNull { it.id == id }?.name ?: id
    }

    /** Open items for the tab on screen: everything, or one pay method's queue. */
    val paymentRows: List<Invoice>
        get() {
            val methods = when (paymentTab) {
                PaymentTab.Wires -> PaymentRuns.WIRE_METHODS
                PaymentTab.Cheques -> setOf(PayMethod.Cheque)
                else -> emptySet()
            }
            val needle = search.trim().lowercase()
            return invoices.filter { invoice ->
                (methods.isEmpty() || invoice.payMethod in methods) &&
                    (needle.isEmpty() || matches(invoice, needle))
            }
        }

    /** The badge on a payment tab — the web counts the two method queues only. */
    fun paymentTabCount(tab: PaymentTab): Int = when (tab) {
        PaymentTab.Wires -> wireInvoices.size
        PaymentTab.Cheques -> chequeInvoices.size
        PaymentTab.Runs -> paymentRuns.size
        PaymentTab.OpenItems -> 0
    }

    /** The Wires tab: wire and faster payment together, as the web's tile counts them. */
    val wireInvoices: List<Invoice> get() = invoices.filter { it.payMethod in PaymentRuns.WIRE_METHODS }

    val chequeInvoices: List<Invoice> get() = invoices.filter { it.payMethod == PayMethod.Cheque }

    val bacsInvoices: List<Invoice> get() = invoices.filter { it.payMethod == PayMethod.Bacs }

    /** One run per vendor and currency: what "Create BACs Run" would make. */
    val bacsGroups: List<PaymentGroup>
        get() = PaymentRuns.groupByVendorCurrency(
            invoices = bacsInvoices.filter { it.id in selected },
            vendorName = { vendorName(it) },
            defaultCurrency = projectCurrency,
        )

    /** Every vendor group in the open items, for the tile that counts them. */
    val openItemGroups: List<PaymentGroup>
        get() = PaymentRuns.groupByVendorCurrency(invoices, { vendorName(it) }, projectCurrency)

    /** The rows behind the ticks on the payments page. */
    val selectedPaymentRows: List<Invoice> get() = paymentRows.filter { it.id in selected }

    /** The one method the whole selection shares, or null when it is mixed. */
    val selectedPayMethod: PayMethod?
        get() = selectedPaymentRows.map { it.payMethod }.distinct().singleOrNull()

    val matchingCount: Int get() = invoices.count { it.status == InvoiceStatus.Matching }
    val heldCount: Int get() = invoices.count { it.status == InvoiceStatus.Held }

    val awaitingCount: Int get() = invoices.count { it.status == InvoiceStatus.Approval && !it.isApproved }
    val approvedCount: Int get() = invoices.count { it.isApproved }

    private fun matches(inv: Invoice, needle: String): Boolean =
        inv.displayNumber.lowercase().contains(needle) ||
            vendorName(inv).lowercase().contains(needle) ||
            inv.description.lowercase().contains(needle) ||
            inv.poLabel.orEmpty().lowercase().contains(needle) ||
            inv.grossAmount.toString().contains(needle)
}

sealed interface InvoicesEvent {
    data class SelectDepartmentTab(val tab: DepartmentTab) : InvoicesEvent
    data class SelectQuickFilter(val filter: QuickFilter) : InvoicesEvent
    data class SelectPage(val page: AccountantPage) : InvoicesEvent

    /** A duplicate flag: kept and marked real, or cleared. */
    data class ConfirmDuplicate(val flagId: String) : InvoicesEvent

    data class DismissDuplicate(val flagId: String) : InvoicesEvent
    data class SelectRegisterChip(val chip: RegisterChip) : InvoicesEvent
    data class SelectRegisterDepartment(val departmentId: String?) : InvoicesEvent
    data class SelectPostedFilter(val filter: PostedFilter) : InvoicesEvent
    data class SelectCreditNoteFilter(val filter: CreditNoteFilter) : InvoicesEvent
    data class SelectAccrualFilter(val filter: AccrualFilter) : InvoicesEvent

    // -- payment runs -------------------------------------------------------

    data class SelectPaymentTab(val tab: PaymentTab) : InvoicesEvent

    /** Ticks or clears every open item at once. */
    data object ToggleSelectAll : InvoicesEvent

    data object CancelPaymentRun : InvoicesEvent
    data class ApproveRun(val run: PaymentRun) : InvoicesEvent
    data class DeleteRun(val run: PaymentRun) : InvoicesEvent

    /** Turning a run down: the sheet, what is typed in it, and the decision. */
    data class StartRejectRun(val run: PaymentRun) : InvoicesEvent
    data class RejectRunReasonChanged(val reason: String) : InvoicesEvent
    data object ConfirmRejectRun : InvoicesEvent
    data object CancelRejectRun : InvoicesEvent

    /** Acts on everything ticked by one method; null = whatever they all share. */
    data class ProcessSelected(val method: PayMethod?) : InvoicesEvent

    // -- the entry stage ----------------------------------------------------

    /** Posts an entered invoice to the ledger, or sends it back for a decision. */
    data class PostInvoice(val invoice: Invoice) : InvoicesEvent
    data class ReturnToApproval(val invoice: Invoice) : InvoicesEvent

    data class SelectEntryFilter(val filter: EntryFilter) : InvoicesEvent
    data class SelectEntrySort(val sort: EntrySort) : InvoicesEvent

    /** Null = every pay method. */
    data class SelectPayFilter(val method: PayMethod?) : InvoicesEvent

    /** Posts everything ticked, one at a time, as the web does. */
    data object PostSelected : InvoicesEvent

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
    data class MarkSalesInvoicePaid(val invoice: SalesInvoice) : InvoicesEvent
    data class DeleteSalesInvoice(val invoice: SalesInvoice) : InvoicesEvent

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

    /** Department: opens the OS picker and runs the upload flow. */
    data object UploadInvoice : InvoicesEvent
    data class ChooseUploadType(val type: UploadType) : InvoicesEvent
    data object SendUpload : InvoicesEvent
    data object CancelUpload : InvoicesEvent

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
}
