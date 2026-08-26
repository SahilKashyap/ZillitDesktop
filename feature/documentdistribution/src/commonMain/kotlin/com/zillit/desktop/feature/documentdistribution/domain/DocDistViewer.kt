package com.zillit.desktop.feature.documentdistribution.domain

import com.zillit.desktop.core.permissions.ProjectPermissions

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
     * The publish destinations this person may actually send to.
     *
     * Each one is gated by its *receiving* tool's posting right, not by this
     * tool's — publishing a call sheet writes into the Call Sheet tool, so
     * that tool decides. Offering a destination the person cannot post to
     * produces a server refusal at the last step of a filled-in form.
     */
    val publishable: Set<String> = emptySet(),
) {
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
                publishable = publishableTargets(permissions),
            )
        }

        /**
         * A destination with no [PublishTarget.toolIdentifier] — the Call
         * Sheet unit — is gated by the home unit list rather than the tool
         * grid, which this module does not hold. It is offered, and the
         * server refuses it if the unit is not this person's to post to.
         */
        private fun publishableTargets(permissions: ProjectPermissions): Set<String> =
            PublishTarget.all
                .filter { it.toolIdentifier == null || permissions.canPost(it.toolIdentifier) }
                .map { it.category }
                .toSet()
    }
}
