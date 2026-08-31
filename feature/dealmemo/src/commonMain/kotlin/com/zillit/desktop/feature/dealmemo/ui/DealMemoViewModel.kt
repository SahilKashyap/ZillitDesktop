package com.zillit.desktop.feature.dealmemo.ui

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.mvvm.ZillitViewModel
import com.zillit.desktop.feature.dealmemo.domain.Agreement
import com.zillit.desktop.feature.dealmemo.domain.BasicRateDetails
import com.zillit.desktop.feature.dealmemo.domain.RateCascade
import com.zillit.desktop.feature.dealmemo.domain.RateCardEntry
import com.zillit.desktop.feature.dealmemo.domain.RateResolution
import com.zillit.desktop.feature.dealmemo.domain.Deal
import com.zillit.desktop.feature.dealmemo.domain.DealHistoryEntry
import com.zillit.desktop.feature.dealmemo.domain.DealMemoRepository
import com.zillit.desktop.feature.dealmemo.domain.DealRates
import com.zillit.desktop.feature.dealmemo.domain.DealStatus
import com.zillit.desktop.feature.dealmemo.domain.DealViewer
import com.zillit.desktop.feature.dealmemo.domain.NewDeal
import com.zillit.desktop.feature.dealmemo.domain.Union
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay

/** The pages the deal memo tool offers. */
enum class DealDestination(val slug: String, val label: String) {
    /** The crew member's own terms. */
    MyDeal("mine", "My Deal"),
    AllDeals("all", "All Deals"),
    Create("create", "New Deal"),
    RateCard("rates", "Rate Card"),
    ;

    /**
     * Whether [viewer] may open this page.
     *
     * The production's deals and the create form are write-access only —
     * everyone else's rates are not a crew member's business, and this is the
     * one gate that keeps them out of them.
     */
    fun visibleTo(viewer: DealViewer): Boolean = when (this) {
        MyDeal -> true
        AllDeals, Create, RateCard -> viewer.canWriteDeals
    }
}

/** Everything the deal memo tool is showing. */
data class DealUiState(
    val viewer: DealViewer,
    val destination: DealDestination = DealDestination.MyDeal,
    val loading: Boolean = false,
    val busy: Boolean = false,
    val error: ZillitError? = null,
    val notice: String? = null,
    val deals: List<Deal> = emptyList(),
    val myDeal: Deal? = null,
    val history: List<DealHistoryEntry> = emptyList(),
    val unions: List<Union> = emptyList(),
    val agreements: List<Agreement> = emptyList(),
    val search: String = "",
    val statusFilter: DealStatus? = null,
    val selectedId: String? = null,
    val draft: DealDraft = DealDraft(),
    val prompt: DealPrompt? = null,
    val rateCard: List<RateCardEntry> = emptyList(),
    /** The agreement's own scale, which roles fall back to. */
    val agreementRates: BasicRateDetails? = null,
    /** The rate the current selection resolves to, once looked up. */
    val resolvedRate: RateResolution? = null,
    val rateLookupFailed: Boolean = false,
) {
    val selected: Deal? get() = deals.firstOrNull { it.id == selectedId }

    val visibleDestinations: List<DealDestination>
        get() = DealDestination.entries.filter { it.visibleTo(viewer) }

    val rows: List<Deal>
        get() = deals.filter { deal ->
            (statusFilter == null || deal.status == statusFilter) &&
                (
                    search.isBlank() ||
                        deal.crewName.lowercase().contains(search.trim().lowercase()) ||
                        deal.designation?.lowercase()?.contains(search.trim().lowercase()) == true
                    )
        }

    /**
     * Whether the drafted weekly rate sits inside the agreement's envelope.
     *
     * Null when there is nothing to compare against. Paying under a collective
     * agreement is a dispute waiting to happen, and the person writing the deal
     * is usually the last to hear the floor moved.
     */
    val weeklyWithinEnvelope: Boolean?
        get() {
            val tier = resolvedRate?.weekly ?: return null
            val rate = draft.weeklyRate.trim().toDoubleOrNull() ?: return null
            if (rate <= 0) return null
            return RateCascade.withinEnvelope(tier, rate)
        }

    /** Deals whose terms changed and have not been re-confirmed. */
    val awaitingAcknowledgement: List<Deal> get() = deals.filter { it.awaitingReacknowledgement }
}

