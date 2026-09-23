package com.zillit.desktop.feature.purchaseorder.ui

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.orDash
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.core.forms.FormLayout
import com.zillit.desktop.core.forms.FormTemplate
import com.zillit.desktop.feature.purchaseorder.domain.PoAddress
import com.zillit.desktop.feature.purchaseorder.domain.PoApprovalStep
import com.zillit.desktop.feature.purchaseorder.domain.PoApprovalTiers
import com.zillit.desktop.feature.purchaseorder.domain.PoAttachment
import com.zillit.desktop.feature.purchaseorder.domain.PoCurrencyRates
import com.zillit.desktop.feature.purchaseorder.domain.PoPeriodLock
import com.zillit.desktop.feature.purchaseorder.domain.PoQueryThread
import com.zillit.desktop.feature.purchaseorder.domain.PoCompany
import com.zillit.desktop.feature.purchaseorder.domain.PoDeliveryAddress
import com.zillit.desktop.feature.purchaseorder.domain.PoDepartment
import com.zillit.desktop.feature.purchaseorder.domain.PoHistoryEntry
import com.zillit.desktop.feature.purchaseorder.domain.PoLine
import com.zillit.desktop.feature.purchaseorder.domain.PoQuickFilter
import com.zillit.desktop.feature.purchaseorder.domain.PoSettings
import com.zillit.desktop.feature.purchaseorder.domain.PoSortColumn
import com.zillit.desktop.feature.purchaseorder.domain.PoSortDirection
import com.zillit.desktop.feature.purchaseorder.domain.PoSortKey
import com.zillit.desktop.feature.purchaseorder.domain.PoStatus
import com.zillit.desktop.feature.purchaseorder.domain.PoTaxType
import com.zillit.desktop.feature.purchaseorder.domain.PoUnread
import com.zillit.desktop.feature.purchaseorder.domain.PoTeamMember
import com.zillit.desktop.feature.purchaseorder.domain.PoTemplate
import com.zillit.desktop.feature.purchaseorder.domain.PoTotals
import com.zillit.desktop.feature.purchaseorder.domain.PoViewer
import com.zillit.desktop.feature.purchaseorder.domain.PurchaseOrder
import com.zillit.desktop.feature.purchaseorder.domain.Vendor

