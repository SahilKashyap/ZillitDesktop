package com.zillit.desktop.feature.castboard.ui

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.mvvm.ZillitViewModel
import com.zillit.desktop.core.permissions.ProjectPermissions
import com.zillit.desktop.feature.castboard.domain.BoardTool
import com.zillit.desktop.feature.castboard.domain.CastingEntry
import com.zillit.desktop.feature.castboard.domain.CastingRepository
import com.zillit.desktop.feature.castboard.domain.CastingStatus
import com.zillit.desktop.feature.castboard.domain.CastingUnit
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
) : ZillitViewModel<CastingUiState, CastingEvent, Nothing>(CastingUiState()) {

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
    private fun move(entryId: String, status: CastingStatus) {
        val unit = currentState.unit ?: return
        if (!unit.canPost || status == currentState.status) return
        setState { copy(busy = true, error = null) }
        launch {
            when (val answer = repository.moveTo(unit.unitId, entryId, status)) {
                is ZillitResult.Success -> setState {
                    copy(
                        busy = false,
                        notice = "Moved to ${status.label}",
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
                is ZillitResult.Success ->
                    setState { copy(loading = false, entries = answer.data) }

                is ZillitResult.Failure ->
                    setState { copy(loading = false, error = answer.error.localised()) }
            }
        }
    }

    companion object {
        /** The statuses the segmented control offers, in the web's order. */
        val STATUSES: List<CastingStatus> = CastingStatus.entries
    }
}
