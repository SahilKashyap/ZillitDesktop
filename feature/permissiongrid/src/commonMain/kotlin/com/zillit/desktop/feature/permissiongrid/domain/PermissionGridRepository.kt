package com.zillit.desktop.feature.permissiongrid.domain

import com.zillit.desktop.core.common.ZillitResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/** What one page of the grid is asked for — the web's `getPermissondata` params. */
data class GridQuery(
    val axis: GridAxis,
    val section: GridSection,
    /** Zero-based, as the endpoint takes it; the screen counts from one. */
    val page: Int,
    val limit: Int,
    /** Trimmed and lower-cased; blank sends no `search` at all. */
    val search: String = "",
    /** Only read on the designations axis. */
    val designations: DesignationFilter = DesignationFilter.Used,
)

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
     * Departments re-ordered elsewhere (`department:reordered`) — the
     * crew-list axis follows that order, so the web re-reads on it.
     */
    val departmentsReordered: Flow<Unit> get() = emptyFlow()

    /** One page of the grid, searched, filtered and sorted by the server. */
    suspend fun load(query: GridQuery): ZillitResult<GridPage>

    /**
     * The whole of one axis's grid, unpaged — the Default Grid's read, which
     * the web makes without `page` or `limit`.
     */
    suspend fun loadDefaults(axis: GridAxis, section: GridSection): ZillitResult<DefaultGridPage>

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
