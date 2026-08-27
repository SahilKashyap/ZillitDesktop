package com.zillit.desktop.feature.castboard

import com.zillit.desktop.feature.castboard.data.CastingEntryDto
import com.zillit.desktop.feature.castboard.data.toEntry
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What the casting service says, read tolerantly. */
class CastingWireTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun parse(body: String) = json
        .decodeFromString(ListSerializer(CastingEntryDto.serializer()), body)
        .mapNotNull { it.toEntry() }

    /** A character with several candidates — the point of a shortlist. */
    @Test
    fun `a character carries every candidate`() {
        val rows = parse(
            """
            [{"_id":"c1","character_name":"Inspector Rao","talent_name":["Ravi Menon","Arjun Das"],
              "episode":"3","hierarchy":"Lead","gender":"Male",
              "attachment":{"media":"casting/c1.jpg","name":"rao.jpg","content_type":"image"}}]
            """.trimIndent(),
        )

        val entry = rows.single()
        assertEquals("Inspector Rao", entry.characterName)
        assertEquals(listOf("Ravi Menon", "Arjun Das"), entry.talentNames)
        assertEquals("casting/c1.jpg", entry.media?.media)
    }

    /**
     * `talent_name` has been seen as a bare string on older rows, which is
     * exactly the shape that breaks a strict decoder.
     */
    @Test
    fun `a single talent name is read as a list of one`() {
        val rows = parse("""[{"_id":"c2","character_name":"Nurse","talent_name":"Sara Ali"}]""")

        assertEquals(listOf("Sara Ali"), rows.single().talentNames)
    }

    /** No candidate yet is an ordinary state, not a broken row. */
    @Test
    fun `a character with nobody attached still reads`() {
        val rows = parse("""[{"_id":"c3","character_name":"Villager 2"}]""")

        assertTrue(rows.single().talentNames.isEmpty())
        assertNull(rows.single().media)
    }

    /** Every client hides deleted rows rather than showing tombstones. */
    @Test
    fun `deleted rows are dropped`() {
        val rows = parse(
            """[{"_id":"c4","character_name":"Cut","deleted":1},{"_id":"c5","character_name":"Kept","deleted":0}]""",
        )

        assertEquals(listOf("Kept"), rows.map { it.characterName })
    }

    /** An episode that arrives as a number reads the same as one that arrives as text. */
    @Test
    fun `an episode is read whatever its type`() {
        val numeric = parse("""[{"_id":"c6","character_name":"A","episode":7}]""").single()
        val listed = parse("""[{"_id":"c7","character_name":"B","episode":["1","2"]}]""").single()

        assertEquals("7", numeric.episode)
        assertEquals("1, 2", listed.episode)
    }

    /** A row nothing can address is dropped, not guessed at. */
    @Test
    fun `an id-less row is dropped`() {
        assertTrue(parse("""[{"character_name":"Ghost"}]""").isEmpty())
    }
}
