package com.zillit.desktop.feature.purchaseorder.ui

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.mvvm.ZillitViewModel
import com.zillit.desktop.core.sync.NewOperation
import com.zillit.desktop.core.sync.OfflineSupport
import com.zillit.desktop.feature.purchaseorder.data.LOCAL_ID_PREFIX
import com.zillit.desktop.feature.purchaseorder.data.PO_CREATE_KIND
import com.zillit.desktop.feature.purchaseorder.data.QueuedPurchaseOrder
import com.zillit.desktop.feature.purchaseorder.data.toLocalOrder
import com.zillit.desktop.feature.purchaseorder.domain.NewPurchaseOrder
import com.zillit.desktop.feature.purchaseorder.domain.PoAttachment
import com.zillit.desktop.feature.purchaseorder.domain.PoHistoryEntry
import com.zillit.desktop.feature.purchaseorder.domain.PoLine
import com.zillit.desktop.feature.purchaseorder.domain.PoRefresh
import com.zillit.desktop.feature.purchaseorder.domain.PoStatus
import com.zillit.desktop.feature.purchaseorder.domain.PoViewer
import com.zillit.desktop.feature.purchaseorder.domain.PurchaseOrder
import com.zillit.desktop.feature.purchaseorder.domain.PurchaseOrderRepository
import com.zillit.desktop.feature.purchaseorder.domain.Vendor
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

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

    /** Whether orders raised offline, not yet on the server, belong on this page. */
    val showsLocalOrders: Boolean get() = this == MyOrders || this == AllOrders
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
    /** Orders raised on this computer that the server has not seen yet. */
    val localOrders: List<PurchaseOrder> = emptyList(),
    val vendors: List<Vendor> = emptyList(),
    val history: List<PoHistoryEntry> = emptyList(),
    /** The selected order's files. The list has always shown their count. */
    val attachments: List<PoAttachment> = emptyList(),
    val search: String = "",
    val statusFilter: PoStatus? = null,
    val selectedId: String? = null,
    val selection: Set<String> = emptySet(),
    val draft: PoDraft = PoDraft(),
    val prompt: PoPrompt? = null,
    /** True while the API cannot be reached; writes queue instead of failing. */
    val offline: Boolean = false,
    /** When [orders] was fetched, if it is a saved copy shown because the network is gone. */
    val staleSince: Long? = null,
) {
    val selected: PurchaseOrder? get() = (localOrders + orders).firstOrNull { it.id == selectedId }

    val visibleDestinations: List<PoDestination>
        get() = PoDestination.entries.filter { it.visibleTo(viewer) }

    /** Rows after the search box and the status filter — local ones first, they are newest. */
    val rows: List<PurchaseOrder>
        get() {
            val local = if (destination.showsLocalOrders) localOrders else emptyList()
            return (local + orders).filter { order ->
                (statusFilter == null || order.status == statusFilter) && order.matches(search)
            }
        }

    /** Committed spend, per currency — mixing currencies would be a lie. */
    val committedByCurrency: Map<String, Double>
        get() = orders
            .filter { it.status.isCommitted }
            .groupBy { it.currency.orEmpty() }
            .mapValues { (_, group) -> group.sumOf { it.total } }
}

/** The Raise an Order form. Serialisable so it survives a restart. */
@Serializable
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

    val isBlank: Boolean get() = this == PoDraft()

    /** [status] is the server's creation status — see [NewPurchaseOrder.status]. */
    fun toRequest(status: String? = null) = NewPurchaseOrder(
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
        status = status,
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

    /** Opens one of the selected order's files. */
    data class OpenAttachment(val attachment: PoAttachment) : PoEvent

    /** Removes one from the selected order. */
    data class DeleteAttachment(val attachment: PoAttachment) : PoEvent

    /** Emails the selected order to its supplier. */
    data class EmailSupplier(val id: String) : PoEvent

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

    /** The host fetches the file from storage and hands it to the OS. */
    data class OpenAttachment(val attachment: PoAttachment) : PoEffect
}

