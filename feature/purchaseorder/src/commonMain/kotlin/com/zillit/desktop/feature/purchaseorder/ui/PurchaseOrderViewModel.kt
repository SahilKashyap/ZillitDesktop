package com.zillit.desktop.feature.purchaseorder.ui

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.mvvm.ZillitViewModel
import com.zillit.desktop.feature.purchaseorder.domain.NewPurchaseOrder
import com.zillit.desktop.feature.purchaseorder.domain.PoHistoryEntry
import com.zillit.desktop.feature.purchaseorder.domain.PoLine
import com.zillit.desktop.feature.purchaseorder.domain.PoStatus
import com.zillit.desktop.feature.purchaseorder.domain.PoViewer
import com.zillit.desktop.feature.purchaseorder.domain.PurchaseOrder
import com.zillit.desktop.feature.purchaseorder.domain.PurchaseOrderRepository
import com.zillit.desktop.feature.purchaseorder.domain.Vendor
import kotlinx.coroutines.Job

/** The pages the purchase order tool offers. */
enum class PoDestination(val slug: String, val label: String) {
    Overview("overview", "Overview"),
    AllOrders("all", "All Orders"),
    MyOrders("my", "My Orders"),
    ApprovalQueue("approval", "Approval Queue"),
    Raise("raise", "Raise an Order"),
    ;

    /**
     * Whether [viewer] may open this page.
     *
     * Only two are gated: the production-wide list, which is an accountant's
     * or a senior's view of everyone's commitments, and the approval queue,
     * which is empty for anyone with nothing routed to them. Everything else is
     * offered to whoever opens the tool.
     */
    fun visibleTo(viewer: PoViewer): Boolean = when (this) {
        AllOrders -> viewer.isAccountant || viewer.hasFullAccess
        else -> true
    }
}

/** Everything the purchase order tool is showing. */
data class PoUiState(
    val viewer: PoViewer,
    val destination: PoDestination = PoDestination.MyOrders,
    val loading: Boolean = false,
    val busy: Boolean = false,
    val error: ZillitError? = null,
    val notice: String? = null,
    val orders: List<PurchaseOrder> = emptyList(),
    val vendors: List<Vendor> = emptyList(),
    val history: List<PoHistoryEntry> = emptyList(),
    val search: String = "",
    val statusFilter: PoStatus? = null,
    val selectedId: String? = null,
    val selection: Set<String> = emptySet(),
    val draft: PoDraft = PoDraft(),
    val prompt: PoPrompt? = null,
) {
    val selected: PurchaseOrder? get() = orders.firstOrNull { it.id == selectedId }

    val visibleDestinations: List<PoDestination>
        get() = PoDestination.entries.filter { it.visibleTo(viewer) }

    /** Rows after the search box and the status filter. */
    val rows: List<PurchaseOrder>
        get() = orders.filter { order ->
            (statusFilter == null || order.status == statusFilter) && order.matches(search)
        }

    /** Committed spend, per currency — mixing currencies would be a lie. */
    val committedByCurrency: Map<String, Double>
        get() = orders
            .filter { it.status.isCommitted }
            .groupBy { it.currency.orEmpty() }
            .mapValues { (_, group) -> group.sumOf { it.total } }
}

/** The Raise an Order form. */
data class PoDraft(
    val vendorId: String? = null,
    val vendorName: String = "",
    val description: String = "",
    val nominalCode: String = "",
    val episode: String = "",
    val notes: String = "",
    val currency: String? = null,
    val lines: List<PoLine> = listOf(PoLine(null, "", 1.0, 0.0, null, null)),
) {
    val total: Double get() = lines.sumOf { it.total }

    fun toRequest() = NewPurchaseOrder(
        vendorId = vendorId,
        vendorName = vendorName.trim(),
        description = description.trim(),
        departmentId = null,
        companyId = null,
        currency = currency,
        nominalCode = nominalCode.takeIf { it.isNotBlank() },
        episode = episode.takeIf { it.isNotBlank() },
        notes = notes.takeIf { it.isNotBlank() },
        effectiveDate = null,
        lines = lines.filter { it.description.isNotBlank() },
    )
}

