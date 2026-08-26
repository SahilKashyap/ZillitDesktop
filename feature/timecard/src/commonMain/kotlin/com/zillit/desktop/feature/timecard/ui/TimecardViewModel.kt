package com.zillit.desktop.feature.timecard.ui

import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.common.EpochDate
import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.mvvm.ZillitViewModel
import com.zillit.desktop.core.sync.LocalDraft
import com.zillit.desktop.core.sync.NewOperation
import com.zillit.desktop.core.sync.OfflineSupport
import com.zillit.desktop.feature.timecard.data.LOCAL_WEEK_PREFIX
import com.zillit.desktop.feature.timecard.data.QueuedTimecardSave
import com.zillit.desktop.feature.timecard.data.QueuedTimecardSubmit
import com.zillit.desktop.feature.timecard.data.TIMECARD_SAVE_KIND
import com.zillit.desktop.feature.timecard.data.TIMECARD_SUBMIT_KIND
import com.zillit.desktop.feature.timecard.data.payrollPeriodStart
import com.zillit.desktop.feature.timecard.data.toLocalTimecard
import com.zillit.desktop.feature.timecard.domain.Allowance
import com.zillit.desktop.feature.timecard.domain.AllowanceType
import com.zillit.desktop.feature.timecard.domain.DayType
import com.zillit.desktop.feature.timecard.domain.Timecard
import com.zillit.desktop.feature.timecard.domain.TimecardDay
import com.zillit.desktop.feature.timecard.domain.TimecardDraft
import com.zillit.desktop.feature.timecard.domain.TimecardHistoryEntry
import com.zillit.desktop.feature.timecard.domain.TimecardMetadata
import com.zillit.desktop.feature.timecard.domain.TimecardRepository
import com.zillit.desktop.feature.timecard.domain.TimecardStatus
import com.zillit.desktop.feature.timecard.domain.TimecardViewer
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** The pages the timecard tool offers. */
enum class TimecardDestination(val slug: String, val label: String) {
    MyWeeks("my", "My Timecards"),
    Edit("edit", "This Week"),
    ApprovalQueue("approval", "Approval Queue"),
    Processing("processing", "Payroll Processing"),
    Outstanding("outstanding", "Outstanding"),
    ;

    /**
     * Whether [viewer] may open this page.
     *
     * Approving is a head-of-department right; processing and the chase list
     * belong to payroll. Everyone files their own week, so the first two are
     * always offered.
     */
    fun visibleTo(viewer: TimecardViewer): Boolean = when (this) {
        ApprovalQueue -> viewer.isApprover || viewer.isFinalApprover
        Processing, Outstanding -> viewer.isFinalApprover
        MyWeeks, Edit -> true
    }
}

/** Everything the timecard tool is showing. */
data class TimecardUiState(
    val viewer: TimecardViewer,
    val destination: TimecardDestination = TimecardDestination.MyWeeks,
    val loading: Boolean = false,
    val busy: Boolean = false,
    val error: ZillitError? = null,
    val notice: String? = null,
    val timecards: List<Timecard> = emptyList(),
    val history: List<TimecardHistoryEntry> = emptyList(),
    val selectedId: String? = null,
    val selection: Set<String> = emptySet(),
    val search: String = "",
    val draft: TimecardDraft? = null,
    val prompt: TimecardPrompt? = null,
    /** Weeks saved on this computer that the server has not seen yet. */
    val localTimecards: List<Timecard> = emptyList(),
    /** True while the API cannot be reached; saves and submits queue instead of failing. */
    val offline: Boolean = false,
    /** When [timecards] was fetched, if it is a saved copy shown because the network is gone. */
    val staleSince: Long? = null,
) {
    val selected: Timecard? get() = (localTimecards + timecards).firstOrNull { it.id == selectedId }

    val visibleDestinations: List<TimecardDestination>
        get() = TimecardDestination.entries.filter { it.visibleTo(viewer) }

    val rows: List<Timecard>
        get() {
            val local = if (destination == TimecardDestination.MyWeeks) localTimecards else emptyList()
            return (local + timecards).filter { card ->
                search.isBlank() ||
                    card.crewName.lowercase().contains(search.trim().lowercase()) ||
                    EpochDate.date(card.weekStarting).lowercase().contains(search.trim().lowercase())
            }
        }

    /** Money the visible weeks come to, per currency. */
    val totalsByCurrency: Map<String, Double>
        get() = rows.groupBy { it.currency.orEmpty() }.mapValues { (_, group) -> group.sumOf { it.net } }
}

