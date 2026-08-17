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
}

/** One section of the tools grid. */
data class ToolGroup(val identifier: String, val name: String)