/**
 * The purchase order tool's view model.
 *
 * Same shape as the two expense tools — per-destination loading, reload after
 * every mutation — so the three read alike. See `CashExpensesViewModel` for the
 * reasoning behind that arrangement.
 *
 * ## Offline
 *
 * With [offline] wired, three things change and nothing else does: the form
 * is kept on disk as it is typed and restored on reopen; raising an order with
 * no network queues it (and it appears in the lists as "waiting to send")
 * instead of failing; and a list that cannot be fetched is shown from its last
 * good copy, dated. A raise that fails because the request never left the
 * machine is queued too — that is not a refusal.
 */
@Suppress("TooManyFunctions") // One handler per user action, plus the offline seams.
class PurchaseOrderViewModel(
    private val repository: PurchaseOrderRepository,
    private val viewer: () -> PoViewer,
    private val offline: OfflineSupport? = null,
    private val nowMillis: () -> Long = { kotlin.time.Clock.System.now().toEpochMilliseconds() },
) : ZillitViewModel<PoUiState, PoEvent, PoEffect>(PoUiState(viewer = viewer())) {

    private var loadJob: Job? = null
    private var draftSaveJob: Job? = null
    private var syncWatch: Job? = null
    private var started = false
    private val json = Json { ignoreUnknownKeys = true }

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
        launch { loadVendors() }
        launch { restoreDraft() }
        watchSync()
        listenOnce()
        load(currentState.destination)
    }

    /**
     * Folds the socket's announcements into the screen: another client's
     * raise, decision or vendor edit lands as a reload of whatever is open —
     * the web's own port shape (every `po:*` frame becomes a parameterless
     * refetch). Guarded so a project switch restarting the tool does not
     * stack collectors, and debounced per kind because the backend fans one
     * action into several frames — the web coalesces the same way
     * (`accountHubListeners.js` `DEBOUNCE_MS = 500`).
     */
    private fun listenOnce() {
        if (listening) return
        listening = true
        launch {
            repository.refreshes.collect { kind ->
                syncJobs.remove(kind)?.cancel()
                syncJobs[kind] = launch {
                    delay(SYNC_DEBOUNCE_MILLIS)
                    when (kind) {
                        PoRefresh.Orders -> load(currentState.destination)
                        PoRefresh.Vendors -> loadVendors()
                    }
                }
            }
        }
    }

    private var listening = false
    private val syncJobs = mutableMapOf<PoRefresh, Job>()

    fun onProjectChanged() {
        started = false
        setState { copy(draft = PoDraft(), localOrders = emptyList(), staleSince = null) }
        start()
    }

    /**
     * Swaps in the real rights once the tool grid has answered.
     *
     * `projectId` flips the moment a production is chosen, but the rights that
     * gate this screen arrive with the Home load a beat later — so the viewer
     * resolved at open is the "not yet known" one, and nothing used to replace
     * it. Seen live 2026-08-27: Document Distribution offered no publish
     * destination at all on a production with 42 tools switched on. Only the
     * viewer changes here; the open page and its data are already right.
     */
    fun onRightsChanged() {
        // Read outside the state lambda: inside it, `viewer` is the
        // state's own viewer property rather than the supplier.
        val resolved = viewer()
        setState { copy(viewer = resolved) }
    }

    @Suppress("CyclomaticComplexMethod") // One branch per user action.
    override fun onEvent(event: PoEvent) {
        when (event) {
            PoEvent.Refresh -> load(currentState.destination)
            is PoEvent.Open -> {
                setState {
                    copy(
                        destination = event.destination,
                        search = "",
                        selectedId = null,
                        error = null,
                        staleSince = null,
                    )
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
            is PoEvent.OpenAttachment -> sendEffect(PoEffect.OpenAttachment(event.attachment))
            is PoEvent.DeleteAttachment -> removeAttachment(event.attachment)
            is PoEvent.EmailSupplier -> emailSupplier(event.id)

            is PoEvent.Ask -> setState { copy(prompt = event.prompt) }
            is PoEvent.UpdatePrompt -> setState { copy(prompt = event.prompt) }
            PoEvent.DismissPrompt -> setState { copy(prompt = null) }
            PoEvent.ConfirmPrompt -> resolvePrompt()

            is PoEvent.EditDraft -> editDraft(event.draft)
            PoEvent.AddLine -> editDraft(
                currentState.draft.let { it.copy(lines = it.lines + PoLine(null, "", 1.0, 0.0, null, null)) },
            )

            is PoEvent.RemoveLine -> editDraft(
                currentState.draft.let { draft ->
                    val remaining = draft.lines.filterIndexed { index, _ -> index != event.index }
                    draft.copy(lines = remaining.ifEmpty { listOf(PoLine(null, "", 1.0, 0.0, null, null)) })
                },
            )

            PoEvent.SubmitDraft -> submitDraft()
        }
    }

    // -- reads ---------------------------------------------------------------

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
                is ZillitResult.Success -> {
                    setState { copy(loading = false, orders = result.data.named(vendors), staleSince = null) }
                    remember(destination.cacheName, ListSerializer(PurchaseOrder.serializer()), result.data)
                }

                is ZillitResult.Failure -> {
                    val saved = recallIfUnreachable(
                        result.error,
                        destination.cacheName,
                        ListSerializer(PurchaseOrder.serializer()),
                    )
                    when {
                        saved != null -> setState {
                            copy(loading = false, orders = saved.first.named(vendors), staleSince = saved.second)
                        }

                        // No copy to show, but the person's own unsent orders
                        // are still theirs to see: an empty list under them
                        // beats an error page that hides them.
                        result.error.isUnreachable() && destination.showsLocalOrders &&
                            currentState.localOrders.isNotEmpty() ->
                            setState { copy(loading = false, orders = emptyList(), staleSince = null) }

                        else -> setState { copy(loading = false, error = result.error, staleSince = null) }
                    }
                }
            }
        }
    }

    private suspend fun loadVendors() {
        when (val fetched = repository.vendors()) {
            is ZillitResult.Success -> {
                setState { copy(vendors = fetched.data, orders = orders.named(fetched.data)) }
                remember(VENDORS_CACHE, ListSerializer(Vendor.serializer()), fetched.data)
            }

            is ZillitResult.Failure ->
                recallIfUnreachable(fetched.error, VENDORS_CACHE, ListSerializer(Vendor.serializer()))
                    ?.let { (saved, _) -> setState { copy(vendors = saved, orders = orders.named(saved)) } }
        }
    }

    /**
     * Fills in vendor names from the vendor list: the server keys an order on
     * `vendor_id` and sends no name, exactly as Android's `POMapper` resolves
     * `vendorObj?.name`. An order whose vendor is not in the list keeps blank.
     */
    private fun List<PurchaseOrder>.named(vendors: List<Vendor>): List<PurchaseOrder> {
        if (vendors.isEmpty()) return this
        val names = vendors.associate { it.id to it.name }
        return map { order ->
            if (order.vendorName.isNotBlank()) order else order.copy(vendorName = names[order.vendorId].orEmpty())
        }
    }

    /** Selecting an order also fetches its audit trail and its files. */
    private fun selectOrder(id: String?) {
        setState { copy(selectedId = id, history = emptyList(), attachments = emptyList()) }
        // A row that exists only here has neither to fetch.
        if (id == null || id.startsWith(LOCAL_ID_PREFIX)) return
        launch {
            repository.history(id).getOrNull()?.let { entries ->
                if (currentState.selectedId == id) setState { copy(history = entries) }
            }
        }
        launch {
            // The count on the row has always come from the order itself; the
            // files behind it were never fetched until now.
            repository.attachments(id).getOrNull()?.let { files ->
                if (currentState.selectedId == id) setState { copy(attachments = files) }
            }
        }
    }

    /** Removes a file, then re-reads so the count and the list agree. */
    private fun removeAttachment(attachment: PoAttachment) {
        val orderId = currentState.selectedId ?: return
        setState { copy(busy = true) }
        launch {
            when (val answer = repository.deleteAttachment(attachment.id, orderId)) {
                is ZillitResult.Success -> {
                    setState {
                        copy(
                            busy = false,
                            notice = "Attachment removed",
                            attachments = attachments.filterNot { it.id == attachment.id },
                        )
                    }
                    load(currentState.destination)
                }

                is ZillitResult.Failure -> setState { copy(busy = false, error = answer.error) }
            }
        }
    }

    /** Sends the order to its supplier — what makes an approved order real to them. */
    private fun emailSupplier(id: String) {
        setState { copy(busy = true) }
        launch {
            when (val answer = repository.emailToSupplier(id)) {
                is ZillitResult.Success ->
                    setState { copy(busy = false, notice = "Sent to the supplier") }

                is ZillitResult.Failure -> setState { copy(busy = false, error = answer.error) }
            }
        }
    }

    // -- the form --------------------------------------------------------------

    private fun editDraft(draft: PoDraft) {
        setState { copy(draft = draft) }
        val support = offline ?: return
        // Debounced: every keystroke changes the draft, and the disk does not
        // need to hear each one. Short enough that a crash loses a phrase.
        draftSaveJob?.cancel()
        draftSaveJob = launch {
            delay(DRAFT_SAVE_DEBOUNCE_MILLIS)
            val scope = support.currentScope() ?: return@launch
            if (draft.isBlank) {
                support.drafts.delete(draftId(scope.userId, scope.projectId))
            } else {
                support.drafts.save(
                    com.zillit.desktop.core.sync.LocalDraft(
                        id = draftId(scope.userId, scope.projectId),
                        scope = scope,
                        kind = DRAFT_KIND,
                        payload = json.encodeToString(PoDraft.serializer(), draft),
                        updatedAt = nowMillis(),
                    ),
                )
            }
        }
    }

    private suspend fun restoreDraft() {
        val support = offline ?: return
        val scope = support.currentScope() ?: return
        val saved = support.drafts.get(draftId(scope.userId, scope.projectId)) ?: return
        val draft = runCatching { json.decodeFromString(PoDraft.serializer(), saved.payload) }.getOrNull() ?: return
        // Only if nothing has been typed since — a restore must never overwrite.
        if (currentState.draft.isBlank) setState { copy(draft = draft) }
    }

    private suspend fun forgetDraft() {
        val support = offline ?: return
        val scope = support.currentScope() ?: return
        draftSaveJob?.cancel()
        support.drafts.delete(draftId(scope.userId, scope.projectId))
    }

    private fun submitDraft() {
        // Accounts raise straight into the ledger's queue; everyone else into
        // the approval chain — the same two statuses the phones send.
        val status = if (currentState.viewer.isAccountant) STATUS_ACCOUNTS_ENTERED else STATUS_PENDING
        val request = currentState.draft.toRequest(status)
        val invalid = request.validationError()
        if (invalid != null) {
            sendEffect(PoEffect.Failed(invalid))
            return
        }
        val support = offline
        if (support != null && support.isOffline) {
            launch { queueOrder(support, request) }
            return
        }
        launch {
            setState { copy(busy = true) }
            when (val result = repository.create(request)) {
                is ZillitResult.Success -> {
                    forgetDraft()
                    setState { copy(busy = false, notice = "Order raised", draft = PoDraft()) }
                    load(currentState.destination)
                }

                is ZillitResult.Failure -> {
                    // The request never left this machine: queue it rather than
                    // make the user retype it later. Anything else — a timeout,
                    // a refusal — is reported, and the form keeps their words.
                    if (support != null && result.error is ZillitError.NoConnection) {
                        queueOrder(support, request)
                    } else {
                        setState { copy(busy = false) }
                        sendEffect(PoEffect.Failed(result.error.localised()))
                    }
                }
            }
        }
    }

    private suspend fun queueOrder(support: OfflineSupport, request: NewPurchaseOrder) {
        val queued = QueuedPurchaseOrder(order = request, raisedBy = currentState.viewer.userId, queuedAt = nowMillis())
        val label = "Purchase order: ${request.vendorName} — ${request.description}".take(LABEL_MAX)
        val enqueued = support.engine.enqueue(
            NewOperation(
                kind = PO_CREATE_KIND,
                label = label,
                payload = json.encodeToString(QueuedPurchaseOrder.serializer(), queued),
            ),
        )
        if (enqueued == null) {
            setState { copy(busy = false) }
            sendEffect(PoEffect.Failed("Open a project before raising an order."))
            return
        }
        forgetDraft()
        setState { copy(busy = false, draft = PoDraft(), notice = QUEUED_NOTICE) }
        refreshLocalOrders()
    }

    // -- the outbox, as rows -------------------------------------------------

    private fun watchSync() {
        val support = offline ?: return
        syncWatch?.cancel()
        syncWatch = launch {
            var pending = support.engine.status.value.pending
            support.engine.status.collect { status ->
                setState { copy(offline = !status.online) }
                refreshLocalOrders()
                // Something queued has gone through: the server now has a row
                // where the local one was, so the list is fetched again.
                if (status.online && status.pending < pending) load(currentState.destination)
                pending = status.pending
            }
        }
    }

    private suspend fun refreshLocalOrders() {
        val support = offline ?: return
        val local = support.engine.operations().mapNotNull { it.toLocalOrder(json) }
        setState { copy(localOrders = local) }
    }

    // -- the read cache ------------------------------------------------------

    private suspend fun <T> remember(name: String, serializer: kotlinx.serialization.KSerializer<T>, value: T) {
        val support = offline ?: return
        val scope = support.currentScope() ?: return
        support.cache.put(scope, name, json.encodeToString(serializer, value), nowMillis())
    }

    /** The saved copy, with when it was fetched — only when the failure is the network, not the server. */
    private suspend fun <T> recallIfUnreachable(
        error: ZillitError,
        name: String,
        serializer: kotlinx.serialization.KSerializer<T>,
    ): Pair<T, Long>? {
        val scope = offline?.currentScope()
        if (!error.isUnreachable() || scope == null) return null
        val cached = offline?.cache?.get(scope, name) ?: return null
        return runCatching { json.decodeFromString(serializer, cached.json) }.getOrNull()
            ?.let { it to cached.fetchedAt }
    }

    // -- actions on existing orders --------------------------------------------

    /**
     * Whether this person may not carry out [prompt].
     *
     * Posting and closing are an accountant's (`PurchaseOrderScreen`), and
     * deleting is the raiser's own order. Approve and reject are absent on
     * purpose: the approval queue only ever holds what was routed to this
     * person, so it is scoped by data rather than by a right.
     */
    private fun refusesPrompt(prompt: PoPrompt): Boolean {
        val confirm = prompt as? PoPrompt.Confirm ?: return false
        val order = currentState.orders.firstOrNull { it.id == confirm.targetId }
        val allowed = when (confirm.action) {
            PoConfirmAction.Post, PoConfirmAction.Close,
            PoConfirmAction.CloseSelected,
            -> currentState.viewer.isAccountant
            PoConfirmAction.Delete -> order == null || currentState.viewer.owns(order)
            else -> true
        }
        return !allowed
    }

    private fun resolvePrompt() {
        val prompt = currentState.prompt ?: return
        setState { copy(prompt = null) }
        if (refusesPrompt(prompt)) {
            sendEffect(PoEffect.Failed("You do not have the rights to do that on this project."))
            return
        }
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

    private fun act(success: String, block: suspend () -> ZillitResult<Unit>) = launch {
        setState { copy(busy = true) }
        when (val result = block()) {
            is ZillitResult.Success -> {
                setState { copy(busy = false, notice = success) }
                load(currentState.destination)
            }

            is ZillitResult.Failure -> {
                setState { copy(busy = false) }
                sendEffect(PoEffect.Failed(result.error.localised()))
            }
        }
    }

    /** Keyed by what is fetched, not which tab asked — My Orders and Raise share one list. */
    private val PoDestination.cacheName: String
        get() = when (this) {
            PoDestination.ApprovalQueue -> "po.orders.approval"
            PoDestination.MyOrders, PoDestination.Raise -> "po.orders.my"
            else -> "po.orders.all"
        }

    private fun ZillitError.isUnreachable() = this is ZillitError.NoConnection || this is ZillitError.Timeout

    private fun draftId(userId: String, projectId: String) = "po.draft:$userId:$projectId"

    companion object {
        const val STATUS_PENDING = "PENDING"
        const val STATUS_ACCOUNTS_ENTERED = "ACCT_ENTERED"
        const val DRAFT_KIND = "po.draft"
        const val VENDORS_CACHE = "po.vendors"
        const val QUEUED_NOTICE = "Saved on this computer — it will be raised when you're back online."
        private const val DRAFT_SAVE_DEBOUNCE_MILLIS = 400L
        /** The web's refetch coalescing window — accountHubListeners.js `DEBOUNCE_MS`. */
        const val SYNC_DEBOUNCE_MILLIS = 500L
        private const val LABEL_MAX = 80
    }
}

internal fun PurchaseOrder.matches(query: String): Boolean {
    if (query.isBlank()) return true
    val needle = query.trim().lowercase()
    return number.lowercase().contains(needle) ||
        vendorName.lowercase().contains(needle) ||
        description.lowercase().contains(needle)
}
