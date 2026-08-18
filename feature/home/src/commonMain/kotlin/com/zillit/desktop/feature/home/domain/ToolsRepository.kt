package com.zillit.desktop.feature.home.domain

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.permissions.ProjectPermissions

/** Loads the production's tools and this user's rights to them. */
interface ToolsRepository {
    suspend fun loadPermissions(): ZillitResult<ProjectPermissions>

    /**
     * The production's tool groups, in the order they should be shown.
     *
     * Names come from the server because they are editable there — a
     * production renames "Art" to "Art & Props" and every client follows.
     * A failure is not fatal: the grid falls back to one ungrouped list.
     */
    suspend fun loadGroups(): ZillitResult<List<ToolGroup>>

    /**
     * This user's own order for the sections — group identifiers, top first
     * (`GET project/tools/group/order`). Empty when never customised: the
     * production's order stands. "Saved only for you", as Android's sheet
     * says; the web's modal is the same feature.
     */
    suspend fun loadGroupOrder(): ZillitResult<List<String>> = ZillitResult.Success(emptyList())

    /**
     * Saves the order (`PUT project/tools/group/order`, `{"order": [...]}`).
     * The server insists the list name every current group exactly once, so
     * callers send the reconciled full list.
     */
    suspend fun saveGroupOrder(order: List<String>): ZillitResult<Unit> = ZillitResult.Success(Unit)
}

/** One section of the tools grid. */
data class ToolGroup(val identifier: String, val name: String)
