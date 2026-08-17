package com.zillit.desktop.feature.calls.ui

import com.zillit.desktop.core.common.onSuccess
import com.zillit.desktop.core.mvvm.ZillitViewModel
import com.zillit.desktop.feature.calls.data.CallApi
import com.zillit.desktop.feature.calls.domain.CallLogEntry
import com.zillit.desktop.feature.calls.domain.CallMode
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/** What the Calls tab is showing. */
data class CallLogUiState(
    val entries: List<CallLogEntry> = emptyList(),
    val missedOnly: Boolean = false,
    val isLoading: Boolean = false,
    /** False once a page comes back short — the server has no more to give. */
    val canLoadMore: Boolean = true,
)

sealed interface CallLogEvent {
    data object ShowAll : CallLogEvent
    data object ShowMissed : CallLogEvent
    data object Refresh : CallLogEvent
    data object LoadMore : CallLogEvent
    data class Redial(val entry: CallLogEntry) : CallLogEvent
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
) : ZillitViewModel<CallLogUiState, CallLogEvent, Nothing>(CallLogUiState()) {

    init {
        launch { load(reset = true) }
    }

    override fun onEvent(event: CallLogEvent) {
        when (event) {
            CallLogEvent.ShowAll -> switchTo(missedOnly = false)
            CallLogEvent.ShowMissed -> switchTo(missedOnly = true)
            CallLogEvent.Refresh -> launch { load(reset = true) }
            CallLogEvent.LoadMore -> launch { load(reset = false) }
            is CallLogEvent.Redial -> onRedial(event.entry)
        }
    }

    private fun switchTo(missedOnly: Boolean) {
        if (state.value.missedOnly == missedOnly) return
        // The two views are paginated separately server-side, so the cursor
        // from one is meaningless in the other — start the new one clean.
        setState { CallLogUiState(missedOnly = missedOnly, isLoading = true) }
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
        ).onSuccess { page ->
            setState {
                val merged = if (reset) page else (entries + page).distinctBy(CallLogEntry::callUuid)
                copy(
                    entries = merged.sortedByDescending(CallLogEntry::startedAtMillis),
                    isLoading = false,
                    // A short page is the end of the history; asking again
                    // would re-fetch the same rows forever.
                    canLoadMore = page.size >= PAGE_SIZE,
                )
            }
        }
        // A failed page leaves the list alone — the rows already on screen are
        // still true, and an empty history is a worse lie than a stale one.
        setState { copy(isLoading = false) }
    }

    private companion object {
        /** The server's page size, as the other clients assume it. */
        const val PAGE_SIZE = 20
    }
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
