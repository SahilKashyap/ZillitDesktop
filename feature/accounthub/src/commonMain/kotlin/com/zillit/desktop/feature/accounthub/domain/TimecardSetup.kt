package com.zillit.desktop.feature.accounthub.domain

/**
 * Who completes the production's time cards — the web's Control Model A–D
 * (`TimecardSetupDetail`). Crew always hold final approval, whichever is set.
 */
enum class TimecardControlModel(val letter: String, val wire: String) {
    Production("A", "production"),
    Department("B", "department"),
    Crew("C", "crew"),
    Hybrid("D", "hybrid"),
    ;

    companion object {
        /** The web's default when nothing is stored: Hybrid. */
        fun fromWire(wire: String?): TimecardControlModel = entries.firstOrNull { it.wire == wire } ?: Hybrid
    }
}

/** The model a hybrid production falls back to for a department with no setting of its own. */
enum class HybridDefault(val letter: String, val wire: String) {
    Production("A", "production"),
    Department("B", "department"),
    Crew("C", "crew"),
    ;

    companion object {
        fun fromWire(wire: String?): HybridDefault = entries.firstOrNull { it.wire == wire } ?: Crew
    }
}

/** The `department_summary` slice that rides on the config read: how the departments are set up. */
data class TimecardDepartmentSummary(
    val configured: Int = 0,
    val completers: Int = 0,
    val departmentControlled: Int = 0,
    val crewControlled: Int = 0,
    val productionControlled: Int = 0,
    val completerIds: List<String> = emptyList(),
) {
    val isConfigured: Boolean get() = configured > 0

    /** Departments somebody completes the cards for, rather than the crew themselves. */
    val controlled: Int get() = departmentControlled + productionControlled

    val allControlledHaveCompleter: Boolean get() = controlled > 0 && completers >= controlled
}

/** The `approval_summary` slice: the default chain and the departments that override it. */
data class TimecardApprovalSummary(
    val defaultLevels: Int = 0,
    val customOverrides: Int = 0,
    val approverCount: Int = 0,
    val approverIds: List<String> = emptyList(),
) {
    val isConfigured: Boolean get() = defaultLevels > 0 || customOverrides > 0

    fun onDefault(totalDepartments: Int): Int = (totalDepartments - customOverrides).coerceAtLeast(0)
}

/**
 * The production's time card configuration.
 *
 * Only [model] and [hybridDefault] are edited here. [cadence] has no editor any
 * more but is carried and sent back verbatim: the save is a PUT of the whole
 * record, so dropping it would reset the project's cadence to "daily" on the
 * next save (the web's note in `TimecardSetupDetail`). The two summaries are
 * read-only context for the Department Setup and Approval Chain panes.
 */
data class TimecardSetup(
    val model: TimecardControlModel = TimecardControlModel.Hybrid,
    val hybridDefault: HybridDefault = HybridDefault.Crew,
    val cadence: String = DEFAULT_CADENCE,
    val departments: TimecardDepartmentSummary = TimecardDepartmentSummary(),
    val approvals: TimecardApprovalSummary = TimecardApprovalSummary(),
) {
    companion object {
        const val DEFAULT_CADENCE = "daily"
    }
}
