package com.zillit.desktop.feature.castboard.ui

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.mvvm.ZillitViewModel
import com.zillit.desktop.core.permissions.RightsKind
import com.zillit.desktop.core.permissions.RightsRequestBus
import com.zillit.desktop.core.permissions.ProjectPermissions
import com.zillit.desktop.core.socket.SocketEventBus
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.castboard.data.castingDiscussionEvents
import com.zillit.desktop.feature.castboard.data.castingSyncEvents
import com.zillit.desktop.feature.castboard.domain.BoardTool
import com.zillit.desktop.feature.castboard.domain.CastingBadges
import com.zillit.desktop.feature.castboard.domain.CastingEntry
import com.zillit.desktop.feature.castboard.domain.CastingRepository
import com.zillit.desktop.feature.castboard.domain.CastingStatus
import com.zillit.desktop.feature.castboard.domain.CastingUnit
import com.zillit.desktop.feature.castboard.domain.CastingUnread
import com.zillit.desktop.feature.castboard.domain.CastingViewer

/**
 * The Casting and Wardrobe boards: one screen per board, both lists in it.
 *
 * Which lists appear is a question of rights — a caster granted only the
 * background unit sees that one alone, rather than an empty tab they cannot
 * use.
 */
class CastingViewModel(
    private val repository: CastingRepository,
    /** The clock a thread is read back from. */
    private val nowMillis: () -> Long = { 0L },
    /** Which board this is — casting or wardrobe. */
    val board: BoardTool = BoardTool.Casting,
    private val permissions: () -> ProjectPermissions = { ProjectPermissions(emptyList()) },
    /** Carries a refused press to the app frame, which offers to ask an admin. */
    private val rights: RightsRequestBus? = null,
    /**
     * The socket, so a record added by another department appears without a
     * reopen. Null in tests and on a build with no socket.
     */
    private val events: SocketEventBus? = null,
    /** The ledger's rows for this board, and its reads. */
    private val badges: CastingBadges = CastingBadges.None,
) : ZillitViewModel<CastingUiState, CastingEvent, Nothing>(CastingUiState()) {

    init {
        launch {
            badges.leaves.collect { leaves ->
                setState { copy(unread = CastingUnread(leaves)) }
                // A comment landing on the open entry is read as it lands.
                val open = currentState.openEntry ?: return@collect
                readIfUnread(open)
            }
        }
        val bus = events
        if (bus != null) {
            launch {
                // Reload rather than patch: the board is filtered by unit and
                // status, and the payload carries one record.
                bus.onAny(castingSyncEvents(board)).collect {
                    if (currentState.unit != null) fetch()
                }
            }
            launch {
                // A line added to the thread on screen. Refreshed quietly —
                // `openDiscussion` blanks the list and raises a spinner, which
                // is right when opening and wrong for every message after.
                bus.onAny(castingDiscussionEvents(board)).collect { refreshDiscussion() }
            }
        }
    }

    override fun onEvent(event: CastingEvent) {
        when (event) {
            CastingEvent.Load -> load()
            is CastingEvent.UnitChanged -> {
                setState { copy(unit = event.unit, entries = emptyList()) }
                fetch()
            }

            is CastingEvent.StatusChanged -> {
                setState { copy(status = event.status, entries = emptyList()) }
                fetch()
            }

            is CastingEvent.QueryChanged -> setState { copy(query = event.query) }
            CastingEvent.Refresh -> fetch()
            CastingEvent.DismissError -> setState { copy(error = null, notice = null) }
            is CastingEvent.MoveTo -> move(event.entryId, event.status)
            is CastingEvent.OpenDiscussion -> openDiscussion(event.entry)
            CastingEvent.CloseDiscussion -> setState {
                copy(openEntry = null, discussion = emptyList(), discussionDraft = "")
            }
            is CastingEvent.DiscussionDraftChanged -> setState { copy(discussionDraft = event.text) }
            CastingEvent.SendDiscussion -> sendDiscussion()
        }
    }

    private fun load() {
        val viewer = CastingViewer.from(permissions(), board)
        setState { copy(viewer = viewer, unit = unit ?: viewer.units.firstOrNull()) }
        if (currentState.unit != null) fetch()
    }

    /**
     * Moves an entry to another stage and drops it from the list on screen.
     *
     * The row belongs to the stage being looked at, so once it has moved it
     * does not belong here — removing it locally rather than re-reading keeps
     * the board still under the reader's hand, and the next refresh confirms.
     */

    /**
     * Refuses, and offers the one thing that changes the answer.
     *
     * The control that got here is on screen for everyone now — hiding it is
     * what sent people to support instead of to an admin. Null when the host
     * wired no bus (tests, previews), and then this is just the refusal.
     */
    private fun askForRights(kind: RightsKind) {
        setState {
            copy(
                error = if (rights == null) {
                    str(S.desktop_no_rights_on_module, kind.verb, str(S.casting))
                } else {
                    str(S.desktop_no_rights_on_module_asking_admin, kind.verb, str(S.casting))
                },
            )
        }
        rights?.ask(MODULE_LABEL, kind)
    }

    private fun move(entryId: String, status: CastingStatus) {
        val unit = currentState.unit ?: return
        if (status == currentState.status) return
        if (!unit.canPost) {
            askForRights(RightsKind.Post)
            return
        }
        setState { copy(busy = true, error = null) }
        launch {
            when (val answer = repository.moveTo(unit.unitId, entryId, status)) {
                is ZillitResult.Success -> setState {
                    copy(
                        busy = false,
                        notice = str(S.desktop_email_moved_to, status.label),
                        entries = entries.filterNot { it.id == entryId },
                    )
                }

                is ZillitResult.Failure ->
                    setState { copy(busy = false, error = answer.error.localised()) }
            }
        }
    }

    /**
     * Opens an entry's thread.
     *
     * Newest-first on the wire, oldest-first on screen: a discussion reads
     * downwards. A failure costs the thread, not the entry.
     */
    private fun openDiscussion(entry: CastingEntry) {
        setState {
            copy(openEntry = entry, discussion = emptyList(), discussionDraft = "", discussionLoading = true)
        }
        readIfUnread(entry)
        launch {
            val rows = repository.messages(entry.id, nowMillis())
            if (currentState.openEntry?.id != entry.id) return@launch
            setState {
                copy(
                    discussionLoading = false,
                    discussion = (rows as? ZillitResult.Success)?.data?.reversed().orEmpty(),
                )
            }
        }
    }

    /** The entry on screen is the read — its folder rows and its own thread. */
    private fun readIfUnread(entry: CastingEntry) {
        val s = currentState
        val unit = s.unit ?: return
        val tool = CastingBadges.toolOf(unit.kind)
        if (s.unread.entry(tool, s.status, entry) > 0) badges.readEntry(tool, s.status, entry)
    }

    /**
     * Re-reads the open thread without disturbing it.
     *
     * No spinner and no blanking: the thread stays on screen and the rows are
     * replaced when they arrive. A failure leaves what is there — a dropped
     * refresh must not empty a discussion somebody is reading.
     */
    private fun refreshDiscussion() {
        val entry = currentState.openEntry ?: return
        launch {
            val rows = repository.messages(entry.id, nowMillis())
            if (currentState.openEntry?.id != entry.id) return@launch
            (rows as? ZillitResult.Success)?.data?.let { loaded ->
                setState { copy(discussion = loaded.reversed()) }
            }
        }
    }

    /** Posts the draft, then re-reads so what shows is what saved. */
    private fun sendDiscussion() {
        val entry = currentState.openEntry ?: return
        val body = currentState.discussionDraft.trim()
        if (body.isEmpty() || currentState.discussionSending) return
        setState { copy(discussionSending = true) }
        launch {
            when (val answer = repository.sendMessage(entry.id, body)) {
                is ZillitResult.Success -> {
                    setState { copy(discussionSending = false, discussionDraft = "") }
                    openDiscussion(entry)
                }

                is ZillitResult.Failure ->
                    setState { copy(discussionSending = false, error = answer.error.localised()) }
            }
        }
    }

    private fun fetch() {
        val unit: CastingUnit = currentState.unit ?: return
        setState { copy(loading = true, error = null) }
        launch {
            when (val answer = repository.entries(unit.unitId, currentState.status)) {
                is ZillitResult.Success -> {
                    setState { copy(loading = false, entries = answer.data) }
                    sweepOrphans(unit, answer.data)
                }

                is ZillitResult.Failure ->
                    setState { copy(loading = false, error = answer.error.localised()) }
            }
        }
    }

    /** Rows of the loaded stage that no listed entry answers for are read — nothing else ever could. */
    private fun sweepOrphans(unit: CastingUnit, entries: List<CastingEntry>) {
        val s = currentState
        if (s.unit?.unitId != unit.unitId) return
        s.unread.orphans(CastingBadges.toolOf(unit.kind), s.status, entries).forEach(badges::readOrphan)
    }

    companion object {
        /** The statuses the segmented control offers, in the web's order. */
        val STATUSES: List<CastingStatus> = CastingStatus.entries
    }
}

private const val MODULE_LABEL = "Casting"