sealed interface TimecardPrompt {
    data class Confirm(
        val action: TimecardConfirmAction,
        val targetId: String,
        val title: String,
        val message: String,
    ) : TimecardPrompt

    data class WithReason(
        val action: TimecardReasonAction,
        val targetId: String,
        val title: String,
        val label: String,
        val reason: String = "",
    ) : TimecardPrompt

    /**
     * Adding a deduction needs a label and an amount; the nominal code is the
     * web modal's third field (`AddDeductionModal.jsx:127-132`) — there is no
     * reason on the deduction wire.
     */
    data class Deduct(
        val targetId: String,
        val label: String = "",
        val amount: String = "",
        val nominalCode: String = "",
    ) : TimecardPrompt
}

enum class TimecardConfirmAction {
    Submit,
    Approve,
    FinalApprove,
    Lock,
    MarkPaid,
    ApproveSelected,
    LockSelected,
}

enum class TimecardReasonAction { Reject, Query }

sealed interface TimecardEvent {
    data object Refresh : TimecardEvent
    data class Open(val destination: TimecardDestination) : TimecardEvent
    data class Search(val query: String) : TimecardEvent
    data class Select(val id: String?) : TimecardEvent
    data class ToggleSelection(val id: String) : TimecardEvent
    data object ClearSelection : TimecardEvent
    data object ClearNotice : TimecardEvent

    data class Ask(val prompt: TimecardPrompt) : TimecardEvent
    data class UpdatePrompt(val prompt: TimecardPrompt) : TimecardEvent
    data object DismissPrompt : TimecardEvent
    data object ConfirmPrompt : TimecardEvent

    data class EditDay(val index: Int, val day: TimecardDay) : TimecardEvent
    data class EditNotes(val notes: String) : TimecardEvent
    data object SaveDraft : TimecardEvent
    data class LoadDraft(val timecardId: String?) : TimecardEvent

    // -- allowances --------------------------------------------------------

    /** Claims an allowance on a day. */
    data class AddAllowance(val dayIndex: Int, val type: AllowanceType) : TimecardEvent

    data class EditAllowance(
        val dayIndex: Int,
        val allowanceIndex: Int,
        val allowance: Allowance,
    ) : TimecardEvent

    data class RemoveAllowance(val dayIndex: Int, val allowanceIndex: Int) : TimecardEvent
}

sealed interface TimecardEffect {
    data class Failed(val message: String) : TimecardEffect
}

/**
 * The timecard tool's view model.
 *
 * Same arrangement as the finance tools: per-destination loading, and every
 * mutation ends by reloading the page it happened on so a week that leaves a
 * queue actually leaves it.
 */
