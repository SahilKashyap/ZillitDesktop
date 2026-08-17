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
 *    document, deleting one, uploading a document to send for signature, and
 *    the "my sent documents" area as a whole.
 *  - **Everything else is open to any viewer**: reading, self-assigning a
 *    form to your own list, checking history, downloading, and *signing* —
 *    a view-only crew member must be able to sign what is sent to them,
 *    which is most of what this tool exists for.
 */
data class FormSignatureViewer(
    val canView: Boolean = true,
    val canPost: Boolean = true,
    val isAdmin: Boolean = false,
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
        fun from(permissions: ProjectPermissions): FormSignatureViewer {
            val access = permissions.access(TOOL_IDENTIFIER)
            if (!access.enabled && permissions.visibleTools.isEmpty()) {
                return FormSignatureViewer(ready = false)
            }
            val post = access.enabled && access.canPost
            return FormSignatureViewer(
                canView = post || (access.enabled && access.canView),
                canPost = post,
                isAdmin = permissions.isAdmin,
                ready = true,
            )
        }
    }
}
