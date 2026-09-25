package com.zillit.desktop.feature.settings.approvals

import com.zillit.desktop.core.socket.SocketEventBus
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.mvvm.ZillitViewModel
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.core.units.ProductionUnit

/**
 * One queue, as the screen renders it.
 *
 * [deciding] is a set rather than a flag because an admin working through a
 * morning's requests will click the next one before the last has answered, and
 * a single flag would lock the whole list on the slowest call.
 */
data class ApprovalQueueState(
    val items: List<PendingApproval> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val query: String = "",
    /** Ids with a decision in flight. */
    val deciding: Set<String> = emptySet(),
    /** The request a decline is being confirmed for. */
    val confirming: PendingApproval? = null,
    /** What just happened, for the strip above the list. */
    val outcome: String? = null,
    /** Distinguishes "nothing waiting" from "not asked yet". */
    val hasLoaded: Boolean = false,
    /** When the list was read, so ages are measured against it. */
    val loadedAtMillis: Long = 0,
    /** Ids ticked for a decision on several at once — the web's select-and-approve. */
    val selected: Set<String> = emptySet(),
    /** True while "Decline selected" is waiting for its confirmation. */
    val confirmingSelected: Boolean = false,
    /** True while a decision on the ticked requests is going through, one by one. */
    val isDecidingSelected: Boolean = false,
) {
    val visible: List<PendingApproval> get() = items.filter { it.matches(query) }

    /** The ticked requests still in the queue, in list order. */
    val selectedItems: List<PendingApproval> get() = items.filter { it.id in selected }

    /** Every visible row ticked — what the Select all box shows. */
    val allVisibleSelected: Boolean get() = visible.isNotEmpty() && visible.all { it.id in selected }

    val isFilteredEmpty: Boolean get() = items.isNotEmpty() && visible.isEmpty()
}

/**
 * The choices the review form offers, and whether they arrived.
 *
 * [failed] is distinct from an empty list: a production with no departments is
 * a real thing, and telling an admin their picker is empty because the call
 * dropped is the difference between "there are none" and "ask again".
 */
data class PresetsState(
    val departments: List<CrewDepartment> = emptyList(),
    val units: List<ProductionUnit> = emptyList(),
    val isLoading: Boolean = false,
    val failed: Boolean = false,
    val hasLoaded: Boolean = false,
) {
    fun department(id: String?): CrewDepartment? = departments.firstOrNull { it.id == id }

    fun role(departmentId: String?, roleId: String?): CrewRole? =
        department(departmentId)?.roles?.firstOrNull { it.id == roleId }

    fun unit(id: String?): ProductionUnit? = units.firstOrNull { it.id == id }
}

/**
 * One request, open for review.
 *
 * Holds the *edited* placement rather than mutating the request: an admin who
 * changes a department and then cancels has changed nothing, and the row behind
 * the form has to still say what the person actually asked for.
 */
data class ReviewState(
    val queue: ApprovalQueue,
    val request: PendingApproval,
    val departmentId: String?,
    val roleId: String?,
    val unitId: String?,
    val keepNamePrivate: Boolean,
    /** Why the form will not submit yet. */
    val error: String? = null,
) {
    /**
     * The request as the form now describes it.
     *
     * Names are carried over with the ids so the row and the outcome message
     * say what was *approved* rather than what was asked for. A name that can
     * no longer be resolved is dropped rather than kept: a stale label beside a
     * changed id is worse than none.
     */
    fun applyTo(presets: PresetsState): PendingApproval = request.copy(
        departmentId = departmentId,
        departmentName = presets.department(departmentId)?.name
            ?: request.departmentName.takeIf { departmentId == request.departmentId },
        designationId = roleId,
        designationName = presets.role(departmentId, roleId)?.name
            ?: request.designationName.takeIf { roleId == request.designationId },
        unitId = unitId,
        unitName = presets.unit(unitId)?.name
            ?: request.unitName.takeIf { unitId == request.unitId },
        keepNamePrivate = keepNamePrivate,
    )

    companion object {
        /** Opens on what the person asked for — the admin adjusts from there. */
        fun of(queue: ApprovalQueue, request: PendingApproval) = ReviewState(
            queue = queue,
            request = request,
            departmentId = request.departmentId,
            roleId = request.designationId,
            unitId = request.unitId,
            keepNamePrivate = request.keepNamePrivate,
        )
    }
}

