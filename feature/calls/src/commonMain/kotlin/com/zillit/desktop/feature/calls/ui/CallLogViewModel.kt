package com.zillit.desktop.feature.calls.ui

import com.zillit.desktop.core.common.onSuccess
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.mvvm.ZillitViewModel
import com.zillit.desktop.feature.calls.data.CallApi
import com.zillit.desktop.feature.calls.data.livekit.LiveKitActiveCall
import com.zillit.desktop.feature.calls.domain.CallLogEntry
import com.zillit.desktop.feature.calls.domain.CallMode
import com.zillit.desktop.feature.calls.domain.CallType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/** What pressing an ongoing call's button does — the web's `Line3CallJoinButton` states. */
enum class OngoingVerb(val label: String) {
    /** Not in it anywhere: join. */
    Join("Join"),

    /** In it on another device: joining here moves the call off that device. */
    Switch("Switch here"),

    /** It is the call open on this device: surface it, never re-join. */
    Return("Return"),
}

/**
 * A live Line 3 call the Calls tab offers a way into (`ActiveCallInfo` on
 * the web's socket, drawn as the "Ongoing" rows and the per-row buttons).
 */
data class OngoingCall(
    val callId: String,
    val title: String,
    val type: CallType,
    val mode: CallMode,
    val chatRoomId: String,
    /** Who is in the room, named — the join-confirm sheet lists them. */
    val inCall: List<Pair<String, String>>,
    val verb: OngoingVerb,
) {
    val count: Int get() = inCall.size
}

/**
 * Where the Ongoing rows come from and what their buttons do — the four
 * seams as one object, so the host wires the socket's live list, the call
 * open on this device, and the two verbs together or not at all.
 */
class OngoingCallsSource(
    /** The calling socket's live list, never polled. */
    val activeCalls: Flow<List<LiveKitActiveCall>>,
    /** The call open on this device, so its row says Return. */
    val liveCallId: Flow<String?>,
    /** Joins or switches into an ongoing call; the host owns what that means. */
    val onJoin: (OngoingCall) -> Unit,
    /** Brings the call already open on this device back on screen. */
    val onReturn: () -> Unit,
)

/** What the Calls tab is showing. */
data class CallLogUiState(
    val entries: List<CallLogEntry> = emptyList(),
    /**
     * Live Line 3 calls this user may join — the web's `useActiveLine3Calls`:
     * seeded and kept live off the calling socket, never polled.
     */
    val ongoing: List<OngoingCall> = emptyList(),
    /** The ongoing call whose join-confirm sheet is open. */
    val joinConfirm: OngoingCall? = null,
    val missedOnly: Boolean = false,
    val isLoading: Boolean = false,
    /** False once a page comes back short — the server has no more to give. */
    val canLoadMore: Boolean = true,
    /** The search box; the pane filters [entries] by counterpart name with it. */
    val query: String = "",
    /** The trash was pressed and the question is on screen. */
    val confirmingDelete: Boolean = false,
    /** The wipe is on the wire. */
    val isDeleting: Boolean = false,
    /**
     * The last thing to happen was a wipe: the list is empty because it was
     * emptied, and the pane says so in Android's words rather than "No calls
     * yet".
     */
    val deletedAll: Boolean = false,
    /** The row whose Call activity sheet is open; null closes it. */
    val detail: CallLogEntry? = null,
    val error: String? = null,
)

sealed interface CallLogEvent {
    data object ShowAll : CallLogEvent
    data object ShowMissed : CallLogEvent
    data object Refresh : CallLogEvent
    data object LoadMore : CallLogEvent
    data class Redial(val entry: CallLogEntry) : CallLogEvent

    /** The search box moved. */
    data class Search(val query: String) : CallLogEvent

    /** The trash: asks first. */
    data object DeleteAll : CallLogEvent
    data object ConfirmDeleteAll : CallLogEvent
    data object CancelDeleteAll : CallLogEvent

    /** The row's info affordance: opens the Call activity sheet. */
    data class ShowDetail(val entry: CallLogEntry) : CallLogEvent
    data object CloseDetail : CallLogEvent
    data object DismissError : CallLogEvent

