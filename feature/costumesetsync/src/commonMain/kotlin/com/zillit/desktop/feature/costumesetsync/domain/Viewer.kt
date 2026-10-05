package com.zillit.desktop.feature.costumesetsync.domain

import com.zillit.desktop.core.permissions.ProjectPermissions

/**
 * What the Costumes & Set Sync tool lets this viewer do, from the tool's own
 * rights row (`costume_set_sync_tool`).
 *
 * **Deny until confirmed.** Unlike Sides or Recce, which permit everything
 * until the rights payload lands, nothing here may call the service until the
 * list has confirmed an enabled, viewable row: a production without the tool
 * switched on answers `403 costume_set_sync_tool_not_enabled`, and a 403
 * anywhere in this app is read as "no longer a member". [canCall] is the flag
 * every request sits behind.
 *
 * Product rule (the web's wiki, 2026-10-01): posting allows everything the
 * reference's role groups did — manager, cleaning, tailor, continuity all map
 * to [canPost]; view and download follow Zillit. Money is a separate gate,
 * [SyncProject.isFinance], not a right.
 */
data class SyncViewer(
    /** The rights list has answered. Until it has, nothing may call the service. */
    val resolved: Boolean = false,
    val enabled: Boolean = false,
    val canView: Boolean = false,
    val canPost: Boolean = false,
    val canDownload: Boolean = false,
) {
    val canCall: Boolean get() = resolved && enabled && canView

    companion object {
        const val TOOL = "costume_set_sync_tool"

        fun from(permissions: ProjectPermissions): SyncViewer {
            // An empty list is "not answered yet", not a denial.
            if (permissions.tools.isEmpty()) return SyncViewer()
            val access = permissions.access(TOOL)
            val row = permissions.tools.firstOrNull { it.identifier == TOOL }
            return SyncViewer(
                resolved = true,
                enabled = row?.enabled == true,
                canView = row != null && access.canView,
                canPost = row != null && access.canPost,
                canDownload = row != null && access.canDownload,
            )
        }
    }
}

/**
 * The service's own record of this production (`GET /projects/{id}`): its
 * name and dates, `my_role` (who sees Budget) and `counts` (whether it is set
 * up yet).
 */
data class SyncProject(val rec: Rec?, val financeRoles: Set<String>) {
    val name: String get() = rec?.str("project_name").orEmpty()
    val currency: String get() = rec?.str("currency").orEmpty()
    val myRole: String get() = rec?.str("my_role").orEmpty()

    /** Budget is for finance roles only — the service redacts money for anyone else. */
    val isFinance: Boolean get() = myRole.isNotEmpty() && myRole in financeRoles

    /**
     * Not set up yet: the record counts no scenes, characters, costumes or
     * actors. No record at all (still loading, or refused) is never "not set up".
     */
    val notSetUp: Boolean
        get() {
            val counts = rec?.rec("counts") ?: return false
            return listOf("scenes", "characters", "costumes", "actors").all { counts.long(it) == 0L }
        }

    /**
     * Who may set a production up: anyone with Zillit posting rights on the tool. The in-tool role plays no part
     * (the web dropped its role check on 2026-10-02; the service treats anyone who can post as ADMIN).
     */
    fun canSetUp(canPost: Boolean): Boolean = canPost
}