data class ApprovalsUiState(
    val crew: ApprovalQueueState = ApprovalQueueState(),
    val profiles: ApprovalQueueState = ApprovalQueueState(),
    /** The request open for review, if any. One at a time, like the form. */
    val review: ReviewState? = null,
    val presets: PresetsState = PresetsState(),
) {
    operator fun get(queue: ApprovalQueue): ApprovalQueueState = when (queue) {
        ApprovalQueue.NewCrew -> crew
        ApprovalQueue.ProfileChanges -> profiles
    }

    /** True while the reviewed request's own decision is in flight. */
    val isReviewSaving: Boolean
        get() = review?.let { it.request.id in this[it.queue].deciding } == true
}

sealed interface ApprovalsEvent {
    /** The screen appeared. Loads once; a second visit keeps what it had. */
    data class Opened(val queue: ApprovalQueue) : ApprovalsEvent
    data class Refresh(val queue: ApprovalQueue) : ApprovalsEvent
    data class SearchChanged(val queue: ApprovalQueue, val query: String) : ApprovalsEvent

    data class Approve(val queue: ApprovalQueue, val id: String) : ApprovalsEvent

    /** Declining asks first — the person has to start again if it was a slip. */
    data class AskDecline(val queue: ApprovalQueue, val id: String) : ApprovalsEvent
    data class ConfirmDecline(val queue: ApprovalQueue) : ApprovalsEvent
    data class DismissDecline(val queue: ApprovalQueue) : ApprovalsEvent

    data class DismissOutcome(val queue: ApprovalQueue) : ApprovalsEvent

    /** Ticks or unticks one row. */
    data class ToggleSelected(val queue: ApprovalQueue, val id: String) : ApprovalsEvent

    /** Ticks every visible row, or clears the ticks. */
    data class SelectAll(val queue: ApprovalQueue, val on: Boolean) : ApprovalsEvent

    /** Approves every ticked request, one after another, as the web does. */
    data class ApproveSelected(val queue: ApprovalQueue) : ApprovalsEvent

    /** Declining several asks first, like declining one. */
    data class AskDeclineSelected(val queue: ApprovalQueue) : ApprovalsEvent
    data class ConfirmDeclineSelected(val queue: ApprovalQueue) : ApprovalsEvent
    data class DismissDeclineSelected(val queue: ApprovalQueue) : ApprovalsEvent

    /**
     * Everything the review form does.
     *
     * Nested under one parent so the dispatch below stays one branch. Eight
     * loose cases would be eight more arms on a `when` that is already the
     * whole screen's vocabulary.
     */
    sealed interface Review : ApprovalsEvent {
        /** Opens the form on a request, and loads the choices if needed. */
        data class Open(val queue: ApprovalQueue, val id: String) : Review

        data class DepartmentChosen(val id: String?) : Review
        data class RoleChosen(val id: String?) : Review
        data class UnitChosen(val id: String?) : Review
        data class PrivacyChanged(val on: Boolean) : Review

        /** Approves with whatever the form now says, not what was asked for. */
        data object Approve : Review

        /** Hands over to the same confirmation the list uses. */
        data object Decline : Review

        data object Close : Review
    }
}

sealed interface ApprovalsEffect {
    /**
     * A request left a queue.
     *
     * The badge on Settings counts what is waiting, so it is wrong the moment a
     * decision lands — this is what tells the rest of the app to re-count.
     */
    data class Decided(val queue: ApprovalQueue, val approved: Boolean) : ApprovalsEffect
}

/**
 * The admin's two approval queues.
 *
 * ## Decisions are not optimistic
 *
 * Everywhere else in this app a change is applied to the screen first and rolled
 * back if the server disagrees — the unit picker does exactly that. Not here.
 * These two calls admit a stranger to a production or change who someone is on
 * the crew list, and a row that vanishes before the server has agreed tells an
 * admin the job is done when it may not be. The row stays, with a spinner, until
 * the server answers.
 */
