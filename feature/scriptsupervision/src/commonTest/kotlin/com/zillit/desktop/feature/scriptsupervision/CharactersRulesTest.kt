package com.zillit.desktop.feature.scriptsupervision

import com.zillit.desktop.feature.scriptsupervision.domain.Characters
import com.zillit.desktop.feature.scriptsupervision.domain.Rec
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The characters group's grouping rules — the ones a reader cannot check by
 * eye, because the service counts them the same way for the character
 * dashboard's tiles and a mismatch shows as a tile that opens the wrong list.
 */
class CharactersRulesTest {

    private fun scene(
        id: String,
        number: String,
        day: Int = 0,
        storyDay: String = "",
        characters: List<String> = emptyList(),
    ) = Rec(
        buildJsonObject {
            put("_id", id)
            put("scene_number", number)
            put("shooting_day", day)
            put("story_day", storyDay)
            put("characters", buildJsonArray { characters.forEach { add(JsonPrimitive(it)) } })
        },
    )

    private fun storyDay(label: String, order: Int?, sceneNumbers: List<String> = emptyList()) = Rec(
        buildJsonObject {
            put("label", label)
            order?.let { put("order", it) }
            put("scene_numbers", buildJsonArray { sceneNumbers.forEach { n -> add(JsonPrimitive(n)) } })
        },
    )

    @Test
    fun `a character's scenes are the named ones and the hand-linked ones`() {
        val character = Rec(
            buildJsonObject {
                put("_id", "c1")
                put("name", "RAVI")
                put("aliases", buildJsonArray { add(JsonPrimitive("THE BOY")) })
                // A scene whose script spells the name another way again.
                put("scene_ids", buildJsonArray { add(JsonPrimitive("s9")) })
            },
        )
        val scenes = listOf(
            scene("s1", "1", characters = listOf("RAVI")),
            scene("s2", "2", characters = listOf("MEERA")),
            scene("s3", "3", characters = listOf("THE BOY")),
            scene("s9", "9", characters = listOf("BOY (contd)")),
        )

        assertEquals(listOf("s1", "s3", "s9"), Characters.scenesFor(character, scenes).map { it.id })
    }

    @Test
    fun `a character with no record has no scenes`() {
        assertEquals(emptyList(), Characters.scenesFor(null, listOf(scene("s1", "1"))))
    }

    @Test
    fun `scene order is numeric, and a suffix follows its own number`() {
        val scenes = listOf(scene("a", "10"), scene("b", "2"), scene("c", "12A"), scene("d", "12"))

        assertEquals(
            listOf("2", "10", "12", "12A"),
            scenes.sortedWith(Characters.byNumber).map { it.str("scene_number") },
        )
    }

    @Test
    fun `a scene's story day is its own label, then a day that lists it, then unassigned`() {
        val days = listOf(storyDay("Story Day 4", 4, sceneNumbers = listOf("7")))

        assertEquals("Story Day 1", Characters.scriptDayOf(scene("s", "3", storyDay = "Story Day 1"), days))
        assertEquals("Story Day 4", Characters.scriptDayOf(scene("s", "7"), days))
        assertEquals(Characters.UNASSIGNED, Characters.scriptDayOf(scene("s", "99"), days))
    }

    @Test
    fun `story days sort by the record's order, then loose labels, then unassigned`() {
        val days = listOf(storyDay("Story Day 2", 2), storyDay("Story Day 1", 1))
        val scenes = listOf(
            // No story day at all: last.
            scene("s1", "1"),
            // A label no record owns: after every record, by its own number.
            scene("s2", "2", storyDay = "Day 7"),
            scene("s3", "3", storyDay = "Story Day 2"),
            scene("s4", "4", storyDay = "Story Day 1"),
            scene("s5", "5", storyDay = "Day 3"),
        )

        val groups = Characters.groupScriptDays(scenes, days)

        assertEquals(
            listOf("Story Day 1", "Story Day 2", "Day 3", "Day 7", Characters.UNASSIGNED),
            groups.map { it.label },
        )
        // The record travels with its group, so the card can show its type and date.
        assertEquals("Story Day 1", groups.first().record?.str("label"))
        assertTrue(groups.last().record == null)
    }

    @Test
    fun `a story-day record with no order of its own still sorts before a loose label`() {
        val days = listOf(storyDay("Prologue", null))
        val scenes = listOf(scene("s1", "1", storyDay = "Day 600"), scene("s2", "2", storyDay = "Prologue"))

        assertEquals(listOf("Prologue", "Day 600"), Characters.groupScriptDays(scenes, days).map { it.label })
    }

    @Test
    fun `shooting days sort by number with the unscheduled ones last`() {
        val scenes = listOf(
            scene("s1", "1", day = 3),
            scene("s2", "2", day = 0),
            scene("s3", "3", day = 1),
            scene("s4", "4", day = 3),
        )

        val groups = Characters.groupShootingDays(scenes)

        assertEquals(listOf(1, 3, 0), groups.map { it.day })
        assertEquals(listOf("s1", "s4"), groups[1].scenes.map { it.id })
    }

    @Test
    fun `a day label shortens to the way it is written on a slate`() {
        assertEquals("D3", Characters.shortDay("Story Day 3"))
        assertEquals("D3", Characters.shortDay("Day 3"))
        assertEquals("D3", Characters.shortDay("DAY 3"))
        // Nothing to shorten: left as it is rather than blanked.
        assertEquals("Prologue", Characters.shortDay("Prologue"))
        assertEquals("", Characters.shortDay(null))
    }

    @Test
    fun `a character written before character_type existed still reads as background`() {
        val old = Rec(buildJsonObject { put("importance", "background") })
        val new = Rec(buildJsonObject { put("character_type", "background") })
        val plain = Rec(JsonObject(emptyMap()))

        assertEquals("background", Characters.typeOf(old))
        assertEquals("background", Characters.typeOf(new))
        assertEquals("principal", Characters.typeOf(plain))
        assertEquals("principal", Characters.typeOf(null))
    }

    @Test
    fun `the primary photo wins, and the first one stands in for it`() {
        val marked = Rec(
            buildJsonObject {
                put(
                    "photos",
                    buildJsonArray {
                        add(buildJsonObject { put("url", "a.jpg") })
                        add(
                            buildJsonObject {
                                put("url", "b.jpg")
                                put("is_primary", true)
                            },
                        )
                    },
                )
            },
        )
        val unmarked = Rec(
            buildJsonObject {
                put("photos", buildJsonArray { add(buildJsonObject { put("url", "a.jpg") }) })
            },
        )

        assertEquals("b.jpg", Characters.primaryPhoto(marked))
        assertEquals("a.jpg", Characters.primaryPhoto(unmarked))
        assertEquals("", Characters.primaryPhoto(null))
    }
}