    /**
     * An ongoing call's button. Join and Switch ask first — who is in the
     * call — as the web does; Return surfaces the call at once.
     */
    data class PressOngoing(val call: OngoingCall) : CallLogEvent
    data object ConfirmJoinOngoing : CallLogEvent
    data object CancelJoinOngoing : CallLogEvent
}

/**
 * The call history.
 *
 * Paging is a timestamp cursor rather than a page number, matching the server
 * and the other clients: `call/{millis}/previous` reads backwards from an
 * instant, so rows arriving mid-scroll cannot shift a page boundary and make
 * the list repeat or skip.
 */
class CallLogViewModel(
    private val api: CallApi,
    private val selfUserId: () -> String?,
    private val nowMillis: () -> Long,
    /** Rings a row again. The host owns what "ring" means. */
    private val onRedial: (CallLogEntry) -> Unit,
    /**
     * Whose history this is, when it is not the open production's — a widget
     * showing another production. Null is the ambient pair.
     */
    private val projectId: String? = null,
    /** The reader's id ON [projectId]; project-scoped, so not the ambient one. */
    private val callerUserId: String = "",
    /** The Ongoing rows — see [OngoingCall]. Null on a host without Line 3. */
    private val ongoing: OngoingCallsSource? = null,
) : ZillitViewModel<CallLogUiState, CallLogEvent, Nothing>(CallLogUiState()) {

    init {
        launch { load(reset = true) }
        ongoing?.let { source ->
            launch {
                combine(source.activeCalls, source.liveCallId) { calls, live -> calls to live }
                    .collect { (calls, live) ->
                        setState { copy(ongoing = ongoingCalls(calls, live, selfUserId().orEmpty(), projectId)) }
                    }
            }
        }
    }

    override fun onEvent(event: CallLogEvent) {
        when (event) {
            CallLogEvent.ShowAll -> switchTo(missedOnly = false)
            CallLogEvent.ShowMissed -> switchTo(missedOnly = true)
            CallLogEvent.Refresh -> launch { load(reset = true) }
            CallLogEvent.LoadMore -> launch { load(reset = false) }
            is CallLogEvent.Redial -> onRedial(event.entry)
            is CallLogEvent.Search -> setState { copy(query = event.query) }
            CallLogEvent.DeleteAll, CallLogEvent.CancelDeleteAll, CallLogEvent.ConfirmDeleteAll -> onDeleteEvent(event)
            is CallLogEvent.ShowDetail -> setState { copy(detail = event.entry) }
            CallLogEvent.CloseDetail -> setState { copy(detail = null) }
            CallLogEvent.DismissError -> setState { copy(error = null) }
            is CallLogEvent.PressOngoing, CallLogEvent.ConfirmJoinOngoing, CallLogEvent.CancelJoinOngoing ->
                onOngoingEvent(event)
        }
    }

    /** The trash: asks first, and only when there is something to wipe. */
    private fun onDeleteEvent(event: CallLogEvent) {
        when (event) {
            // Nothing to wipe is nothing to ask about — Android answers the
            // press with "There are no call records to delete." and stops.
            CallLogEvent.DeleteAll -> if (currentState.entries.isNotEmpty()) {
                setState { copy(confirmingDelete = true) }
            }
            CallLogEvent.ConfirmDeleteAll -> launch { deleteAll() }
            else -> setState { copy(confirmingDelete = false) }
        }
    }

    /** The Ongoing rows' buttons: Return at once, Join and Switch after the confirm. */
    private fun onOngoingEvent(event: CallLogEvent) {
        when (event) {
            is CallLogEvent.PressOngoing -> when (event.call.verb) {
                OngoingVerb.Return -> ongoing?.onReturn?.invoke()
                OngoingVerb.Join, OngoingVerb.Switch -> setState { copy(joinConfirm = event.call) }
            }
            CallLogEvent.ConfirmJoinOngoing -> {
                currentState.joinConfirm?.let { ongoing?.onJoin?.invoke(it) }
                setState { copy(joinConfirm = null) }
            }
            else -> setState { copy(joinConfirm = null) }
        }
    }

    private fun switchTo(missedOnly: Boolean) {
        if (state.value.missedOnly == missedOnly) return
        // The two views are paginated separately server-side, so the cursor
        // from one is meaningless in the other — start the new one clean.
        // The search survives: it is a question about names, not about which
        // view is answering.
        setState { CallLogUiState(missedOnly = missedOnly, isLoading = true, query = query) }
        launch { load(reset = true) }
    }

    private suspend fun load(reset: Boolean) {
        val current = state.value
        if (current.isLoading && !reset) return
        if (!reset && !current.canLoadMore) return
        setState { copy(isLoading = true) }

        // Reading backwards from the oldest row we hold, or from now on a
        // fresh load.
        val cursor = if (reset) {
            nowMillis()
        } else {
            current.entries.minOfOrNull { it.startedAtMillis }?.takeIf { it > 0 } ?: nowMillis()
        }

        api.callLogs(
            cursorMillis = cursor,
            selfUserId = selfUserId().orEmpty(),
            missedOnly = current.missedOnly,
            newestPage = reset,
            projectId = projectId,
            callerUserId = callerUserId,
        ).onSuccess { page ->
            setState {
                val merged = if (reset) page else (entries + page).distinctBy(CallLogEntry::callUuid)
                copy(
                    entries = merged.sortedByDescending(CallLogEntry::startedAtMillis),
                    isLoading = false,
                    // A short page is the end of the history; asking again
                    // would re-fetch the same rows forever.
                    canLoadMore = page.size >= PAGE_SIZE,
                    // A fresh page is the server's word again, not the wipe's.
                    deletedAll = if (reset) false else deletedAll,
                )
            }
        }
        // A failed page leaves the list alone — the rows already on screen are
        // still true, and an empty history is a worse lie than a stale one.
        setState { copy(isLoading = false) }
    }

    /**
     * Android's `RecentCallFragment.kt:203-209`: the Missed tab wipes missed
     * calls; the Recent tab wipes missed *and* recent — in that order, one
     * `DELETE` each. Only a clean sweep clears the list here; a failure on
     * either leg re-reads the server, because half a wipe may have landed and
     * the rows on screen would otherwise be a guess.
     */
    private suspend fun deleteAll() {
        val missedOnly = currentState.missedOnly
        setState { copy(confirmingDelete = false, isDeleting = true, error = null) }
        val outcomes = buildList {
            add(api.deleteMissedCallLogs(projectId, callerUserId))
            if (!missedOnly) add(api.deleteRecentCallLogs(projectId, callerUserId))
        }
        val failure = outcomes.firstNotNullOfOrNull { it.errorOrNull() }
        if (failure == null) {
            setState {
                copy(entries = emptyList(), canLoadMore = false, isDeleting = false, deletedAll = true)
            }
        } else {
            setState { copy(isDeleting = false, error = failure.localised()) }
            load(reset = true)
        }
    }

    private companion object {
        /** The server's page size, as the other clients assume it. */
        const val PAGE_SIZE = 20
    }
}