/** Everything the purchase order tool is showing. */
data class PoUiState(
    val viewer: PoViewer,
    val destination: PoDestination = PoDestination.MyPos,
    /** Which half of the Queue tab — the web's `/queue/my` and `/queue/all`. */
    val queueScope: PoQueueScope = PoQueueScope.Mine,
    val loading: Boolean = false,
    val busy: Boolean = false,
    val error: ZillitError? = null,
    val notice: String? = null,
    val orders: List<PurchaseOrder> = emptyList(),
    /** Orders raised on this computer that the server has not seen yet. */
    val localOrders: List<PurchaseOrder> = emptyList(),
    val vendors: List<Vendor> = emptyList(),

    // -- what the host lends from the account hub -----------------------------
    val departments: List<PoDepartment> = emptyList(),
    val companies: List<PoCompany> = emptyList(),
    val taxTypes: List<PoTaxType> = emptyList(),
    val currencies: List<String> = emptyList(),
    /** The accounts team — who a reassignment may hand an order to. */
    val team: List<PoTeamMember> = emptyList(),
    /** Everyone on the production, for turning a stored id into a name. */
    val people: List<PoTeamMember> = emptyList(),

    // -- the two registers ----------------------------------------------------
    val templates: List<PoTemplate> = emptyList(),
    val templatesLoading: Boolean = false,
    val addresses: List<PoDeliveryAddress> = emptyList(),
    val addressesLoading: Boolean = false,

    // -- filters, in the web's own order --------------------------------------
    val search: String = "",
    val quickFilter: PoQuickFilter = PoQuickFilter.All,
    /** A department id, or null for "All". */
    val departmentFilter: String? = null,
    val sortKey: PoSortKey = PoSortKey.DateDescending,
    /**
     * A clicked column header, which overrides [sortKey] while it is set.
     *
     * Two controls for one thing is the web's arrangement and worth keeping:
     * the dropdown is how the list is read, the header is how one question is
     * answered ("who is this assigned to?").
     */
    val sortColumn: PoSortColumn? = null,
    val sortDirection: PoSortDirection = PoSortDirection.Ascending,

    // -- the open order -------------------------------------------------------
    /** The order in the detail dialog, fetched fresh so its stamps are current. */
    val detail: PurchaseOrder? = null,
    val detailLoading: Boolean = false,
    /** The tool's unread rows — the tabs' and rows' badges. */
    val unread: PoUnread = PoUnread.None,
    val history: List<PoHistoryEntry> = emptyList(),
    val selection: Set<String> = emptySet(),

    // -- the surfaces that take over the page ---------------------------------
    val form: PoFormState? = null,
    val entry: PoEntryState? = null,

    // -- the dialogs ----------------------------------------------------------
    val reassign: PoReassignState? = null,
    val addressForm: PoAddressForm? = null,
    val bulkDate: PoBulkDateState? = null,
    val closeOff: PoCloseOffState? = null,
    val closePo: PoCloseState? = null,
    val prompt: PoPrompt? = null,

    /**
     * What the accountant configured this form to be.
     *
     * Empty until it is read, and an unread template shows every field — a
     * form must not blank its own controls because a fetch failed.
     */
    val formTemplate: FormTemplate = FormTemplate(),
    /** The project's purchase-order settings — the amend gate and the split cadence read it. */
    val projectSettings: PoSettings = PoSettings(),
    /** True while the API cannot be reached; writes queue instead of failing. */
    val offline: Boolean = false,
    /** When [orders] was fetched, if it is a saved copy shown because the network is gone. */
    val staleSince: Long? = null,
    /** The Settings tab — its own reads and saves, none of them an order list. */
    val settings: PoSettingsState = PoSettingsState(),

    // -- the account hub's workflow reads ----------------------------------------
    /** Who approves which tier. Empty until read, and empty means nobody can approve — the web's rule. */
    val tiers: PoApprovalTiers = PoApprovalTiers(),
    /** The cost-report lock; orders dated inside it are read-only. */
    val periodLock: PoPeriodLock = PoPeriodLock(),
    /** The rates a mixed-currency total converts through. */
    val rates: PoCurrencyRates = PoCurrencyRates(),
    /** The open query thread, when one is. */
    val query: PoQueryState? = null,
) {
    /**
     * The next approval decision on [order] and whether this viewer may take it
     * — the web's `getApprovalVisibility`, which gates Approve and Reject on
     * **every** surface, not only the department Approval Queue: an accountant
     * who sits on a tier approves from the console like anybody else.
     */
    fun approvalStep(order: PurchaseOrder): PoApprovalStep = tiers.visibility(order, viewer.userId)

    /** The status pill's words, with the tier progress on a pending order. */
    fun statusLabel(order: PurchaseOrder): String = order.statusLabel(tiers)

    /** Whether [order]'s saved effective date sits in the locked cost-report period. */
    fun isLocked(order: PurchaseOrder): Boolean = periodLock.locks(order)

    /** The form's own rules — which fields show, and which must be filled in. */
    val formLayout: FormLayout get() = FormLayout(formTemplate)

    /** The tabs on the left of the strip, for this viewer. */
    val mainTabs: List<PoDestination>
        get() = PoDestination.entries.filter { it.visibleTo(viewer) && !it.isRegisterTab && it != PoDestination.Form }

    /** The tabs after the action button — Templates, PO Drafts, Delivery Addresses. */
    val registerTabs: List<PoDestination>
        get() = PoDestination.entries.filter { it.isRegisterTab && it.visibleTo(viewer) }

    /**
     * The label on the strip's action button.
     *
     * The two web modules disagree and both are right: accounts *enter* an
     * order already agreed, a department *creates* a request for one.
     */
    val createLabel: String get() = if (viewer.isAccountant) str(S.desktop_enter_po) else str(S.ah_create_po)

    /**
     * Whether the "Assistant View" banner belongs on screen.
     *
     * An accounts user without the senior designation: the web tells them
     * plainly that sections are missing rather than leaving them to wonder.
     */
    val showAssistantBanner: Boolean get() = viewer.isAccountant && !viewer.isSeniorAccountant

    /** Which chips this page offers — see [PoQuickFilter]. */
    val quickFilters: List<PoQuickFilter>
        get() = when (destination) {
            PoDestination.AllPos, PoDestination.DepartmentAllPos -> PoQuickFilter.WITH_HISTORY
            PoDestination.Posted -> PoQuickFilter.RELIEF
            else -> PoQuickFilter.DEFAULT
        }

    /** Whether this page draws the filter row at all. */
    val showsFilters: Boolean
        get() = destination in
            setOf(
                PoDestination.AllPos,
                PoDestination.Queue,
                PoDestination.Posted,
                PoDestination.DepartmentAllPos,
                PoDestination.ApprovalQueue,
                PoDestination.MyPos,
                PoDestination.DepartmentPos,
            )

    /**
     * The rows this page shows: local first (they are newest), then the
     * server's, filtered by the chips, the department, the search, and the
     * page's own scope, then sorted.
     */
    val rows: List<PurchaseOrder>
        get() {
            val local = if (destination.showsLocalOrders) localOrders else emptyList()
            val filtered = (local + orders).filter { order ->
                scoped(order) && quickFilter.matches(order) && matchesDepartment(order) && order.matches(search)
            }
            return sortColumn?.let { column -> filtered.sortedBy(column, sortDirection, this) }
                ?: sortKey.sort(filtered)
        }

    /**
     * Whether [order] belongs on the page that is open.
     *
     * The Queue and Posted tabs read one endpoint and slice it, exactly as the
     * web does: `queuedPos` is everything in flight, `postedPos` everything
     * that reached the ledger. Slicing client-side keeps one fetch behind two
     * tabs and, more usefully, keeps the counts on the stat cards agreeing with
     * the rows under them.
     */
    private fun scoped(order: PurchaseOrder): Boolean = when (destination) {
        PoDestination.Queue -> order.status in QUEUE_STATUSES &&
            (queueScope == PoQueueScope.All || order.assignedTo == viewer.userId)

        PoDestination.Posted -> order.status == PoStatus.Posted || order.status == PoStatus.Closed
        PoDestination.Drafts -> order.status == PoStatus.Draft
        PoDestination.DepartmentPos -> order.departmentId != null && order.departmentId == viewer.departmentId
        // Everything else is already the endpoint's own answer.
        else -> true
    }

    private fun matchesDepartment(order: PurchaseOrder): Boolean =
        departmentFilter == null || order.departmentId == departmentFilter

    /** The selected order, whether it came from the server or the outbox. */
    val selected: PurchaseOrder? get() = detail

    /** Committed spend, per currency — mixing currencies would be a lie. */
    val committedByCurrency: Map<String, Double>
        get() = orders
            .filter { it.status.isCommitted }
            .groupBy { it.currency.orEmpty() }
            .mapValues { (_, group) -> group.sumOf { it.gross } }

    /**
     * A department's display name.
     *
     * Run through [localised] because the crew list answers translation keys,
     * not words — `accounts_department_label` reached the department column and
     * the form's picker verbatim on the first live run. An id that nothing can
     * name reads as a dash rather than 24 characters of hex, which is what the
     * web does too (`resolveDeptLabel`).
     */
    fun departmentName(id: String?): String {
        if (id.isNullOrBlank()) return ""
        val named = departments.firstOrNull { it.id == id }?.name?.takeIf { it.isNotBlank() }
        return named?.localised() ?: id.orDash("")
    }

    /**
     * Who an order is assigned to, as the row shows it.
     *
     * "Me" when it is this viewer's, the member's name when the team lists
     * them, and "Unassigned" when nobody holds it — which is a state to act
     * on, not a blank.
     */
    fun assigneeName(order: PurchaseOrder): String = when {
        order.assignedTo.isNullOrBlank() -> str(S.unassigned)
        order.assignedTo == viewer.userId -> str(S.txt_me)
        else -> personName(order.assignedTo).ifBlank { str(S.assigned) }
    }

    /**
     * A person's name, or blank.
     *
     * Blank rather than the id: an ObjectId in front of a reader is noise they
     * cannot act on, and every caller here hides the row when the name is
     * empty. "Me" for the viewer, as the assignee column shows.
     */
    fun personName(id: String?): String = when {
        id.isNullOrBlank() -> ""
        id == viewer.userId -> str(S.txt_me)
        else -> (people + team).firstOrNull { it.id == id }?.name.orEmpty()
    }

    /**
     * A team member as a picker row: their name, and the role they hold.
     *
     * The role is a translation key on the wire
     * (`production_accountant_label`), so it is localised here — it read as the
     * raw key in the Reassign picker on the first live run.
     */
    fun memberLabel(member: PoTeamMember): String {
        val role = member.role.takeIf { it.isNotBlank() }?.localised()
        return if (role == null) member.name else "${member.name} · $role"
    }

    /** The vendor's name for an order, resolved from the picker's list. */
    fun vendorName(order: PurchaseOrder): String =
        order.vendorName.ifBlank { vendors.firstOrNull { it.id == order.vendorId }?.name.orEmpty() }

    private companion object {
        /** Everything in flight, as the web's `queuedPos` slices it. */
        val QUEUE_STATUSES = setOf(
            PoStatus.AwaitingApproval,
            PoStatus.Approved,
            PoStatus.Queued,
            PoStatus.AccountsEntered,
            PoStatus.Rejected,
        )
    }
}

