package com.zillit.desktop.feature.home.data

import com.zillit.desktop.core.permissions.ProjectPermissions
import com.zillit.desktop.core.permissions.ToolAccess
import com.zillit.desktop.feature.home.domain.ToolGroup
import kotlinx.serialization.Serializable

/**
 * The tools grid as last answered, kept per production so the grid can be
 * drawn when the network is gone — without it there is no way into any tool,
 * offline or not.
 *
 * A snapshot of the *answer*, not of the rights object: `ProjectPermissions`
 * derives everything from this list plus the admin flag, and rebuilding it
 * from the copy goes through the same constructor as a live answer, so the
 * "absent means denied" rule holds for a cached grid too.
 */
@Serializable
data class ToolsOfflineCopy(
    val tools: List<ToolAccessCopy>,
    val isAdmin: Boolean,
    val groups: List<ToolGroupCopy> = emptyList(),
    val groupOrder: List<String> = emptyList(),
) {
    fun permissions(): ProjectPermissions = ProjectPermissions(tools.map { it.toAccess() }, isAdmin)

    fun toolGroups(): List<ToolGroup> = groups.map { ToolGroup(it.identifier, it.name) }

    companion object {
        fun of(permissions: ProjectPermissions, groups: List<ToolGroup>, groupOrder: List<String>) = ToolsOfflineCopy(
            tools = permissions.tools.map { ToolAccessCopy.of(it) },
            isAdmin = permissions.isAdmin,
            groups = groups.map { ToolGroupCopy(it.identifier, it.name) },
            groupOrder = groupOrder,
        )
    }
}

@Serializable
data class ToolAccessCopy(
    val identifier: String,
    val groupIdentifier: String? = null,
    val unitId: String? = null,
    val unitName: String? = null,
    val enabled: Boolean = true,
    val canView: Boolean = false,
    val canPost: Boolean = false,
    val canDownload: Boolean = false,
    val isTool: Boolean = true,
    val onHome: Boolean = false,
) {
    fun toAccess() = ToolAccess(
        identifier = identifier,
        groupIdentifier = groupIdentifier,
        unitId = unitId,
        unitName = unitName,
        enabled = enabled,
        canView = canView,
        canPost = canPost,
        canDownload = canDownload,
        isTool = isTool,
        onHome = onHome,
    )

    companion object {
        fun of(access: ToolAccess) = ToolAccessCopy(
            identifier = access.identifier,
            groupIdentifier = access.groupIdentifier,
            unitId = access.unitId,
            unitName = access.unitName,
            enabled = access.enabled,
            canView = access.canView,
            canPost = access.canPost,
            canDownload = access.canDownload,
            isTool = access.isTool,
            onHome = access.onHome,
        )
    }
}

@Serializable
data class ToolGroupCopy(val identifier: String, val name: String)