sealed interface PoPrompt {
    data class Confirm(
        val action: PoConfirmAction,
        val targetId: String,
        val title: String,
        val message: String,
    ) : PoPrompt

    data class WithReason(
        val action: PoReasonAction,
        val targetId: String,
        val title: String,
        val label: String,
        val reason: String = "",
    ) : PoPrompt
}

enum class PoConfirmAction { Approve, Post, Close, CloseSelected, Delete }

enum class PoReasonAction { Reject }

sealed interface PoEvent {
    data object Refresh : PoEvent
    data class Open(val destination: PoDestination) : PoEvent
    data class Search(val query: String) : PoEvent
    data class Filter(val status: PoStatus?) : PoEvent
    data class Select(val id: String?) : PoEvent
    data class ToggleSelection(val id: String) : PoEvent
    data object ClearSelection : PoEvent
    data object ClearNotice : PoEvent

    data class Ask(val prompt: PoPrompt) : PoEvent
    data class UpdatePrompt(val prompt: PoPrompt) : PoEvent
    data object DismissPrompt : PoEvent
    data object ConfirmPrompt : PoEvent

    data class EditDraft(val draft: PoDraft) : PoEvent
    data object AddLine : PoEvent
    data class RemoveLine(val index: Int) : PoEvent
    data object SubmitDraft : PoEvent
}

sealed interface PoEffect {
    data class Failed(val message: String) : PoEffect
}

/**
 * The purchase order tool's view model.
 *
 * Same shape as the two expense tools — per-destination loading, reload after
 * every mutation — so the three read alike. See `CashExpensesViewModel` for the
 * reasoning behind that arrangement.
 */