/**
 * The server's active list as the tab's rows.
 *
 * Filtered to this production, as the web's Ongoing tab is; the verb per
 * row is the web's rule by call id — the call open here is Return, one we
 * are in on another device is Switch here, anything else Join.
 */
fun ongoingCalls(
    calls: List<LiveKitActiveCall>,
    liveCallId: String?,
    selfUserId: String,
    projectId: String?,
): List<OngoingCall> = calls
    .filter { projectId.isNullOrBlank() || it.projectId.isBlank() || it.projectId == projectId }
    .map { call ->
        OngoingCall(
            callId = call.callId,
            title = call.title,
            type = call.callType,
            mode = call.callMode,
            chatRoomId = call.chatRoomId,
            inCall = call.inCallUsers,
            verb = when {
                call.callId == liveCallId -> OngoingVerb.Return
                selfUserId.isNotBlank() && selfUserId in call.inCallUserIds -> OngoingVerb.Switch
                else -> OngoingVerb.Join
            },
        )
    }

/**
 * The search box's rule — Android's `RecentMissedVM.searchList` (`:265-285`):
 * a row stays when the name of who it was with contains the text, case-blind.
 * The counterpart is what [displayTitle] resolves — the room for a group row,
 * the other person for a 1:1 — because ids are what the row carries and a
 * name is what anyone types.
 */
