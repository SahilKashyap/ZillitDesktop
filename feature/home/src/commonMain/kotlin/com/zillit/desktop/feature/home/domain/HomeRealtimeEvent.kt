package com.zillit.desktop.feature.home.domain

/**
 * What the socket says happened to a board.
 *
 * A typed event rather than a raw payload, so the merge below can be tested
 * without a socket — and so `feature:home` is the only place that knows what
 * `home:message:added` means.
 */
sealed interface HomeRealtimeEvent {
    data class NoticeAdded(val unitId: String?, val notice: Notice) : HomeRealtimeEvent
    data class NoticeEdited(val unitId: String?, val notice: Notice) : HomeRealtimeEvent
    data class NoticeDeleted(val unitId: String?, val noticeId: String) : HomeRealtimeEvent

    /** Units or rights changed; the tab strip has to be refetched. */
    data object UnitsChanged : HomeRealtimeEvent
}

/**
 * Applies a realtime event to the board on screen.
 *
 * ## The case that matters
 *
 * The server broadcasts a post back to the client that made it. Without
 * matching on the client-generated `unique_id` the author sees their own notice
 * twice — once optimistically, once from the socket. The dedup below is the
 * whole reason this function exists rather than a bare `+ notice`.
 *
 * An event for a unit other than the one on screen is ignored: the board is
 * refetched on tab change anyway, and merging it would grow a list nobody is
 * looking at.
 */
fun List<Notice>.applyRealtime(
    event: HomeRealtimeEvent,
    selectedUnitId: String?,
): List<Notice> = when (event) {
    is HomeRealtimeEvent.NoticeAdded -> {
        if (!event.matchesUnit(selectedUnitId)) {
            this
        } else {
            val incoming = event.notice
            // Match on id **or** local id: our own post arrives back carrying the
            // `unique_id` we sent, while the optimistic card is keyed by it.
            val existing = indexOfFirst { it.matches(incoming) }
            if (existing >= 0) {
                // Replace: the server's copy is authoritative and confirms the
                // send, which also clears the "Sending" state.
                mapIndexed { index, notice -> if (index == existing) incoming else notice }
            } else {
                this + incoming
            }
        }.forDisplay()
    }

    is HomeRealtimeEvent.NoticeEdited -> {
        if (!event.matchesUnit(selectedUnitId)) {
            this
        } else {
            // Only replaces something already shown. An edit to a post outside
            // the loaded page is not an invitation to insert it.
            map { if (it.matches(event.notice)) event.notice else it }
        }
    }

    is HomeRealtimeEvent.NoticeDeleted ->
        if (!event.matchesUnit(selectedUnitId)) this else filterNot { it.id == event.noticeId }

    HomeRealtimeEvent.UnitsChanged -> this
}

/** Null unit id means "not scoped" — the server omits it on some events. */
private fun HomeRealtimeEvent.matchesUnit(selectedUnitId: String?): Boolean = when (this) {
    is HomeRealtimeEvent.NoticeAdded -> unitId == null || unitId == selectedUnitId
    is HomeRealtimeEvent.NoticeEdited -> unitId == null || unitId == selectedUnitId
    is HomeRealtimeEvent.NoticeDeleted -> unitId == null || unitId == selectedUnitId
    HomeRealtimeEvent.UnitsChanged -> true
}

/** Same post, whether it is known by server id or by the id we generated. */
private fun Notice.matches(other: Notice): Boolean =
    id == other.id || (localId != null && localId == other.localId)
