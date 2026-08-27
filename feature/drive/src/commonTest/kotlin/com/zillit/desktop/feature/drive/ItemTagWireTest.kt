package com.zillit.desktop.feature.drive

import com.zillit.desktop.feature.drive.data.ItemTagDto
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `/tags/item-tags` returns **joins, not tags**.
 *
 * Found live 2026-08-27: reading the row as a tag put the *join's* `_id`
 * where the tag id belonged and found no `name`, so a freshly applied tag
 * rendered as "#Tag", stayed in the "add" list because the ids never
 * matched, and would have been un-removable — the remove call carried an id
 * the server does not know.
 *
 * The server sends `tag_id` populated on some routes and bare on others; the
 * web branches on exactly that (`FileDetailsPanel.jsx:732`).
 */
class ItemTagWireTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun decode(body: String) =
        json.decodeFromString(ListSerializer(ItemTagDto.serializer()), body).mapNotNull { it.toDomain() }

    @Test
    fun `a populated join yields the tag, not the join row`() {
        val tags = decode(
            """
            [{"_id":"join-1","item_id":"f1","item_type":"file",
              "tag_id":{"_id":"tag-9","name":"Approved","color":"#22aa55"}}]
            """.trimIndent(),
        )

        val tag = tags.single()
        assertEquals("tag-9", tag.id, "the join's own id must never stand in for the tag's")
        assertEquals("Approved", tag.name)
        assertEquals("#22aa55", tag.color)
    }

    @Test
    fun `a bare join yields the tag id with no name`() {
        val tags = decode("""[{"_id":"join-1","tag_id":"tag-9"}]""")

        val tag = tags.single()
        assertEquals("tag-9", tag.id)
        // Blank, not a placeholder: an unresolved name must look unresolved
        // rather than quietly read as a tag called "Tag".
        assertEquals("", tag.name)
    }

    @Test
    fun `both spellings resolve to the same tag id`() {
        val populated = decode("""[{"_id":"j1","tag_id":{"_id":"tag-9","name":"Approved"}}]""")
        val bare = decode("""[{"_id":"j2","tag_id":"tag-9"}]""")

        assertEquals(populated.single().id, bare.single().id)
    }

    @Test
    fun `a join with no tag at all is dropped`() {
        assertTrue(decode("""[{"_id":"join-1","item_id":"f1"}]""").isEmpty())
        assertTrue(decode("""[{"_id":"join-1","tag_id":null}]""").isEmpty())
        assertTrue(decode("""[{"_id":"join-1","tag_id":""}]""").isEmpty())
    }

    @Test
    fun `a populated tag without an id is dropped rather than half-drawn`() {
        assertTrue(decode("""[{"_id":"j1","tag_id":{"name":"Approved"}}]""").isEmpty())
    }

    @Test
    fun `an empty answer is empty, not an error`() {
        assertTrue(decode("[]").isEmpty())
    }

    @Test
    fun `a mixed answer reads both shapes at once`() {
        val tags = decode(
            """
            [{"_id":"j1","tag_id":{"_id":"tag-1","name":"Approved"}},
             {"_id":"j2","tag_id":"tag-2"}]
            """.trimIndent(),
        )

        assertEquals(listOf("tag-1", "tag-2"), tags.map { it.id })
        assertEquals(listOf("Approved", ""), tags.map { it.name })
    }

    /** Unknown keys on the join must not fail the read — it grows fields. */
    @Test
    fun `extra keys are ignored`() {
        val tags = decode(
            """[{"_id":"j1","tag_id":"tag-9","created_by":"u1","created":123,"scope":"file"}]""",
        )

        assertEquals("tag-9", tags.single().id)
    }

    @Test
    fun `a numeric tag_id is not mistaken for a tag`() {
        // Defensive: a primitive that is not a string still yields its content
        // rather than throwing, so a shape change degrades to a nameless tag.
        val tags = decode("""[{"_id":"j1","tag_id":42}]""")

        assertEquals("42", tags.single().id)
        assertNull(tags.singleOrNull()?.name?.takeIf { it.isNotBlank() })
    }
}
