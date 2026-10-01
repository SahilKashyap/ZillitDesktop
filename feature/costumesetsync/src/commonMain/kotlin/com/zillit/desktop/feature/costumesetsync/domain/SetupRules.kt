package com.zillit.desktop.feature.costumesetsync.domain

/**
 * The first-run rules (the web's `lib/setup.js`). "Not set up" and who may set up already live on
 * [SyncProject] (`notSetUp`, `canSetUp`); this holds the wizard's own: its steps and its dates.
 */
object SetupRules {
    /** The project's own type, when Zillit already says it — then the type step is skipped. */
    val KNOWN_TYPES = listOf("FEATURE", "EPISODIC")

    /** The wizard's steps: the type only when the project's type is unknown (step 1 of 3), else 1 of 2. */
    fun steps(type: String?): List<String> = if (type in KNOWN_TYPES) listOf("dates", "script") else listOf("type", "dates", "script")

    /** The six dates the production record holds. */
    val DATE_KEYS = listOf("start_date", "end_date", "prep_start_date", "prep_end_date", "wrap_date", "prep_wrap_date")

    /** The PATCH body for [dates] (`YYYY-MM-DD` or empty): epoch ms at local midnight, 0 = not set. */
    fun datesBody(dates: Map<String, String>): Map<String, Long> = DATE_KEYS.associateWith { DayKeys.toMs(dates[it].orEmpty()) }

    /** The saved dates of [project], as day keys (empty when unset), for the Setup tab to fill in. */
    fun savedDates(project: Rec?): Map<String, String> = DATE_KEYS.associateWith { DayKeys.of(project?.long(it)) }
}
