package com.zillit.desktop.feature.castboard.domain

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * One unread leaf of a board's notification ledger.
 *
 * The service files a casting or wardrobe row under the list's own tool
 * (`casting_main_tool_label`, `wardrobe_background_tool_label`…), the stage
 * as its unit (`casting_select_label` / `_shortlist_label` / `_final_label`,
 * `CommonCasting.jsx:416-483`), the character as `level_1`, the talent
 * (casting) or scene (wardrobe) as `level_2` and the episode as `level_3`.
 * A comment on one entry carries the entry's id as its chat unit — what the
 * web groups "image chat" badges by (`getLCWInnerBadgesFromDB group='all'`).
 */
data class CastingBadgeLeaf(
    val tool: String,
    val status: CastingStatus,
    val character: String,
    val episode: String,
    /** The entry a comment row belongs to; blank for a row about the folder itself. */
    val entryId: String,
    val unread: Int,
)

/**
 * The board's unread rows, and the reads its screen makes — an entry's
 * thread opened, which is where the desktop looks at one character (the
 * web reads the character's image list on open, `fetchReadBadgeForImage`,
 * and its comment thread on scroll, `LCWChatDiscussionV2.jsx:209`).
 */
interface CastingBadges {
    val leaves: Flow<List<CastingBadgeLeaf>> get() = emptyFlow()

    fun readEntry(tool: String, status: CastingStatus, entry: CastingEntry) {}

    /** A leaf nothing on screen can open — read as the stage's list loads (see [CastingUnread.orphans]). */
    fun readOrphan(leaf: CastingBadgeLeaf) {}

    companion object {
        val None: CastingBadges = object : CastingBadges {}

        /** The wire tool of one list — `casting_main_tool` → `casting_main_tool_label`. */
        fun toolOf(kind: BoardUnitKind): String = kind.identifier + "_label"

        /** The unit a stage files under: `casting_select_label`, `wardrobe_final_label`… */
        fun unitOf(board: BoardTool, status: CastingStatus): String = when (status) {
            CastingStatus.Selected -> "${board.segment}_select_label"
            CastingStatus.Shortlisted -> "${board.segment}_shortlist_label"
            CastingStatus.Published -> "${board.segment}_final_label"
        }

        fun statusOf(board: BoardTool, unit: String): CastingStatus? =
            CastingStatus.entries.firstOrNull { unitOf(board, it) == unit }
    }
}

/** The leaves cut the way the board asks: a list tab, a stage tab, one entry. */
data class CastingUnread(val leaves: List<CastingBadgeLeaf> = emptyList()) {

    fun tool(tool: String): Int = leaves.filter { it.tool == tool }.sumOf { it.unread }

    fun status(tool: String, status: CastingStatus): Int =
        leaves.filter { it.tool == tool && it.status == status }.sumOf { it.unread }

    /**
     * One entry: its own comments, plus every folder row about its character
     * (and its episode, where both name one).
     */
    fun entry(tool: String, status: CastingStatus, entry: CastingEntry): Int =
        leaves.filter { it.tool == tool && it.status == status && it.belongsTo(entry) }.sumOf { it.unread }

    /**
     * The leaves of a stage that no entry on it answers for — rows written
     * for a character since moved on or deleted (live data: a Selected tab
     * badged `1` over "Nobody at this stage yet"). No client's screen can
     * open what is not listed, so they would badge the stage for good; the
     * loaded list reads them.
     */
    fun orphans(tool: String, status: CastingStatus, entries: List<CastingEntry>): List<CastingBadgeLeaf> =
        leaves.filter { leaf ->
            leaf.tool == tool && leaf.status == status && entries.none { leaf.belongsTo(it) }
        }

    private fun CastingBadgeLeaf.belongsTo(entry: CastingEntry): Boolean = when {
        entryId.isNotBlank() -> entryId == entry.id
        character.trim() != entry.characterName.trim() -> false
        else -> episode.isBlank() || entry.episode.isBlank() || episode.trim() == entry.episode.trim()
    }

    companion object {
        val None = CastingUnread()
    }
}