/** One clicked column, ascending or descending. Blanks sort last, never interleaved. */
private fun List<PurchaseOrder>.sortedBy(
    column: PoSortColumn,
    direction: PoSortDirection,
    state: PoUiState,
): List<PurchaseOrder> {
    val comparator: Comparator<PurchaseOrder> = when (column) {
        PoSortColumn.Number -> compareBy { it.number.lowercase() }
        PoSortColumn.Vendor -> compareBy { state.vendorName(it).lowercase() }
        PoSortColumn.Department -> compareBy { state.departmentName(it.departmentId).lowercase() }
        PoSortColumn.Amount -> compareBy { it.gross }
        PoSortColumn.EffectiveDate -> compareBy { it.effectiveDate ?: Long.MAX_VALUE }
        PoSortColumn.Status -> compareBy { it.status.label }
        PoSortColumn.Assigned -> compareBy { state.assigneeName(it) }
    }
    return sortedWith(if (direction == PoSortDirection.Ascending) comparator else comparator.reversed())
}

/**
 * The Create / Edit PO form — the web's `POForm`, which takes over the page.
 *
 * One state for four of its modes: a new order, an edit of one, a draft being
 * resumed, and a template being written. They differ in what the buttons say
 * and where the save goes, not in what is on screen, which is why the web
 * carries a `mode` rather than four components.
 */
