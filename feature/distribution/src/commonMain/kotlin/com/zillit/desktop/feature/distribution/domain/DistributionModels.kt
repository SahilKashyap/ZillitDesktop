package com.zillit.desktop.feature.distribution.domain

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.permissions.ProjectPermissions

/**
 * The Distribution List is not a list: it is a per-user × per-unit opt-in
 * matrix. One boolean per (user, unit) — "To" — decides whether content
 * distributed from that Home unit or tool reaches that user by email. The
 * only mutation in the whole tool is a single toggle.
 *
 * Not `document_distribution_tool`: the two names are one letter apart and
 * share nothing — different backend, different permission-grid row.
 */
data class DistributionUser(
    val userId: String,
    val userName: String = "",
    /** `external` is the only value either client tests. */
    val userType: String = "",
    /** `accepted`, `removed`… — only accepted crew and outsiders are rows. */
    val status: String = "",
    /** The literal `outsider` marks an external contact. */
    val outsider: String = "",
    val units: List<DistributionUnit> = emptyList(),
) {
    val isExternal: Boolean get() = userType == "external" || outsider == OUTSIDER

    /** The web's row rule: accepted crew, or any external. */
    val isListed: Boolean get() = status == ACCEPTED || isExternal

    /** The row's cell for [unitId] under [section], if the server sent one. */
    fun cell(unitId: String, section: DistributionSection): DistributionUnit? =
        units.firstOrNull { it.unitId == unitId && it.inSection(section) }

    companion object {
        const val ACCEPTED = "accepted"
        const val OUTSIDER = "outsider"
    }
}

/**
 * One cell's row: a Home unit or a tool, and whether this user is on its
 * distribution. [isTool]/[isHome] discriminate the two sections; Cc and Bcc
 * exist on the wire but render nowhere on any client — modelled, never shown.
 */
data class DistributionUnit(
    val unitId: String,
    /** A label key — translate before display and sort. */
    val unitName: String,
    val toEnabled: Boolean = false,
    val ccEnabled: Boolean = false,
    val bccEnabled: Boolean = false,
    val isTool: Boolean = false,
    val isHome: Boolean = false,
    /** Android honours this for switch enablement; the web ignores it. */
    val toUpdatable: Boolean = true,
) {
    fun inSection(section: DistributionSection): Boolean =
        if (section == DistributionSection.Home) isHome else isTool
}

/** The section chooser — the wire's `type` values, verbatim. */
enum class DistributionSection(val wire: String, val label: String) {
    Home("home", "Home"),
    Tools("tools", "Tools"),
}

/**
 * What the grid knows about a person beyond the wire's row — the web's
 * `usersList` (crew) merged with `useExternalUsers()` (outsiders), keyed by
 * user id. The row carries only `user_name`; the face, department, designation
 * and acceptance status come from here.
 */
data class DistributionPerson(
    val userId: String,
    val fullName: String = "",
    val email: String = "",
    /** A label key — translate before display. */
    val department: String = "",
    /** A label key — translate before display. */
    val designation: String = "",
    /** `accepted` and friends for crew; blank for an outsider. */
    val status: String = "",
    val isExternal: Boolean = false,
)

/** Where the people directory comes from — the crew list and the external users, merged by the host. */
fun interface DistributionDirectory {
    suspend fun people(): List<DistributionPerson>
}

/**
 * The tool's rights, from `distribution_tool`.
 *
 * The web's gate: admins bypass the view check entirely; non-admins need
 * `view_access`. The switches need `posting_access` (admin passes).
 */
data class DistributionViewer(
    val canView: Boolean = true,
    val canPost: Boolean = false,
    val isAdmin: Boolean = false,
    val ready: Boolean = false,
) {
    val isBlocked: Boolean get() = ready && !canView && !isAdmin
    val mayToggle: Boolean get() = isAdmin || canPost

    companion object {
        const val TOOL_IDENTIFIER = "distribution_tool"

        fun from(permissions: ProjectPermissions): DistributionViewer {
            if (permissions.tools.isEmpty()) return DistributionViewer()
            return DistributionViewer(
                canView = permissions.canView(TOOL_IDENTIFIER),
                canPost = permissions.canPost(TOOL_IDENTIFIER),
                isAdmin = permissions.isAdmin,
                ready = true,
            )
        }
    }
}

interface DistributionRepository {
    /** Every listed user with their unit rows — `user/access/all`. */
    suspend fun allAccess(): ZillitResult<List<DistributionUser>>

    /**
     * Flips one cell. The body's `distributionEnable` is the new value;
     * [section] is the wire's `home`/`tools`. Answers the server's message —
     * the web toasts it verbatim (`message[res.status ? 'success' : 'warning']`).
     */
    suspend fun setAccess(
        userId: String,
        unitId: String,
        enabled: Boolean,
        section: DistributionSection,
        isExternal: Boolean,
    ): ZillitResult<String?>
}
