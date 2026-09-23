package com.zillit.desktop.feature.auth.domain

import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str

/** A job title within a department. */
data class Designation(val id: String, val name: String)

/**
 * A production department, with the roles inside it.
 *
 * Designations arrive nested rather than fetched per department: the server
 * offers `departments?designations=true` for exactly this, and a round trip on
 * every dropdown change would make the form feel broken on a unit's hotel wifi.
 */
data class Department(
    val id: String,
    val name: String,
    val designations: List<Designation> = emptyList(),
)

/**
 * What a crew member tells a production when asking to join it.
 *
 * The shape is the server's, confirmed against both live clients: iOS's
 * `JoinUserRequestModel` and Android's `JoinProjectRequest` send the same
 * fields to the same endpoint.
 */
data class JoinDraft(
    val firstName: String = "",
    val lastName: String = "",
    val departmentId: String? = null,
    val designationId: String? = null,
    val unitId: String? = null,
    /**
     * Hides the name from crew lists.
     *
     * Offered because productions carry people who are not publicly attached to
     * them yet — an unannounced cast member on a call sheet is a leak.
     */
    val keepNamePrivate: Boolean = false,
    /**
     * The profile picture, once it has been stored.
     *
     * Holds the *uploaded* keys rather than the file: the picture is sent to
     * storage as soon as it is chosen, so the wait happens while the user is
     * still filling the form instead of being added to the submit. Null means
     * no picture, and the request then omits the field entirely — see
     * `toRequestDto`.
     */
    val photo: JoinPhoto? = null,
) {
    /** Never prints the name: join requests are logged on failure. */
    override fun toString(): String =
        "JoinDraft(hasName=${firstName.isNotBlank()}, dept=$departmentId, unit=$unitId, photo=${photo != null})"
}

/** Why a join request was refused before it was sent. */
enum class JoinFieldError {
    FirstNameTooShort,
    LastNameTooShort,
    DepartmentMissing,
    DesignationMissing,
    UnitMissing,
}

val JoinFieldError.message: String
    get() = when (this) {
        JoinFieldError.FirstNameTooShort -> str(S.desktop_first_name_too_short, MIN_NAME_LENGTH)
        JoinFieldError.LastNameTooShort -> str(S.desktop_last_name_too_short, MIN_NAME_LENGTH)
        JoinFieldError.DepartmentMissing -> str(S.desktop_choose_your_department)
        JoinFieldError.DesignationMissing -> str(S.desktop_choose_your_role)
        JoinFieldError.UnitMissing -> str(S.desktop_choose_your_unit)
    }

/**
 * Checks a join request before it is sent.
 *
 * ## Why personal productions are different
 *
 * A personal production has no departments, roles or units — it is one person's
 * own workspace. Both other clients skip those three checks for it, and asking
 * a solo user which unit they are on would be a question with no answer.
 *
 * The three-letter minimum on names is the other clients' rule too. It exists
 * because these names go on call sheets, and "Jo" versus "J" is the difference
 * between a crew list and a puzzle.
 */
fun JoinDraft.validate(isPersonalProduction: Boolean): Set<JoinFieldError> {
    val errors = linkedSetOf<JoinFieldError>()

    if (firstName.trim().length < MIN_NAME_LENGTH) errors += JoinFieldError.FirstNameTooShort
    if (lastName.trim().length < MIN_NAME_LENGTH) errors += JoinFieldError.LastNameTooShort

    if (!isPersonalProduction) {
        if (departmentId.isNullOrBlank()) errors += JoinFieldError.DepartmentMissing
        if (designationId.isNullOrBlank()) errors += JoinFieldError.DesignationMissing
        if (unitId.isNullOrBlank()) errors += JoinFieldError.UnitMissing
    }

    return errors
}

/** Matches the other clients: `FirstNameMinLimit` / `LastNameMinLimit`. */
const val MIN_NAME_LENGTH = 3
