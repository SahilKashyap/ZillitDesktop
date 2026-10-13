package com.zillit.desktop.feature.pagedistribution.cabinet

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import com.zillit.desktop.core.cabinet.CabinetEntry
import com.zillit.desktop.core.cabinet.CabinetViewer
import com.zillit.desktop.core.designsystem.component.ZillitToast
import com.zillit.desktop.core.designsystem.component.ZillitToastTone
import com.zillit.desktop.feature.pagedistribution.domain.DistributionRepository
import com.zillit.desktop.feature.pagedistribution.domain.DistributionTool
import com.zillit.desktop.feature.pagedistribution.domain.DistributionTransfer
import com.zillit.desktop.feature.pagedistribution.domain.DistributionViewer
import com.zillit.desktop.feature.pagedistribution.domain.ListMode
import com.zillit.desktop.feature.pagedistribution.ui.DistributionEffect
import com.zillit.desktop.feature.pagedistribution.ui.DistributionScreen
import com.zillit.desktop.feature.pagedistribution.ui.DistributionViewModel
import com.zillit.desktop.feature.pagedistribution.ui.dod.ProvideDodFaces

/**
 * The File Cabinet's window onto one of the three PDF distribution tools.
 *
 * The web mounts the tool's own page in a modal with `isFileCabinet` on —
 * `RenderScript` with a fixed tab for Script & Pages, `ScheduleDistributionMain`
 * with a fixed tab for Schedule, `DoD` straight — and its api files add
 * `isFileCabinet=true` to every read. Here that is the same engine and the
 * same three faces, driven in [ListMode.Cabinet]: the repository adds the
 * flag, the faces drop their header, tab strip, search and every posting or
 * management action, and what is left is what the web keeps — folders and
 * lists, View and Download.
 *
 * Script and Schedule are rows that expand into their tabs (the web's
 * `scriptList` / `scheduleDistribution` menus); D.O.D opens directly. One
 * view model is built per opened tab and cancelled when its window closes,
 * so a half-loaded list never lands on a cabinet that has moved on.
 */
class DistributionCabinetViewer(
    override val identifier: String,
    private val tool: DistributionTool,
    private val repository: DistributionRepository,
    private val transfer: DistributionTransfer,
    /** Who is reading, and whether the production is television (episode facts). */
    private val reader: () -> DistributionViewer,
    private val nowMillis: () -> Long,
    /** A user id shown as "Name (Designation)"; null falls back to the id. */
    private val resolveUser: (String) -> String?,
    /** A crew member's photo for the D.O.D cards; null draws initials. */
    private val loadAvatar: suspend (String) -> ImageBitmap? = { null },
) : CabinetViewer {

    override val expands: Boolean = tool.tabs.size > 1

    override suspend fun entries(): List<CabinetEntry> =
        if (expands) tool.tabs.map { CabinetEntry(it.key, it.label) } else emptyList()

    @Composable
    override fun Content(entry: CabinetEntry?, modifier: Modifier) {
        val model = remember(entry?.key) { openModel(entry?.key) }
        DisposableEffect(model) { onDispose { model.close() } }
        val state by model.state.collectAsState()
        var notice by remember(model) { mutableStateOf<String?>(null) }
        LaunchedEffect(model) { model.start() }
        LaunchedEffect(model) {
            model.effects.collect { effect -> if (effect is DistributionEffect.Notice) notice = effect.text }
        }
        Box(modifier) {
            ProvideDodFaces(load = loadAvatar) {
                DistributionScreen(state = state, onEvent = model::onEvent, resolveUser = resolveUser)
            }
            ZillitToast(message = notice, onDismiss = { notice = null }, tone = ZillitToastTone.Success)
        }
    }

    private fun openModel(tabKey: String?) = DistributionViewModel(
        tool = tool,
        repository = repository,
        transfer = transfer,
        resolveViewer = reader,
        nowMillis = nowMillis,
        initialMode = ListMode.Cabinet,
        openTabKey = tabKey,
    )
}

/**
 * The three rows — `script_distribution`, `schedule_distribution`, `dod` —
 * the web's `commonUnits[].identifier`s for these tools.
 *
 * The repository should be built without a socket bus: the cabinet shows
 * archived records, and a live refetch would only reload what is on screen.
 */
fun distributionCabinetViewers(
    repository: DistributionRepository,
    transfer: DistributionTransfer,
    reader: () -> DistributionViewer,
    nowMillis: () -> Long,
    resolveUser: (String) -> String?,
    loadAvatar: suspend (String) -> ImageBitmap? = { null },
): List<CabinetViewer> = listOf(
    "script_distribution" to DistributionTool.ScriptDistribution,
    "schedule_distribution" to DistributionTool.ScheduleDistribution,
    "dod" to DistributionTool.ScheduleDod,
).map { (identifier, tool) ->
    DistributionCabinetViewer(identifier, tool, repository, transfer, reader, nowMillis, resolveUser, loadAvatar)
}

/**
 * The reader the cabinet needs: viewing and downloading, nothing else.
 *
 * File Cabinet Documents is an Admin Settings page, so whoever reaches it is
 * an admin; the web still checks the tool's download right on a Download
 * press, but a cabinet that could be opened and not downloaded from would
 * defeat its purpose, so the right is granted here. Posting and publishing
 * stay off — the faces would hide them anyway.
 */
fun cabinetReader(userId: String, isTelevision: Boolean): DistributionViewer = DistributionViewer(
    userId = userId,
    canView = true,
    canDownload = true,
    isTelevision = isTelevision,
    ready = true,
)
