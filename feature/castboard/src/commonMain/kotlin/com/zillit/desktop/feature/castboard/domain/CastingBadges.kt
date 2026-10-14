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
    /** `level_2` — the talent (casting) or scene (wardrobe) the folder is keyed by. */
    val folder: String = "",
    /** The row's `action` — `casting_created`, `wardrobe_updated`, a comment's own… */
    val action: String = "",
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
            CastingStatus.Finalized -> "${board.segment}_final_label"
        }

        fun statusOf(board: BoardTool, unit: String): CastingStatus? =
            CastingStatus.entries.firstOrNull { unitOf(board, it) == unit }

        /**
         * The `level_2` an entry's folder is filed under — iOS keys a casting
         * folder by character + talent and a wardrobe one by character + scene
         * (`CastingFolderDetailViewController.swift:141-145`,
         * `WardrobeFolderDetailViewController.swift:144`), the first of each
         * where a record names several (`CastingSceneFolderVC+TableView.swift:215-218`;
         * the web's `talentName[0]`, `CommonCasting.jsx:892`).
         */
        fun folderOf(board: BoardTool, entry: CastingEntry): String =
            (if (board.showsScenes) entry.sceneNumbers.firstOrNull() else entry.talentNames.firstOrNull())
                .orEmpty().trim()

        /** The actions a folder read names on the wire — iOS's `unitAction_1` and `unitAction_2`. */
        fun readActions(board: BoardTool): Set<String> = setOf("${board.segment}_created", "${board.segment}_updated")

        /**
         * Whether a folder row (no entry id) is about [entry]: the same
         * character, the same talent or scene where the row names one, the
         * same episode where both name one. A row naming no `level_2` was
         * written from the character's own folder (the web's first modal
         * leaves it blank, `CommonCasting.jsx:329-331`) and belongs to each
         * of the character's entries, as an episode-less row does.
         */
        fun isFolderRowOf(board: BoardTool, leaf: CastingBadgeLeaf, entry: CastingEntry): Boolean =
            leaf.character.trim() == entry.characterName.trim() &&
                (leaf.folder.isBlank() || leaf.folder.trim() == folderOf(board, entry)) &&
                (leaf.episode.isBlank() || entry.episode.isBlank() || leaf.episode.trim() == entry.episode.trim())

        /**
         * Whether opening [entry] reads a row filed at its folder: a
         * created/updated row as iOS reads it ([readActions]), and any other
         * row that is no entry's comment. A comment on a sibling entry is
         * filed at the same levels; iOS's action filter is what spares it,
         * and its entry id says the same thing more precisely.
         */
        fun readsWithEntry(board: BoardTool, leaf: CastingBadgeLeaf, entry: CastingEntry): Boolean =
            (leaf.action in readActions(board) || leaf.entryId.isBlank()) && isFolderRowOf(board, leaf, entry)
    }
}

/** The leaves cut the way the board asks: a list tab, a stage tab, one entry. */
data class CastingUnread(
    val leaves: List<CastingBadgeLeaf> = emptyList(),
    /** Whose folders these are — casting keys them by talent, wardrobe by scene. */
    val board: BoardTool = BoardTool.Casting,
) {

    fun tool(tool: String): Int = leaves.filter { it.tool == tool }.sumOf { it.unread }

    fun status(tool: String, status: CastingStatus): Int =
        leaves.filter { it.tool == tool && it.status == status }.sumOf { it.unread }

    /**
     * One entry: its own comments, plus every folder row about its folder —
     * character, talent or scene, episode ([CastingBadges.isFolderRowOf]).
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

    private fun CastingBadgeLeaf.belongsTo(entry: CastingEntry): Boolean =
        if (entryId.isNotBlank()) entryId == entry.id else CastingBadges.isFolderRowOf(board, this, entry)

    companion object {
        val None = CastingUnread()
    }
}
