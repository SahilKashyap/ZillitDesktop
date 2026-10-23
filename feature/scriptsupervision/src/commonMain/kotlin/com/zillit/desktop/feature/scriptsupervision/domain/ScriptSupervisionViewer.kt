package com.zillit.desktop.feature.scriptsupervision.domain

import com.zillit.desktop.core.permissions.ProjectPermissions

/**
 * What the Continuity Script Supervision tool lets this viewer do, from the
 * tool's own rights row (`script_supervision_tool`).
 *
 * **Deny until confirmed**, as Costumes & Set Sync does: nothing here may call
 * the service until the rights list has confirmed an enabled, viewable row.
 * The web gates the whole module on viewing rights for the identifier
 * (`useToolDisabledRedirect`), and a tool-not-enabled 403 anywhere in this app
 * reads as "no longer a member". [canCall] is the flag every request sits
 * behind.
 *
 * ⚠ The identifier is the FULL `script_supervision_tool`. The Film Tools tile
 * matcher is a substring test, so an identifier holding `continuity` or
 * `script_notes` would open Continuity or Script Notes instead — which is why
 * the web's tile carries the whole identifier as its name.
 */
data class ScriptSupervisionViewer(
    /** The rights list has answered. Until it has, nothing may call the service. */
    val resolved: Boolean = false,
    val enabled: Boolean = false,
    val canView: Boolean = false,
    val canPost: Boolean = false,
    val canDownload: Boolean = false,
) {
    val canCall: Boolean get() = resolved && enabled && canView

    /** Resolved and refused — what the tool window shows its "no access" panel for. */
    val isBlocked: Boolean get() = resolved && !canCall

    companion object {
        const val TOOL = "script_supervision_tool"

        fun from(permissions: ProjectPermissions): ScriptSupervisionViewer {
            // An empty list is "not answered yet", not a denial.
            if (permissions.tools.isEmpty()) return ScriptSupervisionViewer()
            val access = permissions.access(TOOL)
            val row = permissions.tools.firstOrNull { it.identifier == TOOL }
            return ScriptSupervisionViewer(
                resolved = true,
                enabled = row?.enabled == true,
                canView = row != null && access.canView,
                canPost = row != null && access.canPost,
                canDownload = row != null && access.canDownload,
            )
        }
    }
}
