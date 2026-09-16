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
 * One country's dialling code, for the form's code picker — the web's
 * `getCountryDetails` rows (`name (dial_code)`), read from the ISD preset.
 */
data class DialCode(val name: String, val dialCode: String, val isoCode: String = "") {
    val label: String get() = "$name ($dialCode)"
}

/**
 * A crew member the card's "Created By" line names — the web resolves
 * `created_by` against `usersList` for the full name and designation.
 */
data class Creator(val userId: String, val fullName: String, val designation: String = "")

/** `male` | `female` | `non-binary`, plus the legacy `other` (ZL-13367). */
enum class Gender(val wire: String, val label: String) {
    Male("male", "Male"),
    Female("female", "Female"),
    NonBinary("non-binary", "Non-binary"),
    ;

    companion object {
        /** The web's card rule: `other` reads as Non-binary, anything else as itself, upper-cased. */
        fun labelOf(wire: String): String = when (wire.lowercase()) {
            "" -> ""
            "other", NonBinary.wire -> NonBinary.label
            else -> entries.firstOrNull { it.wire == wire.lowercase() }?.label ?: wire.uppercase()
        }
    }
}

/** `+44 7700 900123` — the code and number the way both clients print them; blank when neither is set. */
val ExternalUser.phoneLine: String
    get() = listOf(countryCode, phone).filter { it.isNotBlank() }.joinToString(" ")

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
    if (!email.trim().looksLikeEmail()) put("email", "Please enter a valid email")
    if (phone.isNotBlank() && countryCode.isBlank()) {
        put("countryCode", "Please select the country code")
    }
    phoneError()?.let { put("phone", it) }
    if (userType.isBlank()) put("userType", "Please enter the type")
    if (userType == CREW_TYPE && departmentId.isBlank()) put("departmentId", "Select a department")
    otherInfo.forEachIndexed { index, row ->
        // A half-filled row is the error; both blank is simply an unused row.
        if (row.label.isBlank() != row.value.isBlank()) {
            put("otherInfo$index", "Both the title and the description are needed")
        }
    }
}

/** The web's check, not RFC 5322: something, an `@`, and a dot after it. */
private fun String.looksLikeEmail(): Boolean =
    isNotBlank() && contains('@') && substringAfter('@').contains('.')

/**
 * The one phone message, so the three rules cannot contradict each other by
 * writing to the same key in turn.
 */
private fun ExternalUser.phoneError(): String? = when {
    countryCode.isNotBlank() && phone.isBlank() -> "Phone number is required"
    phone.isNotBlank() && phone.length < MIN_PHONE_DIGITS -> "Phone number is too short"
    phone.length > MAX_PHONE_DIGITS -> "Phone number is too long"
    else -> null
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

/** Shorter than this is not a phone number anywhere we operate. */
private const val MIN_PHONE_DIGITS = 5

/** E.164 allows fifteen digits; longer is a typo rather than a number. */
private const val MAX_PHONE_DIGITS = 15