data class PoFormState(
    val mode: PoFormMode,
    /** The order or draft being edited; blank for a new one. */
    val orderId: String? = null,
    val templateId: String? = null,
    val templateName: String = "",
    val vendorId: String? = null,
    val vendorName: String = "",
    val description: String = "",
    val departmentId: String? = null,
    val companyId: String? = null,
    val nominalCode: String = "",
    val currency: String? = null,
    val episode: String = "",
    val notes: String = "",
    val effectiveDate: Long? = null,
    val deliveryDate: Long? = null,
    val vatTreatment: String = "pending",
    val deliveryAddressId: String? = null,
    val deliveryAddress: PoAddress = PoAddress(),
    val lines: List<PoLine> = listOf(blankLine()),
    val attachments: List<PoAttachment> = emptyList(),
    /** The extra fields this production added, by their form key. */
    val customFields: Map<String, String> = emptyMap(),
    val saving: Boolean = false,
    val uploading: Boolean = false,
    /** Every reason the form is refusing to save, shown together. */
    val problems: List<String> = emptyList(),
) {
    val totals: PoTotals get() = PoTotals.of(lines)

    val title: String
        get() = when (mode) {
            PoFormMode.NewOrder -> str(S.desktop_new_po)
            PoFormMode.EditOrder -> str(S.ah_edit_po_upper)
            PoFormMode.EditDraft -> str(S.desktop_edit_draft)
            PoFormMode.NewTemplate -> str(S.dm_template_new)
            PoFormMode.EditTemplate -> str(S.dd_edit_template)
        }

    /** The web's `backLabel`: a template came from the Templates tab. */
    val backLabel: String get() = if (isTemplate) str(S.desktop_back_to_templates) else str(S.desktop_back_to_pos)

    val isTemplate: Boolean get() = mode == PoFormMode.NewTemplate || mode == PoFormMode.EditTemplate

    /** The primary button — the web's `Update & Submit PO` / `Create & Submit PO`. */
    val submitLabel: String
        get() = when (mode) {
            PoFormMode.EditOrder -> str(S.desktop_update_and_submit_po)
            else -> str(S.desktop_create_and_submit_po)
        }

    val saveDraftLabel: String
        get() = if (mode == PoFormMode.EditDraft) str(S.ah_update_draft_opt) else str(S.ah_save_draft)

    val saveTemplateLabel: String
        get() = when (mode) {
            PoFormMode.EditTemplate -> str(S.update_template)
            PoFormMode.NewTemplate -> str(S.ah_save_template_btn)
            else -> str(S.save_as_template)
        }
}