class ApprovalsViewModel(
    private val repository: ApprovalsRepository,
    /**
     * What the crew list says about a person today, for the change diff.
     *
     * Supplied rather than fetched: the session already holds the crew, and the
     * profile-change queue is unreadable without it — the server sends only the
     * requested values.
     */
    private val knownCrew: (String) -> KnownCrewMember? = { null },
    /**
     * The departments, roles and units the review form offers.
     *
     * Null where there is nothing to offer — a production with no session, or a
     * build without the join flow wired. The form then shows what the request
     * asked for and nothing to change it to, which is still an approvable
     * screen.
     */
    private val presets: ApprovalPresets? = null,
    private val nowMillis: () -> Long = { 0 },
    /**
     * The live feed. A join request or profile change arriving — or decided by
     * another admin — re-reads that queue. Null where nothing needs it.
     */
    private val events: SocketEventBus? = null,
) : ZillitViewModel<ApprovalsUiState, ApprovalsEvent, ApprovalsEffect>(ApprovalsUiState()) {

    init {
        listenForChanges()
    }

    override fun onEvent(event: ApprovalsEvent) {
        when (event) {
            is ApprovalsEvent.Review -> onReview(event)

            is ApprovalsEvent.Opened -> refresh(event.queue)
            is ApprovalsEvent.Refresh -> load(event.queue)

            is ApprovalsEvent.SearchChanged ->
                update(event.queue) { copy(query = event.query) }

            is ApprovalsEvent.Approve -> find(event.queue, event.id)?.let { decide(event.queue, it, true) }

            is ApprovalsEvent.AskDecline ->
                update(event.queue) { copy(confirming = find(event.queue, event.id)) }

            is ApprovalsEvent.ConfirmDecline -> {
                val request = currentState[event.queue].confirming
                update(event.queue) { copy(confirming = null) }
                request?.let { decide(event.queue, it, false) }
            }

            is ApprovalsEvent.DismissDecline -> update(event.queue) { copy(confirming = null) }

            is ApprovalsEvent.DismissOutcome -> update(event.queue) { copy(outcome = null) }

            is ApprovalsEvent.ToggleSelected -> update(event.queue) {
                copy(selected = if (event.id in selected) selected - event.id else selected + event.id)
            }
            is ApprovalsEvent.SelectAll -> update(event.queue) {
                copy(selected = if (event.on) selected + visible.map { it.id } else emptySet())
            }
            is ApprovalsEvent.ApproveSelected -> decideSelected(event.queue, approved = true)
            is ApprovalsEvent.AskDeclineSelected -> update(event.queue) {
                copy(confirmingSelected = selectedItems.isNotEmpty())
            }
            is ApprovalsEvent.ConfirmDeclineSelected -> {
                update(event.queue) { copy(confirmingSelected = false) }
                decideSelected(event.queue, approved = false)
            }
            is ApprovalsEvent.DismissDeclineSelected -> update(event.queue) { copy(confirmingSelected = false) }
        }
    }

    /**
     * Decides every ticked request, one call at a time.
     *
     * Sequential, as the web's `handleUniversalApprove` is: a burst of parallel
     * admissions is what the join endpoint has not been asked to take, and one
     * at a time means a failure stops nothing but its own row. Each request
     * goes through the same path a single decision does, so its row, its
     * spinner and its error behave the same.
     */
    private fun decideSelected(queue: ApprovalQueue, approved: Boolean) {
        val picked = currentState[queue].selectedItems
        if (picked.isEmpty() || currentState[queue].isDecidingSelected) return

        update(queue) { copy(isDecidingSelected = true) }
        launch {
            var done = 0
            picked.forEach { request -> if (decideNow(queue, request, approved)) done++ }
            update(queue) {
                copy(
                    isDecidingSelected = false,
                    selected = selected.filterTo(mutableSetOf()) { id -> items.any { it.id == id } },
                    outcome = str(S.desktop_bulk_decided, done, picked.size),
                )
            }
        }
    }

    /** What the crew list holds for [userId], for the screen's change diff. */
    fun known(userId: String): KnownCrewMember? = knownCrew(userId)

    /**
     * The review form.
     *
     * Every edit lands on [ApprovalsUiState.review] and nowhere else until
     * Approve is pressed: an admin who changes a department and then closes the
     * form has changed nothing, and the row behind it must still say what the
     * person actually asked for.
     */
    private fun onReview(event: ApprovalsEvent.Review) {
        when (event) {
            is ApprovalsEvent.Review.Open -> openReview(event.queue, event.id)

            is ApprovalsEvent.Review.DepartmentChosen -> setState {
                // The roles belong to the department, so the old one cannot
                // survive the move — keeping it would approve someone into a
                // job their new department does not have.
                copy(review = review?.copy(departmentId = event.id, roleId = null, error = null))
            }

            is ApprovalsEvent.Review.RoleChosen ->
                setState { copy(review = review?.copy(roleId = event.id, error = null)) }

            is ApprovalsEvent.Review.UnitChosen ->
                setState { copy(review = review?.copy(unitId = event.id, error = null)) }

            is ApprovalsEvent.Review.PrivacyChanged ->
                setState { copy(review = review?.copy(keepNamePrivate = event.on)) }

            ApprovalsEvent.Review.Approve -> approveReviewed()

            ApprovalsEvent.Review.Decline -> currentState.review?.let { review ->
                // The list's own confirmation, rather than a second one here:
                // declining says the same thing whichever screen asked.
                setState { copy(review = null) }
                update(review.queue) { copy(confirming = review.request) }
            }

            ApprovalsEvent.Review.Close -> setState { copy(review = null) }
        }
    }

    private fun openReview(queue: ApprovalQueue, id: String) {
        val request = find(queue, id) ?: return
        setState { copy(review = ReviewState.of(queue, request)) }
        if (!currentState.presets.hasLoaded) loadPresets()
    }

    /**
     * Reads the departments and units once per session.
     *
     * Lazily, on the first review rather than with the queue: most visits to
     * this page are an admin clearing requests from the list, and two calls
     * nobody needed is two calls on a production's wifi.
     */
    private fun loadPresets() {
        val source = presets ?: return
        if (currentState.presets.isLoading) return

        setState { copy(presets = presets.copy(isLoading = true, failed = false)) }
        launch {
            when (val result = source.load()) {
                is ZillitResult.Success -> setState {
                    copy(
                        presets = PresetsState(
                            departments = result.data.departments,
                            units = result.data.units,
                            hasLoaded = true,
                        ),
                    )
                }

                // Not fatal to the form: it still shows what the person asked
                // for, and approving that unchanged is the common case anyway.
                is ZillitResult.Failure -> setState {
                    copy(presets = presets.copy(isLoading = false, failed = true, hasLoaded = true))
                }
            }
        }
    }

    /**
     * Approves what the form now says.
     *
     * The edits are folded into a copy of the request, so the wire body is
     * built from one shape whether the decision came from the list or from
     * here — [approvalBody] never needs to know a form exists.
     */
    private fun approveReviewed() {
        val review = currentState.review ?: return
        val presets = currentState.presets
        val department = presets.department(review.departmentId)

        // The one rule the phone clients enforce: a department with roles in it
        // must have one chosen, or the person lands on the crew list with a
        // department and no job.
        if (department != null && department.roles.isNotEmpty() && review.roleId.isNullOrBlank()) {
            setState { copy(review = review.copy(error = str(S.desktop_choose_a_role_in_department, department.name))) }
            return
        }

        decide(review.queue, review.applyTo(presets), approved = true)
    }

    private fun find(queue: ApprovalQueue, id: String): PendingApproval? =
        currentState[queue].items.firstOrNull { it.id == id }

    /**
     * Re-reads a queue, unless a decision in it is still going through.
     *
     * Every visit reads again: an admin coming back must see the requests that
     * arrived while they were away, not the list from their first visit. What
     * the old read-once rule protected — an admin part-way through deciding —
     * is protected here instead. [load] replaces only the rows, so the search,
     * an open confirmation and the ticked rows all survive it; and it is
     * skipped outright while a decision is in flight, because a read that
     * raced the server could put an approved request back on screen. The
     * server announces every decision on the socket, which re-reads straight
     * after, so nothing skipped here stays stale for long.
     */
    private fun refresh(queue: ApprovalQueue) {
        val state = currentState[queue]
        // Both, because a batch decides one request at a time: between two of
        // them `deciding` is empty while the batch is still running.
        if (state.deciding.isNotEmpty() || state.isDecidingSelected) return
        load(queue)
    }

    /** Requests arriving, or decided by another admin, while the page is open. */
    private fun listenForChanges() {
        val bus = events ?: return
        launch {
            bus.onAny(APPROVAL_SYNC_EVENTS).collect { message ->
                val queue = APPROVAL_SYNC_QUEUES[message.event] ?: return@collect
                // A queue nobody has opened is read when it is. Fetching it now
                // would be a request for a page nobody is looking at.
                if (currentState[queue].hasLoaded) refresh(queue)
            }
        }
    }

    private fun load(queue: ApprovalQueue) {
        update(queue) { copy(isLoading = true, error = null) }
        launch {
            when (val result = repository.pending(queue)) {
                is ZillitResult.Success -> update(queue) {
                    copy(
                        items = result.data,
                        isLoading = false,
                        hasLoaded = true,
                        loadedAtMillis = nowMillis(),
                        error = null,
                    )
                }

                is ZillitResult.Failure -> update(queue) {
                    // Keeps whatever was already listed. A dropped request is
                    // not evidence that the queue emptied, and blanking it would
                    // tell an admin there is nothing to do.
                    copy(
                        isLoading = false,
                        hasLoaded = true,
                        error = result.error.localised(),
                    )
                }
            }
        }
    }

    private fun decide(queue: ApprovalQueue, request: PendingApproval, approved: Boolean) {
        // Claimed here, before the coroutine: a second click arriving while the
        // first is still queued must find the row already taken.
        if (!claim(queue, request)) return
        launch { send(queue, request, approved) }
    }

    /** One decision, start to finish. True when the server took it. */
    private suspend fun decideNow(queue: ApprovalQueue, request: PendingApproval, approved: Boolean): Boolean =
        claim(queue, request) && send(queue, request, approved)

    /** Marks [request] as in flight. False when it already was. */
    private fun claim(queue: ApprovalQueue, request: PendingApproval): Boolean {
        if (request.id in currentState[queue].deciding) return false
        update(queue) { copy(deciding = deciding + request.id, error = null) }
        return true
    }

    /** Sends a claimed decision and applies the answer. True when the server took it. */
    private suspend fun send(queue: ApprovalQueue, request: PendingApproval, approved: Boolean): Boolean {
        run {
            when (val result = repository.decide(queue, request, approved)) {
                is ZillitResult.Success -> {
                    update(queue) {
                        copy(
                            items = items.filterNot { it.id == request.id },
                            deciding = deciding - request.id,
                            selected = selected - request.id,
                            outcome = outcomeFor(request, approved),
                        )
                    }
                    // The form closes on its own decision only. Left open, it
                    // would be a form editing a request that is no longer in
                    // the queue behind it.
                    setState { copy(review = review?.takeIf { it.request.id != request.id }) }
                    sendEffect(ApprovalsEffect.Decided(queue, approved))
                    return true
                }

                is ZillitResult.Failure -> {
                    update(queue) {
                        // The row stays exactly where it was, so the decision
                        // can be made again rather than being lost with the
                        // message.
                        copy(
                            deciding = deciding - request.id,
                            error = "${request.displayName}: ${result.error.localised()}",
                        )
                    }
                    // Said on the form too, when it is the form waiting: an
                    // admin who edited a department and pressed Approve must
                    // not have to close the dialog to find out it failed.
                    setState {
                        copy(
                            review = review?.takeIf { it.request.id == request.id }
                                ?.copy(error = result.error.localised())
                                ?: review,
                        )
                    }
                }
            }
        }
        return false
    }

    /** Names the person, because an admin decides several in a row. */
    private fun outcomeFor(request: PendingApproval, approved: Boolean): String =
        if (approved) {
            "${request.displayName} was approved."
        } else {
            "${request.displayName}'s request was declined."
        }

    private fun update(queue: ApprovalQueue, block: ApprovalQueueState.() -> ApprovalQueueState) {
        setState {
            when (queue) {
                ApprovalQueue.NewCrew -> copy(crew = crew.block())
                ApprovalQueue.ProfileChanges -> copy(profiles = profiles.block())
            }
        }
    }
}
