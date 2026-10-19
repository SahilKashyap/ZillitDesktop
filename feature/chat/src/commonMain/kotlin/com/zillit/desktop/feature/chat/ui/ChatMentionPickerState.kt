package com.zillit.desktop.feature.chat.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.common.mentionMatchedIndices
import com.zillit.desktop.core.common.mentionMatches
import com.zillit.desktop.core.common.mentionQueryOf
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ZillitAvatar
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.feature.chat.domain.CrewContact

/**
 * The crew the composer is offering to tag, and which row the keys are on.
 *
 * The board's picker ([com.zillit.desktop.feature.home.ui] `MentionPickerState`)
 * ranks plain names, because home chat has no tag format of its own. A CNC
 * thread tags by id, so this one carries whole crew members: the row that
 * Enter completes has to name a person, not a string that might belong to two
 * of them.
 *
 * The rows are deliberately not focusable, so every key press lands in the
 * composer field; the field routes Up/Down/Enter/Escape here while the list is
 * showing and answers them itself once it is not.
 */
@Stable
internal class ChatMentionPickerState {

    var matches: List<CrewContact> by mutableStateOf(emptyList())
        private set

    var selected: Int by mutableStateOf(0)
        private set

    /** The token being matched — the rows light the letters it hit. */
    var query: String? by mutableStateOf(null)
        private set

    private var dismissedQuery: String? = null

    val isOpen: Boolean get() = matches.isNotEmpty()

    /**
     * Re-derives the list from the draft, on every recomposition, so the
     * suggestions follow the text.
     *
     * The lit row resets whenever the token changes: a narrower query reorders
     * the rows, and an index remembered across that reorder would complete
     * somebody the writer never looked at.
     */
    fun sync(draft: String, crew: List<CrewContact>) {
        val nextQuery = mentionQueryOf(draft)
        if (nextQuery != query) {
            query = nextQuery
            selected = 0
            // A fresh token earns a fresh chance after an Escape.
            if (dismissedQuery != nextQuery) dismissedQuery = null
        }
        matches = if (nextQuery == null || nextQuery == dismissedQuery) {
            emptyList()
        } else {
            rank(nextQuery, crew)
        }
        if (selected >= matches.size) selected = 0
    }

    /**
     * The crew behind the names the shared matcher ranked, in its order.
     *
     * Two people with one name collapse to a single row — the matcher works on
     * distinct names — and the row completes the first of them. Better that
     * than two rows a writer cannot tell apart; the thread's own bubbles name
     * whoever was actually tagged.
     */
    private fun rank(query: String, crew: List<CrewContact>): List<CrewContact> =
        mentionMatches(query, crew.map { it.fullName })
            .mapNotNull { name -> crew.firstOrNull { it.fullName == name } }

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

    fun selectedContact(): CrewContact? = matches.getOrNull(selected)
}

/**
 * The suggestions, above the writing bar — the board's picker, so the two
 * composers look and answer alike.
 */
@Composable
internal fun ChatMentionSuggestions(
    picker: ChatMentionPickerState,
    onPick: (CrewContact) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = ZillitTheme.spacing.md)
            .clip(ZillitTheme.shapes.medium)
            .background(ZillitTheme.colors.canvas)
            .border(MENTION_HAIRLINE, ZillitTheme.colors.border, ZillitTheme.shapes.medium)
            .padding(ZillitTheme.spacing.xxs),
    ) {
        picker.matches.forEachIndexed { index, contact ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(ZillitTheme.shapes.small)
                    .then(
                        if (index == picker.selected) {
                            Modifier.background(ZillitTheme.colors.surfaceHover)
                        } else {
                            Modifier
                        },
                    )
                    // Not focusable: a click that took the keyboard off the
                    // field would turn "pick, then carry on typing" into two
                    // steps — the board's picker learnt this the hard way.
                    .focusProperties { canFocus = false }
                    .clickable { onPick(contact) }
                    .padding(horizontal = ZillitTheme.spacing.sm, vertical = ZillitTheme.spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            ) {
                ZillitAvatar(name = contact.fullName, size = MENTION_AVATAR)
                ZillitText(
                    text = litName(contact.fullName, picker.query.orEmpty()),
                    style = ZillitTheme.typography.bodySmall,
                    color = ZillitTheme.colors.textPrimary,
                )
            }
        }
    }
}

/**
 * A suggestion with the letters that answered the query lit, so the picker
 * explains its own fuzzy ranking: `vp` lights two initials, `vdya` its
 * scattered path. The board's treatment, and the accent a finished tag wears
 * in a bubble.
 */
@Composable
private fun litName(name: String, query: String): AnnotatedString {
    val indices = mentionMatchedIndices(query, name)
    if (indices.isEmpty()) return AnnotatedString(name)

    val mark = SpanStyle(color = ZillitTheme.colors.accent, fontWeight = FontWeight.SemiBold)
    return buildAnnotatedString {
        append(name)
        indices.forEach { addStyle(mark, it, it + 1) }
    }
}

private val MENTION_AVATAR = 24.dp
private val MENTION_HAIRLINE = 1.dp
