package com.zillit.desktop

import androidx.compose.ui.graphics.ImageBitmap
import com.zillit.desktop.core.badges.BadgeSections
import com.zillit.desktop.core.badges.LedgerRead
import com.zillit.desktop.core.badges.NotificationRecord
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.media.decodeImageBitmap
import com.zillit.desktop.core.permissions.ProjectPermissions
import com.zillit.desktop.feature.castboard.data.CastingRepositoryImpl
import com.zillit.desktop.feature.castboard.domain.BoardTool
import com.zillit.desktop.feature.castboard.domain.CastingBadgeLeaf
import com.zillit.desktop.feature.castboard.domain.CastingBadges
import com.zillit.desktop.feature.castboard.domain.CastingEntry
import com.zillit.desktop.feature.castboard.domain.CastingMedia
import com.zillit.desktop.feature.castboard.domain.CastingStatus
import com.zillit.desktop.feature.castboard.ui.CastingToolProvider
import com.zillit.desktop.feature.castboard.ui.CastingViewModel
import com.zillit.desktop.feature.home.domain.NoticeAttachment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The Casting and Wardrobe boards' host wiring.
 *
 * Both are the same engine on different services (see `BoardTool`), so this
 * builds one view model per board. Stills come through the same routed store
 * the notice boards read — a casting or costume photograph is an attachment
 * like any other, and its URL has to be signed before it can be fetched.
 */
internal fun AppGraph.Ready.buildCastBoard(
    board: BoardTool,
    permissions: () -> ProjectPermissions,
) = CastingViewModel(
    repository = CastingRepositoryImpl(
        apiClient,
        config,
        board,
        // A board discussion is encrypted like every other body in this app.
        encrypt = { plain -> (noticeDecryptor.encryptToHex(plain) as? ZillitResult.Success)?.data },
        decrypt = { cipher -> (noticeDecryptor.decryptFromHex(cipher) as? ZillitResult.Success)?.data },
        myUserId = { projectContext?.context?.value?.profile?.userId },
    ),
    nowMillis = System::currentTimeMillis,
    board = board,
    permissions = permissions,
    rights = rightsRequests,
    // A record added by another department should not wait for a reopen.
    events = socketEvents,
    badges = castBoardBadges(board),
)

/**
 * The board's ledger rows as leaves, and its read.
 *
 * Both of the board's lists file under their own tool (`casting_main_tool_label`
 * and `casting_background_tool_label`); every unread row of either becomes a
 * leaf keyed by stage unit, character, talent or scene (`level_2`), episode,
 * action and — for a comment — the entry it was made on. The entry read is
 * two reads in one: the folder's rows (iOS `readToolBagdes`,
 * `CastingFolderDetailViewController.swift:141-145`) and the image-chat read
 * of the entry's own thread (`LCWChatDiscussionV2.jsx:209`).
 */
private fun AppGraph.Ready.castBoardBadges(board: BoardTool): CastingBadges = object : CastingBadges {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val tools = board.units.map { CastingBadges.toolOf(it) }.toSet()

    override val leaves: Flow<List<CastingBadgeLeaf>> = badgeStore.counts.map { leavesNow() }.distinctUntilChanged()

    private fun leavesNow(): List<CastingBadgeLeaf> =
        badgeStore.unreadRows(BadgeSections.TOOLS)
            .filter { it.tool in tools }
            .groupingBy { row -> row.castLeafKey() }
            .eachCount()
            .mapNotNull { (key, count) -> key.leaf(count) }

    private fun NotificationRecord.castLeafKey() =
        CastLeafKey(tool, unit, level1, level2, level3, chatUnitId, action)

    private fun CastLeafKey.leaf(count: Int): CastingBadgeLeaf? {
        val status = CastingBadges.statusOf(board, unit) ?: return null
        return CastingBadgeLeaf(tool, status, character, episode, entryId, count, folder = folder, action = action)
    }

    /**
     * The server hears iOS's folder read — character, talent or scene,
     * episode — once per action it names ([CastingBadges.readActions]; the
     * wire takes one, and iOS's REST twin sends only the first). Here the
     * folder's own rows are read whatever their action and a sibling's
     * comments never ([CastingBadges.readsWithEntry]), including rows that
     * name no talent or scene, which the entry counts.
     */
    override fun readEntry(tool: String, status: CastingStatus, entry: CastingEntry) {
        val unit = CastingBadges.unitOf(board, status)
        scope.launch {
            CastingBadges.readActions(board).forEach { action ->
                emitLevelRead(
                    tool = tool,
                    unit = unit,
                    level1 = entry.characterName,
                    level2 = CastingBadges.folderOf(board, entry),
                    level3 = entry.episode.takeIf { it.isNotBlank() },
                    action = action,
                )
            }
            badgeStore.markRead(
                LedgerRead.Matching("${board.segment} entry ${entry.id}") { row ->
                    row.tool == tool && row.unit == unit &&
                        row.castLeafKey().leaf(1)?.let { CastingBadges.readsWithEntry(board, it, entry) } == true
                },
            )
            emitRecordChatRead(tool, entry.id)
        }
    }

    override fun readOrphan(leaf: CastingBadgeLeaf) {
        scope.launch {
            if (leaf.entryId.isNotBlank()) {
                emitRecordChatRead(leaf.tool, leaf.entryId)
            } else {
                // Exactly the leaf: its talent or scene and its action, so an
                // orphan read never reaches a listed entry's rows.
                emitLevelRead(
                    tool = leaf.tool,
                    unit = CastingBadges.unitOf(board, leaf.status),
                    level1 = leaf.character,
                    level2 = leaf.folder,
                    level3 = leaf.episode.takeIf { it.isNotBlank() },
                    action = leaf.action,
                )
            }
        }
    }
}

private data class CastLeafKey(
    val tool: String,
    val unit: String,
    val character: String,
    val folder: String,
    val episode: String,
    val entryId: String,
    val action: String,
)

internal fun AppGraph.Ready.castBoardProvider(viewModel: CastingViewModel, path: String) = CastingToolProvider(
    viewModel = viewModel,
    path = path,
    loadPhoto = { media -> castingPhoto(this, media) },
)

/** One candidate's still, as a bitmap — null when it cannot be fetched. */
internal suspend fun castingPhoto(ready: AppGraph.Ready, media: CastingMedia): ImageBitmap? {
    val fetched = ready.noticeMedia.fetch(
        NoticeAttachment(
            media = media.media,
            fileName = media.fileName,
            bucket = media.bucket,
            region = media.region,
        ),
        preview = true,
    )
    val bytes = (fetched as? ZillitResult.Success)?.data ?: return null
    return decodeImageBitmap(bytes)
}
