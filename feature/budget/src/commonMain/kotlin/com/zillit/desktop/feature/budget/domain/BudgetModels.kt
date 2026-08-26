package com.zillit.desktop.feature.budget.domain

/**
 * Which budget a document belongs to.
 *
 * The wire spells these `main` and `department` (`BudgetPrimaryComponent.jsx:111,146`,
 * `BudgetView.jsx:78`). [Unknown] keeps a document the server invents tomorrow
 * visible rather than dropping it on the floor.
 */
enum class BudgetType(val wire: String) {
    Main("main"),
    Department("department"),
    Unknown(""),
    ;

    companion object {
        fun ofWire(raw: String?): BudgetType =
            entries.firstOrNull { it.wire == raw?.trim()?.lowercase() } ?: Unknown
    }
}

/** The file a budget document carries — the same attachment shape chat and the boards use. */
data class BudgetFile(
    val media: String,
    val name: String = "",
    val contentType: String = "",
    val bucket: String = "",
    val region: String = "",
    val sizeBytes: Long = 0,
) {
    /** A document whose file was deleted keeps its row but has nothing to open. */
    val isPresent: Boolean get() = media.isNotBlank()
}

/**
 * One budget document.
 *
 * A production has at most one *main* budget — the web replaces its whole list
 * with the single upload response (`BudgetView.jsx:84`) — and one *department*
 * budget per department.
 */
data class BudgetDocument(
    val id: String,
    val type: BudgetType,
    val departmentId: String = "",
    val departmentName: String = "",
    val file: BudgetFile? = null,
    val uploadedByName: String = "",
    val uploadedAtMillis: Long = 0,
)

/** Someone who can see a budget — the members list behind the people button. */
data class BudgetMember(
    val userId: String,
    val fullName: String,
    val profileMedia: String = "",
    val departmentName: String = "",
)
