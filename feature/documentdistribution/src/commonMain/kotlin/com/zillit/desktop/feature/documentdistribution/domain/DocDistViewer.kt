package com.zillit.desktop.feature.documentdistribution.domain

import com.zillit.desktop.core.permissions.ProjectPermissions

/**
 * What one publish destination offers this person.
 *
 * Three independent answers, because the dialog says a different thing to
 * each: [visible] false with [enabled] true is "No access", [visible] true
 * with [canPost] false is "View only", and [enabled] false is a module the
 * production has not switched on — which no amount of granting will fix, so
 * that case offers no Request button.
 */
data class PublishAccess(
    val visible: Boolean,
    val canPost: Boolean,
    val enabled: Boolean,
) {
    companion object {
        /** A destination gated outside this module; offered, and the server decides. */
        val Granted = PublishAccess(visible = true, canPost = true, enabled = true)

        /** Rights have not arrived — the module's soft default, not a denial. */
        val Unknown = Granted

        /** No row at all: on the web that reads as a module the production has not enabled. */
        val Denied = PublishAccess(visible = false, canPost = false, enabled = false)
    }
}

/**
 * Who is looking at Document Distribution, and what that entitles them to.
 *
 * Unlike the finance tools — where rights come from a department and a
 * designation — this module is gated purely by the production's **tool access
 * grid**: `view_access`, `posting_access` and `download_access` on
 * `document_distribution_tool`. That is what the web reads
 * (`useDocumentDistributionRights`) and what an admin edits in the permission
 * grid, so it is what this reads too.
 *
 * ## The soft default
 *
 * Before the rights payload arrives the web assumes *permissive* — all three
 * true, `isReady` false — and only bounces the user once the list has loaded
 * and explicitly withholds view. Copied deliberately: the alternative flashes a
 * "no access" screen at every user on every open while the tools call is in
 * flight, and the server enforces the real gate on every write regardless.
 * [ready] is what tells the two apart.
 */
data class DocDistViewer(
    val userId: String = "",
    val userEmail: String = "",
    val canView: Boolean = true,
    val canPost: Boolean = true,
    val canDownload: Boolean = true,
    /** False until the tools call has answered. See the class doc. */
    val ready: Boolean = false,
    /** Television productions make the episode number mandatory on the tool destinations. */
    val isTelevision: Boolean = false,
    /**
     * What this person may do with each publish destination, by category.
     *
     * Each destination is gated by its *receiving* tool's row, not by this
     * tool's — publishing a call sheet writes into the Call Sheet tool, so
     * that tool decides. Unlike the old shape, which simply dropped the
     * destinations the person could not post to, every one is kept: the web
     * shows them all so somebody who cannot post yet can still find the
     * destination and ask for the right (`PublishModal` → `visibleCategories`).
     *
     * Empty until the tools call answers — see [accessFor], which reads an
     * empty map as "not known yet" rather than as a denial.
     */
    val publishAccess: Map<String, PublishAccess> = emptyMap(),
) {
    /**
     * This person's standing on one destination.
     *
     * An unknown category is denied, as on the web, where a destination with
     * no rights row reads as a module the production has not enabled. An
     * *empty map* is different: rights have not arrived, and the module's
     * documented soft default applies rather than a flash of "no access" on
     * every open.
     */
    fun accessFor(category: String): PublishAccess = when {
        publishAccess.isEmpty() -> PublishAccess.Unknown
        else -> publishAccess[category] ?: PublishAccess.Denied
    }

    /** The destinations this person may actually post to. */
    val publishable: Set<String>
        get() = publishAccess.filterValues { it.canPost }.keys

    fun targets(): List<PublishTarget> = PublishTarget.all.filter { it.category in publishable }

    /**
     * Whether the tool should refuse to render.
     *
     * Only once rights are known: an unresolved viewer is never denied.
     */
    val isBlocked: Boolean get() = ready && !canView

    /** Whether to show the "you can look but not send" banner the web shows. */
    val isRestricted: Boolean get() = ready && (!canPost || !canDownload)

    companion object {
        const val TOOL_IDENTIFIER = "document_distribution_tool"

        /**
         * Reads this person's rights out of the production's permission set.
         *
         * [ProjectPermissions.Empty] — the state before the tools call returns
         * — answers false to everything, which would deny the whole tool. So an
         * empty set is treated as "not yet known" and resolves to the soft
         * default rather than to a denial.
         */
        fun from(
            permissions: ProjectPermissions,
            userId: String,
            userEmail: String,
            isTelevision: Boolean = false,
        ): DocDistViewer {
            val access = permissions.access(TOOL_IDENTIFIER)
            if (!access.enabled && permissions.visibleTools.isEmpty()) {
                return DocDistViewer(
                    userId = userId,
                    userEmail = userEmail,
                    ready = false,
                    isTelevision = isTelevision,
                )
            }
            return DocDistViewer(
                userId = userId,
                userEmail = userEmail,
                canView = permissions.canView(TOOL_IDENTIFIER),
                canPost = permissions.canPost(TOOL_IDENTIFIER),
                canDownload = permissions.canDownload(TOOL_IDENTIFIER),
                ready = true,
                isTelevision = isTelevision,
                publishAccess = publishAccess(permissions),
            )
        }

        /**
         * A destination with no [PublishTarget.toolIdentifier] — the Call
         * Sheet unit — is gated by the home unit list rather than the tool
         * grid, which this module does not hold. It is offered as postable,
         * and the server refuses it if the unit is not this person's to post
         * to. (The web resolves it against `getHomeUnitData`; the desktop has
         * no equivalent inside this tool, so it cannot show "View only" there.)
         */
        private fun publishAccess(permissions: ProjectPermissions): Map<String, PublishAccess> =
            PublishTarget.all.associate { target ->
                val tool = target.toolIdentifier
                    ?: return@associate target.category to PublishAccess.Granted
                val row = permissions.access(tool)
                target.category to PublishAccess(
                    // A tool turned off for the production is off for admins
                    // too, so `enabled` is read raw and never bypassed.
                    visible = permissions.canView(tool),
                    canPost = permissions.canPost(tool),
                    enabled = row.enabled,
                )
            }
    }
}