class PurchaseOrderViewModel(
    private val repository: PurchaseOrderRepository,
    private val viewer: () -> PoViewer,
) : ZillitViewModel<PoUiState, PoEvent, PoEffect>(PoUiState(viewer = viewer())) {

    private var loadJob: Job? = null
    private var started = false

    /** Resolves the viewer and opens their landing page. Idempotent. */
    fun start() {
        if (started) return
        started = true
        val identity = viewer()
        setState {
            copy(
                viewer = identity,
                // An accountant opens on the production's commitments; everyone
                // else on their own orders.
                destination = if (identity.isAccountant || identity.hasFullAccess) {
                    PoDestination.Overview
                } else {
                    PoDestination.MyOrders
                },
            )
        }
        launch { repository.vendors().getOrNull()?.let { list -> setState { copy(vendors = list) } } }
        load(currentState.destination)
    }

    fun onProjectChanged() {
        started = false
        start()
    }

    @Suppress("CyclomaticComplexMethod") // One branch per user action.
    override fun onEvent(event: PoEvent) {
        when (event) {
            PoEvent.Refresh -> load(currentState.destination)
            is PoEvent.Open -> {
                setState {
                    copy(destination = event.destination, search = "", selectedId = null, error = null)
                }
                load(event.destination)
            }

            is PoEvent.Search -> setState { copy(search = event.query) }
            is PoEvent.Filter -> setState { copy(statusFilter = event.status) }
            is PoEvent.Select -> selectOrder(event.id)
            is PoEvent.ToggleSelection -> setState {
                copy(selection = if (event.id in selection) selection - event.id else selection + event.id)
            }

            PoEvent.ClearSelection -> setState { copy(selection = emptySet()) }
            PoEvent.ClearNotice -> setState { copy(notice = null) }

            is PoEvent.Ask -> setState { copy(prompt = event.prompt) }
            is PoEvent.UpdatePrompt -> setState { copy(prompt = event.prompt) }
            PoEvent.DismissPrompt -> setState { copy(prompt = null) }
            PoEvent.ConfirmPrompt -> resolvePrompt()

            is PoEvent.EditDraft -> setState { copy(draft = event.draft) }
            PoEvent.AddLine -> setState {
                copy(draft = draft.copy(lines = draft.lines + PoLine(null, "", 1.0, 0.0, null, null)))
            }

            is PoEvent.RemoveLine -> setState {
                val remaining = draft.lines.filterIndexed { index, _ -> index != event.index }
                copy(
                    draft = draft.copy(
                        lines = remaining.ifEmpty { listOf(PoLine(null, "", 1.0, 0.0, null, null)) },
                    ),
                )
            }

            PoEvent.SubmitDraft -> submitDraft()
        }
    }

    private fun load(destination: PoDestination) {
        loadJob?.cancel()
        setState { copy(loading = true, error = null) }
        loadJob = launch {
            val result = when (destination) {
                PoDestination.ApprovalQueue -> repository.approvalQueue()
                PoDestination.MyOrders, PoDestination.Raise -> repository.myOrders()
                else -> repository.orders(null)
            }
            when (result) {
                is ZillitResult.Success -> setState { copy(loading = false, orders = result.data) }
                is ZillitResult.Failure -> setState { copy(loading = false, error = result.error) }
            }
        }
    }

    /** Selecting an order also fetches its audit trail for the detail pane. */
    private fun selectOrder(id: String?) {
        setState { copy(selectedId = id, history = emptyList()) }
        if (id == null) return
        launch {
            repository.history(id).getOrNull()?.let { entries ->
                if (currentState.selectedId == id) setState { copy(history = entries) }
            }
        }
    }

    private fun submitDraft() {
        val request = currentState.draft.toRequest()
        val invalid = request.validationError()
        if (invalid != null) {
            sendEffect(PoEffect.Failed(invalid))
            return
        }
        act("Order raised", clearDraft = true) { repository.create(request) }
    }

    private fun resolvePrompt() {
        val prompt = currentState.prompt ?: return
        setState { copy(prompt = null) }
        when (prompt) {
            is PoPrompt.Confirm -> when (prompt.action) {
                PoConfirmAction.Approve -> act("Order approved") { repository.approve(prompt.targetId, null) }
                PoConfirmAction.Post -> act("Order posted") { repository.post(prompt.targetId, null) }
                PoConfirmAction.Close -> act("Order closed") { repository.close(prompt.targetId, null) }
                PoConfirmAction.Delete -> act("Order deleted") { repository.delete(prompt.targetId) }
                PoConfirmAction.CloseSelected -> closeSelected()
            }

            is PoPrompt.WithReason -> {
                val reason = prompt.reason.trim()
                if (reason.isEmpty()) {
                    sendEffect(PoEffect.Failed("A reason is required."))
                    setState { copy(prompt = prompt) }
                    return
                }
                act("Order rejected") { repository.reject(prompt.targetId, reason) }
            }
        }
    }

    private fun closeSelected() {
        val ids = currentState.selection.toList()
        if (ids.isEmpty()) {
            sendEffect(PoEffect.Failed("Nothing is selected."))
            return
        }
        act("${ids.size} order(s) closed") { repository.closeAll(ids, null) }
        setState { copy(selection = emptySet()) }
    }

    private fun act(
        success: String,
        clearDraft: Boolean = false,
        block: suspend () -> ZillitResult<Unit>,
    ) = launch {
        setState { copy(busy = true) }
        when (val result = block()) {
            is ZillitResult.Success -> {
                setState {
                    copy(busy = false, notice = success, draft = if (clearDraft) PoDraft() else draft)
                }
                load(currentState.destination)
            }

            is ZillitResult.Failure -> {
                setState { copy(busy = false) }
                sendEffect(PoEffect.Failed(result.error.userMessage))
            }
        }
    }
}

internal fun PurchaseOrder.matches(query: String): Boolean {
    if (query.isBlank()) return true
    val needle = query.trim().lowercase()
    return number.lowercase().contains(needle) ||
        vendorName.lowercase().contains(needle) ||
        description.lowercase().contains(needle)
}
