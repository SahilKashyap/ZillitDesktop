package com.zillit.desktop.feature.home.cabinet

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.zillit.desktop.core.cabinet.CabinetEntry
import com.zillit.desktop.core.cabinet.CabinetViewer
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.home.domain.HomeUnit
import com.zillit.desktop.feature.home.ui.Empty
import com.zillit.desktop.feature.home.ui.HomeBoardContext
import com.zillit.desktop.feature.home.ui.HomeFeedEvent
import com.zillit.desktop.feature.home.ui.HomeFeedScreen
import com.zillit.desktop.feature.home.ui.HomeFeedViewModel
import com.zillit.desktop.feature.home.ui.Loading

/**
 * What a row opens onto, once it is known.
 *
 * A row whose unit comes from the production's tool list cannot say at
 * construction whether it has one — the tools call may still be out, or the
 * viewer may lack rights — so the window asks, and shows what it was told.
 */
internal sealed interface CabinetUnit {
    data object Pending : CabinetUnit

    /** No unit to show: the tool issued none, or this person may not view it. */
    data class Denied(val reason: DenyReason) : CabinetUnit

    data class Ready(val unit: HomeUnit) : CabinetUnit
}

internal enum class DenyReason { NoUnit, NoRights }

/**
 * A notice-board row of the File Cabinet: Home, Info, Confidential Info,
 * Accounts, Catering, Script Notes, Camera & Sound Reports, Production Report.
 *
 * The web mounts the same read-only unit chat for every one of them
 * (`FileCabinateDataShow` → `UnitChatsList`, posting off, download on) and only
 * the route differs. The desktop does the same with the board it already has:
 * the Home board engine over a repository in cabinet mode, with the model
 * switched to read-only. One viewer class, eight rows — [identifier] and the
 * two lambdas are the whole difference.
 *
 * @param listUnits the units the row expands into, or null for a row that
 *   opens straight onto its tool's one unit ([toolUnit]).
 * @param toolUnit the tool's own unit, for a row that does not expand.
 * @param newFeed the read-only board over [unit]'s chat, one per window.
 */
class NoticeCabinetViewer internal constructor(
    override val identifier: String,
    private val listUnits: (suspend () -> List<HomeUnit>)?,
    private val toolUnit: suspend () -> CabinetUnit,
    private val newFeed: (HomeUnit) -> HomeFeedViewModel,
    private val board: HomeBoardContext,
) : CabinetViewer {

    override val expands: Boolean get() = listUnits != null

    /** The units last listed, so a chosen entry opens with its real identifier and name. */
    private var listed: Map<String, HomeUnit> = emptyMap()

    override suspend fun entries(): List<CabinetEntry> {
        val units = runCatching { listUnits?.invoke() }.getOrNull().orEmpty()
        // Re-issued as the cabinet shows a unit: whatever this person may do
        // on the live board, the archive is read and downloaded, nothing else.
        listed = units.associate { it.id to cabinetUnit(it.id, it.identifier, it.unitName) }
        return units.map { CabinetEntry(key = it.id, name = it.label) }
    }

    @Composable
    override fun Content(entry: CabinetEntry?, modifier: Modifier) {
        var unit by remember(identifier, entry?.key) { mutableStateOf<CabinetUnit>(CabinetUnit.Pending) }
        LaunchedEffect(identifier, entry?.key) {
            unit = if (entry == null) {
                toolUnit()
            } else {
                CabinetUnit.Ready(listed[entry.key] ?: cabinetUnit(entry.key, identifier, entry.name))
            }
        }
        when (val resolved = unit) {
            CabinetUnit.Pending -> Box(modifier) { Loading(str(S.ah_loading)) }
            is CabinetUnit.Denied -> Box(modifier) {
                Empty(
                    title = when (resolved.reason) {
                        DenyReason.NoUnit -> str(S.no_data_found)
                        DenyReason.NoRights -> str(S.desktop_cabinet_no_view_rights)
                    },
                    message = "",
                    icon = ZillitIcons.Chat,
                )
            }
            is CabinetUnit.Ready -> CabinetBoardView(resolved.unit, newFeed, board, modifier)
        }
    }
}

/** The board itself: one read-only model per unit, loaded once when the window opens it. */
@Composable
private fun CabinetBoardView(
    unit: HomeUnit,
    newFeed: (HomeUnit) -> HomeFeedViewModel,
    board: HomeBoardContext,
    modifier: Modifier,
) {
    val feed = remember(unit.id) { newFeed(unit) }
    val state by feed.state.collectAsState()
    LaunchedEffect(feed) { feed.onEvent(HomeFeedEvent.Load) }

    HomeFeedScreen(
        state = state,
        onEvent = feed::onEvent,
        modifier = modifier,
        media = board.media,
        onOpenAttachment = board.onOpenAttachment,
        player = board.player,
        onOpenLocation = board.onOpenLocation,
        onOpenLink = board.onOpenLink,
        resolveAuthor = board.resolveAuthor,
        loadAvatar = board.loadAvatar,
        crewNames = board.crewNames,
    )
}

/**
 * A unit as the cabinet shows it: viewable and downloadable, never postable —
 * the web hands `UnitChatsList` `view_access: true, posting_access: false,
 * download_access: true` whatever the user's own rights are.
 */
internal fun cabinetUnit(id: String, identifier: String, name: String) = HomeUnit(
    id = id,
    identifier = identifier,
    unitName = name,
    canView = true,
    canPost = false,
    canDownload = true,
    enabled = true,
)
