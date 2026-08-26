package com.zillit.desktop.feature.castboard.ui

import com.zillit.desktop.feature.castboard.domain.BoardMessage
import com.zillit.desktop.feature.castboard.domain.CastingEntry
import com.zillit.desktop.feature.castboard.domain.CastingStatus
import com.zillit.desktop.feature.castboard.domain.CastingUnit
import com.zillit.desktop.feature.castboard.domain.CastingViewer

data class CastingUiState(
    val viewer: CastingViewer = CastingViewer(),
    val unit: CastingUnit? = null,
    val status: CastingStatus = CastingStatus.Selected,
    val entries: List<CastingEntry> = emptyList(),
    val query: String = "",
    val loading: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
    /** The entry whose discussion is open, and the thread itself. */
    val openEntry: CastingEntry? = null,
    val discussion: List<BoardMessage> = emptyList(),
    val discussionLoading: Boolean = false,
    val discussionDraft: String = "",
    val discussionSending: Boolean = false,
) {

    val hasNoAccess: Boolean get() = viewer.hasNoAccess

    /**
     * What to head the page with.
     *
     * The unit labels carry the production's word and the list in brackets —
     * "Costume (Main Cast)" — so the word alone titles the board, and the
     * brackets stay on the tabs. Falls back to the descriptor's own title
     * where a production sent no names.
     */
    fun title(fallback: String): String = viewer.units.firstOrNull()
        ?.serverLabel
        ?.substringBefore(" (")
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?: fallback

    /**
     * The rows on screen: a caster looking for one name should not have to
     * read the whole board. Matches a character or any candidate on it.
     */
    val visible: List<CastingEntry>
        get() = query.trim().takeIf { it.isNotBlank() }?.let { needle ->
            entries.filter { entry ->
                entry.characterName.contains(needle, ignoreCase = true) ||
                    entry.talentNames.any { it.contains(needle, ignoreCase = true) }
            }
        } ?: entries
}

sealed interface CastingEvent {
    data object Load : CastingEvent
    data class UnitChanged(val unit: CastingUnit) : CastingEvent
    data class StatusChanged(val status: CastingStatus) : CastingEvent
    data class QueryChanged(val query: String) : CastingEvent
    data object Refresh : CastingEvent
    data object DismissError : CastingEvent

    /** Moves one entry to another stage — the board's whole point. */
    data class MoveTo(val entryId: String, val status: CastingStatus) : CastingEvent

    /** The discussion on one entry — the thread the phones and web have. */
    data class OpenDiscussion(val entry: CastingEntry) : CastingEvent
    data object CloseDiscussion : CastingEvent
    data class DiscussionDraftChanged(val text: String) : CastingEvent
    data object SendDiscussion : CastingEvent
}
