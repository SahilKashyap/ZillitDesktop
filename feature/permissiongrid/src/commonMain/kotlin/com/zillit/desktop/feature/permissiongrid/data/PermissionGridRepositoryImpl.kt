package com.zillit.desktop.feature.permissiongrid.data

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.common.map
import com.zillit.desktop.core.config.AppConfig
import com.zillit.desktop.core.network.ApiClient
import com.zillit.desktop.core.network.ApiEnvelope
import com.zillit.desktop.core.network.HttpVerb
import com.zillit.desktop.core.network.RequestModule
import com.zillit.desktop.core.network.jsonBody
import com.zillit.desktop.core.strings.Strings
import com.zillit.desktop.feature.permissiongrid.domain.AccessKind
import com.zillit.desktop.feature.permissiongrid.domain.DefaultGridPage
import com.zillit.desktop.feature.permissiongrid.domain.DesignationFilter
import com.zillit.desktop.feature.permissiongrid.domain.GridAxis
import com.zillit.desktop.feature.permissiongrid.domain.GridPage
import com.zillit.desktop.feature.permissiongrid.domain.GridQuery
import com.zillit.desktop.feature.permissiongrid.domain.GridSection
import com.zillit.desktop.feature.permissiongrid.domain.PermissionGridRepository
import com.zillit.desktop.feature.permissiongrid.domain.RightsSync
import com.zillit.desktop.core.socket.SocketEventBus
import com.zillit.desktop.core.socket.SocketEventName
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull

/**
 * `permissions/{axis}/{section}/access` — the production's rights spreadsheet.
 *
 * The same pair of URLs the web uses, read under the axis's read name
 * (`crewlist` for the crew-list axis) and written under its write name
 * (`users`). Admin Settings opens this same grid, as the web's admin button
 * does (`/film-tools/permission-grid?s=admin`).
 *
 * @param currentUserId whose row to hide — see [toPage].
 */
class PermissionGridRepositoryImpl(
    private val apiClient: ApiClient,
    config: AppConfig,
    private val currentUserId: () -> String?,
    /** Null keeps the grid socket-less — tests, and hosts without a bus. */
    private val bus: SocketEventBus? = null,
    private val currentProjectId: () -> String? = { null },
    /** The language the server matches and sorts translated labels in. */
    private val languageCode: () -> String = { Strings.language.code },
) : PermissionGridRepository {

    private val base = config.apiV2()

    /**
     * See [PermissionGridRepository.syncs]. Another production's event is
     * dropped when both sides can name a project — the socket reconnects per
     * production, but a stale frame can straddle a switch.
     */
    override val syncs: Flow<RightsSync> =
        bus?.onAny(RIGHTS_SYNC_EVENTS, RightsSyncDto.serializer())
            ?.mapNotNull { (_, dto) -> dto.toDomain() }
            ?.mapNotNull { sync ->
                val here = currentProjectId()
                if (sync.projectId != null && here != null && sync.projectId != here) null else sync
            }
            ?: emptyFlow()

    override val departmentsReordered: Flow<Unit> =
        bus?.on(DEPARTMENT_REORDERED)
            ?.map { }
            ?: emptyFlow()

    private fun readUrl(axis: GridAxis, section: GridSection) =
        "${base}permissions/${axis.readWire}/${section.wire}/access"

    private fun writeUrl(axis: GridAxis, section: GridSection) =
        "${base}permissions/${axis.writeWire}/${section.wire}/access"

    /**
     * One page, searched and sorted by the server (web, 2026-09-14): `search`
     * filters before paging, `lang` says which language the translated labels
     * are matched and sorted in, and `only_used_designation=1` narrows the
     * designations axis to the hired ones. Each is sent only when it applies —
     * the contract is `=1`, and a `0` would lean on the server reading the
     * string "0" as false.
     */
    override suspend fun load(query: GridQuery): ZillitResult<GridPage> {
        val params = buildMap<String, Any> {
            put("page", query.page)
            put("limit", query.limit)
            if (query.search.isNotBlank()) put("search", query.search)
            if (query.axis == GridAxis.Designations && query.designations == DesignationFilter.Used) {
                put("only_used_designation", 1)
            }
            put("lang", languageCode())
        }
        return apiClient.request(
            verb = HttpVerb.Get,
            url = readUrl(query.axis, query.section),
            serializer = GridPageDto.serializer(),
            // Rights are per-person, per-production; a lighter header answers 406.
            module = RequestModule.ProjectUser,
            queryParameters = params,
        ).map { it.toPage(axis = query.axis, mine = currentUserId()) }
    }

    override suspend fun loadDefaults(axis: GridAxis, section: GridSection): ZillitResult<DefaultGridPage> =
        apiClient.request(
            verb = HttpVerb.Get,
            url = readUrl(axis, section),
            serializer = GridPageDto.serializer(),
            module = RequestModule.ProjectUser,
        ).map { it.toDefaultPage(axis) }

    /**
     * One cell.
     *
     * The endpoint answers `200` with `status: 0` when it refuses — a revoked
     * right the caller may not change, most often — so the envelope is read
     * rather than the HTTP code, and the server's own message is carried back
     * for the screen to show. Nothing in `data` is worth keeping: the caller
     * already knows what it asked for.
     */
    override suspend fun setAccess(
        axis: GridAxis,
        section: GridSection,
        entityId: String,
        unitId: String,
        kind: AccessKind,
        enable: Boolean,
    ): ZillitResult<Unit> {
        val body = SetAccessDto(
            unitId = unitId,
            enable = enable,
            accessType = kind.wire,
            userId = entityId.takeIf { axis.isPeople },
            departmentId = entityId.takeIf { axis == GridAxis.Departments },
            designationId = entityId.takeIf { axis == GridAxis.Designations },
        )
        return when (
            val outcome = apiClient.envelope(
                verb = HttpVerb.Post,
                url = writeUrl(axis, section),
                module = RequestModule.ProjectUser,
                body = jsonBody(body),
            )
        ) {
            is ZillitResult.Failure -> outcome
            is ZillitResult.Success ->
                if (outcome.data.status == 0) {
                    ZillitResult.Failure(refused(outcome.data))
                } else {
                    ZillitResult.Success(Unit)
                }
        }
    }

    private fun refused(envelope: ApiEnvelope): ZillitError =
        ZillitError.Http(status = HTTP_OK, serverMessage = envelope.message ?: "something_went_wrong")

    private companion object {
        const val HTTP_OK = 200
        val DEPARTMENT_REORDERED = SocketEventName("department:reordered")
    }
}
