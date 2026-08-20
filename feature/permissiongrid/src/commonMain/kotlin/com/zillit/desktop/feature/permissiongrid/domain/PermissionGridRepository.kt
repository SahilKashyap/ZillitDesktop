package com.zillit.desktop.feature.permissiongrid.domain

import com.zillit.desktop.core.common.ZillitResult

/** Reads and writes the production's viewing & posting rights grid. */
interface PermissionGridRepository {

    /**
     * One page of the grid for [axis] and [section].
     *
     * [page] is zero-based, as the endpoint takes it — the screen counts from
     * one and subtracts here rather than leaking the off-by-one into the UI.
     */
    suspend fun load(
        axis: GridAxis,
        section: GridSection,
        page: Int,
        limit: Int,
    ): ZillitResult<GridPage>

    /**
     * Grants or revokes one right on one cell.
     *
     * One call per cell, as every client does it: the endpoint takes a single
     * `{entity, unit_id, access_type, enable}` and there is no bulk form.
     */
    suspend fun setAccess(
        axis: GridAxis,
        section: GridSection,
        entityId: String,
        unitId: String,
        kind: AccessKind,
        enable: Boolean,
    ): ZillitResult<Unit>
}
