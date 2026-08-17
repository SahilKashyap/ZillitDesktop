package com.zillit.desktop.feature.timecard.ui

import com.zillit.desktop.core.common.EpochDate
import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.mvvm.ZillitViewModel
import com.zillit.desktop.feature.timecard.domain.Allowance
import com.zillit.desktop.feature.timecard.domain.AllowanceType
import com.zillit.desktop.feature.timecard.domain.DayType
import com.zillit.desktop.feature.timecard.domain.Timecard
import com.zillit.desktop.feature.timecard.domain.TimecardDay
import com.zillit.desktop.feature.timecard.domain.TimecardDraft
import com.zillit.desktop.feature.timecard.domain.TimecardHistoryEntry
import com.zillit.desktop.feature.timecard.domain.TimecardRepository
import com.zillit.desktop.feature.timecard.domain.TimecardStatus
import com.zillit.desktop.feature.timecard.domain.TimecardViewer
import kotlinx.coroutines.Job

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
) {
    val selected: Timecard? get() = timecards.firstOrNull { it.id == selectedId }

    val visibleDestinations: List<TimecardDestination>
        get() = TimecardDestination.entries.filter { it.visibleTo(viewer) }

    val rows: List<Timecard>
        get() = timecards.filter { card ->
            search.isBlank() ||
                card.crewName.lowercase().contains(search.trim().lowercase()) ||
                EpochDate.date(card.weekStarting).lowercase().contains(search.trim().lowercase())
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

    /** Adding a deduction needs both a label and an amount. */
    data class Deduct(
        val targetId: String,
        val label: String = "",
        val amount: String = "",
        val reason: String = "",
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
class TimecardViewModel(
    private val repository: TimecardRepository,
    private val viewer: () -> TimecardViewer,
    /** The current week's Monday, so a fresh draft knows what it covers. */
    private val currentWeekStarting: () -> Long,
) : ZillitViewModel<TimecardUiState, TimecardEvent, TimecardEffect>(
    TimecardUiState(viewer = viewer()),
) {

    private var loadJob: Job? = null
    private var started = false

    fun start() {
        if (started) return
        started = true
        launch {
            val identity = viewer()
            val metadata = repository.metadata().getOrNull()
            // The allowance catalogue rides along: the week grid cannot offer a
            // claim without it, and fetching it on first use would put a wait
            // in front of a keystroke.
            val allowances = repository.allowanceTypes().getOrNull().orEmpty()
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
        start()
    }

    @Suppress("CyclomaticComplexMethod") // One branch per user action.
    override fun onEvent(event: TimecardEvent) {
        when (event) {
            TimecardEvent.Refresh -> load(currentState.destination)
            is TimecardEvent.Open -> {
                setState { copy(destination = event.destination, selectedId = null, error = null) }
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

            is TimecardEvent.AddAllowance -> setState {
                val current = draft ?: return@setState this
                val day = current.days.getOrNull(event.dayIndex) ?: return@setState this
                // A once-a-day allowance claimed twice is a mistake the server
                // would reject, so the second claim is dropped. Only a per-unit
                // allowance — mileage — genuinely stacks.
                val existing = day.allowances.indexOfFirst { it.code == event.type.code }
                val claims = when {
                    existing >= 0 && !event.type.perUnit -> day.allowances
                    else -> day.allowances + event.type.claim()
                }
                copy(draft = current.withDay(event.dayIndex, day.copy(allowances = claims)))
            }

            is TimecardEvent.EditAllowance -> setState {
                val current = draft ?: return@setState this
                val day = current.days.getOrNull(event.dayIndex) ?: return@setState this
                val claims = day.allowances.mapIndexed { index, allowance ->
                    if (index == event.allowanceIndex) event.allowance else allowance
                }
                copy(draft = current.withDay(event.dayIndex, day.copy(allowances = claims)))
            }

            is TimecardEvent.RemoveAllowance -> setState {
                val current = draft ?: return@setState this
                val day = current.days.getOrNull(event.dayIndex) ?: return@setState this
                val claims = day.allowances.filterIndexed { index, _ -> index != event.allowanceIndex }
                copy(draft = current.withDay(event.dayIndex, day.copy(allowances = claims)))
            }
        }
    }

    private fun load(destination: TimecardDestination) {
        loadJob?.cancel()
        setState { copy(loading = true, error = null) }
        loadJob = launch {
            val result = when (destination) {
                TimecardDestination.ApprovalQueue -> repository.approvalQueue()
                TimecardDestination.Processing ->
                    repository.payrollProcessing(EpochDate.isoDate(currentWeekStarting()))

                TimecardDestination.Outstanding -> repository.outstanding()
                else -> repository.myTimecards()
            }
            when (result) {
                is ZillitResult.Success -> {
                    setState { copy(loading = false, timecards = result.data) }
                    // Opening the editor with nothing to edit is a dead end, so
                    // the draft is seeded from this week as soon as the list
                    // that would contain it has arrived.
                    if (destination == TimecardDestination.Edit && currentState.draft == null) {
                        loadDraft(result.data.firstOrNull { it.isEditable }?.id)
                    }
                }

                is ZillitResult.Failure -> setState { copy(loading = false, error = result.error) }
            }
        }
    }

    private fun selectCard(id: String?) {
        setState { copy(selectedId = id, history = emptyList()) }
        if (id == null) return
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
        if (existing != null) {
            setState {
                copy(
                    draft = TimecardDraft(
                        timecardId = existing.id,
                        weekStarting = existing.weekStarting,
                        days = existing.days.ifEmpty { blankWeek(existing.weekStarting) },
                        notes = existing.notes.orEmpty(),
                    ),
                )
            }
            return
        }
        val monday = currentWeekStarting()
        setState {
            copy(draft = TimecardDraft(timecardId = null, weekStarting = monday, days = blankWeek(monday)))
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
        act("Timecard saved") { repository.save(draft) }
    }

    @Suppress("CyclomaticComplexMethod") // One branch per confirmable action.
    private fun resolvePrompt() {
        val prompt = currentState.prompt ?: return
        setState { copy(prompt = null) }
        when (prompt) {
            is TimecardPrompt.Confirm -> when (prompt.action) {
                TimecardConfirmAction.Submit -> act("Timecard submitted") { repository.submit(prompt.targetId) }
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
                        prompt.reason.takeIf(String::isNotBlank),
                    )
                }
            }
        }
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
                sendEffect(TimecardEffect.Failed(result.error.userMessage))
            }
        }
    }

    private companion object {
        const val DAYS_IN_WEEK = 7
        const val DAY_MILLIS = 86_400_000L
    }
}

/** The tone a timecard status is drawn in. */
internal val TimecardStatus.isTerminal: Boolean get() = this == TimecardStatus.Paid

/** Replaces one day, leaving the rest of the week alone. */
private fun TimecardDraft.withDay(index: Int, day: TimecardDay): TimecardDraft =
    copy(days = days.mapIndexed { at, existing -> if (at == index) day else existing })
