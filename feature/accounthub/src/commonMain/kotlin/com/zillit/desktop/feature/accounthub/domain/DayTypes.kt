package com.zillit.desktop.feature.accounthub.domain

import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str

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
        DayType("SWD", str(S.desktop_standard_working_day), workMinutes = 600, mealBreakMinutes = 60),
        DayType("CWD", str(S.desktop_continuous_working_day), workMinutes = 540, mealBreakMinutes = 0),
        DayType("SCWD", str(S.desktop_hub_semi_continuous_working_day), workMinutes = 570, mealBreakMinutes = 30),
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
     * Whether the row at [index] is one of the three by **origin** — [seeded]
     * always puts them first, in order — rather than by whatever code a
     * custom row has had typed into it.
     */
    fun isSeededDefault(index: Int, rows: List<DayType>): Boolean =
        index in defaults.indices && rows.getOrNull(index)?.dayType == defaults[index].dayType

    /**
     * What the catalogue refuses to save, or null when it is ready.
     *
     * Two codes the same is the one that matters: the pay engine looks a day
     * type up by code, and a duplicate means one of them is never found.
     */
    fun problem(rows: List<DayType>): String? {
        if (rows.size > MAX_ROWS) return str(S.desktop_hub_a_project_can_have_at_most_n_day_types, MAX_ROWS)
        val seen = mutableSetOf<String>()
        return rows.firstNotNullOfOrNull { row -> rowProblem(row, seen) }
    }

    /** The first thing wrong with one row, or null. [seen] carries the codes so far. */
    private fun rowProblem(row: DayType, seen: MutableSet<String>): String? {
        val code = row.dayType.trim()
        return when {
            code.isEmpty() -> str(S.desktop_hub_every_day_type_needs_a_code)
            !seen.add(code) -> str(S.desktop_hub_day_type_code_used_twice, code)
            row.workMinutes == null -> str(S.desktop_hub_day_type_say_how_long_the_working_day_is, code)
            row.workMinutes !in 0..MAX_MINUTES ->
                str(S.desktop_hub_day_type_working_minutes_between_0_and_n, code, MAX_MINUTES)

            row.mealBreakMinutes != null && row.mealBreakMinutes !in 0..MAX_MINUTES ->
                str(S.desktop_hub_day_type_meal_break_between_0_and_n, code, MAX_MINUTES)

            else -> null
        }
    }
}
