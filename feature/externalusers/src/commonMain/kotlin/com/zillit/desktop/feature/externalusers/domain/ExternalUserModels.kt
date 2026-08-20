package com.zillit.desktop.feature.externalusers.domain

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.permissions.ProjectPermissions

/**
 * One external contact — a project-scoped address-book record.
 *
 * Not an account: external users are never invited, never sign in, and hold
 * no rights. Other tools (mail, distribution, e-signature) consume them as
 * recipients. Both phones and the web agree on this; there is no
 * invite/revoke flow to port.
 */
data class ExternalUser(
    val id: String = "",
    val fullName: String = "",
    val email: String = "",
    val phone: String = "",
    /** The dial code with its `+` (`+44`), not an ISO country code. */
    val countryCode: String = "",
    /** `male` | `female` | `non-binary`; legacy rows may say `other`. */
    val gender: String = "",
    /**
     * `crew_member_label`, `vender_label` (the wire's own misspelling), or —
     * for the "Others" case — whatever the user typed, stored verbatim.
     */
    val userType: String = "",
    val departmentId: String = "",
    val designationId: String = "",
    val otherInfo: List<LabeledValue> = emptyList(),
    /** Who made the record — edit rights hang off this. */
    val createdBy: String = "",
    /** The pagination cursor and sort stamp. */
    val updatedOnMillis: Long = 0,
)

data class LabeledValue(val label: String, val value: String)

/**
 * The filter buckets, as both clients bucket the free-form type: the two
 * known labels are themselves, anything else non-blank is Others.
 */
enum class ExternalUserBucket(val wire: String, val label: String) {
    All("all", "All"),
    Crew(CREW_TYPE, "Crew member"),
    Vendor(VENDOR_TYPE, "Vendor"),
    Others("others", "Others"),
    ;

    companion object {
        fun of(userType: String): ExternalUserBucket = when {
            userType == CREW_TYPE -> Crew
            userType == VENDOR_TYPE -> Vendor
            userType.isNotBlank() -> Others
            else -> Crew
        }
    }
}

const val CREW_TYPE = "crew_member_label"

/** `vender`, not `vendor` — the wire's spelling, load-bearing. */
const val VENDOR_TYPE = "vender_label"

/**
 * The tool's rights, read from `external_users_tool`.
 *
 * Opening needs `view_access`; the Add button needs `posting_access`; a row's
 * Edit/Delete need posting **and** ownership-or-admin — the web's rule, which
 * is the stricter of the two clients and the intended one.
 */
data class ExternalUsersViewer(
    val userId: String = "",
    val canView: Boolean = true,
    val canPost: Boolean = false,
    val isAdmin: Boolean = false,
    val ready: Boolean = false,
) {
    val isBlocked: Boolean get() = ready && !canView && !isAdmin

    fun mayEdit(user: ExternalUser): Boolean =
        canPost && (isAdmin || user.createdBy == userId)

    companion object {
        const val TOOL_IDENTIFIER = "external_users_tool"

        fun from(permissions: ProjectPermissions, userId: String): ExternalUsersViewer {
            if (permissions.tools.isEmpty()) return ExternalUsersViewer(userId = userId)
            return ExternalUsersViewer(
                userId = userId,
                canView = permissions.canView(TOOL_IDENTIFIER),
                canPost = permissions.canPost(TOOL_IDENTIFIER),
                isAdmin = permissions.isAdmin,
                ready = true,
            )
        }
    }
}

/**
 * The form's rules, exactly the web's (`ExternalUserModal.jsx`): name and
 * email always; phone and dial code as a pair — either both or neither;
 * a typed type when the bucket is Others; department when the type is crew.
 * Answers a field→message map; empty means the draft may be sent.
 */
fun ExternalUser.validationErrors(): Map<String, String> = buildMap {
    if (fullName.isBlank()) put("fullName", "Please enter the full name")
    if (email.isBlank() || !email.contains('@') || !email.substringAfter('@').contains('.')) {
        put("email", "Please enter a valid email")
    }
    if (phone.isNotBlank() && countryCode.isBlank()) {
        put("countryCode", "Please select the country code")
    }
    if (countryCode.isNotBlank() && phone.isBlank()) put("phone", "Phone number is required")
    if (phone.isNotBlank() && phone.length < 5) put("phone", "Phone number is too short")
    if (phone.length > 15) put("phone", "Phone number is too long")
    if (userType.isBlank()) put("userType", "Please enter the type")
    if (userType == CREW_TYPE && departmentId.isBlank()) put("departmentId", "Select a department")
    otherInfo.forEachIndexed { index, row ->
        if (row.label.isBlank() != row.value.isBlank()) {
            put("otherInfo$index", "Both the title and the description are needed")
        }
    }
}

interface ExternalUsersRepository {
    /**
     * One page. [bucket] narrows by type — [ExternalUserBucket.All] must omit
     * the parameter entirely: the server answers an empty list to
     * `ExternalUserType=all` (ZL-17425, the web works around it; Android
     * still carries the bug).
     */
    suspend fun list(
        bucket: ExternalUserBucket,
        timestampMillis: Long,
        older: Boolean,
    ): ZillitResult<List<ExternalUser>>

    suspend fun create(user: ExternalUser): ZillitResult<Unit>

    suspend fun update(user: ExternalUser): ZillitResult<Unit>

    suspend fun delete(id: String): ZillitResult<Unit>
}
