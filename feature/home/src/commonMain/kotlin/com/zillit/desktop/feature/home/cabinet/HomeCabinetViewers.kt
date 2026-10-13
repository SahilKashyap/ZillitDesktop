package com.zillit.desktop.feature.home.cabinet

import com.zillit.desktop.core.cabinet.CabinetViewer
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.config.AppConfig
import com.zillit.desktop.core.config.ZillitService
import com.zillit.desktop.core.network.ApiClient
import com.zillit.desktop.core.permissions.ProjectPermissions
import com.zillit.desktop.feature.home.data.HomeFeedRepositoryImpl
import com.zillit.desktop.feature.home.data.NoticeDecryptor
import com.zillit.desktop.feature.home.data.ReportUnitsSource
import com.zillit.desktop.feature.home.domain.HomeFeedRepository
import com.zillit.desktop.feature.home.domain.HomeUnit
import com.zillit.desktop.feature.home.domain.ToolsRepository
import com.zillit.desktop.feature.home.domain.visibleTabs
import com.zillit.desktop.feature.home.ui.HomeBoardContext
import com.zillit.desktop.feature.home.ui.HomeFeedViewModel

/** The Home unit the calendar lives in; the cabinet has no records of it (`home_unit_calendar`). */
private const val CALENDAR_UNIT = "home_unit_calendar"

/** The tool that owns the production report chat's rights since the backend split. */
private const val PRODUCTION_REPORT_CHAT_TOOL = "production_report_chat_tool"

/**
 * One notice-board row of the cabinet: where its chat lives, and where its
 * units come from. Everything else is the same board.
 *
 * @property identifier the cabinet row — the web's `commonUnits[].identifier`.
 * @property board the REST segment (`modalName` after the web's `getUnitName`).
 * @property unitsRoute the row's own unit list, for the four that expand into one.
 * @property tool the tool whose `unit_id` the row opens onto, for the ones that do not.
 * @property dropsDeletedUnits Catering's `deleted === 0` filter.
 */
private data class BoardRow(
    val identifier: String,
    val board: String,
    val service: ZillitService = ZillitService.Units,
    val chatSegment: String = "chat",
    val unitsRoute: String? = null,
    val tool: String? = null,
    val dropsDeletedUnits: Boolean = false,
)

private val BOARD_ROWS = listOf(
    BoardRow("home", "home"),
    BoardRow("info", "info", tool = "info_tool"),
    BoardRow("confidential_info", "confidentialinfo", tool = "confidential_info_tool"),
    BoardRow("accounting", "account", unitsRoute = "account/unit"),
    BoardRow("catering", "catering", unitsRoute = "catering/unit", dropsDeletedUnits = true),
    BoardRow("script_notes", "script-notes", ZillitService.ScriptNotes, unitsRoute = "script-notes/unit"),
    BoardRow("reports", "reports", ZillitService.ScriptNotes, unitsRoute = "reports/unit/"),
    // Hangs straight off production-report/ on the report service — no `chat/`.
    BoardRow(
        "production_report",
        "production-report",
        ZillitService.ProductionReport,
        chatSegment = "",
        tool = "production_report_tool",
    ),
)

/**
 * The File Cabinet's viewers for the notice-board rows: `home`, `info`,
 * `confidential_info`, `accounting`, `catering`, `script_notes`, `reports`
 * and `production_report`.
 *
 * Each opens the board the tool already has, read-only, over the chat routes
 * with the cabinet flag on — see [NoticeCabinetViewer]. [homeFeed] is the live
 * Home repository, asked only for its unit list; [tools] names the unit of the
 * rows that open onto their tool's own.
 */
