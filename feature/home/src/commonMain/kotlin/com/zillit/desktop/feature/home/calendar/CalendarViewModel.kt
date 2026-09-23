package com.zillit.desktop.feature.home.calendar

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.mvvm.ZillitViewModel
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

data class CalendarUiState(
    val anchor: LocalDate,
    /**
     * The real today, for the highlight.
     *
     * Carried on the state rather than read from a clock in the composable: a
     * screen that reads the date while drawing cannot be tested, and would keep
     * highlighting yesterday until something else caused a recomposition.
     */
    val today: LocalDate = anchor,
    val mode: CalendarViewMode = CalendarViewMode.Month,
    val events: List<CalendarEvent> = emptyList(),
    val selected: LocalDate? = null,
    val isBusy: Boolean = false,
    val error: String? = null,
    val weekStart: DayOfWeek = DayOfWeek.MONDAY,
    /** The event whose popover is open, if any. */
    val detail: EventDetailState? = null,
    /** The event being written, if any. */
    val form: EventFormState? = null,
    /** The received-invitations panel: open, which tab, and its rows. */
    val invitationsOpen: Boolean = false,
    val invitationStatus: InvitationStatus = InvitationStatus.Pending,
    val invitations: List<EventInvitation> = emptyList(),
    val invitationsBusy: Boolean = false,
    /** The way to the next invitations page; null when the list is complete. */
    val invitationsCursor: String? = null,
    /** How many invitations await an answer — the toolbar badge. */
    val pendingInvitations: Int = 0,
    /** The invitation a decline reason is being written for, or null. */
    val declining: EventInvitation? = null,
    /**
     * A drag-drop reschedule awaiting the organiser's yes. The drop no longer
     * saves on its own — an event nudged by a stray drag moved a meeting
     * nobody meant to move, silently.
     */
    val pendingReschedule: PendingReschedule? = null,
    val zone: TimeZone = TimeZone.currentSystemDefault(),
) {
    val grid: MonthGrid get() = monthGrid(anchor, weekStart)

    val week: List<LocalDate> get() = weekOf(anchor, weekStart)

    /** The day panel: whichever day is selected, else the anchor. */
    val focusedDay: LocalDate get() = selected ?: anchor

    fun eventsOn(date: LocalDate): List<CalendarEvent> = events.on(date, zone)

    val title: String
        get() = "${anchor.month.name.lowercase().replaceFirstChar { it.uppercase() }} ${anchor.year}"
}

/** One drop's proposal: the event as it was, and the times it would take. */
data class PendingReschedule(
    val event: CalendarEvent,
    val newStart: Long,
    val newEnd: Long,
)

/**
 * The open event, and what can be done with it.
 *
 * Derived once when the popover opens rather than recomputed while it is up:
 * permissions depend on "now", and an event that expires mid-read should not
 * have its buttons vanish under the cursor.
 */
data class EventDetailState(
    val event: CalendarEvent,
    val permissions: EventPermissions,
    val hasFinished: Boolean,
    /** The event's call can be joined right now. See [canJoinCall]. */
    val canJoinCall: Boolean = false,
    val dateLabel: String,
    val timeLabel: String,
    val isBusy: Boolean = false,
    val isConfirmingDelete: Boolean = false,
    val error: String? = null,
)

/**
 * The event form.
 *
 * [invitees] is the production's crew, which the session already holds — so
 * opening the form costs no request.
 */
data class EventFormState(
    val draft: EventDraft,
    /** For the picker's "today" highlight. */
    val today: LocalDate,
    val invitees: List<EventInvitee> = emptyList(),
    /** The server's timezone list; empty until it arrives. */
    val timezones: List<TimezoneOption> = emptyList(),
    /**
     * The device's zone, as the fallback the typed times are read in. The form
     * needs it to convert them when the picker changes zone.
     */
    val zone: TimeZone = TimeZone.currentSystemDefault(),
    val errors: Set<EventFieldError> = emptySet(),
    val recurrenceErrors: Set<RecurrenceError> = emptySet(),
    val isSaving: Boolean = false,
    val error: String? = null,
)