enum class PoFormMode { NewOrder, EditOrder, EditDraft, NewTemplate, EditTemplate }

/** A fresh, empty line — one item at no price, which is what a new row means. */
fun blankLine(): PoLine = PoLine(
    id = null,
    description = "",
    quantity = 1.0,
    unitPrice = 0.0,
    nominalCode = null,
    vatRate = null,
)

/**
 * The PO Entry page — the web's `POEntry`, where an accountant codes an
 * approved order and posts it to the ledger.
 *
 * The lines here are the *ledger* lines, which start as a copy of the order's
 * and are then re-coded: the whole point of the surface is that what the
 * department asked for and what the books record are two different documents
 * that have to add up to the same number.
 */
data class PoEntryState(
    val orderId: String,
    /** The coded lines — never the consolidated tax row, which is [taxLine]. */
    val lines: List<PoLine> = emptyList(),
    val effectiveDate: Long? = null,
    // -- the header the web's process surface lets the accountant correct -----
    val nominalCode: String = "",
    val vendorId: String? = null,
    val companyId: String? = null,
    val departmentId: String? = null,
    val currency: String? = null,
    val deliveryDate: Long? = null,
    val vatTreatment: String = "pending",
    // -- the consolidated tax line ---------------------------------------------
    /** The persisted `is_tax` row, kept for its id, tags and tracking codes. */
    val taxLine: PoLine? = null,
    /** A typed tax amount; null derives it from the lines. Sticky, like the web's `overridden`. */
    val taxOverride: Double? = null,
    /** The reclaim nominal the tax line posts to. */
    val taxCode: String = "",
    val saving: Boolean = false,
    val posting: Boolean = false,
    val sending: Boolean = false,
    /** Whether the order's own document is shown beside the coding. */
    val previewOpen: Boolean = false,
    /** Rows the last Post refused for want of a nominal, 1-based — flagged on screen. */
    val missingCodes: List<Int> = emptyList(),
) {
    val totals: PoTotals get() = PoTotals.of(lines)

    /**
     * What the coded lines come to, before the consolidated tax substitution.
     * The page itself reconciles through [ledger], which applies it.
     */
    val ledgerTotal: Double get() = totals.gross
}

/** The Reassign dialog — one order or a whole selection. */
data class PoReassignState(
    /** Empty for a bulk reassign; the ids are in [ids]. */
    val orderId: String,
    val ids: List<String>,
    val label: String,
    val userId: String? = null,
    val reason: String = "",
    val customReason: String = "",
    val saving: Boolean = false,
) {
    /**
     * The reasons the web offers, plus its own escape hatch.
     *
     * A closed list with "Other" is the shape that produces a usable audit
     * trail: free text alone gives a column of one-word notes.
     */
    val resolvedReason: String get() = if (reason == OTHER) customReason.trim() else reason

    companion object {
        val OTHER: String get() = str(S.desktop_other_custom_reason)
        val REASONS: List<String>
            get() = listOf(
                str(S.desktop_inv_reason_workload),
                str(S.desktop_po_reason_department_specialisation),
                str(S.desktop_po_reason_absence_cover),
                str(S.desktop_po_reason_escalation),
                OTHER,
            )
    }
}

/** The Delivery Addresses tab's add/edit dialog. */
data class PoAddressForm(
    /** Null for a new one. */
    val id: String? = null,
    val address: PoAddress = PoAddress(),
    val saving: Boolean = false,
    val problem: String? = null,
)

/** The bulk "Set Effective Date" dialog. */
data class PoBulkDateState(val ids: List<String>, val date: Long? = null, val saving: Boolean = false)

/** The per-period "Close Off Period" confirm. */
data class PoCloseOffState(
    val period: String,
    val ids: List<String>,
    val date: Long? = null,
    val confirmed: Boolean = false,
    val saving: Boolean = false,
)

/**
 * An order's query thread, open beside whatever surface asked for it — the
 * web's `QueryPanel`.
 */
data class PoQueryState(
    val orderId: String,
    val title: String,
    /** Null until read, and while nobody has asked anything. */
    val thread: PoQueryThread? = null,
    val loading: Boolean = true,
    val draft: String = "",
    val sending: Boolean = false,
)

/** Closing one order: a reason and the period it lands in. */
data class PoCloseState(
    val orderId: String,
    val number: String,
    val reason: String = "",
    val date: Long? = null,
    val saving: Boolean = false,
)