/** The New Deal form. */
data class DealDraft(
    val userId: String = "",
    val crewName: String = "",
    val designation: String = "",
    val currency: String? = null,
    val weeklyRate: String = "",
    val dailyRate: String = "",
    val overtimeRate: String = "",
    val standardHours: String = "",
    val daysPerWeek: String = "",
    val boxRental: String = "",
    val unionId: String? = null,
    val agreementId: String? = null,
    val nominalCode: String = "",
    val notes: String = "",
    val departmentIdentifier: String? = null,
    val productionType: String? = null,
    /** The production's budget, which decides which rate tier applies. */
    val budget: String = "",
) {
    fun toRequest() = NewDeal(
        userId = userId.trim(),
        crewName = crewName.trim(),
        // The rate-card department identifier — the wire's authoritative
        // department column (`crew_details.department_identifier`,
        // toDealMemoPayload.js:796-800).
        departmentId = departmentIdentifier,
        designation = designation.takeIf { it.isNotBlank() },
        currency = currency,
        rates = DealRates(
            weeklyRate = weeklyRate.trim().toDoubleOrNull() ?: 0.0,
            dailyRate = dailyRate.trim().toDoubleOrNull() ?: 0.0,
            overtimeRate = overtimeRate.trim().toDoubleOrNull() ?: 0.0,
            standardHours = standardHours.trim().toDoubleOrNull() ?: 0.0,
            daysPerWeek = daysPerWeek.trim().toDoubleOrNull() ?: 0.0,
            boxRental = boxRental.trim().toDoubleOrNull() ?: 0.0,
        ),
        startDate = null,
        endDate = null,
        unionId = unionId,
        agreementId = agreementId,
        nominalCode = nominalCode.takeIf { it.isNotBlank() },
        notes = notes.takeIf { it.isNotBlank() },
    )
}

sealed interface DealPrompt {
    data class Confirm(
        val action: DealConfirmAction,
        val targetId: String,
        val title: String,
        val message: String,
    ) : DealPrompt
}

enum class DealConfirmAction { Acknowledge, SendToCrew }

sealed interface DealEvent {
    data object Refresh : DealEvent
    data class Open(val destination: DealDestination) : DealEvent
    data class Search(val query: String) : DealEvent
    data class Filter(val status: DealStatus?) : DealEvent
    data class Select(val id: String?) : DealEvent
    data object ClearNotice : DealEvent
    data class Ask(val prompt: DealPrompt) : DealEvent
    data object DismissPrompt : DealEvent
    data object ConfirmPrompt : DealEvent
    data class EditDraft(val draft: DealDraft) : DealEvent
    data class SubmitDraft(val notify: Boolean) : DealEvent

    // -- rate card ---------------------------------------------------------

    /** Looks up the published rate for the drafted role. */
    data object ResolveRate : DealEvent

    /** Copies the resolved rate into the form. */
    data object ApplyResolvedRate : DealEvent

    data class BrowseRateCard(val departmentIdentifier: String?) : DealEvent
}

sealed interface DealEffect {
    data class Failed(val message: String) : DealEffect
}

