package com.zillit.desktop.feature.home.domain

/**
 * What the socket says happened to a board.
 *
 * A typed event rather than a raw payload, so the merge below can be tested
 * without a socket — and so `feature:home` is the only place that knows what
 * `home:message:added` means.
 */
sealed interface HomeRealtimeEvent {
    /**
     * [wire] is the server's own row for this post, carried alongside the
     * parsed [notice] so the board's offline copy can be brought up to date
     * without a second shape to serialise into — see [NoticeWire]. Null when
     * the producer has no row to hand, which only costs the post its place on
     * disk until the board's next read.
     */
    data class NoticeAdded(
        val unitId: String?,
        val notice: Notice,
        val wire: String? = null,
    ) : HomeRealtimeEvent

    data class NoticeEdited(
        val unitId: String?,
        val notice: Notice,
        val wire: String? = null,
    ) : HomeRealtimeEvent

    data class NoticeDeleted(val unitId: String?, val noticeId: String) : HomeRealtimeEvent

    /** Units or rights changed; the tab strip has to be refetched. */
    data object UnitsChanged : HomeRealtimeEvent

    /**
     * Something on a board changed in a way no payload can patch — a comment,
     * a multi-delete — so that board has to be read again. The tab strip has
     * not moved, which is the whole difference from [UnitsChanged]: refetching
     * the units sets `isLoadingUnits`, and that used to replace the board with
     * a full-screen spinner every time anybody commented anywhere.
     *
     * [unitId] is null when the payload does not name one; the board on screen
     * is then the one re-read, on the same "not scoped" reading as the notice
     * events below.
     */
    data class BoardChanged(val unitId: String?) : HomeRealtimeEvent

    /**
     * Somebody read [messageId], so its read-by list has grown.
     *
     * Only the open read-by panel cares. The board itself shows no receipt,
     * which is why this is not a notice change — both phones react the same
     * way, refreshing the page only when it is showing that message
     * (`ReadByUserPage.kt:452`, iOS `ProjectObserver.swift:5335`).
     */
    data class ReadByChanged(val messageId: String) : HomeRealtimeEvent
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
            val existing = indexOfFirst { it.sameAs(incoming) }
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
            map { if (it.sameAs(event.notice)) event.notice else it }
        }
    }

    is HomeRealtimeEvent.NoticeDeleted ->
        if (!event.matchesUnit(selectedUnitId)) this else filterNot { it.id == event.noticeId }

    HomeRealtimeEvent.UnitsChanged -> this

    // Both of these are handled by refetching, not by merging — there is
    // nothing in either payload to apply to a post.
    is HomeRealtimeEvent.BoardChanged -> this

    // A receipt is not a post. The panel reads it; the board does not.
    is HomeRealtimeEvent.ReadByChanged -> this
}

/**
 * Null unit id means "not scoped" — the server omits it on some events.
 *
 * Internal rather than private: the view model asks the same question of a
 * [HomeRealtimeEvent.BoardChanged] before it refetches, and two copies of this
 * rule would be one too many.
 */
internal fun HomeRealtimeEvent.matchesUnit(selectedUnitId: String?): Boolean = when (this) {
    is HomeRealtimeEvent.NoticeAdded -> unitId == null || unitId == selectedUnitId
    is HomeRealtimeEvent.NoticeEdited -> unitId == null || unitId == selectedUnitId
    is HomeRealtimeEvent.NoticeDeleted -> unitId == null || unitId == selectedUnitId
    is HomeRealtimeEvent.BoardChanged -> unitId == null || unitId == selectedUnitId
    HomeRealtimeEvent.UnitsChanged -> true
    is HomeRealtimeEvent.ReadByChanged -> true
}

/**
 * Same post, whether it is known by server id or by the id we generated.
 *
 * Internal rather than private: the view model asks it of a post still going
 * up when a fetched page lands, to tell its own card from the server's copy.
 */
internal fun Notice.sameAs(other: Notice): Boolean =
    id == other.id || (localId != null && localId == other.localId)
