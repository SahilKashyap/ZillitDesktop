package com.zillit.desktop.feature.dealmemo.domain

/** Someone on the production's user list, as the deal lists look them up. */
data class DealPerson(
    val userId: String,
    val fullName: String,
    /** The directory's department — a translation key or a name. */
    val departmentName: String? = null,
    val designationName: String? = null,
    /** Their standing: accepted, pending, left, removed… Null when unknown. */
    val status: String? = null,
)

/**
 * A production member as the deal builder picks crew (`GET /v2/project/users`):
 * the directory's identifiers for the role auto-select, the master ids the
 * deal is scoped by, and the unit they joined.
 */
data class DealCrewUser(
    val userId: String,
    val fullName: String,
    val status: String? = null,
    val departmentIdentifier: String? = null,
    val designationIdentifier: String? = null,
    val departmentName: String? = null,
    val designationName: String? = null,
    /** The master department and designation `_id`s the deal's top-level keys carry. */
    val departmentId: String? = null,
    val designationId: String? = null,
    val joinUnitId: String? = null,
) {
    /** Accepted members and invited ones not yet joined — the Crew Member picker's pool. */
    val pickable: Boolean get() = status == "accepted" || status == "pending"
}

/** Name, department and role for one deal row. */
data class DealPersonLabels(val name: String, val department: String, val role: String)

/**
 * The web's person resolver (`dealCrew.js:98-136`) and label helpers
 * (`data/utils.js:86-140`) — one set of rules for every list.
 *
 * The name is directory-first (a person renamed on the production reads their
 * current name), the department and role deal-first (what the deal was written
 * for). Every helper answers the em dash for a miss, and a candidate only
 * counts when it is neither blank nor that dash.
 */
class DealCrewLabels(
    private val people: Map<String, DealPerson> = emptyMap(),
    private val catalogue: DepartmentCatalogue = DepartmentCatalogue(),
    private val translate: (String) -> String? = DealLabels.translation,
) {

    fun person(userId: String?): DealPerson? = userId?.let(people::get)

    fun labels(deal: DealDoc): DealPersonLabels {
        val user = person(deal.userId)
        return DealPersonLabels(
            name = firstResolved(user?.fullName, deal.crewName, deal.fullLegalName),
            department = firstResolved(
                departmentLabel(deal.crewDepartmentRef),
                departmentLabel(deal.departmentId),
                user?.departmentName?.takeIf { it.isNotEmpty() }?.let { DealLabels.formatLabel(it, translate) },
            ),
            role = firstResolved(
                deal.customDesignation,
                designationLabel(deal.crewDesignationRef),
                designationLabel(deal.designationId),
                user?.designationName?.takeIf { it.isNotEmpty() }?.let { DealLabels.formatLabel(it, translate) },
            ),
        )
    }

    /** `departmentLabel(ref)`: master by `_id`, then identifier; a bare identifier humanised; else a dash. */
    fun departmentLabel(ref: String?): String {
        if (ref.isNullOrEmpty()) return DASH
        catalogue.departments.firstOrNull { it.id == ref }?.let { return DealLabels.formatLabel(it.nameKey, translate) }
        catalogue.departments.firstOrNull { it.identifier == ref }
            ?.let { return DealLabels.formatLabel(it.nameKey, translate) }
        return if (ref.startsWith(DEPARTMENT_PREFIX)) {
            DealLabels.formatLabel(ref.removePrefix(DEPARTMENT_PREFIX), translate)
        } else {
            DASH
        }
    }

    /**
     * `designationLabel(ref)`: any department's role by `_id` or identifier;
     * a bare identifier loses its prefix and the trailing department slug the
     * rate-card taxonomy appends — but only a department this production has.
     */
    fun designationLabel(ref: String?): String {
        if (ref.isNullOrEmpty()) return DASH
        catalogue.departments.forEach { department ->
            department.designations.firstOrNull { it.id == ref || it.identifier == ref }
                ?.let { return DealLabels.formatLabel(it.nameKey, translate) }
        }
        if (!ref.startsWith(DESIGNATION_PREFIX)) return DASH
        val bare = ref.removePrefix(DESIGNATION_PREFIX)
        val slug = catalogue.departments
            .map { it.identifier.removePrefix(DEPARTMENT_PREFIX) }
            .filter { it.isNotEmpty() }
            .firstOrNull { bare.endsWith("_$it") }
        return DealLabels.formatLabel(slug?.let { bare.dropLast(it.length + 1) } ?: bare, translate)
    }

    private fun firstResolved(vararg candidates: String?): String =
        candidates.firstOrNull { !it.isNullOrEmpty() && it != DASH } ?: DASH

    companion object {
        const val DASH = "—"
        private const val DEPARTMENT_PREFIX = "department_"
        private const val DESIGNATION_PREFIX = "designation_"
    }
}