/** The deal memo tool's view model. */
class DealMemoViewModel(
    private val repository: DealMemoRepository,
    private val viewer: () -> DealViewer,
) : ZillitViewModel<DealUiState, DealEvent, DealEffect>(DealUiState(viewer = viewer())) {

    private var loadJob: Job? = null
    private var started = false

    fun start() {
        if (started) return
        started = true
        val identity = viewer()
        setState {
            copy(
                viewer = identity,
                destination = if (identity.canWriteDeals) DealDestination.AllDeals else DealDestination.MyDeal,
            )
        }
        if (identity.canWriteDeals) {
            launch { repository.unions().getOrNull()?.let { list -> setState { copy(unions = list) } } }
        }
        listenOnce()
        load(currentState.destination)
    }

    /**
     * Folds the socket's deal announcements into the screen: another client's
     * create, decision or termination lands as a reload of whatever page is
     * open — the web's `ah:deal_memo:*` refetch pattern. The rate card is left
     * alone: no `deal:*` event changes the published scale, and reloading a
     * 500-row catalogue over it would be pure noise. Guarded so a project
     * switch restarting the tool does not stack collectors, and debounced
     * because one action fans into several frames (the web coalesces at
     * `accountHubListeners.js` `DEBOUNCE_MS = 500`).
     */
    private fun listenOnce() {
        if (listening) return
        listening = true
        launch {
            repository.refreshes.collect {
                syncJob?.cancel()
                syncJob = launch {
                    delay(SYNC_DEBOUNCE_MILLIS)
                    if (currentState.destination != DealDestination.RateCard) {
                        load(currentState.destination)
                    }
                }
            }
        }
    }

    private var listening = false
    private var syncJob: Job? = null

    fun onProjectChanged() {
        started = false
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
    override fun onEvent(event: DealEvent) {
        when (event) {
            DealEvent.Refresh -> load(currentState.destination)
            is DealEvent.Open -> {
                setState { copy(destination = event.destination, selectedId = null, error = null) }
                load(event.destination)
            }

            is DealEvent.Search -> setState { copy(search = event.query) }
            is DealEvent.Filter -> setState { copy(statusFilter = event.status) }
            is DealEvent.Select -> selectDeal(event.id)
            DealEvent.ClearNotice -> setState { copy(notice = null) }
            is DealEvent.Ask -> setState { copy(prompt = event.prompt) }
            DealEvent.DismissPrompt -> setState { copy(prompt = null) }
            DealEvent.ConfirmPrompt -> resolvePrompt()
            is DealEvent.EditDraft -> editDraft(event.draft)
            is DealEvent.SubmitDraft -> submitDraft(event.notify)
            DealEvent.ResolveRate -> resolveRate()
            DealEvent.ApplyResolvedRate -> applyResolvedRate()
            is DealEvent.BrowseRateCard -> browseRateCard(event.departmentIdentifier)
        }
    }

    private fun load(destination: DealDestination) {
        loadJob?.cancel()
        setState { copy(loading = true, error = null) }
        loadJob = launch {
            when (destination) {
                DealDestination.MyDeal -> when (val result = repository.myDeal()) {
                    is ZillitResult.Success -> setState { copy(loading = false, myDeal = result.data) }
                    is ZillitResult.Failure -> setState { copy(loading = false, error = result.error) }
                }

                DealDestination.RateCard -> {
                    when (val result = repository.rateCard(currentState.draft.unionId, null, null)) {
                        is ZillitResult.Success -> setState { copy(loading = false, rateCard = result.data) }
                        is ZillitResult.Failure -> setState { copy(loading = false, error = result.error) }
                    }
                }

                else -> when (val result = repository.deals(null)) {
                    is ZillitResult.Success -> setState { copy(loading = false, deals = result.data) }
                    is ZillitResult.Failure -> setState { copy(loading = false, error = result.error) }
                }
            }
        }
    }

    private fun selectDeal(id: String?) {
        setState { copy(selectedId = id, history = emptyList()) }
        if (id == null) return
        launch {
            repository.history(id).getOrNull()?.let { entries ->
                if (currentState.selectedId == id) setState { copy(history = entries) }
            }
        }
    }

    /**
     * Updates the form, refetching the agreements when the union changes.
     *
     * The agreement list is union-scoped, so leaving a stale one on screen
     * would offer terms that do not belong to the union just chosen.
     */
    private fun editDraft(draft: DealDraft) {
        val unionChanged = draft.unionId != currentState.draft.unionId
        setState {
            copy(draft = if (unionChanged) draft.copy(agreementId = null) else draft)
        }
        if (unionChanged) {
            launch {
                val agreements = repository.agreements(draft.unionId).getOrNull().orEmpty()
                if (currentState.draft.unionId == draft.unionId) {
                    setState { copy(agreements = agreements) }
                }
            }
        }
    }

    private fun browseRateCard(departmentIdentifier: String?) = launch {
        val entries = repository.rateCard(currentState.draft.unionId, departmentIdentifier, null)
        if (entries is ZillitResult.Success) setState { copy(rateCard = entries.data) }
    }

    /**
     * Looks up what this role should be paid, and by whose authority.
     *
     * The agreement's own scale is fetched alongside the role's rate because
     * the cascade needs both: a designation that publishes only its hours still
     * takes its rate from the agreement, and asking for one without the other
     * would show a blank where a rate exists. A failed lookup is recorded so
     * the screen can say "no published rate" rather than showing nothing and
     * leaving the reader to guess whether it looked.
     */
    private fun resolveRate() {
        val draft = currentState.draft
        val department = draft.departmentIdentifier
        val designation = draft.designation
        if (department.isNullOrBlank() || designation.isBlank()) {
            sendEffect(DealEffect.Failed("Pick a department and a role before looking up a rate."))
            return
        }
        launch {
            setState { copy(busy = true, rateLookupFailed = false) }
            val entry = repository.resolveRate(
                departmentIdentifier = department,
                designationIdentifier = designation,
                productionType = draft.productionType.orEmpty(),
                agreementId = draft.agreementId,
                unionId = draft.unionId,
                budget = draft.budget.trim().toDoubleOrNull(),
            ).getOrNull()
            val agreementScale = draft.agreementId?.let { repository.basicRateDetails(it).getOrNull() }
            val resolution = RateCascade.resolve(entry, agreementScale)
            setState {
                copy(
                    busy = false,
                    agreementRates = agreementScale,
                    resolvedRate = resolution.takeIf { it.hasAnything },
                    rateLookupFailed = !resolution.hasAnything,
                )
            }
        }
    }

    /**
     * Copies the resolved base rates into the form.
     *
     * Only the base rates and the hours — the envelope stays advisory, because
     * a deal at the minimum and a deal that happens to equal the minimum are
     * different things and the person writing it should choose.
     */
    private fun applyResolvedRate() {
        val resolved = currentState.resolvedRate ?: return
        setState {
            copy(
                draft = draft.copy(
                    weeklyRate = resolved.weekly?.baseRate?.toString() ?: draft.weeklyRate,
                    dailyRate = resolved.daily?.baseRate?.toString() ?: draft.dailyRate,
                    standardHours = resolved.daily?.workHours?.toString() ?: draft.standardHours,
                ),
                notice = "Published rate applied",
            )
        }
    }

    private fun submitDraft(notify: Boolean) {
        val request = currentState.draft.toRequest()
        val invalid = request.validationError()
        if (invalid != null) {
            sendEffect(DealEffect.Failed(invalid))
            return
        }
        act(if (notify) "Deal created and sent" else "Deal created", clearDraft = true) {
            repository.create(request, notify)
        }
    }

    private fun resolvePrompt() {
        val prompt = currentState.prompt ?: return
        setState { copy(prompt = null) }
        when (prompt) {
            is DealPrompt.Confirm -> when (prompt.action) {
                DealConfirmAction.Acknowledge ->
                    act("Terms acknowledged") { repository.acknowledge(prompt.targetId) }

                DealConfirmAction.SendToCrew -> {
                    // Issuing terms to a crew member is the production's act,
                    // not the crew member's. The screen offers it only where
                    // `canWriteDeals` holds (`DealMemoScreen`), and the whole
                    // All Deals destination is gated on the same — but this
                    // handler took any prompt that reached it.
                    if (!currentState.viewer.canWriteDeals) {
                        sendEffect(DealEffect.Failed("You do not have rights to issue deals."))
                        return
                    }
                    val deal = currentState.deals.firstOrNull { it.id == prompt.targetId }
                    if (deal == null) {
                        sendEffect(DealEffect.Failed("That deal is no longer on screen."))
                        return
                    }
                    // Re-sending is an update with notify on: it does not change
                    // the terms, it re-issues them to the crew member.
                    act("Deal sent to crew") {
                        repository.update(deal.id, deal.toRequest(), notify = true)
                    }
                }
            }
        }
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
                    copy(busy = false, notice = success, draft = if (clearDraft) DealDraft() else draft)
                }
                load(currentState.destination)
            }

            is ZillitResult.Failure -> {
                setState { copy(busy = false) }
                sendEffect(DealEffect.Failed(result.error.localised()))
            }
        }
    }

    companion object {
        /** The web's refetch coalescing window — accountHubListeners.js `DEBOUNCE_MS`. */
        const val SYNC_DEBOUNCE_MILLIS = 500L
    }
}

/** The deal's own terms, as an update request — for a re-send. */
internal fun Deal.toRequest() = NewDeal(
    userId = userId,
    crewName = crewName,
    departmentId = departmentId,
    // The raw stored identifier, never the display string — the wire keeps
    // designation under `crew_details.designation_identifier`.
    designation = designationIdentifier ?: designation,
    currency = currency,
    rates = rates,
    startDate = startDate,
    endDate = endDate,
    unionId = null,
    agreementId = null,
    nominalCode = nominalCode,
    notes = notes,
)
