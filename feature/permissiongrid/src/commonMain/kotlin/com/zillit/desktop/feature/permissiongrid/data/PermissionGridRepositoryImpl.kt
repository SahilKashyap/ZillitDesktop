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
import com.zillit.desktop.feature.permissiongrid.domain.AccessKind
import com.zillit.desktop.feature.permissiongrid.domain.GridAxis
import com.zillit.desktop.feature.permissiongrid.domain.GridPage
import com.zillit.desktop.feature.permissiongrid.domain.GridSection
import com.zillit.desktop.feature.permissiongrid.domain.PermissionGridRepository

/**
 * `permissions/{axis}/{section}/access` — the production's rights spreadsheet.
 *
 * The same pair of URLs the web uses. The admin settings page deliberately
 * reads the *per-user* route instead (`user/access/{id}`, one person at a
 * time, as both phones do); this tool is the spreadsheet, so it reads the one
 * the phones skip. The write is the same endpoint either way, which is why a
 * cell toggled here shows up on the admin page and vice versa.
 *
 * @param currentUserId whose row to hide — see [toPage].
 */
class PermissionGridRepositoryImpl(
    private val apiClient: ApiClient,
    config: AppConfig,
    private val currentUserId: () -> String?,
) : PermissionGridRepository {

    private val base = config.apiV2()

    private fun accessUrl(axis: GridAxis, section: GridSection) =
        "${base}permissions/${axis.wire}/${section.wire}/access"

    override suspend fun load(
        axis: GridAxis,
        section: GridSection,
        page: Int,
        limit: Int,
    ): ZillitResult<GridPage> =
        apiClient.request(
            verb = HttpVerb.Get,
            url = accessUrl(axis, section),
            serializer = GridPageDto.serializer(),
            // Rights are per-person, per-production; a lighter header answers 406.
            module = RequestModule.ProjectUser,
            queryParameters = mapOf("page" to page, "limit" to limit),
        ).map { it.toPage(axis = axis, mine = currentUserId()) }

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
            userId = entityId.takeIf { axis == GridAxis.Crew },
            departmentId = entityId.takeIf { axis == GridAxis.Departments },
            designationId = entityId.takeIf { axis == GridAxis.Designations },
        )
        return when (
            val outcome = apiClient.envelope(
                verb = HttpVerb.Post,
                url = accessUrl(axis, section),
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
    }
}
