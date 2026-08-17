package com.zillit.desktop.feature.home.ui

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.zillit.desktop.feature.home.domain.mentionMatches
import com.zillit.desktop.feature.home.domain.mentionQueryOf

/**
 * Keyboard selection for the mention picker.
 *
 * The picker's rows are deliberately non-focusable — a click must not
 * interrupt typing — which means every key press lands in the composer field.
 * The field routes Up/Down/Enter/Escape here while suggestions are showing,
 * and this holder answers what they mean: which row is lit, which name Enter
 * completes, and whether Escape has hidden the list for the current token.
 */
@Stable
class MentionPickerState {

    var matches: List<String> by mutableStateOf(emptyList())
        private set

    var selected: Int by mutableStateOf(0)
        private set

    /**
     * The token being matched — the rows light the letters it hit, so this is
     * snapshot state: growing `ab` to `abc` can keep the same match list while
     * moving which letters lit it.
     */
    var query: String? by mutableStateOf(null)
        private set

    private var dismissedQuery: String? = null

    val isOpen: Boolean get() = matches.isNotEmpty()

    /**
     * Re-derives the list from the draft. Runs on every recomposition, so the
     * list follows the text; the selection survives while the token is stable
     * and snaps back to the top when it changes — a narrower query reorders
     * the rows, and a remembered index would light a different person.
     */
    fun sync(text: String, names: List<String>, recentFirst: List<String> = emptyList()) {
        val nextQuery = mentionQueryOf(text)
        if (nextQuery != query) {
            query = nextQuery
            selected = 0
            // A new token gets a fresh chance after an Escape.
            if (dismissedQuery != nextQuery) dismissedQuery = null
        }
        matches = if (nextQuery == null || nextQuery == dismissedQuery) {
            emptyList()
        } else {
            mentionMatches(nextQuery, names, recentFirst)
        }
        if (selected >= matches.size) selected = 0
    }

    fun moveDown() {
        if (isOpen) selected = (selected + 1) % matches.size
    }

    fun moveUp() {
        if (isOpen) selected = (selected - 1 + matches.size) % matches.size
    }

    /** Escape: hide the list until the token changes, giving Enter back to send. */
    fun dismiss() {
        dismissedQuery = query
        matches = emptyList()
    }

    fun selectedName(): String? = matches.getOrNull(selected)
}