@Suppress("TooManyFunctions") // One handler per user action, plus the offline seams.
class TimecardViewModel(
    private val repository: TimecardRepository,
    private val viewer: () -> TimecardViewer,
    /** The current week's Monday, so a fresh draft knows what it covers. */
    private val currentWeekStarting: () -> Long,
    /**
     * With this wired: the week is kept on disk as it is typed, a save or
     * submit with no network queues instead of failing, and lists that cannot
     * be fetched are shown from their last good copy. Null keeps the tool
     * exactly as it was.
     */
    private val offline: OfflineSupport? = null,
    private val nowMillis: () -> Long = { kotlin.time.Clock.System.now().toEpochMilliseconds() },
) : ZillitViewModel<TimecardUiState, TimecardEvent, TimecardEffect>(
    TimecardUiState(viewer = viewer()),
) {

    private var loadJob: Job? = null
    private var draftSaveJob: Job? = null
    private var syncWatch: Job? = null
    private var started = false
    private val json = Json { ignoreUnknownKeys = true }

    fun start() {
        if (started) return
        started = true
        watchSync()
        listenOnce()
        launch {
            val identity = viewer()
            val metadata = repository.metadata().rememberOrRecall(METADATA_CACHE, TimecardMetadata.serializer())
            // The allowance catalogue rides along: the week grid cannot offer a
            // claim without it, and fetching it on first use would put a wait
            // in front of a keystroke.
            val allowances = repository.allowanceTypes()
                .rememberOrRecall(ALLOWANCES_CACHE, ListSerializer(AllowanceType.serializer()))
                .orEmpty()
            setState {
                val base = metadata ?: identity.metadata
                val resolved = identity.copy(metadata = base.copy(allowanceTypes = allowances))
                copy(
                    viewer = resolved,
                    // Payroll opens on the week they are processing; everyone
                    // else on their own.
                    destination = if (resolved.isFinalApprover) {
                        TimecardDestination.Processing
                    } else {
                        TimecardDestination.MyWeeks
                    },
                )
            }
            load(currentState.destination)
        }
    }

    fun onProjectChanged() {
        started = false
        setState { copy(draft = null, localTimecards = emptyList(), staleSince = null) }
        start()
    }

    /**
     * Folds the socket's announcements into the screen: another client's
     * submit, decision, lock or payment lands as a reload of whatever page is
     * open — the web's `ah:timecard:*` refetch pattern. Guarded so a project
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
                    load(currentState.destination)
                }
            }
        }
    }

    private var listening = false
    private var syncJob: Job? = null

    @Suppress("CyclomaticComplexMethod") // One branch per user action.
    override fun onEvent(event: TimecardEvent) {
        when (event) {
            TimecardEvent.Refresh -> load(currentState.destination)
            is TimecardEvent.Open -> {
                setState { copy(destination = event.destination, selectedId = null, error = null, staleSince = null) }
                load(event.destination)
            }

            is TimecardEvent.Search -> setState { copy(search = event.query) }
            is TimecardEvent.Select -> selectCard(event.id)
            is TimecardEvent.ToggleSelection -> setState {
                copy(selection = if (event.id in selection) selection - event.id else selection + event.id)
            }

            TimecardEvent.ClearSelection -> setState { copy(selection = emptySet()) }
            TimecardEvent.ClearNotice -> setState { copy(notice = null) }

            is TimecardEvent.Ask -> setState { copy(prompt = event.prompt) }
            is TimecardEvent.UpdatePrompt -> setState { copy(prompt = event.prompt) }
            TimecardEvent.DismissPrompt -> setState { copy(prompt = null) }
            TimecardEvent.ConfirmPrompt -> resolvePrompt()

            is TimecardEvent.EditDay -> setState {
                val current = draft ?: return@setState this
                copy(
                    draft = current.copy(
                        days = current.days.mapIndexed { index, day ->
                            if (index == event.index) event.day else day
                        },
                    ),
                )
            }

            is TimecardEvent.EditNotes -> setState {
                copy(draft = draft?.copy(notes = event.notes))
            }

            TimecardEvent.SaveDraft -> saveDraft()
            is TimecardEvent.LoadDraft -> loadDraft(event.timecardId)

            is TimecardEvent.AddAllowance -> editAllowances(event.dayIndex) { day ->
                // A once-a-day allowance claimed twice is a mistake the server
                // would reject, so the second claim is dropped. Only a per-unit
                // allowance — mileage — genuinely stacks.
                val existing = day.allowances.indexOfFirst { it.code == event.type.code }
                if (existing >= 0 && !event.type.perUnit) day.allowances else day.allowances + event.type.claim()
            }

            is TimecardEvent.EditAllowance -> editAllowances(event.dayIndex) { day ->
                day.allowances.mapIndexed { index, allowance ->
                    if (index == event.allowanceIndex) event.allowance else allowance
                }
            }

            is TimecardEvent.RemoveAllowance -> editAllowances(event.dayIndex) { day ->
                day.allowances.filterIndexed { index, _ -> index != event.allowanceIndex }
            }
        }
        if (event.editsTheWeek) scheduleDraftSave()
    }

    /** Rewrites one day's allowances, leaving the rest of the week alone. */
    private fun editAllowances(dayIndex: Int, claims: (TimecardDay) -> List<Allowance>) = setState {
        val current = draft ?: return@setState this
        val day = current.days.getOrNull(dayIndex) ?: return@setState this
        copy(draft = current.withDay(dayIndex, day.copy(allowances = claims(day))))
    }

    private fun load(destination: TimecardDestination) {
        loadJob?.cancel()
        setState { copy(loading = true, error = null) }
        loadJob = launch {
            val result = when (destination) {
                TimecardDestination.ApprovalQueue -> repository.approvalQueue()
                // The path takes epoch millis of the pay-period start's local
                // midnight, never a date string — the web computes it with
                // `startOfPeriodTz(now, browserTz(), payPeriodStartDay || 1)`
                // (AccountantPayrollModule.jsx:949-969) and anything else is
                // refused as `timecard_invalid_week_starting`.
                TimecardDestination.Processing -> repository.payrollProcessing(
                    payrollPeriodStart(nowMillis(), currentState.viewer.metadata.payPeriodStartDay),
                )

                TimecardDestination.Outstanding -> repository.outstanding()
                else -> repository.myTimecards()
            }
            when (result) {
                is ZillitResult.Success -> {
                    setState { copy(loading = false, timecards = result.data, staleSince = null) }
                    remember(destination.cacheName, ListSerializer(Timecard.serializer()), result.data)
                    seedEditor(destination, result.data)
                }

                is ZillitResult.Failure -> {
                    val saved = recallIfUnreachable(
                        result.error,
                        destination.cacheName,
                        ListSerializer(Timecard.serializer()),
                    )
                    when {
                        saved != null -> {
                            setState { copy(loading = false, timecards = saved.first, staleSince = saved.second) }
                            seedEditor(destination, saved.first)
                        }

                        // No copy to show, but the week filed on this computer
                        // is still theirs to see — and the editor still opens
                        // on a blank week rather than an error page.
                        result.error.isUnreachable() && destination.worksOffline -> {
                            setState { copy(loading = false, timecards = emptyList(), staleSince = null) }
                            seedEditor(destination, emptyList())
                        }

                        else -> setState { copy(loading = false, error = result.error, staleSince = null) }
                    }
                }
            }
        }
    }

    /**
     * Opening the editor with nothing to edit is a dead end, so the draft is
     * seeded from this week as soon as the list that would contain it has
     * arrived.
     */
    private suspend fun seedEditor(destination: TimecardDestination, weeks: List<Timecard>) {
        if (destination == TimecardDestination.Edit && currentState.draft == null) {
            loadDraft(weeks.firstOrNull { it.isEditable }?.id)
        }
    }

    private fun selectCard(id: String?) {
        setState { copy(selectedId = id, history = emptyList()) }
        // A week that exists only here has no history to fetch.
        if (id == null || id.startsWith(LOCAL_WEEK_PREFIX)) return
        launch {
            repository.history(id).getOrNull()?.let { entries ->
                if (currentState.selectedId == id) setState { copy(history = entries) }
            }
        }
    }

    /**
     * Opens a week for editing — an existing one, or a blank seven days.
     *
     * A blank week is seven [DayType.NotWorked] days rather than an empty
     * list: the grid has a row per day whether or not it was worked, and
     * building it from an empty list would make "Monday" mean "the first day
     * someone happened to fill in".
     */
    private fun loadDraft(timecardId: String?) {
        val existing = currentState.timecards.firstOrNull { it.id == timecardId }
        // The web stamps the creator's department on CREATE so the
        // department-scoped approval tiers resolve (WeeklyTimecardModule.jsx:
        // 4788-4791); null is its own missing-department case.
        val department = currentState.viewer.departmentIdentifier
        val fromServer = if (existing != null) {
            TimecardDraft(
                timecardId = existing.id,
                weekStarting = existing.weekStarting,
                days = existing.days.ifEmpty { blankWeek(existing.weekStarting) },
                notes = existing.notes.orEmpty(),
                departmentId = existing.departmentId ?: department,
            )
        } else {
            val monday = currentWeekStarting()
            TimecardDraft(
                timecardId = null,
                weekStarting = monday,
                days = blankWeek(monday),
                departmentId = department,
            )
        }
        seededNotes = fromServer.notes.trim()
        setState { copy(draft = fromServer) }
        // Words typed into this week on this computer, if newer than what the
        // server has, come back over it. Never over something typed since.
        launch {
            val saved = restoreDraft(fromServer.weekStarting, newerThan = existing?.updatedAt) ?: return@launch
            if (currentState.draft == fromServer) {
                setState {
                    copy(
                        draft = saved.copy(
                            timecardId = fromServer.timecardId,
                            departmentId = fromServer.departmentId,
                        ),
                    )
                }
            }
        }
    }

    private fun blankWeek(weekStarting: Long?): List<TimecardDay> {
        val monday = weekStarting ?: currentWeekStarting()
        return (0 until DAYS_IN_WEEK).map { offset ->
            TimecardDay(
                date = monday + offset * DAY_MILLIS,
                dayType = DayType.NotWorked,
                callTime = null,
                wrapTime = null,
                note = null,
            )
        }
    }

    private fun saveDraft() {
        val draft = currentState.draft ?: return
        val invalid = draft.validationError()
        if (invalid != null) {
            sendEffect(TimecardEffect.Failed(invalid))
            return
        }
        val support = offline
        if (support != null && support.isOffline) {
            launch { queueSave(support, draft) }
            return
        }
        launch {
            setState { copy(busy = true) }
            when (val result = repository.save(draft)) {
                is ZillitResult.Success -> {
                    sendNoteIfChanged(result.data, draft)
                    forgetDraft(draft.weekStarting)
                    setState { copy(busy = false, notice = "Timecard saved") }
                    load(currentState.destination)
                }

                is ZillitResult.Failure -> {
                    // The request never left this machine: queue it rather than
                    // lose the week. Anything else is reported; the grid keeps
                    // its hours either way.
                    if (support != null && result.error is ZillitError.NoConnection) {
                        queueSave(support, draft)
                    } else {
                        setState { copy(busy = false) }
                        sendEffect(TimecardEffect.Failed(result.error.localised()))
                    }
                }
            }
        }
    }

    /**
     * What the notes field held when the editor opened this week — the yard
     * stick for "did the user actually write something new". Compared against
     * this rather than the list rows because `my-summary` is the slim
     * projection and never carries notes, so a list comparison would re-post
     * the same note on every save.
     */
    private var seededNotes: String = ""

    /**
     * Notes never ride the save body — the web appends them one at a time
     * through `POST /weekly/:id/notes` (`timecards.js:114-126`, an append-only
     * array). Sent only when the text actually changed, or every save would
     * stack a duplicate note; best effort, because the week itself is already
     * saved.
     */
    private suspend fun sendNoteIfChanged(savedId: String?, draft: TimecardDraft) {
        val note = draft.notes.trim()
        if (savedId == null || note.isEmpty() || note == seededNotes) return
        if (repository.addNote(savedId, note) is ZillitResult.Success) seededNotes = note
    }

    // -- offline: the queue ----------------------------------------------------

    private suspend fun queueSave(support: OfflineSupport, draft: TimecardDraft) {
        val queued = QueuedTimecardSave(draft = draft, userId = currentState.viewer.userId, queuedAt = nowMillis())
        val enqueued = support.engine.enqueue(
            NewOperation(
                kind = TIMECARD_SAVE_KIND,
                label = "Timecard: week of ${EpochDate.date(draft.weekStarting)}",
                payload = json.encodeToString(QueuedTimecardSave.serializer(), queued),
                groupKey = weekGroup(draft.weekStarting),
            ),
        )
        if (enqueued == null) {
            setState { copy(busy = false) }
            sendEffect(TimecardEffect.Failed("Open a production before saving a timecard."))
            return
        }
        forgetDraft(draft.weekStarting)
        setState { copy(busy = false, notice = QUEUED_SAVE_NOTICE) }
        refreshLocalWeeks()
    }

    /**
     * Queues the submit behind the week's queued save when there is one, so
     * it runs once the server has the week and its id.
     */
    private suspend fun queueSubmit(support: OfflineSupport, targetId: String) {
        val week = currentState.selected?.takeIf { it.id == targetId }
        val pendingSave = support.engine.operations()
            .firstOrNull { it.kind == TIMECARD_SAVE_KIND && it.isOpen && it.groupKey == weekGroup(week?.weekStarting) }
        val serverId = targetId.takeUnless { it.startsWith(LOCAL_WEEK_PREFIX) }
        val enqueued = support.engine.enqueue(
            NewOperation(
                kind = TIMECARD_SUBMIT_KIND,
                label = "Submit timecard: week of ${EpochDate.date(week?.weekStarting)}",
                payload = json.encodeToString(
                    QueuedTimecardSubmit.serializer(),
                    QueuedTimecardSubmit(timecardId = serverId, weekStarting = week?.weekStarting),
                ),
                groupKey = weekGroup(week?.weekStarting),
                dependsOn = pendingSave?.id,
            ),
        )
        if (enqueued == null) {
            sendEffect(TimecardEffect.Failed("Open a production before submitting a timecard."))
            return
        }
        setState { copy(notice = QUEUED_SUBMIT_NOTICE) }
        refreshLocalWeeks()
    }

    private fun watchSync() {
        val support = offline ?: return
        syncWatch?.cancel()
        syncWatch = launch {
            var pending = support.engine.status.value.pending
            support.engine.status.collect { status ->
                setState { copy(offline = !status.online) }
                refreshLocalWeeks()
                // Something queued has gone through: the server now has the
                // week where the local row was, so the list is fetched again.
                if (status.online && status.pending < pending) load(currentState.destination)
                pending = status.pending
            }
        }
    }

    private suspend fun refreshLocalWeeks() {
        val support = offline ?: return
        val operations = support.engine.operations()
        val submits = operations.filter { it.kind == TIMECARD_SUBMIT_KIND }
        setState { copy(localTimecards = operations.mapNotNull { it.toLocalTimecard(submits, json) }) }
    }

    // -- offline: the draft on disk ------------------------------------------------

    private fun scheduleDraftSave() {
        val support = offline ?: return
        draftSaveJob?.cancel()
        draftSaveJob = launch {
            delay(DRAFT_SAVE_DEBOUNCE_MILLIS)
            val draft = currentState.draft ?: return@launch
            val scope = support.currentScope() ?: return@launch
            support.drafts.save(
                LocalDraft(
                    id = draftId(scope.userId, scope.projectId, draft.weekStarting),
                    scope = scope,
                    kind = DRAFT_KIND,
                    payload = json.encodeToString(TimecardDraft.serializer(), draft),
                    updatedAt = nowMillis(),
                ),
            )
        }
    }

    private suspend fun restoreDraft(weekStarting: Long?, newerThan: Long?): TimecardDraft? {
        val scope = offline?.currentScope() ?: return null
        val saved = offline?.drafts?.get(draftId(scope.userId, scope.projectId, weekStarting))
            ?.takeIf { newerThan == null || it.updatedAt > newerThan }
            ?: return null
        return runCatching { json.decodeFromString(TimecardDraft.serializer(), saved.payload) }.getOrNull()
    }

    private suspend fun forgetDraft(weekStarting: Long?) {
        val support = offline ?: return
        val scope = support.currentScope() ?: return
        draftSaveJob?.cancel()
        support.drafts.delete(draftId(scope.userId, scope.projectId, weekStarting))
    }

    // -- offline: the read cache -------------------------------------------------

    private suspend fun <T> ZillitResult<T>.rememberOrRecall(name: String, serializer: KSerializer<T>): T? =
        when (this) {
            is ZillitResult.Success -> data.also { remember(name, serializer, it) }
            is ZillitResult.Failure -> recallIfUnreachable(error, name, serializer)?.first
        }

    private suspend fun <T> remember(name: String, serializer: KSerializer<T>, value: T) {
        val support = offline ?: return
        val scope = support.currentScope() ?: return
        support.cache.put(scope, name, json.encodeToString(serializer, value), nowMillis())
    }

    /** The saved copy, with when it was fetched — only when the failure is the network, not the server. */
    private suspend fun <T> recallIfUnreachable(
        error: ZillitError,
        name: String,
        serializer: KSerializer<T>,
    ): Pair<T, Long>? {
        val scope = offline?.currentScope()
        if (!error.isUnreachable() || scope == null) return null
        val cached = offline?.cache?.get(scope, name) ?: return null
        return runCatching { json.decodeFromString(serializer, cached.json) }.getOrNull()
            ?.let { it to cached.fetchedAt }
    }

    @Suppress("CyclomaticComplexMethod") // One branch per confirmable action.
    private fun resolvePrompt() {
        val prompt = currentState.prompt ?: return
        setState { copy(prompt = null) }
        when (prompt) {
            is TimecardPrompt.Confirm -> when (prompt.action) {
                TimecardConfirmAction.Submit -> submitWeek(prompt.targetId)
                TimecardConfirmAction.Approve -> act("Approved") { repository.approve(prompt.targetId, null) }
                TimecardConfirmAction.FinalApprove ->
                    act("Final approved") { repository.finalApprove(prompt.targetId) }

                TimecardConfirmAction.Lock -> act("Week locked") { repository.lock(prompt.targetId) }
                TimecardConfirmAction.MarkPaid -> act("Marked paid") { repository.markPaid(prompt.targetId) }
                TimecardConfirmAction.ApproveSelected -> batch("approved") { repository.approveAll(it) }
                TimecardConfirmAction.LockSelected -> batch("locked") { repository.lockAll(it) }
            }

            is TimecardPrompt.WithReason -> {
                val reason = prompt.reason.trim()
                if (reason.isEmpty()) {
                    sendEffect(TimecardEffect.Failed("A reason is required."))
                    setState { copy(prompt = prompt) }
                    return
                }
                when (prompt.action) {
                    TimecardReasonAction.Reject ->
                        act("Timecard rejected") { repository.reject(prompt.targetId, reason) }

                    TimecardReasonAction.Query ->
                        act("Query sent") { repository.query(prompt.targetId, reason) }
                }
            }

            is TimecardPrompt.Deduct -> {
                val amount = prompt.amount.trim().toDoubleOrNull()
                if (prompt.label.isBlank() || amount == null || amount <= 0) {
                    sendEffect(TimecardEffect.Failed("A deduction needs a label and an amount."))
                    setState { copy(prompt = prompt) }
                    return
                }
                act("Deduction added") {
                    repository.addDeduction(
                        prompt.targetId,
                        prompt.label.trim(),
                        amount,
                        prompt.nominalCode.takeIf(String::isNotBlank),
                    )
                }
            }
        }
    }

    /**
     * Submits a week: straight to the server when it is there and we are
     * online; through the queue when either is not — a week saved offline is
     * only on this computer, and its submit rides behind its save.
     */
    private fun submitWeek(targetId: String) {
        val support = offline
        val local = targetId.startsWith(LOCAL_WEEK_PREFIX)
        if (support != null && (local || support.isOffline)) {
            launch { queueSubmit(support, targetId) }
            return
        }
        if (local) {
            sendEffect(TimecardEffect.Failed(NOT_ON_SERVER_MESSAGE))
            return
        }
        act("Timecard submitted") { repository.submit(targetId) }
    }

    private fun batch(verb: String, block: suspend (List<String>) -> ZillitResult<Unit>) {
        val ids = currentState.selection.toList()
        if (ids.isEmpty()) {
            sendEffect(TimecardEffect.Failed("Nothing is selected."))
            return
        }
        act("${ids.size} timecard(s) $verb") { block(ids) }
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
                sendEffect(TimecardEffect.Failed(result.error.localised()))
            }
        }
    }

    /** Keyed by what is fetched, not which tab asked — My Timecards and the editor share one list. */
    private val TimecardDestination.cacheName: String
        get() = when (this) {
            TimecardDestination.ApprovalQueue -> "timecard.list.approval"
            TimecardDestination.Processing -> "timecard.list.processing"
            TimecardDestination.Outstanding -> "timecard.list.outstanding"
            TimecardDestination.MyWeeks, TimecardDestination.Edit -> "timecard.list.my"
        }

    /** The pages that draw the person's own weeks — the ones with a local row to show. */
    private val TimecardDestination.worksOffline: Boolean
        get() = this == TimecardDestination.MyWeeks || this == TimecardDestination.Edit

    private fun ZillitError.isUnreachable() = this is ZillitError.NoConnection || this is ZillitError.Timeout

    private fun weekGroup(weekStarting: Long?) = "timecard:${weekStarting ?: "unknown"}"

    private fun draftId(userId: String, projectId: String, weekStarting: Long?) =
        "timecard.draft:$userId:$projectId:${weekStarting ?: "unknown"}"

    companion object {
        const val DRAFT_KIND = "timecard.draft"
        const val METADATA_CACHE = "timecard.metadata"
        const val ALLOWANCES_CACHE = "timecard.allowances"
        const val QUEUED_SAVE_NOTICE = "Saved on this computer — it will be sent when you're back online."
        const val QUEUED_SUBMIT_NOTICE = "Will be submitted as soon as you're back online."
        const val NOT_ON_SERVER_MESSAGE = "This week is still waiting to be sent; it can be submitted once it is."
        private const val DAYS_IN_WEEK = 7
        private const val DAY_MILLIS = 86_400_000L
        private const val DRAFT_SAVE_DEBOUNCE_MILLIS = 400L

        /** The web's refetch coalescing window — accountHubListeners.js `DEBOUNCE_MS`. */
        const val SYNC_DEBOUNCE_MILLIS = 500L
    }
}

/** Whether an event changes the open week — and so should reach the disk. */
private val TimecardEvent.editsTheWeek: Boolean
    get() = this is TimecardEvent.EditDay || this is TimecardEvent.EditNotes ||
        this is TimecardEvent.AddAllowance || this is TimecardEvent.EditAllowance ||
        this is TimecardEvent.RemoveAllowance

/** The tone a timecard status is drawn in. */
internal val TimecardStatus.isTerminal: Boolean get() = this == TimecardStatus.Paid

/** Replaces one day, leaving the rest of the week alone. */
private fun TimecardDraft.withDay(index: Int, day: TimecardDay): TimecardDraft =
    copy(days = days.mapIndexed { at, existing -> if (at == index) day else existing })