fun List<CallLogEntry>.matchingCounterpart(
    query: String,
    nameFor: (String) -> String?,
): List<CallLogEntry> {
    val needle = query.trim()
    if (needle.isEmpty()) return this
    return filter { it.displayTitle(nameFor).contains(needle, ignoreCase = true) }
}

/**
 * Who the row was with.
 *
 * A group row is titled by its room. A 1:1 row resolves the peer against the
 * crew, because the history endpoint returns ids and a list of ids is not a
 * list anyone can read.
 */
fun CallLogEntry.displayTitle(nameFor: (String) -> String?): String = when {
    mode == CallMode.Group -> title.ifBlank { "Group call" }
    else -> nameFor(peerUserId)?.takeIf(String::isNotBlank)
        ?: title.ifBlank { "Unknown caller" }
}

/**
 * The line under the name: when it happened, and how long it lasted.
 *
 * When comes first because it is what a call list is scanned for — Android's
 * row shows the time alone. The duration follows for a call that connected; a
 * missed one has none worth printing, and "Missed · 0:00" reads as a fault
 * rather than as nobody having picked up.
 */
fun CallLogEntry.subtitle(
    nowMillis: Long,
    zone: TimeZone = TimeZone.currentSystemDefault(),
): String {
    val at = callTimeLabel(startedAtMillis, nowMillis, zone)
    val what = when {
        missed -> "Missed"
        else -> formatDuration(durationMillis)
    }
    return listOf(what, at).filter(String::isNotBlank).joinToString(" · ")
}

/**
 * The row's time column, matching the chat listing's convention: a clock
 * today, a date this year, month-and-year beyond.
 */
fun callTimeLabel(
    atMillis: Long,
    nowMillis: Long,
    zone: TimeZone = TimeZone.currentSystemDefault(),
): String {
    if (atMillis <= 0) return ""
    val at = Instant.fromEpochMilliseconds(atMillis).toLocalDateTime(zone)
    val today = Instant.fromEpochMilliseconds(nowMillis).toLocalDateTime(zone).date
    val month = MONTHS[at.date.monthNumber - 1]
    return when {
        at.date == today -> "${at.hour.pad()}:${at.minute.pad()}"
        at.date.year == today.year -> "${at.date.dayOfMonth} $month, ${at.hour.pad()}:${at.minute.pad()}"
        else -> "$month ${at.date.year}"
    }
}

/**
 * How long the call ran.
 *
 * `call_duration` is **milliseconds**. The logs contract does not say so —
 * Android's own note calls the unit unpinned and guesses per row, treating
 * anything past 24 hours as milliseconds — but every row this production has
 * is unambiguous: read as milliseconds they are calls of 14 to 90 seconds,
 * and read as seconds they are ten consecutive calls lasting between four and
 * twenty-five hours. Reading them as seconds is what rendered a ninety-second
 * call as "24:48:09".
 *
 * A row that really did carry seconds would read short here. That is the
 * lesser error: a wrong-by-1000 duration on a rare row is quieter than every
 * ordinary call claiming to have run all afternoon.
 */
fun formatDuration(rawMillis: Long): String {
    if (rawMillis <= 0) return "No answer"
    val seconds = rawMillis / MILLIS_PER_SECOND
    val minutes = seconds / SECONDS_PER_MINUTE
    val hours = minutes / MINUTES_PER_HOUR
    return when {
        hours > 0 -> "${hours}h ${minutes % MINUTES_PER_HOUR}m"
        minutes > 0 -> "${minutes}m ${seconds % SECONDS_PER_MINUTE}s"
        // Under a second still connected; "0s" is truer than "No answer".
        else -> "${seconds}s"
    }
}

private fun Long.pad(): String = toString().padStart(2, '0')
private fun Int.pad(): String = toString().padStart(2, '0')

private const val SECONDS_PER_MINUTE = 60
private const val MINUTES_PER_HOUR = 60
private const val MILLIS_PER_SECOND = 1_000L

private val MONTHS = listOf(
    "Jan", "Feb", "Mar", "Apr", "May", "Jun",
    "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
)
