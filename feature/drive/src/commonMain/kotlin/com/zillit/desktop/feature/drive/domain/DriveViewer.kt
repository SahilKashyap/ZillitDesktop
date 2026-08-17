package com.zillit.desktop.feature.drive.domain

import com.zillit.desktop.core.permissions.ProjectPermissions

/**
 * Who is looking at the Drive, and what that entitles them to.
 *
 * ## Two gates, not one
 *
 * Drive is the only tool in the app with a **two-level** rights model, and
 * conflating them is the mistake to avoid:
 *
 *  1. **Tool access** — `drive_tool` in the production's permission grid.
 *     Decides whether the Drive opens at all and whether Upload / New Folder
 *     appear (FR-21.1).
 *  2. **Per-item access** — [DrivePermissions] on each row, resolved by the
 *     server from folder roles, explicit file grants and creator ownership.
 *     Decides what may be done to *that file*.
 *
 * Someone with posting rights on the tool still cannot delete a file they only
 * have view access to, and an admin bypasses both. [may] is where the two are
 * combined, once, so no screen has to remember to check both.
 */
data class DriveViewer(
    val userId: String = "",
    val displayName: String = "",
    val canView: Boolean = true,
    /** Tool-level posting: whether Upload and New Folder are offered at all. */
    val canPost: Boolean = true,
    val canDownload: Boolean = true,
    val isAdmin: Boolean = false,
    /** False until the tools call has answered. See [DriveViewer.Companion.from]. */
    val ready: Boolean = false,
) {

    val isBlocked: Boolean get() = ready && !canView

    /**
     * Whether this viewer may do [action] to [item].
     *
     * An admin passes everything — that is the server's rule too (FR-05.6), and
     * a client that hides an action the server would allow makes an admin think
     * the file is locked.
     *
     * Otherwise both gates apply: the tool grant *and* the item's own
     * permissions. Download is the one that catches people out — a viewer with
     * item-level download but no tool-level download right must not get the
     * button, because the server refuses it at the middleware before it ever
     * reaches the per-item check.
     */
    fun may(action: DriveAction, item: DriveItem): Boolean {
        if (isAdmin) return true
        if (!canView) return false
        return toolGrantFor(action) && itemGrantFor(action, grantsOn(item))
    }

    /**
     * What this viewer holds on [item] — the server's flags, or ownership.
     *
     * **FR-05.7: a file's creator always gets owner-level rights**, and the
     * listing routes do not send per-item flags at all. Without this rule every
     * row resolves to [DrivePermissions.ViewOnly] and nobody but an admin can
     * download or delete anything, including files they uploaded a moment ago.
     * The web derives the same thing client-side
     * (`String(record.created_by || record.uploaded_by) === String(currentUserId)`).
     *
     * Explicit grants still win: a row the server *did* describe is described
     * correctly, and only the silent case falls back to ownership.
     */
    private fun grantsOn(item: DriveItem): DrivePermissions {
        if (item.permissions != DrivePermissions.ViewOnly) return item.permissions
        val mine = userId.isNotBlank() && item.uploadedById == userId
        return if (mine) DrivePermissions.Owner else item.permissions
    }

    /** The tool-level half of [may] — the production's permission grid. */
    private fun toolGrantFor(action: DriveAction): Boolean = when (action) {
        DriveAction.View -> true
        DriveAction.Download -> canDownload
        // Everything that changes something needs posting rights on the tool,
        // whatever the item itself says.
        DriveAction.Edit, DriveAction.Delete, DriveAction.Share -> canPost
    }

    /** The item-level half of [may] — what the server resolved for this row. */
    private fun itemGrantFor(action: DriveAction, granted: DrivePermissions): Boolean =
        when (action) {
            DriveAction.View -> granted.canView
            DriveAction.Edit -> granted.canEdit
            DriveAction.Download -> granted.canDownload
            DriveAction.Delete -> granted.canDelete
            // Sharing rewrites who else can reach the file, so it is an owner's
            // act — the server models that as delete rights, which only owners
            // and creators hold.
            DriveAction.Share -> granted.canDelete
        }

    /** Whether Upload and New Folder appear. Tool-level only — there is no item yet. */
    val canCreate: Boolean get() = isAdmin || (canView && canPost)

    companion object {
        const val TOOL_IDENTIFIER = "drive_tool"

        /**
         * Reads this person's tool rights out of the production's permission set.
         *
         * [ProjectPermissions.Empty] — the state before the tools call returns
         * — answers false to everything, which would deny the whole tool while
         * it is still loading. So an empty set resolves to "not yet known"
         * rather than to a denial, and [ready] is what tells the two apart.
         */
        fun from(
            permissions: ProjectPermissions,
            userId: String,
            displayName: String,
        ): DriveViewer {
            val access = permissions.access(TOOL_IDENTIFIER)
            if (!access.enabled && permissions.visibleTools.isEmpty()) {
                return DriveViewer(userId = userId, displayName = displayName, ready = false)
            }
            return DriveViewer(
                userId = userId,
                displayName = displayName,
                canView = permissions.canView(TOOL_IDENTIFIER),
                canPost = permissions.canPost(TOOL_IDENTIFIER),
                canDownload = permissions.canDownload(TOOL_IDENTIFIER),
                isAdmin = permissions.isAdmin,
                ready = true,
            )
        }
    }
}

/** The things that can be done to a drive item, for [DriveViewer.may]. */
enum class DriveAction { View, Edit, Download, Delete, Share }

/**
 * Which of [items] this viewer may act on.
 *
 * Bulk operations are permission-checked **per item** by the server (FR-05 /
 * NFR-04.7), so a selection of twenty where three are read-only deletes
 * seventeen. Filtering here first is what lets the toolbar say "delete 17 of
 * 20" rather than reporting a partial success afterwards.
 */
fun DriveViewer.eligible(action: DriveAction, items: List<DriveItem>): List<DriveItem> =
    items.filter { may(action, it) }