/** Someone who can be invited. */
data class EventInvitee(
    val userId: String,
    val name: String,
    /** Shown under the name in the picker; null when the crew record has none. */
    val designation: String? = null,
)

sealed interface CalendarEvent2Event {

    /** Anything that acts on a single event — its popover or its form. */
    sealed interface Event : CalendarEvent2Event

    /** Opens the form. Null creates; an event edits it. */
    data class OpenForm(val event: CalendarEvent?) : Event
    data object CloseForm : Event
    data class FormChanged(val draft: EventDraft) : Event
    data object SaveForm : Event

    /** Opens the detail popover. */
    data class OpenDetail(val event: CalendarEvent) : Event
    data object CloseDetail : Event

    data class RespondToInvite(val accept: Boolean) : Event

    data object AskDeleteEvent : Event
    data object ConfirmDeleteEvent : Event
    data object DismissDeleteEvent : Event

    data object Previous : CalendarEvent2Event
    data object Next : CalendarEvent2Event
    data object Today : CalendarEvent2Event
    data class Select(val date: LocalDate) : CalendarEvent2Event
    data class SetMode(val mode: CalendarViewMode) : CalendarEvent2Event
    data object Reload : CalendarEvent2Event

    /** A different production opened: its calendar is not this one's. */
    data object ProjectChanged : CalendarEvent2Event
    data object DismissError : CalendarEvent2Event

    /** The received-invitations panel. */
    data object ShowInvitations : CalendarEvent2Event
    data object HideInvitations : CalendarEvent2Event

    /** The panel's "Load more": continue from the current cursor. */
    data object LoadMoreInvitations : CalendarEvent2Event
    data class SetInvitationTab(val status: InvitationStatus) : CalendarEvent2Event
    data class AnswerInvitation(val invitation: EventInvitation, val accept: Boolean) :
        CalendarEvent2Event

    /** The decline-reason dialog's two exits. */
    data class ConfirmDecline(val reason: String) : CalendarEvent2Event
    data object CancelDecline : CalendarEvent2Event

    /**
     * A drag ended: move [event] by whole days and minutes. The UI reports
     * *deltas* rather than a new timestamp so the wall time can be recomputed
     * in the event's zone — adding raw millis breaks across DST.
     */
    data class MoveEvent(val event: CalendarEvent, val dayDelta: Int, val minuteDelta: Int) :
        CalendarEvent2Event

    /** A resize drag ended: stretch or shrink [event]'s end by minutes. */
    data class ResizeEvent(val event: CalendarEvent, val minuteDelta: Int) : CalendarEvent2Event

    /** The confirmation's yes: apply the drop held in [CalendarUiState.pendingReschedule]. */
    data object ConfirmReschedule : CalendarEvent2Event

    /** The confirmation's no: the drop is forgotten and the event stays put. */
    data object CancelReschedule : CalendarEvent2Event

    /**
     * Somebody else changed the calendar — reload rather than patch.
     *
     * Raised by the socket, never by the screen. See [CalendarRealtimeKind].
     */
    data class Realtime(val kind: CalendarRealtimeKind) : CalendarEvent2Event
}

/**
 * Drives the calendar.
 *
 * Fetches by **visible window** rather than by month: the grid always shows
 * whole weeks, so a month view genuinely displays days either side of the month
 * and fetching only the month would leave them blank.
 */
