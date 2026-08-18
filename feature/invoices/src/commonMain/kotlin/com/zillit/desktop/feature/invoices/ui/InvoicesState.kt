package com.zillit.desktop.feature.invoices.ui

import com.zillit.desktop.feature.invoices.domain.ApprovalStatus
import com.zillit.desktop.feature.invoices.domain.ApprovalTierConfig
import com.zillit.desktop.feature.invoices.domain.BankAccount
import com.zillit.desktop.feature.invoices.domain.HistoryEntry
import com.zillit.desktop.feature.invoices.domain.Invoice
import com.zillit.desktop.feature.invoices.domain.InvoiceAttachment
import com.zillit.desktop.feature.invoices.domain.InvoiceExtraction
import com.zillit.desktop.feature.invoices.domain.InvoiceStatus
import com.zillit.desktop.feature.invoices.domain.InvoiceViewer
import com.zillit.desktop.feature.invoices.domain.PayMethod
import com.zillit.desktop.feature.invoices.domain.PickedInvoiceFile
import com.zillit.desktop.feature.invoices.domain.UploadType
import com.zillit.desktop.feature.invoices.domain.Vendor

/** The department view's three tabs. */
enum class DepartmentTab(val id: String, val label: String, val emptyText: String) {
    ApprovalQueue("all", "Approval Queue", "No invoices awaiting your approval"),
    MyDepartment("dept", "My Department", "No invoices found in your department"),
    MyInvoices("my", "My Invoices", "You haven't uploaded any invoices yet"),
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

/** The accountant pages this port carries. */
enum class AccountantPage(val id: String, val label: String) {
    Register("register", "Register"),
    Inbox("inbox", "Inbox"),
    ApprovalQueue("approval", "Approval Queue"),
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
    val loading: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
    val projectCurrency: String = "GBP",
    // Department view.
    val departmentTab: DepartmentTab = DepartmentTab.ApprovalQueue,
    val quickFilter: QuickFilter = QuickFilter.All,
    // Accountant view.
    val page: AccountantPage = AccountantPage.Register,
    val registerChip: RegisterChip = RegisterChip.All,
    /** Department `_id`; null = every department. */
    val registerDepartment: String? = null,
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
) {
    val isAccountant: Boolean get() = viewer.isAccountant

    fun vendorName(invoice: Invoice): String =
        vendors[invoice.vendorId]?.name?.ifBlank { null } ?: invoice.supplierName.ifBlank { "Unknown" }

    fun departmentName(id: String): String = departmentNames[id] ?: id.ifBlank { "—" }

    /** Department ids seen in the current rows, for the register filter. */
    val departmentOptions: List<String>
        get() = invoices.map { it.departmentId }.filter { it.isNotBlank() }.distinct().sortedBy { departmentName(it) }

    /** The rows after the client-side filters of the current page. */
    val shownInvoices: List<Invoice>
        get() {
            val needle = search.trim().lowercase()
            return invoices.filter { inv ->
                val byFilter = when {
                    !isAccountant -> quickFilter.keeps(inv)
                    page != AccountantPage.Register -> true
                    else -> registerChip.keeps(inv) &&
                        (registerDepartment == null || inv.departmentId == registerDepartment)
                }
                byFilter && (needle.isEmpty() || matches(inv, needle))
            }
        }

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
    data class SelectRegisterChip(val chip: RegisterChip) : InvoicesEvent
    data class SelectRegisterDepartment(val departmentId: String?) : InvoicesEvent
    data class Search(val query: String) : InvoicesEvent
    data object Refresh : InvoicesEvent
    data object DismissError : InvoicesEvent

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
