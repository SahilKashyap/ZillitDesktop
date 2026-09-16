package com.zillit.desktop.feature.formsignature.domain

import com.zillit.desktop.core.permissions.ProjectPermissions

/**
 * Who is in the Documents & Signature tool, and what that entitles them to.
 *
 * The web's rule set, transcribed:
 *
 *  - **view access is the door.** Without it the web bounces the route back
 *    to Film Tools; here the screen renders its refusal instead.
 *  - **posting access is authorship.** It gates uploading a standard
 *    document, deleting one, and the whole Documents for Signature tile
 *    (`ContractSignatureTiles.jsx`: `visible: isPostingRights && …`).
 *  - **Everything else is open to any viewer**: reading, adding a form to
 *    your downloads, checking history, downloading, and *signing*.
 *  - **A pending member sees only the signature block** — both document
 *    tiles hide while `project.status === 'pending'`.
 */
data class FormSignatureViewer(
    val canView: Boolean = true,
    val canPost: Boolean = true,
    val isAdmin: Boolean = false,
    /** This person has not been accepted onto the production yet. */
    val isPending: Boolean = false,
    /** False until the production's tools call has answered. */
    val ready: Boolean = false,
) {

    /** Only a resolved denial blocks; the server re-checks every call anyway. */
    val isBlocked: Boolean get() = ready && !canView

    companion object {
        /** The backend's `project_tools_list` identifier. */
        const val TOOL_IDENTIFIER = "forms_and_signature_tool"

        /**
         * Reads rights from the production's permission grid, with the
         * desktop's usual admin bypass via [ProjectPermissions.access] and
         * "empty grid = still loading" resolution — see the identical
         * reasoning on the drive and budget-builder viewers.
         */
        fun from(permissions: ProjectPermissions, isPending: Boolean = false): FormSignatureViewer {
            val access = permissions.access(TOOL_IDENTIFIER)
            if (!access.enabled && permissions.visibleTools.isEmpty()) {
                return FormSignatureViewer(ready = false, isPending = isPending)
            }
            val post = access.enabled && access.canPost
            return FormSignatureViewer(
                canView = post || (access.enabled && access.canView),
                canPost = post,
                isAdmin = permissions.isAdmin,
                isPending = isPending,
                ready = true,
            )
        }
    }
}