@Suppress("TooManyFunctions") // One handler per user act, as every calendar has acts.
class CalendarViewModel(
    private val repository: CalendarRepository,
    private val today: () -> LocalDate,
    /** The signed-in user, for deciding who may delete an event. */
    private val currentUserId: () -> String? = { null },
    /**
     * The production's crew, for the invitee list.
     *
     * Read from the session the app already holds, so opening the form costs no
     * request — and works offline for the people most events go to.
     */
    private val invitees: () -> List<EventInvitee> = { emptyList() },
    /**
     * The clock, for the rules about whether something has already happened —
     * a date in the past, a reminder that would have to fire before now.
     * Injected so those rules can be tested at a fixed moment rather than
     * passing until whatever time of day breaks them.
     */
    private val now: () -> Instant = { kotlin.time.Clock.System.now() },
) : ZillitViewModel<CalendarUiState, CalendarEvent2Event, Nothing>(
    CalendarUiState(anchor = today(), today = today()),
) {

    // No eager load: the events call carries project and user in its header.

    /** The server's timezone list, fetched once per session — it never moves. */
    private var timezoneCache: List<TimezoneOption> = emptyList()

    // Exhaustive dispatch over the sealed event set — the branch count is the
    // pattern, not a complexity problem (see ChatViewModel and LiveKitWire).
    @Suppress("CyclomaticComplexMethod")
    override fun onEvent(event: CalendarEvent2Event) {
        when (event) {
            CalendarEvent2Event.Previous -> shift(-1)
            CalendarEvent2Event.Next -> shift(1)
            CalendarEvent2Event.Today -> setState { copy(anchor = today(), selected = today()) }
            is CalendarEvent2Event.Select -> setState { copy(selected = event.date) }
            is CalendarEvent2Event.SetMode -> setState { copy(mode = event.mode) }
            CalendarEvent2Event.Reload -> load()
            is CalendarEvent2Event.Realtime -> onRealtime(event.kind)
            CalendarEvent2Event.ProjectChanged -> {
                // Events, invitations and any open form belong to the
                // production that was open when they were fetched.
                setState {
                    copy(
                        events = emptyList(),
                        invitations = emptyList(),
                        invitationsCursor = null,
                        pendingInvitations = 0,
                        detail = null,
                        form = null,
                        declining = null,
                        error = null,
                    )
                }
                load()
            }
            CalendarEvent2Event.DismissError -> setState { copy(error = null) }
            is CalendarEvent2Event.MoveEvent ->
                moveEvent(event.event, event.dayDelta, event.minuteDelta)
            is CalendarEvent2Event.ResizeEvent -> resizeEvent(event.event, event.minuteDelta)
            CalendarEvent2Event.ConfirmReschedule, CalendarEvent2Event.CancelReschedule ->
                answerReschedule(confirmed = event == CalendarEvent2Event.ConfirmReschedule)
            is CalendarEvent2Event.Event -> onEventAction(event)
            else -> onInvitationEvent(event)
        }
    }

    /** The received-invitations panel's events, split out for onEvent's size. */
    private fun onInvitationEvent(event: CalendarEvent2Event) {
        when (event) {
            CalendarEvent2Event.ShowInvitations -> {
                setState { copy(invitationsOpen = true) }
                loadInvitations(currentState.invitationStatus)
            }
            CalendarEvent2Event.HideInvitations -> setState { copy(invitationsOpen = false) }
            is CalendarEvent2Event.SetInvitationTab -> loadInvitations(event.status)
            CalendarEvent2Event.LoadMoreInvitations ->
                loadInvitations(currentState.invitationStatus, append = true)
            is CalendarEvent2Event.AnswerInvitation ->
                answerInvitation(event.invitation, event.accept)
            is CalendarEvent2Event.ConfirmDecline -> confirmDecline(event.reason)
            CalendarEvent2Event.CancelDecline -> setState { copy(declining = null) }
            else -> Unit
        }
    }

    private fun loadInvitations(status: InvitationStatus, append: Boolean = false) {
        // Appending continues from the cursor; a fresh load starts over.
        val cursor = if (append) currentState.invitationsCursor ?: return else null
        setState {
            if (append) {
                copy(invitationsBusy = true)
            } else {
                copy(invitationStatus = status, invitationsBusy = true, invitationsCursor = null)
            }
        }
        launchResult(
            block = { repository.invitations(status, cursor) },
            onSuccess = { page ->
                setState {
                    // Only if this tab is still the one being looked at.
                    if (invitationStatus != status) return@setState this
                    val merged = if (append) {
                        (invitations + page.rows).distinctBy { it.id }
                    } else {
                        page.rows
                    }
                    copy(
                        invitationsBusy = false,
                        invitations = merged,
                        invitationsCursor = page.nextCursor,
                        pendingInvitations = if (status == InvitationStatus.Pending) {
                            merged.size
                        } else {
                            pendingInvitations
                        },
                    )
                }
            },
            onError = { error ->
                setState { copy(invitationsBusy = false, error = error.localised()) }
            },
        )
    }

    /**
     * Accept or decline from the panel.
     *
     * The event's own start rides in the body — a recurring invitation is
     * answered per occurrence. Success refetches the open tab *and* the board:
     * an accepted event belongs on the grid the moment it is accepted.
     */
    private fun answerInvitation(invitation: EventInvitation, accept: Boolean) {
        // Declining asks for a reason first — the organiser reads it, and a
        // silent decline from a crew member reads as a missed one.
        if (!accept) {
            setState { copy(declining = invitation) }
            return
        }
        sendAnswer { repository.accept(invitation.eventId, invitation.event?.startMillis ?: 0) }
    }

    private fun confirmDecline(reason: String) {
        val invitation = currentState.declining ?: return
        // Closes the popover as well when the decline came from one — the
        // answer is given, and the reload brings back the server's view.
        setState { copy(declining = null, detail = null) }
        sendAnswer {
            repository.decline(
                invitation.eventId,
                invitation.event?.startMillis ?: 0,
                reason.trim().takeIf { it.isNotBlank() },
            )
        }
    }

    /** One answer's round trip: the open tab refetches, the board reloads. */
    private fun sendAnswer(block: suspend () -> ZillitResult<Unit>) {
        setState { copy(invitationsBusy = true) }
        launchResult(
            block = block,
            onSuccess = {
                markSelfAction()
                loadInvitations(currentState.invitationStatus)
                load()
            },
            onError = { error ->
                setState { copy(invitationsBusy = false, error = error.localised()) }
            },
        )
    }

    /**
     * Opens the form to create or edit.
     *
     * A new event is dated to whichever day is in focus, which is nearly always
     * the one the user just clicked — typing today's date into a form you
     * opened from today's cell is the sort of thing that makes a calendar feel
     * like paperwork.
     */
    private fun openForm(event: CalendarEvent?) {
        val state = currentState
        val draft = event?.toDraft(state.zone)
            ?: newEventDraft(state.focusedDay, now(), state.zone)

        setState {
            copy(
                detail = null,
                form = EventFormState(
                    draft = draft,
                    today = state.today,
                    invitees = invitees(),
                    timezones = timezoneCache,
                    zone = state.zone,
                ),
            )
        }

        if (timezoneCache.isEmpty()) {
            launchResult(
                block = { repository.timezones() },
                onSuccess = { zones ->
                    timezoneCache = zones
                    setState { copy(form = form?.copy(timezones = zones)) }
                },
                // The picker simply stays empty; the device zone still applies.
                onError = { },
            )
        }
    }

    private fun saveForm() {
        val form = currentState.form ?: return
        val checked = form.draft.validate(currentState.zone, now())
        val repeatErrors = form.draft.recurrence.validate(form.draft.dateText)

        if (!checked.isValid || repeatErrors.isNotEmpty()) {
            setState {
                copy(form = form.copy(errors = checked.errors, recurrenceErrors = repeatErrors))
            }
            return
        }

        setState { copy(form = form.copy(isSaving = true, error = null)) }

        launchResult(
            block = { repository.save(form.draft, checked.times!!, currentState.zone) },
            onSuccess = {
                markSelfAction()
                setState { copy(form = null) }
                load()
            },
            // The form stays open with everything typed in it. Losing a written
            // event to a network blip is a small thing done twice.
            onError = { error ->
                setState { copy(form = form.copy(isSaving = false, error = error.localised())) }
            },
        )
    }

    /**
     * Everything that acts on one event.
     *
     * Split out of [onEvent] so the dispatcher stays readable — these ten cases
     * are two features, not ten.
     */
    private fun onEventAction(event: CalendarEvent2Event.Event) {
        when (event) {
            is CalendarEvent2Event.OpenDetail -> openDetail(event.event)
            CalendarEvent2Event.CloseDetail -> setState { copy(detail = null) }
            is CalendarEvent2Event.RespondToInvite -> respond(event.accept)
            CalendarEvent2Event.AskDeleteEvent -> setState {
                copy(detail = detail?.copy(isConfirmingDelete = true))
            }
            CalendarEvent2Event.DismissDeleteEvent -> setState {
                copy(detail = detail?.copy(isConfirmingDelete = false))
            }
            CalendarEvent2Event.ConfirmDeleteEvent -> deleteEvent()
            is CalendarEvent2Event.OpenForm -> openForm(event.event)
            CalendarEvent2Event.CloseForm -> setState { copy(form = null) }
            is CalendarEvent2Event.FormChanged -> setState {
                // Errors clear as the user types rather than persisting until
                // the next save, which would keep a field red they have fixed.
                copy(
                    form = form?.copy(
                        draft = event.draft,
                        errors = emptySet(),
                        recurrenceErrors = emptySet(),
                    ),
                )
            }
            CalendarEvent2Event.SaveForm -> saveForm()
        }
    }

    /**
     * Opens the popover for an event.
     *
     * Permissions and labels are computed here, once, rather than in the
     * composable: they depend on "now", and an event expiring mid-read should
     * not have its buttons disappear under the cursor.
     */
    private fun openDetail(event: CalendarEvent) {
        val state = currentState
        // Midnight today: near enough for "has this finished", and it does
        // not drift while the popover is open.
        val now = state.today.startOfDayMillis(state.zone)

        setState {
            copy(
                detail = EventDetailState(
                    event = event,
                    permissions = permissionsFor(event, isCreator(event), now),
                    hasFinished = event.hasFinished(now),
                    canJoinCall = event.canJoinCall(now),
                    dateLabel = event.dateLabel(state.zone),
                    timeLabel = event.timeLabel(state.zone),
                ),
            )
        }
    }

    private fun respond(accept: Boolean) {
        val detail = currentState.detail ?: return

        // Declining asks for the reason first, exactly as the panel does. The
        // popover's event stands in as its own invitation — the answer
        // endpoint is addressed by event id either way.
        if (!accept) {
            setState {
                copy(
                    declining = EventInvitation(
                        id = detail.event.id,
                        eventId = detail.event.id,
                        status = InvitationStatus.Pending,
                        event = detail.event,
                    ),
                )
            }
            return
        }

        setState { copy(detail = detail.copy(isBusy = true, error = null)) }

        launchResult(
            block = {
                if (accept) {
                    repository.accept(detail.event.id, detail.event.startMillis)
                } else {
                    repository.decline(detail.event.id, detail.event.startMillis)
                }
            },
            onSuccess = {
                // Closed rather than left showing the old status: the answer is
                // given, and the reload brings back what the server now thinks.
                setState { copy(detail = null) }
                load()
            },
            onError = { error ->
                setState { copy(detail = detail.copy(isBusy = false, error = error.localised())) }
            },
        )
    }

    /**
     * Reschedules a dragged event: same duration, same everything else, new
     * start. Optimistically — the block lands where it was dropped and the
     * reload confirms; a refused save reloads the truth back with the error.
     */
    private fun moveEvent(event: CalendarEvent, dayDelta: Int, minuteDelta: Int) {
        if (dayDelta == 0 && minuteDelta == 0) return
        if (!mayReschedule(event)) return

        val zone = currentState.zone
        val start = kotlin.time.Instant.fromEpochMilliseconds(event.startMillis)
            .toLocalDateTime(zone)
        val minuteOfDay = (start.time.toMillisecondOfDay() / MILLIS_PER_MINUTE + minuteDelta)
            .coerceIn(0, MINUTES_PER_DAY - 1)
        val newStart = kotlinx.datetime.LocalDateTime(
            start.date.plus(dayDelta, DateTimeUnit.DAY),
            kotlinx.datetime.LocalTime(minuteOfDay / MINUTES_PER_HOUR, minuteOfDay % MINUTES_PER_HOUR),
        ).toInstant(zone).toEpochMilliseconds()
        val duration = (event.endMillis - event.startMillis).coerceAtLeast(0)

        setState { copy(pendingReschedule = PendingReschedule(event, newStart, newStart + duration)) }
    }

    /**
     * Stretches or shrinks a dragged bottom edge: the start holds still, the
     * end moves by [minuteDelta], and an event can never shrink below a
     * quarter hour — a zero-length block would be undraggable forever after.
     */
    private fun resizeEvent(event: CalendarEvent, minuteDelta: Int) {
        if (minuteDelta == 0) return
        if (!mayReschedule(event)) return

        val newEnd = (event.endMillis + minuteDelta * MILLIS_PER_MINUTE.toLong())
            .coerceAtLeast(event.startMillis + MIN_EVENT_MILLIS)
        if (newEnd == event.endMillis) return

        setState { copy(pendingReschedule = PendingReschedule(event, event.startMillis, newEnd)) }
    }

    /** The shared rules: only the organiser, and never a recurring series. */
    private fun mayReschedule(event: CalendarEvent): Boolean {
        if (!isCreator(event)) {
            setState { copy(error = str(S.desktop_cal_only_organiser_reschedules)) }
            return false
        }
        if (event.isRecurring) {
            setState { copy(error = str(S.desktop_cal_recurring_no_drag)) }
            return false
        }
        return true
    }

    /** The question's answer: clear it either way, save only on a yes. */
    private fun answerReschedule(confirmed: Boolean) {
        val pending = currentState.pendingReschedule ?: return
        setState { copy(pendingReschedule = null) }
        if (confirmed) applyNewTimes(pending.event, pending.newStart, pending.newEnd)
    }

    /** Optimistic swap, then the full-bodied edit; a refusal reloads truth. */
    private fun applyNewTimes(event: CalendarEvent, newStart: Long, newEnd: Long) {
        setState {
            copy(
                events = events.map {
                    if (it.id == event.id) {
                        it.copy(startMillis = newStart, endMillis = newEnd)
                    } else {
                        it
                    }
                },
            )
        }

        launchResult(
            block = {
                repository.save(event.toDraft(currentState.zone), EventTimes(newStart, newEnd), currentState.zone)
            },
            onSuccess = {
                markSelfAction()
                load()
            },
            onError = { error ->
                setState { copy(error = error.localised()) }
                load()
            },
        )
    }

    private fun deleteEvent() {
        val detail = currentState.detail ?: return
        setState { copy(detail = detail.copy(isBusy = true, isConfirmingDelete = false, error = null)) }

        launchResult(
            block = { repository.delete(detail.event.id) },
            onSuccess = {
                markSelfAction()
                setState { copy(detail = null) }
                load()
            },
            onError = { error ->
                setState { copy(detail = detail.copy(isBusy = false, error = error.localised())) }
            },
        )
    }

    /**
     * Whether this user made the event.
     *
     * Unknown without a signed-in user id, and the safe answer is no: showing
     * Delete to someone who cannot delete produces a server rejection they can
     * do nothing about.
     */
    private fun isCreator(event: CalendarEvent): Boolean =
        currentUserId()?.let { it.isNotBlank() && it == event.creatorId } == true

    /** Steps by the unit the current view shows, not always a month. */
    private fun shift(direction: Int) {
        val state = currentState
        val moved = when (state.mode) {
            CalendarViewMode.Month -> state.anchor.plus(direction, DateTimeUnit.MONTH)
            CalendarViewMode.Week -> state.anchor.plus(direction * DAYS_PER_WEEK, DateTimeUnit.DAY)
            CalendarViewMode.Day -> state.anchor.plus(direction, DateTimeUnit.DAY)
        }
        setState { copy(anchor = moved, selected = null) }
        // Only when the move leaves the fetched window.
        if (moved.monthsFrom(state.anchor) != 0) load()
    }

    /**
     * The server echoing back a change this window just made.
     *
     * Every local mutation already reloads on its own success, so acting on
     * the echo too would fetch the same window twice a second apart. Both
     * phones and the web suppress it the same way and with the same two
     * seconds (`CalendarSocketManager.isInSelfActionWindow`,
     * `useCalendarSocketV3.markSelfAction`).
     */
    private var lastSelfActionMillis = 0L

    private fun markSelfAction() {
        lastSelfActionMillis = now().toEpochMilliseconds()
    }

    private val isEchoOfOwnChange: Boolean
        get() = now().toEpochMilliseconds() - lastSelfActionMillis < SELF_ACTION_WINDOW_MILLIS

    /**
     * A change from somebody else.
     *
     * The board always reloads; the invitations list only when it is open,
     * because that is a paged fetch nobody is looking at otherwise. The
     * pending badge rides [load] either way.
     */
    private fun onRealtime(kind: CalendarRealtimeKind) {
        if (isEchoOfOwnChange) return

        load()
        if (kind == CalendarRealtimeKind.Invitations && currentState.invitationsOpen) {
            loadInvitations(currentState.invitationStatus)
        }
    }

    /**
     * Fetches a generous window around what is on screen.
     *
     * Three months either side, so paging between adjacent months needs no
     * refetch and the grid's leading and trailing days are never blank.
     */
    fun load() {
        if (currentState.isBusy) return
        val state = currentState
        setState { copy(isBusy = true, error = null) }

        val from = state.anchor.plus(-WINDOW_MONTHS, DateTimeUnit.MONTH)
            .atStartOfDayIn(state.zone).toEpochMilliseconds()
        val to = state.anchor.plus(WINDOW_MONTHS, DateTimeUnit.MONTH)
            .atStartOfDayIn(state.zone).toEpochMilliseconds()

        launchResult(
            block = { repository.events(from, to) },
            onSuccess = { events -> setState { copy(isBusy = false, events = events) } },
            onError = { setState { copy(isBusy = false, error = it.localised()) } },
        )

        // The toolbar badge — how many invitations await an answer. A failure
        // costs the badge, never the board.
        launchResult(
            block = { repository.invitations(InvitationStatus.Pending) },
            onSuccess = { page -> setState { copy(pendingInvitations = page.rows.size) } },
            onError = { },
        )
    }

    private companion object {
        const val DAYS_PER_WEEK = 7
        const val MILLIS_PER_MINUTE = 60_000
        const val MINUTES_PER_DAY = 24 * 60
        const val MINUTES_PER_HOUR = 60
        const val MIN_EVENT_MILLIS = 15 * 60_000L

        /** The phones' and the web's own suppression window, to the second. */
        const val SELF_ACTION_WINDOW_MILLIS = 2_000L

        /** Months fetched either side of the anchor. */
        const val WINDOW_MONTHS = 3
    }
}

private fun LocalDate.monthsFrom(other: LocalDate): Int =
    (year - other.year) * MONTHS_PER_YEAR + (month.ordinal - other.month.ordinal)

private const val MONTHS_PER_YEAR = 12
