package com.zillit.desktop.feature.permissiongrid.domain

import com.zillit.desktop.core.common.ZillitResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/** Reads and writes the production's viewing & posting rights grid. */
interface PermissionGridRepository {

    /**
     * Rights changes announced over the socket — the backend's three
     * `access-grid:{viewing,posting,download}-rights:update:sync` events
     * (ZL-17812), so an edit made on another client lands in an open grid.
     * Defaulted empty for tests and hosts without a socket.
     */
    val syncs: Flow<RightsSync> get() = emptyFlow()

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
