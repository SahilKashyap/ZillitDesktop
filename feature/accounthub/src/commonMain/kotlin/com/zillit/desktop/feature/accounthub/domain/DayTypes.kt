package com.zillit.desktop.feature.accounthub.domain

/**
 * The project's day-type catalogue, for non-union deals.
 *
 * A non-union deal copies this list into its own `day_types` when it is saved;
 * a union deal takes its day types from the agreement instead, and offering
 * project-level ones there would quietly override a negotiated term.
 *
 * Saved through its own endpoint, a bare array that replaces the lot. That
 * matters: it means editing a day type never re-saves the overtime, premium
 * and penalty rules it is rendered beside.
 */
object DayTypes {

    /**
     * The three every production starts with.
     *
     * Seeded whether or not they have been saved, and their codes cannot be
     * changed — the pay engine reads them by code, and a renamed `SWD` is a
     * standard working day nothing recognises.
     */
    val defaults: List<DayType> = listOf(
        DayType("SWD", "Standard Working Day", workMinutes = 600, mealBreakMinutes = 60),
        DayType("CWD", "Continuous Working Day", workMinutes = 540, mealBreakMinutes = 0),
        DayType("SCWD", "Semi-Continuous Working Day", workMinutes = 570, mealBreakMinutes = 30),
    )

    val defaultCodes: Set<String> = defaults.map { it.dayType }.toSet()

    /** A project may not carry more than this many. */
    const val MAX_ROWS = 100

    /** A day cannot be longer than one. */
    const val MAX_MINUTES = 1440

    /**
     * The saved catalogue with the three defaults always in front.
     *
     * A saved row wins over the seeded default of the same code, so a
     * production that shortened its standard day keeps the shorter one. Custom
     * rows follow in the order they were saved.
     */
    fun seeded(saved: List<DayType>): List<DayType> {
        val byCode = saved.filter { it.dayType.isNotBlank() }.associateBy { it.dayType }
        val seededDefaults = defaults.map { byCode[it.dayType] ?: it }
        return seededDefaults + saved.filterNot { it.dayType in defaultCodes }
    }

    /** Whether a row is one of the three, by its code. */
    fun isDefault(dayType: DayType): Boolean = dayType.dayType in defaultCodes

    /**
     * What the catalogue refuses to save, or null when it is ready.
     *
     * Two codes the same is the one that matters: the pay engine looks a day
     * type up by code, and a duplicate means one of them is never found.
     */
    fun problem(rows: List<DayType>): String? {
        if (rows.size > MAX_ROWS) return "A project can have at most $MAX_ROWS day types."
        val seen = mutableSetOf<String>()
        return rows.firstNotNullOfOrNull { row -> rowProblem(row, seen) }
    }

    /** The first thing wrong with one row, or null. [seen] carries the codes so far. */
    private fun rowProblem(row: DayType, seen: MutableSet<String>): String? {
        val code = row.dayType.trim()
        return when {
            code.isEmpty() -> "Every day type needs a code."
            !seen.add(code) -> "\"$code\" is used twice. Each code must be its own."
            row.workMinutes == null -> "\"$code\": say how long the working day is."
            row.workMinutes !in 0..MAX_MINUTES ->
                "\"$code\": working minutes must be between 0 and $MAX_MINUTES."

            row.mealBreakMinutes != null && row.mealBreakMinutes !in 0..MAX_MINUTES ->
                "\"$code\": the meal break must be between 0 and $MAX_MINUTES minutes."

            else -> null
        }
    }
}
