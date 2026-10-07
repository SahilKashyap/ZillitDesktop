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

    // -- Manage Tool Groups (admin) — the calls the Admin Settings page makes ---

    /**
     * Files a tool under a group (`PUT project/tools/group`, `{identifier,
     * group_identifier}`); an empty [groupIdentifier] takes it out of every
     * group. Per production, not per user.
     */
    suspend fun moveTool(identifier: String, groupIdentifier: String): ZillitResult<Unit> =
        ZillitResult.Success(Unit)

    /** `POST project/tools/groups` `{group_name}`; answers the new group's identifier when the server echoes it. */
    suspend fun createGroup(name: String): ZillitResult<String?> = ZillitResult.Success(null)

    /** `PUT project/tools/groups/{tool_group_id}` `{group_name}`. */
    suspend fun renameGroup(toolGroupId: String, name: String): ZillitResult<Unit> = ZillitResult.Success(Unit)

    /** `DELETE project/tools/groups/{tool_group_id}` — refused as `tool_group_in_use` while it holds tools. */
    suspend fun deleteGroup(toolGroupId: String): ZillitResult<Unit> = ZillitResult.Success(Unit)
}

/**
 * One section of the tools grid.
 *
 * [id] is the row id the rename and delete calls address (`tool_group_id`);
 * [systemDefined] marks the six defaults, which can be renamed but never
 * deleted (the backend answers `system_defined_tool_group`).
 */
data class ToolGroup(
    val identifier: String,
    val name: String,
    val id: String? = null,
    val systemDefined: Boolean = false,
)
