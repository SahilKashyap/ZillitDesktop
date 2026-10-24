package com.zillit.desktop.feature.scriptsupervision.domain

import kotlin.math.roundToInt

/**
 * The dashboard's figures — the web's `screens/reports/dashboardStats.js`.
 *
 * All of it is arithmetic over lists the tool already holds, which is why it
 * is here and tested rather than inside the screen: the page leads with
 * percentages, and a percentage computed two slightly different ways is the
 * kind of wrong nobody notices.
 *
 * ⚠ The counting rule the web encodes and this keeps: the figures are about
 * the PICKED SHOOTING DAY when one is picked, and about the production when
 * none is. `allScenes` is separate because the per-day table always covers
 * the whole production — otherwise picking day 4 would show a table of one
 * row.
 */
object DashboardStats {

    /** The matching-note categories, in the web's order. */
    val MATCH_CATEGORIES: List<String> = listOf(
        "prop_match",
        "wardrobe_match",
        "eyeline_match",
        "action_overlap",
        "continuity_error",
        "other",
    )

    /**
     * A whole percent, and 0 rather than a division by zero.
     *
     * ⚠ ROUNDED, not truncated — the web uses `Math.round`. Truncating is
     * the easy mistake and it is quietly wrong: two scenes of three is 67%
     * there and would be 66% here, so the two clients would disagree about
     * how far a production has got. Caught by the test, which is why it is
     * tested at all. Percentages here are never negative, so Kotlin's
     * half-away-from-zero and JS's half-up agree.
     */
    fun percent(part: Int, whole: Int): Int =
        if (whole > 0) ((part.toDouble() / whole) * FULL).roundToInt().coerceIn(0, FULL) else 0

    /**
     * Everything the dashboard shows.
     *
     * [scenes] are the picked day's (or all of them), [allScenes] the whole
     * production for the per-day table, [takes] every take, and [matching]
     * every matching note.
     */
    fun of(scenes: List<Rec>, allScenes: List<Rec>, day: Int?, takes: List<Rec>, matching: List<Rec>): Stats {
        val sceneIds = scenes.map { it.id }.toSet()
        // Takes count for the PICKED day's scenes; with no day picked, all.
        val dayTakes = if (day != null) takes.filter { it.str("scene_id") in sceneIds } else takes

        val byStatus = { status: String ->
            scenes.count { it.str("status").ifBlank { "pending" } == status }
        }
        val completed = byStatus("completed")

        val circled = dayTakes.count { it.bool("is_circled") }
        val ng = dayTakes.count { it.bool("is_ng") }
        val mos = dayTakes.count { it.bool("is_mos") }
        val open = matching.count { !it.bool("resolved") }

        return Stats(
            sceneCount = scenes.size,
            completed = completed,
            inProgress = byStatus("in_progress"),
            pending = byStatus("pending"),
            completionPercent = percent(completed, scenes.size),
            takeCount = dayTakes.size,
            ng = ng,
            ngPercent = percent(ng, dayTakes.size),
            openIssues = open,
            highPriorityOpen = matching.count { it.str("priority") == "high" && !it.bool("resolved") },
            // Only the statuses this production actually has: a row reading
            // "omitted 0" is noise on a page of headline numbers.
            statusRows = ScriptSupervision.SCENE_STATUSES
                .map { Share(it, byStatus(it), percent(byStatus(it), scenes.size)) }
                .filter { it.count > 0 },
            takeRows = listOf(
                Share("is_circled", circled, percent(circled, dayTakes.size)),
                Share("is_ng", ng, percent(ng, dayTakes.size)),
                Share("is_mos", mos, percent(mos, dayTakes.size)),
                // Whatever is left: never negative, because a take can be
                // circled AND MOS at once and the three overlap.
                ("other" to (dayTakes.size - circled - ng - mos).coerceAtLeast(0)).let { (key, count) ->
                    Share(key, count, percent(count, dayTakes.size))
                },
            ),
            dayRows = dayProgress(allScenes),
            categoryRows = categoryProgress(matching),
        )
    }

    /**
     * Each shooting day's scenes and how many are done, in day order.
     *
     * Scenes with no shooting day are left out entirely rather than gathered
     * into a day 0 — they are unscheduled, which is not a day's progress.
     */
    private fun dayProgress(allScenes: List<Rec>): List<DayProgress> {
        val byDay = LinkedHashMap<Int, MutableList<Rec>>()
        allScenes.forEach { scene ->
            val day = scene.int("shooting_day")
            if (day > 0) byDay.getOrPut(day) { mutableListOf() }.add(scene)
        }
        return byDay.entries
            .sortedBy { it.key }
            .map { (day, its) ->
                val done = its.count { it.str("status") == "completed" }
                DayProgress(day, its.size, done, percent(done, its.size))
            }
    }

    /**
     * The matching notes by category, resolved against open.
     *
     * In [MATCH_CATEGORIES] order, and only the categories that have notes —
     * the web filters the empties out for the same reason the statuses are
     * filtered.
     */
    private fun categoryProgress(matching: List<Rec>): List<CategoryProgress> {
        val counts = matching.groupBy { it.str("category").ifBlank { "other" } }
        return MATCH_CATEGORIES.mapNotNull { category ->
            val notes = counts[category] ?: return@mapNotNull null
            val resolved = notes.count { it.bool("resolved") }
            CategoryProgress(category, resolved, notes.size - resolved)
        }
    }

    private const val FULL = 100
}

/** The dashboard's figures. */
data class Stats(
    val sceneCount: Int,
    val completed: Int,
    val inProgress: Int,
    val pending: Int,
    val completionPercent: Int,
    val takeCount: Int,
    val ng: Int,
    val ngPercent: Int,
    val openIssues: Int,
    val highPriorityOpen: Int,
    val statusRows: List<Share>,
    val takeRows: List<Share>,
    val dayRows: List<DayProgress>,
    val categoryRows: List<CategoryProgress>,
)

/** One slice of a whole: what it is, how many, and what share of the total. */
data class Share(val key: String, val count: Int, val share: Int)

/** One shooting day's work. */
data class DayProgress(val day: Int, val scenes: Int, val completed: Int, val share: Int)

/** One matching category's notes, settled against outstanding. */
data class CategoryProgress(val category: String, val resolved: Int, val open: Int) {
    val total: Int get() = resolved + open
}
