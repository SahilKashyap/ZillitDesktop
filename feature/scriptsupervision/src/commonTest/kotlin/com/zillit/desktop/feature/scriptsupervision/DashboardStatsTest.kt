package com.zillit.desktop.feature.scriptsupervision

import com.zillit.desktop.feature.scriptsupervision.domain.DashboardStats
import com.zillit.desktop.feature.scriptsupervision.domain.Rec
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The dashboard's arithmetic.
 *
 * The page leads with percentages, and a percentage computed slightly
 * differently from the web's is the kind of wrong nobody notices — two
 * clients quietly disagreeing about how far a production has got.
 */
class DashboardStatsTest {

    private fun scene(id: String, status: String = "pending", day: Int = 0) = Rec(
        buildJsonObject {
            put("_id", id)
            put("status", status)
            put("shooting_day", day)
        },
    )

    private fun take(sceneId: String, circled: Boolean = false, ng: Boolean = false, mos: Boolean = false) = Rec(
        buildJsonObject {
            put("_id", "t_$sceneId${circled}_$ng$mos")
            put("scene_id", sceneId)
            put("is_circled", circled)
            put("is_ng", ng)
            put("is_mos", mos)
        },
    )

    private fun note(category: String, resolved: Boolean, priority: String = "low") = Rec(
        buildJsonObject {
            put("_id", "n_$category$resolved$priority")
            put("category", category)
            put("resolved", resolved)
            put("priority", priority)
        },
    )

    @Test
    fun `a percentage rounds the way the web rounds, and never divides by zero`() {
        assertEquals(50, DashboardStats.percent(1, 2))
        assertEquals(33, DashboardStats.percent(1, 3))
        assertEquals(67, DashboardStats.percent(2, 3))
        assertEquals(100, DashboardStats.percent(3, 3))
        // Nothing to be a share OF.
        assertEquals(0, DashboardStats.percent(0, 0))
        assertEquals(0, DashboardStats.percent(5, 0))
    }

    @Test
    fun `an empty production reports zeroes, not blanks or crashes`() {
        val stats = DashboardStats.of(emptyList(), emptyList(), null, emptyList(), emptyList())

        assertEquals(0, stats.sceneCount)
        assertEquals(0, stats.completionPercent)
        assertEquals(0, stats.ngPercent)
        assertEquals(emptyList(), stats.statusRows)
        assertEquals(emptyList(), stats.dayRows)
    }

    @Test
    fun `a scene with no status counts as pending`() {
        val noStatus = Rec(buildJsonObject { put("_id", "s1") })

        val stats = DashboardStats.of(listOf(noStatus), listOf(noStatus), null, emptyList(), emptyList())

        assertEquals(1, stats.pending)
        assertEquals(listOf("pending"), stats.statusRows.map { it.key })
    }

    @Test
    fun `only the statuses the production has get a row`() {
        val scenes = listOf(
            scene("s1", "completed"),
            scene("s2", "completed"),
            scene("s3", "in_progress"),
        )

        val stats = DashboardStats.of(scenes, scenes, null, emptyList(), emptyList())

        // No `pending`, `omitted` or `postponed` row: a row reading "0" is
        // noise on a page of headline numbers.
        assertEquals(listOf("in_progress", "completed"), stats.statusRows.map { it.key }.sorted().reversed())
        assertEquals(2, stats.completed)
        assertEquals(67, stats.completionPercent)
    }

    @Test
    fun `with a day picked, only that day's takes count`() {
        val dayScenes = listOf(scene("s1", day = 4))
        val all = dayScenes + scene("s2", day = 5)
        val takes = listOf(take("s1", circled = true), take("s2"), take("s2", ng = true))

        val picked = DashboardStats.of(dayScenes, all, day = 4, takes = takes, matching = emptyList())
        val whole = DashboardStats.of(all, all, day = null, takes = takes, matching = emptyList())

        assertEquals(1, picked.takeCount)
        assertEquals(0, picked.ng)
        // With no day picked, every take counts.
        assertEquals(3, whole.takeCount)
        assertEquals(1, whole.ng)
    }

    @Test
    fun `the leftover take count never goes negative when flags overlap`() {
        // A take can be circled AND MOS at once, so the three counts overlap
        // and a naive subtraction would report a negative "other".
        val scenes = listOf(scene("s1"))
        val overlapping = listOf(take("s1", circled = true, ng = true, mos = true))

        val stats = DashboardStats.of(scenes, scenes, null, overlapping, emptyList())

        val other = stats.takeRows.single { it.key == "other" }
        assertEquals(0, other.count)
    }

    @Test
    fun `per-day progress covers the whole production, not the picked day`() {
        val all = listOf(
            scene("s1", "completed", day = 1),
            scene("s2", "pending", day = 1),
            scene("s3", "completed", day = 2),
        )

        // Day 1 picked: the headline figures narrow, the table does not.
        val stats = DashboardStats.of(all.take(2), all, day = 1, takes = emptyList(), matching = emptyList())

        assertEquals(listOf(1, 2), stats.dayRows.map { it.day })
        assertEquals(50, stats.dayRows.first().share)
        assertEquals(100, stats.dayRows.last().share)
    }

    @Test
    fun `unscheduled scenes are left out of the per-day table, not gathered as day zero`() {
        val all = listOf(scene("s1", "completed", day = 1), scene("s2", "pending", day = 0))

        val stats = DashboardStats.of(all, all, null, emptyList(), emptyList())

        assertEquals(listOf(1), stats.dayRows.map { it.day })
    }

    @Test
    fun `matching notes group by category in the service's order, empties dropped`() {
        val notes = listOf(
            note("continuity_error", resolved = false, priority = "high"),
            note("prop_match", resolved = true),
            note("prop_match", resolved = false),
            // No category at all: the service's own fallback.
            Rec(buildJsonObject { put("_id", "n9"); put("resolved", false) }),
        )

        val stats = DashboardStats.of(emptyList(), emptyList(), null, emptyList(), notes)

        // MATCH_CATEGORIES order, not the order the notes arrived in.
        assertEquals(listOf("prop_match", "continuity_error", "other"), stats.categoryRows.map { it.category })
        val props = stats.categoryRows.first()
        assertEquals(1, props.resolved)
        assertEquals(1, props.open)
        assertEquals(2, props.total)
        // The headline counts every unresolved note, whatever its category.
        assertEquals(3, stats.openIssues)
        assertEquals(1, stats.highPriorityOpen)
    }
}
