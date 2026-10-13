package com.zillit.desktop.feature.purchaseorder.domain

/**
 * The Queue's "Assigned Depts" card — the departments the assignment rules
 * route to this viewer (`POQueue.jsx`'s `ruleDepts`), short-named the way the
 * card prints them (`DEPT_ABBR`).
 *
 * It is the rules, not the departments of the orders on screen: a rule for a
 * department with nothing in the queue today is still that person's.
 */
object PoQueueDepartments {
    private val abbreviations = mapOf(
        "Art Department" to "Art",
        "Camera" to "Cam",
        "Electrical" to "Elec",
        "Hair & Make-Up" to "H&MU",
        "Locations" to "Loc",
        "Post Production" to "Post",
        "Production" to "Prod",
        "Transport" to "Trans",
    )

    /** The card's short name for a department; one the web has no short form for stays whole. */
    fun abbreviate(name: String): String = abbreviations[name] ?: name

    /**
     * Ids of the departments in the active rules aimed at [userId], first seen
     * first. A rule is inactive when the server says so; an unaimed one is
     * nobody's.
     */
    fun departmentIds(rules: List<PoAssignmentRule>, userId: String): List<String> =
        rules.filter { it.isActive && it.assignTo.isNotBlank() && it.assignTo == userId }
            .flatMap { it.departments }
            .distinct()

    /**
     * What the web does for an id its department list cannot name: drop the
     * `department_` slug prefix and read the rest as words.
     */
    fun slugLabel(id: String): String =
        id.removePrefix("department_").split('_').filter { it.isNotBlank() }
            .joinToString(" ") { word -> word.replaceFirstChar { it.uppercase() } }
}