@Suppress("LongParameterList") // One seam per host fact the boards read; the same list `buildHomeFeed` takes.
fun homeCabinetViewers(
    apiClient: ApiClient,
    config: AppConfig,
    decrypt: NoticeDecryptor,
    homeFeed: HomeFeedRepository,
    tools: ToolsRepository,
    board: HomeBoardContext,
    nowMillis: () -> Long,
    isAdmin: () -> Boolean,
    currentUserId: () -> String?,
): List<CabinetViewer> = BOARD_ROWS.map { row ->
    val newFeed = { unit: HomeUnit ->
        HomeFeedViewModel(
            repository = HomeFeedRepositoryImpl(
                apiClient = apiClient,
                config = config,
                decrypt = decrypt,
                isAdmin = isAdmin,
                nowMillis = nowMillis,
                board = row.board,
                units = { ZillitResult.Success(listOf(unit)) },
                service = row.service,
                chatSegment = row.chatSegment,
                fileCabinet = true,
            ),
            nowMillis = nowMillis,
            isAdmin = isAdmin,
            currentUserId = currentUserId,
            readOnly = true,
        )
    }
    val listUnits = unitLister(row, apiClient, config, homeFeed)
    NoticeCabinetViewer(
        identifier = row.identifier,
        listUnits = listUnits,
        toolUnit = { toolUnit(row, tools) },
        newFeed = newFeed,
        board = board,
    )
}

/** How a row lists its units: Home from its own repository, four from their own routes, the rest not at all. */
private fun unitLister(
    row: BoardRow,
    apiClient: ApiClient,
    config: AppConfig,
    homeFeed: HomeFeedRepository,
): (suspend () -> List<HomeUnit>)? = when {
    // The web lists `homeUnitDetailsForForward` — the units this person can
    // view, which is the live Home's tabs — less the calendar.
    row.identifier == "home" -> suspend {
        homeFeed.loadUnits().unitsOrEmpty().visibleTabs().filter { it.identifier != CALENDAR_UNIT }
    }
    row.unitsRoute != null -> {
        val source = ReportUnitsSource(apiClient, config, row.service, row.unitsRoute, fileCabinet = true)
        suspend { source.cabinetUnits(row) }
    }
    else -> null
}

private fun ZillitResult<List<HomeUnit>>.unitsOrEmpty(): List<HomeUnit> =
    (this as? ZillitResult.Success)?.data.orEmpty()

private suspend fun ReportUnitsSource.cabinetUnits(row: BoardRow): List<HomeUnit> {
    val rows = (units() as? ZillitResult.Success)?.data.orEmpty()
    return rows
        .filter { !(row.dropsDeletedUnits && it.deleted) }
        .map { cabinetUnit(it.id, it.identifier ?: row.identifier, it.name) }
}

/**
 * The unit a tool row opens onto: the tool's `unit_id` from the production's
 * tool list (the web's `filmUnitList.find(identifier).unit_id`).
 *
 * The production report is the one with a rights check of its own. Its chat
 * has been a separate tool since Oct 2026, and the web hides the row without
 * `usePrChatRights().canView` because the chat route 403s — and a 403 there
 * sends the web user out to the project list. The desktop shows the row for
 * every enabled tool, so the check lands here, as a message instead of a 403.
 */
private suspend fun toolUnit(row: BoardRow, tools: ToolsRepository): CabinetUnit {
    // A tools call that did not answer is "no unit", not an endless spinner.
    val permissions = (tools.loadPermissions() as? ZillitResult.Success)?.data
        ?: return CabinetUnit.Denied(DenyReason.NoUnit)
    val tool = row.tool ?: return CabinetUnit.Denied(DenyReason.NoUnit)
    val access = permissions.access(tool)
    val unitId = access.unitId
    return when {
        unitId.isNullOrBlank() -> CabinetUnit.Denied(DenyReason.NoUnit)
        row.identifier == "production_report" && !permissions.canViewProductionReportChat() ->
            CabinetUnit.Denied(DenyReason.NoRights)
        else -> CabinetUnit.Ready(cabinetUnit(unitId, access.identifier, permissions.unitLabel(row, access.unitName)))
    }
}

/**
 * The web's `resolvePrChatRights(...).canView`: the chat tool's row exists and
 * grants view (an admin's grant is implicit), and the report tool has a unit.
 */
private fun ProjectPermissions.canViewProductionReportChat(): Boolean =
    tools.any { it.identifier == PRODUCTION_REPORT_CHAT_TOOL } && access(PRODUCTION_REPORT_CHAT_TOOL).canView

private fun ProjectPermissions.unitLabel(row: BoardRow, toolUnitName: String?): String =
    (if (row.identifier == "production_report") access(PRODUCTION_REPORT_CHAT_TOOL).unitName else null)
        ?: toolUnitName
        ?: row.identifier
