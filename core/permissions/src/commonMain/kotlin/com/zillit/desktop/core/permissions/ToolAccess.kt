package com.zillit.desktop.core.permissions

/**
 * What one crew member may do with one tool, in one production.
 *
 * Transcribed from the Android `ToolsInfo` (`GET project/tools`). The three
 * access flags are independent: a driver can view the call sheet without posting
 * to it, and an accountant can download a report they cannot edit.
 */
data class ToolAccess(
    val identifier: String,
    val groupIdentifier: String? = null,
    val unitId: String? = null,
    val unitName: String? = null,
    /** Turned on for the production at all. A project-level switch, not a right. */
    val enabled: Boolean = true,
    val canView: Boolean = false,
    val canPost: Boolean = false,
    val canDownload: Boolean = false,
    /** Appears in the tools grid. */
    val isTool: Boolean = true,
    /** Appears on the home dashboard. */
    val onHome: Boolean = false,
) {
    companion object {
        /**
         * What an unknown tool resolves to.
         *
         * Every flag false. A tool the server did not mention is one this user
         * has no rights to, and the safe reading of "absent" is "denied" — see
         * [ProjectPermissions.access].
         */
        fun denied(identifier: String) = ToolAccess(identifier = identifier, enabled = false)
    }
}

/**
 * The resolved rights for the open production.
 *
 * ## Why this is a core module
 *
 * Every feature module from M4 onward asks it questions, so it cannot live in
 * any one of them. It holds no state beyond the answer set and does no I/O —
 * `feature:home` fetches the list and hands it here.
 *
 * ## Admin bypass, and what it does not bypass
 *
 * An admin passes every *access* check, matching Android
 * (`AssetRegisterRights.from`: `canView = isAdmin || info?.viewAccess == true`).
 *
 * An admin does **not** bypass [ToolAccess.enabled]. That flag is the
 * production's own switch — a tool turned off for the shoot is off for
 * everyone, and showing admins a tool nobody else can see would misrepresent
 * the production rather than grant a privilege. The web agrees
 * (`view_access == true && item?.enabled == true`).
 */
class ProjectPermissions(
    tools: List<ToolAccess>,
    val isAdmin: Boolean = false,
) {
    private val byIdentifier: Map<String, ToolAccess> =
        tools.associateBy { it.identifier }

    /** Every tool the server issued, as issued — for keeping a copy of the answer. */
    val tools: List<ToolAccess> get() = byIdentifier.values.toList()

    /**
     * Rights for [identifier], with admin bypass applied.
     *
     * An identifier that was never issued resolves to [ToolAccess.denied] rather
     * than throwing: rights arrive from the server and a client that crashes on
     * an unfamiliar tool name would break every time the backend adds one.
     */
    fun access(identifier: String): ToolAccess {
        val raw = byIdentifier[identifier] ?: return ToolAccess.denied(identifier)
        if (!isAdmin) return raw

        return raw.copy(canView = true, canPost = true, canDownload = true)
    }

    fun canView(identifier: String): Boolean = access(identifier).let { it.enabled && it.canView }

    /** Posting implies viewing — the UI should never offer an edit on a hidden tool. */
    fun canPost(identifier: String): Boolean = canView(identifier) && access(identifier).canPost

    fun canDownload(identifier: String): Boolean =
        canView(identifier) && access(identifier).canDownload

    /** Everything this user may open, in the order the server returned it. */
    val visibleTools: List<ToolAccess>
        get() = byIdentifier.values.filter { it.enabled && (isAdmin || it.canView) }

    val homeTools: List<ToolAccess> get() = visibleTools.filter { it.onHome }

    val gridTools: List<ToolAccess> get() = visibleTools.filter { it.isTool }

    companion object {
        /** Before the tools call returns: nothing is permitted, nothing is shown. */
        val Empty = ProjectPermissions(emptyList(), isAdmin = false)
    }
}
